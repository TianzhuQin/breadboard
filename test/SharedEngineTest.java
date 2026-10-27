import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.WebSocket;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * The script engine is shared by every account and runs one experiment's session at a time. An
 * account's console shows its own selection, so: engine actions are refused when the engine runs
 * another experiment; a launch is refused when the page shows an experiment that is no longer
 * selected; every tab of the account learns of a new selection; a session the engine moves off is
 * marked stopped, and its participants are not put into the next game. Also: event times come
 * with UTC, the websocket password change follows the Settings page's rules, and deleting an
 * experiment switches off an autopilot that runs it.
 */
public class SharedEngineTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
    }

    private AdminSocketsTest.Tab tab(User user) {
        AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        return t;
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = tab(user);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        return t;
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    private static Long engineExperimentId() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engineExperimentId");
        f.setAccessible(true);
        return (Long) f.get(null);
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

    private static JsonNode lastUserJson(AdminSocketsTest.Tab t) {
        JsonNode last = null;
        for (JsonNode f : t.frames) if (f.has("user")) last = f;
        return last;
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
    }

    // ---- engine actions need the engine to run your experiment -----------------------------------------

    @Test
    public void anotherAccountCantActOnTheEnginesGame() throws Exception {
        final User alice = user("alice");
        final User bob = user("bob");
        final Experiment hers = createExperiment("Hers");
        final Experiment his = createExperiment("His");
        own(alice, hers);
        own(bob, his);
        bob.setSelectedExperiment(Experiment.findById(his.id));
        bob.update();

        final AdminSocketsTest.Tab a = loggedIn(alice);
        a.send("SelectExperiment", "experimentId", hers.id);
        await("her experiment in the engine", () -> hers.id.equals(engineExperimentId()));

        // Bob's console shows his own experiment, the engine runs hers: he is told, nothing runs.
        final AdminSocketsTest.Tab b = loggedIn(bob);
        JsonNode engineInfo = lastUserJson(b).get("engine");
        assertEquals(hers.id.longValue(), engineInfo.get("experimentId").asLong());
        assertEquals("Hers", engineInfo.get("experimentName").asText());
        assertEquals(alice.email, engineInfo.get("owner").asText());
        assertEquals(his.id.longValue(), lastUserJson(b).get("experiment").get("id").asLong());

        // As the console sends them, naming the experiment its page shows: the refusal that names
        // what the engine runs answers, not "reload the page" (which wouldn't help).
        b.send("SendScript", "script", "bobWasHere = 1", "requestId", "bob-1", "experimentId", his.id);
        b.send("SendStep", "id", -1, "name", "Setup", "source", "bobStep = 1", "experimentId", his.id);
        b.send("RunGame");
        b.send("MakeChoice", "choiceUID", "nope");
        b.send("SubmitAMTTask", "lifetimeInSeconds", 60, "tutorialTime", 0);
        b.send("EditTie", "op", "add", "a", "x", "b", "y");
        for (final String request : new String[]{"SendScript", "SendStep", "RunGame", "MakeChoice", "SubmitAMTTask"}) {
            await(request + " refused", () -> b.count(AdminSocketsTest.error(request)) == 1);
            String text = b.first(AdminSocketsTest.error(request)).get("error").asText();
            assertTrue(text, text.contains("the engine is running \"Hers\"") && text.contains("\"His\""));
        }
        assertEquals("the refusal carries the console's requestId", "bob-1",
            b.first(AdminSocketsTest.error("SendScript")).get("requestId").asText());
        assertFalse(b.first(AdminSocketsTest.error("SendStep")).has("requestId"));
        assertEquals("and the choice's id", "nope", b.first(AdminSocketsTest.error("MakeChoice")).get("choiceUID").asText());
        await("EditTie refused", () -> b.count(f -> f.has("tieEdit") && !f.get("tieEdit").get("ok").asBoolean()) == 1);
        Thread.sleep(300);
        assertNull(engine().get("bobWasHere"));
        assertNull(engine().get("bobStep"));
        assertNull(engine().get("initStep.start()"));
        assertEquals("no AMT output either", 0, b.count(AdminSocketsTest.output("AMT HIT created")));

        // Alice, whose experiment the engine runs, can.
        a.send("SendScript", "script", "aliceWasHere = 1");
        await("her script ran", () -> Integer.valueOf(1).equals(engine().get("aliceWasHere")));
        assertEquals(0, a.count(AdminSocketsTest.error("SendScript")));
    }

    // ---- a script or step written for another experiment than the engine's runs nothing ----------------

    @Test
    public void aScriptOrStepWrittenForAnotherExperimentRunsNothing() throws Exception {
        final User user = user("stale");
        final Experiment shown = createExperiment("Shown");
        final Experiment loaded = createExperiment("Loaded");
        Step shownStep = createStep(shown, "Setup", "shownStep = 1");
        Step loadedStep = createStep(loaded, "Setup", "loadedStep = 1");
        own(user, shown);
        own(user, loaded);

        final AdminSocketsTest.Tab t = loggedIn(user);
        t.send("SelectExperiment", "experimentId", loaded.id);
        await("loaded in the engine", () -> loaded.id.equals(engineExperimentId()) && Integer.valueOf(1).equals(engine().get("loadedStep")));

        // This page still shows "Shown" (another tab has selected "Loaded" since).
        t.send("SendScript", "script", "staleScript = 1", "experimentId", shown.id, "requestId", "s-1");
        t.send("SendStep", "id", -1, "name", "Setup", "source", "staleStep = 1", "experimentId", shown.id, "requestId", "s-2");
        // A saved step of another experiment, even from a page that doesn't say which it shows.
        t.send("SendStep", "id", shownStep.id, "name", "Setup", "source", "shownStep = 1", "requestId", "s-3");
        String[][] refused = {{"s-1", "SendScript"}, {"s-2", "SendStep"}, {"s-3", "SendStep"}};
        for (final String[] r : refused) {
            await(r[0] + " refused", () -> t.count(f -> f.has("requestId") && r[0].equals(f.get("requestId").asText())) == 1);
            JsonNode error = t.first(f -> f.has("requestId") && r[0].equals(f.get("requestId").asText()));
            assertEquals("Nothing was run: the engine has another experiment loaded now. Reload the page and try again.",
                error.get("error").asText());
            assertEquals(r[1], error.get("request").asText());
        }
        Thread.sleep(300);
        assertNull(engine().get("staleScript"));
        assertNull(engine().get("staleStep"));
        assertNull(engine().get("shownStep"));
        assertFalse("its script isn't saved as the account's either", "staleScript = 1".equals(User.findByEmail(user.email).currentScript));

        // What is written for the experiment loaded runs.
        t.send("SendScript", "script", "freshScript = 1", "experimentId", loaded.id, "requestId", "s-4");
        t.send("SendStep", "id", loadedStep.id, "name", "Setup", "source", "loadedStep = 2", "experimentId", loaded.id, "requestId", "s-5");
        await("ran", () -> Integer.valueOf(1).equals(engine().get("freshScript")) && Integer.valueOf(2).equals(engine().get("loadedStep")));
        for (final String id : new String[]{"s-4", "s-5"}) {
            await(id + " answered", () -> t.count(f -> f.has("requestId") && id.equals(f.get("requestId").asText()) && f.has("output")) == 1);
        }
    }

    // ---- a page that shows another experiment can't launch; every tab learns the selection -------------

    @Test
    public void aTabShowingAnOldSelectionCantLaunchAndEveryTabLearnsTheNewOne() throws Exception {
        final User user = user("tabs");
        final Experiment first = createExperiment("First");
        final Experiment second = createExperiment("Second");
        own(user, first);
        own(user, second);

        final AdminSocketsTest.Tab one = loggedIn(user);
        final AdminSocketsTest.Tab two = loggedIn(user);
        one.send("SelectExperiment", "experimentId", first.id);
        await("tab two learns of the selection made in tab one", () -> lastUserJson(two).has("experiment")
            && first.id.equals(lastUserJson(two).get("experiment").get("id").asLong()));

        two.send("SelectExperiment", "experimentId", second.id);
        await("and back", () -> lastUserJson(one).has("experiment")
            && second.id.equals(lastUserJson(one).get("experiment").get("id").asLong()));

        // A page still showing the first experiment presses Launch: refused, nothing starts.
        one.send("LaunchGame", "experimentId", first.id, "name", "stale", "parameters", Json.newObject());
        await("refused", () -> one.count(AdminSocketsTest.error("LaunchGame")) == 1);
        assertTrue(one.first(AdminSocketsTest.error("LaunchGame")).get("error").asText().contains("\"Second\""));
        Thread.sleep(300);
        assertTrue(sessions(first).isEmpty());
        assertTrue(sessions(second).isEmpty());

        // The page that shows it launches it; both tabs see the session.
        two.send("LaunchGame", "experimentId", second.id, "name", "fresh", "parameters", Json.newObject());
        await("launched, in both tabs", () -> {
            JsonNode inst = lastUserJson(one).get("experimentInstance");
            return inst.isObject() && "fresh".equals(inst.get("name").asText());
        });
        assertEquals(1, sessions(second).size());
    }

    // ---- a session the engine moves off is stopped, and its pages are not let in ------------------------

    @Test
    public void aSessionTheEngineMovesOffIsStoppedAndItsPagesAreTurnedAway() throws Throwable {
        final User user = user("mover");
        final Experiment first = createExperiment("Played first");
        final Experiment second = createExperiment("Edited next");
        own(user, first);
        own(user, second);

        final AdminSocketsTest.Tab t = loggedIn(user);
        t.send("SelectExperiment", "experimentId", first.id);
        await("selected", () -> {
            Experiment selection = User.findByEmail(user.email).selectedExperiment;
            return selection != null && first.id.equals(selection.id);
        });
        t.send("LaunchGame", "name", "S1", "parameters", Json.newObject());
        await("running", () -> sessions(first).size() == 1 && sessions(first).get(0).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance s1 = sessions(first).get(0);
        assertTrue(ScriptBoard.runsSession(first.id, ExperimentInstance.findById(s1.id)));
        assertEquals(s1.id, Autopilot.openInstance(first.id).id);

        // Selecting another experiment rebuilds the engine: S1's game is gone, and it says so.
        t.send("SelectExperiment", "experimentId", second.id);
        await("S1 stopped", () -> ExperimentInstance.findById(s1.id).status == ExperimentInstance.Status.STOPPED);
        await("the engine runs the other experiment", () -> second.id.equals(engineExperimentId()));
        final int users = t.count(AdminSocketsTest.userJson());
        t.send("Update");
        await("User JSON", () -> t.count(AdminSocketsTest.userJson()) > users);
        assertEquals(second.id.longValue(), lastUserJson(t).get("engine").get("experimentId").asLong());
        assertTrue("it runs no session", lastUserJson(t).get("engine").get("sessionId").isNull());
        assertNull("the /play links don't lead to it", Autopilot.openInstance(first.id));

        // Even marked running again (as after a restart), its pages are turned away, not put into the
        // game the engine runs now.
        ExperimentInstance stale = ExperimentInstance.findById(s1.id);
        stale.status = ExperimentInstance.Status.RUNNING;
        stale.save();
        assertFalse(ScriptBoard.runsSession(first.id, ExperimentInstance.findById(s1.id)));
        final List<JsonNode> page = new CopyOnWriteArrayList<>();
        ScriptBoard.addClient(first.id.toString(), s1.id.toString(), "late-page", new WebSocket.In<JsonNode>(),
            new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
                public void write(JsonNode frame) { page.add(frame); }
                public void close() {}
            }, 0L));
        assertEquals(1, page.size());
        assertTrue(page.get(0).toString(), page.get(0).get("player").get("text").asText().contains("isn't running"));
        assertNull("no seat in the game", engine().eval("g.getVertex('late-page')"));
    }

    // ---- a token for another session never opens a seat in the live game -------------------------------

    /** A participant page's socket: the frames it gets, and whether the server closed it. */
    private static final class Page {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { closed = true; }
        }, 0L);
    }

    private static String text(JsonNode frame) {
        return frame.has("player") ? frame.get("player").path("text").asText() : "";
    }

    @SuppressWarnings("unchecked")
    private static Client client(String id) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("clients");
        f.setAccessible(true);
        return ((java.util.Map<String, Client>) f.get(null)).get(id);
    }

    @Test
    public void aTokenForAnotherSessionNeverOpensASeatInTheLiveGame() throws Throwable {
        final User user = user("host");
        final Experiment experiment = createExperiment("Live game");
        own(user, experiment);
        // An earlier session of the same experiment, over now; its participants kept their tokens.
        ExperimentInstance earlier = createInstance("earlier", experiment);
        earlier.status = ExperimentInstance.Status.FINISHED;
        earlier.save();

        final AdminSocketsTest.Tab t = loggedIn(user);
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> {
            Experiment selection = User.findByEmail(user.email).selectedExperiment;
            return selection != null && experiment.id.equals(selection.id);
        });
        t.send("LaunchGame", "name", "R", "parameters", Json.newObject());
        await("running", () -> {
            for (ExperimentInstance s : sessions(experiment)) {
                if ("R".equals(s.name) && s.status == ExperimentInstance.Status.RUNNING) return true;
            }
            return false;
        });
        ExperimentInstance live = null;
        for (ExperimentInstance s : sessions(experiment)) if ("R".equals(s.name)) live = s;
        final String e = experiment.id.toString();

        // Participant 3 of R joins.
        Page real = new Page();
        ScriptBoard.addClient(e, live.id.toString(), "3", new WebSocket.In<JsonNode>(), real.out);
        assertNotNull(engine().eval("g.getVertex('3')"));
        assertSame(real.out, client("3").out);

        // Someone with a token for seat 3 of the earlier session, or of the test session, connects:
        // refused, and participant 3 keeps their seat and their socket.
        Page old = new Page();
        ScriptBoard.addClient(e, earlier.id.toString(), "3", new WebSocket.In<JsonNode>(), old.out);
        assertEquals(1, old.frames.size());
        assertTrue(old.frames.get(0).toString(), text(old.frames.get(0)).contains("ended"));
        Page test = new Page();
        ScriptBoard.addClient(e, "0", "3", new WebSocket.In<JsonNode>(), test.out);
        assertEquals(1, test.frames.size());
        assertTrue(test.frames.get(0).toString(), text(test.frames.get(0)).contains("isn't running"));
        assertSame(real.out, client("3").out);
        assertFalse(real.closed);

        // Nor does the test session take newcomers into R's game while R runs.
        assertFalse(ScriptBoard.runsSession(experiment.id, experiment.getTestInstance()));
        Page bot = new Page();
        ScriptBoard.addClient(e, "0", "bot1", new WebSocket.In<JsonNode>(), bot.out);
        assertTrue(bot.frames.toString(), text(bot.frames.get(0)).contains("isn't running"));
        assertNull(engine().eval("g.getVertex('bot1')"));

        // Participant 3 coming back to R still gets their seat.
        Page back = new Page();
        ScriptBoard.addClient(e, live.id.toString(), "3", new WebSocket.In<JsonNode>(), back.out);
        assertSame(back.out, client("3").out);
        assertTrue("the older socket is closed", real.closed);
    }

    // ---- a step queued for an engine that has been replaced -----------------------------------------------

    @Test
    public void aStepQueuedForAReplacedEngineDoesNotRunInTheNewOne() throws Throwable {
        final User user = user("stepper");
        final Experiment first = createExperiment("Steps first");
        final Experiment second = createExperiment("Steps second");
        own(user, first);
        own(user, second);
        final AdminSocketsTest.Tab t = loggedIn(user);
        t.send("SelectExperiment", "experimentId", first.id);
        await("first in the engine", () -> first.id.equals(engineExperimentId()));
        Field generation = ScriptBoard.class.getDeclaredField("engineGeneration");
        generation.setAccessible(true);
        final long before = (Long) generation.get(null);

        t.send("SelectExperiment", "experimentId", second.id);
        await("second in the engine", () -> second.id.equals(engineExperimentId()) && (Long) generation.get(null) > before);

        // A step queued (by SendStep) for the first engine, arriving after the rebuild.
        Field instances = Breadboard.class.getDeclaredField("instances");
        instances.setAccessible(true);
        akka.actor.ActorRef actor = ((java.util.Map<String, akka.actor.ActorRef>) instances.get(null)).get(user.email);
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() {}
        }, 0L);
        actor.tell(new Breadboard.RunStep(User.findByEmail(user.email), "staleStep = 1", out, "SendStep", before), null);
        await("refused", () -> frames.size() == 1);
        assertEquals("SendStep", frames.get(0).get("request").asText());
        assertTrue(frames.get(0).get("error").asText().contains("reloaded"));
        assertNull(engine().get("staleStep"));

        // One for the engine that runs now does run.
        actor.tell(new Breadboard.RunStep(User.findByEmail(user.email), "freshStep = 1", out, "SendStep", (Long) generation.get(null)), null);
        await("ran", () -> Integer.valueOf(1).equals(engine().get("freshStep")));
    }

    // ---- signing out, or a new password, cuts off the account's open consoles ---------------------------

    @Test
    public void signingOutClosesTheAccountsConsoleSockets() throws Throwable {
        final User user = user("leaving");
        final User other = user("staying");
        final AdminSocketsTest.Tab one = loggedIn(user);
        final AdminSocketsTest.Tab two = loggedIn(user);
        final AdminSocketsTest.Tab theirs = loggedIn(other);
        final int before = ScriptBoard.adminCount();

        play.test.Helpers.route(play.test.Helpers.fakeRequest("POST", "/logout").withSession("uid", user.uid));
        for (final AdminSocketsTest.Tab t : new AdminSocketsTest.Tab[]{one, two}) {
            await("told", () -> t.count(f -> f.has("status") && "unauthorized".equals(f.get("status").asText())) == 1);
            assertTrue(t.first(f -> f.has("status")).get("error").asText().startsWith("You are not signed in"));
        }
        assertEquals("both of its sockets are gone, the other account's is not", before - 2, ScriptBoard.adminCount());
        assertEquals(0, theirs.count(f -> f.has("status")));
    }

    @Test
    public void aNewPasswordClosesTheConsolesThatStillUseTheOldSignIn() throws Throwable {
        Field grace = controllers.Application.class.getDeclaredField("CONSOLE_GRACE_MS");
        grace.setAccessible(true);
        Object was = grace.get(null);
        grace.set(null, 300L);
        try {
            final User user = user("rotating");
            final AdminSocketsTest.Tab stolen = loggedIn(user);
            java.util.Map<String, String> form = new java.util.HashMap<>();
            form.put("currentPassword", "secret-1");
            form.put("newPassword", "a-new-one-1");
            form.put("confirmPassword", "a-new-one-1");
            play.mvc.Result changed = play.test.Helpers.route(play.test.Helpers.fakeRequest("POST", "/saveUserSettings")
                .withFormUrlEncodedBody(form).withSession("uid", user.uid));
            String uid = Json.parse(play.test.Helpers.contentAsString(changed)).get("uid").asText();
            // The console that asked reconnects with the new sign-in at once: it is kept.
            final AdminSocketsTest.Tab fresh = tab(User.findByEmail(user.email));
            assertEquals(uid, fresh.uid);
            fresh.send("LogIn");
            await("LogIn", () -> fresh.count(AdminSocketsTest.userJson()) >= 1);

            await("the old one is told", () -> stolen.count(f -> f.has("status") && "unauthorized".equals(f.get("status").asText())) == 1);
            Thread.sleep(300);
            assertEquals(0, fresh.count(f -> f.has("status")));
            // The old socket gets nothing more: a change in the account isn't pushed to it.
            final int seen = stolen.frames.size();
            final int freshSeen = fresh.count(AdminSocketsTest.userJson());
            fresh.send("SelectExperiment", "experimentId", createExperiment("After").id);
            await("the new console hears", () -> fresh.count(AdminSocketsTest.userJson()) > freshSeen);
            Thread.sleep(300);
            assertEquals(seen, stolen.frames.size());
        } finally {
            grace.set(null, was);
        }
    }

    // ---- a tab that closes before its Admin is registered ----------------------------------------------

    @Test
    public void aTabClosedRightAfterLoggingInLeavesNoAdminBehind() throws Throwable {
        final int before = ScriptBoard.adminCount();
        for (int i = 0; i < 3; i++) {
            AdminSocketsTest.Tab t = tab(user("brief"));
            t.send("LogIn");
            t.close();   // before the account's engine actor has registered it
        }
        Thread.sleep(800);
        assertEquals(before, ScriptBoard.adminCount());
    }

    // ---- event times come with UTC ----------------------------------------------------------------------

    @Test
    public void theEventLogSaysWhenInUtc() throws Exception {
        User user = user("events");
        Experiment experiment = createExperiment("Logged");
        ExperimentInstance instance = createInstance("logged", experiment);
        Event event = new Event();
        event.name = "chose";
        event.datetime = new Date(1793109605123L);
        event.experimentInstance = instance;
        event.save();

        final AdminSocketsTest.Tab t = tab(user);
        t.send("ShowEvent", "id", instance.id);
        await("events", () -> t.first(f -> f.has("events")) != null);
        JsonNode shown = t.first(f -> f.has("events")).get("events").get(0);
        assertEquals("2026-10-27T14:00:05.123Z", shown.get("datetimeUtc").asText());
        assertTrue("the old local time is still there", shown.has("datetime"));
    }

    // ---- password change over the websocket -------------------------------------------------------------

    @Test
    public void theWebsocketPasswordChangeFollowsTheSettingsPagesRules() throws Exception {
        final User user = user("pw");
        final AdminSocketsTest.Tab t = tab(user);

        t.send("SaveUserSettings", "currentPassword", "secret-1", "newPassword", "short", "confirmPassword", "short");
        await("too short", () -> t.count(AdminSocketsTest.error("SaveUserSettings")) == 1);
        assertTrue(t.first(AdminSocketsTest.error("SaveUserSettings")).get("error").asText().contains("at least 8"));
        assertNotNull(User.authenticate(user.email, "secret-1"));

        // Taken as typed: spaces around it are part of it.
        t.send("SaveUserSettings", "currentPassword", "secret-1", "newPassword", " spaced out ", "confirmPassword", " spaced out ");
        await("changed", () -> User.authenticate(user.email, " spaced out ") != null);
        assertNull(User.authenticate(user.email, "spaced out"));
        // ...and whoever held the old token is signed out.
        assertNull(User.findByUID(user.uid));
        assertNotNull(User.findByEmail(user.email).uid);
    }

    // ---- deleting the autopilot's experiment ------------------------------------------------------------

    @Test
    public void deletingTheAutopilotsExperimentSwitchesItOff() throws Exception {
        final User user = user("deleter");
        Experiment experiment = createExperiment("Unattended, then deleted");
        own(user, experiment);
        AutopilotSettings settings = new AutopilotSettings();
        settings.enabled = true;
        settings.experimentId = experiment.id;
        settings.operatorEmail = user.email;
        settings.save();

        final AdminSocketsTest.Tab t = loggedIn(user);
        final int before = t.count(AdminSocketsTest.userJson());
        t.send("DeleteExperiment", "experimentId", experiment.id);
        await("deleted", () -> t.count(AdminSocketsTest.userJson()) > before);
        AutopilotSettings saved = AutopilotSettings.load();
        assertFalse(saved.enabled);
        assertNull(saved.experimentId);
        assertEquals("the rest is kept", user.email, saved.operatorEmail);
    }
}
