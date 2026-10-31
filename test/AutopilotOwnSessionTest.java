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
 * Every console hears whether the live session is the autopilot's own ({@code engine.autopilotSession},
 * in the User JSON and in every {"engine": ...} frame): the console then says that steps saved meanwhile
 * go into the autopilot's next session, rather than "Reload needed". It changes without the engine
 * changing when the autopilot is switched off (its session goes on, run by nobody), switched on again
 * (the open session of its experiment is handed to it), paused, or resumed; a console that had asked
 * the autopilot once used to keep what it was told then.
 */
public class AutopilotOwnSessionTest extends BaseTest {

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
            setAutopilot(name, null);
        }
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);
        due.set(null, 0L);
    }

    private static void setAutopilot(String field, Object value) throws Exception {
        Field f = Autopilot.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static ScriptEngine engine() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (ScriptEngine) f.get(null);
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
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        return t;
    }

    /** What the engine runs, as this console was last told: by a User JSON or an {"engine": ...} frame. */
    private static JsonNode lastEngine(AdminSocketsTest.Tab t) {
        JsonNode last = null;
        for (JsonNode f : t.frames) if (f.has("engine") && f.get("engine").isObject()) last = f.get("engine");
        return last;
    }

    /** Whether this console was last told that the engine runs session {@code id}, and whether it is the autopilot's own. */
    private static boolean told(AdminSocketsTest.Tab t, Long id, boolean autopilots) {
        JsonNode engine = lastEngine(t);
        return engine != null && engine.path("sessionId").isNumber() && engine.get("sessionId").asLong() == id
            && engine.path("autopilotSession").isBoolean() && engine.get("autopilotSession").asBoolean() == autopilots;
    }

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static Result resume(User user) {
        return callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", user.uid));
    }

    /** Continuous, with a waiting room for two that nobody joins: its session stays open, its game unstarted. */
    private static ObjectNode settings(Experiment experiment, boolean on) {
        ObjectNode s = Json.newObject();
        s.put("enabled", on);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", 2);
        room.putNull("maxPlayers");
        room.put("waitSeconds", 300);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", 5);
        return s;
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
    }

    @Test
    public void everyConsoleHearsWhetherTheLiveSessionIsTheAutopilotsOwn() throws Exception {
        final User alice = user("alice");
        final User bob = user("bob");
        final Experiment experiment = createExperiment("Unattended");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\ninitStep.run = { }\ninitStep.done = { }");
        alice.ownedExperiments.add(experiment);
        alice.saveManyToManyAssociations("ownedExperiments");
        // a colleague's console, which works on no experiment
        final AdminSocketsTest.Tab b = loggedIn(bob);

        assertEquals(OK, status(post(alice, settings(experiment, true))));
        await("the autopilot's session", () -> sessions(experiment).size() == 1
            && sessions(experiment).get(0).status == ExperimentInstance.Status.RUNNING);
        final Long session = sessions(experiment).get(0).id;
        await("the console hears it is the autopilot's", () -> told(b, session, true));

        // Switched off, the autopilot leaves its session running, and it is nobody's: saved steps need a reload.
        assertEquals(OK, status(post(alice, settings(experiment, false))));
        await("the console hears it is no longer the autopilot's", () -> told(b, session, false));
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session).status);

        // Switched on again, it takes the open session of its experiment as its own, and opens no other.
        assertEquals(OK, status(post(alice, settings(experiment, true))));
        await("the console hears it is the autopilot's again", () -> told(b, session, true));
        Thread.sleep(500);
        assertEquals(1, sessions(experiment).size());

        // Paused (as a researcher's action pauses it), it runs no session; Resume hands the open one back.
        setAutopilot("pausedBecause", "a test paused it");
        setAutopilot("ownSession", null);
        ScriptBoard.engineChanged();
        await("the console hears it is paused", () -> told(b, session, false));
        assertEquals(OK, status(resume(alice)));
        await("the console hears it was resumed with it", () -> told(b, session, true));
        assertEquals(1, sessions(experiment).size());

        // A console opened now is told in its User JSON.
        final AdminSocketsTest.Tab later = loggedIn(user("carol"));
        assertTrue(lastEngine(later).toString(), told(later, session, true));
    }
}
