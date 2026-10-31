package launcher;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * The server's window output (launcher.QueuedOutput, which launcher.Main makes System.out and
 * System.err): printing never waits for a window that takes no output.
 */
public class QueuedOutputTest {

  /** A window that takes no output (paused, or a pipe nobody reads) until it is let go. */
  static final class StuckWindow extends OutputStream {
    final CountDownLatch letGo = new CountDownLatch(1);
    private final ByteArrayOutputStream got = new ByteArrayOutputStream();

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      try {
        letGo.await();
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
      synchronized (got) {
        got.write(b, off, len);
      }
    }

    String got() {
      synchronized (got) {
        return new String(got.toByteArray(), StandardCharsets.UTF_8);
      }
    }
  }

  private static String xs(int n) {
    char[] x = new char[n];
    Arrays.fill(x, 'x');
    return new String(x);
  }

  @Test(timeout = 60000)
  public void printingNeverWaitsForAWindowThatTakesNoOutputAndSaysWhatWasLeftOut() throws Exception {
    StuckWindow window = new StuckWindow();
    QueuedOutput queued = new QueuedOutput(window, "test-window");
    PrintStream out = Main.printStream(queued, "UTF-8");
    String text = xs(200);
    // twice what may wait for the window
    int lines = 2 * QueuedOutput.MAX_WAITING / text.length();
    long started = System.nanoTime();
    for (int i = 0; i < lines; i++) {
      out.println(i + " " + text);
    }
    long tookMs = (System.nanoTime() - started) / 1000000;
    assertTrue("printing waited for the window: " + tookMs + " ms", tookMs < 20000);
    assertFalse("nothing reached the window yet", window.got().contains(text));

    window.letGo.countDown();
    assertTrue("what waited went out once the window took output", queued.drainWithin(20000));
    out.println("after");
    assertTrue(queued.drainWithin(20000));

    List<String> got = new ArrayList<>(Arrays.asList(window.got().split("\n", -1)));
    assertEquals("the output ends with its last line", "", got.remove(got.size() - 1));
    assertEquals("after", got.remove(got.size() - 1));
    // Whole lines, in order, from the first; where lines were left out (while the window took
    // nothing, the writer's own buffer may still have made room for a few), a note says how many.
    Pattern note = Pattern.compile("\\((\\d+) lines? left out here: this window wasn't taking output\\. "
        + "logs/application\\.log has what the server logged\\.\\)");
    int next = 0;
    int shown = 0;
    String last = "";
    for (String line : got) {
      Matcher said = note.matcher(line);
      if (said.matches()) {
        next += Integer.parseInt(said.group(1));
      } else {
        assertEquals(next + " " + text, line);
        next++;
        shown++;
      }
      last = line;
    }
    assertEquals("every line is in the window or counted as left out", lines, next);
    assertTrue("some waited for the window: " + shown, shown > lines / 4);
    assertTrue("the lines left out at the end are said before what comes after: " + last, note.matcher(last).matches());
  }

  @Test(timeout = 60000)
  public void linesFromSeveralThreadsComeOutWholeAndInTheirOrder() throws Exception {
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    QueuedOutput queued = new QueuedOutput(window, "test-window");
    PrintStream out = Main.printStream(queued, "UTF-8");
    int threads = 4;
    int each = 2000;
    List<Thread> printers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int which = t;
      Thread printer = new Thread(() -> {
        for (int i = 0; i < each; i++) {
          out.println("t" + which + " " + i + " " + xs(30 + i % 50));
        }
      });
      printers.add(printer);
      printer.start();
    }
    for (Thread printer : printers) {
      printer.join();
    }
    assertTrue(queued.drainWithin(20000));

    String[] got = new String(window.toByteArray(), StandardCharsets.UTF_8).split("\n");
    assertEquals(threads * each, got.length);
    int[] next = new int[threads];
    Pattern whole = Pattern.compile("t(\\d) (\\d+) (x+)");
    for (String line : got) {
      Matcher m = whole.matcher(line);
      assertTrue("a whole line: " + line, m.matches());
      int which = Integer.parseInt(m.group(1));
      int i = Integer.parseInt(m.group(2));
      assertEquals("in its order", next[which], i);
      assertEquals(30 + i % 50, m.group(3).length());
      next[which]++;
    }
  }

  @Test(timeout = 60000)
  public void aLineShowsOnceItEndsOrIsFlushedAndALongOneComesOutWhole() throws Exception {
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    QueuedOutput queued = new QueuedOutput(window, "test-window");
    PrintStream out = Main.printStream(queued, "UTF-8");
    out.print("waiting for its end");
    Thread.sleep(100);
    assertEquals("", new String(window.toByteArray(), StandardCharsets.UTF_8));
    out.flush();
    assertTrue(queued.drainWithin(20000));
    assertEquals("waiting for its end", new String(window.toByteArray(), StandardCharsets.UTF_8));

    String longLine = xs(5 * QueuedOutput.MAX_PIECE + 17);
    out.println();
    out.println(longLine);
    out.println("next, 中文 too");
    assertTrue(queued.drainWithin(20000));
    assertEquals("waiting for its end\n" + longLine + "\nnext, 中文 too\n", new String(window.toByteArray(), StandardCharsets.UTF_8));
  }

  @Test(timeout = 60000)
  public void aWindowThatIsGoneTakesNothingAndPrintingGoesOn() throws Exception {
    OutputStream gone = new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        throw new IOException("Broken pipe");
      }
    };
    QueuedOutput queued = new QueuedOutput(gone, "test-window");
    PrintStream out = Main.printStream(queued, "UTF-8");
    for (int i = 0; i < 1000; i++) {
      out.println("line " + i);
    }
    assertTrue("what was queued is dropped, not kept for good", queued.drainWithin(20000));
    assertFalse(out.checkError());
  }
}
