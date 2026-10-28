import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Database backups (models.Backups, Settings → Backups), GET /health, and what a restart does to
 * the sessions that were running.
 */
public class BackupsAndHealthTest extends BaseTest {

    @Test
    public void healthSaysOkWithoutASignIn() {
        Result health = route(fakeRequest(GET, "/health"));
        assertEquals(OK, status(health));
        JsonNode body = Json.parse(contentAsString(health));
        assertEquals("ok", body.get("status").asText());
        assertEquals("ok", body.get("database").asText());
    }

    @Test
    public void theBackupListNeedsASignIn() {
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/backups"))));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(POST, "/backups"))));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/backups/breadboard-2026-10-28-120000.zip"))));

        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        Result list = route(fakeRequest(GET, "/backups").withSession("uid", me.uid));
        assertEquals(OK, status(list));
        assertFalse("an in-memory database isn't backed up", Json.parse(contentAsString(list)).get("enabled").asBoolean());
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/backups").withSession("uid", me.uid))));
        assertEquals(NOT_FOUND, status(route(fakeRequest(GET, "/backups/..%2Fconf%2Fapplication.conf").withSession("uid", me.uid))));
    }

    @Test
    public void aBackupIsAZipOfTheDatabaseAndOnlyTheNewestAreKept() throws Exception {
        File dir = Files.createTempDirectory("bb-backups").toFile();
        File folder = new File(dir, "backups");
        try (Connection db = DriverManager.getConnection("jdbc:h2:" + new File(dir, "breadboard").getAbsolutePath() + ";MODE=MYSQL", "", "")) {
            try (Statement s = db.createStatement()) {
                s.execute("create table things (id int primary key, name varchar(20))");
                s.execute("insert into things values (1, 'kept')");
            }
            File first = Backups.backUpTo(db, folder, null, 2);
            assertTrue(first.getName(), Backups.NAME.matcher(first.getName()).matches());
            try (ZipFile zip = new ZipFile(first)) {
                assertNotNull("the zip holds the database file", zip.getEntry("breadboard.h2.db"));
            }
            // made by hand, and once more: only the newest two stay (the same second: by the file's time)
            File second = Backups.backUpTo(db, folder, "manual", 2);
            long now = System.currentTimeMillis();
            first.setLastModified(now - 20000);
            second.setLastModified(now - 10000);
            File third = Backups.backUpTo(db, folder, "upgrade", 2);
            List<File> kept = Backups.list(folder);
            assertEquals(2, kept.size());
            assertEquals("newest first", third, kept.get(0));
            assertFalse("the oldest went", first.exists());
        }
        assertNotNull(Backups.find(folder, Backups.list(folder).get(0).getName()));
        assertNull(Backups.find(folder, "../breadboard.h2.db"));
        assertNull(Backups.find(folder, "breadboard-2026-10-28-120000.zip"));
    }

    @Test
    public void sessionsRunningWhenTheServerStoppedAreMarkedStoppedWithAReason() {
        Experiment experiment = createExperiment("Restarted");
        ExperimentInstance running = createInstance("live", experiment);
        running.status = ExperimentInstance.Status.RUNNING;
        running.update();
        ExperimentInstance finished = createInstance("done", experiment);
        finished.status = ExperimentInstance.Status.FINISHED;
        finished.update();

        Global.markInterruptedSessions();

        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(running.id).status);
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(finished.id).status);
        List<Event> events = Event.find.where().eq("experimentInstance.id", running.id).findList();
        assertEquals(1, events.size());
        assertEquals("sessionInterrupted", events.get(0).name);
        assertEquals(0, Event.find.where().eq("experimentInstance.id", finished.id).findRowCount());
    }
}
