import play.Application;
import play.Logger;
import play.Plugin;

/**
 * Closes the server's database at the very end of a stop, once Play's connection pool has stopped
 * (conf/play.plugins). The pool closes only the connections it holds: one still out, such as a
 * script's own (play.db.DB.getConnection() in the Script console), kept the database open, and with
 * DB_CLOSE_ON_EXIT=FALSE (conf/application-prod.conf) nothing else closed it. The process then ended
 * with H2's last half second of commits (it writes them out every 500 ms) still in memory, the
 * stop's own serverStopping event among them, and left the database for H2 to recover.
 */
public class CloseDatabasePlugin extends Plugin {

  private final Application app;

  public CloseDatabasePlugin(Application app) {
    this.app = app;
  }

  @Override
  public void onStop() {
    // (the server's own process, as in Global.onStart: not the tests', nor sbt's, which reloads)
    if (app.isProd() && !Global.closeOwnDatabase(app.configuration(), Global.CLOSE_DATABASE_MS)) {
      Logger.warn("The database did not close in time (a statement was still running); H2 recovers it at the next start");
    }
  }
}
