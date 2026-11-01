package launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Play 2.2's WebSocketHandler calls Throwable.printStackTrace directly for a socket that closed
 * while a reply was being written. Logger settings cannot quiet it. Replace only that known trace
 * with a short diagnostic; other errors, even ClosedChannelException elsewhere, stay unchanged.
 */
final class DisconnectedOutput extends OutputStream {
  static final int MAX_LINE = 8192;
  static final int MAX_TRACE = 65536;
  private final OutputStream out;
  private final Runnable note;
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final ByteArrayOutputStream candidate = new ByteArrayOutputStream();
  private boolean matching;
  private boolean longLine;

  DisconnectedOutput(OutputStream out, Runnable note) {
    this.out = out;
    this.note = note;
  }

  @Override public synchronized void write(int b) throws IOException {
    if (longLine) {
      out.write(b);
      if (b == '\n') longLine = false;
      return;
    }
    line.write(b);
    if (b == '\n') {
      accept(line.toByteArray());
      line.reset();
    } else if (line.size() >= MAX_LINE) {
      // Never accumulate an unbounded line, nor treat a truncated line as a stack frame.
      endTrace();
      line.writeTo(out);
      line.reset();
      longLine = true;
    }
  }

  @Override public synchronized void write(byte[] bytes, int off, int len) throws IOException {
    if (off < 0 || len < 0 || off > bytes.length - len) throw new IndexOutOfBoundsException();
    for (int i = off; i < off + len; i++) write(bytes[i] & 255);
  }

  private void accept(byte[] bytes) throws IOException {
    String text = new String(bytes, StandardCharsets.ISO_8859_1).replaceFirst("[\r\n]+$", "");
    boolean frame = text.matches("\\s+at .+");
    if ((matching || candidate.size() > 0) && frame) {
      if (!matching) {
        candidate.write(bytes);
        if (text.matches("\\s+at play\\.core\\.server\\.netty\\.WebSocketHandler[.$].*")) {
          matching = true;
          candidate.reset();
          try { note.run(); } catch (RuntimeException ignored) { /* output must keep working */ }
        } else if (candidate.size() >= MAX_TRACE) {
          endTrace();
        }
      }
      return;
    }
    endTrace();
    if (text.matches("java\\.nio\\.channels\\.ClosedChannelException(?::.*)?")) candidate.write(bytes);
    else out.write(bytes);
  }

  private void endTrace() throws IOException {
    candidate.writeTo(out);
    candidate.reset();
    matching = false;
  }

  @Override public synchronized void flush() throws IOException {
    if (line.size() > 0) { accept(line.toByteArray()); line.reset(); }
    endTrace();
    out.flush();
  }

  @Override public void close() throws IOException { flush(); }
}
