package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Backups;
import play.Logger;
import play.Play;
import play.db.DB;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GET /health, for uptime monitors and service managers: 200 {"status": "ok", ...} while the server
 * can write to its database, the disk that holds it has room and its actors (the engine, the
 * console) run, else 503 {"status": "failing", "reason": "..."}. A full disk used to leave the
 * database refusing every write ("Timeout trying to lock table") while a read (select 1) still
 * answered, so each check writes a row (a heartbeat). Says nothing about experiments or people, so it
 * needs no sign-in.
 */
public class HealthController extends Controller {

  /** Less free space than this where the database lives, and the server is about to fail. */
  static final long MIN_FREE_MB = 100;

  /** How long a check waits for the database before saying it doesn't answer. */
  static final long WAIT_SECONDS = 5;

  private static ExecutorService checker;
  /** The last database check; while it hasn't finished (stuck), the next ones wait for it. */
  private static Future<String> pending;
  private static volatile boolean tableReady;
  private static volatile String lastReason;

  public static Result health() {
    String database = databaseProblem();
    long freeMb = Backups.megabytes(Backups.freeSpace(databaseFolder()));
    String reason = join(join(database, diskProblem(freeMb)), actorsProblem());
    noteChange(reason);

    ObjectNode result = Json.newObject();
    result.put("status", reason == null ? "ok" : "failing");
    if (reason != null) {
      result.put("reason", reason);
    }
    result.put("database", database == null ? "ok" : "failing");
    result.put("diskFreeMb", freeMb);
    result.put("version", Play.application().configuration().getString("application.version", ""));
    result.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    response().setHeader("Cache-Control", "no-store");
    return reason == null ? ok(result) : status(SERVICE_UNAVAILABLE, result);
  }

  /** Null when {@code freeMb} is enough, else why not. */
  static String diskProblem(long freeMb) {
    return freeMb < MIN_FREE_MB ? "only " + freeMb + " MB is free on the disk that holds the database" : null;
  }

  /**
   * Akka stops the actor system that runs the engine and the console when an actor fails with an
   * error it doesn't handle; a release then ends the process (Global.exitIfActorsStop).
   */
  static String actorsProblem() {
    return play.libs.Akka.system().isTerminated()
        ? "the actor system that runs the engine and the console has stopped (logs/application.log says why)" : null;
  }

  private static String join(String a, String b) {
    return a == null ? b : b == null ? a : a + "; " + b;
  }

  /** Where the database lives (for an in-memory one, the server's folder: its logs and backups). */
  static File databaseFolder() {
    File database = Backups.databaseFile();
    return database != null ? database.getParentFile() : Play.application().path();
  }

  /**
   * Null when a heartbeat row could be written, else why not. Waits at most {@link #WAIT_SECONDS}: the
   * write runs on a thread of its own, and a check that never comes back is waited for by the next
   * ones instead of starting more (one stuck thread at most).
   */
  static String databaseProblem() {
    Future<String> check;
    synchronized (HealthController.class) {
      if (pending == null || pending.isDone()) {
        if (checker == null) {
          checker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "breadboard-health");
            t.setDaemon(true);
            return t;
          });
        }
        pending = checker.submit(HealthController::writeHeartbeat);
      }
      check = pending;
    }
    try {
      return check.get(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      return "the database did not answer within " + WAIT_SECONDS + " seconds";
    } catch (Exception e) {
      return "the database check failed: " + e;
    }
  }

  /**
   * One row, updated by every check in its own short transaction (a single statement), then a read of
   * the accounts, which every sign-in needs: a write the database never finished (when the disk
   * filled) left that table locked for good. The table is made at first use. Null when both worked
   * (each waits at most H2's lock timeout, a second or two), else what the database said.
   */
  static String writeHeartbeat() {
    try (Connection connection = DB.getConnection(); Statement statement = connection.createStatement()) {
      if (!tableReady) {
        statement.execute("create table if not exists health_check (id int not null, checked_at timestamp, "
            + "constraint pk_health_check primary key (id))");
        tableReady = true;
      }
      statement.executeUpdate("merge into health_check (id, checked_at) key (id) values (1, current_timestamp())");
      statement.executeQuery("select email from users limit 1").close();
      return null;
    } catch (Exception e) {
      // made again by the next check, in case it was what went missing
      tableReady = false;
      return "the database is failing (" + firstLine(e.getMessage()) + ")";
    }
  }

  /** H2 puts the statement and its own codes after the message: the message alone. */
  private static String firstLine(String message) {
    String line = String.valueOf(message);
    int end = line.indexOf('\n');
    line = end > 0 ? line.substring(0, end) : line;
    line = line.replaceFirst(";? *SQL statement:\\s*$", "");
    return line.trim();
  }

  /** Log when the server starts failing, and when it is well again (not every check). */
  private static synchronized void noteChange(String reason) {
    String before = lastReason;
    lastReason = reason;
    if (reason != null && before == null) {
      Logger.error("Health check: failing: " + reason);
    } else if (reason == null && before != null) {
      Logger.info("Health check: ok again");
    }
  }
}
