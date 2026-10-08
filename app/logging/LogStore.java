package logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Durable, size-capped store of everything the server logs, so admins can read and search it
 * from the browser instead of over SSH.
 *
 * <p>Entries are appended as JSON lines to {@code console-<startSeq>.jsonl} files in {@code dir}.
 * A new file is started once the current one passes {@code maxFileBytes}, and the oldest files
 * are deleted beyond {@code maxFiles}. Files are never renamed, so readers can scan them without
 * holding the store lock. The newest {@code memoryEntries} entries are also kept in memory so the
 * common case (tailing, polling for new lines) never touches the disk.
 *
 * <p>Logging threads never wait on the disk: {@link #append} only queues the entry, and a single
 * background thread writes queued entries in batches. Reads treat queued entries as part of the
 * in-memory window, so nothing is missing between "evicted from memory" and "written to disk". If
 * the writer falls more than {@code MAX_PENDING} entries behind, further entries are kept in
 * memory only (and the drop is reported) rather than growing the queue without bound.
 *
 * <p>Reads are windowed: {@link #before} pages backwards from a cursor and {@link #after} pages
 * forwards, each returning at most {@code limit} matching entries plus a cursor to continue
 * from. A single read scans at most {@code scanBudgetBytes} of log files, so a search over a
 * large history comes back in chunks rather than tying up a request thread.
 */
public class LogStore {
  private static final String PREFIX = "console-";
  private static final String SUFFIX = ".jsonl";
  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final int MAX_PENDING = 100000;
  private static final int MAX_BATCH = 1000;

  private final File dir;
  private final long maxFileBytes;
  private final int maxFiles;
  private final int memoryEntries;
  private final long scanBudgetBytes;

  // Guarded by this.
  private final ArrayDeque<LogEntry> recent = new ArrayDeque<LogEntry>();
  /** Appended but not yet on disk, oldest first. Removed only once written, see writeLoop. */
  private final ArrayDeque<LogEntry> pending = new ArrayDeque<LogEntry>();
  private long nextSeq = 1;
  private long droppedFromDisk = 0;
  private boolean diskFailed = false;
  private boolean closed = false;
  private volatile PrintStream errorSink = System.err;

  // Owned by the writer thread (and the constructor, before it starts).
  private File currentFile;
  private OutputStream currentOut;
  private long currentBytes;
  private final Thread writer;

  public LogStore(File dir, long maxFileBytes, int maxFiles, int memoryEntries, long scanBudgetBytes) {
    this.dir = dir;
    this.maxFileBytes = maxFileBytes;
    this.maxFiles = Math.max(1, maxFiles);
    this.memoryEntries = Math.max(1, memoryEntries);
    this.scanBudgetBytes = scanBudgetBytes;
    dir.mkdirs();
    recover();
    writer = new Thread(new Runnable() {
      public void run() {
        writeLoop();
      }
    }, "breadboard-log-writer");
    writer.setDaemon(true);
    writer.start();
  }

  /** Where disk-write failures are reported. Must not route back into this store. */
  public void setErrorSink(PrintStream errorSink) {
    this.errorSink = errorSink;
  }

  public synchronized LogEntry append(long ts, String level, String source, String msg) {
    LogEntry e = new LogEntry(nextSeq++, ts, level, source, msg);
    recent.addLast(e);
    if (recent.size() > memoryEntries) {
      recent.removeFirst();
    }
    if (!closed && !diskFailed) {
      if (pending.size() >= MAX_PENDING) {
        droppedFromDisk++;
      } else {
        pending.addLast(e);
        if (pending.size() == 1) notifyAll();
      }
    }
    return e;
  }

  public synchronized long latestSeq() {
    return nextSeq - 1;
  }

  /** Blocks until everything appended so far is on disk (or disk writes have failed). */
  public synchronized void flush() throws InterruptedException {
    while (!pending.isEmpty() && !diskFailed && writer.isAlive()) {
      wait(100);
    }
  }

  /**
   * Writes out what is queued, then stops disk writes for good, so a capture stream that outlives
   * its install can't reopen the file.
   */
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      notifyAll();
    }
    try {
      writer.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ---------------------------------------------------------------------------------------
  // Reading

  /** Matches entries at or above a minimum level whose message or source contains a query (case-insensitive). */
  public static class Filter {
    public static final Filter ALL = new Filter(null, null);
    private final String query;
    private final int minLevel;

    public Filter(String query, String minLevel) {
      this.query = (query == null || query.isEmpty()) ? null : query.toLowerCase(Locale.ROOT);
      this.minLevel = (minLevel == null || minLevel.isEmpty()) ? -1 : LogEntry.levelRank(minLevel);
    }

    public boolean matches(LogEntry e) {
      if (minLevel >= 0 && LogEntry.levelRank(e.level) < minLevel) return false;
      if (query == null) return true;
      return (e.msg != null && e.msg.toLowerCase(Locale.ROOT).contains(query))
          || (e.source != null && e.source.toLowerCase(Locale.ROOT).contains(query));
    }
  }

  public static class Page {
    /** Matching entries, oldest first. */
    public final List<LogEntry> entries;
    /** Whether more entries may exist past {@link #cursor} in the direction that was read. */
    public final boolean hasMore;
    /**
     * Every seq between the request cursor and this one has been examined; pass it back as the
     * next {@code before} (for {@link #before}) or {@code after} (for {@link #after}).
     */
    public final long cursor;
    public final long latestSeq;

    Page(List<LogEntry> entries, boolean hasMore, long cursor, long latestSeq) {
      this.entries = entries;
      this.hasMore = hasMore;
      this.cursor = cursor;
      this.latestSeq = latestSeq;
    }
  }

  private static class Snapshot {
    List<LogEntry> recent;
    List<File> files;
    long latestSeq;
    long oldestSeq;
    long memOldest;
  }

  private Snapshot snapshot() {
    Snapshot s = new Snapshot();
    synchronized (this) {
      // Queued entries already evicted from `recent` aren't on disk yet: count them as in memory.
      long recentOldest = recent.isEmpty() ? nextSeq : recent.peekFirst().seq;
      s.recent = new ArrayList<LogEntry>();
      for (LogEntry e : pending) {
        if (e.seq >= recentOldest) break;
        s.recent.add(e);
      }
      s.recent.addAll(recent);
      s.latestSeq = nextSeq - 1;
    }
    // Listed after the memory snapshot and outside the lock: every entry below memOldest was
    // written before this point, so its file is listed. Newer or since-pruned files are harmless.
    s.files = listFiles();
    s.memOldest = s.recent.isEmpty() ? s.latestSeq + 1 : s.recent.get(0).seq;
    s.oldestSeq = s.files.isEmpty() ? s.memOldest : Math.min(startSeqOf(s.files.get(0)), s.memOldest);
    return s;
  }

  /** Up to {@code limit} matching entries with seq &lt; {@code before}, newest first in scan order. */
  public Page before(long before, int limit, Filter filter) {
    Snapshot s = snapshot();
    LinkedList<LogEntry> out = new LinkedList<LogEntry>();
    long cursor = Math.min(before, s.latestSeq + 1);

    for (int i = s.recent.size() - 1; i >= 0; i--) {
      LogEntry e = s.recent.get(i);
      if (e.seq >= cursor) continue;
      cursor = e.seq;
      if (filter.matches(e)) {
        out.addFirst(e);
        if (out.size() >= limit) return new Page(out, cursor > s.oldestSeq, cursor, s.latestSeq);
      }
    }
    // Everything from memOldest up has been examined (or was never there).
    cursor = Math.min(cursor, s.memOldest);

    long budget = scanBudgetBytes;
    for (int i = s.files.size() - 1; i >= 0; i--) {
      File file = s.files.get(i);
      long start = startSeqOf(file);
      if (start >= cursor) continue;
      if (budget <= 0) break;
      budget -= file.length();
      List<LogEntry> entries = readFile(file);
      for (int j = entries.size() - 1; j >= 0; j--) {
        LogEntry e = entries.get(j);
        if (e.seq >= cursor) continue;
        cursor = e.seq;
        if (filter.matches(e)) {
          out.addFirst(e);
          if (out.size() >= limit) return new Page(out, cursor > s.oldestSeq, cursor, s.latestSeq);
        }
      }
      cursor = Math.min(cursor, start);
    }
    return new Page(out, cursor > s.oldestSeq, cursor, s.latestSeq);
  }

  /** Up to {@code limit} matching entries with seq &gt; {@code after}, oldest first. */
  public Page after(long after, int limit, Filter filter) {
    Snapshot s = snapshot();
    List<LogEntry> out = new ArrayList<LogEntry>();
    long cursor = Math.max(after, s.oldestSeq - 1);

    if (cursor + 1 < s.memOldest) {
      long budget = scanBudgetBytes;
      for (int i = 0; i < s.files.size(); i++) {
        File file = s.files.get(i);
        long start = startSeqOf(file);
        long nextStart = (i + 1 < s.files.size()) ? startSeqOf(s.files.get(i + 1)) : Long.MAX_VALUE;
        if (nextStart <= cursor + 1) continue;
        if (start >= s.memOldest) break;
        if (budget <= 0) return new Page(out, true, cursor, s.latestSeq);
        budget -= file.length();
        for (LogEntry e : readFile(file)) {
          if (e.seq <= cursor) continue;
          if (e.seq >= s.memOldest) break;
          cursor = e.seq;
          if (filter.matches(e)) {
            out.add(e);
            if (out.size() >= limit) return new Page(out, cursor < s.latestSeq, cursor, s.latestSeq);
          }
        }
        cursor = Math.max(cursor, Math.min(nextStart, s.memOldest) - 1);
      }
    }

    for (LogEntry e : s.recent) {
      if (e.seq <= cursor) continue;
      cursor = e.seq;
      if (filter.matches(e)) {
        out.add(e);
        if (out.size() >= limit) return new Page(out, cursor < s.latestSeq, cursor, s.latestSeq);
      }
    }
    return new Page(out, false, Math.max(cursor, s.latestSeq), s.latestSeq);
  }

  // ---------------------------------------------------------------------------------------
  // Files

  private List<File> listFiles() {
    File[] files = dir.listFiles(new FilenameFilter() {
      public boolean accept(File d, String name) {
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && startSeqOf(name) >= 0;
      }
    });
    List<File> list = files == null ? new ArrayList<File>() : new ArrayList<File>(Arrays.asList(files));
    Collections.sort(list, new Comparator<File>() {
      public int compare(File a, File b) {
        return Long.compare(startSeqOf(a), startSeqOf(b));
      }
    });
    return list;
  }

  private static long startSeqOf(File f) {
    return startSeqOf(f.getName());
  }

  private static long startSeqOf(String name) {
    try {
      return Long.parseLong(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private File fileFor(long startSeq) {
    // Zero-padded so a plain directory listing sorts chronologically too.
    return new File(dir, PREFIX + String.format("%019d", startSeq) + SUFFIX);
  }

  /** Every parseable entry in the file, in order. A torn last line (crash mid-write) is skipped. */
  static List<LogEntry> readFile(File file) {
    List<LogEntry> entries = new ArrayList<LogEntry>();
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) continue;
        try {
          entries.add(LogEntry.fromMap(mapper.readValue(line, Map.class)));
        } catch (Exception ignored) {
          // Partial or corrupt line.
        }
      }
    } catch (IOException ignored) {
      // Pruned while we were reading it, or unreadable: treat as empty.
    } finally {
      if (reader != null) {
        try { reader.close(); } catch (IOException ignored) { }
      }
    }
    return entries;
  }

  /** Continue the seq numbering from the newest file, so cursors stay valid across restarts. */
  private void recover() {
    List<File> files = listFiles();
    if (files.isEmpty()) return;
    File last = files.get(files.size() - 1);
    long maxSeq = startSeqOf(last) - 1;
    for (LogEntry e : readFile(last)) {
      maxSeq = Math.max(maxSeq, e.seq);
    }
    nextSeq = maxSeq + 1;
    if (last.length() < maxFileBytes) {
      currentFile = last;
    }
  }

  private void writeLoop() {
    try {
      while (true) {
        List<LogEntry> batch = new ArrayList<LogEntry>();
        long dropped;
        synchronized (this) {
          while (pending.isEmpty() && !closed) {
            try {
              wait();
            } catch (InterruptedException e) {
              return;
            }
          }
          if (pending.isEmpty()) return;  // closed and drained
          for (LogEntry e : pending) {
            batch.add(e);
            if (batch.size() >= MAX_BATCH) break;
          }
          dropped = droppedFromDisk;
          droppedFromDisk = 0;
        }
        if (dropped > 0) {
          errorSink.println("Admin log store: " + dropped + " entries were not written to " + dir
              + " because the disk writer fell behind");
        }
        boolean ok = writeBatch(batch);
        synchronized (this) {
          // Only now are these readable from disk, so only now do they leave the in-memory window.
          for (int i = 0; i < batch.size(); i++) pending.removeFirst();
          if (!ok) {
            diskFailed = true;
            pending.clear();
          }
          notifyAll();
        }
        if (!ok) return;
      }
    } finally {
      closeCurrent();
    }
  }

  private boolean writeBatch(List<LogEntry> batch) {
    try {
      for (LogEntry e : batch) {
        if (currentOut == null || currentBytes >= maxFileBytes) {
          if (currentOut == null && currentFile != null && currentFile.length() < maxFileBytes) {
            openCurrent(currentFile);
          } else {
            roll(e.seq);
          }
        }
        byte[] bytes = (mapper.writeValueAsString(e.toMap()) + "\n").getBytes(UTF_8);
        currentOut.write(bytes);
        currentBytes += bytes.length;
      }
      currentOut.flush();
      return true;
    } catch (IOException ex) {
      // Keep serving from memory; don't retry (and re-report) on every line.
      closeCurrent();
      errorSink.println("Admin log store: disabling disk persistence after write failure in " + dir + ": " + ex);
      return false;
    }
  }

  private void roll(long startSeq) throws IOException {
    closeCurrent();
    openCurrent(fileFor(startSeq));
    prune();
  }

  private void openCurrent(File file) throws IOException {
    currentFile = file;
    currentBytes = file.length();
    currentOut = new BufferedOutputStream(new FileOutputStream(file, true), 64 * 1024);
    if (currentBytes > 0 && !endsWithNewline(file)) {
      // Terminate a line torn by a crash so the next entry starts cleanly.
      currentOut.write('\n');
      currentBytes++;
    }
  }

  private static boolean endsWithNewline(File file) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    try {
      raf.seek(raf.length() - 1);
      return raf.read() == '\n';
    } finally {
      raf.close();
    }
  }

  private void closeCurrent() {
    if (currentOut != null) {
      try { currentOut.close(); } catch (IOException ignored) { }
      currentOut = null;
    }
  }

  private void prune() {
    List<File> files = listFiles();
    for (int i = 0; i < files.size() - maxFiles; i++) {
      File f = files.get(i);
      if (!f.equals(currentFile)) {
        // May fail on Windows while a reader has it open; it is retried on the next roll.
        f.delete();
      }
    }
  }
}
