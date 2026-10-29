package models;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Logger;
import play.Play;
import play.db.DB;
import play.libs.Json;

import java.io.File;
import java.sql.Connection;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Copies of the database, made while the server runs (H2's BACKUP TO: consistent, nothing is
 * locked). One every breadboard.backup.everyHours hours (24), and before a newer release changes
 * the database; the newest breadboard.backup.keep (14) are kept, in breadboard.backup.dir
 * ("backups", in the server's folder). Each is a zip holding breadboard.h2.db: to go back to one,
 * stop the server, unzip it into db/ (replacing the file there), and start the server again.
 *
 * Only for a database in a file (H2): an in-memory one (the tests) or another database is skipped.
 * No backup is made while the disk is short of room for one (see {@link #roomProblem}): a full disk
 * stops the database itself.
 */
public final class Backups {

  /** breadboard-2026-10-28-153012.zip, or with a reason: breadboard-2026-10-28-153012-manual.zip */
  public static final Pattern NAME = Pattern.compile("breadboard-\\d{4}-\\d{2}-\\d{2}-\\d{6}(-[a-z0-9]+)?\\.zip");

  /** Room left free beside a copy of the database (see {@link #roomProblem}). */
  static final long SPARE_BYTES = 100L * 1024 * 1024;

  private static ScheduledExecutorService scheduler;
  private static volatile String lastProblem;

  private Backups() {}

  /** Whether backups are made: on, unless switched off or the database isn't an H2 file. */
  public static boolean enabled() {
    if (!Play.application().configuration().getBoolean("breadboard.backup.enabled", true)) {
      return false;
    }
    String driver = Play.application().configuration().getString("db.default.driver", "");
    String url = Play.application().configuration().getString("db.default.url", "");
    return driver.contains("h2") && url.startsWith("jdbc:h2:") && !url.startsWith("jdbc:h2:mem:");
  }

  public static File folder() {
    String dir = Play.application().configuration().getString("breadboard.backup.dir", "backups");
    File folder = new File(dir);
    return folder.isAbsolute() ? folder : new File(Play.application().path(), dir);
  }

  static int keep() {
    return Math.max(1, Play.application().configuration().getInt("breadboard.backup.keep", 14));
  }

  static int everyHours() {
    return Math.max(1, Play.application().configuration().getInt("breadboard.backup.everyHours", 24));
  }

  /**
   * The database's file (H2 keeps the database db/breadboard in db/breadboard.h2.db, relative to the
   * server's working folder), or null when it isn't an H2 file (in memory, or on a database server).
   */
  public static File databaseFile() {
    return databaseFile(Play.application().configuration().getString("db.default.driver", ""),
        Play.application().configuration().getString("db.default.url", ""));
  }

  /** {@link #databaseFile()} for the database settings {@code driver} and {@code url}. */
  public static File databaseFile(String driver, String url) {
    if (!driver.contains("h2") || !url.startsWith("jdbc:h2:")) {
      return null;
    }
    String path = url.substring("jdbc:h2:".length());
    int settings = path.indexOf(';');
    if (settings >= 0) {
      path = path.substring(0, settings);
    }
    if (path.startsWith("file:")) {
      path = path.substring("file:".length());
    } else if (path.matches("^[A-Za-z]{2,}:.*")) {
      // mem:, tcp:, ssl:, zip:, ... (not a Windows drive, C:)
      return null;
    }
    if (path.startsWith("~")) {
      path = System.getProperty("user.home") + path.substring(1);
    }
    return new File(path + ".h2.db").getAbsoluteFile();
  }

  /** The space free for this server on the disk that holds {@code where} (or would, once made), in bytes. */
  public static long freeSpace(File where) {
    File existing = where.getAbsoluteFile();
    while (existing != null && !existing.exists()) {
      existing = existing.getParentFile();
    }
    return existing == null ? 0L : existing.getUsableSpace();
  }

  /**
   * Null when {@code freeBytes} leave room for a copy of a database of {@code databaseBytes}: about
   * twice its size plus 100 MB, so that a backup never fills the disk. Otherwise why no backup is
   * made, for the console.
   */
  static String roomProblem(long freeBytes, long databaseBytes) {
    long needed = roomNeeded(databaseBytes);
    if (freeBytes >= needed) {
      return null;
    }
    return "No backup was made: " + megabytes(freeBytes) + " MB is free on the disk, and a backup needs about "
        + megabytes(needed) + " MB (twice the database, plus 100 MB to spare). Free some space, or set "
        + "breadboard.backup.dir to a folder on another disk.";
  }

  /** The free space a copy of a database of {@code databaseBytes} needs: twice its size plus 100 MB. */
  public static long roomNeeded(long databaseBytes) {
    return 2 * databaseBytes + SPARE_BYTES;
  }

  /** {@link #roomProblem} for this server's database and backup folder. */
  static String noRoomForBackup() {
    File database = databaseFile();
    return roomProblem(freeSpace(folder()), database == null ? 0L : database.length());
  }

  public static long megabytes(long bytes) {
    return bytes / (1024 * 1024);
  }

  /** Back up when the newest backup is older than everyHours, checking every 30 minutes. */
  public static synchronized void start() {
    if (!enabled() || scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "breadboard-backups");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(() -> {
      try {
        if (due()) {
          String noRoom = noRoomForBackup();
          if (noRoom != null) {
            // tried again at the next check, once there is room
            lastProblem = noRoom;
            Logger.warn("Backups: " + noRoom);
            return;
          }
          backUp(null);
        }
      } catch (Throwable t) {
        // kept for the console; the next check tries again
        Logger.error("Backups: could not back up the database", t);
      }
    }, 2, 30 * 60, TimeUnit.SECONDS);
  }

  public static synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  static boolean due() {
    List<File> all = list();
    if (all.isEmpty()) {
      return true;
    }
    long newest = all.get(0).lastModified();
    // (a backup made by hand counts too)
    return System.currentTimeMillis() - newest >= TimeUnit.HOURS.toMillis(everyHours());
  }

  /**
   * Back up now. {@code why} goes in the name ("manual", "upgrade"; null for the regular one). Returns
   * the new file; throws when the database could not be copied, or the disk has no room for a copy
   * (the reason is also kept for status).
   */
  public static synchronized File backUp(String why) throws Exception {
    String noRoom = noRoomForBackup();
    if (noRoom != null) {
      lastProblem = noRoom;
      throw new IllegalStateException(noRoom);
    }
    try (Connection connection = DB.getConnection()) {
      File target = backUpTo(connection, folder(), why, keep());
      lastProblem = null;
      Logger.info("Backups: saved " + target.getAbsolutePath() + " (" + target.length() / 1024 + " KB)");
      return target;
    } catch (Exception e) {
      lastProblem = "The last backup failed: " + e.getMessage();
      throw e;
    }
  }

  /** Back up the database of {@code connection} into {@code folder}, keeping the newest {@code keep}. */
  public static File backUpTo(Connection connection, File folder, String why, int keep) throws Exception {
    if (!folder.isDirectory() && !folder.mkdirs()) {
      throw new IllegalStateException("Couldn't make the folder " + folder.getAbsolutePath() + ".");
    }
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd-HHmmss");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    String name = "breadboard-" + format.format(new Date()) + (why == null ? "" : "-" + why) + ".zip";
    File target = new File(folder, name);
    // two in the same second: the second waits for a name of its own
    for (int i = 0; target.exists() && i < 3; i++) {
      Thread.sleep(1000);
      name = "breadboard-" + format.format(new Date()) + (why == null ? "" : "-" + why) + ".zip";
      target = new File(folder, name);
    }
    File partial = new File(folder, name + ".part");
    try (Statement statement = connection.createStatement()) {
      statement.execute("BACKUP TO '" + partial.getAbsolutePath().replace("'", "''") + "'");
    } catch (Exception e) {
      partial.delete();
      throw e;
    }
    if (!partial.renameTo(target)) {
      partial.delete();
      throw new IllegalStateException("Couldn't write " + target.getAbsolutePath() + ".");
    }
    prune(folder, keep);
    return target;
  }

  /** Delete all but the newest {@code keep} backups in {@code folder}, and pieces of failed ones. */
  static void prune(File folder, int keep) {
    List<File> all = list(folder);
    for (int i = keep; i < all.size(); i++) {
      if (!all.get(i).delete()) {
        Logger.warn("Backups: couldn't delete the old backup " + all.get(i));
      }
    }
    File[] partials = folder.listFiles((dir, name) -> name.endsWith(".zip.part"));
    if (partials != null) {
      for (File partial : partials) {
        partial.delete();
      }
    }
  }

  /** The backups, newest first. */
  public static List<File> list() {
    return list(folder());
  }

  public static List<File> list(File folder) {
    File[] files = folder.listFiles((dir, name) -> NAME.matcher(name).matches());
    if (files == null) {
      return Collections.emptyList();
    }
    List<File> all = new ArrayList<File>(Arrays.asList(files));
    // by the time in the name (to the second), then by the file's time
    all.sort((a, b) -> {
      int byName = stamp(b).compareTo(stamp(a));
      return byName != 0 ? byName : Long.compare(b.lastModified(), a.lastModified());
    });
    return all;
  }

  private static String stamp(File backup) {
    return backup.getName().substring(0, "breadboard-0000-00-00-000000".length());
  }

  /** The backup with this name, or null (for any other name, so nothing else can be fetched). */
  public static File find(String name) {
    return find(folder(), name);
  }

  public static File find(File folder, String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      return null;
    }
    File file = new File(folder, name);
    return file.isFile() ? file : null;
  }

  /** {enabled, folder, keep, everyHours, problem, backups: [{name, size, createdAt}]} for the console. */
  public static ObjectNode statusJson() {
    ObjectNode json = Json.newObject();
    boolean on = enabled();
    json.put("enabled", on);
    json.put("folder", folder().getAbsolutePath());
    json.put("keep", keep());
    json.put("everyHours", everyHours());
    if (lastProblem == null) {
      json.putNull("problem");
    } else {
      json.put("problem", lastProblem);
    }
    ArrayNode backups = json.putArray("backups");
    if (on) {
      for (File file : list()) {
        ObjectNode row = backups.addObject();
        row.put("name", file.getName());
        row.put("size", file.length());
        row.put("createdAt", Timestamps.iso(new Date(file.lastModified())));
      }
    }
    return json;
  }
}
