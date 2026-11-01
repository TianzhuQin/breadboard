package models;

import org.junit.Test;

import javax.persistence.PersistenceException;
import java.io.File;
import java.sql.SQLException;

import static org.junit.Assert.*;

/**
 * What keeps a full disk or a long value from stopping the server: a backup is skipped when the disk
 * lacks room for it (about twice the database, plus 100 MB), and an event value too long for the
 * database is cut to fit, marked as such.
 */
public class StorageLimitsTest {

    private static final long MB = 1024L * 1024;

    @Test
    public void aBackupNeedsTwiceTheDatabasePlus100Mb() {
        assertNull(Backups.roomProblem(2000 * MB, 500 * MB));
        assertNull("exactly enough", Backups.roomProblem(1100 * MB, 500 * MB));
        String problem = Backups.roomProblem(1099 * MB, 500 * MB);
        assertNotNull(problem);
        assertTrue(problem, problem.startsWith("No backup was made: 1099 MB is free"));
        assertTrue(problem, problem.contains("about 1100 MB"));
        assertTrue(problem, problem.contains("breadboard.backup.dir"));
        // an empty (or in-memory) database still leaves 100 MB
        assertNotNull(Backups.roomProblem(99 * MB, 0));
        assertNull(Backups.roomProblem(100 * MB, 0));
        assertEquals(2 * 300 * MB + 100 * MB, Backups.roomNeeded(300 * MB));
    }

    @Test
    public void theDatabaseFileComesFromTheDatabaseUrl() {
        assertEquals(new File("db/breadboard.h2.db").getAbsoluteFile(),
            Backups.databaseFile("org.h2.Driver", "jdbc:h2:db/breadboard;MODE=MYSQL"));
        assertEquals(new File("/srv/bb/data.h2.db"), Backups.databaseFile("org.h2.Driver", "jdbc:h2:file:/srv/bb/data;MODE=MYSQL"));
        assertEquals(new File(System.getProperty("user.home"), "bb.h2.db"), Backups.databaseFile("org.h2.Driver", "jdbc:h2:~/bb"));
        assertNull(Backups.databaseFile("org.h2.Driver", "jdbc:h2:mem:breadboard-test;MODE=MYSQL"));
        assertNull(Backups.databaseFile("org.h2.Driver", "jdbc:h2:tcp://localhost/~/bb"));
        assertNull(Backups.databaseFile("com.mysql.jdbc.Driver", "jdbc:mysql://localhost/bb"));
    }

    @Test
    public void freeSpaceIsMeasuredWhereAFolderWillBe() {
        File missing = new File(System.getProperty("java.io.tmpdir"), "not-there-yet/backups");
        assertTrue(Backups.freeSpace(missing) > 0);
        assertEquals(Backups.freeSpace(new File(System.getProperty("java.io.tmpdir"))), Backups.freeSpace(missing), 64 * MB);
    }

    @Test
    public void aLongValueIsCutToFitAndMarked() {
        assertNull(EventTracker.cut(null, 10));
        assertEquals("short", EventTracker.cut("short", 255));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append('x');
        String cut = EventTracker.cut(sb.toString(), 255);
        assertEquals(255, cut.length());
        assertTrue(cut, cut.endsWith("…[truncated from 2000 characters]"));

        // never half of an emoji
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 300; i++) emoji.append("😀");
        String cutEmoji = EventTracker.cut(emoji.toString(), 255);
        assertTrue(cutEmoji.length() <= 255);
        String kept = cutEmoji.substring(0, cutEmoji.indexOf('…'));
        assertEquals("whole emoji only", 0, kept.length() % 2);
        assertFalse(Character.isHighSurrogate(kept.charAt(kept.length() - 1)));
    }

    @Test
    public void aValueTooLongForItsColumnIsRecognised() {
        SQLException h2 = new SQLException("Value too long for column \"VALUE VARCHAR(255)\"", "22001", 22001);
        assertTrue(EventTracker.tooLong(new PersistenceException("Query threw SQLException", h2)));
        SQLException other = new SQLException("Referential integrity constraint violation", "23506", 23506);
        assertFalse(EventTracker.tooLong(new PersistenceException("Query threw SQLException", other)));
        assertFalse(EventTracker.tooLong(new RuntimeException("disk full")));
        SQLException batch = new SQLException("batch failed", "HY000");
        batch.setNextException(new SQLException("Data too long for column 'value'", "22001"));
        assertTrue(EventTracker.tooLong(batch));
    }
}
