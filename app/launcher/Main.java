package launcher;

import java.io.File;
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
 *
 * It also keeps what Java prints as errors until the server listens (StartErrors). The launchers
 * make logs/start-errors.log before they start Java; once the server listens, it is deleted, and
 * when the process ends before that (a mistake in conf/local.conf, the port taken), those errors are
 * written into it. A launcher that finds the file after Java ended knows that the server never
 * started, and doesn't start it again: it would only fail the same way.
 *
 * What Java itself says (that it ran out of memory), which it writes straight to the window, the
 * launchers send into a file of each start's own instead, logs/jvm-pid&lt;N&gt;.log: this deletes
 * the earlier ones in which Java said nothing, and keeps the newest of the others (JvmLogs).
 */
public final class Main {

  /** How long the end of the process waits for the window to take what is still queued for it. */
  static final long DRAIN_AT_EXIT_MS = 2000;

  private Main() {}

  public static void main(String[] args) {
    File home = new File(args.length > 0 ? args[0] : System.getProperty("user.dir"));
    StartErrors startErrors = new StartErrors(new File(new File(home, "logs"), "start-errors.log"));
    QueuedOutput out = new QueuedOutput(new FileOutputStream(FileDescriptor.out), "breadboard-stdout");
    QueuedOutput err = new QueuedOutput(new FileOutputStream(FileDescriptor.err), "breadboard-stderr", startErrors);
    // As Java makes them (System.initializeSystemClass): the console's encoding, when it has one
    System.setOut(printStream(out, System.getProperty("sun.stdout.encoding")));
    System.setErr(printStream(err, System.getProperty("sun.stderr.encoding")));
    // What is printed just before the process ends (why the server can't start) still goes out.
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      startErrors.writeIfTheServerNeverListened();
      long until = System.nanoTime() + DRAIN_AT_EXIT_MS * 1000000L;
      err.drain(until);
      out.drain(until);
    }, "breadboard-console-at-exit"));
    try {
      JvmLogs.prune(new File(home, "logs"), JvmLogs.KEEP, JvmLogs.ownName());
    } catch (RuntimeException e) {
      // they stay until the next start
    }

    play.core.server.NettyServer.main(args);
    // Play returns once the server listens. When it can't start, it ends the process itself
    // (System.exit), except when the folder it is given isn't there: then it only says so.
    if (play.api.Play.maybeApplication().isDefined()) {
      startErrors.serverListens();
    } else {
      System.exit(-1);
    }
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
