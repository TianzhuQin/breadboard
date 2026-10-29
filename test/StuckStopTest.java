import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * A stop that a stuck script holds up is cut short (Global.stopIfShutdownHangs), and closes the
 * database first: a normal stop closes it at its very end (CloseDatabasePlugin), after the actors,
 * so the file was left open, for H2 to recover at the next start.
 */
public class StuckStopTest {

    private File folder;
    private String url;
    private File lock;
    // the database's own account, when a test gives it one
    private String user = "";
    private String password = "";
    // what the server's log ("application") got, once a test watches it
    private ListAppender<ILoggingEvent> logged;
    private Level level;

    @Before
    public void makeDatabase() throws Exception {
        folder = Files.createTempDirectory("bb-stuck-stop").toFile();
        // as conf/application-prod.conf has it
        url = "jdbc:h2:" + new File(folder, "breadboard").getAbsolutePath() + ";MODE=MYSQL;DB_CLOSE_ON_EXIT=FALSE";
        // (there while the database is open; H2 deletes it when the database is closed properly)
        lock = new File(folder, "breadboard.lock.db");
    }

    @After
    public void removeDatabase() {
        if (logged != null) {
            Logger log = (Logger) LoggerFactory.getLogger("application");
            log.detachAppender(logged);
            log.setLevel(level);
        }
        Global.closeDatabase(url, user, password, 5000);
        FileUtils.deleteQuietly(folder);
    }

    /** From here on, keeps what the server's log gets (until the test ends). */
    private void watchLog() {
        Logger log = (Logger) LoggerFactory.getLogger("application");
        logged = new ListAppender<>();
        logged.start();
        log.addAppender(logged);
        level = log.getLevel();
        log.setLevel(Level.WARN);
    }

    private String said() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    public void theDatabaseIsClosedForEveryConnectionToIt() throws Exception {
        Connection pool = DriverManager.getConnection(url, "", "");
        try (Statement s = pool.createStatement()) {
            s.execute("create table kept (id int)");
            s.executeUpdate("insert into kept values (1)");
        }
        assertTrue("open", lock.exists());

        assertTrue(Global.closeDatabase(url, "", "", 3000));
        assertTrue("the pool's connection is closed with it", pool.isClosed());
        assertFalse("closed properly: nothing for H2 to recover", lock.exists());
        try (Connection again = DriverManager.getConnection(url, "", "");
             ResultSet rows = again.createStatement().executeQuery("select count(*) from kept")) {
            rows.next();
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    public void itWaitsNoLongerThanItsLimitForADatabaseThatIsBusy() throws Exception {
        Connection pool = DriverManager.getConnection(url, "", "");
        try (Statement s = pool.createStatement()) {
            s.execute("create alias pause_for for \"java.lang.Thread.sleep\"");
        }
        // H2 runs one statement at a time: this one holds the database for 3 seconds
        Thread busy = new Thread(() -> {
            try (Statement s = pool.createStatement()) {
                s.execute("call pause_for(3000)");
            } catch (Exception closedUnderIt) {
                // the database closes as soon as it is done
            }
        });
        busy.start();
        Thread.sleep(300);

        watchLog();
        long started = System.currentTimeMillis();
        assertFalse(Global.closeDatabase(url, "", "", 500));
        long took = System.currentTimeMillis() - started;
        assertTrue("it gave up after its limit: " + took + " ms", took < 1500);
        assertTrue("still open", lock.exists());
        assertTrue(said(), said().contains("a statement was still running"));

        // The attempt goes on, and closes it once the statement is done.
        busy.join(10000);
        long until = System.currentTimeMillis() + 10000;
        while (lock.exists() && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertFalse("closed once the database was free", lock.exists());
        assertTrue(pool.isClosed());
    }

    @Test
    public void aDatabaseThatIsNotThereIsNotMade() {
        assertFalse(Global.closeDatabase("jdbc:h2:" + new File(folder, "none").getAbsolutePath() + ";MODE=MYSQL;", "", "", 3000));
        assertFalse(new File(folder, "none.h2.db").exists());
    }

    @Test
    public void aUrlThatSaysIfExistsItselfIsClosedToo() throws Exception {
        Connection pool = DriverManager.getConnection(url, "", "");
        try (Statement s = pool.createStatement()) {
            s.execute("create table kept (id int)");
        }
        // (H2 refused it written another way beside the one closeDatabase adds: "Duplicate property")
        assertTrue(Global.closeDatabase(url + ";ifexists=true", "", "", 3000));
        assertTrue(pool.isClosed());
        assertFalse(lock.exists());
    }

    @Test
    public void aUrlWithItsOwnUserAndPasswordIsClosedWithThem() throws Exception {
        user = "researcher";
        password = "secret";
        Connection pool = DriverManager.getConnection(url, user, password);
        try (Statement s = pool.createStatement()) {
            s.execute("create table kept (id int)");
        }
        // (the settings' user and password, none here, beside the URL's: "Duplicate property USER")
        assertTrue(Global.closeDatabase(url + ";USER=researcher;Password=secret", "", "", 3000));
        assertTrue(pool.isClosed());
        assertFalse(lock.exists());
    }

    @Test
    public void whatTheAttemptRanIntoIsLoggedNotABusyDatabase() throws Exception {
        user = "researcher";
        password = "secret";
        Connection pool = DriverManager.getConnection(url, user, password);
        watchLog();
        assertFalse(Global.closeDatabase(url, user, "not the password", 3000));
        assertTrue("still open", lock.exists());
        assertTrue(said(), said().contains("Wrong user name or password"));
        assertFalse(said(), said().contains("a statement was still running"));
        pool.close();
    }
}
