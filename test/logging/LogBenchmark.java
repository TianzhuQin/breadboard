package logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Measures what admin log capture costs the threads that log, and how long a full-history search
 * request takes. Not part of the test suite; run with:
 *
 * <pre>sbt -java-home "$JAVA8_HOME" "test:runMain logging.LogBenchmark [totalLines]"</pre>
 *
 * The baseline mirrors production: logback writing every event to an application.log file (Play's
 * default FILE appender). The capture scenarios add LogCapture on top, as Global.onStart does.
 */
public class LogBenchmark {
  private static final PrintStream out = System.out;

  public static void main(String[] args) throws Exception {
    int totalLines = args.length > 0 ? Integer.parseInt(args[0]) : 400000;
    File work = Files.createTempDirectory("log-bench").toFile();
    try {
      LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
      ctx.reset();
      ch.qos.logback.classic.Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
      root.setLevel(Level.DEBUG);
      root.addAppender(applicationLog(ctx, new File(work, "application.log")));
      Logger log = LoggerFactory.getLogger("application");

      // Warm up the JIT on both paths before measuring anything.
      run(log, 4, 50000);
      LogCapture.install(store(new File(work, "warmup"), 64));
      run(log, 4, 50000);
      LogCapture.uninstall();

      out.println(String.format("%,d log lines per scenario, logger.debug with two arguments", totalLines));
      out.println(String.format("%-34s %8s %12s %9s %9s %9s %9s %10s",
          "scenario", "threads", "lines/sec", "p50 us", "p99 us", "p99.9 us", "max us", "drain ms"));
      for (int threads : new int[] {1, 8}) {
        report("application.log only", threads, run(log, threads, totalLines), -1);

        LogStore store = store(new File(work, "capture-" + threads), 64);
        LogCapture.install(store);
        Result r = run(log, threads, totalLines);
        // Time for anything still queued to reach disk once the callers are done.
        long drainStart = System.nanoTime();
        LogCapture.uninstall();
        report("+ admin log capture", threads, r, (System.nanoTime() - drainStart) / 1000000);
      }

      searchBenchmark(new File(work, "search"));
    } finally {
      delete(work);
    }
  }

  private static LogStore store(File dir, long scanBudgetMB) {
    return new LogStore(dir, 5L * 1024 * 1024, 20, 5000, scanBudgetMB * 1024 * 1024);
  }

  private static FileAppender<ILoggingEvent> applicationLog(LoggerContext ctx, File file) {
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(ctx);
    encoder.setPattern("%date - [%level] - from %logger in %thread %n%message%n%xException%n");
    encoder.start();
    FileAppender<ILoggingEvent> appender = new FileAppender<ILoggingEvent>();
    appender.setContext(ctx);
    appender.setFile(file.getPath());
    appender.setEncoder(encoder);
    appender.start();
    return appender;
  }

  static class Result {
    long[] latenciesNs;
    long elapsedNs;
  }

  private static Result run(final Logger log, int threads, int totalLines) throws InterruptedException {
    final int perThread = totalLines / threads;
    final long[][] latencies = new long[threads][perThread];
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    for (int t = 0; t < threads; t++) {
      final int id = t;
      new Thread(new Runnable() {
        public void run() {
          try {
            start.await();
          } catch (InterruptedException e) {
            return;
          }
          long[] mine = latencies[id];
          for (int i = 0; i < perThread; i++) {
            long t0 = System.nanoTime();
            log.debug("client send {} event {}", "player-" + id, i);
            mine[i] = System.nanoTime() - t0;
          }
          done.countDown();
        }
      }).start();
    }
    long t0 = System.nanoTime();
    start.countDown();
    done.await();
    Result r = new Result();
    r.elapsedNs = System.nanoTime() - t0;
    r.latenciesNs = new long[threads * perThread];
    for (int t = 0; t < threads; t++) {
      System.arraycopy(latencies[t], 0, r.latenciesNs, t * perThread, perThread);
    }
    Arrays.sort(r.latenciesNs);
    return r;
  }

  private static void report(String name, int threads, Result r, long drainMs) {
    long[] l = r.latenciesNs;
    out.println(String.format("%-34s %8d %,12d %9.1f %9.1f %9.1f %9.1f %10s",
        name, threads,
        (long) (l.length / (r.elapsedNs / 1e9)),
        pct(l, 0.50), pct(l, 0.99), pct(l, 0.999), l[l.length - 1] / 1000.0,
        drainMs < 0 ? "-" : String.valueOf(drainMs)));
  }

  private static double pct(long[] sorted, double p) {
    return sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1000.0;
  }

  /** Fill ~100MB of history, then time one search request that matches nothing (worst case). */
  private static void searchBenchmark(File dir) throws InterruptedException {
    LogStore filler = store(dir, 64);
    String padding = new String(new char[150]).replace('\0', 'x');
    for (int i = 0; i < 500000; i++) {
      filler.append(System.currentTimeMillis(), "DEBUG", "application", "client send player-" + i + " " + padding);
      // Let the writer keep up so the whole fixture lands on disk instead of tripping the backlog cap.
      if (i % 50000 == 0) filler.flush();
    }
    filler.close();

    out.println();
    out.println("Search with no matches over a full 20 x 5MB history (one request, then the whole history):");
    for (long budgetMB : new long[] {64, 16}) {
      LogStore store = store(dir, budgetMB);
      LogStore.Filter filter = new LogStore.Filter("no such text", null);
      long t0 = System.nanoTime();
      LogStore.Page page = store.before(Long.MAX_VALUE, 200, filter);
      long first = System.nanoTime() - t0;
      int requests = 1;
      while (page.hasMore) {
        page = store.before(page.cursor, 200, filter);
        requests++;
      }
      long total = System.nanoTime() - t0;
      out.println(String.format("  scan budget %2d MB: first request %,6d ms; whole history %,6d ms in %d requests",
          budgetMB, first / 1000000, total / 1000000, requests));
      store.close();
    }
  }

  private static void delete(File f) {
    File[] children = f.listFiles();
    if (children != null) for (File c : children) delete(c);
    f.delete();
  }
}
