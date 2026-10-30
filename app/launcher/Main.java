package launcher;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * How the launchers (breadboard.sh, breadboard.bat) start the server: Play's own start,
 * play.core.server.NettyServer.main, with a System.out and a System.err that never wait for the
 * window (QueuedOutput).
 *
 * Whatever printed to a window that took no output (one paused: a click into a Windows console
 * starts selecting text, Ctrl+S pauses a terminal; or a pipe nobody reads, once it was full) waited
 * for it, for as long as that took: a step whose script printed, logging, and Play's own start
 * ("Play server process ID is ..."), so a server that the launcher started again after a crash
 * never answered, and nothing said why. Now what is printed waits in a queue, and what doesn't fit
 * is left out of the window (logs/application.log has everything the server logs).
 */
public final class Main {

  /** How long the end of the process waits for the window to take what is still queued for it. */
  static final long DRAIN_AT_EXIT_MS = 2000;

  private Main() {}

  public static void main(String[] args) {
    QueuedOutput out = new QueuedOutput(new FileOutputStream(FileDescriptor.out), "breadboard-stdout");
    QueuedOutput err = new QueuedOutput(new FileOutputStream(FileDescriptor.err), "breadboard-stderr");
    // As Java makes them (System.initializeSystemClass): the console's encoding, when it has one
    System.setOut(printStream(out, System.getProperty("sun.stdout.encoding")));
    System.setErr(printStream(err, System.getProperty("sun.stderr.encoding")));
    // What is printed just before the process ends (why the server can't start) still goes out.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      long until = System.nanoTime() + DRAIN_AT_EXIT_MS * 1000000L;
      err.drain(until);
      out.drain(until);
    }, "breadboard-console-at-exit"));

    play.core.server.NettyServer.main(args);
  }

  /**
   * A PrintStream onto {@code out}, in {@code encoding} (null: Java's default). It doesn't flush by
   * itself, so that a line reaches {@code out}'s queue whole (println would flush its text and its
   * line's end apart); an explicit flush still sends a line that isn't ended yet.
   */
  static PrintStream printStream(OutputStream out, String encoding) {
    if (encoding != null) {
      try {
        return new PrintStream(out, false, encoding);
      } catch (UnsupportedEncodingException e) {
        // the default, as Java does
      }
    }
    return new PrintStream(out, false);
  }
}
