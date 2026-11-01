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
 * An engine error names each of its lines by the step whose code it is (StepScripts: the server
 * records the class each step's code compiles to), and the failing step's own line is the innermost
 * line in that step's code, also when another step's code it called went wrong (the network game's
 * RoundStep and FinishStep call InitStep's isHuman), when another step started it (RoundStep starts
 * FinishStep), when the waiting room started it, and when it failed as it loaded. So say the error
 * the consoles get ("frames"), the data (stepFailed, sessionFailed) and the autopilot's pause.
 */
public class StepErrorLinesTest extends BaseTest {

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
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession", "openedSession"}) {
            Field f2 = Autopilot.class.getDeclaredField(name);
            f2.setAccessible(true);
            f2.set(null, null);
        }
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);
        due.set(null, 0L);
    }

    private static ScriptEngine engine() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (ScriptEngine) f.get(null);
    }

    private static Long engineExperimentId() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engineExperimentId");
        f.setAccessible(true);
        return (Long) f.get(null);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@example.org", prefix, "secret-1", lang);
    }

    private Experiment experiment(User researcher, String name, String... steps) {
        Experiment experiment = createExperiment(name);
        for (int i = 0; i < steps.length; i += 2) createStep(experiment, steps[i], steps[i + 1]);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        return experiment;
    }

    /** A console tab of the user's, logged in, with nothing selected yet. */
    private AdminSocketsTest.Tab tab(User user) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        return tab;
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
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

    /** A session of the experiment launched by hand from the tab, running. */
    private ExperimentInstance launchByHand(final AdminSocketsTest.Tab tab, final User user, final Experiment experiment) throws Exception {
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the selection", () -> experiment.id.equals(engineExperimentId()));
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> sessions(experiment).size() == 1 && sessions(experiment).get(0).status == ExperimentInstance.Status.RUNNING);
        return sessions(experiment).get(0);
    }

    /** The first error frame a tab got whose text says {@code text}. */
    private static JsonNode errorSaying(AdminSocketsTest.Tab tab, String text) {
        return tab.first(f -> f.has("error") && f.get("error").asText().contains(text));
    }

    /** Its lines as "InitStep line 6 isHuman", "RoundStep line 5 own", "line 1" (a script that is no step's). */
    private static List<String> frames(JsonNode error) {
        List<String> lines = new ArrayList<>();
        assertTrue(error.toString(), error.has("frames"));
        for (JsonNode f : error.get("frames")) {
            assertTrue(f.toString(), f.get("file").asText().matches("Script\\d+\\.groovy"));
            String line = (f.has("step") ? f.get("step").asText() + " " : "") + "line " + f.get("line").asInt();
            if (f.path("own").asBoolean()) line += " own";
            if (f.has("name")) line += " " + f.get("name").asText();
            lines.add(line);
        }
        return lines;
    }

    // InitStep starts RoundStep, whose time limit ends it on its timer; isHuman is InitStep's, at its line 6.
    private static final String INIT_STEP = "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = { }\n"
        + "initStep.done = { roundStep.start() }\n"
        + "// people's ids don't start with _\n"
        + "isHuman = { v ->\n"
        + "  throw new IllegalStateException(\"isHuman fails for \" + v)\n"
        + "}";

    private static String roundStep(String done) {
        return "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = {\n"
            + done + "\n"
            + "}";
    }

    @Test
    public void aStepThatFailsInItsOwnCodeFailsAtItsOwnLine() throws Exception {
        User researcher = user("own");
        Experiment experiment = experiment(researcher, "Fails in its own code",
            "InitStep", INIT_STEP, "RoundStep", roundStep("  println \"round over: ${scroe}\""));
        final AdminSocketsTest.Tab tab = tab(researcher);
        final ExperimentInstance session = launchByHand(tab, researcher, experiment);

        tab.send("RunGame");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("RoundStep", value(failed, "step"));
        assertEquals("line 5: No such property: scroe", value(failed, "error"));
        await("the error on the console", () -> errorSaying(tab, "scroe") != null);
        JsonNode error = errorSaying(tab, "scroe");
        assertEquals("RoundStep", error.get("step").asText());
        assertEquals(java.util.Arrays.asList("RoundStep line 5 own"), frames(error));
    }

    @Test
    public void aStepThatCallsAnotherStepsFunctionThatFailsFailsAtItsLineThatCalledIt() throws Exception {
        User researcher = user("helper");
        Experiment experiment = experiment(researcher, "Fails in a helper",
            "InitStep", INIT_STEP, "RoundStep", roundStep("  if (isHuman(\"p1\")) println \"p1 is a person\""));
        final AdminSocketsTest.Tab tab = tab(researcher);
        final ExperimentInstance session = launchByHand(tab, researcher, experiment);

        tab.send("RunGame");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("RoundStep", value(failed, "step"));
        // RoundStep's line 5, in InitStep's isHuman, which threw at InitStep's line 6 (it used to say
        // line 6, InitStep's, under RoundStep's name)
        assertEquals("line 5, in isHuman (InitStep line 6): isHuman fails for p1", value(failed, "error"));
        await("the error on the console", () -> errorSaying(tab, "isHuman fails") != null);
        JsonNode error = errorSaying(tab, "isHuman fails");
        assertEquals("RoundStep", error.get("step").asText());
        assertEquals(java.util.Arrays.asList("InitStep line 6 isHuman", "RoundStep line 5 own"), frames(error));
        // (the text is as it was, with Groovy's names, for older consoles and the full error)
        assertTrue(error.get("error").asText(), error.get("error").asText().matches("(?s)Caught error: .*\n  at Script\\d+\\.groovy:6\n.*"));
    }

    @Test
    public void aStepAnotherStepStartedFailsAtItsOwnLine() throws Exception {
        User researcher = user("started");
        // FinishStep has no name of its own; its saved code names it, not its variable. RoundStep starts it
        Experiment experiment = experiment(researcher, "Fails in the step started",
            "InitStep", INIT_STEP, "RoundStep", roundStep("  finishStep.start()"),
            "FinishStep", "finishStep = stepFactory.createNoUserActionStep()\n"
                + "finishStep.run = {\n"
                + "  println \"Thank you for playing ${roundz} rounds\"\n"
                + "}\n"
                + "finishStep.done = { finishStep.finish() }");
        final AdminSocketsTest.Tab tab = tab(researcher);
        final ExperimentInstance session = launchByHand(tab, researcher, experiment);

        tab.send("RunGame");
        await("the event", () -> events(session, "stepFailed").size() == 1);
        Event failed = events(session, "stepFailed").get(0);
        assertEquals("FinishStep", value(failed, "step"));
        assertEquals("line 3: No such property: roundz", value(failed, "error"));
        await("the error on the console", () -> errorSaying(tab, "roundz") != null);
        assertEquals("FinishStep", errorSaying(tab, "roundz").get("step").asText());
        // FinishStep's line 3, and RoundStep's line 5 that started it: neither is the other's
        assertEquals(java.util.Arrays.asList("FinishStep line 3 own", "RoundStep line 5"), frames(errorSaying(tab, "roundz")));
    }

    private static Result saveAutopilot(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static JsonNode autopilot(User user) {
        return Json.parse(contentAsString(route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid))));
    }

    /** Settings whose waiting room starts the game as soon as one person is here. */
    private static ObjectNode startsAtOnce(Experiment experiment) {
        ObjectNode s = Json.newObject();
        s.put("enabled", true);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", 1);
        room.put("maxPlayers", 1);
        room.put("waitSeconds", 30);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", 0);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", true);
        replace.put("afterSeconds", 15);
        replace.put("returnToHuman", true);
        return s;
    }

    @Test
    public void initStepTheWaitingRoomStartedFailsAtItsOwnLineAndTheAutopilotSaysWhere() throws Exception {
        final User researcher = user("room");
        // InitStep calls RoundStep's seat at its line 3 (in the closure of its g.V.each), which fails at
        // RoundStep's line 2, as the waiting room starts the game
        final Experiment experiment = experiment(researcher, "Fails as the room starts it",
            "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
                + "initStep.run = {\n"
                + "  g.V.each { p -> seat(p) }\n"
                + "}\n"
                + "initStep.done = { }",
            "RoundStep", "seat = { p ->\n"
                + "  p.seat = seatz.pop()\n"
                + "}");
        final AdminSocketsTest.Tab tab = tab(researcher);

        assertEquals(OK, status(saveAutopilot(researcher, startsAtOnce(experiment))));
        await("the session", () -> sessions(experiment).size() == 1 && sessions(experiment).get(0).status == ExperimentInstance.Status.RUNNING);
        await("its waiting room", () -> Integer.valueOf(1).equals(engine().eval("autopilot.room?.maxPlayers")));
        final ExperimentInstance session = sessions(experiment).get(0);
        engine().eval("def v = g.addPlayer('room-1'); v._system.connected = true; autopilot.onConnection('room-1', true); null");
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);

        String problem = autopilot(researcher).get("status").get("problem").asText();
        assertTrue(problem, problem.contains("the step \"InitStep\" failed during its session"));
        assertTrue(problem, problem.contains("(line 3, in seat (RoundStep line 2): No such property: seatz)"));
        assertEquals("line 3, in seat (RoundStep line 2): No such property: seatz", value(events(session, "stepFailed").get(0), "error"));
        Event ended = events(session, "sessionFailed").get(0);
        assertEquals("InitStep", value(ended, "step"));
        assertEquals("3", value(ended, "line"));
        assertEquals("in seat (RoundStep line 2): No such property: seatz", value(ended, "error"));
        // the consoles hear of it from the server: the waiting room's start has no console to answer
        await("the error on the console", () -> errorSaying(tab, "seatz") != null);
        JsonNode error = errorSaying(tab, "seatz");
        assertEquals("InitStep", error.get("step").asText());
        // (and the script the waiting room ran, initStep.start(), is no step's, nor is this test's, which
        // stands in for the participant's page connecting)
        assertEquals(java.util.Arrays.asList("RoundStep line 2 seat", "InitStep line 3 own", "line 1", "line 1"), frames(error));
    }

    @Test
    public void aStepThatFailsAsItLoadsOrIsRunFromTheStepsPageFailsAtItsOwnLine() throws Exception {
        User researcher = user("loads");
        // FinishStep (it loads first) has a name that isn't defined at its line 2; RoundStep calls
        // InitStep's isHuman at its line 2 as it loads
        Experiment experiment = experiment(researcher, "Fails as it loads",
            "FinishStep", "SEATS = 2\nROUNDS = five",
            "InitStep", INIT_STEP,
            "RoundStep", "FIRST = 'p1'\nFIRST_IS_HUMAN = isHuman(FIRST)");
        final AdminSocketsTest.Tab tab = tab(researcher);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the errors of the steps that didn't load", () -> errorSaying(tab, "five") != null && errorSaying(tab, "isHuman fails") != null);
        JsonNode five = errorSaying(tab, "five");
        assertEquals("Steps", five.get("source").asText());
        assertEquals("FinishStep", five.get("step").asText());
        assertEquals(java.util.Arrays.asList("FinishStep line 2 own"), frames(five));
        JsonNode helper = errorSaying(tab, "isHuman fails");
        assertEquals("RoundStep", helper.get("step").asText());
        assertEquals(java.util.Arrays.asList("InitStep line 6 isHuman", "RoundStep line 2 own"), frames(helper));

        // Run on the Steps page: the step it is the code of, by its name
        tab.send("SendStep", "id", -1, "name", "RoundStep", "source", "SECOND = 'p2'\n\nisHuman(SECOND)", "requestId", "run-1");
        await("the run's error", () -> tab.first(f -> f.has("error") && "run-1".equals(f.path("requestId").asText())) != null);
        JsonNode run = tab.first(f -> f.has("error") && "run-1".equals(f.path("requestId").asText()));
        assertEquals(java.util.Arrays.asList("InitStep line 6 isHuman", "RoundStep line 3 own"), frames(run));
        // and code that doesn't compile: the line the compiler names
        tab.send("SendStep", "id", -1, "name", "RoundStep", "source", "x = 1\ny = (2", "requestId", "run-2");
        await("the run's error", () -> tab.first(f -> f.has("error") && "run-2".equals(f.path("requestId").asText())) != null);
        assertEquals(java.util.Arrays.asList("RoundStep line 2 own"), frames(tab.first(f -> f.has("error") && "run-2".equals(f.path("requestId").asText()))));

        // A script typed in the Script console is no step's: its lines in InitStep's code are named, none is "own"
        tab.send("SendScript", "script", "isHuman('p3')", "requestId", "typed-1");
        await("its error", () -> tab.first(f -> f.has("error") && "typed-1".equals(f.path("requestId").asText())) != null);
        JsonNode typed = tab.first(f -> f.has("error") && "typed-1".equals(f.path("requestId").asText()));
        assertFalse(typed.has("step"));
        assertEquals(java.util.Arrays.asList("InitStep line 6", "line 1"), frames(typed));
    }
}
