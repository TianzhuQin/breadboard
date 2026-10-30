import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.Http;
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
 * How the autopilot's sessions flow for participants and researchers: a waiting room's maximum is a
 * real one (the first to arrive are seated, anyone else is told the session is full and how to join
 * the next one, and nobody more is sent to a full room).
 */
public class AutopilotFlowTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    @SuppressWarnings("unchecked")
    public void cleanUp() throws Throwable {
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
        setAutopilot("launchRequestedAt", 0L);
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession"}) {
            setAutopilot(name, null);
        }
        setAutopilot("nextOpenAt", 0L);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static void setAutopilot(String name, Object value) throws Exception {
        Field f = Autopilot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

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

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static JsonNode autopilotStatus(User user) {
        return Json.parse(contentAsString(route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid)))).get("status");
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
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

    /** Settings that run the experiment with a waiting room of these limits (the computer takes no seats). */
    private static ObjectNode settings(Experiment experiment, int minPlayers, Integer maxPlayers, int countdownSeconds) {
        ObjectNode s = Json.newObject();
        s.put("enabled", true);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        s.putNull("maxSessionMinutes");
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", minPlayers);
        if (maxPlayers == null) room.putNull("maxPlayers"); else room.put("maxPlayers", maxPlayers);
        room.put("waitSeconds", 30);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", countdownSeconds);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", false);
        replace.put("afterSeconds", 15);
        replace.put("returnToHuman", true);
        return s;
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

        /** The last player frame (the screen it shows). */
        JsonNode lastPlayer() {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (frames.get(i).has("player")) return frames.get(i).get("player");
            }
            return null;
        }
    }

    private static Page connect(Experiment experiment, ExperimentInstance session, String id) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), id, page.in, page.out);
        return page;
    }

    // InitStep's start waits for nothing: the game is under way once it starts.
    private static final String STEPS = "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = { }\n"
        + "initStep.done = { }";

    /** The autopilot's open session of the experiment, its waiting room set. */
    private static ExperimentInstance openSession(final Experiment experiment) throws Exception {
        await("its session", () -> Autopilot.openInstance(experiment.id) != null && engineValue("autopilot.room?.minPlayers") != null);
        return Autopilot.openInstance(experiment.id);
    }

    private Experiment experimentOf(User researcher, String name) {
        Experiment experiment = createExperiment(name);
        createStep(experiment, "InitStep", STEPS);
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        return experiment;
    }

    // ---- Maximum players --------------------------------------------------------------------------

    @Test
    public void aFullRoomSeatsTheFirstToArriveAndTellsTheOthersTheSessionIsFull() throws Exception {
        final User researcher = user("full");
        final Experiment experiment = experimentOf(researcher, "Full room");
        assertEquals(OK, status(post(researcher, settings(experiment, 2, 2, 2))));
        final ExperimentInstance session = openSession(experiment);

        Page first = connect(experiment, session, "f-1");
        Thread.sleep(10);
        Page second = connect(experiment, session, "f-2");
        assertNull("nobody more is sent to a full room as it counts down", Autopilot.openInstance(experiment.id));
        // Someone who signed in just before the room was full joins during the countdown.
        Thread.sleep(10);
        final Page third = connect(experiment, session, "f-3");
        await("the start", () -> events(session, "autopilotStart").size() == 1 && third.closed);

        assertEquals("2", value(events(session, "autopilotStart").get(0), "players"));
        assertNotNull(engineValue("g.getVertex('f-1')"));
        assertNotNull(engineValue("g.getVertex('f-2')"));
        assertNull("seated, although the room was full", engineValue("g.getVertex('f-3')"));
        assertFalse(first.closed);
        assertFalse(second.closed);
        JsonNode screen = third.lastPlayer();
        assertEquals("sessionFull", screen.get("ended").asText());
        String text = screen.get("text").asText();
        assertTrue(text, text.contains("This session is full"));
        assertTrue("it leads to the next session: " + text, text.contains("href=\"/play/" + experiment.id + "/login\""));
        List<Event> full = events(session, "sessionFull");
        assertEquals(1, full.size());
        assertEquals("f-3", value(full.get(0), "clientId"));
        int gone = 0;
        for (Event e : events(session, "clientDisconnected")) {
            if ("f-3".equals(value(e, "clientId"))) gone++;
        }
        assertEquals("their going is recorded", 1, gone);

        // Their page coming back gets the same screen, and the study's link treats them as someone new.
        Page back = connect(experiment, session, "f-3");
        assertEquals("sessionFull", back.lastPlayer().get("ended").asText());
        assertNull(engineValue("g.getVertex('f-3')"));
        assertTrue(ScriptBoard.leftOutOf(session.id.toString(), "f-3"));
        ParticipantAuth.claim(session.id.toString(), "f-3");
        Result link = route(fakeRequest(GET, "/play/" + experiment.id + "/login")
            .withCookies(new Http.Cookie("bb_seat_" + experiment.id, session.id + "/f-3", null, null, null, false, false)));
        assertFalse("not sent back to the seat that only says it is full", contentAsString(link).contains("already taking part"));
    }

    // ---- the study's link while no session takes participants --------------------------------------

    private static String page(String uri) {
        return contentAsString(route(fakeRequest(GET, uri)));
    }

    @Test
    public void theStudyLinkWaitsForTheNextSessionWhileTheAutopilotOpensItByItself() throws Exception {
        final User researcher = user("holding");
        final Experiment experiment = experimentOf(researcher, "Holding page");
        ObjectNode s = settings(experiment, 1, 1, 0);   // the game starts as soon as one is here
        s.put("nextSessionDelaySeconds", 30);
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = openSession(experiment);
        String login = "/play/" + experiment.id + "/login";
        String prolific = "/play/" + experiment.id + "/prolific?PROLIFIC_PID=HOLD1&STUDY_ID=S&SESSION_ID=X";
        assertEquals("a session is open: straight to it", SEE_OTHER, status(route(fakeRequest(GET, login))));
        connect(experiment, session, "h-1");
        await("the game", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));

        // During the game: a session is in progress, and the next opens when it ends. The page asks again
        // by itself, every few seconds.
        Result during = route(fakeRequest(GET, login));
        String text = contentAsString(during);
        assertTrue(text, text.contains("A session is in progress"));
        assertTrue(text, text.contains("The next one opens as soon as it has ended, and this page takes you into it by itself: please keep it open."));
        assertTrue(text, text.contains("<meta http-equiv=\"refresh\" content=\"4\">"));
        assertFalse(text, text.contains("try again in a few minutes"));
        assertEquals("no-store", header("Cache-Control", during));
        String forProlific = page(prolific);
        // (the page escapes the apostrophe of "can't")
        assertTrue(forProlific, forProlific.contains("so you can keep it open. If you can&#x27;t wait, please return the study on Prolific."));
        assertTrue(forProlific, forProlific.contains("http-equiv=\"refresh\""));

        // Between sessions: the next one opens in a moment.
        engine().eval("gameListener.finish(); null");
        await("finished", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
        String between = page(login);
        assertTrue(between, between.contains("The next session opens soon"));
        assertTrue(between, between.contains("http-equiv=\"refresh\""));

        // Paused or done, or off, nothing opens by itself: the page says so, and asks nobody to wait.
        setAutopilot("pausedBecause", "a test paused it");
        String paused = page(login);
        assertTrue(paused, paused.contains("No session is open right now"));
        assertTrue(paused, paused.contains("Please try again in a few minutes, or contact the researcher."));
        assertFalse(paused, paused.contains("http-equiv=\"refresh\""));
        assertTrue(page(prolific).contains("There is no session taking participants at the moment. Please return the study on Prolific."));
        setAutopilot("pausedBecause", null);
        setAutopilot("doneBecause", "its one session finished");
        assertFalse(page(login).contains("http-equiv=\"refresh\""));
        setAutopilot("doneBecause", null);
        assertTrue(page(login).contains("http-equiv=\"refresh\""));
        // (changed in the database, as a save would, without the save opening a session)
        Ebean.createSqlUpdate("update autopilot_settings set continuous = false").execute();
        String once = page(login);
        assertTrue(once, once.contains("No session is open right now"));
        Ebean.createSqlUpdate("update autopilot_settings set continuous = true, enabled = false").execute();
        String off = page(login);
        assertTrue(off, off.contains("No session is open right now"));
    }

    @Test
    public void theFullScreenSaysWhatToDoOnProlificAndWhenNoNextSessionOpensByItself() throws Exception {
        final User researcher = user("fullwords");
        final Experiment experiment = experimentOf(researcher, "Full room words");
        ObjectNode s = settings(experiment, 2, 2, 2);
        assertEquals(OK, status(post(researcher, s)));
        openSession(experiment);
        java.lang.reflect.Method screen = ScriptBoard.class.getDeclaredMethod("sessionFullScreen", boolean.class, Long.class);
        screen.setAccessible(true);
        String prolific = ((JsonNode) screen.invoke(null, true, experiment.id)).get("player").get("text").asText();
        assertTrue(prolific, prolific.contains("open the study again from Prolific"));
        assertTrue(prolific, prolific.contains("return the study on Prolific if you can't wait"));
        // With no next session opening by itself (here: continuous off), it says so.
        s.put("continuous", false);
        assertEquals(OK, status(post(researcher, s)));
        String plain = ((JsonNode) screen.invoke(null, false, experiment.id)).get("player").get("text").asText();
        assertFalse(plain, plain.contains("href"));
        assertTrue(plain, plain.contains("contact the researcher"));
        String prolificNoNext = ((JsonNode) screen.invoke(null, true, experiment.id)).get("player").get("text").asText();
        assertTrue(prolificNoNext, prolificNoNext.contains("Please return the study on Prolific"));
    }
}
