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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The autopilot opening real sessions on the shared engine: saving the settings opens one, applies
 * the console's waiting room over the experiment's own, keeps an open session when only the waiting
 * room changes, and opens the next one after a session finishes when continuous. The account it runs
 * as sees the session in its open console tabs, and so does every account working on its experiment,
 * from one session to the next. A step failure stalls the game when it kept the next
 * step from getting going (a step gets going when its start() returns without throwing). One that
 * stalls the game of its own session (one it opened, or one of its experiment Resume or a save handed
 * it) ends that session and pauses it; in a session a researcher runs by hand (even of its
 * experiment) a start that didn't get going is taken back, the error is shown and the session goes
 * on. A failure that leaves the game going (once the next round got going, a mistake in the
 * OnLeaveStep) or that comes from an engine replaced since ends nothing. A researcher can
 * work on another experiment while it runs: selecting one leaves its session (and the autopilot)
 * alone.
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

        // A researcher runs another experiment (selecting it leaves the autopilot's session alone;
        // launching a session of it ends that one, and the autopilot pauses), and presses Resume while
        // their session is live: the autopilot waits for the engine...
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", other.id);
        await("the selection", () -> selected(researcher, other));
        assertEquals("selecting it ended nothing", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(first.id).status);
        tab.send("LaunchGame", "name", "meanwhile", "parameters", Json.newObject());
        await("their session", () -> running(other) == 1);
        await("paused", () -> state(researcher).get("status").get("paused").asBoolean());
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

    // The experiment's OnJoinStep starts the game once two people are in. InitStep's done starts the
    // first round (a minute long), then has a typo on its line 5.
    private void startsOnJoinAndFailsOnceTheFirstRoundHasStarted(Experiment experiment) {
        createStep(experiment, "OnJoinStep", "onJoinStep = stepFactory.createNoUserActionStep()\n"
            + "onJoinStep.run = { playerId -> if (g.V.count() >= 2) initStep.start() }\n"
            + "onJoinStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = {\n"
            + "  roundStep.start()\n"
            + "  println \"round ${roundd} started\"\n"
            + "}");
        createStep(experiment, "RoundStep", "roundStarts = 0\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 60\n"
            + "roundStep.run = { roundStarts++ }\n"
            + "roundStep.done = { }");
    }

    /** A console tab of the user's that only logs in: it sees what every console is told. */
    private AdminSocketsTest.Tab watching(final User user) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        return tab;
    }

    private static AdminSocketsTest.Matcher from(final String source) {
        return f -> f.has("source") && source.equals(f.get("source").asText());
    }

    @Test
    public void aStartTheOnJoinStepMadeStaysOnceInitStepHasStartedTheFirstRound() throws Exception {
        final User researcher = user("roundbegun");
        final Experiment experiment = createExperiment("Fails once the first round has started");
        startsOnJoinAndFailsOnceTheFirstRoundHasStarted(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        engine().eval("g.addPlayer('roundbegun-1'); null");
        await("the first OnJoinStep", () -> tab.count(from("OnJoinStep")) == 1);
        engine().eval("g.addPlayer('roundbegun-2'); null");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("InitStep", value(failed, "step"));
        assertTrue(value(failed, "error"), value(failed, "error").startsWith("line 5: No such property: roundd"));
        Thread.sleep(500);

        // The first round runs: the game got going, so its start stays, and the session stays closed
        // to newcomers. (Taken back, it let them in, and a newcomer's OnJoinStep started the game again
        // on top of that round.)
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the researcher decides", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals("closed to newcomers", Boolean.TRUE, now.hasStarted);
        assertEquals(Boolean.TRUE, engine().get("initStep.start()"));
        assertTrue(User.findByEmail(researcher.email).toJson().get("engine").get("gameStarted").asBoolean());
        assertEquals(0, tab.count(AdminSocketsTest.notify("GameStartFailed")));
        assertEquals(1, engineValue("roundStarts"));
        assertEquals("the consoles hear of it once", 1, tab.count(f -> f.has("error") && f.get("error").asText().contains("roundd")));
    }

    @Test
    public void aStepThatFailsOnceTheGameIsUnderWayIsRecordedAndTheAutopilotsSessionGoesOn() throws Exception {
        final User researcher = user("underway");
        final Experiment experiment = createExperiment("Goes on after a mistake");
        startsOnJoinAndFailsOnceTheFirstRoundHasStarted(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // No waiting room: the experiment's own OnJoinStep starts the game.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);
        final AdminSocketsTest.Tab tab = watching(researcher);

        engine().eval("g.addPlayer('underway-1'); null");
        await("the first OnJoinStep", () -> tab.count(from("OnJoinStep")) == 1);
        engine().eval("g.addPlayer('underway-2'); null");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        assertEquals("InitStep", value(events(session, "stepFailed").get(0), "step"));
        await("the consoles are told", () -> tab.count(f -> f.has("error") && f.get("error").asText().contains("roundd")) == 1);
        Thread.sleep(500);

        // Its first round is under way: nothing stalls, so nothing is ended.
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the game goes on", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals(Boolean.TRUE, now.hasStarted);
        assertEquals(1, engineValue("roundStarts"));
        JsonNode status = state(researcher).get("status");
        assertFalse(status.toString(), status.get("paused").asBoolean());
        assertEquals(session.id.longValue(), status.get("instance").get("id").asLong());
    }

    // The experiment's OnJoinStep starts the game once two people are in; InitStep (no choices) starts
    // the first round at once, whose run has a typo on its line 4: that round never gets going.
    private void startsOnJoinAndTheFirstRoundFailsAsItStarts(Experiment experiment) {
        createStep(experiment, "OnJoinStep", "onJoinStep = stepFactory.createNoUserActionStep()\n"
            + "onJoinStep.run = { playerId -> if (g.V.count() >= 2) initStep.start() }\n"
            + "onJoinStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 60\n"
            + "roundStep.run = {\n"
            + "  println \"round ${roundd}\"\n"
            + "}\n"
            + "roundStep.done = { }");
    }

    @Test
    public void aFirstRoundThatFailsAsItStartsEndsTheAutopilotsSessionWhoseOnJoinStepStartedIt() throws Exception {
        final User researcher = user("roundfails");
        final Experiment experiment = createExperiment("First round fails as it starts");
        startsOnJoinAndTheFirstRoundFailsAsItStarts(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // No waiting room: the experiment's own OnJoinStep starts the game.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its steps", () -> engineValue("roundStep") != null);
        final ExperimentInstance session = sessions(experiment).get(0);
        final AdminSocketsTest.Tab tab = watching(researcher);

        engine().eval("g.addPlayer('roundfails-1'); null");
        await("the first OnJoinStep", () -> tab.count(from("OnJoinStep")) == 1);
        engine().eval("g.addPlayer('roundfails-2'); null");

        // InitStep's start came back with an error from its first round: nothing got going, and
        // nothing would carry the game on.
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertNull("nobody new is let in", Autopilot.openInstance(experiment.id));
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"RoundStep\" failed during its session \"" + session.name + "\""));
        assertTrue(problem, problem.contains("line 4: No such property: roundd"));
        List<Event> failed = events(session, "stepFailed");
        assertEquals(1, failed.size());
        assertEquals("RoundStep", value(failed.get(0), "step"));
        watchdog();
        Thread.sleep(500);
        assertEquals("nothing opens until Resume", 1, sessions(experiment).size());
    }

    @Test
    public void aFirstRoundThatFailsAsItStartsInASessionRunByHandHandsTheStartBack() throws Exception {
        final User researcher = user("roundfailshand");
        final Experiment experiment = createExperiment("First round fails as it starts, by hand");
        startsOnJoinAndTheFirstRoundFailsAsItStarts(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        await("its steps", () -> engineValue("roundStep") != null);
        final ExperimentInstance session = sessions(experiment).get(0);

        engine().eval("g.addPlayer('roundfailshand-1'); null");
        await("the first OnJoinStep", () -> tab.count(from("OnJoinStep")) == 1);
        engine().eval("g.addPlayer('roundfailshand-2'); null");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        assertEquals("RoundStep", value(events(session, "stepFailed").get(0), "step"));
        // The game never got going: its start is taken back, people may come in again, and Start can
        // try again.
        await("the start taken back", () -> tab.count(AdminSocketsTest.notify("GameStartFailed")) == 1);
        Thread.sleep(500);
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the researcher decides", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals("newcomers are let in again", Boolean.FALSE, now.hasStarted);
        assertNull(engine().get("initStep.start()"));
        assertFalse(User.findByEmail(researcher.email).toJson().get("engine").get("gameStarted").asBoolean());

        tab.send("SendScript", "script", "roundStep.run = { }");
        await("fixed", () -> tab.count(f -> f.has("request") && "SendScript".equals(f.get("request").asText())
            && f.has("output")) == 1);
        tab.send("RunGame");
        await("started", () -> tab.count(f -> f.has("request") && "RunGame".equals(f.get("request").asText())
            && f.has("error") && f.get("error").asText().isEmpty()) == 1);
        assertEquals(0, tab.count(f -> f.has("error") && f.get("error").asText().contains("started already")));
        assertEquals(Boolean.TRUE, ExperimentInstance.findById(session.id).hasStarted);
    }

    // InitStep's done starts the first round (a minute long), which gets going, then has a typo on its
    // line 5.
    private void theFirstRoundGetsGoingThenInitStepFails(Experiment experiment) {
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = {\n"
            + "  roundStep.start()\n"
            + "  println \"round ${roundd} started\"\n"
            + "}");
        createStep(experiment, "RoundStep", "roundStarts = 0\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 60\n"
            + "roundStep.run = { roundStarts++ }\n"
            + "roundStep.done = { }");
    }

    @Test
    public void aStartByItsWaitingRoomWhoseFirstRoundGotGoingGoesOnAfterAMistakeInInitStep() throws Exception {
        final User researcher = user("roomround");
        final Experiment experiment = createExperiment("First round begun by the waiting room");
        theFirstRoundGetsGoingThenInitStepFails(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);
        final AdminSocketsTest.Tab tab = watching(researcher);

        arrives("roomround-1");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("InitStep", value(failed, "step"));
        assertTrue(value(failed, "error"), value(failed, "error").startsWith("line 5: No such property: roundd"));
        await("the consoles are told", () -> tab.count(f -> from("InitStep").matches(f) && f.has("error")
            && f.get("error").asText().contains("roundd")) == 1);
        Thread.sleep(1000);

        // Its first round is under way: nothing stalls, so nothing is ended, and the start stays.
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the game goes on", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals("closed to newcomers", Boolean.TRUE, now.hasStarted);
        assertEquals(Boolean.TRUE, engine().get("initStep.start()"));
        assertEquals(1, engineValue("roundStarts"));
        assertEquals(1, events(session, "stepFailed").size());
        JsonNode status = state(researcher).get("status");
        assertFalse(status.toString(), status.get("paused").asBoolean());
        assertEquals(session.id.longValue(), status.get("instance").get("id").asLong());
    }

    @Test
    public void theConsolesStartWhoseFirstRoundGotGoingKeepsTheGameStartedAfterAMistakeInInitStep() throws Exception {
        final User researcher = user("startround");
        final Experiment experiment = createExperiment("First round begun by Start");
        theFirstRoundGetsGoingThenInitStepFails(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        tab.send("RunGame");
        await("the error", () -> tab.count(f -> f.has("request") && "RunGame".equals(f.get("request").asText())
            && f.has("error") && f.get("error").asText().contains("roundd")) == 1);
        Thread.sleep(500);

        // The first round runs: the session stays closed to newcomers in the middle of it (it used to
        // take them again), and the consoles learn the game started.
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals(ExperimentInstance.Status.RUNNING, now.status);
        assertEquals("closed to newcomers", Boolean.TRUE, now.hasStarted);
        assertEquals(Boolean.TRUE, engine().get("initStep.start()"));
        assertEquals(1, engineValue("roundStarts"));
        assertTrue(User.findByEmail(researcher.email).toJson().get("engine").get("gameStarted").asBoolean());
        assertEquals(1, tab.count(AdminSocketsTest.notify("GameStarted")));
        assertEquals(0, tab.count(AdminSocketsTest.notify("GameStartFailed")));
        tab.send("RunGame");
        await("a second Start refused", () -> tab.count(f -> f.has("error") && f.get("error").asText().contains("started already")) == 1);
        assertEquals(1, engineValue("roundStarts"));
    }

    @Test
    public void aDoneOnATimerThatStartedTheNextRoundBeforeItFailedLeavesTheAutopilotsGameGoing() throws Exception {
        final User researcher = user("nextround");
        final Experiment experiment = createExperiment("Next round begun on a timer");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        // RoundStep ends on its timer; its done starts the next round, which gets going, then has a typo
        // on its line 7.
        createStep(experiment, "RoundStep", "round2Starts = 0\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = {\n"
            + "  round2Step.start()\n"
            + "  println \"round ${roundd} done\"\n"
            + "}\n"
            + "round2Step = stepFactory.createStep('Round2Step')\n"
            + "round2Step.duration = 60\n"
            + "round2Step.run = { round2Starts++ }\n"
            + "round2Step.done = { }");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);

        arrives("nextround-1");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("RoundStep", value(failed, "step"));
        assertTrue(value(failed, "error"), value(failed, "error").startsWith("line 7: No such property: roundd"));
        Thread.sleep(1000);

        // The next round is under way: the game goes on.
        assertEquals("the game goes on", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertEquals(1, engineValue("round2Starts"));
        assertEquals(1, events(session, "stepFailed").size());
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }

    // The OnJoinStep has a typo on its line 3, before it would start the game once two people are in.
    private void anOnJoinStepThatFailsBeforeItsStart(Experiment experiment) {
        createStep(experiment, "OnJoinStep", "onJoinStep = stepFactory.createNoUserActionStep()\n"
            + "onJoinStep.run = { playerId ->\n"
            + "  g.getVertex(playerId).text = \"Welcome, ${playerId} ${welcomee}\"\n"
            + "  if (g.V.count() >= 2) initStep.start()\n"
            + "}\n"
            + "onJoinStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { }");
    }

    @Test
    public void anOnJoinStepThatFailsBeforeTheGameStartsEndsTheAutopilotsSessionWithNoWaitingRoomToStartIt() throws Exception {
        final User researcher = user("joinfails");
        final Experiment experiment = createExperiment("OnJoinStep fails, no waiting room");
        anOnJoinStepThatFailsBeforeItsStart(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // No waiting room: only the OnJoinStep would start the game.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its steps", () -> engineValue("onJoinStep") != null);
        final ExperimentInstance session = sessions(experiment).get(0);

        engine().eval("g.addPlayer('joinfails-1'); null");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"onJoinStep\" failed during its session \"" + session.name + "\""));
        assertTrue(problem, problem.contains("line 3: No such property: welcomee"));
        assertEquals(1, events(session, "stepFailed").size());
        watchdog();
        Thread.sleep(500);
        assertEquals("nothing opens until Resume", 1, sessions(experiment).size());
    }

    @Test
    public void anOnJoinStepThatFailsWhileItsWaitingRoomFillsIsRecordedAndTheSessionGoesOn() throws Exception {
        final User researcher = user("joinroom");
        final Experiment experiment = createExperiment("OnJoinStep fails, waiting room on");
        anOnJoinStepThatFailsBeforeItsStart(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // The waiting room (two people) starts the game.
        assertEquals(OK, status(post(researcher, settings(experiment, 2))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(2).equals(engineValue("autopilot.room?.minPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);

        arrives("joinroom-1");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        assertEquals("onJoinStep", value(events(session, "stepFailed").get(0), "step"));
        Thread.sleep(1000);
        assertEquals("it waits for the next person", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
        assertEquals(session.id, Autopilot.openInstance(experiment.id).id);
    }

    @Test
    public void theComputerPlaysNoMoreOfASessionAFailureEnded() throws Exception {
        final User researcher = user("noai");
        final Experiment experiment = createExperiment("Ended with choices on screen");
        // RoundStep offers each player a choice that ends the game, then has a typo on its line 4: the
        // round never gets going.
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "finishes = 0\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.run = {\n"
            + "  g.V.filter { !it.id.startsWith('_') }.each { p -> a.add(p, [name: 'End', result: { finishStep.start() }]) }\n"
            + "  println \"round ${roundd}\"\n"
            + "}\n"
            + "finishStep = stepFactory.createNoUserActionStep()\n"
            + "finishStep.run = { finishes++ }\n"
            + "finishStep.done = { finishStep.finish() }\n"
            + "// a computer player that picks the first choice at once\n"
            + "picksAtOnce = { p -> a.ai.pickLater(p, 50) { a.ai.chooseFor(p, p.choices[0]) } }");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // Two places, no countdown; the computer takes over a seat a moment after its person leaves.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("maxPlayers", 2);
        ((ObjectNode) s.get("waitingRoom")).put("countdownSeconds", 0);
        ((ObjectNode) s.get("replaceDisconnected")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(2).equals(engineValue("autopilot.room?.maxPlayers")));
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 0.2, behavior: picksAtOnce]); null");
        final ExperimentInstance session = sessions(experiment).get(0);

        arrives("noai-1");
        arrives("noai-2");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals("their choices are still on screen", 2, ((Number) engineValue("a.actions.size()")).intValue());

        // One leaves (the takeover is due), and a script makes the other a computer player: neither
        // picks, so the stopped game isn't played on to its finish.
        engine().eval("def v = g.getVertex('noai-1'); v._system.connected = false; autopilot.onConnection('noai-1', false); null");
        engine().eval("a.ai.add(g.getVertex('noai-2'), picksAtOnce); null");
        Thread.sleep(1500);
        assertEquals(Integer.valueOf(1), engineValue("g.getVertex('noai-1').ai"));
        assertEquals(0, engineValue("finishes"));
        assertEquals(2, ((Number) engineValue("a.actions.size()")).intValue());
        assertEquals("still stopped, so it can be resumed", ExperimentInstance.Status.STOPPED,
            ExperimentInstance.findById(session.id).status);
    }

    @Test
    public void aStepOfAnEngineSinceReplacedDoesNotFinishTheSessionRunningNow() throws Exception {
        final User researcher = user("latefinish");
        final Experiment experiment = createExperiment("Finishes after the rebuild");
        createStep(experiment, "FinishStep", "finishStep = stepFactory.createNoUserActionStep()\n"
            + "finishStep.run = { }\n"
            + "finishStep.done = { finishStep.finish() }");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "first", "parameters", Json.newObject());
        await("the first session", () -> named(experiment, "first") != null && running(experiment) == 1);
        await("its steps", () -> engineValue("finishStep") != null);
        final ScriptEngine first = engine();
        final groovy.lang.GroovyObject oldStep = (groovy.lang.GroovyObject) first.get("finishStep");

        // The same account launches the next session: a new engine, with the same game listener.
        tab.send("LaunchGame", "name", "second", "parameters", Json.newObject());
        await("the second session", () -> named(experiment, "second") != null
            && named(experiment, "second").status == ExperimentInstance.Status.RUNNING && engine() != first
            && engineValue("finishStep") != null);
        final ExperimentInstance second = named(experiment, "second");

        // The first engine's step finishes, its code running on after the rebuild: not the second's game.
        oldStep.invokeMethod("finish", null);
        Thread.sleep(500);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(second.id).status);
        assertTrue(events(second, "Finished").isEmpty());

        // Its own steps still finish it.
        ((groovy.lang.GroovyObject) engine().get("finishStep")).invokeMethod("finish", null);
        await("finished", () -> ExperimentInstance.findById(second.id).status == ExperimentInstance.Status.FINISHED);
        assertEquals(1, events(second, "Finished").size());
    }

    @Test
    public void aMistakeInTheOnLeaveStepAsTheWaitingRoomStartsTheGameIsShownAndTheGameGoesOn() throws Exception {
        final User researcher = user("leaver");
        final Experiment experiment = createExperiment("Typo in OnLeaveStep");
        createStep(experiment, "OnLeaveStep", "onLeaveStep = stepFactory.createNoUserActionStep()\n"
            + "onLeaveStep.run = { playerId -> println \"left: ${playerId} ${leaverss}\" }\n"
            + "onLeaveStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 60\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = { }");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        // Two places, and a two-second countdown.
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("maxPlayers", 2);
        ((ObjectNode) s.get("waitingRoom")).put("countdownSeconds", 2);
        ((ObjectNode) s.get("replaceDisconnected")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(2).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);
        final AdminSocketsTest.Tab tab = watching(researcher);

        // One of the two leaves during the countdown: Breadboard's waiting room takes them out of the
        // network as it starts the game, which runs the experiment's OnLeaveStep for them.
        arrives("leaver-1");
        arrives("leaver-2");
        engine().eval("def v = g.getVertex('leaver-2'); v._system.connected = false; autopilot.onConnection('leaver-2', false); null");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("onLeaveStep", value(failed, "step"));
        assertTrue(value(failed, "error"), value(failed, "error").startsWith("line 2: No such property: leaverss"));
        await("the consoles are told", () -> tab.count(f -> from("OnLeaveStep").matches(f) && f.has("error")
            && f.get("error").asText().contains("leaverss")) == 1);
        Thread.sleep(500);

        // The game it started goes on.
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals("the game goes on", ExperimentInstance.Status.RUNNING, now.status);
        assertEquals(Boolean.TRUE, now.hasStarted);
        assertEquals(1, ((Number) engineValue("g.V.count()")).intValue());
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }

    @Test
    public void aSessionLaunchedByHandIsItsOwnOnceResumeIsPressedWhileItIsOpen() throws Throwable {
        final User researcher = user("handover");
        final Experiment experiment = createExperiment("Handed over on Resume");
        stepsWithATypoInFinishStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the autopilot's session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);

        // A researcher takes over with a session launched by hand (the autopilot pauses), then presses
        // Resume and leaves: the autopilot opens no other session, its link leads to that one...
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session launched by hand", () -> named(experiment, "by hand") != null
            && named(experiment, "by hand").status == ExperimentInstance.Status.RUNNING && running(experiment) == 1);
        final ExperimentInstance byHand = named(experiment, "by hand");
        assertTrue(state(researcher).get("status").get("paused").asBoolean());
        assertEquals(OK, status(resume(researcher)));
        tab.close();
        Thread.sleep(500);
        assertEquals("no other session opened", 2, sessions(experiment).size());
        assertEquals(byHand.id, Autopilot.openInstance(experiment.id).id);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));

        // ...and a step that fails during the game, with nobody watching, ends it as its own session.
        arrives("handover-1");
        assertEquals("the game started", Boolean.TRUE, ExperimentInstance.findById(byHand.id).hasStarted);
        await("the session to be stopped", () -> ExperimentInstance.findById(byHand.id).status == ExperimentInstance.Status.STOPPED);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"finishStep\" failed during its session \"by hand\""));
        assertTrue(problem, problem.contains("line 3: No such property: roundz"));
        assertEquals(1, events(byHand, "stepFailed").size());
        assertNull("the link leads nowhere now", Autopilot.openInstance(experiment.id));
        watchdog();
        Thread.sleep(500);
        assertEquals("nothing opens until Resume", 2, sessions(experiment).size());
    }

    @Test
    public void aSessionRunByHandIsItsOwnOnceItsSettingsAreSavedWhileItIsOpen() throws Throwable {
        final User researcher = user("savedover");
        final Experiment experiment = createExperiment("Handed over on a save");
        stepsWithATypoInFinishStep(experiment);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session launched by hand", () -> running(experiment) == 1);
        final ExperimentInstance byHand = sessions(experiment).get(0);

        // The researcher turns the autopilot on for that experiment and leaves it to it: it opens no
        // other session, and keeps that one open as its own.
        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        tab.close();
        await("the console's waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        Thread.sleep(500);
        assertEquals(1, sessions(experiment).size());
        assertEquals(byHand.id, Autopilot.openInstance(experiment.id).id);

        arrives("savedover-1");
        await("the session to be stopped", () -> ExperimentInstance.findById(byHand.id).status == ExperimentInstance.Status.STOPPED);
        JsonNode status = state(researcher).get("status");
        assertTrue(status.toString(), status.get("paused").asBoolean());
        String problem = status.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"finishStep\" failed during its session \"by hand\""));
        assertEquals(1, events(byHand, "stepFailed").size());
    }

    private static Object field(Class<?> of, String name) throws Exception {
        Field f = of.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    public void aLateFailureInTheEngineTheNextSessionReplacedLeavesThatSessionAlone() throws Exception {
        final User researcher = user("late");
        final Experiment experiment = createExperiment("Fails after it finished");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = { finishStep.start() }");
        // Code that goes on after finish() (here it waits for the test, standing for slow code) and
        // throws once the next session runs, in a new engine built by the same account's actor.
        createStep(experiment, "FinishStep", "finishStep = stepFactory.createNoUserActionStep()\n"
            + "finishStep.run = { }\n"
            + "finishStep.done = {\n"
            + "  finishStep.finish()\n"
            + "  goOn.await(20, java.util.concurrent.TimeUnit.SECONDS)\n"
            + "  thrown.countDown()\n"
            + "  throw new IllegalStateException('late, in the old engine')\n"
            + "}");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the first session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance first = sessions(experiment).get(0);
        final CountDownLatch goOn = new CountDownLatch(1);
        final CountDownLatch thrown = new CountDownLatch(1);
        engine().put("goOn", goOn);
        engine().put("thrown", thrown);
        try {
            arrives("late-1");
            await("the next session", () -> sessions(experiment).size() == 2 && running(experiment) == 1
                && ExperimentInstance.findById(first.id).status == ExperimentInstance.Status.FINISHED);
        } finally {
            goOn.countDown();
        }
        ExperimentInstance second = null;
        for (ExperimentInstance i : sessions(experiment)) {
            if (!i.id.equals(first.id)) second = i;
        }
        assertTrue("it threw", thrown.await(10, TimeUnit.SECONDS));
        Thread.sleep(1000);

        assertEquals("the next session goes on", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(second.id).status);
        assertTrue(events(second, "stepFailed").isEmpty());
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
    }

    @Test
    public void anOnJoinStepQueuedForAnEngineSinceReplacedIsNotCountedAgainstTheGameRunningNow() throws Exception {
        final User researcher = user("stalejoin");
        final Experiment experiment = createExperiment("Joined before the rebuild");
        // The OnJoinStep starts the game as soon as someone is in; InitStep's done has a typo: the start
        // is taken back, and the game stalls.
        createStep(experiment, "OnJoinStep", "joined = []\n"
            + "onJoinStep = stepFactory.createNoUserActionStep()\n"
            + "onJoinStep.run = { playerId -> joined << playerId; initStep.start() }\n"
            + "onJoinStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = {\n"
            + "  roundStepp.start()\n"
            + "}");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        ObjectNode s = settings(experiment, 2);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);

        // A join of a player of the engine before this one, which the actor gets to only now: it runs
        // here, but what fails in it isn't this game's.
        long built = (Long) field(ScriptBoard.class, "engineGeneration");
        akka.actor.ActorRef owner = (akka.actor.ActorRef) field(ScriptBoard.class, "engineOwner");
        com.tinkerpop.blueprints.Vertex before = new com.tinkerpop.blueprints.impls.tg.TinkerGraph().addVertex("stalejoin-0");
        owner.tell(new Breadboard.RunOnJoinStep(null, before, null, built - 1), null);
        await("it ran", () -> Integer.valueOf(1).equals(engineValue("joined.size()")));
        Thread.sleep(500);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertTrue(events(session, "stepFailed").isEmpty());
        assertFalse(state(researcher).get("status").get("paused").asBoolean());

        // A player of this engine: its game stalls, so the session ends.
        engine().eval("g.addPlayer('stalejoin-1'); null");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals(1, events(session, "stepFailed").size());
        assertTrue(state(researcher).get("status").get("paused").asBoolean());
    }

    @Test
    public void aStartThatFailsInTheEngineItsActorHasSinceReplacedIsOnlyLogged() throws Exception {
        final User researcher = user("replacedstart");
        final Experiment experiment = createExperiment("Start outlived its engine");
        // InitStep's run waits for the test (standing for slow code), then throws.
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = {\n"
            + "  startedRun.countDown()\n"
            + "  holdStart.await(20, java.util.concurrent.TimeUnit.SECONDS)\n"
            + "  throw new IllegalStateException('in the engine since replaced')\n"
            + "}\n"
            + "initStep.done = { }");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        assertEquals(OK, status(post(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its waiting room", () -> Integer.valueOf(1).equals(engineValue("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);
        final CountDownLatch startedRun = new CountDownLatch(1);
        final CountDownLatch holdStart = new CountDownLatch(1);
        engine().put("startedRun", startedRun);
        engine().put("holdStart", holdStart);
        final AdminSocketsTest.Tab tab = watching(researcher);

        // Someone arrives, and the waiting room starts the game (on its own thread here)...
        Thread starting = new Thread(() -> {
            try {
                arrives("replacedstart-1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            starting.start();
            assertTrue(startedRun.await(10, TimeUnit.SECONDS));
            // ...while the account the autopilot runs as reloads the engine: its session carries on in
            // the new one, with the same game listener.
            final int reloaded = tab.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
            tab.send("ReloadEngine");
            await("rebuilt", () -> tab.count(AdminSocketsTest.notify("ScriptEngineReloaded")) > reloaded);
        } finally {
            holdStart.countDown();
        }
        starting.join(10000);
        Thread.sleep(1000);

        assertEquals("its session goes on", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertTrue(events(session, "stepFailed").isEmpty());
        assertFalse(state(researcher).get("status").get("paused").asBoolean());
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

    /** "<id> <status>" of the session a User JSON shows, or "(none)". */
    private static String shown(JsonNode userJson) {
        JsonNode i = userJson == null ? null : userJson.get("experimentInstance");
        return i == null || !i.isObject() ? "(none)" : i.get("id").asLong() + " " + i.get("status").asText();
    }

    private static JsonNode lastUserJson(AdminSocketsTest.Tab t) {
        JsonNode last = null;
        for (JsonNode f : t.frames) if (f.has("user")) last = f;
        return last;
    }

    private static ExperimentInstance theRunning(Experiment experiment) {
        for (ExperimentInstance i : sessions(experiment)) {
            if (i.status == ExperimentInstance.Status.RUNNING) return i;
        }
        return null;
    }

    @Test
    public void aResearcherWorkingOnItsExperimentFollowsEverySessionItOpens() throws Exception {
        final User operator = user("operator");
        final User pi = user("pi");
        final Experiment experiment = createExperiment("Followed study");
        operator.ownedExperiments.add(experiment);
        operator.saveManyToManyAssociations("ownedExperiments");
        // The PI, not the account the autopilot runs as, works on its experiment in two tabs, opened before
        // any session is.
        pi.setSelectedExperiment(Experiment.findById(experiment.id));
        pi.update();
        final AdminSocketsTest.Tab first = watching(pi);
        final AdminSocketsTest.Tab second = watching(pi);
        assertEquals("(none)", shown(lastUserJson(first)));

        ObjectNode settings = settings(experiment, 2);
        settings.put("nextSessionDelaySeconds", 1);
        assertEquals(OK, status(post(operator, settings)));
        await("the first session", () -> theRunning(experiment) != null);
        final ExperimentInstance s1 = theRunning(experiment);
        // Both tabs show it as it goes live, without asking.
        for (final AdminSocketsTest.Tab t : new AdminSocketsTest.Tab[]{first, second}) {
            await("a tab shows the first session", () -> (s1.id + " RUNNING").equals(shown(lastUserJson(t))));
        }
        assertEquals(s1.id, User.findByEmail(pi.email).experimentInstanceId);

        // It finishes by itself: the tabs show it finished, then the next session the autopilot opens.
        engine().eval("gameListener.finish(); null");
        for (final AdminSocketsTest.Tab t : new AdminSocketsTest.Tab[]{first, second}) {
            await("a tab shows it finished", () -> t.count(f -> f.has("user") && (s1.id + " FINISHED").equals(shown(f))) >= 1);
        }
        await("the next session", () -> sessions(experiment).size() == 2 && theRunning(experiment) != null);
        final ExperimentInstance s2 = theRunning(experiment);
        for (final AdminSocketsTest.Tab t : new AdminSocketsTest.Tab[]{first, second}) {
            await("a tab shows the next session", () -> (s2.id + " RUNNING").equals(shown(lastUserJson(t))));
        }
        assertEquals(s2.id, User.findByEmail(pi.email).experimentInstanceId);
        // What the console asks for when the engine news names it (askUserJsonSoon) says the same.
        final int before = first.count(AdminSocketsTest.userJson());
        first.send("Update");
        await("the User JSON asked for", () -> first.count(AdminSocketsTest.userJson()) > before);
        assertEquals(s2.id + " RUNNING", shown(lastUserJson(first)));
        assertEquals("the two tabs agree", shown(lastUserJson(first)), shown(lastUserJson(second)));
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
