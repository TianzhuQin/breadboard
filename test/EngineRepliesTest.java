import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/**
 * What a console is told about the engine's work, so it can tell its own answers from everything
 * else on the socket: RunGame, SendScript and SendStep frames name their request, the join and leave
 * steps' frames their source; a researcher's choice is answered "made" or "gone"; a failed rebuild
 * is told to every console; a stale sign-in is answered with "status": "unauthorized".
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
        assertEquals("[Left]", String.valueOf(engine.eval("picked")));

        // Already answered: nothing runs a second time.
        t.send("MakeChoice", "choiceUID", uid);
        await("gone", () -> t.count(f -> tagged("choice", "gone").matches(f)
            && "MakeChoice".equals(f.get("request").asText())) == 1);
        t.send("MakeChoice", "choiceUID", "no-such-choice");
        await("gone again", () -> t.count(tagged("choice", "gone")) == 2);
        assertEquals("[Left]", String.valueOf(engine.eval("picked")));

        // A choice whose result fails: an error naming the request.
        engine.eval("a.add(g.getVertex('ch1'), [name: 'Boom', result: { throw new IllegalStateException('boom') }])");
        final String boom = String.valueOf(engine.eval("g.getVertex('ch1').choices[0].uid"));
        t.send("MakeChoice", "choiceUID", boom);
        await("error", () -> t.count(AdminSocketsTest.error("MakeChoice")) == 1);
        assertTrue(t.first(AdminSocketsTest.error("MakeChoice")).get("error").asText().contains("boom"));
        assertFalse(t.first(AdminSocketsTest.error("MakeChoice")).has("choice"));
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
