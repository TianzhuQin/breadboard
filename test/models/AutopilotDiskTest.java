package models;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * The autopilot opens no session while the disk that holds the database is almost full: which folder
 * that is (the H2 file's, from db.default.url), and when it counts as full. No Play application.
 */
public class AutopilotDiskTest {

    private final Supplier<Long> realFreeSpace = Autopilot.freeDiskBytes;

    @After
    public void restore() {
        Autopilot.freeDiskBytes = realFreeSpace;
    }

    @Test
    public void theDatabaseFolderIsTheOneItsFileIsIn() throws Exception {
        File dir = Files.createTempDirectory("bb-disk").toFile();
        File db = new File(dir, "db");
        assertTrue(db.mkdir());
        String path = db.getAbsolutePath();
        assertEquals(db.getAbsoluteFile(), Autopilot.databaseFolder("jdbc:h2:" + path + "/breadboard;MODE=MYSQL"));
        assertEquals(db.getAbsoluteFile(), Autopilot.databaseFolder("jdbc:h2:file:" + path + "/breadboard;AUTO_SERVER=TRUE"));
        assertTrue("its disk has room", Autopilot.databaseFolder("jdbc:h2:" + path + "/breadboard").getUsableSpace() > 0);
        // A folder not made yet: the nearest one above it, on the same disk.
        assertEquals(dir.getAbsoluteFile(), Autopilot.databaseFolder("jdbc:h2:" + dir.getAbsolutePath() + "/later/breadboard"));
        // Relative to the folder the server runs in, as H2 reads it (db/ there, or that folder itself).
        File here = new File("").getAbsoluteFile();
        File relative = Autopilot.databaseFolder("jdbc:h2:db/breadboard;MODE=MYSQL");
        assertTrue(String.valueOf(relative), new File(here, "db").equals(relative) || here.equals(relative));

        // None to look at: in memory, on a server, not H2.
        assertNull(Autopilot.databaseFolder("jdbc:h2:mem:breadboard-test;MODE=MYSQL;DB_CLOSE_DELAY=-1"));
        assertNull(Autopilot.databaseFolder("jdbc:h2:tcp://localhost/~/breadboard"));
        assertNull(Autopilot.databaseFolder("jdbc:mysql://localhost/breadboard"));
        assertNull(Autopilot.databaseFolder(null));
    }

    // (No lambdas in this package: Ebean's enhancer, which reads every class in models, can't parse them.)
    private static Supplier<Long> free(final Long bytes) {
        return new Supplier<Long>() {
            public Long get() {
                return bytes;
            }
        };
    }

    @Test
    public void lessThanAHundredMegabytesFreeIsFull() {
        Autopilot.freeDiskBytes = free(99L * 1024 * 1024);
        String full = Autopilot.diskFull();
        assertNotNull(full);
        assertTrue(full, full.startsWith("The disk is almost full (99 MB free"));
        assertTrue(full, full.contains("no new session opens until there is room"));

        Autopilot.freeDiskBytes = free(100L * 1024 * 1024);
        assertNull(Autopilot.diskFull());
        // Unknown (a database in memory or on a server), or it couldn't be told: never in the way.
        Autopilot.freeDiskBytes = free(null);
        assertNull(Autopilot.diskFull());
        Autopilot.freeDiskBytes = new Supplier<Long>() {
            public Long get() {
                throw new IllegalStateException("no application");
            }
        };
        assertNull(Autopilot.diskFull());
    }
}
