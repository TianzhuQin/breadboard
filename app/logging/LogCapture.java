package logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Iterator;

/**
 * Feeds everything an admin would otherwise have to SSH in to see into a {@link LogStore}:
 * <ul>
 *   <li>logback events (play.Logger and friends) via an appender on the root logger,</li>
 *   <li>anything written to System.out / System.err (stack traces, library chatter),</li>
 *   <li>groovy {@code println} from the script engine, via {@link #scriptOutput()}.</li>
 * </ul>
 * Output still reaches the real console as before. Installed from Global.onStart and removed in
 * onStop, so a dev-mode reload doesn't stack a new capture on top of the previous one.
 */
public final class LogCapture {
  private static final String APPENDER_NAME = "breadboard-admin-logs";

  private static LogStore store;
  private static PrintStream originalOut;
  private static PrintStream originalErr;
  private static PrintStream capturedOut;
  private static PrintStream capturedErr;
  private static StoreAppender appender;

  private LogCapture() { }

  public static synchronized LogStore store() {
    return store;
  }

  public static synchronized void install(LogStore newStore) {
    uninstall();
    store = newStore;
    originalOut = System.out;
    originalErr = System.err;
    newStore.setErrorSink(originalErr);

    capturedOut = printStream(new LineCaptureStream(newStore, "INFO", "stdout", originalOut));
    capturedErr = printStream(new LineCaptureStream(newStore, "ERROR", "stderr", originalErr));
    System.setOut(capturedOut);
    System.setErr(capturedErr);

    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory instanceof LoggerContext) {
      LoggerContext context = (LoggerContext) factory;
      ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      // Console appenders resolve System.out on every write, so they would now go through the
      // stdout capture and every logback line would be stored twice. Point them straight at the
      // real console instead; the appender below records the event (with its level) once.
      Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
      while (it.hasNext()) {
        Appender<ILoggingEvent> a = it.next();
        if (a instanceof ConsoleAppender) {
          ConsoleAppender<ILoggingEvent> console = (ConsoleAppender<ILoggingEvent>) a;
          boolean err = "System.err".equals(console.getTarget());
          console.setOutputStream(new NonClosingOutputStream(err ? originalErr : originalOut));
        }
      }
      appender = new StoreAppender(newStore);
      appender.setName(APPENDER_NAME);
      appender.setContext(context);
      appender.start();
      root.addAppender(appender);
    }
  }

  public static synchronized void uninstall() {
    if (store == null) return;
    if (System.out == capturedOut) System.setOut(originalOut);
    if (System.err == capturedErr) System.setErr(originalErr);
    if (appender != null) {
      ILoggerFactory factory = LoggerFactory.getILoggerFactory();
      if (factory instanceof LoggerContext) {
        ((LoggerContext) factory).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
      }
      appender.stop();
      appender = null;
    }
    store.close();
    store = null;
  }

  /**
   * The stream the groovy engine's {@code println} should write to: recorded with source
   * "script" and echoed to the console. Plain System.out when capture isn't installed (tests).
   */
  public static synchronized OutputStream scriptOutput() {
    if (store == null) return new NonClosingOutputStream(System.out);
    return new LineCaptureStream(store, "INFO", "script", originalOut);
  }

  private static PrintStream printStream(OutputStream out) {
    try {
      return new PrintStream(out, true, Charset.defaultCharset().name());
    } catch (UnsupportedEncodingException e) {
      return new PrintStream(out, true);
    }
  }

  static class StoreAppender extends AppenderBase<ILoggingEvent> {
    private final LogStore target;

    StoreAppender(LogStore target) {
      this.target = target;
    }

    @Override
    protected void append(ILoggingEvent event) {
      String msg = event.getFormattedMessage();
      IThrowableProxy tp = event.getThrowableProxy();
      if (tp != null) {
        String trace = ThrowableProxyUtil.asString(tp);
        msg = (msg == null || msg.isEmpty()) ? trace : msg + "\n" + trace;
      }
      target.append(event.getTimeStamp(), event.getLevel().toString(), event.getLoggerName(), msg);
    }
  }

  /** Echoes bytes to {@code passthrough} and records each complete line as a log entry. */
  static class LineCaptureStream extends OutputStream {
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private final LogStore target;
    private final String level;
    private final String source;
    private final OutputStream passthrough;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    LineCaptureStream(LogStore target, String level, String source, OutputStream passthrough) {
      this.target = target;
      this.level = level;
      this.source = source;
      this.passthrough = passthrough;
    }

    @Override
    public synchronized void write(int b) {
      try { passthrough.write(b); } catch (IOException ignored) { }
      capture(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      try { passthrough.write(b, off, len); } catch (IOException ignored) { }
      for (int i = off; i < off + len; i++) {
        capture(b[i]);
      }
    }

    @Override
    public synchronized void flush() {
      try { passthrough.flush(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() {
      emit();
      flush();
    }

    private void capture(int b) {
      if (b == '\n') {
        emit();
      } else {
        line.write(b);
        if (line.size() >= MAX_LINE_BYTES) emit();
      }
    }

    private void emit() {
      String s = new String(line.toByteArray(), Charset.defaultCharset());
      line.reset();
      if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
      if (!s.isEmpty()) {
        target.append(System.currentTimeMillis(), level, source, s);
      }
    }
  }

  /** Lets logback "close" a console stream on reconfigure without closing the real console. */
  static class NonClosingOutputStream extends FilterOutputStream {
    NonClosingOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }
}
