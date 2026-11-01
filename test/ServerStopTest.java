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
 * data records it, its participants' pages say the session ended because the server was stopped or
 * restarted (not that a researcher stopped it; a completion code on screen stays), and the next start
 * marks it stopped (Global.markInterruptedSessions).
 */
public class ServerStopTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    @SuppressWarnings("unchecked")
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
        // (a stop's marks last as long as the process, which a real stop ends)
        ((java.util.Map<?, ?>) staticField("endingSessions").get(null)).clear();
        java.util.Map<String, Client> clients = (java.util.Map<String, Client>) staticField("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        security.ParticipantAuth.reset();
        Recruitment.reset();
        try {
            engine().eval("timers.cancel(); null");
        } catch (Exception ignored) {}
    }

    private static Field staticField(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
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
        // Run as each frame arrives (on the thread that writes it), when set.
        volatile java.util.function.Consumer<JsonNode> onFrame;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) {
                frames.add(frame);
                java.util.function.Consumer<JsonNode> hook = onFrame;
                if (hook != null) hook.accept(frame);
            }
            public void close() { closed = true; }
        }, 0L);

        JsonNode last() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }
    }

    private static Page connect(Experiment experiment, ExperimentInstance session, String clientId) {
        Page page = new Page();
        try {
            ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), clientId, new WebSocket.In<JsonNode>(), page.out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return page;
    }

    /** A researcher's console with the experiment selected, and a session of it launched there. */
    private ExperimentInstance launched(final Experiment experiment) throws Exception {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        final User user = createUser("stop-" + UUID.randomUUID() + "@test.org", "Stopper", "secret-1", lang);
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
        return sessions(experiment).get(0);
    }

    /** How many events of this name the session recorded for this participant. */
    private static int events(ExperimentInstance session, String name, String clientId) {
        int n = 0;
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", name).findList()) {
            for (EventData d : e.eventData) {
                if ("clientId".equals(d.name) && clientId.equals(d.value)) n++;
            }
        }
        return n;
    }

    private static String text(JsonNode frame) {
        return frame == null ? "" : frame.path("player").path("text").asText();
    }

    private static javax.script.ScriptEngine engine() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (javax.script.ScriptEngine) f.get(null);
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
        // A second one came from Prolific, and the experiment has given them their completion code.
        Page done = new Page();
        ScriptBoard.addClient(experiment.id.toString(), live.id.toString(), "p2", new WebSocket.In<JsonNode>(), done.out);
        engine().eval("def v = g.getVertex('p2'); recruitment.registerProlific(v); "
            + "recruitment.completeProlific(v, [completionCode: 'C0DE42']); null");

        assertEquals(live.id, ServerStop.endLiveSession());

        List<Event> stopping = Event.find.where().eq("experimentInstance.id", live.id).eq("name", "serverStopping").findList();
        assertEquals(1, stopping.size());
        assertEquals("reason", stopping.get(0).eventData.get(0).name);
        JsonNode last = page.frames.get(page.frames.size() - 1);
        assertEquals(last.toString(), "serverStopped", last.get("player").get("ended").asText());
        String text = last.get("player").get("text").asText();
        assertTrue(text, text.contains("It ended because the study's server was stopped or restarted."));
        assertFalse(text, text.contains("researcher stopped"));
        assertTrue("its socket is closed", page.closed);
        // The one with a completion code keeps it on screen, and is told the session is over all the same.
        JsonNode over = done.frames.get(done.frames.size() - 1);
        assertEquals(over.toString(), "serverStopped", over.path("sessionOver").path("player").path("ended").asText());
        JsonNode screen = null;
        for (JsonNode f : done.frames) if (f.has("player")) screen = f;
        assertEquals("C0DE42", screen.path("player").path("_system").path("recruitment").path("completionCode").asText());
        assertFalse(screen.path("player").has("ended"));
        assertTrue(done.closed);
        assertEquals("left for the next start to mark", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);

        // the next start
        Global.markInterruptedSessions();
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(live.id).status);
        assertEquals(1, Event.find.where().eq("experimentInstance.id", live.id).eq("name", "sessionInterrupted").findRowCount());
    }

    @Test
    public void theGameStopsFirstAndAPageThatComesBackMeanwhileGetsItsEndNotTheGame() throws Exception {
        final Experiment experiment = createExperiment("Stopped as it plays");
        final ExperimentInstance live = launched(experiment);
        Page playing = connect(experiment, live, "p1");
        Page fromProlific = connect(experiment, live, "p2");
        engine().eval("recruitment.registerProlific(g.getVertex('p2')); bot = g.addAI(a, 1, { p -> })[0]; "
            + "fired = false; timers.newTimer().runAfter(400) { fired = true }; null");
        assertEquals(Boolean.TRUE, engine().eval("a.ai.isOn"));
        // As p1's page is told, it comes back (its connection had dropped a moment before), and so does
        // someone new. Both used to be taken into the game, which went on until the process ended.
        final Page[] meanwhile = new Page[2];
        playing.onFrame = f -> {
            if (f.path("player").has("ended") && meanwhile[0] == null) {
                meanwhile[0] = connect(experiment, live, "p1");
                meanwhile[1] = connect(experiment, live, "p9");
            }
        };

        assertEquals(live.id, ServerStop.endLiveSession());

        // what each is told: what happened, and what they can do now
        assertEquals("serverStopped", playing.last().path("player").path("ended").asText());
        assertTrue(text(playing.last()), text(playing.last()).contains("If you were given a link to the study, you can open it again "
            + "in a few minutes to join the session that is open then"));
        assertTrue(playing.closed);
        assertEquals("serverStopped", fromProlific.last().path("player").path("ended").asText());
        assertTrue(text(fromProlific.last()), text(fromProlific.last()).contains("open the study again from Prolific in a few minutes"));
        assertTrue(text(fromProlific.last()), text(fromProlific.last()).contains("return the study on Prolific"));
        assertFalse(text(fromProlific.last()).contains("If you were given a link"));
        // the pages that came meanwhile got the end, not the game, and nobody was added or recorded
        for (Page page : meanwhile) {
            assertNotNull(page);
            assertEquals(page.frames.toString(), 1, page.frames.size());
            assertEquals("serverStopped", page.frames.get(0).path("player").path("ended").asText());
        }
        assertNull(engine().eval("g.getVertex('p9')"));
        assertNull(staticClient("p1"));
        assertNull(staticClient("p9"));
        assertEquals(1, events(live, "clientConnected", "p1"));
        assertEquals(0, events(live, "clientConnected", "p9"));
        // and a page or a sign-in that comes later finds it isn't running
        Page later = connect(experiment, live, "p1");
        assertEquals("serverStopped", later.frames.get(0).path("player").path("ended").asText());
        assertFalse(ScriptBoard.runsSession(experiment.id.toString(), live.id.toString()));

        // The game stopped: the computer players are off, its timers are gone, and nothing more is recorded.
        assertEquals(Boolean.FALSE, engine().eval("a.ai.isOn"));
        Thread.sleep(600);
        assertEquals(Boolean.FALSE, engine().eval("fired"));
        engine().eval("eventTracker.track('afterTheStop', [:]); null");
        assertEquals(0, Event.find.where().eq("experimentInstance.id", live.id).eq("name", "afterTheStop").findRowCount());
        assertEquals(1, Event.find.where().eq("experimentInstance.id", live.id).eq("name", "serverStopping").findRowCount());
        assertEquals("left for the next start to mark", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);
    }

    @SuppressWarnings("unchecked")
    private static Client staticClient(String id) throws Exception {
        return ((java.util.Map<String, Client>) staticField("clients").get(null)).get(id);
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
