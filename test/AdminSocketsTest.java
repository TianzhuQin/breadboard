import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.WebSocket;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * The admin console's websocket, driven like a browser tab would (Breadboard.connect with a fake
 * socket): every reply goes to the socket that asked; each socket has its own Admin, replay and
 * lifetime; a second account logging in leaves the running engine alone; OnJoinStep / OnLeaveStep
 * run once per player; bad requests get {"error", "request"} replies; live events reach consoles.
 */
public class AdminSocketsTest extends BaseTest {

    /** One console tab. */
    static class Tab {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        final String uid;
        boolean closed;

        Tab(String uid) {
            this.uid = uid;
            Breadboard.connect(in, new WebSocket.Out<JsonNode>() {
                public void write(JsonNode frame) { frames.add(frame); }
                public void close() {}
            });
        }

        void send(String action, Object... pairs) {
            ObjectNode m = Json.newObject();
            m.put("action", action);
            if (uid != null) m.put("uid", uid);
            for (int i = 0; i < pairs.length; i += 2) {
                m.put((String) pairs[i], Json.toJson(pairs[i + 1]));
            }
            sendRaw(m);
        }

        void sendRaw(JsonNode m) {
            for (F.Callback<JsonNode> cb : in.callbacks) {
                try { cb.invoke(m); } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }

        void close() throws Throwable {
            if (closed) return;
            closed = true;
            for (F.Callback0 cb : in.closeCallbacks) cb.invoke();
        }

        int count(Matcher m) {
            int n = 0;
            for (JsonNode f : frames) if (m.matches(f)) n++;
            return n;
        }

        JsonNode first(Matcher m) {
            for (JsonNode f : frames) if (m.matches(f)) return f;
            return null;
        }
    }

    interface Matcher { boolean matches(JsonNode f); }

    static Matcher userJson() { return f -> f.has("user"); }
    static Matcher error(final String request) {
        return f -> f.has("error") && f.get("error").asText().length() > 0 && f.has("request") && request.equals(f.get("request").asText());
    }
    static Matcher graph(final String action, final String id) {
        return f -> f.has("action") && action.equals(f.get("action").asText()) && id.equals(f.get("id").asText());
    }
    static Matcher replay() { return f -> f.has("graphReplay"); }
    static Matcher notify(final String what) { return f -> f.has("notify") && f.get("notify").has(what); }
    static Matcher output(final String text) { return f -> f.has("output") && f.get("output").asText().contains(text); }

    private final List<Tab> tabs = new ArrayList<>();

    private Tab tab(User user) {
        Tab t = new Tab(user == null ? null : user.uid);
        tabs.add(t);
        return t;
    }

    @After
    public void closeTabs() throws Throwable {
        for (Tab t : tabs) t.close();
        // A session launched here must not keep recording events for rows the cleanup deletes.
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

    /** Give the actors a moment, for checks that something did NOT happen. */
    private static void settle() throws InterruptedException {
        Thread.sleep(400);
    }

    private static ScriptEngine engine() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (ScriptEngine) f.get(null);
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static void own(User user, Experiment experiment) {
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
    }

    // ---- replies go to the asking socket ---------------------------------------------------------

    @Test
    public void repliesGoToTheTabThatAsked() throws Exception {
        User u = user("tabs");
        final Tab a = tab(u);
        final Tab b = tab(u);

        a.send("Update");
        await("User JSON on a", () -> a.count(userJson()) == 1);
        settle();
        assertEquals("b connected last but did not ask", 0, b.count(userJson()));

        b.send("Update");
        await("User JSON on b", () -> b.count(userJson()) == 1);
        settle();
        assertEquals(1, a.count(userJson()));
    }

    @Test
    public void badRequestsAreAnsweredNotDropped() throws Exception {
        User u = user("bad");
        final Tab t = tab(u);

        t.send("NoSuchThing");
        await("unknown action", () -> t.count(error("NoSuchThing")) == 1);

        t.send("StopGame");
        await("missing id", () -> t.first(error("StopGame")) != null);
        assertTrue(t.first(error("StopGame")).get("error").asText().contains("\"id\""));

        t.send("SelectExperiment", "experimentId", "twelve");
        await("non-numeric id", () -> t.count(error("SelectExperiment")) == 1);

        t.send("SelectExperiment", "experimentId", 987654321);
        await("unknown experiment", () -> t.count(error("SelectExperiment")) == 2);

        // Engine actions before LogIn: no ScriptBoard yet for this account.
        t.send("RunGame");
        await("RunGame before LogIn", () -> t.count(error("RunGame")) == 1);

        final Tab stranger = tab(null);
        stranger.send("Update");
        await("no uid", () -> stranger.count(error("Update")) == 1);
        ObjectNode forged = Json.newObject();
        forged.put("action", "Update");
        forged.put("uid", "not-a-real-uid");
        stranger.sendRaw(forged);
        await("unknown uid", () -> stranger.count(error("Update")) == 2);

        ObjectNode noAction = Json.newObject();
        noAction.put("uid", u.uid);
        t.sendRaw(noAction);
        await("no action", () -> t.count(f -> f.has("error") && f.get("error").asText().contains("\"action\"")) == 1);
        assertEquals("errors change nothing and send no User JSON", 0, t.count(userJson()));
    }

    // ---- password change ------------------------------------------------------------------------

    @Test
    public void passwordChangesAreCheckedAndHashed() throws Exception {
        final User u = user("pw");
        final Tab t = tab(u);

        t.send("SaveUserSettings", "currentPassword", "wrong", "newPassword", "new-pass-2", "confirmPassword", "new-pass-2");
        await("wrong current password", () -> t.count(error("SaveUserSettings")) == 1);
        assertNotNull(User.authenticate(u.email, "secret-1"));

        t.send("SaveUserSettings", "currentPassword", "secret-1", "newPassword", "new-pass-2", "confirmPassword", "typo");
        await("confirmation mismatch", () -> t.count(error("SaveUserSettings")) == 2);
        assertNotNull(User.authenticate(u.email, "secret-1"));

        t.send("SaveUserSettings", "currentPassword", "secret-1", "newPassword", "", "confirmPassword", "");
        await("empty new password", () -> t.count(error("SaveUserSettings")) == 3);

        t.send("SaveUserSettings", "currentPassword", "secret-1", "newPassword", "new-pass-2", "confirmPassword", "new-pass-2");
        await("User JSON after the change", () -> t.count(userJson()) == 1);
        User saved = User.findByEmail(u.email);
        assertNotEquals("stored as typed", "new-pass-2", saved.password);
        assertNotNull("the new password signs in", User.authenticate(u.email, "new-pass-2"));
        assertNull(User.authenticate(u.email, "secret-1"));
    }

    // ---- delete experiment ----------------------------------------------------------------------

    @Test
    public void experimentsAreDeletedByIdAndEveryonesSelectionIsCleared() throws Exception {
        final User owner = user("del");
        User other = user("del-other");
        Experiment keep = createExperiment("Same name");
        Experiment doomed = new Experiment();
        doomed.name = "Same name";
        doomed.id = 5000L + (long) (Math.random() * 100000); // well above 127: Long == compared references
        doomed.fileMode = false;
        doomed.save();
        own(owner, keep);
        own(owner, doomed);
        other.setSelectedExperiment(Experiment.findById(doomed.id));
        other.update();

        final Tab t = tab(owner);
        t.send("LogIn");
        await("LogIn", () -> t.count(userJson()) >= 1);
        final int before = t.count(userJson());

        t.send("DeleteExperiment", "experimentId", doomed.id, "selectedExperiment", "Same name");
        await("User JSON after delete", () -> t.count(userJson()) > before);
        assertNull(Experiment.findById(doomed.id));
        assertNotNull("the other experiment of the same name stays", Experiment.findById(keep.id));
        assertNull(User.findByEmail(other.email).selectedExperiment);

        t.send("DeleteExperiment", "experimentId", 999999999L);
        await("unknown id", () -> t.count(error("DeleteExperiment")) == 1);

        // by name still works (the old console)
        final int after = t.count(userJson());
        t.send("DeleteExperiment", "selectedExperiment", "Same name");
        await("User JSON after delete by name", () -> t.count(userJson()) > after);
        assertNull(Experiment.findById(keep.id));
    }

    // ---- live events ----------------------------------------------------------------------------

    @Test
    public void recordedEventsArePushedToEveryConsole() throws Exception {
        Experiment experiment = createExperiment("Events");
        ExperimentInstance instance = createInstance("live", experiment);
        List<JsonNode> one = new CopyOnWriteArrayList<>();
        List<JsonNode> two = new CopyOnWriteArrayList<>();
        ThrottledWebSocketOut out1 = new ThrottledWebSocketOut(recorder(one), 0L);
        ThrottledWebSocketOut out2 = new ThrottledWebSocketOut(recorder(two), 0L);
        ScriptBoard.addAdmin(new Admin(null, null, out1));
        ScriptBoard.addAdmin(new Admin(null, null, out2));
        try {
            EventTracker tracker = new EventTracker();
            tracker.setExperimentInstance(instance);
            java.util.LinkedHashMap<Object, Object> data = new java.util.LinkedHashMap<>();
            data.put("clientId", "p1");
            data.put("score", 3);
            tracker.track("clientConnected", data);

            assertEquals(1, one.size());
            assertEquals(1, two.size());
            JsonNode event = one.get(0).get("event");
            assertEquals("clientConnected", event.get("name").asText());
            assertTrue("saved first, so it has its id", event.get("id").asLong() > 0);
            assertTrue(event.get("datetime").asText().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
            assertEquals("p1", event.get("data").get("clientId").asText());
            assertEquals("3", event.get("data").get("score").asText());

            // Scripts pass Groovy maps whose values need not be strings.
            List<Map<String, String>> raw = new ArrayList<>();
            Map numbers = new java.util.HashMap();
            numbers.put("name", "players");
            numbers.put("value", 4);
            raw.add(numbers);
            tracker.track("autopilotStart", raw);
            assertEquals("4", two.get(1).get("event").get("data").get("players").asText());

            tracker.disable();
            tracker.track("ignored", data);
            assertEquals("a disabled tracker records and sends nothing", 2, one.size());
        } finally {
            ScriptBoard.removeAdmin(out1);
            ScriptBoard.removeAdmin(out2);
        }
    }

    private static WebSocket.Out<JsonNode> recorder(final List<JsonNode> into) {
        return new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { into.add(frame); }
            public void close() {}
        };
    }

    // ---- the engine: per-tab replay, shared engine, join/leave steps, launch checks ---------------

    @Test
    public void tabsAccountsAndTheSharedEngine() throws Throwable {
        final int adminsBefore = ScriptBoard.adminCount();
        final User alice = user("alice");
        final User bob = user("bob");
        Experiment experiment = createExperiment("Shared engine");
        createStep(experiment, "OnJoinStep",
            "class JoinLog { List ids = java.util.Collections.synchronizedList([]); def start(String id) { ids << id } }\n"
                + "onJoinStep = new JoinLog() // marker-join");
        createStep(experiment, "OnLeaveStep",
            "class LeaveLog { List ids = java.util.Collections.synchronizedList([]); def start(String id) { ids << id } }\n"
                + "onLeaveStep = new LeaveLog() // marker-leave");
        createParameter(experiment, "rounds", "Integer", "3");
        own(alice, experiment);

        // Alice's first tab. With nothing selected, logging in loads no experiment.
        final Tab a1 = tab(alice);
        a1.send("LogIn");
        await("a1 User JSON", () -> a1.count(userJson()) == 1);
        a1.send("SelectExperiment", "experimentId", experiment.id);
        await("steps loaded", () -> a1.count(output("marker-join")) == 1 && a1.count(output("marker-leave")) == 1);
        final ScriptEngine engine = engine();
        assertEquals(adminsBefore + 1, ScriptBoard.adminCount());

        // A second LogIn on the same socket (the console logs in on every open) adds no second Admin.
        a1.send("LogIn");
        await("a1 second User JSON", () -> a1.count(userJson()) >= 3);
        settle();
        assertEquals(adminsBefore + 1, ScriptBoard.adminCount());

        // Alice's second tab: its own User JSON and replay; the first tab is not replayed to again.
        final Tab a2 = tab(alice);
        final int a1Replays = a1.count(replay());
        a2.send("LogIn");
        await("a2 replay", () -> a2.count(replay()) == 1);
        assertTrue("User JSON comes before the network", a2.frames.indexOf(a2.first(userJson())) < a2.frames.indexOf(a2.first(replay())));
        settle();
        assertEquals(a1Replays, a1.count(replay()));
        assertEquals(adminsBefore + 2, ScriptBoard.adminCount());

        // A player joins: each tab sees it once, OnJoinStep runs once.
        ScriptBoard.processScript("g.addPlayer('p1')", null, "test");
        await("OnJoinStep", () -> "[p1]".equals(String.valueOf(engine.eval("onJoinStep.ids"))));
        await("addNode on both tabs", () -> a1.count(graph("addNode", "p1")) == 1 && a2.count(graph("addNode", "p1")) == 1);
        settle();
        assertEquals("[p1]", String.valueOf(engine.eval("onJoinStep.ids")));

        // Bob (another account) logs in: he gets his User JSON and the live network; the engine
        // and Alice's session are left alone.
        final int a1Reloads = a1.count(notify("ScriptEngineReloading"));
        final Tab b = tab(bob);
        b.send("LogIn");
        await("bob's replay", () -> b.count(graph("addNode", "p1")) == 1);
        assertEquals(bob.email, b.first(userJson()).get("user").get("email").asText());
        settle();
        assertSame("the engine was not rebuilt", engine, engine());
        assertEquals(a1Reloads, a1.count(notify("ScriptEngineReloading")));
        assertEquals("[p1]", String.valueOf(engine.eval("onJoinStep.ids")));

        // A tab closes: it is sent nothing more. The player leaves: OnLeaveStep runs once.
        a2.close();
        assertEquals(adminsBefore + 2, ScriptBoard.adminCount());
        ScriptBoard.processScript("g.removePlayer('p1')", null, "test");
        await("OnLeaveStep", () -> "[p1]".equals(String.valueOf(engine.eval("onLeaveStep.ids"))));
        await("removeNode", () -> a1.count(graph("removeNode", "p1")) == 1 && b.count(graph("removeNode", "p1")) == 1);
        assertEquals(0, a2.count(graph("removeNode", "p1")));

        // With every console closed (the autopilot running alone) the steps still run, once.
        a1.close();
        b.close();
        assertEquals(adminsBefore, ScriptBoard.adminCount());
        ScriptBoard.processScript("g.addPlayer('p2')", null, "test");
        await("OnJoinStep with no console", () -> "[p1, p2]".equals(String.valueOf(engine.eval("onJoinStep.ids"))));
        settle();
        assertEquals("[p1, p2]", String.valueOf(engine.eval("onJoinStep.ids")));

        // Launching: a bad value is refused before anything is reset.
        final Tab a3 = tab(alice);
        a3.send("LogIn");
        await("a3 User JSON", () -> a3.count(userJson()) == 1);
        a3.send("LaunchGame", "name", "bad", "parameters", Json.parse("{\"rounds\":\"many\"}"));
        await("refused launch", () -> a3.count(error("LaunchGame")) == 1);
        assertTrue(a3.first(error("LaunchGame")).get("error").asText().contains("rounds"));
        settle();
        assertSame("a refused launch resets nothing", engine, engine());
        assertEquals(0, a3.count(notify("ScriptEngineReloading")));

        // A null value takes the parameter's default (it used to throw after the reset).
        a3.send("LaunchGame", "name", "good", "parameters", Json.parse("{\"rounds\":null}"));
        await("launched", () -> {
            for (JsonNode f : a3.frames) {
                JsonNode inst = f.get("experimentInstance");
                if (f.has("user") && inst != null && inst.isObject() && "good".equals(inst.get("name").asText())) return true;
            }
            return false;
        });
        final ScriptEngine launched = engine();
        await("rounds bound", () -> Integer.valueOf(3).equals(launched.get("rounds")));
        User fresh = User.findByEmail(alice.email);
        final ExperimentInstance session = ExperimentInstance.findById(fresh.experimentInstanceId);
        assertEquals("good", session.name);
        assertEquals(ExperimentInstance.Status.RUNNING, session.status);

        // Selecting a session that has finished (its game's copy is now out of date) doesn't abort
        // half-way: the user gets it selected, not restarted, and a User JSON.
        ExperimentInstance finished = ExperimentInstance.findById(session.id);
        finished.status = ExperimentInstance.Status.FINISHED;
        finished.save();
        final int users = a3.count(userJson());
        a3.send("SelectInstance", "id", session.id);
        await("User JSON after selecting a finished session", () -> {
            if (a3.count(userJson()) < users + 2) return false; // the immediate one, then the one after the reload
            JsonNode last = null;
            for (JsonNode f : a3.frames) if (f.has("user")) last = f;
            JsonNode inst = last.get("experimentInstance");
            return inst.isObject() && inst.get("id").asLong() == session.id && "FINISHED".equals(inst.get("status").asText());
        });
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(session.id).status);
        assertEquals(0, a3.count(error("SelectInstance")));

        a3.send("SelectInstance", "id", 987654321L);
        await("unknown session", () -> a3.count(error("SelectInstance")) == 1);
    }
    // ---- the autopilot hook ---------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    public void theAutopilotHookRunsAfterEveryLoadOfTheSteps() throws Throwable {
        final User carol = user("carol");
        final Experiment experiment = createExperiment("Hooked");
        createStep(experiment, "Setup", "hookedSetup = true // marker-setup");
        own(carol, experiment);

        // Each call: the experiment's id, and whether its steps were already in the engine.
        final List<String> calls = new CopyOnWriteArrayList<>();
        Field hook = ScriptBoard.class.getDeclaredField("afterStepsLoadedHook");
        hook.setAccessible(true);
        Object original = hook.get(null);
        hook.set(null, new java.util.function.BiConsumer<Experiment, ScriptEngine>() {
            public void accept(Experiment e, ScriptEngine engine) {
                calls.add((e == null ? "none" : String.valueOf(e.id)) + ":" + (engine.get("hookedSetup") != null));
                if (calls.size() == 1) {
                    throw new IllegalStateException("a broken hook is only logged");
                }
            }
        });
        try {
            final String loaded = experiment.id + ":true";
            final Tab t = tab(carol);
            t.send("LogIn");
            await("LogIn", () -> t.count(userJson()) >= 1);

            t.send("SelectExperiment", "experimentId", experiment.id);
            await("hook after SelectExperiment", () -> calls.contains(loaded));
            await("User JSON despite the broken hook", () -> t.count(userJson()) >= 2);

            t.send("LaunchGame", "name", "hooked", "parameters", Json.newObject());
            await("hook after LaunchGame", () -> calls.size() == 2 && calls.get(1).equals(loaded));

            t.send("ReloadEngine");
            await("hook after ReloadEngine", () -> calls.size() == 3 && calls.get(2).equals(loaded));

            t.send("SendStep", "id", -1, "name", "Setup", "source", "hookedSetup = 2");
            await("hook after SendStep", () -> calls.size() == 4 && calls.get(3).equals(loaded));

            final ExperimentInstance session = ExperimentInstance.findById(User.findByEmail(carol.email).experimentInstanceId);
            t.send("SelectInstance", "id", session.id);
            await("hook after SelectInstance", () -> calls.size() == 5 && calls.get(4).equals(loaded));
        } finally {
            hook.set(null, original);
        }
    }
}
