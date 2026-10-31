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
 * "Reload needed" comes from the server (models.EngineSteps): what the engine runs
 * ({@code engine.stepsChanged}, in the User JSON and in every {"engine": ...} frame) says whether
 * steps of its experiment were saved since it loaded them. Every console hears it when a step is
 * saved or deleted, and a console opened afterwards is told too; Run of a saved step's code and a
 * reload catch the engine up. Steps of another experiment don't count.
 */
public class EngineStepsTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
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

    private static Long engineExperimentId() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engineExperimentId");
        f.setAccessible(true);
        return (Long) f.get(null);
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

    /** What the engine runs, as this console was last told: by a User JSON or an {"engine": ...} frame. */
    private static JsonNode lastEngine(AdminSocketsTest.Tab t) {
        JsonNode last = null;
        for (JsonNode f : t.frames) if (f.has("engine") && f.get("engine").isObject()) last = f.get("engine");
        return last;
    }

    private static JsonNode lastNews(AdminSocketsTest.Tab t) {
        JsonNode last = null;
        for (JsonNode f : t.frames) if (f.has("engine") && !f.has("user")) last = f.get("engine");
        return last;
    }

    private static int engineFrames(AdminSocketsTest.Tab t) {
        return t.count(f -> f.has("engine") && !f.has("user"));
    }

    /** Whether this console was last told that the engine runs experiment {@code e} with steps changed {@code changed}. */
    private static boolean told(AdminSocketsTest.Tab t, Experiment e, boolean changed) {
        JsonNode engine = lastEngine(t);
        return engine != null && engine.has("stepsChanged") && engine.get("experimentId").isNumber()
            && engine.get("experimentId").asLong() == e.id && engine.get("stepsChanged").asBoolean() == changed;
    }

    private static Result saveStep(User user, Long stepId, Long experimentId, String name, String source) {
        ObjectNode body = Json.newObject();
        body.put("name", name);
        body.put("stepSource", source);
        body.put("experimentId", experimentId);
        return callAction(controllers.routes.ref.StepsController.updateStep(stepId),
            fakeRequest().withJsonBody(body).withSession("uid", user.uid));
    }

    private static Result deleteStep(User user, Long stepId) {
        return callAction(controllers.routes.ref.StepsController.deleteStep(stepId), fakeRequest().withSession("uid", user.uid));
    }

    /** An experiment with the built-in steps and a step "Note" ({@code noteValue = 1}), loaded by {@code by}'s console. */
    private Step loadedNote(final Experiment e, User by, final AdminSocketsTest.Tab t) throws Exception {
        createStep(e, "OnJoinStep", Experiment.generateOnJoinStep().source);
        createStep(e, "OnLeaveStep", Experiment.generateOnLeaveStep().source);
        createStep(e, "InitStep", Experiment.generateInitStep().source);
        Step note = createStep(e, "Note", "noteValue = 1");
        own(by, e);
        t.send("SelectExperiment", "experimentId", e.id);
        // (the engine's own push after the rebuild, not only the User JSON that answers the selection)
        await("loaded", () -> e.id.equals(engineExperimentId()) && told(t, e, false)
            && lastNews(t) != null && lastNews(t).path("experimentId").asLong() == e.id);
        return note;
    }

    @Test
    public void everyConsoleHearsThatSavedStepsAreNotInTheEngineUntilRunOrAReloadLoadsThem() throws Throwable {
        final User alice = user("alice");
        final User bob = user("bob");
        final Experiment e = createExperiment("Reload needed");
        final AdminSocketsTest.Tab a = loggedIn(alice);
        final Step note = loadedNote(e, alice, a);
        own(bob, e);
        final AdminSocketsTest.Tab b = loggedIn(bob);
        assertTrue("a console that signs in is told the engine runs the saved steps", told(b, e, false));

        // Alice saves a change: every console hears it, hers and Bob's.
        assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 2")));
        await("Alice hears", () -> told(a, e, true));
        await("Bob hears", () -> told(b, e, true));

        // A console opened now, or refreshed, is told the same.
        final AdminSocketsTest.Tab b2 = loggedIn(bob);
        assertTrue(told(b2, e, true));
        final int users = b.count(AdminSocketsTest.userJson());
        b.send("Update");
        await("Bob's refresh", () -> b.count(AdminSocketsTest.userJson()) > users);
        assertTrue(told(b, e, true));

        // Run of that step's saved code catches the engine up, for everyone.
        a.send("SendStep", "id", note.id, "name", "Note", "source", "noteValue = 2", "experimentId", e.id, "requestId", "r-1");
        await("Bob hears it caught up", () -> told(b, e, false));
        assertEquals(2, engine().eval("noteValue"));

        // Saved back to what the engine has: nothing to reload.
        assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 3")));
        await("changed", () -> told(b, e, true));
        assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 2")));
        await("the same again", () -> told(b, e, false));

        // A Run that fails loads nothing; one of code that isn't saved doesn't catch up either.
        assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = notDefinedAnywhere")));
        await("changed again", () -> told(b, e, true));
        a.send("SendStep", "id", note.id, "name", "Note", "source", "noteValue = notDefinedAnywhere", "experimentId", e.id, "requestId", "r-2");
        await("its error", () -> a.count(f -> f.has("error") && "r-2".equals(f.path("requestId").asText())) >= 1);
        a.send("SendStep", "id", note.id, "name", "Note", "source", "noteValue = 4", "experimentId", e.id, "requestId", "r-3");
        await("ran", () -> Integer.valueOf(4).equals(engine().eval("noteValue")));
        Thread.sleep(300);
        assertTrue(told(b, e, true));
        assertTrue(told(a, e, true));

        // A reload loads every saved step.
        assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 5")));
        a.send("ReloadEngine");
        await("reloaded", () -> told(b, e, false) && Integer.valueOf(5).equals(engine().eval("noteValue")));
    }

    /**
     * A step that holds its build while system property {@code gate} is "closed" (20 s at most), so a
     * test can ask what the engine runs while the steps load. It sets {@code gate}.in as it starts.
     */
    private static String gateStep(String gate) {
        return "System.setProperty('" + gate + ".in', 'yes')\n"
            + "def gateUntil = System.currentTimeMillis() + 20000\n"
            + "while (System.getProperty('" + gate + "') == 'closed' && System.currentTimeMillis() < gateUntil) { Thread.sleep(20) }\n"
            + "gateValue = 1";
    }

    private static int indexSince(AdminSocketsTest.Tab t, int from, AdminSocketsTest.Matcher m) {
        for (int i = from; i < t.frames.size(); i++) if (m.matches(t.frames.get(i))) return i;
        return -1;
    }

    /**
     * Reload engine, with its steps held at the gate. As every console does, ask for the User JSON as
     * soon as the engine says it rebuilt (admin.ts askEngineInfo), and return what it says the engine
     * runs; {@code meanwhile} runs once the steps have begun to load. The gate stays closed.
     */
    private static JsonNode askedWhileTheStepsLoad(final AdminSocketsTest.Tab a, final String gate, Runnable meanwhile) throws Exception {
        System.clearProperty(gate + ".in");
        System.setProperty(gate, "closed");
        final int before = a.frames.size();
        a.send("ReloadEngine");
        await("the engine says it rebuilt", () -> indexSince(a, before, f -> f.path("notify").has("ScriptEngineReloaded")) >= 0);
        await("its steps load", () -> System.getProperty(gate + ".in") != null);
        meanwhile.run();
        final int asked = a.frames.size();
        a.send("Update");
        await("the User JSON", () -> indexSince(a, asked, AdminSocketsTest.userJson()) >= 0);
        JsonNode answer = a.frames.get(indexSince(a, asked, AdminSocketsTest.userJson()));
        assertNull("the steps are still loading", engine().get("gateValue"));
        return answer.has("engine") ? answer.get("engine") : answer.get("user").get("engine");
    }

    @Test
    public void aConsoleThatAsksWhileARebuildsStepsLoadIsNotToldToReload() throws Throwable {
        final String gate = "bb.test.enginesteps." + UUID.randomUUID();
        try {
            final User alice = user("alice");
            final Experiment e = createExperiment("Slow steps");
            createStep(e, "Gate", gateStep(gate));
            final AdminSocketsTest.Tab a = loggedIn(alice);
            final Step note = loadedNote(e, alice, a);

            // Reload engine with nothing saved: nothing to reload, while the steps load and after.
            JsonNode during = askedWhileTheStepsLoad(a, gate, () -> {});
            assertEquals(e.id.longValue(), during.get("experimentId").asLong());
            assertFalse("nothing was saved", during.get("stepsChanged").asBoolean());
            final int loading = a.frames.size();
            System.clearProperty(gate);
            await("the push after the steps loaded", () -> indexSince(a, loading, f -> f.has("engine") && !f.has("user")) >= 0);
            assertTrue(told(a, e, false));
            for (int i = loading; i < a.frames.size(); i++) {
                JsonNode engine = a.frames.get(i).has("user") ? null : a.frames.get(i).get("engine");
                if (engine != null) assertFalse("never said since: " + engine, engine.path("stepsChanged").asBoolean());
            }

            // Reloaded for a step saved before: the same.
            assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 2")));
            await("saved", () -> told(a, e, true));
            during = askedWhileTheStepsLoad(a, gate, () -> {});
            assertFalse("the engine is loading what was saved", during.get("stepsChanged").asBoolean());
            System.clearProperty(gate);
            await("loaded", () -> told(a, e, false) && Integer.valueOf(2).equals(engine().get("noteValue")));

            // A step saved while they load, after the build took them: needs a reload once they have loaded.
            during = askedWhileTheStepsLoad(a, gate, () ->
                assertEquals(OK, status(saveStep(alice, note.id, e.id, "Note", "noteValue = 3"))));
            assertFalse(during.get("stepsChanged").asBoolean());
            System.clearProperty(gate);
            await("the save is told", () -> told(a, e, true));
            assertEquals(2, engine().get("noteValue"));
        } finally {
            System.clearProperty(gate);
            System.clearProperty(gate + ".in");
        }
    }

    @Test
    public void aDeletedOrNewStepNeedsAReloadButAnotherExperimentsStepsDont() throws Throwable {
        final User alice = user("alice");
        final Experiment e = createExperiment("Steps come and go");
        final Experiment other = createExperiment("Not loaded");
        final Step otherStep = createStep(other, "Elsewhere", "elsewhere = 1");
        own(alice, other);
        final AdminSocketsTest.Tab a = loggedIn(alice);
        final Step note = loadedNote(e, alice, a);

        // A step of an experiment the engine doesn't run: nothing to say, and nothing said.
        Thread.sleep(300);
        final int before = engineFrames(a);
        assertEquals(OK, status(saveStep(alice, otherStep.id, other.id, "Elsewhere", "elsewhere = 2")));
        Thread.sleep(300);
        assertEquals(before, engineFrames(a));
        assertTrue(told(a, e, false));

        // Deleting a step: the engine keeps what it defined until it is reloaded.
        assertEquals(OK, status(deleteStep(alice, note.id)));
        await("deleted", () -> told(a, e, true));
        a.send("ReloadEngine");
        await("reloaded", () -> told(a, e, false));

        // A new step is saved steps the engine doesn't have.
        assertEquals(OK, status(saveStep(alice, -1L, e.id, "Extra", "extraValue = 1")));
        await("added", () -> told(a, e, true));
        final Long extra = Step.find.where().eq("name", "Extra").findUnique().id;
        // a step not saved yet, run, doesn't count (its id is -1); the saved one run does
        a.send("SendStep", "id", -1, "name", "Draft", "source", "draftValue = 1", "experimentId", e.id, "requestId", "r-1");
        await("draft ran", () -> Integer.valueOf(1).equals(engine().get("draftValue")));
        Thread.sleep(300);
        assertTrue(told(a, e, true));
        a.send("SendStep", "id", extra, "name", "Extra", "source", "extraValue = 1", "experimentId", e.id, "requestId", "r-2");
        await("caught up", () -> told(a, e, false));
    }
}
