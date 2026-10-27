import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;

import org.apache.commons.io.FileUtils;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/**
 * What a console is told about the engine's work, so it can tell its own answers from everything
 * else on the socket: RunGame, SendScript and SendStep frames name their request (and carry back the
 * console's requestId), the join and leave steps' frames their source; a researcher's choice is
 * answered "made" or "gone", with its choiceUID; a failed rebuild is told to every console; a stale
 * sign-in is answered with "status": "unauthorized"; the User JSON says whether the game started; a
 * file mode switch that fails says so.
 */
public class EngineRepliesTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
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

    /** An experiment of the user's, with the default steps, selected in the engine from tab t. */
    private Experiment selected(User user, AdminSocketsTest.Tab t, String name) throws Exception {
        Experiment experiment = createExperiment(name);
        createStep(experiment, "OnJoinStep", Experiment.generateOnJoinStep().source);
        createStep(experiment, "OnLeaveStep", Experiment.generateOnLeaveStep().source);
        createStep(experiment, "InitStep", Experiment.generateInitStep().source + "\n// marker-init");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("steps loaded", () -> t.count(AdminSocketsTest.output("marker-init")) == 1);
        return experiment;
    }

    private static AdminSocketsTest.Matcher tagged(final String key, final String value) {
        return f -> f.has(key) && value.equals(f.get(key).asText());
    }

    private static boolean failed(JsonNode f) {
        return f.has("error") && f.get("error").asText().length() > 0;
    }

    // ---- every frame names the request (or the step) it comes from ---------------------------------

    @Test
    public void runGameSendScriptAndSendStepFramesNameTheirRequest() throws Exception {
        User u = user("tags");
        final AdminSocketsTest.Tab t = loggedIn(u);
        selected(u, t, "Tagged");
        assertTrue("the steps loading on a rebuild are not an answer to anything",
            t.first(AdminSocketsTest.output("marker-init")).has("source"));
        assertFalse(t.first(AdminSocketsTest.output("marker-init")).has("request"));

        t.send("RunGame");
        await("RunGame output", () -> t.count(tagged("request", "RunGame")) == 1);
        assertFalse(failed(t.first(tagged("request", "RunGame"))));
        t.send("RunGame");
        await("started already", () -> t.count(f -> tagged("request", "RunGame").matches(f) && failed(f)
            && f.get("error").asText().contains("started already")) == 1);

        t.send("SendScript", "script", "6 * 7");
        await("SendScript output", () -> t.count(f -> tagged("request", "SendScript").matches(f) && f.has("output")
            && f.get("output").asText().endsWith("==>42")) == 1);
        t.send("SendScript", "script", "throw new IllegalStateException('nope')");
        await("SendScript error", () -> t.count(f -> tagged("request", "SendScript").matches(f) && failed(f)
            && f.get("error").asText().contains("nope")) == 1);

        t.send("SendStep", "id", -1, "name", "Extra", "source", "stepRan = 'yes'");
        await("SendStep output", () -> t.count(f -> tagged("request", "SendStep").matches(f) && f.has("output")
            && f.get("output").asText().contains("stepRan")) == 1);
        t.send("SendStep", "id", -1, "name", "Broken", "source", "this is not groovy (");
        await("SendStep error", () -> t.count(f -> tagged("request", "SendStep").matches(f) && failed(f)) == 1);
        assertEquals("yes", engine().get("stepRan"));
    }

    @Test
    public void joinAndLeaveStepFramesNameTheirStep() throws Exception {
        User u = user("join");
        final AdminSocketsTest.Tab t = loggedIn(u);
        selected(u, t, "Join and leave");

        ScriptBoard.processScript("g.addPlayer('jl1')", null, "test");
        await("OnJoinStep's frame", () -> t.count(tagged("source", "OnJoinStep")) == 1);
        ScriptBoard.processScript("g.removePlayer('jl1')", null, "test");
        await("OnLeaveStep's frame", () -> t.count(tagged("source", "OnLeaveStep")) == 1);
        assertFalse(t.first(tagged("source", "OnJoinStep")).has("request"));
    }

    // ---- a researcher's choice on a player's behalf ------------------------------------------------

    @Test
    public void aChoiceFromTheConsoleIsMadeOrGone() throws Exception {
        User u = user("choice");
        final AdminSocketsTest.Tab t = loggedIn(u);
        selected(u, t, "Choices");
        final ScriptEngine engine = engine();
        engine.eval("picked = java.util.Collections.synchronizedList([])\n"
            + "def p = g.addPlayer('ch1')\n"
            + "a.add(p, [name: 'Left', result: { picked << 'Left' }], [name: 'Right', result: { picked << 'Right' }])");
        final String uid = String.valueOf(engine.eval("g.getVertex('ch1').choices[0].uid"));

        t.send("MakeChoice", "choiceUID", uid);
        await("made", () -> t.count(f -> tagged("request", "MakeChoice").matches(f) && f.has("choice")) == 1);
        assertEquals("made", t.first(tagged("request", "MakeChoice")).get("choice").asText());
        assertEquals("the answer names the choice", uid, t.first(tagged("request", "MakeChoice")).get("choiceUID").asText());
        assertEquals("[Left]", String.valueOf(engine.eval("picked")));

        // Already answered: nothing runs a second time.
        t.send("MakeChoice", "choiceUID", uid);
        await("gone", () -> t.count(f -> tagged("choice", "gone").matches(f)
            && "MakeChoice".equals(f.get("request").asText())) == 1);
        assertEquals(uid, t.first(tagged("choice", "gone")).get("choiceUID").asText());
        t.send("MakeChoice", "choiceUID", "no-such-choice");
        await("gone again", () -> t.count(tagged("choice", "gone")) == 2);
        assertEquals(1, t.count(f -> tagged("choice", "gone").matches(f) && tagged("choiceUID", "no-such-choice").matches(f)));
        assertEquals("[Left]", String.valueOf(engine.eval("picked")));

        // A choice whose result fails: an error naming the request.
        engine.eval("a.add(g.getVertex('ch1'), [name: 'Boom', result: { throw new IllegalStateException('boom') }])");
        final String boom = String.valueOf(engine.eval("g.getVertex('ch1').choices[0].uid"));
        t.send("MakeChoice", "choiceUID", boom);
        await("error", () -> t.count(AdminSocketsTest.error("MakeChoice")) == 1);
        assertTrue(t.first(AdminSocketsTest.error("MakeChoice")).get("error").asText().contains("boom"));
        assertFalse(t.first(AdminSocketsTest.error("MakeChoice")).has("choice"));
        assertEquals(boom, t.first(AdminSocketsTest.error("MakeChoice")).get("choiceUID").asText());
    }

    // ---- requestId: a console's own id for a script or step, carried back on every answer ------------

    private static AdminSocketsTest.Matcher answering(final String requestId) {
        return tagged("requestId", requestId);
    }

    @Test
    public void framesAnsweringSendScriptAndSendStepCarryTheirRequestId() throws Exception {
        User u = user("ids");
        final AdminSocketsTest.Tab t = loggedIn(u);
        selected(u, t, "With ids");

        t.send("SendScript", "script", "6 * 7", "requestId", "r-1");
        await("output", () -> t.count(answering("r-1")) == 1);
        JsonNode output = t.first(answering("r-1"));
        assertEquals("SendScript", output.get("request").asText());
        assertTrue(output.get("output").asText().endsWith("==>42"));

        t.send("SendScript", "script", "throw new IllegalStateException('nope')", "requestId", "r-2");
        await("error", () -> t.count(f -> answering("r-2").matches(f) && failed(f)) == 1);
        assertEquals("SendScript", t.first(answering("r-2")).get("request").asText());

        t.send("SendStep", "id", -1, "name", "Extra", "source", "stepRan = 'yes'", "requestId", "r-3");
        await("step output", () -> t.count(f -> answering("r-3").matches(f) && f.has("output")) == 1);
        assertEquals("SendStep", t.first(answering("r-3")).get("request").asText());
        t.send("SendStep", "id", -1, "name", "Broken", "source", "this is not groovy (", "requestId", "r-4");
        await("step error", () -> t.count(f -> answering("r-4").matches(f) && failed(f)) == 1);

        // A start twice: "started already" answers too.
        t.send("SendScript", "script", "initStep.start()", "requestId", "r-5");
        await("started", () -> t.count(answering("r-5")) == 1);
        t.send("SendScript", "script", "initStep.start()", "requestId", "r-6");
        await("started already", () -> t.count(f -> answering("r-6").matches(f)
            && f.get("error").asText().contains("started already")) == 1);

        // Refused before anything runs: the error carries it as well.
        t.send("SendScript", "requestId", "r-7");
        await("no script", () -> t.count(f -> answering("r-7").matches(f) && AdminSocketsTest.error("SendScript").matches(f)) == 1);
        final AdminSocketsTest.Tab stranger = new AdminSocketsTest.Tab("not-a-sign-in-" + UUID.randomUUID());
        tabs.add(stranger);
        stranger.send("SendStep", "source", "x = 1", "requestId", "r-8");
        await("not signed in", () -> stranger.count(f -> answering("r-8").matches(f) && AdminSocketsTest.error("SendStep").matches(f)) == 1);

        // Without one, answers are as before; each id answers its own request only.
        t.send("SendScript", "script", "1 + 1");
        await("plain output", () -> t.count(AdminSocketsTest.output("1 + 1")) == 1);
        assertFalse(t.first(AdminSocketsTest.output("1 + 1")).has("requestId"));
        for (String id : new String[]{"r-1", "r-2", "r-3", "r-4", "r-5", "r-6", "r-7"}) {
            assertEquals(id, 1, t.count(answering(id)));
        }
    }

    @Test
    public void aRequestIdMustBeShortText() throws Exception {
        User u = user("longids");
        final AdminSocketsTest.Tab t = loggedIn(u);
        selected(u, t, "Long ids");
        final String longest = new String(new char[64]).replace('\0', 'x');

        t.send("SendScript", "script", "tooLong = 1", "requestId", longest + "x");
        t.send("SendScript", "script", "notText = 1", "requestId", 7);
        await("both refused", () -> t.count(f -> AdminSocketsTest.error("SendScript").matches(f)
            && f.get("error").asText().contains("requestId")) == 2);
        t.send("SendScript", "script", "justRight = 1", "requestId", longest);
        await("64 characters are fine", () -> t.count(f -> answering(longest).matches(f) && f.has("output")) == 1);
        assertNull(engine().get("tooLong"));
        assertNull(engine().get("notText"));
        assertEquals(1, engine().get("justRight"));
    }

    // ---- whether the game has started -------------------------------------------------------------

    private static boolean gameStarted(User user) {
        return User.findByEmail(user.email).toJson().get("engine").get("gameStarted").asBoolean();
    }

    @Test
    public void theEngineSaysWhetherTheGameStartedAndAStartThatFailsKeepsTheSessionOpen() throws Exception {
        User u = user("starter");
        final AdminSocketsTest.Tab t = loggedIn(u);
        final Experiment experiment = createExperiment("Starts badly");
        createStep(experiment, "OnJoinStep", Experiment.generateOnJoinStep().source);
        createStep(experiment, "OnLeaveStep", Experiment.generateOnLeaveStep().source);
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { throw new IllegalStateException('not yet') }\n"
            + "initStep.done = { }\n// marker-bad-init");
        u.ownedExperiments.add(experiment);
        u.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("steps loaded", () -> t.count(AdminSocketsTest.output("marker-bad-init")) == 1);
        assertFalse(gameStarted(u));

        t.send("LaunchGame", "name", "S1", "parameters", Json.newObject());
        final Callable<ExperimentInstance> session = () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
        await("running", () -> session.call() != null && session.call().status == ExperimentInstance.Status.RUNNING);
        assertEquals(Boolean.FALSE, session.call().hasStarted);

        t.send("RunGame");
        await("the start failed", () -> t.count(f -> tagged("request", "RunGame").matches(f) && failed(f)
            && f.get("error").asText().contains("not yet")) == 1);
        assertFalse(gameStarted(u));
        assertEquals("newcomers are let in again", Boolean.FALSE, session.call().hasStarted);

        t.send("SendScript", "script", "initStep.run = { }");
        await("fixed", () -> t.count(f -> tagged("request", "SendScript").matches(f) && f.has("output")) == 1);
        t.send("RunGame");
        await("started", () -> t.count(f -> tagged("request", "RunGame").matches(f) && !failed(f)) == 1);
        assertTrue(gameStarted(u));
        assertEquals(Boolean.TRUE, session.call().hasStarted);

        // A rebuild: nothing has started in the new engine.
        final int reloaded = t.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
        t.send("ReloadEngine");
        await("rebuilt", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloaded")) > reloaded);
        assertFalse(gameStarted(u));
    }

    @Test
    public void aGameStartedByTheExperimentsOwnCodeCountsAndCodeThatOnlyMentionsAStartDoesNot() throws Exception {
        User u = user("selfstarter");
        final AdminSocketsTest.Tab t = loggedIn(u);
        final Experiment experiment = createExperiment("Starts itself");
        createStep(experiment, "OnJoinStep", Experiment.generateOnJoinStep().source);
        createStep(experiment, "OnLeaveStep", Experiment.generateOnLeaveStep().source);
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initRuns = 0\n"
            + "initStep.run = { initRuns = initRuns + 1 }\n"
            + "initStep.done = { }\n// marker-self-init");
        // The usual waiting-room pattern: the step's code mentions initStep.start() but only defines it.
        final String autoStart = "startWhenReady = { if (ready) { initStep.start() } }\nready = false\n// marker-auto";
        createStep(experiment, "AutoStart", autoStart);
        u.ownedExperiments.add(experiment);
        u.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("steps loaded", () -> t.count(AdminSocketsTest.output("marker-auto")) == 1);
        assertFalse("loading the steps starts nothing", gameStarted(u));
        assertNull(engine().get("initStep.start()"));

        t.send("LaunchGame", "name", "S1", "parameters", Json.newObject());
        final Callable<ExperimentInstance> session = () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
        await("running", () -> session.call() != null && session.call().status == ExperimentInstance.Status.RUNNING);
        assertEquals("open to newcomers after the launch", Boolean.FALSE, session.call().hasStarted);

        // Running the step again from the Steps page doesn't start the game either.
        t.send("SendStep", "id", -1, "name", "AutoStart", "source", autoStart, "requestId", "again");
        await("step ran", () -> t.count(f -> answering("again").matches(f) && f.has("output")) == 1);
        assertFalse(gameStarted(u));
        assertEquals(Boolean.FALSE, session.call().hasStarted);

        // Text that only mentions the start command starts nothing (as a participant's id
        // "initStep.start()" in the join script "onJoinStep.start('initStep.start()')").
        t.send("SendScript", "script", "note = 'initStep.start()'", "requestId", "mention");
        await("mentioned", () -> t.count(f -> answering("mention").matches(f) && f.has("output")) == 1);
        ScriptBoard.processScript("onJoinNote = 'initStep.start()'", null, null);
        assertEquals("initStep.start()", engine().get("onJoinNote"));
        assertNull(engine().get("initStep.start()"));
        assertFalse(gameStarted(u));
        assertEquals(Boolean.FALSE, session.call().hasStarted);

        // The experiment's code starts it: counted as a start like the console's Start.
        t.send("SendScript", "script", "ready = true; startWhenReady()", "requestId", "go");
        await("started by code", () -> t.count(f -> answering("go").matches(f) && f.has("output")) == 1);
        assertEquals(1, engine().get("initRuns"));
        await("consoles are told, to ask what the engine runs", () -> t.count(AdminSocketsTest.notify("GameStarted")) == 1);
        assertTrue(gameStarted(u));
        assertEquals("closed to newcomers", Boolean.TRUE, session.call().hasStarted);
        t.send("RunGame");
        await("started already", () -> t.count(f -> tagged("request", "RunGame").matches(f)
            && failed(f) && f.get("error").asText().contains("started already")) == 1);
        assertEquals("not run twice", 1, engine().get("initRuns"));
        // Nor does a researcher's script that starts it again, however it calls initStep.start().
        t.send("SendScript", "script", "startWhenReady()", "requestId", "twice");
        await("refused", () -> t.count(f -> answering("twice").matches(f) && failed(f)
            && f.get("error").asText().contains("started already")) == 1);
        assertEquals("still not run twice", 1, engine().get("initRuns"));
        assertTrue(gameStarted(u));

        // A reload forgets the game while the session carries on: people may come in again.
        final int reloaded = t.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
        t.send("ReloadEngine");
        await("rebuilt", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloaded")) > reloaded);
        await("reopened by the reload", () -> Boolean.FALSE.equals(session.call().hasStarted));
        assertEquals(ExperimentInstance.Status.RUNNING, session.call().status);
        assertFalse(gameStarted(u));

        // So does opening the session again (Resume / Make current).
        t.send("SendScript", "script", "ready = true; startWhenReady()", "requestId", "go-again");
        await("started again", () -> t.count(f -> answering("go-again").matches(f) && f.has("output")) == 1);
        assertEquals(Boolean.TRUE, session.call().hasStarted);
        final int reloadedAgain = t.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
        t.send("SelectInstance", "id", session.call().id);
        await("rebuilt again", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloaded")) > reloadedAgain);
        await("reopened by the resume", () -> Boolean.FALSE.equals(session.call().hasStarted));
        assertEquals(ExperimentInstance.Status.RUNNING, session.call().status);
        assertFalse(gameStarted(u));
    }

    @Test
    public void aStartFromTheExperimentsOwnCodeThatThrowsIsTakenBack() throws Exception {
        User u = user("failingself");
        final AdminSocketsTest.Tab t = loggedIn(u);
        final Experiment experiment = createExperiment("Fails to start itself");
        createStep(experiment, "OnJoinStep", Experiment.generateOnJoinStep().source);
        createStep(experiment, "OnLeaveStep", Experiment.generateOnLeaveStep().source);
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "failing = true\n"
            + "initStep.run = { if (failing) { throw new IllegalStateException('not ready') } }\n"
            + "initStep.done = { }\n// marker-fail-init");
        createStep(experiment, "AutoStart", "startNow = { initStep.start() }\n// marker-auto-fail");
        u.ownedExperiments.add(experiment);
        u.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("steps loaded", () -> t.count(AdminSocketsTest.output("marker-auto-fail")) == 1);
        t.send("LaunchGame", "name", "S1", "parameters", Json.newObject());
        final Callable<ExperimentInstance> session = () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
        await("running", () -> session.call() != null && session.call().status == ExperimentInstance.Status.RUNNING);

        t.send("SendScript", "script", "startNow()", "requestId", "first");
        await("the start threw", () -> t.count(f -> answering("first").matches(f) && failed(f)
            && f.get("error").asText().contains("not ready")) == 1);
        assertNull("not marked as started", engine().get("initStep.start()"));
        await("consoles are told it was taken back", () -> t.count(AdminSocketsTest.notify("GameStartFailed")) == 1);
        assertFalse(gameStarted(u));
        assertEquals("newcomers are let in again", Boolean.FALSE, session.call().hasStarted);

        // So Start can try again.
        t.send("SendScript", "script", "failing = false");
        await("fixed", () -> t.count(f -> tagged("request", "SendScript").matches(f) && f.has("output")
            && f.get("output").asText().startsWith("failing = false")) == 1);
        t.send("RunGame");
        await("started", () -> t.count(f -> tagged("request", "RunGame").matches(f) && !failed(f)) == 1);
        assertTrue(gameStarted(u));
        assertEquals(Boolean.TRUE, session.call().hasStarted);
    }

    // ---- file mode -----------------------------------------------------------------------------------

    @Test
    public void aFileModeSwitchThatFailsIsReported() throws Exception {
        User u = user("files");
        Experiment experiment = createExperiment("Unwritable");
        u.ownedExperiments.add(experiment);
        u.saveManyToManyAssociations("ownedExperiments");
        u.setSelectedExperiment(Experiment.findById(experiment.id));
        u.update();
        File dev = new File(play.Play.application().path(), "dev");
        boolean devExisted = dev.exists();
        // A file where the experiment's folder would go: it can't be written there.
        File blocker = new File(dev, experiment.getDirectoryName());
        try {
            FileUtils.writeStringToFile(blocker, "not a folder");
            final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(u.uid);
            tabs.add(t);
            t.send("ToggleFileMode");
            await("refused", () -> t.count(AdminSocketsTest.error("ToggleFileMode")) == 1);
            String text = t.first(AdminSocketsTest.error("ToggleFileMode")).get("error").asText();
            assertTrue(text, text.startsWith("Couldn't switch file mode: ") && text.length() > "Couldn't switch file mode: ".length());
            assertFalse(Boolean.TRUE.equals(Experiment.findById(experiment.id).fileMode));
            assertEquals("not answered as if it had worked", 0, t.count(AdminSocketsTest.userJson()));
        } finally {
            FileUtils.deleteQuietly(devExisted ? blocker : dev);
        }
    }

    // ---- a rebuild that fails is told to every console ---------------------------------------------

    @Test
    public void aFailedReloadIsToldToEveryConsole() throws Exception {
        User u = user("reload");
        User other = user("watcher");
        final AdminSocketsTest.Tab t = loggedIn(u);
        final AdminSocketsTest.Tab watcher = loggedIn(other);
        selected(u, t, "Reloaded");

        Field managerField = ScriptBoard.class.getDeclaredField("manager");
        managerField.setAccessible(true);
        Object manager = managerField.get(null);
        managerField.set(null, new ScriptEngineManager() {
            @Override
            public ScriptEngine getEngineByName(String name) {
                throw new IllegalStateException("no engine today");
            }
        });
        try {
            t.send("ReloadEngine");
            await("told to the console that asked", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloadFailed")) == 1);
            await("and to another account's console", () -> watcher.count(AdminSocketsTest.notify("ScriptEngineReloadFailed")) == 1);
            String reason = watcher.first(AdminSocketsTest.notify("ScriptEngineReloadFailed")).get("notify")
                .get("ScriptEngineReloadFailed").asText();
            assertTrue(reason, reason.contains("could not be rebuilt") && reason.contains("no engine today"));
            await("the error itself, to the console that asked", () -> t.count(AdminSocketsTest.error("ReloadEngine")) == 1);
            assertEquals(0, watcher.count(AdminSocketsTest.error("ReloadEngine")));
        } finally {
            managerField.set(null, manager);
        }
        // A working rebuild afterwards says Reloaded, as usual.
        final int reloaded = t.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
        t.send("ReloadEngine");
        await("rebuilt", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloaded")) == reloaded + 1);
        assertEquals(1, t.count(AdminSocketsTest.notify("ScriptEngineReloadFailed")));
    }

    // ---- a stale sign-in ---------------------------------------------------------------------------

    @Test
    public void aStaleSignInIsAnsweredUnauthorized() throws Exception {
        final AdminSocketsTest.Tab stranger = new AdminSocketsTest.Tab(null);
        tabs.add(stranger);
        ObjectNode forged = Json.newObject();
        forged.put("action", "SendScript");
        forged.put("uid", "a-uid-from-before-the-password-change");
        stranger.sendRaw(forged);
        await("refused", () -> stranger.count(AdminSocketsTest.error("SendScript")) == 1);
        JsonNode refusal = stranger.first(AdminSocketsTest.error("SendScript"));
        assertEquals("unauthorized", refusal.get("status").asText());
        assertTrue(refusal.get("error").asText().startsWith("You are not signed in"));
    }
}
