import com.typesafe.config.ConfigFactory;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Configuration;
import scala.collection.JavaConversions;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Every stop of the server closes the database last, once Play's connection pool has stopped
 * (CloseDatabasePlugin). The pool closes only the connections it holds: with DB_CLOSE_ON_EXIT=FALSE
 * (conf/application-prod.conf), one still out, such as a script's own, kept the database open until
 * the process ended, and H2's last half second of commits was lost with it.
 */
public class CloseDatabasePluginTest extends BaseTest {

    private File folder;
    private String url;
    private File lock;

    @Before
    public void makeDatabase() throws Exception {
        folder = Files.createTempDirectory("bb-close-database").toFile();
        // as conf/application-prod.conf has it
        url = "jdbc:h2:" + new File(folder, "breadboard").getAbsolutePath() + ";MODE=MYSQL;DB_CLOSE_ON_EXIT=FALSE";
        // (there while the database is open; H2 deletes it when the database is closed properly)
        lock = new File(folder, "breadboard.lock.db");
    }

    @After
    public void removeDatabase() {
        Global.closeOwnDatabase(settings(), 5000);
        FileUtils.deleteQuietly(folder);
    }

    /** The server's database settings, as the plugin reads them. */
    private Configuration settings() {
        Map<String, Object> db = new HashMap<>();
        db.put("db.default.driver", "org.h2.Driver");
        db.put("db.default.url", url);
        return new Configuration(ConfigFactory.parseMap(db));
    }

    private static int count(Connection c, String table) throws Exception {
        try (ResultSet rows = c.createStatement().executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Closed: no lock file. H2 deletes it as the database closes, but its lock watchdog can write it
     * again just then (H2 1.3; a busy machine shows it now and then). A file nobody keeps up is taken
     * away here: an open database's watchdog would write it again within a second.
     */
    private boolean closed() throws InterruptedException {
        if (!lock.exists()) {
            return true;
        }
        FileUtils.deleteQuietly(lock);
        Thread.sleep(2500);
        return !lock.exists();
    }

    @Test
    public void aConnectionStillOutIsClosedWithTheDatabaseAndWhatWasCommittedIsKept() throws Exception {
        Connection pooled = DriverManager.getConnection(url, "", "");
        try (Statement s = pooled.createStatement()) {
            s.execute("create table kept (id int)");
            s.executeUpdate("insert into kept values (1)");
        }
        // a script's own connection, never given back
        Connection script = DriverManager.getConnection(url, "", "");
        try (Statement s = script.createStatement()) {
            s.executeUpdate("insert into kept values (2)");
        }
        // the pool stops: it closes the connections it holds
        pooled.close();
        assertTrue("still open: the script's connection keeps it", lock.exists());

        assertTrue(Global.closeOwnDatabase(settings(), Global.CLOSE_DATABASE_MS));
        assertTrue("closed properly: nothing for H2 to recover", closed());
        assertTrue("the script's connection is closed with it", script.isClosed());
        try (Connection again = DriverManager.getConnection(url, "", "")) {
            assertEquals("both rows were written out", 2, count(again, "kept"));
        }
    }

    @Test
    public void itClosesItWithNoDriverLeftInDriverManager() throws Exception {
        Connection script = DriverManager.getConnection(url, "", "");
        try (Statement s = script.createStatement()) {
            s.execute("create table kept (id int)");
            s.executeUpdate("insert into kept values (1)");
        }
        // what play.api.db.BoneCPPlugin.onStop does after closing the pool
        List<Driver> drivers = Collections.list(DriverManager.getDrivers());
        try {
            for (Driver d : drivers) {
                DriverManager.deregisterDriver(d);
            }
            try {
                DriverManager.getConnection(url, "", "").close();
                fail("DriverManager still has a driver for H2");
            } catch (SQLException expected) {
                // No suitable driver found
            }
            assertTrue(Global.closeOwnDatabase(settings(), Global.CLOSE_DATABASE_MS));
            assertTrue(closed());
            assertTrue(script.isClosed());
        } finally {
            for (Driver d : drivers) {
                DriverManager.registerDriver(d);
            }
        }
        try (Connection again = DriverManager.getConnection(url, "", "")) {
            assertEquals(1, count(again, "kept"));
        }
    }

    @Test
    public void aDatabaseClosedAlreadyIsNotOpenedAgainJustToBeClosed() throws Exception {
        try (Connection pooled = DriverManager.getConnection(url, "", "");
             Statement s = pooled.createStatement()) {
            s.execute("create table kept (id int)");
        }
        assertTrue("closed with its last connection", closed());

        // (H2 makes the lock file first thing when it opens the database, and holds it at least 25 ms)
        AtomicBoolean opened = new AtomicBoolean();
        Thread watch = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (lock.exists()) opened.set(true);
            }
        });
        watch.start();
        try {
            assertTrue(Global.closeOwnDatabase(settings(), Global.CLOSE_DATABASE_MS));
        } finally {
            watch.interrupt();
            watch.join();
        }
        assertFalse(opened.get());
    }

    @Test
    public void theEndOfAStopInTheServersOwnProcessClosesTheDatabase() throws Exception {
        // a script's own connection, never given back
        Connection script = DriverManager.getConnection(url, "", "");
        try (Statement s = script.createStatement()) {
            s.execute("create table kept (id int)");
            s.executeUpdate("insert into kept values (1)");
        }
        assertTrue(CloseDatabasePlugin.closeAtEndOfStop(true, settings()));
        assertTrue("closed properly: nothing for H2 to recover", closed());
        assertTrue("the script's connection is closed with it", script.isClosed());
        try (Connection again = DriverManager.getConnection(url, "", "")) {
            assertEquals(1, count(again, "kept"));
        }
    }

    @Test
    public void theEndOfAStopInTheTestsOrSbtLeavesTheDatabaseOpen() throws Exception {
        // (sbt reloads the application in the same process, and the tests go on with their database)
        Connection script = DriverManager.getConnection(url, "", "");
        try (Statement s = script.createStatement()) {
            s.execute("create table kept (id int)");
        }
        assertFalse(CloseDatabasePlugin.closeAtEndOfStop(false, settings()));
        assertTrue("still open", lock.exists());
        assertFalse(script.isClosed());
        try (Statement s = script.createStatement()) {
            s.executeUpdate("insert into kept values (1)");
            assertEquals(1, count(script, "kept"));
        }
        script.close();
    }

    @Test
    public void itStopsAfterTheConnectionPool() {
        // Play stops its plugins in the reverse of this order (conf/play.plugins)
        List<String> plugins = new ArrayList<>();
        for (play.api.Plugin p : JavaConversions.seqAsJavaList(app.getWrappedApplication().plugins())) {
            plugins.add(p.getClass().getName());
        }
        int pool = plugins.indexOf("play.api.db.BoneCPPlugin");
        int ours = plugins.indexOf("CloseDatabasePlugin");
        assertTrue(plugins.toString(), pool >= 0 && ours >= 0 && ours < pool);
    }
}
