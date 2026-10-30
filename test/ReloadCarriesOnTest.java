import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.WebSocket;
import security.ParticipantAuth;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * A Reload engine that carries the account's running session on, while its game is under way: the
 * game starts again from the beginning, the pages of those who held a seat get back in by themselves
 * as they reconnect (they used to be told "it began before you arrived", and stopped trying), the
 * session takes newcomers as before its game, its data says so (engineReloaded: restarted, by), the
 * Prolific participants get their Prolific end again, and the autopilot goes on running its session.
 */
public class ReloadCarriesOnTest extends BaseTest {

    // The steps load this slowly (ms) while it is set: a reload's pages reconnect while they load.
    private static final String SLOW_LOAD = "breadboard.test.slowLoad";

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    @SuppressWarnings("unchecked")
    public void cleanUp() throws Throwable {
        System.clearProperty(SLOW_LOAD);
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
        Thread.sleep(300);   // let a launch that is under way finish before the tables are emptied
        for (AdminSocketsTest.Tab t : tabs) t.close();
        ((EventTracker) scriptBoardField("eventTracker").get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
        Map<String, Client> clients = (Map<String, Client>) scriptBoardField("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ((Set<?>) scriptBoardField("endedPages").get(null)).clear();
        ParticipantAuth.reset();
        Recruitment.reset();
        Field launch = Autopilot.class.getDeclaredField("launchRequestedAt");
        launch.setAccessible(true);
        launch.set(null, 0L);
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession"}) {
            Field f = Autopilot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);
        due.set(null, 0L);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Field scriptBoardField(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ScriptEngine engine() throws Exception {
        return (ScriptEngine) scriptBoardField("engine").get(null);
    }

    private static Object engineValue(String script) {
        try {
            ScriptEngine engine = engine();
            return engine == null ? null : engine.eval(script);
        } catch (Exception e) {
            return null;
        }
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

    /** A participant's page: its socket's frames, and whether the server closed it. */
    private static final class Page {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { closed = true; }
        }, 0L);

        /** Whether the server gave it an end of its own ("ended": the session started without them, ...). */
        String ended() {
            for (JsonNode f : frames) {
                if (f.path("player").hasNonNull("ended")) return f.path("player").path("ended").asText();
            }
            return null;
        }
    }

    private static Page connect(Experiment experiment, ExperimentInstance session, String id) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), id, page.in, page.out);
        return page;
    }

    private static List<Event> events(ExperimentInstance session, String name) {
        return Event.find.where().eq("experimentInstance.id", session.id).eq("name", name).orderBy("id asc").findList();
    }

    private static String value(Event event, String name) {
        for (EventData d : event.eventData) {
            if (name.equals(d.name)) return d.value;
        }
        return null;
    }

    /** InitStep, loading slowly while SLOW_LOAD is set; its start waits for nothing. */
    private static final String STEPS = "def slow = System.getProperty('" + SLOW_LOAD + "')\n"
        + "if (slow != null) Thread.sleep(Long.valueOf(slow))\n"
        + "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = { }\n"
        + "initStep.done = { }";

    private AdminSocketsTest.Tab console(User user) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(AdminSocketsTest.userJson()) >= 1);
        return tab;
    }

    private static boolean gameStarted() throws Exception {
        java.lang.reflect.Method m = ScriptBoard.class.getDeclaredMethod("engineJson");
        m.setAccessible(true);
        return ((ObjectNode) m.invoke(null)).get("gameStarted").asBoolean();
    }

    // ---- a session run by hand ----------------------------------------------------------------------

    @Test
    public void theGameStartsAgainAndTheSeatHoldersGetBackInWhileTheStepsStillLoad() throws Exception {
        final User researcher = user("reload");
        final Experiment experiment = createExperiment("Reloaded mid-game");
        createStep(experiment, "InitStep", STEPS);
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(scriptBoardField("engineExperimentId").get(null)));
        tab.send("LaunchGame", "name", "R", "parameters", Json.newObject());
        await("running", () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id)
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        final ExperimentInstance session = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();

        // A Prolific participant and another one, in a game under way.
        Recruitment.prolificEntry("pp-r1", "PID-R1", "STUDY", "SESSION");
        java.util.LinkedHashMap<Object, Object> entry = new java.util.LinkedHashMap<Object, Object>();
        entry.put("clientId", "pp-r1");
        entry.put("prolificPid", "PID-R1");
        entry.put("studyId", "STUDY");
        entry.put("sessionId", "SESSION");
        ScriptBoard.trackEvent("prolificEntry", entry);
        final Page prolific = connect(experiment, session, "pp-r1");
        final Page other = connect(experiment, session, "r2");
        assertEquals("prolific", engineValue("g.getVertex('pp-r1')._system.recruitment?.source"));
        tab.send("RunGame");
        await("the game under way", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted) && gameStarted());

        // Reload, with the steps loading for a while: the pages are closed, and come back meanwhile.
        System.setProperty(SLOW_LOAD, "2500");
        tab.send("ReloadEngine");
        await("their pages closed by the rebuild", () -> prolific.closed && other.closed);
        Page otherBack = connect(experiment, session, "r2");
        assertNull("not the late arrival's page: " + otherBack.frames, otherBack.ended());
        assertFalse("it takes people again before the steps have loaded", ExperimentInstance.findById(session.id).hasStarted);
        assertFalse(otherBack.closed);
        assertNotNull("back in the network", engineValue("g.getVertex('r2')"));
        System.clearProperty(SLOW_LOAD);
        await("the steps loaded", () -> engineValue("initStep") != null && !gameStarted());

        // The Prolific participant gets their Prolific end again (their entry is known again).
        Page prolificBack = connect(experiment, session, "pp-r1");
        assertNull(prolificBack.ended());
        assertEquals("prolific", engineValue("g.getVertex('pp-r1')._system.recruitment?.source"));
        assertEquals("PID-R1", engineValue("g.getVertex('pp-r1')._system.recruitment?.prolificPid"));

        // The data says where the game started again, and who reloaded the engine; the seat holders'
        // returns come after it.
        List<Event> reloaded = events(session, "engineReloaded");
        assertEquals(1, reloaded.size());
        assertEquals("true", value(reloaded.get(0), "restarted"));
        assertEquals(researcher.email, value(reloaded.get(0), "by"));
        List<Event> connected = events(session, "clientConnected");
        assertEquals(4, connected.size());
        assertTrue(connected.get(2).id > reloaded.get(0).id);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);

        // Newcomers are taken as before the game, and Start runs InitStep again.
        Result login = route(fakeRequest(POST, "/game/" + experiment.id + "/" + session.id + "/login")
            .withFormUrlEncodedBody(java.util.Collections.singletonMap("id", "r3")));
        assertEquals(SEE_OTHER, status(login));
        int errors = tab.count(AdminSocketsTest.error("RunGame"));
        tab.send("RunGame");
        await("the game under way again", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted) && gameStarted());
        Thread.sleep(200);
        assertEquals(errors, tab.count(AdminSocketsTest.error("RunGame")));

        // Someone who was there before the reload and didn't come back hasn't taken part, when the
        // session finishes: their connection was the game before.
        Event before = new Event();
        before.name = "clientConnected";
        before.experimentInstance = session;
        before.datetime = new java.util.Date(reloaded.get(0).datetime.getTime() - 1000);
        EventData who = new EventData();
        who.name = "clientId";
        who.value = "pp-r4";
        before.addEventData(who);
        before.save();
        Event entered = new Event();
        entered.name = "prolificEntry";
        entered.experimentInstance = session;
        entered.datetime = before.datetime;
        for (String[] d : new String[][]{{"clientId", "pp-r4"}, {"prolificPid", "PID-R4"}}) {
            EventData ed = new EventData();
            ed.name = d[0];
            ed.value = d[1];
            entered.addEventData(ed);
        }
        entered.save();
        ExperimentInstance finished = ExperimentInstance.findById(session.id);
        finished.finish();
        finished.update();
        assertNull("not taken part", Recruitment.prolificCompletion(experiment.id, "PID-R4"));
        assertNotNull("but those connected at the end have", Recruitment.prolificCompletion(experiment.id, "PID-R1"));
    }

    @Test
    public void aReloadBeforeTheGameStartedSaysSoAndChangesNothingElse() throws Exception {
        final User researcher = user("early");
        final Experiment experiment = createExperiment("Reloaded before the game");
        createStep(experiment, "InitStep", STEPS);
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(scriptBoardField("engineExperimentId").get(null)));
        tab.send("LaunchGame", "name", "E", "parameters", Json.newObject());
        await("running", () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id)
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        final ExperimentInstance session = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
        final Page page = connect(experiment, session, "e1");
        tab.send("ReloadEngine");
        await("rebuilt", () -> page.closed && engineValue("initStep") != null);
        List<Event> reloaded = events(session, "engineReloaded");
        assertEquals(1, reloaded.size());
        assertEquals("false", value(reloaded.get(0), "restarted"));
        assertNull(connect(experiment, session, "e1").ended());
    }

    // ---- the autopilot's session ----------------------------------------------------------------

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static JsonNode autopilotStatus(User user) {
        return Json.parse(contentAsString(route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid)))).get("status");
    }

    @Test
    public void theAutopilotGoesOnRunningItsSessionWhoseGameStartsAgain() throws Exception {
        final User researcher = user("apreload");
        final Experiment experiment = createExperiment("Autopilot reloaded mid-game");
        createStep(experiment, "InitStep", STEPS);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        ObjectNode s = Json.newObject();
        s.put("enabled", true);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", 2);
        room.put("maxPlayers", 2);
        room.put("waitSeconds", 30);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", 0);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", false);
        replace.put("afterSeconds", 15);
        replace.put("returnToHuman", true);
        final AdminSocketsTest.Tab tab = console(researcher);
        assertEquals(OK, status(post(researcher, s)));
        await("its session", () -> Autopilot.openInstance(experiment.id) != null && engineValue("autopilot.room?.maxPlayers") != null);
        final ExperimentInstance session = Autopilot.openInstance(experiment.id);

        final Page first = connect(experiment, session, "ap-1");
        final Page second = connect(experiment, session, "ap-2");
        await("its waiting room started the game", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted)
            && events(session, "autopilotStart").size() == 1);

        // The account the autopilot runs as reloads the engine: its session carries on.
        tab.send("ReloadEngine");
        await("rebuilt", () -> first.closed && second.closed && engineValue("autopilot.room?.maxPlayers") != null);
        JsonNode st = autopilotStatus(researcher);
        assertFalse(st.toString(), st.get("paused").asBoolean());
        assertEquals(session.id.longValue(), st.get("instance").get("id").asLong());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertEquals(session.id, Autopilot.openInstance(experiment.id).id);
        assertEquals("true", value(events(session, "engineReloaded").get(0), "restarted"));

        // Its two participants come back, and its waiting room starts the game again.
        connect(experiment, session, "ap-1");
        connect(experiment, session, "ap-2");
        await("the game started again", () -> events(session, "autopilotStart").size() == 2
            && Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        assertFalse(autopilotStatus(researcher).get("paused").asBoolean());
    }
}
