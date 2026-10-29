import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import controllers.LanguageController;
import models.*;
import org.apache.commons.io.FileUtils;
import play.Play;
import play.Application;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Global extends GlobalSettings {

  static Process process = null;

  @Override
  public void onStart(Application app) {

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

    // Interpolated Groovy strings must reach browsers as text.
    models.JsonSetup.install();

    resetPasswordIfAsked();

    // Evolution 33, for databases from older releases (it does nothing where the index exists).
    try {
      Ebean.createSqlUpdate("create index if not exists ix_event_data_name on event_data (name)").execute();
    } catch (Exception e) {
      Logger.warn("Could not add the index on event data names (looking up returning Prolific participants is slower without it): " + e.getMessage());
    }

    markInterruptedSessions();

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

  @Override
  public void onStop(Application app){
    if(process != null){
      process.destroy();
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
   * it is signed out. Start the server without the option afterwards.
   */
  static void resetPasswordIfAsked() {
    String email = System.getProperty("breadboard.resetPassword");
    if (email == null || email.trim().isEmpty()) {
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