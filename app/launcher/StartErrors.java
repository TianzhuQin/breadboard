package launcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.TimeZone;

/**
 * What Java prints as errors while the server starts, for logs/start-errors.log (see {@link Main}):
 * the last {@link #MAX_LINES} lines, until the server listens. When the process ends before that,
 * they are written into the file; once the server listens, the file goes.
 */
final class StartErrors {

  /** The most lines kept (a start that fails prints one stack trace, about 70 lines). */
  static final int MAX_LINES = 400;

  /** The most bytes kept. */
  static final int MAX_BYTES = 64 * 1024;

  private final File file;

  // Guarded by this
  private final ArrayDeque<byte[]> lines = new ArrayDeque<>();
  private int bytes;
  private boolean listening;

  StartErrors(File file) {
    this.file = file;
  }

  File file() {
    return file;
  }

  /** A line (or a piece of one) that Java printed as an error. */
  synchronized void add(byte[] line) {
    if (listening) {
      return;
    }
    lines.addLast(line);
    bytes += line.length;
    while (lines.size() > MAX_LINES || (bytes > MAX_BYTES && lines.size() > 1)) {
      bytes -= lines.removeFirst().length;
    }
  }

  /** The server listens: nothing more is kept, and the file, which would say it didn't start, goes. */
  void serverListens() {
    synchronized (this) {
      listening = true;
      lines.clear();
      bytes = 0;
    }
    // (not holding this: what is printed comes back to add)
    if (file.exists() && !file.delete()) {
      System.err.println("Breadboard: couldn't delete " + file + ", which is from this start: it went well.");
    }
  }

  synchronized boolean listening() {
    return listening;
  }

  /**
   * At the end of the process: when the server never listened, what Java printed as errors goes
   * into the file, in place of what was there. (Nothing is said when the file can't be written: the
   * process is ending, and the window has the same lines.)
   */
  synchronized void writeIfTheServerNeverListened() {
    if (listening) {
      return;
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    String when = format.format(new Date());
    try (OutputStream out = new FileOutputStream(file)) {
      if (lines.isEmpty()) {
        out.write(("Breadboard stopped before the server started (" + when + "), without printing an error.\n"
            + "What it printed in its window may say why.\n").getBytes(StandardCharsets.US_ASCII));
      } else {
        out.write(("Breadboard stopped before the server started (" + when + "). What Java printed as errors:\n\n")
            .getBytes(StandardCharsets.US_ASCII));
        for (byte[] line : lines) {
          out.write(line);
        }
      }
    } catch (IOException | RuntimeException e) {
      // nothing to do
    }
  }
}
