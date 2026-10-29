import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Vertex;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.WebSocket;
import security.ParticipantAuth;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * How a session ends for the people in it, driven as pages and consoles would (ScriptBoard.addClient
 * with fake sockets, in a session launched from a console): an update of a participant's view that
 * comes as the session ends never replaces their end screen.
 */
public class SessionEndTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();
    private AdminSocketsTest.Tab console;
    private User user;
    private Experiment experiment;
    private ExperimentInstance session;

    @Before
    public void launch() throws Exception {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        user = createUser("ends-" + UUID.randomUUID() + "@test.org", "ends", "secret-1", lang);
        experiment = createExperiment("Ends");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        console = loggedIn(user);
        console.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(field("engineExperimentId").get(null)));
        session = launchSession(console, "S");
    }

    @After
    @SuppressWarnings("unchecked")
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        // (later tests' participants may have the same ids)
        Map<String, Client> clients = (Map<String, Client>) field("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ((Set<?>) field("endedPages").get(null)).clear();
        ParticipantAuth.reset();
        Recruitment.reset();
        ((EventTracker) field("eventTracker").get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A participant's page, or a researcher's preview of one: its socket, as the server sees it. */
    static final class Page {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        // Run as each frame arrives (on the thread that writes it), when set.
        volatile Consumer<JsonNode> onFrame;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) {
                frames.add(frame);
                Consumer<JsonNode> hook = onFrame;
                if (hook != null) hook.accept(frame);
            }
            public void close() { closed = true; }
        }, 0L);

        /** The browser closes the socket (or the connection drops and the server notices). */
        void hangUp() throws Throwable {
            for (F.Callback0 cb : in.closeCallbacks) cb.invoke();
        }

        JsonNode last() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }

        /** The last frame with a player in it: what the page shows. */
        JsonNode lastPlayer() {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (frames.get(i).has("player")) return frames.get(i);
            }
            return null;
        }
    }

    private Page connect(String clientId) throws Exception {
        return connect(clientId, false);
    }

    private Page connect(String clientId, boolean preview) throws Exception {
        return connect(session, clientId, preview);
    }

    private Page connect(ExperimentInstance to, String clientId, boolean preview) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), to.id.toString(), clientId, page.in, page.out, preview);
        return page;
    }

    /** How the server ended this frame's session for its page (player.ended), or "". */
    static String ended(JsonNode frame) {
        return frame == null ? "" : frame.path("player").path("ended").asText();
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        return t;
    }

    private ExperimentInstance launchSession(AdminSocketsTest.Tab tab, final String name) throws Exception {
        tab.send("LaunchGame", "name", name, "parameters", Json.newObject());
        await("session " + name + " running", () -> named(name) != null && named(name).status == ExperimentInstance.Status.RUNNING
            && named(name).id.equals(engineSessionId()));
        return named(name);
    }

    private ExperimentInstance named(String name) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", name).findUnique();
    }

    private static Long engineSessionId() throws Exception {
        java.lang.reflect.Method m = ScriptBoard.class.getDeclaredMethod("engineSession");
        m.setAccessible(true);
        ExperimentInstance running = (ExperimentInstance) m.invoke(null);
        return running == null ? null : running.id;
    }

    static Field field(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static ScriptEngine engine() throws Exception {
        return (ScriptEngine) field("engine").get(null);
    }

    @SuppressWarnings("unchecked")
    static Client client(String id) throws Exception {
        return ((Map<String, Client>) field("clients").get(null)).get(id);
    }

    static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    /** What the client update actor does every few hundred milliseconds: the player's view, as it is now. */
    private static void update(Client client) {
        try {
            client.updateGraph((Vertex) engine().eval("g.getVertex('" + client.id + "')"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- an update as the session ends ------------------------------------------------------------

    @Test
    public void anUpdateThatComesAsTheSessionEndsNeverReplacesTheEndScreen() throws Throwable {
        Page first = connect("p1");
        Page second = connect("p2");
        engine().eval("g.getVertex('p1').text = '<p>Round 3</p>'; g.getVertex('p2').text = '<p>Round 3</p>'; null");
        final Client a = client("p1");
        final Client b = client("p2");
        // The update actor updates every participant's view: here, as each page is told the session has
        // ended, the other's is updated, whichever is told first. The end screen of the one told first
        // used to be replaced by their game view before it was remembered, so their page, coming back,
        // got a round that no longer answered.
        first.onFrame = f -> { if (!ended(f).isEmpty()) update(b); };
        second.onFrame = f -> { if (!ended(f).isEmpty()) update(a); };
        console.send("StopGame", "id", session.id);
        await("both told", () -> first.closed && second.closed);
        assertEquals(first.frames.toString(), "stopped", ended(first.last()));
        assertEquals(second.frames.toString(), "stopped", ended(second.last()));
        for (Client c : new Client[]{a, b}) {
            assertEquals(c.lastFrame.toString(), "stopped", ended(c.lastFrame));
            Page back = connect(c.id);
            assertEquals(back.frames.toString(), "stopped", ended(back.frames.get(0)));
        }
        // An update after the end changes nothing either: no view goes out, and the end stays theirs.
        int before = first.frames.size();
        update(a);
        assertEquals("stopped", ended(a.lastFrame));
        assertEquals(before, first.frames.size());
        assertEquals("stopped", ended(connect("p1").frames.get(0)));
    }
}
