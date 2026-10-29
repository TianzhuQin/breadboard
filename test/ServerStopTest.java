import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.WebSocket;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * A normal stop of the server (SIGTERM, Ctrl+C: Global.onStop) while a session runs: the session's
 * data records it, its participants' pages say the session has stopped, and the next start marks it
 * stopped (Global.markInterruptedSessions).
 */
public class ServerStopTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    /** A participant page's socket: the frames it gets, and whether the server closed it. */
    private static final class Page {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { closed = true; }
        }, 0L);
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
    }

    @Test
    public void aRunningSessionIsRecordedAndItsParticipantsAreTold() throws Exception {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        final User user = createUser("stop-" + UUID.randomUUID() + "@test.org", "Stopper", "secret-1", lang);
        final Experiment experiment = createExperiment("Stopped with the server");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");

        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> {
            Experiment selection = User.findByEmail(user.email).selectedExperiment;
            return selection != null && experiment.id.equals(selection.id);
        });
        t.send("LaunchGame", "name", "Live", "parameters", Json.newObject());
        await("running", () -> sessions(experiment).size() == 1
            && sessions(experiment).get(0).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance live = sessions(experiment).get(0);

        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), live.id.toString(), "p1", new WebSocket.In<JsonNode>(), page.out);
        assertFalse(page.closed);

        assertEquals(live.id, ServerStop.endLiveSession());

        List<Event> stopping = Event.find.where().eq("experimentInstance.id", live.id).eq("name", "serverStopping").findList();
        assertEquals(1, stopping.size());
        assertEquals("reason", stopping.get(0).eventData.get(0).name);
        JsonNode last = page.frames.get(page.frames.size() - 1);
        assertEquals(last.toString(), "stopped", last.get("player").get("ended").asText());
        assertTrue(last.toString(), last.get("player").get("text").asText().contains("stopped"));
        assertTrue("its socket is closed", page.closed);
        assertEquals("left for the next start to mark", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);

        // the next start
        Global.markInterruptedSessions();
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(live.id).status);
        assertEquals(1, Event.find.where().eq("experimentInstance.id", live.id).eq("name", "sessionInterrupted").findRowCount());
    }

    @Test
    public void withNoSessionRunningNothingHappens() {
        Experiment experiment = createExperiment("Idle");
        ExperimentInstance finished = createInstance("done", experiment);
        finished.status = ExperimentInstance.Status.FINISHED;
        finished.update();

        assertNull(ServerStop.endLiveSession());
        assertEquals(0, Event.find.where().eq("name", "serverStopping").findRowCount());
    }
}
