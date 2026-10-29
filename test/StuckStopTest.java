import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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
        Global.closeDatabase(url, "", "", 5000);
        FileUtils.deleteQuietly(folder);
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

        long started = System.currentTimeMillis();
        assertFalse(Global.closeDatabase(url, "", "", 500));
        long took = System.currentTimeMillis() - started;
        assertTrue("it gave up after its limit: " + took + " ms", took < 1500);
        assertTrue("still open", lock.exists());

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
}
