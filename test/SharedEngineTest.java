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

        b.send("SendScript", "script", "bobWasHere = 1");
        b.send("SendStep", "id", -1, "name", "Setup", "source", "bobStep = 1");
        b.send("RunGame");
        b.send("MakeChoice", "choiceUID", "nope");
        b.send("SubmitAMTTask", "lifetimeInSeconds", 60, "tutorialTime", 0);
        b.send("EditTie", "op", "add", "a", "x", "b", "y");
        for (final String request : new String[]{"SendScript", "SendStep", "RunGame", "MakeChoice", "SubmitAMTTask"}) {
            await(request + " refused", () -> b.count(AdminSocketsTest.error(request)) == 1);
            String text = b.first(AdminSocketsTest.error(request)).get("error").asText();
            assertTrue(text, text.contains("the engine is running \"Hers\"") && text.contains("\"His\""));
        }
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
        await("selected", () -> first.id.equals(User.findByEmail(user.email).selectedExperiment.id));
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
