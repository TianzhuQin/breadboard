package logging;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
  private static final String SEQ_PREFIX = "{\"seq\":";
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
  private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(512);
  private final Thread writer;
  /** Held for the store's lifetime; null when another store owns {@code dir} (memory-only mode). */
  private final FileLock dirLock;

  public LogStore(File dir, long maxFileBytes, int maxFiles, int memoryEntries, long scanBudgetBytes) {
    this.dir = dir;
    this.maxFileBytes = maxFileBytes;
    this.maxFiles = Math.max(1, maxFiles);
    this.memoryEntries = Math.max(1, memoryEntries);
    this.scanBudgetBytes = scanBudgetBytes;
    dir.mkdirs();
    dirLock = lockDir(dir);
    if (dirLock != null) {
      recover();
    } else {
      // Another store (a second server, or a test run, on the same directory) is writing here.
      // Sharing it would hand out duplicate seqs, so keep this one in memory only.
      diskFailed = true;
      System.err.println("Admin log store: " + dir + " is in use by another process; keeping logs in memory only");
    }
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
    if (dirLock != null) {
      try { dirLock.channel().close(); } catch (IOException ignored) { }
    }
  }

  /** An exclusive lock on {@code dir/.lock}, or null if another store (in any process) holds it. */
  private static FileLock lockDir(File dir) {
    FileChannel channel = null;
    try {
      channel = new RandomAccessFile(new File(dir, ".lock"), "rw").getChannel();
      FileLock lock = channel.tryLock();
      if (lock != null) return lock;
    } catch (OverlappingFileLockException e) {
      // Held by another store in this JVM, e.g. a dev-mode reload that hasn't closed the old one.
    } catch (IOException e) {
      // Can't create the lock file: treat like a write failure.
    }
    if (channel != null) {
      try { channel.close(); } catch (IOException ignored) { }
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------
  // Reading

  /** Matches entries at or above a minimum level whose message or source contains a query (case-insensitive). */
  public static class Filter {
    public static final Filter ALL = new Filter(null, null);
    private final String query;
    private final int minLevel;
    /** Whether a match must also appear verbatim in the stored JSON line, see {@link #mayMatchLine}. */
    private final boolean lineCheckable;

    public Filter(String query, String minLevel) {
      this.query = (query == null || query.isEmpty()) ? null : query.toLowerCase(Locale.ROOT);
      this.minLevel = (minLevel == null || minLevel.isEmpty()) ? -1 : LogEntry.levelRank(minLevel);
      this.lineCheckable = this.query != null && isUnescapedAscii(this.query);
    }

    public boolean matches(LogEntry e) {
      if (minLevel >= 0 && LogEntry.levelRank(e.level) < minLevel) return false;
      if (query == null) return true;
      return (e.source != null && e.source.toLowerCase(Locale.ROOT).contains(query))
          || (e.msg != null && e.msg.toLowerCase(Locale.ROOT).contains(query));
    }

    /**
     * Cheap pre-check on a raw JSON line, so lines that can't match are never parsed. False
     * positives (e.g. the query hits a key name) are fine: callers still apply {@link #matches}.
     * Only used when the query is printable ASCII without {@code "} or {@code \}, which Jackson
     * writes unescaped, so any match in msg or src also appears in the line itself.
     */
    boolean mayMatchLine(String line) {
      return !lineCheckable || line.toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean isUnescapedAscii(String s) {
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') return false;
      }
      return true;
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

  /**
   * Copies the in-memory window, skipping entries with seq &lt;= {@code minSeq}: a tail poll only
   * needs the few newest entries, and the copy is made under the lock that {@link #append} needs.
   */
  private Snapshot snapshot(long minSeq) {
    Snapshot s = new Snapshot();
    List<LogEntry> newestFirst = new ArrayList<LogEntry>();
    synchronized (this) {
      // Queued entries already evicted from `recent` aren't on disk yet: count them as in memory.
      long recentOldest = recent.isEmpty() ? nextSeq : recent.peekFirst().seq;
      LogEntry firstPending = pending.peekFirst();
      s.memOldest = (firstPending != null && firstPending.seq < recentOldest) ? firstPending.seq : recentOldest;
      s.recent = new ArrayList<LogEntry>();
      if (minSeq + 1 < recentOldest) {
        for (LogEntry e : pending) {
          if (e.seq >= recentOldest) break;
          if (e.seq > minSeq) s.recent.add(e);
        }
      }
      Iterator<LogEntry> it = recent.descendingIterator();
      while (it.hasNext()) {
        LogEntry e = it.next();
        if (e.seq <= minSeq) break;
        newestFirst.add(e);
      }
      s.latestSeq = nextSeq - 1;
    }
    for (int i = newestFirst.size() - 1; i >= 0; i--) s.recent.add(newestFirst.get(i));
    // Listed after the memory snapshot and outside the lock: every entry below memOldest was
    // written before this point, so its file is listed. Newer or since-pruned files are harmless.
    s.files = listFiles();
    s.oldestSeq = s.files.isEmpty() ? s.memOldest : Math.min(startSeqOf(s.files.get(0)), s.memOldest);
    return s;
  }

  /** Up to {@code limit} matching entries with seq &lt; {@code before}, newest first in scan order. */
  public Page before(long before, int limit, Filter filter) {
    Snapshot s = snapshot(0);
    // Collected newest first, reversed once on return.
    List<LogEntry> out = new ArrayList<LogEntry>();
    long cursor = Math.min(before, s.latestSeq + 1);

    for (int i = s.recent.size() - 1; i >= 0; i--) {
      LogEntry e = s.recent.get(i);
      if (e.seq >= cursor) continue;
      cursor = e.seq;
      if (filter.matches(e)) {
        out.add(e);
        if (out.size() >= limit) return beforePage(out, cursor > s.oldestSeq, cursor, s.latestSeq);
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
      // Only lines the scan reaches are parsed, so paging back through one file stays cheap.
      // The cursor only has to be exact where a page ends (at a match) or at the file boundary.
      List<String> lines = readLines(file);
      for (int j = lines.size() - 1; j >= 0; j--) {
        String line = lines.get(j);
        if (seqOf(line) >= cursor || !filter.mayMatchLine(line)) continue;
        LogEntry e = parse(line);
        if (e == null || e.seq >= cursor) continue;
        cursor = e.seq;
        if (filter.matches(e)) {
          out.add(e);
          if (out.size() >= limit) return beforePage(out, cursor > s.oldestSeq, cursor, s.latestSeq);
        }
      }
      cursor = Math.min(cursor, start);
    }
    return beforePage(out, cursor > s.oldestSeq, cursor, s.latestSeq);
  }

  private static Page beforePage(List<LogEntry> newestFirst, boolean hasMore, long cursor, long latestSeq) {
    Collections.reverse(newestFirst);
    return new Page(newestFirst, hasMore, cursor, latestSeq);
  }

  /** Up to {@code limit} matching entries with seq &gt; {@code after}, oldest first. */
  public Page after(long after, int limit, Filter filter) {
    Snapshot s = snapshot(after);
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
        for (String line : readLines(file)) {
          long seq = seqOf(line);
          if (seq >= s.memOldest) break;
          if ((seq >= 0 && seq <= cursor) || !filter.mayMatchLine(line)) continue;
          LogEntry e = parse(line);
          if (e == null || e.seq <= cursor) continue;
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
    // Without the lock the files belong to another store, whose seqs overlap ours.
    if (dirLock == null) return new ArrayList<File>();
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
    for (String line : readLines(file)) {
      LogEntry e = parse(line);
      if (e != null) entries.add(e);
    }
    return entries;
  }

  /** The file's non-empty lines, unparsed, in order. */
  private static List<String> readLines(File file) {
    List<String> lines = new ArrayList<String>();
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF_8), 64 * 1024);
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty()) lines.add(line);
      }
    } catch (IOException ignored) {
      // Pruned while we were reading it, or unreadable: treat as empty.
    } finally {
      if (reader != null) {
        try { reader.close(); } catch (IOException ignored) { }
      }
    }
    return lines;
  }

  /** The entry on a stored line, or null for a partial or corrupt line. */
  private static LogEntry parse(String line) {
    try {
      return LogEntry.fromMap(mapper.readValue(line, Map.class));
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * The seq of a stored line without parsing it, relying on {@link #writeEntry} putting seq first;
   * -1 when the line doesn't start that way, in which case callers fall back to {@link #parse}.
   */
  static long seqOf(String line) {
    if (!line.startsWith(SEQ_PREFIX)) return -1;
    int i = SEQ_PREFIX.length();
    int digitsStart = i;
    long seq = 0;
    while (i < line.length() && i - digitsStart < 18) {
      char c = line.charAt(i);
      if (c < '0' || c > '9') break;
      seq = seq * 10 + (c - '0');
      i++;
    }
    if (i == digitsStart || i >= line.length() || line.charAt(i) != ',') return -1;
    return seq;
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
        writeEntry(e);
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

  /** One JSON line, the same as serializing {@link LogEntry#toMap}. seq must stay first, see {@link #seqOf}. */
  private void writeEntry(LogEntry e) throws IOException {
    lineBuffer.reset();
    JsonGenerator g = mapper.getFactory().createGenerator(lineBuffer, JsonEncoding.UTF8);
    g.writeStartObject();
    g.writeNumberField("seq", e.seq);
    g.writeNumberField("ts", e.ts);
    g.writeStringField("level", e.level);
    g.writeStringField("src", e.source);
    g.writeStringField("msg", e.msg);
    g.writeEndObject();
    g.close();
    lineBuffer.write('\n');
    lineBuffer.writeTo(currentOut);
    currentBytes += lineBuffer.size();
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
