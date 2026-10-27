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
 * as sees the session in its open console tabs.
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
        assertTrue(first.name, first.name.startsWith("auto "));
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
    public void aSessionOfAnotherExperimentIsLeftAloneWhenTheNextOneIsDue() throws Exception {
        final User researcher = user("busy");
        final Experiment auto = createExperiment("Autopiloted");
        final Experiment manual = createExperiment("Run by hand");
        researcher.ownedExperiments.add(auto);
        researcher.ownedExperiments.add(manual);
        researcher.saveManyToManyAssociations("ownedExperiments");

        assertEquals(OK, status(post(researcher, settings(auto, 2))));
        await("the autopilot's session", () -> sessions(auto).size() == 1
            && sessions(auto).get(0).status == ExperimentInstance.Status.RUNNING);

        // Someone launches a session of another experiment by hand (ending the autopilot's).
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(f -> f.has("user")) >= 1);
        tab.send("SelectExperiment", "experimentId", manual.id);
        await("the selection", () -> manual.id.equals(User.findByEmail(researcher.email).selectedExperiment.id));
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the manual session", () -> sessions(manual).size() == 1
            && sessions(manual).get(0).status == ExperimentInstance.Status.RUNNING);

        // The autopilot's session counts as finished: its next one waits for the engine to be free.
        ExperimentInstance autoSession = ExperimentInstance.findById(sessions(auto).get(0).id);
        autoSession.status = ExperimentInstance.Status.FINISHED;
        autoSession.save();
        Autopilot.sessionFinished(autoSession);
        Thread.sleep(800);
        assertEquals(1, sessions(auto).size());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(sessions(manual).get(0).id).status);
    }
}
