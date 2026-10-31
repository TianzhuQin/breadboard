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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * How the autopilot's sessions flow for participants and researchers: a waiting room's maximum is a
 * real one (the first to arrive are seated, anyone else is told the session is full and how to join
 * the next one, and nobody more is sent to a full room); the study's links wait for the next session
 * while the autopilot opens it by itself; a session a researcher opens by hand is theirs, with the
 * steps' own settings, until Resume hands it to the autopilot.
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
        // (waited for a few seconds at most: after a test that found the engine's threads deadlocked,
        // the waiting room's monitor is never free again, and the rest of the suite still runs)
        final ScriptEngine engine = engine();
        Thread stop = new Thread(() -> {
            try {
                if (engine != null) engine.eval("autopilot.stop(); timers.cancel(); null");
            } catch (Exception ignored) {}
        }, "test-cleanup");
        stop.setDaemon(true);
        stop.start();
        stop.join(5000);
        Map<String, Client> clients = (Map<String, Client>) scriptBoardField("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ((Set<?>) scriptBoardField("endedPages").get(null)).clear();
        ParticipantAuth.reset();
        Recruitment.reset();
        setAutopilot("launchRequestedAt", 0L);
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession", "openedSession"}) {
            setAutopilot(name, null);
        }
        setAutopilot("nextOpenAt", 0L);
        setAutopilot("freeDiskBytes", REAL_FREE_DISK);
    }

    // How much room the database's disk has, as the server tells it (tests pretend it is almost full).
    private static final Object REAL_FREE_DISK;
    static {
        try {
            Field f = Autopilot.class.getDeclaredField("freeDiskBytes");
            f.setAccessible(true);
            REAL_FREE_DISK = f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    /** Whether {@code condition} holds within {@code ms}. */
    private static boolean within(long ms, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return true;
            Thread.sleep(25);
        }
        return Boolean.TRUE.equals(condition.call());
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

    @SuppressWarnings("unchecked")
    private static Client client(String id) throws Exception {
        return ((Map<String, Client>) scriptBoardField("clients").get(null)).get(id);
    }

    /** A participant's record lock (Client.recordLock), which ScriptBoard.recordConnection holds as it records. */
    private static Object recordLockOf(Client client) throws Exception {
        java.lang.reflect.Method lock = Client.class.getDeclaredMethod("recordLock");
        lock.setAccessible(true);
        return lock.invoke(client);
    }

    /** The last clientConnected or clientDisconnected of this participant in the session, or null. */
    private static String lastConnectionEvent(ExperimentInstance session, String clientId) {
        String last = null;
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id)
                .in("name", "clientConnected", "clientDisconnected").orderBy("id asc").findList()) {
            if (clientId.equals(value(e, "clientId"))) last = e.name;
        }
        return last;
    }

    /** The threads the JVM finds deadlocked, with where each waits, or null when none are. */
    private static String deadlocked() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (ids == null) return null;
        StringBuilder b = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().getThreadInfo(ids, true, true)) {
            b.append("\n\"").append(info.getThreadName()).append("\" waits for ").append(info.getLockName())
                .append(", held by \"").append(info.getLockOwnerName()).append("\"");
            for (StackTraceElement e : info.getStackTrace()) {
                String line = e.toString();
                if (line.contains("models.") || line.contains("Autopilot")) b.append("\n    at ").append(line);
            }
        }
        return b.toString();
    }

    @Test
    public void aFullRoomStartsWhileAPageOfSomeoneItTurnsAwayIsRecordingAConnectionChange() throws Exception {
        final User researcher = user("fullrecord");
        final Experiment experiment = experimentOf(researcher, "Full room, a page recording");
        assertEquals(OK, status(post(researcher, settings(experiment, 2, 2, 1))));
        final ExperimentInstance session = openSession(experiment);
        connect(experiment, session, "r-1");
        Thread.sleep(10);
        connect(experiment, session, "r-2");
        Thread.sleep(10);
        final Page third = connect(experiment, session, "r-3");

        // As the countdown ends, a page of the one the full room turns away records a connection change
        // (it reconnected, closed, or fell silent): it holds their record lock as it does, then tells the
        // waiting room. The start used to wait for that lock, holding the waiting room's monitor, which
        // the page then waited for: neither ever went on, and the game never started.
        boolean started;
        synchronized (recordLockOf(client("r-3"))) {
            started = within(10000, () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        }
        assertTrue("the game started without waiting for the turned-away page's record", started);
        assertNotNull(engineValue("g.getVertex('r-1')"));
        assertNotNull(engineValue("g.getVertex('r-2')"));
        assertNull(engineValue("g.getVertex('r-3')"));
        assertEquals(1, events(session, "sessionFull").size());
        // Their page is told and closed, and their going is recorded (the Prolific check of who took
        // part reads the last of these), once their page's own record is done.
        await("their page closed", () -> third.closed);
        assertEquals("sessionFull", third.lastPlayer().get("ended").asText());
        await("their going recorded", () -> "clientDisconnected".equals(lastConnectionEvent(session, "r-3")));
        assertNull(deadlocked());
    }

    @Test
    public void aFullRoomStartsWhileThePageOfSomeoneItTurnsAwayReconnectsAsTheCountdownEnds() throws Exception {
        final User researcher = user("fullflaky");
        final Experiment experiment = experimentOf(researcher, "Full room, a flaky page");
        assertEquals(OK, status(post(researcher, settings(experiment, 2, 2, 1))));
        Long previous = null;
        for (int round = 1; round <= 2; round++) {
            final Long before = previous;
            await("session " + round, () -> {
                ExperimentInstance open = Autopilot.openInstance(experiment.id);
                return open != null && !open.id.equals(before) && engineValue("autopilot.room?.maxPlayers") != null;
            });
            final ExperimentInstance session = Autopilot.openInstance(experiment.id);
            previous = session.id;
            final String third = "f" + round + "-3";
            connect(experiment, session, "f" + round + "-1");
            Thread.sleep(5);
            connect(experiment, session, "f" + round + "-2");
            final long countdownBegan = System.currentTimeMillis();
            Thread.sleep(5);
            connect(experiment, session, third);

            // The page of the one it turns away (a phone on a weak connection) reconnects over and over,
            // from just before the 1 s countdown ends until after the game has started.
            final AtomicInteger reconnects = new AtomicInteger();
            final AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread flaky = new Thread(() -> {
                try {
                    while (System.currentTimeMillis() < countdownBegan + 850) Thread.sleep(1);
                    while (System.currentTimeMillis() < countdownBegan + 1600) {
                        connect(experiment, session, third);
                        reconnects.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failed.set(t);
                }
            }, "test-flaky-page");
            flaky.setDaemon(true);
            flaky.start();
            flaky.join(10000);
            String stuck = deadlocked();
            assertNull("round " + round + ": threads deadlocked:" + stuck, stuck);
            assertFalse("round " + round + ": the page's reconnecting never ended", flaky.isAlive());
            assertNull(failed.get());
            assertTrue(reconnects.get() > 0);

            await("round " + round + "'s game", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
            assertEquals("2", value(events(session, "autopilotStart").get(0), "players"));
            assertEquals(1, events(session, "sessionFull").size());
            assertNull(engineValue("g.getVertex('" + third + "')"));
            // A page of theirs that comes back now is told the session is full, and their going is recorded.
            Page back = connect(experiment, session, third);
            assertEquals("sessionFull", back.lastPlayer().get("ended").asText());
            await("their going recorded", () -> {
                for (Event e : events(session, "clientDisconnected")) {
                    if (third.equals(value(e, "clientId"))) return true;
                }
                return false;
            });
            if (round < 2) {
                // The game ends; the autopilot opens the next session.
                engine().eval("gameListener.finish(); null");
                await("round " + round + "'s end", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
            }
        }
    }

    // ---- whose session it is -----------------------------------------------------------------------

    // The steps set up a waiting room and takeover of their own, which the Autopilot page's replace.
    private static final String OWN_SETUP = "autopilot.waitingRoom([minPlayers: 5, waitSeconds: 30])\n"
        + "autopilot.replaceDisconnected([afterSeconds: 99])";

    private AdminSocketsTest.Tab console(User user) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(AdminSocketsTest.userJson()) >= 1);
        return tab;
    }

    private static ObjectNode withTakeover(ObjectNode s, int afterSeconds) {
        ObjectNode replace = (ObjectNode) s.get("replaceDisconnected");
        replace.put("enabled", true);
        replace.put("afterSeconds", afterSeconds);
        return s;
    }

    private static Long statusInstance(User user) {
        JsonNode instance = autopilotStatus(user).get("instance");
        return instance == null || instance.isNull() ? null : instance.get("id").asLong();
    }

    private static Object getAutopilot(String name) throws Exception {
        Field f = Autopilot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** GET /autopilot reports this session as one the researcher opened by hand (status.handSession). */
    private static void assertHandSession(JsonNode status, ExperimentInstance session, User by, boolean openToNewcomers) {
        JsonNode hand = status.get("handSession");
        assertNotNull(status.toString(), hand);
        assertFalse("not reported: " + status, hand.isNull());
        assertEquals(session.id.longValue(), hand.get("id").asLong());
        assertEquals(session.name, hand.get("name").asText());
        assertEquals("RUNNING", hand.get("status").asText());
        assertEquals(by.email, hand.get("owner").asText());
        assertEquals(status.toString(), openToNewcomers, hand.get("openToNewcomers").asBoolean());
        assertEquals(status.toString(), !openToNewcomers, hand.get("gameStarted").asBoolean());
    }

    @Test
    public void aSessionOpenedByHandIsTheResearchersWithTheStepsOwnSettings() throws Exception {
        final User researcher = user("byhand");
        final Experiment experiment = experimentOf(researcher, "Opened by hand");
        createStep(experiment, "Setup", OWN_SETUP);
        assertEquals(OK, status(post(researcher, withTakeover(settings(experiment, 2, null, 1), 45))));
        final ExperimentInstance auto = openSession(experiment);
        // The autopilot's own: the console calls it so, and the Autopilot page's settings apply in it.
        assertEquals(auto.id, statusInstance(researcher));
        assertEquals(2, engineValue("autopilot.room.minPlayers"));
        assertEquals(45, engineValue("autopilot.replace.afterSeconds"));

        // The researcher opens a session by hand ("Stop it and create"): the autopilot pauses, and the
        // new session is theirs.
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("LaunchGame", "name", "Test players", "parameters", Json.newObject());
        await("their session", () -> sessions(experiment).size() == 2
            && sessions(experiment).get(1).status == ExperimentInstance.Status.RUNNING
            && engineValue("autopilot.room") != null);
        final ExperimentInstance byHand = sessions(experiment).get(1);
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.get("paused").asBoolean());
        assertTrue("not reported as the autopilot's: " + st, st.get("instance").isNull());
        assertEquals("the steps' own waiting room", 5, engineValue("autopilot.room.minPlayers"));
        assertEquals("the steps' own takeover", 99, engineValue("autopilot.replace.afterSeconds"));
        String paused = st.get("problem").asText();
        assertTrue(paused, paused.contains("its session \"" + auto.name + "\" ended when " + researcher.email + " launched another session"));
        // It is reported on its own, and the reason the autopilot is paused says that it is open and
        // where the study's links lead: the Autopilot page used to say that no session was open.
        assertHandSession(st, byHand, researcher, true);
        String meanwhile = " Meanwhile \"Test players\", a session of this experiment that " + researcher.email
            + " opened by hand, is open, and the participant links lead to it. Resume hands it to the autopilot.";
        assertTrue(paused, paused.endsWith(meanwhile));
        assertEquals("paused", st.get("problemKind").asText());
        // Done (its one session finished) says the same, with the button that then hands it over.
        Object pausedFor = getAutopilot("pausedBecause");
        setAutopilot("pausedBecause", null);
        setAutopilot("doneBecause", "Its session \"" + auto.name + "\" has finished, and opening the next session (continuous) is off");
        st = autopilotStatus(researcher);
        assertEquals("done", st.get("problemKind").asText());
        assertTrue(st.get("problem").asText(), st.get("problem").asText().endsWith(
            meanwhile.replace("Resume hands", "Open another session hands")));
        setAutopilot("doneBecause", null);
        setAutopilot("pausedBecause", pausedFor);

        // Stopping it leaves the reason the autopilot paused for, which is about its own session.
        tab.send("StopGame", "id", byHand.id);
        await("stopped", () -> ExperimentInstance.findById(byHand.id).status == ExperimentInstance.Status.STOPPED);
        st = autopilotStatus(researcher);
        assertEquals(paused.substring(0, paused.length() - meanwhile.length()), st.get("problem").asText());
        assertTrue(st.get("instance").isNull());
        assertTrue(st.toString(), st.get("handSession").isNull());

        // Resume: it opens a session of its own again.
        assertEquals(OK, status(callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", researcher.uid))));
        await("its next session", () -> sessions(experiment).size() == 3 && Autopilot.openInstance(experiment.id) != null
            && Integer.valueOf(2).equals(engineValue("autopilot.room.minPlayers")));
        assertEquals(sessions(experiment).get(2).id, statusInstance(researcher));
        assertEquals(45, engineValue("autopilot.replace.afterSeconds"));
    }

    @Test
    public void resumeHandsItTheSessionOpenedByHandWithThePagesSettings() throws Exception {
        final User researcher = user("handover");
        final Experiment experiment = experimentOf(researcher, "Handed over");
        createStep(experiment, "Setup", OWN_SETUP);
        assertEquals(OK, status(post(researcher, withTakeover(settings(experiment, 2, null, 1), 45))));
        openSession(experiment);
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("LaunchGame", "name", "Mine", "parameters", Json.newObject());
        await("their session", () -> sessions(experiment).size() == 2
            && sessions(experiment).get(1).status == ExperimentInstance.Status.RUNNING
            && Integer.valueOf(5).equals(engineValue("autopilot.room.minPlayers")));
        final ExperimentInstance byHand = sessions(experiment).get(1);

        // Resume while it runs: the autopilot keeps that one open as its own, with its page's settings.
        assertEquals(OK, status(callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", researcher.uid))));
        await("its settings applied", () -> Integer.valueOf(2).equals(engineValue("autopilot.room.minPlayers")));
        assertEquals(byHand.id, statusInstance(researcher));
        assertEquals(45, engineValue("autopilot.replace.afterSeconds"));
        assertEquals("no other session opened", 2, sessions(experiment).size());

        // Stopping it now pauses the autopilot, naming it as its session.
        tab.send("StopGame", "id", byHand.id);
        await("paused", () -> autopilotStatus(researcher).get("paused").asBoolean());
        String paused = autopilotStatus(researcher).get("problem").asText();
        assertTrue(paused, paused.contains("its session \"Mine\" was stopped by " + researcher.email));
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

    private static boolean selected(User user, Experiment experiment) {
        Experiment selection = User.findByEmail(user.email).selectedExperiment;
        return selection != null && experiment.id.equals(selection.id);
    }

    /** The autopilot's periodic check, now. */
    private static void watchdog() throws Exception {
        java.lang.reflect.Method check = Autopilot.class.getDeclaredMethod("watchdog");
        check.setAccessible(true);
        check.invoke(null);
    }

    private static String fullScreenText(boolean prolific, Long experimentId) throws Exception {
        java.lang.reflect.Method screen = ScriptBoard.class.getDeclaredMethod("sessionFullScreen", boolean.class, Long.class);
        screen.setAccessible(true);
        return ((JsonNode) screen.invoke(null, prolific, experimentId)).get("player").get("text").asText();
    }

    private static void assertNoSessionOpen(String experimentLink, String prolificLink) {
        String text = page(experimentLink);
        assertTrue(text, text.contains("No session is open right now"));
        assertTrue(text, text.contains("Please try again in a few minutes, or contact the researcher."));
        assertFalse("a promise the autopilot can't keep: " + text, text.contains("in a moment") || text.contains("in progress"));
        assertFalse(text, text.contains("http-equiv=\"refresh\""));
        String forProlific = page(prolificLink);
        assertTrue(forProlific, forProlific.contains("There is no session taking participants at the moment. Please return the study on Prolific."));
    }

    @Test
    public void theStudyLinkPromisesNoSessionWhileSomethingKeepsTheAutopilotFromOpeningOne() throws Throwable {
        final User researcher = user("heldup");
        final Experiment experiment = experimentOf(researcher, "Held up");
        final Experiment other = experimentOf(researcher, "Someone else's");
        String login = "/play/" + experiment.id + "/login";
        String prolific = "/play/" + experiment.id + "/prolific?PROLIFIC_PID=HELD1&STUDY_ID=S&SESSION_ID=X";

        // Another experiment's session holds the engine when the autopilot is turned on: it waits for
        // that session to end, which may take any time.
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("SelectExperiment", "experimentId", other.id);
        await("the selection", () -> selected(researcher, other) && other.id.equals(scriptBoardField("engineExperimentId").get(null)));
        tab.send("LaunchGame", "name", "Pilot B", "parameters", Json.newObject());
        await("Pilot B", () -> sessions(other).size() == 1 && sessions(other).get(0).status == ExperimentInstance.Status.RUNNING);
        ObjectNode s = settings(experiment, 1, 1, 0);
        s.put("nextSessionDelaySeconds", 600);
        assertEquals(OK, status(post(researcher, s)));
        await("waiting for it", () -> autopilotStatus(researcher).get("problem").asText().startsWith("Another session is running"));
        assertNoSessionOpen(login, prolific);

        // That session is stopped, but its researcher has their experiment loaded and a console open:
        // the autopilot leaves the engine to them.
        final ExperimentInstance pilot = sessions(other).get(0);
        tab.send("StopGame", "id", pilot.id);
        await("Pilot B stopped", () -> ExperimentInstance.findById(pilot.id).status == ExperimentInstance.Status.STOPPED);
        watchdog();
        assertTrue(autopilotStatus(researcher).get("problem").asText(), autopilotStatus(researcher).get("problem").asText()
            .contains("is loaded in the engine for " + researcher.email));
        assertNoSessionOpen(login, prolific);

        // Their console closes: the autopilot opens its session, and the link leads there.
        tab.close();
        await("the engine left to the autopilot", () -> autopilotStatus(researcher).get("problem").isNull());
        watchdog();
        final ExperimentInstance session = openSession(experiment);
        assertEquals(SEE_OTHER, status(route(fakeRequest(GET, login))));
        connect(experiment, session, "held-1");
        await("the game", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        assertTrue(page(login).contains("A session is in progress"));
        assertTrue(fullScreenText(false, experiment.id).contains("Join the next session"));

        // The disk where the database is kept is almost full: no next session opens until there is
        // room, so the link no longer says one does, nor does the screen of someone a full room turns away.
        setAutopilot("freeDiskBytes", new java.util.function.Supplier<Long>() {
            public Long get() { return 10L * 1024 * 1024; }
        });
        assertNoSessionOpen(login, prolific);
        String plain = fullScreenText(false, experiment.id);
        assertFalse(plain, plain.contains("href"));
        assertTrue(plain, plain.contains("contact the researcher"));
        assertTrue(fullScreenText(true, experiment.id).contains("Please return the study on Prolific"));
        setAutopilot("freeDiskBytes", REAL_FREE_DISK);
        String again = page(login);
        assertTrue(again, again.contains("A session is in progress") && again.contains("http-equiv=\"refresh\""));

        // A problem the Autopilot page shows (here: the last session it opened reported an error).
        setAutopilot("lastProblem", "The last session it opened reported an error: something");
        assertNoSessionOpen(login, prolific);
    }

    @Test
    public void endingASessionOpenedByHandBetweenItsSessionsLeavesTheAutopilotRunning() throws Exception {
        final User researcher = user("gap");
        final Experiment experiment = experimentOf(researcher, "Between its sessions");
        ObjectNode s = settings(experiment, 1, 1, 0);
        s.put("nextSessionDelaySeconds", 600);
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance auto = openSession(experiment);
        // Its game ends; its next session opens in 10 minutes.
        engine().eval("gameListener.finish(); null");
        await("finished", () -> ExperimentInstance.findById(auto.id).status == ExperimentInstance.Status.FINISHED);

        // Meanwhile a researcher opens a session by hand: theirs, which the console calls nobody else's.
        final AdminSocketsTest.Tab tab = console(researcher);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the selection", () -> selected(researcher, experiment));
        tab.send("LaunchGame", "name", "By hand", "parameters", Json.newObject());
        await("their session", () -> sessions(experiment).size() == 2
            && sessions(experiment).get(1).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance byHand = sessions(experiment).get(1);
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("instance").isNull());
        assertFalse(st.get("paused").asBoolean());
        // The Autopilot page is told of it, apart from its own (status.handSession), and why it opens
        // none meanwhile: it used to say that no session was open and that it would open one shortly,
        // and when the pause between its sessions would end, while the study's links led into that
        // session and the autopilot opened nothing until it had ended.
        assertHandSession(st, byHand, researcher, true);
        assertEquals("waiting", st.get("problemKind").asText());
        assertEquals("\"By hand\", a session of this experiment that " + researcher.email + " opened by hand, is open, and the "
            + "participant links lead to it. The autopilot opens its own session once that one has ended; saving these "
            + "settings hands it to the autopilot.", st.get("problem").asText());
        assertTrue("nothing opens when the pause is over: " + st, st.get("nextOpenAt").isNull());
        Result toIt = route(fakeRequest(GET, "/play/" + experiment.id + "/login"));
        assertEquals(SEE_OTHER, status(toIt));
        assertEquals("/game/" + experiment.id + "/" + byHand.id + "/login", header("Location", toIt));

        // Its game starts. The page says so; the study's links wait for the autopilot's next session,
        // which opens once that game has ended (the note isn't a reason that holds that one up).
        tab.send("RunGame");
        await("its game", () -> Boolean.TRUE.equals(ExperimentInstance.findById(byHand.id).hasStarted));
        st = autopilotStatus(researcher);
        assertHandSession(st, byHand, researcher, false);
        assertTrue(st.get("problem").asText(), st.get("problem").asText().startsWith("\"By hand\", a session of this experiment that "
            + researcher.email + " opened by hand, is running. The autopilot opens its own session once that one has ended"));
        String during = page("/play/" + experiment.id + "/login");
        assertTrue(during, during.contains("A session is in progress") && during.contains("http-equiv=\"refresh\""));

        // They open another over it ("Stop it and create"): a rebuild of the engine that ends theirs.
        // The console's question says nothing of the autopilot (status.instance names its own session
        // alone), and the autopilot doesn't pause: it used to, and its links then led nowhere.
        tab.send("LaunchGame", "name", "Another", "parameters", Json.newObject());
        await("theirs ended, another running", () -> ExperimentInstance.findById(byHand.id).status == ExperimentInstance.Status.STOPPED
            && sessions(experiment).size() == 3 && sessions(experiment).get(2).status == ExperimentInstance.Status.RUNNING);
        Thread.sleep(300);
        st = autopilotStatus(researcher);
        assertFalse("paused for a session it didn't open: " + st, st.get("paused").asBoolean());
        assertEquals(st.toString(), "waiting", st.get("problemKind").asText());
        assertTrue(st.get("problem").asText(), st.get("problem").asText().startsWith("\"Another\", a session of this experiment"));
        assertHandSession(st, sessions(experiment).get(2), researcher, true);

        // Stopping that one doesn't pause it either, and once the pause between its sessions is over
        // (here: now), it opens its next one.
        final ExperimentInstance another = sessions(experiment).get(2);
        tab.send("StopGame", "id", another.id);
        await("stopped", () -> ExperimentInstance.findById(another.id).status == ExperimentInstance.Status.STOPPED);
        Thread.sleep(300);
        st = autopilotStatus(researcher);
        assertFalse(st.toString(), st.get("paused").asBoolean());
        assertTrue(st.toString(), st.get("problem").isNull());
        assertTrue(st.toString(), st.get("handSession").isNull());
        setAutopilot("nextOpenAt", 0L);
        watchdog();
        await("its next session", () -> sessions(experiment).size() == 4 && Autopilot.openInstance(experiment.id) != null);
        assertEquals(sessions(experiment).get(3).id, statusInstance(researcher));
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
