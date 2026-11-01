import play.Application;
import play.Configuration;
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
    closeAtEndOfStop(app.isProd(), app.configuration());
  }

  /**
   * What a stop does last: in the server's own process ({@code prod}, as in Global.onStart), it closes
   * the database; in the tests' and sbt's (which reloads the application), it leaves it open. True
   * when it closed it (or found it closed); when it couldn't, Global.closeDatabase logs why.
   */
  static boolean closeAtEndOfStop(boolean prod, Configuration config) {
    return prod && Global.closeOwnDatabase(config, Global.CLOSE_DATABASE_MS);
  }
}
