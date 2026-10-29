import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The autopilot opening real sessions on the shared engine: saving the settings opens one, applies
 * the console's waiting room over the experiment's own, keeps an open session when only the waiting
 * room changes, and opens the next one after a session finishes when continuous. The account it runs
 * as sees the session in its open console tabs. A researcher can work on another experiment while it
 * runs: selecting one leaves its session (and the autopilot) alone.
 */
public class AutopilotSessionTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void cleanUp() throws Throwable {
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
        Thread.sleep(300);   // let a launch that is under way finish before the tables are emptied
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
        Field launch = Autopilot.class.getDeclaredField("launchRequestedAt");
        launch.setAccessible(true);
        launch.set(null, 0L);
        for (String name : new String[]{"pausedBecause", "doneBecause"}) {
            Field f2 = Autopilot.class.getDeclaredField(name);
            f2.setAccessible(true);
            f2.set(null, null);
        }
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);
        due.set(null, 0L);
    }

    private static void watchdog() throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("watchdog");
        m.setAccessible(true);
        m.invoke(null);
    }

    /** Whether the user has the experiment selected (false while the selection is not saved yet). */
    private static boolean selected(User user, Experiment experiment) {
        Experiment selection = User.findByEmail(user.email).selectedExperiment;
        return selection != null && experiment.id.equals(selection.id);
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

    private static Object engineValue(String script) {
        try {
            ScriptEngine engine = engine();
            return engine == null ? null : engine.eval(script);
        } catch (Exception e) {
            return null;
        }
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static JsonNode state(User user) {
        return Json.parse(contentAsString(route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid))));
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
    }

    private static ObjectNode settings(Experiment experiment, int minPlayers) {
        ObjectNode s = Json.newObject();
        s.put("enabled", true);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", minPlayers);
        room.putNull("maxPlayers");
        room.put("waitSeconds", 30);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", 5);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", true);
        replace.put("afterSeconds", 15);
        replace.put("returnToHuman", true);
        return s;
    }

    @Test
    public void aSessionWhoseStepsDontLoadIsStoppedAndTheAutopilotPausesSayingWhere() throws Exception {
        final User researcher = user("typo");
        final Experiment experiment = createExperiment("Has a typo");
        // valid Groovy, but "five" is not defined: it fails as the step loads
        createStep(experiment, "InitStep", "NETWORK_SIZE = 3\nSEATS = five\nautopilot.waitingRoom([minPlayers: 2])");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        Result saved = post(researcher, settings(experiment, 2));
        assertEquals(contentAsString(saved), OK, status(saved));
        await("the session to be opened and stopped", () -> sessions(experiment).size() == 1
            && sessions(experiment).get(0).status == ExperimentInstance.Status.STOPPED);
        Thread.sleep(500);
        assertEquals("no other session opened in its place", 1, sessions(experiment).size());

        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("InitStep: line 2: No such property: five"));
        assertTrue(problem, problem.contains("Steps page"));
        assertTrue("the link leads nowhere now", status.get("instance").isNull()
            || !"RUNNING".equals(status.get("instance").get("status").asText()));
        List<Event> events = Event.find.where().eq("experimentInstance.id", sessions(experiment).get(0).id)
            .eq("name", "stepsFailedToLoad").findList();
        assertEquals(1, events.size());

        // Not even the watchdog opens another until Resume
        watchdog();
        Thread.sleep(500);
        assertEquals(1, sessions(experiment).size());
    }

    @Test
    public void theAutopilotKeepsASessionOpenWithTheConsolesWaitingRoom() throws Exception {
        final User researcher = user("pilot");
        final Experiment experiment = createExperiment("Runs by itself");
        createStep(experiment, "Setup", "autopilot.waitingRoom([minPlayers: 9, onCancel: { players -> }])");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // The researcher has a console open: it is told about the session the autopilot opens.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);

        Result saved = post(researcher, settings(experiment, 3));
        assertEquals(contentAsString(saved), OK, status(saved));
        // Saved again at once, while that session is still being opened: still one session.
        assertEquals(OK, status(post(researcher, settings(experiment, 3))));

        await("a session to open", () -> sessions(experiment).size() == 1
            && sessions(experiment).get(0).status == ExperimentInstance.Status.RUNNING);
        Thread.sleep(500);
        assertEquals("one session", 1, sessions(experiment).size());
        final ExperimentInstance first = sessions(experiment).get(0);
        assertTrue(first.name, first.name.startsWith("Autopilot · "));
        await("the console's waiting room over the experiment's own",
            () -> Integer.valueOf(3).equals(engineValue("autopilot.room?.minPlayers")));
        assertEquals(true, engineValue("autopilot.room.onCancel != null"));
        assertEquals(15, engineValue("autopilot.replace.afterSeconds"));
        await("the researcher's console shows the new session", () -> tab.count(f -> f.has("experimentInstance")
            && f.get("experimentInstance").isObject() && f.get("experimentInstance").get("id").asLong() == first.id) >= 1);

        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("on").asBoolean());
        assertEquals(first.id.longValue(), status.get("instance").get("id").asLong());
        assertEquals("RUNNING", status.get("instance").get("status").asText());
        assertFalse(status.get("instance").get("gameStarted").asBoolean());
        assertEquals("/play/" + experiment.id + "/login", status.get("links").get("login").asText());
        assertEquals(first.id, Autopilot.openInstance(experiment.id).id);

        // Changing the waiting room keeps the open session and applies at once.
        assertEquals(OK, status(post(researcher, settings(experiment, 4))));
        await("the new minimum in the running engine", () -> Integer.valueOf(4).equals(engineValue("autopilot.room?.minPlayers")));
        Thread.sleep(500);
        assertEquals("no second session", 1, sessions(experiment).size());

        // When it finishes (here: by hand), the next one opens.
        ExperimentInstance done = ExperimentInstance.findById(first.id);
        done.status = ExperimentInstance.Status.FINISHED;
        done.save();
        Autopilot.sessionFinished(done);
        await("the next session", () -> sessions(experiment).size() == 2
            && sessions(experiment).get(1).status == ExperimentInstance.Status.RUNNING);
        await("the waiting room again", () -> Integer.valueOf(4).equals(engineValue("autopilot.room?.minPlayers")));

        // Turned off, nothing new opens; the open session keeps running.
        assertEquals(OK, status(post(researcher, Json.parse("{\"enabled\": false}"))));
        ExperimentInstance second = sessions(experiment).get(1);
        ExperimentInstance finished = ExperimentInstance.findById(second.id);
        finished.status = ExperimentInstance.Status.FINISHED;
        finished.save();
        Autopilot.sessionFinished(finished);
        Thread.sleep(600);
        assertEquals(2, sessions(experiment).size());
    }

    @Test
    public void aResearcherWhoMovesTheEngineOffItsSessionPausesItUntilResume() throws Exception {
        final User researcher = user("busy");
        final Experiment auto = createExperiment("Autopiloted");
        final Experiment manual = createExperiment("Run by hand");
        researcher.ownedExperiments.add(auto);
        researcher.ownedExperiments.add(manual);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, settings(auto, 2))));
        await("the autopilot's session", () -> sessions(auto).size() == 1
            && sessions(auto).get(0).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance first = sessions(auto).get(0);

        // Someone selects another experiment (which leaves the autopilot's session alone) and launches
        // it by hand, which ends the autopilot's session.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", manual.id);
        await("the selection", () -> selected(researcher, manual));
        Thread.sleep(300);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(first.id).status);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the manual session", () -> sessions(manual).size() == 1
            && sessions(manual).get(0).status == ExperimentInstance.Status.RUNNING);
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(first.id).status);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        assertEquals("paused", status.get("problemKind").asText());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains(researcher.email));
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("launched another session"));

        // Paused, it doesn't take the engine back, not even when the manual session finishes.
        watchdog();
        ExperimentInstance manualSession = ExperimentInstance.findById(sessions(manual).get(0).id);
        manualSession.status = ExperimentInstance.Status.FINISHED;
        manualSession.save();
        Autopilot.sessionFinished(manualSession);
        Thread.sleep(800);
        watchdog();
        Thread.sleep(500);
        assertEquals(1, sessions(auto).size());
        assertTrue(selected(researcher, manual));

        // Resume (which changes no settings) opens its next one.
        Result resumed = callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", researcher.uid));
        assertEquals(contentAsString(resumed), OK, status(resumed));
        assertFalse(Json.parse(contentAsString(resumed)).get("status").get("paused").asBoolean());
        await("the autopilot's next session", () -> sessions(auto).size() == 2 && running(auto) == 1);
        // ...without changing what the researcher has selected.
        assertTrue(selected(researcher, manual));
    }

    @Test
    public void theWatchdogLeavesTheEngineToAResearcherWhoHasAnotherExperimentLoaded() throws Throwable {
        final User operator = user("operator");
        final User colleague = user("colleague");
        final Experiment auto = createExperiment("Unattended");
        final Experiment theirs = createExperiment("Being edited");
        operator.ownedExperiments.add(auto);
        operator.saveManyToManyAssociations("ownedExperiments");
        colleague.ownedExperiments.add(theirs);
        colleague.saveManyToManyAssociations("ownedExperiments");
        // The operator has another experiment selected: the autopilot leaves that selection alone.
        User op = User.findByEmail(operator.email);
        op.setSelectedExperiment(Experiment.findById(theirs.id));
        op.update();

        assertEquals(OK, status(post(operator, settings(auto, 2))));
        await("the autopilot's session", () -> sessions(auto).size() == 1 && running(auto) == 1);
        assertTrue("the operator's selection is kept", selected(operator, theirs));
        assertEquals(-1L, User.findByEmail(operator.email).experimentInstanceId.longValue());

        // A colleague selects their experiment to edit it, which leaves the autopilot's session alone;
        // then they stop that session (it pauses) and load their experiment to try it.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(colleague.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", theirs.id);
        await("the selection", () -> selected(colleague, theirs));
        assertEquals(auto.id, engineExperimentId());
        assertEquals(1, running(auto));
        tab.send("StopGame", "id", sessions(auto).get(0).id);
        await("paused", () -> state(operator).get("status").get("paused").asBoolean());
        tab.send("ReloadEngine");
        await("their experiment in the engine", () -> theirs.id.equals(engineExperimentId()));

        // Even with the pause lifted by other means (e.g. between two sessions), the periodic check
        // leaves the engine to the colleague while their console is open...
        for (String name : new String[]{"pausedBecause", "doneBecause"}) {
            Field f = Autopilot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
        watchdog();
        Thread.sleep(1000);
        assertEquals(1, sessions(auto).size());
        JsonNode status = state(operator).get("status");
        assertEquals("waiting", status.get("problemKind").asText());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains(colleague.email));
        assertTrue(selected(operator, theirs));

        // ...and takes it once they have closed it.
        tab.close();
        watchdog();
        await("the autopilot's next session", () -> sessions(auto).size() == 2 && running(auto) == 1);
        assertTrue("still the operator's own selection", selected(operator, theirs));
    }

    @Test
    public void selectingAnotherExperimentWhileTheAutopilotRunsChangesOnlyTheSelection() throws Throwable {
        final User operator = user("unattended");
        final User researcher = user("editor");
        final Experiment auto = createExperiment("Running unattended");
        final Experiment draft = createExperiment("Being written");
        createStep(draft, "Setup", "draftLoaded = true");
        operator.ownedExperiments.add(auto);
        operator.saveManyToManyAssociations("ownedExperiments");
        researcher.ownedExperiments.add(draft);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(operator, settings(auto, 2))));
        await("the autopilot's session", () -> sessions(auto).size() == 1 && running(auto) == 1);
        final ExperimentInstance live = sessions(auto).get(0);
        final ScriptEngine engine = engine();

        // Another researcher works on their own experiment while it runs.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        final int reloads = tab.count(AdminSocketsTest.notify("ScriptEngineReloading"));
        tab.send("SelectExperiment", "experimentId", draft.id);
        await("the selection, answered", () -> tab.count(f -> f.has("user") && f.has("engineKept")) == 1);
        assertTrue(selected(researcher, draft));
        JsonNode reply = tab.first(f -> f.has("engineKept"));
        assertEquals(draft.id.longValue(), reply.get("experiment").get("id").asLong());
        assertEquals("it says what the engine still runs", auto.id.longValue(), reply.get("engine").get("experimentId").asLong());
        assertEquals(live.id.longValue(), reply.get("engine").get("sessionId").asLong());

        // The engine and the autopilot's session are untouched, and the autopilot isn't paused.
        Thread.sleep(500);
        assertSame("not rebuilt", engine, engine());
        assertEquals(reloads, tab.count(AdminSocketsTest.notify("ScriptEngineReloading")));
        assertEquals(auto.id, engineExperimentId());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);
        assertNull("the draft's steps aren't loaded", engineValue("draftLoaded"));
        JsonNode status = state(researcher).get("status");
        assertTrue(status.get("on").asBoolean());
        assertFalse(status.toString(), status.get("paused").asBoolean());

        // Its Build pages work (here: a new step, saved to the database)...
        tab.send("CreateStep", "name", "Rounds");
        await("the step", () -> Experiment.findById(draft.id).getSteps().size() == 2);

        // ...and its engine actions are refused, saying what runs and what to do.
        tab.send("SendScript", "script", "draftScript = 1", "experimentId", draft.id, "requestId", "d-1");
        tab.send("SendStep", "id", -1, "name", "Setup", "source", "draftStep = 1", "experimentId", draft.id);
        tab.send("RunGame");
        tab.send("ReloadEngine");
        for (final String request : new String[]{"SendScript", "SendStep", "RunGame", "ReloadEngine"}) {
            await(request + " refused", () -> tab.count(AdminSocketsTest.error(request)) == 1);
            String text = tab.first(AdminSocketsTest.error(request)).get("error").asText();
            assertTrue(text, text.contains("the engine is running \"Running unattended\" (the autopilot's session \"" + live.name + "\")"));
            assertTrue(text, text.contains("not your selected experiment \"Being written\""));
            assertTrue(text, text.contains("end that session first (that pauses the autopilot), or open a new session of \"Being written\""));
        }
        assertTrue(tab.first(AdminSocketsTest.error("ReloadEngine")).get("error").asText().startsWith("Nothing was reloaded"));
        Thread.sleep(300);
        assertSame("still not rebuilt", engine, engine());
        assertNull(engineValue("draftScript"));
        assertNull(engineValue("draftStep"));
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());

        // On the experiment the engine runs, the account follows its live session (and can act on it).
        assertEquals(-1L, User.findByEmail(researcher.email).experimentInstanceId.longValue());
        tab.send("SelectExperiment", "experimentId", auto.id);
        await("answered", () -> tab.count(f -> f.has("user") && f.has("engineKept")) == 2);
        assertEquals(live.id, User.findByEmail(researcher.email).experimentInstanceId);
        JsonNode back = null;
        for (JsonNode f : tab.frames) if (f.has("engineKept")) back = f;
        assertEquals(live.id.longValue(), back.get("experimentInstance").get("id").asLong());
        assertSame(engine, engine());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);
        tab.send("SendScript", "script", "watched = 1", "experimentId", auto.id, "requestId", "a-1");
        await("its scripts run again", () -> Integer.valueOf(1).equals(engineValue("watched")));
    }

    @Test
    public void stopPressedOnItsSessionAfterItFinishedLeavesTheAutopilotAlone() throws Exception {
        final User operator = user("finisher");
        final Experiment auto = createExperiment("Finishes by itself");
        operator.ownedExperiments.add(auto);
        operator.saveManyToManyAssociations("ownedExperiments");
        ObjectNode settings = settings(auto, 2);
        settings.put("nextSessionDelaySeconds", 2);
        assertEquals(OK, status(post(operator, settings)));
        await("its session", () -> sessions(auto).size() == 1 && running(auto) == 1);
        final ExperimentInstance first = sessions(auto).get(0);

        // A console that still shows it running; meanwhile its game finishes (the next one is due).
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(operator.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        engine().eval("gameListener.finish()");
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(first.id).status);

        // Stop pressed on it: nothing is stopped, it says why, and the autopilot isn't paused (which
        // also called off the next session).
        tab.send("StopGame", "id", first.id);
        await("refused", () -> tab.count(AdminSocketsTest.error("StopGame")) == 1);
        String why = tab.first(AdminSocketsTest.error("StopGame")).get("error").asText();
        assertTrue(why, why.contains("is already over (it has finished)"));
        assertFalse(state(operator).get("status").toString(), state(operator).get("status").get("paused").asBoolean());
        // (Nor does it pause when told outright that a researcher ended a session that is over.)
        java.lang.reflect.Method stopped = Autopilot.class.getDeclaredMethod("sessionStopped", ExperimentInstance.class, User.class, String.class);
        stopped.setAccessible(true);
        stopped.invoke(null, ExperimentInstance.findById(first.id), operator, "was stopped by %s");
        assertFalse(state(operator).get("status").get("paused").asBoolean());
        assertEquals(0, Event.find.where().eq("experimentInstance.id", first.id).eq("name", "sessionStopped").findRowCount());

        await("the next session", () -> sessions(auto).size() == 2 && running(auto) == 1);
    }

    // (Ids don't always grow with time here, so sessions are told apart by status.)
    private static int running(Experiment experiment) {
        int n = 0;
        for (ExperimentInstance i : sessions(experiment)) {
            if (i.status == ExperimentInstance.Status.RUNNING) n++;
        }
        return n;
    }

    @Test
    public void savingNeverEndsAnotherExperimentsSessionUnlessToldToAndStoppingItsSessionPausesIt() throws Exception {
        final User researcher = user("careful");
        final Experiment auto = createExperiment("Autopiloted too");
        final Experiment manual = createExperiment("Someone's live session");
        researcher.ownedExperiments.add(auto);
        researcher.ownedExperiments.add(manual);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // A session of another experiment is live in the engine.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", manual.id);
        await("the selection", () -> selected(researcher, manual));
        tab.send("LaunchGame", "name", "live", "parameters", Json.newObject());
        await("the live session", () -> sessions(manual).size() == 1
            && sessions(manual).get(0).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance live = sessions(manual).get(0);

        // Turning the autopilot on, then editing it: the live session is left alone, and said so.
        assertEquals(OK, status(post(researcher, settings(auto, 2))));
        assertEquals(OK, status(post(researcher, settings(auto, 3))));
        watchdog();
        Thread.sleep(1000);
        assertTrue(sessions(auto).isEmpty());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(live.id).status);
        JsonNode status = state(researcher).get("status");
        assertEquals(live.id.longValue(), status.get("engineSession").get("id").asLong());
        assertTrue(status.get("problem").asText().contains("Another session is running"));

        // Told to take over, it does (that session ends).
        ObjectNode takeOver = settings(auto, 3);
        takeOver.put("takeOver", true);
        assertEquals(OK, status(post(researcher, takeOver)));
        await("the autopilot's session", () -> sessions(auto).size() == 1
            && sessions(auto).get(0).status == ExperimentInstance.Status.RUNNING);
        await("the other one ended", () -> ExperimentInstance.findById(live.id).status == ExperimentInstance.Status.STOPPED);
        final ExperimentInstance first = sessions(auto).get(0);

        // The researcher stops it: paused, nothing reopens by itself...
        tab.send("StopGame", "id", first.id);
        await("stopped", () -> ExperimentInstance.findById(first.id).status == ExperimentInstance.Status.STOPPED);
        await("paused", () -> state(researcher).get("status").get("paused").asBoolean());
        watchdog();
        Thread.sleep(1000);
        assertEquals(1, sessions(auto).size());
        assertTrue(state(researcher).get("status").get("problem").asText().contains("paused"));

        // ...until the settings are saved again.
        assertEquals(OK, status(post(researcher, settings(auto, 3))));
        await("a new session", () -> sessions(auto).size() == 2 && running(auto) == 1);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }
}
