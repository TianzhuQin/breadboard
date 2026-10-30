package launcher;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An output stream that never waits for its window: what is written to it goes into a queue, a line
 * at a time (a line longer than {@link #MAX_PIECE} in pieces), and a thread of its own writes that
 * out to the window. At most {@link #MAX_WAITING} bytes wait; what comes while they do (the window
 * takes no output) is left out, and once there is room again a line says how many lines were.
 */
final class QueuedOutput extends OutputStream {

  /** How much may wait for the window, in bytes; beyond that, lines are left out. */
  static final int MAX_WAITING = 1024 * 1024;

  /** A line longer than this many bytes goes into the queue in pieces. */
  static final int MAX_PIECE = 8 * 1024;

  private final OutputStream window;
  /** Where the lines also go while the server starts (the error output's), or null */
  private final StartErrors startErrors;
  private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
  /** The bytes in the queue, and those being written out */
  private final AtomicLong waiting = new AtomicLong();

  // Guarded by this
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  /** The lines (or pieces) left out since the last one that went into the queue */
  private long leftOut;
  /** Whether the last piece that went into the queue didn't end its line */
  private boolean lineOpen;

  /** Writes out to {@code window} from a thread named {@code name} (a daemon: it never keeps the process going). */
  QueuedOutput(OutputStream window, String name) {
    this(window, name, null);
  }

  /** As above, and passes each line to {@code startErrors} too (see {@link StartErrors}). */
  QueuedOutput(OutputStream window, String name, StartErrors startErrors) {
    this.window = window;
    this.startErrors = startErrors;
    Thread writer = new Thread(this::writeOut, name);
    writer.setDaemon(true);
    writer.start();
  }

  @Override
  public synchronized void write(int b) {
    line.write(b);
    if (b == '\n' || line.size() >= MAX_PIECE) {
      queueLine();
    }
  }

  @Override
  public synchronized void write(byte[] b, int off, int len) {
    if (off < 0 || len < 0 || off + len > b.length || off + len < 0) {
      throw new IndexOutOfBoundsException();
    }
    int start = off;
    int end = off + len;
    for (int i = off; i < end; i++) {
      if (b[i] == '\n' || line.size() + (i + 1 - start) >= MAX_PIECE) {
        line.write(b, start, i + 1 - start);
        queueLine();
        start = i + 1;
      }
    }
    line.write(b, start, end - start);
  }

  /** A line not ended yet goes into the queue as it is (as a flush of a window's stream shows it). */
  @Override
  public synchronized void flush() {
    if (line.size() > 0) {
      queueLine();
    }
  }

  @Override
  public void close() {
    flush();
  }

  /** The line written so far goes into the queue, if there is room for it; holds this. */
  private void queueLine() {
    byte[] piece = line.toByteArray();
    line.reset();
    if (startErrors != null) {
      startErrors.add(piece);
    }
    if (leftOut > 0) {
      // what was left out is said where it was, before what comes after it
      byte[] note = ((lineOpen ? "\n" : "") + "(" + leftOut + (leftOut == 1 ? " line" : " lines")
          + " left out here: this window wasn't taking output. logs/application.log has what the server logged.)\n")
          .getBytes(StandardCharsets.US_ASCII);
      if (!offer(note)) {
        leftOut++;
        return;
      }
      leftOut = 0;
      lineOpen = false;
    }
    if (offer(piece)) {
      lineOpen = piece[piece.length - 1] != '\n';
    } else {
      leftOut++;
    }
  }

  private boolean offer(byte[] piece) {
    if (waiting.get() + piece.length > MAX_WAITING) {
      return false;
    }
    waiting.addAndGet(piece.length);
    queue.add(piece);
    return true;
  }

  /** The writer's thread: what is queued goes out to the window, which may take its time. */
  private void writeOut() {
    OutputStream out = new BufferedOutputStream(window, MAX_PIECE);
    boolean gone = false;
    while (true) {
      byte[] piece;
      try {
        piece = queue.take();
      } catch (InterruptedException e) {
        return;
      }
      try {
        if (!gone) {
          out.write(piece);
          if (queue.isEmpty()) {
            out.flush();
          }
        }
      } catch (IOException e) {
        // The window is gone (closed, or a pipe whose reader ended): what comes is dropped.
        gone = true;
      } finally {
        waiting.addAndGet(-piece.length);
      }
    }
  }

  /**
   * Waits until what waits, a line not ended yet included, has been written out, until
   * {@code untilNanos} (System.nanoTime) at the latest. True if it was.
   */
  boolean drain(long untilNanos) {
    flush();
    while (waiting.get() > 0) {
      if (System.nanoTime() - untilNanos >= 0) {
        return false;
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        return false;
      }
    }
    return true;
  }

  /** {@link #drain(long)}, for at most {@code millis} from now. */
  boolean drainWithin(long millis) {
    return drain(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
  }
}
