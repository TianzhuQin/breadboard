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

  private final File dir;
  private final long maxFileBytes;
  private final int maxFiles;
  private final int memoryEntries;
  private final long scanBudgetBytes;

  private final ArrayDeque<LogEntry> recent = new ArrayDeque<LogEntry>();
  private long nextSeq = 1;
  private File currentFile;
  private OutputStream currentOut;
  private long currentBytes;
  private boolean diskFailed = false;
  private boolean closed = false;
  private PrintStream errorSink = System.err;

  public LogStore(File dir, long maxFileBytes, int maxFiles, int memoryEntries, long scanBudgetBytes) {
    this.dir = dir;
    this.maxFileBytes = maxFileBytes;
    this.maxFiles = Math.max(1, maxFiles);
    this.memoryEntries = Math.max(1, memoryEntries);
    this.scanBudgetBytes = scanBudgetBytes;
    dir.mkdirs();
    recover();
  }

  /** Where disk-write failures are reported. Must not route back into this store. */
  public synchronized void setErrorSink(PrintStream errorSink) {
    this.errorSink = errorSink;
  }

  public synchronized LogEntry append(long ts, String level, String source, String msg) {
    LogEntry e = new LogEntry(nextSeq++, ts, level, source, msg);
    recent.addLast(e);
    if (recent.size() > memoryEntries) {
      recent.removeFirst();
    }
    writeToDisk(e);
    return e;
  }

  public synchronized long latestSeq() {
    return nextSeq - 1;
  }

  /** Stops disk writes for good; a capture stream that outlives its install can't reopen the file. */
  public synchronized void close() {
    closed = true;
    closeCurrent();
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

  private synchronized Snapshot snapshot() {
    Snapshot s = new Snapshot();
    s.recent = new ArrayList<LogEntry>(recent);
    s.files = listFiles();
    s.latestSeq = nextSeq - 1;
    s.memOldest = s.recent.isEmpty() ? nextSeq : s.recent.get(0).seq;
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

  private void writeToDisk(LogEntry e) {
    if (diskFailed || closed) return;
    try {
      if (currentOut == null || currentBytes >= maxFileBytes) {
        if (currentOut == null && currentFile != null && currentFile.length() < maxFileBytes) {
          openCurrent(currentFile);
        } else {
          roll(e.seq);
        }
      }
      byte[] bytes = (mapper.writeValueAsString(e.toMap()) + "\n").getBytes(UTF_8);
      currentOut.write(bytes);
      currentOut.flush();
      currentBytes += bytes.length;
    } catch (IOException ex) {
      // Keep serving from memory; don't retry (and re-report) on every line.
      diskFailed = true;
      closeCurrent();
      errorSink.println("Admin log store: disabling disk persistence after write failure in " + dir + ": " + ex);
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
    currentOut = new FileOutputStream(file, true);
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
