import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import javax.script.ScriptEngine;
import java.lang.ref.WeakReference;
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
 * as sees the session in its open console tabs. A step that fails during the game of a session it
 * opened ends that session and pauses it; in a session a researcher runs by hand (even of its
 * experiment) the error is shown and the session goes on.
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
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession"}) {
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

    /** Settings whose waiting room starts the game as soon as one person is here. */
    private static ObjectNode startsAtOnce(Experiment experiment) {
        ObjectNode s = settings(experiment, 1);
        ObjectNode room = (ObjectNode) s.get("waitingRoom");
        room.put("maxPlayers", 1);
        room.put("countdownSeconds", 0);
        return s;
    }

    /** As if a participant's page had connected: the autopilot's waiting room counts them. */
    private static void arrives(String id) throws Exception {
        engine().eval("def v = g.addPlayer('" + id + "'); v._system.connected = true; autopilot.onConnection('" + id + "', true); null");
    }

    private static List<Event> events(ExperimentInstance session, String name) {
        return Event.find.where().eq("experimentInstance.id", session.id).eq("name", name).findList();
    }

    private static String value(Event event, String name) {
        for (EventData d : event.eventData) {
            if (name.equals(d.name)) return d.value;
        }
        return null;
    }

    private static Result resume(User user) {
        return callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", user.uid));
    }

    // InitStep starts RoundStep, whose time limit ends it on its timer, and its done starts FinishStep,
    // which has a typo on its line 3: that threw on the timer's thread, and the session waited for good.
    private void stepsWithATypoInFinishStep(Experiment experiment) {
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = { finishStep.start() }");
        createStep(experiment, "FinishStep", "finishStep = stepFactory.createNoUserActionStep()\n"
            + "finishStep.run = {\n"
            + "  println \"Thank you for playing ${roundz} rounds\"\n"
            + "}\n"
            + "finishStep.done = { finishStep.finish() }");
    }

    @Test
    public void aStepThatFailsDuringTheGameEndsTheAutopilotsSessionAndPausesItSayingWhere() throws Exception {
        final User researcher = user("midgame");
        final Experiment experiment = createExperiment("Fails mid-game");
        stepsWithATypoInFinishStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);

        arrives("midgame-1");
        assertEquals("the game started", Boolean.TRUE, ExperimentInstance.findById(session.id).hasStarted);
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);

        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        // (FinishStep has no name of its own: it is called by the variable that holds it)
        assertTrue(problem, problem.contains("the step \"finishStep\" failed during its session \"" + session.name + "\""));
        assertTrue(problem, problem.contains("line 3: No such property: roundz"));
        assertTrue(problem, problem.contains("Steps page"));
        List<Event> failed = events(session, "stepFailed");
        assertEquals(1, failed.size());
        assertEquals("finishStep", value(failed.get(0), "step"));
        assertTrue(value(failed.get(0), "error"), value(failed.get(0), "error").startsWith("line 3: No such property: roundz"));
        assertNull("the link leads nowhere now", Autopilot.openInstance(experiment.id));

        // Not even the watchdog opens another until Resume
        watchdog();
        Thread.sleep(500);
        assertEquals(1, sessions(experiment).size());
        Result resumed = resume(researcher);
        assertEquals(contentAsString(resumed), OK, status(resumed));
        await("the next session", () -> sessions(experiment).size() == 2 && running(experiment) == 1);
    }

    @Test
    public void aGameThatFailsToStartEndsTheAutopilotsSessionInsteadOfLeavingItOnTheCountdown() throws Exception {
        final User researcher = user("nostart");
        final Experiment experiment = createExperiment("Fails to start");
        // InitStep's done starts a step that doesn't exist (a typo): the start of the game throws.
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = {\n"
            + "  roundStepp.start()\n"
            + "}");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);

        arrives("nostart-1");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertNull("nobody new is let in", Autopilot.openInstance(experiment.id));

        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"InitStep\" failed during its session"));
        assertTrue(problem, problem.contains("line 4: No such property: roundStepp"));
        List<Event> failed = events(session, "stepFailed");
        assertEquals(1, failed.size());
        assertEquals("InitStep", value(failed.get(0), "step"));

        watchdog();
        Thread.sleep(500);
        assertEquals(1, sessions(experiment).size());
        assertEquals(OK, status(resume(researcher)));
        await("the next session", () -> sessions(experiment).size() == 2 && running(experiment) == 1);
    }

    @Test
    public void aStepThatFailsInASessionRunByHandIsShownOnTheConsolesAndTheSessionGoesOn() throws Exception {
        final User researcher = user("byhand");
        final Experiment experiment = createExperiment("Fails by hand");
        stepsWithATypoInFinishStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the selection", () -> selected(researcher, experiment));
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        tab.send("RunGame");
        // As processScript tells of an error, from the step: the Live session page shows it.
        await("the error on the console", () -> tab.count(f -> f.has("error") && f.has("source")
            && "finishStep".equals(f.get("source").asText()) && f.get("error").asText().startsWith("Caught error: ")
            && f.get("error").asText().contains("roundz")) == 1);
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Thread.sleep(500);
        assertEquals("the researcher decides", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }

    private static ExperimentInstance named(Experiment experiment, String name) {
        for (ExperimentInstance i : sessions(experiment)) {
            if (name.equals(i.name)) return i;
        }
        return null;
    }

    /** A console tab of the user's, logged in, with the experiment selected. */
    private AdminSocketsTest.Tab console(final User user, final Experiment experiment) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the selection", () -> selected(user, experiment) && experiment.id.equals(engineExperimentId()));
        return tab;
    }

    @Test
    public void aSessionOfItsExperimentLaunchedByHandGoesOnWhenAStepFailsAndThePauseKeepsItsReason() throws Exception {
        final User researcher = user("takeover");
        final Experiment experiment = createExperiment("Taken over by hand");
        stepsWithATypoInFinishStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, settings(experiment, 2))));
        await("the autopilot's session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);

        // The researcher takes over: a session of the same experiment launched by hand, which ends the
        // autopilot's and pauses it.
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session launched by hand", () -> named(experiment, "by hand") != null
            && named(experiment, "by hand").status == ExperimentInstance.Status.RUNNING && running(experiment) == 1);
        final ExperimentInstance byHand = named(experiment, "by hand");
        final String pausedFor = state(researcher).get("status").get("problem").asText();
        assertTrue(pausedFor, pausedFor.contains("ended when " + researcher.email + " launched another session"));

        tab.send("RunGame");
        await("the error on the console", () -> tab.count(f -> f.has("error") && f.has("source")
            && "finishStep".equals(f.get("source").asText()) && f.get("error").asText().contains("roundz")) == 1);
        await("the event", () -> events(byHand, "stepFailed").size() == 1);
        Thread.sleep(500);
        assertEquals("the researcher decides", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(byHand.id).status);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        assertEquals("still paused for the launch", pausedFor, status.get("problem").asText());
    }

    @Test
    public void itsOwnSessionResumedByHandAfterAPauseIsTheResearchers() throws Exception {
        final User researcher = user("resumer");
        final Experiment auto = createExperiment("Resumed by hand");
        final Experiment other = createExperiment("In the meantime");
        stepsWithATypoInFinishStep(auto);
        researcher.ownedExperiments.add(auto);
        researcher.ownedExperiments.add(other);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, settings(auto, 2))));
        await("the autopilot's session", () -> sessions(auto).size() == 1 && running(auto) == 1);
        final ExperimentInstance first = sessions(auto).get(0);

        // A researcher runs another experiment (the autopilot's session ends, it pauses), and presses
        // Resume while that session is live: the autopilot waits for the engine...
        final AdminSocketsTest.Tab tab = console(researcher, other);
        await("paused", () -> state(researcher).get("status").get("paused").asBoolean());
        tab.send("LaunchGame", "name", "meanwhile", "parameters", Json.newObject());
        await("their session", () -> running(other) == 1);
        assertEquals(OK, status(resume(researcher)));
        // ...and they resume the autopilot's old session themselves.
        tab.send("SelectInstance", "id", first.id);
        await("resumed", () -> ExperimentInstance.findById(first.id).status == ExperimentInstance.Status.RUNNING
            && auto.id.equals(engineExperimentId()));
        assertEquals("nothing else opened", 1, sessions(auto).size());

        tab.send("RunGame");
        await("the event", () -> events(first, "stepFailed").size() == 1);
        Thread.sleep(500);
        assertEquals("theirs to decide about", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(first.id).status);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }

    // The experiment starts its game itself, from its OnJoinStep, as soon as someone is in; InitStep's
    // done has a typo on its line 4.
    private void startsOnJoinWithATypoInInitStep(Experiment experiment) {
        createStep(experiment, "OnJoinStep", "onJoinStep = stepFactory.createNoUserActionStep()\n"
            + "onJoinStep.run = { playerId -> initStep.start() }\n"
            + "onJoinStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = {\n"
            + "  roundStepp.start()\n"
            + "}");
    }

    @Test
    public void aGameItsOnJoinStepStartsThatFailsEndsTheAutopilotsSession() throws Exception {
        final User researcher = user("onjoin");
        final Experiment experiment = createExperiment("Starts on join");
        startsOnJoinWithATypoInInitStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // No waiting room: the experiment's own OnJoinStep starts the game.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        engine().eval("g.addPlayer('onjoin-1'); null");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertNull("nobody new is let in", Autopilot.openInstance(experiment.id));
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"InitStep\" failed during its session \"" + session.name + "\""));
        assertTrue(problem, problem.contains("line 4: No such property: roundStepp"));
        List<Event> failed = events(session, "stepFailed");
        assertEquals(1, failed.size());
        assertEquals("InitStep", value(failed.get(0), "step"));
    }

    @Test
    public void aGameTheOnJoinStepStartsThatFailsInASessionRunByHandIsTakenBackAndShownOnce() throws Exception {
        final User researcher = user("onjoinhand");
        final Experiment experiment = createExperiment("Starts on join, by hand");
        startsOnJoinWithATypoInInitStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        engine().eval("g.addPlayer('onjoinhand-1'); null");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        assertEquals("InitStep", value(events(session, "stepFailed").get(0), "step"));
        // The game never got going: as when the console's Start fails, people may come in again, and
        // Start can try again.
        await("the start taken back", () -> tab.count(AdminSocketsTest.notify("GameStartFailed")) == 1);
        Thread.sleep(500);
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the researcher decides", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals("newcomers are let in again", Boolean.FALSE, now.hasStarted);
        assertNull(engine().get("initStep.start()"));
        assertEquals("the consoles hear of it once, from the OnJoinStep", 1, tab.count(f -> f.has("error")
            && f.get("error").asText().contains("roundStepp")));
        assertEquals("OnJoinStep", tab.first(f -> f.has("error") && f.get("error").asText().contains("roundStepp"))
            .get("source").asText());

        tab.send("SendScript", "script", "initStep.done = { }");
        await("fixed", () -> tab.count(f -> f.has("request") && "SendScript".equals(f.get("request").asText())
            && f.has("output")) == 1);
        tab.send("RunGame");
        await("started", () -> tab.count(f -> f.has("request") && "RunGame".equals(f.get("request").asText())
            && f.has("error") && f.get("error").asText().isEmpty()) == 1);
        assertEquals(Boolean.TRUE, ExperimentInstance.findById(session.id).hasStarted);
    }

    @Test
    public void aScriptThatFailsLeavesWordsOnItsThreadNotTheScriptsObjects() throws Exception {
        final User researcher = user("words");
        final Experiment experiment = createExperiment("Kept as words");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        console(researcher, experiment);

        // What went wrong is kept on the thread for the autopilot's start (gameStartFailed). A missing
        // method's exception holds its arguments (a player, say, and through it the graph): here an
        // object nothing else refers to once the script has run.
        Object argument = new Object();
        WeakReference<Object> collected = new WeakReference<>(argument);
        engine().put("argument", argument);
        argument = null;
        String failed = ScriptBoard.processScript("def kept = argument; argument = null; kept.noSuchMethodHere(kept)", null, "test");
        assertNotNull(failed);
        assertTrue(failed, failed.contains("noSuchMethodHere"));
        for (int i = 0; i < 50 && collected.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull("the failure kept the script's objects on this thread", collected.get());
    }

    @Test
    public void noSessionOpensWhileTheDiskIsAlmostFull() throws Exception {
        final User researcher = user("fulldisk");
        final Experiment experiment = createExperiment("No room");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        Field free = Autopilot.class.getDeclaredField("freeDiskBytes");
        free.setAccessible(true);
        Object before = free.get(null);
        try {
            free.set(null, (java.util.function.Supplier<Long>) () -> 50L * 1024 * 1024);
            assertEquals(OK, status(post(researcher, settings(experiment, 2))));
            await("the problem", () -> state(researcher).get("status").get("problem").asText().contains("disk"));
            JsonNode status = state(researcher).get("status");
            assertEquals("error", status.get("problemKind").asText());
            assertTrue(status.get("problem").asText(), status.get("problem").asText().startsWith("The disk is almost full (50 MB free"));
            assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("no new session opens until there is room"));
            watchdog();
            Thread.sleep(500);
            assertTrue(sessions(experiment).isEmpty());

            // Room again: the next check opens one.
            free.set(null, (java.util.function.Supplier<Long>) () -> 500L * 1024 * 1024);
            watchdog();
            await("a session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
            await("no problem", () -> state(researcher).get("status").get("problem").isNull());
        } finally {
            free.set(null, before);
        }
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

        // Someone selects another experiment (ending the autopilot's session) and launches it by hand.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", manual.id);
        await("the selection", () -> selected(researcher, manual));
        await("its session stopped", () -> ExperimentInstance.findById(first.id).status == ExperimentInstance.Status.STOPPED);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        assertEquals("paused", status.get("problemKind").asText());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains(researcher.email));
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the manual session", () -> sessions(manual).size() == 1
            && sessions(manual).get(0).status == ExperimentInstance.Status.RUNNING);

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

        // A colleague selects their experiment to edit it: the autopilot's session ends, it pauses.
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(colleague.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", theirs.id);
        await("paused", () -> state(operator).get("status").get("paused").asBoolean());
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
