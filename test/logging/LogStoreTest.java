package logging;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import static org.junit.Assert.*;

/** Unit tests for {@link LogStore} windowed reads and {@link LogCapture.LineCaptureStream}. No app or DB needed. */
public class LogStoreTest {

  private File dir;
  private List<LogStore> stores = new ArrayList<LogStore>();

  @Before
  public void setUp() throws IOException {
    dir = Files.createTempDirectory("logstore-test").toFile();
  }

  @After
  public void tearDown() {
    for (LogStore s : stores) s.close();
    File[] files = dir.listFiles();
    if (files != null) for (File f : files) f.delete();
    dir.delete();
  }

  /** Tiny files and memory so a few hundred entries span many files and mostly live on disk. */
  private LogStore store(long maxFileBytes, int maxFiles, int memoryEntries, long scanBudget) {
    LogStore s = new LogStore(dir, maxFileBytes, maxFiles, memoryEntries, scanBudget);
    stores.add(s);
    return s;
  }

  private LogStore smallStore() {
    return store(2000, 1000, 10, Long.MAX_VALUE);
  }

  /** Appends n entries and waits until they are on disk, so the reads below exercise the files. */
  private static void fill(LogStore s, int n) throws InterruptedException {
    append(s, n);
    s.flush();
  }

  private static void append(LogStore s, int n) {
    for (int i = 1; i <= n; i++) {
      s.append(i, i % 10 == 0 ? "ERROR" : "INFO", "src", "line " + i);
    }
  }

  /** The store's log files, without its .lock file. */
  private File[] logFiles() {
    return dir.listFiles(new java.io.FilenameFilter() {
      public boolean accept(File d, String name) {
        return name.endsWith(".jsonl");
      }
    });
  }

  private static List<Long> seqs(List<LogEntry> entries) {
    List<Long> out = new ArrayList<Long>();
    for (LogEntry e : entries) out.add(e.seq);
    return out;
  }

  private static List<Long> range(long from, long to) {
    List<Long> out = new ArrayList<Long>();
    for (long i = from; i <= to; i++) out.add(i);
    return out;
  }

  @Test
  public void tailReturnsNewestPageOldestFirst() throws Exception {
    LogStore s = smallStore();
    fill(s, 50);

    LogStore.Page page = s.before(Long.MAX_VALUE, 5, LogStore.Filter.ALL);

    assertEquals(range(46, 50), seqs(page.entries));
    assertTrue(page.hasMore);
    assertEquals(46, page.cursor);
    assertEquals(50, page.latestSeq);
  }

  @Test
  public void pagingBackwardsCoversEverythingAcrossMemoryAndFiles() throws Exception {
    LogStore s = smallStore();
    fill(s, 300);
    assertTrue("test needs several files", logFiles().length > 3);

    List<Long> all = new ArrayList<Long>();
    long cursor = Long.MAX_VALUE;
    boolean more = true;
    while (more) {
      LogStore.Page page = s.before(cursor, 7, LogStore.Filter.ALL);
      all.addAll(0, seqs(page.entries));
      cursor = page.cursor;
      more = page.hasMore;
    }
    assertEquals(range(1, 300), all);
  }

  @Test
  public void pagingForwardsCoversEverythingAcrossFilesAndMemory() throws Exception {
    LogStore s = smallStore();
    fill(s, 300);

    List<Long> all = new ArrayList<Long>();
    long cursor = 0;
    boolean more = true;
    while (more) {
      LogStore.Page page = s.after(cursor, 7, LogStore.Filter.ALL);
      all.addAll(seqs(page.entries));
      cursor = page.cursor;
      more = page.hasMore;
    }
    assertEquals(range(1, 300), all);
    assertEquals(300, cursor);
  }

  @Test
  public void afterLatestReturnsNothingThenNewEntries() throws Exception {
    LogStore s = smallStore();
    fill(s, 20);
    LogStore.Page empty = s.after(20, 100, LogStore.Filter.ALL);
    assertTrue(empty.entries.isEmpty());
    assertFalse(empty.hasMore);

    s.append(0, "INFO", "src", "new");
    LogStore.Page page = s.after(empty.cursor, 100, LogStore.Filter.ALL);
    assertEquals(range(21, 21), seqs(page.entries));
    assertEquals("new", page.entries.get(0).msg);
  }

  @Test
  public void filterAppliesAcrossDiskAndCursorSkipsNonMatches() throws Exception {
    LogStore s = smallStore();
    fill(s, 300);

    LogStore.Page errors = s.before(Long.MAX_VALUE, 1000, new LogStore.Filter(null, "WARN"));
    assertEquals(30, errors.entries.size());
    assertFalse(errors.hasMore);
    for (LogEntry e : errors.entries) assertEquals("ERROR", e.level);

    // "line 15" matches 15 and 150..159; the page stops at the limit with a resumable cursor.
    LogStore.Page first = s.before(Long.MAX_VALUE, 3, new LogStore.Filter("LINE 15", null));
    assertEquals(java.util.Arrays.asList(157L, 158L, 159L), seqs(first.entries));
    LogStore.Page rest = s.before(first.cursor, 100, new LogStore.Filter("line 15", null));
    assertEquals(java.util.Arrays.asList(15L, 150L, 151L, 152L, 153L, 154L, 155L, 156L), seqs(rest.entries));
  }

  @Test
  public void searchOnDiskHandlesEscapedAndNonAsciiText() throws Exception {
    LogStore s = smallStore();
    s.append(0, "INFO", "src", "say \"hi\" to C:\\temp");
    s.append(0, "INFO", "src", "first\nSECOND line");
    s.append(0, "INFO", "src", "\u00dcber caf\u00e9");
    s.append(0, "WARN", "Some.Logger", "plain");
    fill(s, 100);  // memory holds 10, so the four above are only on disk

    assertEquals(range(1, 1), seqs(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("\"hi\" to c:\\", null)).entries));
    assertEquals(range(2, 2), seqs(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("first\nsecond", null)).entries));
    assertEquals(range(2, 2), seqs(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("second line", null)).entries));
    assertEquals(range(3, 3), seqs(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("\u00fcBER CAF\u00c9", null)).entries));
    assertEquals(range(4, 4), seqs(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("some.logger", null)).entries));
    assertEquals(range(4, 4), seqs(s.after(0, 10, new LogStore.Filter("some.logger", null)).entries));
    // Hits a JSON key on every line, but no message or source.
    assertTrue(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("\"level\"", null)).entries.isEmpty());
    assertTrue(s.before(Long.MAX_VALUE, 10, new LogStore.Filter("level", null)).entries.isEmpty());
  }

  @Test
  public void storedLinesMatchTheEntryMapAndStartWithSeq() throws Exception {
    LogStore s = smallStore();
    s.append(7, "INFO", null, "quote \" slash \\ tab \t \u00e9");
    s.close();

    String line = new String(Files.readAllBytes(logFiles()[0].toPath()), "UTF-8");
    LogEntry e = LogStore.readFile(logFiles()[0]).get(0);
    assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(e.toMap()) + "\n", line);
    assertNull(e.source);
    assertEquals(1, LogStore.seqOf(line));
    assertEquals(-1, LogStore.seqOf("{\"ts\":1,\"seq\":2}"));
    assertEquals(-1, LogStore.seqOf("{\"seq\":12"));
  }

  @Test
  public void pagingForwardsHasNoGapsWhileDiskWritesAreStillQueued() throws Exception {
    LogStore s = smallStore();
    append(s, 3000);

    List<Long> all = new ArrayList<Long>();
    long cursor = 0;
    boolean more = true;
    while (more) {
      LogStore.Page page = s.after(cursor, 50, LogStore.Filter.ALL);
      all.addAll(seqs(page.entries));
      cursor = page.cursor;
      more = page.hasMore;
    }
    assertEquals(range(1, 3000), all);
  }

  @Test
  public void readsHaveNoGapsWhileDiskWritesAreStillQueued() throws Exception {
    // Memory holds only 10 entries, so most of these are either queued or on disk depending on
    // how far the writer has got. Either way every entry must be readable exactly once.
    LogStore s = smallStore();
    append(s, 3000);

    List<Long> all = new ArrayList<Long>();
    long cursor = Long.MAX_VALUE;
    boolean more = true;
    while (more) {
      LogStore.Page page = s.before(cursor, 50, LogStore.Filter.ALL);
      all.addAll(0, seqs(page.entries));
      cursor = page.cursor;
      more = page.hasMore;
    }
    assertEquals(range(1, 3000), all);
  }

  @Test
  public void restartContinuesSequenceAndKeepsHistory() throws Exception {
    LogStore first = smallStore();
    fill(first, 120);
    first.close();

    LogStore second = smallStore();
    assertEquals(120, second.latestSeq());
    LogEntry e = second.append(0, "INFO", "src", "after restart");
    assertEquals(121, e.seq);

    LogStore.Page page = second.before(Long.MAX_VALUE, 1000, LogStore.Filter.ALL);
    assertEquals(range(1, 121), seqs(page.entries));
  }

  @Test
  public void secondStoreOnTheSameDirectoryStaysInMemoryAndCannotDuplicateSeqs() throws Exception {
    LogStore owner = smallStore();
    fill(owner, 30);

    LogStore intruder = smallStore();
    intruder.append(0, "INFO", "src", "intruder");
    intruder.flush();
    // Neither sees the other's entries, and nothing the intruder logged reached the owner's files.
    assertEquals(range(1, 1), seqs(intruder.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL).entries));
    assertEquals(range(1, 30), seqs(owner.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL).entries));
    for (File f : logFiles()) {
      for (LogEntry e : LogStore.readFile(f)) assertFalse("intruder".equals(e.msg));
    }

    owner.close();
    intruder.close();
    LogStore next = smallStore();
    assertEquals("the lock is released on close", 30, next.latestSeq());
  }

  @Test
  public void oldestFilesArePrunedBeyondMaxFiles() throws Exception {
    LogStore s = store(2000, 3, 10, Long.MAX_VALUE);
    fill(s, 300);

    assertTrue(logFiles().length <= 3);
    LogStore.Page page = s.before(Long.MAX_VALUE, 1000, LogStore.Filter.ALL);
    assertFalse(page.hasMore);
    List<Long> got = seqs(page.entries);
    assertEquals(300L, (long) got.get(got.size() - 1));
    assertTrue("oldest entries pruned", got.get(0) > 1);
    assertEquals(range(got.get(0), 300), got);
  }

  @Test
  public void tornLastLineIsSkippedAndNotGluedToTheNextEntry() throws Exception {
    LogStore first = smallStore();
    fill(first, 3);
    first.close();
    File file = logFiles()[0];
    FileOutputStream out = new FileOutputStream(file, true);
    out.write("{\"seq\":4,\"ts\":1,\"lev".getBytes("UTF-8"));
    out.close();

    LogStore second = smallStore();
    second.append(0, "INFO", "src", "next");
    second.close();

    List<LogEntry> onDisk = LogStore.readFile(file);
    assertEquals(range(1, 4), seqs(onDisk));
    assertEquals("next", onDisk.get(3).msg);
  }

  @Test
  public void scanBudgetSplitsALongSearchIntoResumablePages() throws Exception {
    LogStore s = store(2000, 1000, 10, 1);  // one file per request
    fill(s, 300);

    LogStore.Filter none = new LogStore.Filter("no such text", null);
    LogStore.Page page = s.before(Long.MAX_VALUE, 100, none);
    assertTrue(page.entries.isEmpty());
    assertTrue(page.hasMore);

    int requests = 1;
    while (page.hasMore) {
      long previous = page.cursor;
      page = s.before(page.cursor, 100, none);
      assertTrue("cursor must advance", page.cursor < previous);
      requests++;
    }
    assertTrue(requests > 3);
  }

  @Test
  public void lineCaptureRecordsWholeLinesAndPassesBytesThrough() throws IOException {
    LogStore s = smallStore();
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    LogCapture.LineCaptureStream capture = new LogCapture.LineCaptureStream(s, "INFO", "stdout", console);

    capture.write("hello wo".getBytes("UTF-8"));
    capture.write("rld\r\n\nsecond\n".getBytes("UTF-8"));
    capture.write("partial".getBytes("UTF-8"));

    assertEquals("hello world\r\n\nsecond\npartial", console.toString("UTF-8"));
    LogStore.Page page = s.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL);
    assertEquals(2, page.entries.size());
    assertEquals("hello world", page.entries.get(0).msg);
    assertEquals("stdout", page.entries.get(0).source);
    assertEquals("second", page.entries.get(1).msg);

    capture.close();
    assertEquals("partial", s.before(Long.MAX_VALUE, 1, LogStore.Filter.ALL).entries.get(0).msg);
  }

  @Test
  public void lineCaptureSplitsOverlongLinesWithinOneWrite() throws IOException {
    LogStore s = smallStore();
    LogCapture.LineCaptureStream capture = new LogCapture.LineCaptureStream(s, "INFO", "stdout", new ByteArrayOutputStream());
    byte[] big = new byte[64 * 1024 * 2 + 10 + 1];
    java.util.Arrays.fill(big, (byte) 'a');
    big[big.length - 1] = '\n';

    capture.write(big);

    List<LogEntry> entries = s.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL).entries;
    assertEquals(3, entries.size());
    assertEquals(64 * 1024, entries.get(0).msg.length());
    assertEquals(64 * 1024, entries.get(1).msg.length());
    assertEquals(10, entries.get(2).msg.length());
  }

  @Test
  public void lineCaptureStripsColorCodesFromRecordedTextOnly() throws IOException {
    LogStore s = smallStore();
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    LogCapture.LineCaptureStream capture = new LogCapture.LineCaptureStream(s, "INFO", "script", console);
    String colored = "\u001B[31mred\u001B[0m and \u001B[1;32mbold green\u001B[0m\n";

    capture.write(colored.getBytes("UTF-8"));

    assertEquals(colored, console.toString("UTF-8"));
    assertEquals("red and bold green", s.before(Long.MAX_VALUE, 1, LogStore.Filter.ALL).entries.get(0).msg);
  }

  @Test
  public void lineCaptureRelabelsPlayConsoleLinesWithTheirLevelAndLogger() throws IOException {
    LogStore s = smallStore();
    LogCapture.LineCaptureStream capture =
        new LogCapture.LineCaptureStream(s, "INFO", "stdout", new ByteArrayOutputStream(), true);

    capture.write("[\u001B[36mdebug\u001B[0m] application - loading util.groovy\n".getBytes("UTF-8"));
    capture.write("[warn] a.c.ClientController - no - dashes - lost\n".getBytes("UTF-8"));
    capture.write("[note] not a level - left alone\n".getBytes("UTF-8"));

    List<LogEntry> entries = s.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL).entries;
    assertEquals(3, entries.size());
    assertEquals("DEBUG", entries.get(0).level);
    assertEquals("application", entries.get(0).source);
    assertEquals("loading util.groovy", entries.get(0).msg);
    assertEquals("WARN", entries.get(1).level);
    assertEquals("a.c.ClientController", entries.get(1).source);
    assertEquals("no - dashes - lost", entries.get(1).msg);
    assertEquals("INFO", entries.get(2).level);
    assertEquals("stdout", entries.get(2).source);
    assertEquals("[note] not a level - left alone", entries.get(2).msg);
  }

  @Test
  public void lineCaptureLeavesConsoleLookalikesAloneWhenNotParsing() throws IOException {
    LogStore s = smallStore();
    LogCapture.LineCaptureStream capture = new LogCapture.LineCaptureStream(s, "INFO", "script", new ByteArrayOutputStream());

    capture.write("[debug] application - from a groovy println\n".getBytes("UTF-8"));

    LogEntry e = s.before(Long.MAX_VALUE, 1, LogStore.Filter.ALL).entries.get(0);
    assertEquals("INFO", e.level);
    assertEquals("script", e.source);
    assertEquals("[debug] application - from a groovy println", e.msg);
  }

  @Test
  public void groovyPrintlnIsRecordedThroughTheEngineWriter() throws Exception {
    LogStore s = smallStore();
    ScriptEngine engine = new ScriptEngineManager().getEngineByName("gremlin-groovy");
    ByteArrayOutputStream console = new ByteArrayOutputStream();
    // Same wiring as ScriptBoard.resetEngine, with a capture stream standing in for scriptOutput().
    engine.getContext().setWriter(new java.io.PrintWriter(
        new LogCapture.LineCaptureStream(s, "INFO", "script", console), true));

    engine.eval("println 'from eval'");
    engine.eval("later = { println 'from closure' }");
    engine.eval("later()");

    List<LogEntry> entries = s.before(Long.MAX_VALUE, 100, LogStore.Filter.ALL).entries;
    assertEquals(2, entries.size());
    assertEquals("from eval", entries.get(0).msg);
    assertEquals("script", entries.get(0).source);
    assertEquals("from closure", entries.get(1).msg);
  }
}
