import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import controllers.LanguageController;
import models.*;
import org.apache.commons.io.FileUtils;
import play.Play;
import play.Application;
import play.Configuration;
import play.GlobalSettings;
import play.Logger;
import play.db.DB;
import play.libs.F;
import play.libs.Yaml;
import play.api.PlayException;
import play.api.db.DBApi;
import play.api.db.DBPlugin;
import play.api.db.evolutions.DownScript;
import play.api.db.evolutions.Evolutions$;
import play.api.mvc.EssentialFilter;
import scala.collection.JavaConversions;
import play.mvc.Action;
import play.mvc.Http;

import java.io.*;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class Global extends GlobalSettings {

  static Process process = null;

  @Override
  public void onStart(Application app) {

    // For the server's own process (a release, bin/run-local), not the tests' or sbt's.
    if (app.isProd()) {
      logUncaughtErrors();
      stopIfShutdownHangs(app.configuration());
      exitIfActorsStop();
      keepEbeanThroughTheStop();
    }

    backUpBeforeUpgrade(app);
    applyNewEvolutions(app);
    // Before anything reads Experiment, which maps this column: a database built from evolutions
    // 1-29 (v2.3) lacks it, and so does a v2.2 one, which the migration below reads.
    version2Point4Upgrade();

    String sql = "select count(*) as table_count from information_schema.tables where table_name = 'breadboard_version';";
    SqlRow versionTableCount = Ebean.createSqlQuery(sql).findUnique();
    String count = versionTableCount.getString("table_count");

    // If the breadboard_version table doesn't exist we're < v2.3
    if (count.equals("0")) {

      // Create the breadboard_version table
      sql = "create table breadboard_version ( version varchar(255) ); ";
      Ebean.createSqlUpdate(sql).execute();
      // Update the version
      String version = play.Play.application().configuration().getString("application.version");
      sql = "insert into breadboard_version values ('" + version + "'); ";
      Ebean.createSqlUpdate(sql).execute();

      // Create the languages table
      sql = "create table if not exists languages ( id bigint not null, code varchar(8), name varchar(255), " +
          "constraint pk_language primary key (id) ); ";
      Ebean.createSqlUpdate(sql).execute();

      // Create the experiment_languages table
      sql = "create table if not exists experiments_languages " +
          "( experiments_id bigint not null, languages_id bigint not null, " +
          "foreign key (experiments_id) references experiments(id), " +
          "foreign key (languages_id) references languages(id) );";
      Ebean.createSqlUpdate(sql).execute();

      // Create the translations table
      sql = "create table if not exists translations ( id bigint not null, html text, content_id bigint not null, languages_id bigint not null," +
          "foreign key (content_id) references content(id)," +
          "foreign key (languages_id) references languages(id)," +
          "constraint pk_translations primary key (id) ); ";
      Ebean.createSqlUpdate(sql).execute();

      // Add the auto increment sequences
      sql = "create sequence if not exists languages_seq; ";
      Ebean.createSqlUpdate(sql).execute();
      sql = "create sequence if not exists experiments_languages_seq; ";
      Ebean.createSqlUpdate(sql).execute();
      sql = "create sequence if not exists translations_seq; ";
      Ebean.createSqlUpdate(sql).execute();

      // Add the uid column to experiments
      sql = "alter table experiments add column if not exists uid varchar(255); ";
      Ebean.createSqlUpdate(sql).execute();

      // Add default language column to users
      sql = "alter table users add column if not exists default_language_id bigint; ";
      Ebean.createSqlUpdate(sql).execute();

      sql = "alter table users add constraint fk_default_language_languages " +
          "foreign key (default_language_id) references languages (id) " +
          "on delete restrict on update restrict; ";
      Ebean.createSqlUpdate(sql).execute();

      sql = "create index if not exists ix_users_default_language on users (default_language_id); ";
      Ebean.createSqlUpdate(sql).execute();

      // Changes to support new AMT dialog
      sql = "alter table amt_assignments add column if not exists bonus_amount varchar(255); ";
      Ebean.createSqlUpdate(sql).execute();

      sql = "alter table amt_hits alter column experiment_instance_id set null; ";
      Ebean.createSqlUpdate(sql).execute();

      // Additional schema changes for older versions of breadboard pre v2.1
      sql = "alter table experiments add column if not exists client_graph text; ";
      Ebean.createSqlUpdate(sql).execute();
      sql = "alter table experiments add column if not exists client_html text; ";
      Ebean.createSqlUpdate(sql).execute();

      // Messages table
      sql = "create table messages (\n" +
          "  id                        bigint not null,\n" +
          "  message_uid               varchar(36),\n" +
          "  message_title             varchar(255),\n" +
          "  message_html              text,\n" +
          "  priority                  tinyint,\n" +
          "  auto_open                 bit,\n" +
          "  created_at                timestamp,\n" +
          "  dismissed_at              timestamp\n" +
          ");\n";
      Ebean.createSqlUpdate(sql).execute();

      // Migrate data
      LanguageController.seedLanguages();

      Language english = Language.findByIso3("eng");
      if (english == null) {
        Logger.debug("Unable to find english in languages.");
        english = Language.findAll().get(0);
      }

      for (User user : User.findAll()) {
        user.defaultLanguage = english;
        user.save();
      }

      for (Experiment experiment : Experiment.findAll()) {
        experiment.languages.add(english);
        experiment.uid = UUID.randomUUID().toString();

        try {
          File experimentDirectory = new File(Play.application().path().toString() + "/experiments/" + experiment.name + "_v2.2");
          FileUtils.writeStringToFile(new File(experimentDirectory, "client.html"), experiment.clientHtml);
          FileUtils.writeStringToFile(new File(experimentDirectory, "client-graph.js"), experiment.clientGraph);
          // Through the setters: plain writes to these fields from here were never saved, so
          // upgraded experiments kept their v2.2 page.
          experiment.setClientGraph(Experiment.defaultClientGraph());
          // The client HTML this upgrade always gave them, not the newer participant page.
          experiment.setClientHtml(Experiment.legacyClientHTML());
        } catch (IOException ioe) {
          Logger.error("Error backing up v2.2 client.html and client.js to experiments directory");
        }

        experiment.save();
      }

      for (Content content : Content.findAll()) {
        Translation translation = new Translation();
        translation.language = english;
        translation.setHtml(content.html);
        content.translations.add(translation);
        content.save();
      }

      // TODO: Add message telling user what was done
      // TODO: Load v2.3 version notes as message from file system

      // Upgrade to version 2.4.0
      version2Point4Upgrade();
    } else {
      // The breadboard_version table exists, let's check if we're v2.3 or v2.4
      sql = "select version from breadboard_version limit 1;";
      SqlRow versionString = Ebean.createSqlQuery(sql).findUnique();
      String version = versionString != null ? versionString.getString("version") : "";
      if (version.equals("v2.3.0") || version.equals("v2.3.1")) {
        // Add v2.4.0 fields
        version2Point4Upgrade();
      } // Otherwise, no upgrade needed
    }
    // A database built from the evolutions has an empty breadboard_version: record the version that
    // brought it up to date, for later upgrades.
    String version = app.configuration().getString("application.version");
    if (version != null && tableRows("breadboard_version") == 0) {
      Ebean.createSqlUpdate("insert into breadboard_version values (:version)").setParameter("version", version).execute();
    } else if (version != null) {
      // the release this database is up to date with (backUpBeforeUpgrade compares with it)
      Ebean.createSqlUpdate("update breadboard_version set version = :version").setParameter("version", version).execute();
    }

    widenEventValues();

    // Interpolated Groovy strings must reach browsers as text.
    models.JsonSetup.install();

    resetPasswordIfAsked();

    // Evolution 33, for databases from older releases (it does nothing where the index exists).
    try {
      Ebean.createSqlUpdate("create index if not exists ix_event_data_name on event_data (name)").execute();
    } catch (Exception e) {
      Logger.warn("Could not add the index on event data names (looking up returning Prolific participants is slower without it): " + e.getMessage());
    }
    addLongestSession();
    uncacheSessionIds();

    markInterruptedSessions();

    // What the first account needs (its default language), before the first request: the list was
    // added only when the console's setup page asked for it.
    try {
      LanguageController.languages();
    } catch (Exception e) {
      Logger.warn("Could not add the list of languages (the first request that needs it will): " + e.getMessage());
    }

    // Open a session of the configured experiment so participants can join without an admin.
    models.Autopilot.start();

    models.Backups.start();
  }

  /**
   * Releases run with evolutions off (conf/application-prod.conf), so that Play never rewrites a
   * database copied over from an older release. This does their work where that is safe: an empty
   * database (a new install) gets its tables from the evolution scripts, and one whose recorded
   * evolutions (play_evolutions) reach {@link #HAND_MADE_UNTIL}, e.g. one an older release built
   * from evolutions 1-29, gets the ones it lacks, which all tolerate what is there already. Below
   * that, onStart's own upgrade code is in charge: v2.2 databases record 1-23, and v2.3 and v2.4
   * made what 24-29 make by hand when they upgraded one. Only new evolutions are ever run: when the
   * recorded ones differ from this version's, nothing is, since Play would run their Downs first.
   * With evolutions on, Play has applied them already and nothing is left to run.
   */
  @SuppressWarnings("unchecked")
  private static void applyNewEvolutions(Application app) {
    boolean empty = !tableExists("experiments");
    if (!empty) {
      if (!tableExists("play_evolutions")) {
        return;
      }
      Integer applied = Ebean.createSqlQuery("select max(id) as max_id from play_evolutions where state = 'applied'")
          .findUnique().getInteger("max_id");
      if (applied == null || applied < HAND_MADE_UNTIL) {
        return;
      }
    }
    DBApi api = app.plugin(DBPlugin.class).api();
    try {
      scala.collection.Seq<scala.Product> script =
          Evolutions$.MODULE$.evolutionScript(api, app.path(), app.classloader(), "default");
      List<scala.Product> steps = JavaConversions.seqAsJavaList(script);
      if (steps.isEmpty()) {
        return;
      }
      for (scala.Product step : steps) {
        if (step instanceof DownScript) {
          Logger.warn("The evolutions recorded in this database differ from this version's, so none are applied.");
          return;
        }
      }
      Logger.info(empty ? "The database is empty: creating its tables"
          : "Bringing the database up to date (" + steps.size() + " evolution(s))");
      Evolutions$.MODULE$.applyScript(api, "default", (scala.collection.Seq) script);
    } catch (PlayException e) {
      throw new IllegalStateException("The database could not be brought up to date: " + e.getMessage()
          + ". If this is a new install, stop the server, delete the db folder and start again; "
          + "otherwise restore the database from a backup and report the problem.", e);
    }
  }

  /**
   * Evolution 34, for databases from older releases: the autopilot's longest session, with no
   * default, so settings saved before keep no limit (AutopilotSettings.ADD_LONGEST_SESSION). Does
   * nothing where the column is there. Where the table doesn't exist yet, the autopilot creates it
   * with this column at first use.
   */
  static void addLongestSession() {
    try {
      if (tableExists("autopilot_settings")) {
        Ebean.createSqlUpdate(AutopilotSettings.ADD_LONGEST_SESSION).execute();
      }
    } catch (Exception e) {
      Logger.warn("Could not add the autopilot's longest session to its settings (its sessions then have no time limit): " + e.getMessage());
    }
  }

  /**
   * Session ids follow on after a crash. H2 hands out a sequence's numbers 32 at a time (its cache)
   * and writes down only where each batch ends, so after a server was killed (a crash, a power cut,
   * kill -9) the next session's id jumped by up to 32: sessions 21 to 51 seemed to be missing from
   * Sessions & data. The sequence of session ids keeps no numbers in memory (a cache of 1: each is
   * written as it is handed out), going on from the last one handed out, or from the highest id when
   * that is higher. H2 1.3 can't change a sequence's cache (its ALTER SEQUENCE has no CACHE), so the
   * sequence is made again: once, nothing where its cache is 1 already. One found missing, as after a
   * stop between the two steps, is made. Only on H2 (every configuration Breadboard has).
   */
  static void uncacheSessionIds() {
    try {
      if (!Play.application().configuration().getString("db.default.driver", "").contains("h2")) {
        return;
      }
      SqlRow sequence = Ebean.createSqlQuery("select current_value as current_value, cache as cache_size "
          + "from information_schema.sequences where lower(sequence_name) = 'experiment_instances_seq'").findUnique();
      if (sequence != null && Long.valueOf(1L).equals(sequence.getLong("cache_size"))) {
        return;
      }
      Long current = sequence == null ? null : sequence.getLong("current_value");
      Long highest = Ebean.createSqlQuery("select max(id) as max_id from experiment_instances").findUnique().getLong("max_id");
      long next = Math.max(current == null ? 0L : current, highest == null ? 0L : highest) + 1;
      if (sequence != null) {
        Ebean.createSqlUpdate("drop sequence experiment_instances_seq").execute();
      }
      Ebean.createSqlUpdate("create sequence experiment_instances_seq start with " + next + " cache 1").execute();
    } catch (Exception e) {
      Logger.warn("Could not make session ids follow on after a crash (a crash may then skip some): " + e.getMessage());
    }
  }

  /**
   * Sessions that were running when the server stopped (a restart, a crash, a power cut) can't go
   * on: their game lived in the server's memory. They are marked stopped, with a sessionInterrupted
   * event, so the console shows them as stopped (Resume starts their game again, as for a session a
   * researcher stopped) and nothing takes them for running.
   */
  static void markInterruptedSessions() {
    try {
      List<ExperimentInstance> stale = ExperimentInstance.find.where()
          .in("status", ExperimentInstance.Status.RUNNING, ExperimentInstance.Status.TESTING).findList();
      for (ExperimentInstance instance : stale) {
        instance.status = ExperimentInstance.Status.STOPPED;
        instance.update();
        Event event = new Event();
        event.name = "sessionInterrupted";
        event.experimentInstance = instance;
        EventData why = new EventData();
        why.name = "reason";
        why.value = "The server stopped while this session ran; its game could not go on.";
        event.addEventData(why);
        event.save();
        Logger.warn("Session " + instance.id + " (\"" + instance.name + "\") was running when the server stopped: it is marked stopped");
      }
    } catch (Exception e) {
      Logger.error("Could not mark the sessions that were running when the server stopped", e);
    }
  }

  /**
   * Before a newer release changes a database an older one used, keep a copy of it as it was
   * (models.Backups, named "...-upgrade.zip"): going back to the older release then takes that copy.
   */
  private static void backUpBeforeUpgrade(Application app) {
    String version = app.configuration().getString("application.version");
    if (version == null || !models.Backups.enabled() || !tableExists("experiments")) {
      return;
    }
    try {
      String recorded = null;
      if (tableExists("breadboard_version")) {
        SqlRow row = Ebean.createSqlQuery("select version from breadboard_version limit 1").findUnique();
        recorded = row == null ? null : row.getString("version");
      }
      if (!version.equals(recorded)) {
        Logger.info("This database was last used by " + (recorded == null ? "an older release" : recorded)
            + ": backing it up before bringing it up to date");
        models.Backups.backUp("upgrade");
      }
    } catch (Exception e) {
      Logger.error("Could not back up the database before bringing it up to date", e);
    }
  }

  /** The last evolution whose tables older releases also made by hand (see applyNewEvolutions). */
  private static final int HAND_MADE_UNTIL = 29;

  private static boolean tableExists(String name) {
    SqlRow row = Ebean.createSqlQuery("select count(*) as table_count from information_schema.tables "
        + "where lower(table_name) = :name").setParameter("name", name).findUnique();
    return !"0".equals(row.getString("table_count"));
  }

  private static int tableRows(String table) {
    return Ebean.createSqlQuery("select count(*) as row_count from " + table).findUnique().getInteger("row_count");
  }

  private void version2Point4Upgrade() {
    // Changes to support Experiment fileMode
    String sql = "alter table experiments add column if not exists file_mode bit default 0;";
    Ebean.createSqlUpdate(sql).execute();
  }

  /**
   * event_data.value was varchar(255) (evolution 1), so an event value longer than that (a free-text
   * answer, a JSON value) threw inside the experiment's script. It holds EventTracker.VALUE_LIMIT
   * characters (1 MiB), for databases built from the evolutions and older ones alike. Changed only
   * while it is shorter, since H2 copies the table to change a column; and not while the disk lacks
   * room for that copy (tried again at the next start; until then EventTracker keeps long values cut
   * short). Said in the log only when the table has rows: on tables just made (a new database) it takes
   * no time, and the line read as if something were wrong.
   */
  static void widenEventValues() {
    try {
      SqlRow column = Ebean.createSqlQuery("select type_name as type_name, character_maximum_length as max_length "
          + "from information_schema.columns where lower(table_name) = 'event_data' and lower(column_name) = 'value'")
          .findUnique();
      if (column == null) {
        return;
      }
      Long length = column.getLong("max_length");
      if (!"varchar".equalsIgnoreCase(column.getString("type_name")) || length == null || length >= EventTracker.VALUE_LIMIT) {
        // already long enough (or a text column, which has no limit)
        return;
      }
      File database = models.Backups.databaseFile();
      if (database != null && models.Backups.freeSpace(database) < models.Backups.roomNeeded(database.length())) {
        Logger.warn("Not widening event_data.value now: the disk has too little free space to copy the table. "
            + "Event values longer than " + length + " characters are kept cut short until it has.");
        return;
      }
      String widening = "Widening event_data.value to keep event values of up to " + EventTracker.VALUE_LIMIT
          + " characters (once; on a large database this takes a while)";
      if (Ebean.createSqlQuery("select 1 as one from event_data limit 1").findUnique() == null) {
        Logger.debug(widening);
      } else {
        Logger.info(widening);
      }
      Ebean.createSqlUpdate("alter table event_data alter column value varchar(" + EventTracker.VALUE_LIMIT + ")").execute();
    } catch (Exception e) {
      Logger.warn("Could not widen event_data.value; event values longer than 255 characters are kept cut short: " + e.getMessage());
    }
  }

  /** How long a stop may take before the process ends anyway (see stopIfShutdownHangs). */
  static final long SHUTDOWN_LIMIT_MS = 20000;
  /** How long a stop cut short then waits for the database to close (see closeDatabase). */
  static final long CLOSE_DATABASE_MS = 3000;
  /**
   * What a stop cut short (stopIfShutdownHangs) exits with: 143, as Java does for SIGTERM, so that the
   * launchers (BREADBOARD_KEEP_RUNNING) and service managers take it for the stop that was asked for
   * and don't start the server again; 1 for the server's own stop after a failure (exitIfActorsStop),
   * which is to be started again.
   */
  private static volatile int stuckStopExitCode = 143;
  private static boolean shutdownLimitSet = false;

  /**
   * SIGTERM and Ctrl+C run Play's stop, which waits, with no time limit, for every actor to finish:
   * a script that never ends (while (true) in the Script console or a step) held it up for good,
   * with the server still answering and RUNNING_PID already deleted, so the next start couldn't
   * bind. When a stop is still going after SHUTDOWN_LIMIT_MS, the database is closed (otherwise
   * CloseDatabasePlugin closes it at the very end of the stop, after the actors: the file was left
   * open, for H2 to recover at the next start) and the process ends anyway, with stuckStopExitCode.
   * Once per process: the timer starts with the stop and dies with the process when the stop ends in
   * time (a daemon thread).
   */
  static synchronized void stopIfShutdownHangs(Configuration config) {
    if (shutdownLimitSet) {
      return;
    }
    shutdownLimitSet = true;
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      Thread limit = new Thread(() -> {
        try {
          Thread.sleep(SHUTDOWN_LIMIT_MS);
        } catch (InterruptedException e) {
          return;
        }
        cutStopShort(config);
        Runtime.getRuntime().halt(stuckStopExitCode);
      }, "breadboard-shutdown-limit");
      limit.setDaemon(true);
      limit.start();
    }, "breadboard-shutdown-watch"));
  }

  /**
   * What a stop cut short does before the process ends: says so, and closes the database. When the
   * database can't be closed, the one line that says why (closeDatabase's) goes to the console too,
   * as stopNote's do.
   */
  static void cutStopShort(Configuration config) {
    stopNote("Breadboard: shutdown is taking too long (a script may be stuck); stopping now");
    closeOwnDatabase(config, CLOSE_DATABASE_MS, Global::stopNote);
  }

  /**
   * For a stop cut short: on the console, and in the log while it still works. (The log's lines for
   * the console are written by a thread of their own, conf/logger.xml, which may not get to them
   * before the process ends.)
   */
  private static void stopNote(String what) {
    System.out.println(what);
    System.out.flush();
    logWarning(what);
  }

  /** A warning in the log, and never an exception: a stop cut short halts the process right after. */
  private static void logWarning(String what) {
    try {
      Logger.warn(what);
    } catch (Throwable ignored) {
      // nothing more to do
    }
  }

  /**
   * Closes the server's database (db.default) when it is an H2 file of its own (not one in memory,
   * nor one on a database server) that this process still has open (see openHere). True when it is
   * closed (in time: see closeDatabase) or was already; otherwise the log says why.
   */
  static boolean closeOwnDatabase(Configuration config, long waitMs) {
    return closeOwnDatabase(config, waitMs, Global::logWarning);
  }

  /** {@link #closeOwnDatabase(Configuration, long)}, telling {@code say} why it couldn't close it. */
  static boolean closeOwnDatabase(Configuration config, long waitMs, Consumer<String> say) {
    String url = config.getString("db.default.url", "");
    File data = models.Backups.databaseFile(config.getString("db.default.driver", ""), url);
    if (data == null || !openHere(data)) {
      return true;
    }
    return closeDatabase(url, config.getString("db.default.user", ""),
        config.getString("db.default.password", config.getString("db.default.pass", "")), waitMs, say);
  }

  // How long openHere waits for H2's own answer before it goes by the lock file.
  private static final long ASK_H2_MS = 500;

  /**
   * Whether this process has the H2 database with the data file {@code data} (…/breadboard.h2.db)
   * open. H2 is asked (org.h2.engine.Engine keeps the databases it has open), not its lock file:
   * H2 1.3 deletes that file as it closes a database, but doesn't wait for its lock watchdog, which
   * can write the file again just after. A close of a database closed already then took that file
   * for an open one: it opened the database again just to close it, waited for the stale lock to age
   * and logged that it didn't close in time. When H2 can't say (another version, or not in time),
   * the lock file does, as before.
   */
  static boolean openHere(File data) {
    String path = data.getPath().replaceFirst("\\.h2\\.db$", "");
    Boolean open = openInH2(new File(path));
    return open != null ? open : new File(path + ".lock.db").exists();
  }

  /**
   * H2's answer for openHere ({@code database}: the data file without ".h2.db"), or null when it
   * can't be had. Its list of open databases is read under the lock H2 opens them under (the engine's
   * own: Engine.openSession), on a thread of its own and for at most ASK_H2_MS: a connection stuck
   * on a busy database holds that lock, and a stop cut short must never wait for it.
   */
  static Boolean openInH2(final File database) {
    final Object engine;
    final java.lang.reflect.Field databases;
    try {
      Class<?> h2 = Class.forName("org.h2.engine.Engine");
      databases = h2.getDeclaredField("DATABASES");
      databases.setAccessible(true);
      engine = h2.getMethod("getInstance").invoke(null);
    } catch (Throwable e) {
      return null;
    }
    FutureTask<List<Object>> read = new FutureTask<>(() -> {
      synchronized (engine) {
        return new ArrayList<Object>(((Map<?, ?>) databases.get(null)).values());
      }
    });
    Thread reader = new Thread(read, "breadboard-ask-h2");
    reader.setDaemon(true);
    reader.start();
    try {
      File wanted = database.getCanonicalFile();
      for (Object open : read.get(ASK_H2_MS, TimeUnit.MILLISECONDS)) {
        // (null for a database in memory)
        Object path = open.getClass().getMethod("getDatabasePath").invoke(open);
        if (path != null && new File(path.toString()).getCanonicalFile().equals(wanted)) {
          return true;
        }
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Throwable e) {
      // (not in time, a database closing meanwhile, or an H2 that has these no more)
      return null;
    }
  }

  /**
   * Closes the H2 database at {@code url} for every connection to it (SHUTDOWN, on one of its own),
   * waiting at most {@code waitMs}: true when it was closed in time. A statement running meanwhile
   * holds it up, and one that never ends would for good, hence the limit (the attempt goes on, on its
   * own daemon thread). It connects through H2's driver itself: once Play's connection pool has
   * stopped, DriverManager has no driver left (BoneCPPlugin.onStop takes every one out). When it
   * isn't closed, the log says why: a statement still running, or what the attempt ran into.
   */
  static boolean closeDatabase(String url, String user, String password, long waitMs) {
    return closeDatabase(url, user, password, waitMs, Global::logWarning);
  }

  /** {@link #closeDatabase(String, String, String, long)}, telling {@code say} why it couldn't close it. */
  static boolean closeDatabase(String url, String user, String password, long waitMs, Consumer<String> say) {
    // IFEXISTS: a database that isn't there is not made, empty, just to be closed. H2 refuses a setting
    // given twice with two values ("Duplicate property"), and then nothing was closed: the URL's own
    // IFEXISTS, user and password, in whatever case, are kept instead.
    String trimmed = url.replaceAll(";+$", "");
    String existing = urlSets(trimmed, "IFEXISTS") ? trimmed : trimmed + ";IFEXISTS=TRUE";
    Properties login = new Properties();
    if (!urlSets(trimmed, "USER")) {
      login.setProperty("user", user);
    }
    if (!urlSets(trimmed, "PASSWORD")) {
      login.setProperty("password", password);
    }
    FutureTask<Boolean> close = new FutureTask<>(() -> {
      try (Connection connection = new org.h2.Driver().connect(existing, login);
           Statement statement = connection.createStatement()) {
        statement.execute("shutdown");
      }
      return true;
    });
    Thread closer = new Thread(close, "breadboard-close-database");
    closer.setDaemon(true);
    closer.start();
    String why;
    try {
      return close.get(waitMs, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      why = "did not close within " + waitMs + " ms (a statement was still running)";
    } catch (java.util.concurrent.ExecutionException e) {
      why = "could not be closed: " + e.getCause();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      why = "was not closed: the stop was interrupted";
    }
    // (never an exception from here: a stop cut short halts the process right after this)
    try {
      say.accept("The database " + why + "; H2 recovers it at the next start");
    } catch (Throwable ignored) {
      // nothing more to do
    }
    return false;
  }

  /** Whether the H2 database URL {@code url} gives the setting {@code name} itself (in any case, as H2 reads it). */
  private static boolean urlSets(String url, String name) {
    return java.util.regex.Pattern.compile(";" + name + "=", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url).find();
  }

  /**
   * An exception nothing caught ends its thread; Java prints it on the console only, so a timer that
   * died (java.util.Timer threads, the experiment's own) left no trace in logs/application.log. (Akka's
   * threads have a handler of their own: conf/application-prod.conf sends what it says to the log.)
   */
  static void logUncaughtErrors() {
    Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
      if (e instanceof ThreadDeath) {
        return;
      }
      try {
        Logger.error("Uncaught error in thread " + thread.getName() + ": it has stopped", e);
      } catch (Throwable logging) {
        System.err.println("Uncaught error in thread " + thread.getName() + ":");
        e.printStackTrace();
      }
    });
  }

  /**
   * Ebean stops itself in a JVM shutdown hook of its own, which runs beside Play's stop (onStop, then
   * the actors): what a stop records (serverStopping, participants disconnecting) was saved, but each
   * save logged "NotifyOfCommit failed ... Pool is stopping". Play never stops Ebean otherwise, so the
   * hook is taken back and Ebean ends with the process. (The database itself is closed last, once
   * Play's connection pool has stopped, by CloseDatabasePlugin, or by stopIfShutdownHangs when the
   * stop is cut short: DB_CLOSE_ON_EXIT=FALSE in conf/application-prod.conf.) Ebean is the one Play
   * 2.2 comes with (3.2.2); with any other, nothing is changed.
   */
  static void keepEbeanThroughTheStop() {
    try {
      java.lang.reflect.Field hook = Class.forName("com.avaje.ebeaninternal.server.lib.ShutdownManager")
          .getDeclaredField("shutdownHook");
      hook.setAccessible(true);
      Runtime.getRuntime().removeShutdownHook((Thread) hook.get(null));
    } catch (Exception e) {
      Logger.debug("Ebean's shutdown hook stays: " + e);
    }
  }

  /** Set once the application is being stopped (see exitIfActorsStop). */
  private static volatile boolean stopping = false;

  /**
   * Akka stops its actor system when an actor fails with an error it doesn't handle (a script's
   * StackOverflowError, from runaway recursion, did: "guardian failed, shutting down system"). The
   * server then went on answering, /health said ok, and nothing worked: no engine, no console. The
   * process ends instead (exit code 1), so that the launcher (BREADBOARD_KEEP_RUNNING) or the service
   * manager starts it again.
   */
  static void exitIfActorsStop() {
    play.libs.Akka.system().registerOnTermination(() -> {
      if (stopping) {
        return;
      }
      Logger.error("The actor system that runs the engine and the console has stopped after an error (above): "
          + "stopping the server so that it can be started again");
      // a failure, also if this stop is cut short in turn
      stuckStopExitCode = 1;
      // (not on this thread: the actor system finishes stopping only once this returns)
      new Thread(() -> System.exit(1), "breadboard-exit").start();
    });
  }

  @Override
  public void onStop(Application app){
    stopping = true;
    if(process != null){
      process.destroy();
    }
    // A session in progress ends with the server: its data says so and its participants are told,
    // first (the autopilot, stopped below, must not open another meanwhile).
    try {
      models.ServerStop.endLiveSession();
    } catch (Throwable t) {
      Logger.error("Could not end the running session for its participants", t);
    }
    models.Backups.stop();
    // Its periodic check would otherwise outlive this application (in dev mode, a reload) and open
    // sessions through it beside the new one's.
    try {
      models.Autopilot.stop();
    } catch (Exception e) {
      Logger.error("Could not stop the autopilot", e);
    }
  }

  /**
   * A forgotten password: start the server with -Dbreadboard.resetPassword=<email> (e.g.
   * ./breadboard.sh -Dbreadboard.resetPassword=me@lab.org) and the account gets a new random
   * password, printed on the server's console (not written to the log). Every browser signed in to
   * it is signed out. Start the server without the option afterwards. (Not when a launcher started
   * the server again after it stopped by itself: see below.)
   */
  static void resetPasswordIfAsked() {
    String email = System.getProperty("breadboard.resetPassword");
    if (email == null || email.trim().isEmpty()) {
      return;
    }
    // The launchers start the server again, with the same options, when it stops by itself
    // (BREADBOARD_KEEP_RUNNING): the password was reset at the first start, and stays as it was then.
    String restarted = System.getenv("BREADBOARD_RESTARTED");
    if (restarted != null && !restarted.trim().isEmpty() && !"0".equals(restarted.trim())) {
      System.out.println("breadboard.resetPassword: not reset again after an automatic restart; the password is "
          + "the one shown when the server first started with this option.");
      return;
    }
    User user = User.findByEmail(email);
    if (user == null) {
      System.out.println("breadboard.resetPassword: there is no account " + email.trim()
          + ". The accounts are: " + accountList());
      return;
    }
    String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    java.security.SecureRandom random = new java.security.SecureRandom();
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 16; i++) {
      password.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    user.changePassword(password.toString());
    user.uid = UUID.randomUUID().toString();
    user.update();
    System.out.println();
    System.out.println("  The password of " + user.email + " is now:  " + password);
    System.out.println("  Sign in with it, change it in Settings, and start the server without");
    System.out.println("  -Dbreadboard.resetPassword next time.");
    System.out.println();
    Logger.warn("breadboard.resetPassword: the password of " + user.email + " was reset (shown on the console only)");
  }

  private static String accountList() {
    StringBuilder list = new StringBuilder();
    for (User u : User.findAll()) {
      if (list.length() > 0) list.append(", ");
      list.append(u.email);
    }
    return list.length() == 0 ? "(none)" : list.toString();
  }

  @Override
  public Action onRequest(Http.Request request, Method actionMethod) {
    return super.onRequest(request, actionMethod);
  }

  /**
   * A participant's link that leads nowhere (cut off, or mistyped) gets the participant pages' own
   * notice, readable on a phone, instead of Play's developer page; a session's address without
   * /login goes to its sign-in page.
   */
  @Override
  public F.Promise<play.mvc.SimpleResult> onHandlerNotFound(Http.RequestHeader request) {
    String path = request.path();
    if (path.startsWith("/game/") || path.startsWith("/play/")) {
      java.util.regex.Matcher session = java.util.regex.Pattern.compile("^/game/(\\d+)/(\\d+)/?$").matcher(path);
      if (session.matches()) {
        return F.Promise.pure((play.mvc.SimpleResult) play.mvc.Results.redirect(
            "/game/" + session.group(1) + "/" + session.group(2) + "/login"));
      }
      return F.Promise.pure((play.mvc.SimpleResult) play.mvc.Results.notFound(
          controllers.ClientLogin.invalidLinkPage()));
    }
    return super.onHandlerNotFound(request);
  }

  /**
   * What every response passes through (app/filters): the researcher's session cookie goes out with
   * SameSite=Lax, and the files under /assets can be loaded by participant pages that run in an
   * opaque origin (a researcher's sandboxed preview).
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T extends EssentialFilter> Class<T>[] filters() {
    return new Class[]{filters.SessionCookieSameSite.class, filters.AssetsCors.class};
  }

  static class InitialData {
    public static void insert(Application app) {
      Logger.info("Importing initial data:");

      @SuppressWarnings("unchecked")
      Map<String, List<Object>> all = (Map<String, List<Object>>) Yaml.load("initial-data.yml");

      if (Ebean.find(Experiment.class).findRowCount() == 0) {
        Logger.info("\t importing Experiment");
        Collection allExperiments = all.get("experiments");
        Ebean.save(allExperiments);

        resetSeq("experiments", "experiments_seq");
      }

      if (Ebean.find(User.class).findRowCount() == 0) {
        Logger.info("\t importing User");
        Ebean.save(all.get("users"));

        for (Object user : all.get("users")) {
          Ebean.saveManyToManyAssociations(user, "ownedExperiments");
        }
      }

    }

    /**
     * This is used to reset the auto generated sequence for the h2 database.
     * This is useful because after the initial-data is loaded, the tables' auto generated values are not setting
     * to the right value which is causing #11 in the issue tracker.
     *
     * @param tableName
     * @param seqName
     */
    private static void resetSeq(String tableName, String seqName) {
      Connection connection = DB.getConnection();
      Statement selectStatement = null;

      Long maxId = null;
      try {
        selectStatement = connection.createStatement();
        ResultSet rs = selectStatement.executeQuery("select max(id) from " + tableName + ";");
        while (rs.next()) {
          maxId = rs.getLong(1);
        }

      } catch (SQLException e) {
        Logger.error("Unable to get the current max id.", e);
      } finally {
        if (selectStatement != null) {
          try {
            selectStatement.close();
          } catch (SQLException e) {
            Logger.error("Unable to close the statement.", e);
          }
        }
      }

      if (maxId != null) {
        Statement statement = null;
        try {
          statement = connection.createStatement();
          statement.execute("ALTER SEQUENCE " + seqName + " RESTART WITH " + (maxId + 1) + ";");
        } catch (SQLException e) {
          Logger.error("Unable to alter the sequence.", e);
        } finally {
          if (statement != null) {
            try {
              statement.close();
            } catch (SQLException e) {
              Logger.error("Unable to close the statement.", e);
            }
          }
          try {
            connection.close();
          } catch (SQLException e) {
            Logger.error("Unable to close the connection.", e);
          }
        }
      }
    }
  }
}