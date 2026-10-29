import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.WebSocket;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The autopilot's guards of its own session (Autopilot.guardOwnSession), on real sessions. Its game
 * may run maxSessionMinutes at most: a longer one is ended, its participants told it ran out of time
 * (a completion code on screen stays), the session finished, and the next one opens; three in a row
 * pause it, and a game that comes to its end starts the count again. Once a step failure has been
 * recorded in it, a game that has nothing left to wait for is ended as a failure that stalls it is,
 * its participants told it had to stop because of a problem with the study; one that waits for a
 * round's time, a timer, a choice or a participant's input (a form they have yet to finish, a chat
 * that is on) goes on, and a form they finished or a chat switched off is nothing to wait for. A start
 * the console's Start made in its own session that didn't get going ends that session. Stop, and a
 * session stopped because its steps didn't load, turn the computer players off, so a takeover after
 * it never plays the stopped game on. The clock and the stall time are moved here, not waited for.
 */
public class AutopilotGuardTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();
    // how far the autopilot's clock is ahead of the real one
    private volatile long ahead = 0L;

    @Before
    public void guardsOnTheirTestClock() throws Exception {
        setStatic("clock", (LongSupplier) () -> System.currentTimeMillis() + ahead);
        setStatic("stallMs", 300L);
    }

    @After
    public void cleanUp() throws Throwable {
        Thread guard = (Thread) getStatic("guardEnd");
        if (guard != null) guard.join(10000);
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
        Thread.sleep(300);   // let a launch that is under way finish before the tables are emptied
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
        setStatic("launchRequestedAt", 0L);
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession", "notice", "quietSession", "endingTooLong"}) {
            setStatic(name, null);
        }
        setStatic("nextOpenAt", 0L);
        setStatic("noticeAt", 0L);
        setStatic("tooLongInARow", 0);
        setStatic("stallMs", 60000L);
        setStatic("guardWaitMs", 5000L);
        setStatic("clock", (LongSupplier) System::currentTimeMillis);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = Autopilot.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Object getStatic(String name) throws Exception {
        Field f = Autopilot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void watchdog() throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("watchdog");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    /** The periodic check, every 100 ms, until the condition holds (at most 15 s). */
    private static void watchUntil(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            watchdog();
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + what);
    }

    /** The periodic check, every 100 ms for {@code ms}: the session must stay running throughout. */
    private static void watchWhileRunning(String why, ExperimentInstance session, long ms) throws Exception {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            watchdog();
            assertEquals(why, ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
            Thread.sleep(100);
        }
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

    private static void own(User user, Experiment experiment) {
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
    }

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static Result resume(User user) {
        return callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", user.uid));
    }

    private static JsonNode autopilotStatus(User user) {
        return Json.parse(contentAsString(route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid)))).get("status");
    }

    private static List<ExperimentInstance> sessions(Experiment experiment) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).orderBy("id asc").findList();
    }

    private static int running(Experiment experiment) {
        int n = 0;
        for (ExperimentInstance i : sessions(experiment)) if (i.status == ExperimentInstance.Status.RUNNING) n++;
        return n;
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

    /** Settings that run the experiment with a waiting room (the computer takes no seats). */
    private static ObjectNode settings(Experiment experiment, int minPlayers, Integer maxPlayers, Integer maxSessionMinutes) {
        ObjectNode s = Json.newObject();
        s.put("enabled", true);
        s.put("experimentId", experiment.id);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        if (maxSessionMinutes == null) s.putNull("maxSessionMinutes"); else s.put("maxSessionMinutes", maxSessionMinutes);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", minPlayers);
        if (maxPlayers == null) room.putNull("maxPlayers"); else room.put("maxPlayers", maxPlayers);
        room.put("waitSeconds", 30);
        room.putNull("maxWaitSeconds");
        room.put("countdownSeconds", 0);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", false);
        replace.put("afterSeconds", 15);
        replace.put("returnToHuman", true);
        return s;
    }

    /** As if a participant's page had connected: the autopilot's waiting room counts them. */
    private static void arrives(String id) throws Exception {
        engine().eval("def v = g.addPlayer('" + id + "'); v._system.connected = true; autopilot.onConnection('" + id + "', true); null");
    }

    /** A participant's page (its socket's frames, and whether the server closed it). */
    private static final class Page {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { closed = true; }
        }, 0L);

        JsonNode last() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }

        /** The last player frame (the screen it shows). */
        JsonNode lastPlayer() {
            for (int i = frames.size() - 1; i >= 0; i--) {
                if (frames.get(i).has("player")) return frames.get(i);
            }
            return null;
        }

        /** The page's socket closes (Play runs these once it has). */
        void socketClosed() throws Throwable {
            for (F.Callback0 cb : in.closeCallbacks) cb.invoke();
        }

        /** The page sends a player event, as Breadboard.send(eventName, data) does (frontend/core). */
        void send(String playerId, String eventName, ObjectNode data) throws Throwable {
            ObjectNode message = Json.newObject();
            message.put("action", "CustomEvent");
            message.put("playerId", playerId);
            message.put("eventName", eventName);
            message.set("data", data);
            for (F.Callback<JsonNode> cb : in.callbacks) cb.invoke(message);
        }
    }

    /** What a form's page sends when Next, or Done on its last page, is pressed (Form.vue). */
    private static ObjectNode next(String form) {
        ObjectNode data = Json.newObject();
        data.put("name", form);
        data.putObject("results");
        return data;
    }

    /** How many listeners the scripts' event bus has for a player's event (events.groovy's "__player-<id>-<event>"). */
    private static int listening(String playerId, String event) {
        Object n = engineValue("events.events['__player-" + playerId + "-" + event + "']?.closures?.size() ?: 0");
        return n == null ? -1 : ((Number) n).intValue();
    }

    /** What a page that comes back to a session that has ended is sent (ScriptBoard.endedSessionScreen). */
    private static JsonNode endedScreen(ExperimentInstance session, String clientId) throws Exception {
        java.lang.reflect.Method m = ScriptBoard.class.getDeclaredMethod("endedSessionScreen", Long.class, String.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(null, session.id, clientId);
    }

    private static Page joins(Experiment experiment, ExperimentInstance session, String id) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), id, page.in, page.out);
        return page;
    }

    /** A console tab of the user's, logged in, with the experiment selected. */
    private AdminSocketsTest.Tab console(final User user, final Experiment experiment) throws Exception {
        final AdminSocketsTest.Tab tab = watching(user);
        tab.send("SelectExperiment", "experimentId", experiment.id);
        await("the selection", () -> {
            Experiment selection = User.findByEmail(user.email).selectedExperiment;
            return selection != null && experiment.id.equals(selection.id) && experiment.id.equals(engineExperimentId());
        });
        return tab;
    }

    /** A console tab of the user's, logged in. */
    private AdminSocketsTest.Tab watching(final User user) throws Exception {
        final AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(user.uid);
        tabs.add(tab);
        tab.send("LogIn");
        await("LogIn", () -> tab.count(AdminSocketsTest.userJson()) >= 1);
        return tab;
    }

    /** The autopilot's next session: open, its steps loaded, its waiting room set. */
    private static ExperimentInstance nextSession(final Experiment experiment, final int n) throws Exception {
        await("session " + n, () -> {
            List<ExperimentInstance> all = sessions(experiment);
            ExperimentInstance open = Autopilot.openInstance(experiment.id);
            return all.size() == n && open != null && open.id.equals(all.get(n - 1).id)
                && engineValue("autopilot.room?.minPlayers") != null;
        });
        return sessions(experiment).get(n - 1);
    }

    // ---- the longest session ------------------------------------------------------------------------

    // Every participant gets a choice that ends the game, which nobody makes here: the game waits.
    private static final String WAITS_FOR_A_CHOICE = "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = { }\n"
        + "initStep.done = { roundStep.start() }\n"
        + "roundStep = stepFactory.createStep('RoundStep')\n"
        + "roundStep.run = { g.V.filter { !it.id.startsWith('_') }.each { p -> a.add(p, [name: 'Done', result: { }]) } }\n"
        + "roundStep.done = { roundStep.finish() }";

    /** Everyone makes the choice on their screen: the game comes to its end. */
    private static void everyoneChooses() throws Exception {
        engine().eval("new ArrayList(a.actions.keySet()).each { a.choose(it) }; null");
    }

    @Test
    public void aGameThatRunsLongerThanTheLongestSessionIsEndedAndTheNextOneOpens() throws Throwable {
        final User researcher = user("toolong");
        final Experiment experiment = createExperiment("Runs too long");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 2, 2, 1);
        s.put("nextSessionDelaySeconds", 2);
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        final AdminSocketsTest.Tab tab = watching(researcher);

        Page first = joins(experiment, session, "tl-1");
        Page second = joins(experiment, session, "tl-2");
        await("the start", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted)
            && ((Number) engineValue("a.actions.size()")).intValue() == 2);
        // The second came from Prolific, and the experiment has given them their completion code.
        engine().eval("def v = g.getVertex('tl-2'); recruitment.registerProlific(v); "
            + "recruitment.completeProlific(v, [completionCode: 'C0DE42']); null");

        // Within the limit nothing happens.
        watchdog();
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);

        ahead = 61000L;
        final int engineNews = tab.count(f -> f.has("engine"));
        watchdog();
        await("the session to be finished", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);

        List<Event> tooLong = events(session, "sessionTooLong");
        assertEquals(1, tooLong.size());
        assertEquals("1", value(tooLong.get(0), "minutes"));
        assertEquals("1", value(tooLong.get(0), "limit"));
        // Its participants are told it ran out of time; the one with a completion code keeps it.
        assertEquals(first.last().toString(), "outOfTime", first.last().get("player").get("ended").asText());
        assertTrue(first.last().toString(), first.last().get("player").get("text").asText().contains("ran out of time"));
        assertTrue(first.closed);
        assertEquals("C0DE42", second.lastPlayer().get("player").get("_system").get("recruitment").get("completionCode").asText());
        assertFalse(second.lastPlayer().get("player").has("ended"));
        // told the session is over all the same (so it stops reconnecting), in the way that keeps its own end
        assertEquals("outOfTime", second.last().get("sessionOver").get("player").get("ended").asText());
        assertTrue(second.closed);
        // and so do the pages that come back to it
        assertEquals("outOfTime", endedScreen(session, "tl-1").get("player").get("ended").asText());
        assertEquals("C0DE42", endedScreen(session, "tl-2").get("player").get("_system").get("recruitment")
            .get("completionCode").asText());
        // The game is frozen: no computer player picks, no timer runs.
        assertEquals(Boolean.FALSE, engineValue("a.ai.isOn"));
        assertEquals(0, ((Number) engineValue("timers.timers.size()")).intValue());
        await("every console told what the engine runs", () -> tab.count(f -> f.has("engine")) > engineNews);

        // The page says what happened, and the next session opens after the pause.
        JsonNode st = autopilotStatus(researcher);
        assertFalse(st.toString(), st.get("paused").asBoolean());
        String notice = st.get("notice").asText();
        assertEquals("Session \"" + session.name + "\" was ended after 1 minute, the longest a session may run. "
            + "Its participants were told that the session ran out of time.", notice);
        assertTrue(st.get("noticeAt").asText().endsWith("Z"));
        final ExperimentInstance next = nextSession(experiment, 2);
        assertEquals(ExperimentInstance.Status.RUNNING, next.status);
        assertEquals("the notice stays as the next session opens", notice, autopilotStatus(researcher).get("notice").asText());

        // Saving the settings (here: a longer limit) is the researcher having seen it.
        s.put("maxSessionMinutes", 90);
        assertEquals(OK, status(post(researcher, s)));
        assertTrue(autopilotStatus(researcher).get("notice").isNull());
    }

    @Test
    public void theEndReachesTheResearchersPreviewsOfAComputerPlayerAndOfAParticipant() throws Throwable {
        final User researcher = user("previews");
        final Experiment experiment = createExperiment("Watched as it runs too long");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { g.addAI(a, 1, { p -> }) }\n"
            + "initStep.done = { roundStep.start() }\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.run = { g.V.filter { !it.id.startsWith('_') }.each { p -> a.add(p, [name: 'Done', result: { }]) } }\n"
            + "roundStep.done = { roundStep.finish() }");
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 1, 1, 1);
        s.put("continuous", false);   // (no next session as the test ends)
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        Page participant = joins(experiment, session, "pv-1");
        await("the start", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted)
            && ((Number) engineValue("a.actions.size()")).intValue() == 1 && engineValue("g.getVertex('_1')") != null);
        // The researcher watches the computer player (its Client never takes a seat) and the participant.
        Page computer = previews(experiment, session, "_1");
        Page watcher = previews(experiment, session, "pv-1");
        assertTrue(computer.frames.get(0).path("bbWatching").asBoolean());
        assertTrue(watcher.frames.get(0).path("bbWatching").asBoolean());

        ahead = 61000L;
        watchdog();
        await("the session to be finished", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
        // Every page is told and closed: the computer player's preview (it used to be left open with a
        // frozen view, as its Client has no seat), and the participant's, which watches too.
        for (Page page : new Page[]{participant, watcher, computer}) {
            assertEquals(page.frames.toString(), "outOfTime", page.lastPlayer().get("player").get("ended").asText());
            assertTrue(page.closed);
        }
    }

    /** A researcher's preview of a player (a signed-in researcher's socket, as ClientController makes it). */
    private static Page previews(Experiment experiment, ExperimentInstance session, String id) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), id, page.in, page.out, true);
        return page;
    }

    @Test
    public void threeSessionsInARowThatRunTooLongPauseItAndAGameThatEndsStartsTheCountAgain() throws Exception {
        final User researcher = user("inarow");
        final Experiment experiment = createExperiment("Never ends, mostly");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 1, 1, 1))));
        // Every game has run longer than a minute as soon as it starts.
        ahead = 61000L;

        // too long, then one that ends, then too long twice: not three in a row
        String[] ends = {"too long", "finishes", "too long", "too long"};
        for (int i = 0; i < ends.length; i++) {
            final ExperimentInstance session = nextSession(experiment, i + 1);
            arrives("row-" + i);
            await("game " + (i + 1), () -> ((Number) engineValue("a.actions.size()")).intValue() == 1);
            if ("finishes".equals(ends[i])) {
                everyoneChooses();
            } else {
                watchdog();
            }
            await("session " + (i + 1) + " finished", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
            assertEquals(ends[i], "too long".equals(ends[i]) ? 1 : 0, events(session, "sessionTooLong").size());
            assertFalse("session " + (i + 1), autopilotStatus(researcher).get("paused").asBoolean());
        }

        // The third in a row pauses it instead of opening another.
        final ExperimentInstance third = nextSession(experiment, 5);
        arrives("row-5");
        await("game 5", () -> ((Number) engineValue("a.actions.size()")).intValue() == 1);
        watchdog();
        await("session 5 finished", () -> ExperimentInstance.findById(third.id).status == ExperimentInstance.Status.FINISHED);
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("paused").asBoolean());
        String problem = st.get("problem").asText();
        assertTrue(problem, problem.contains("3 sessions in a row ran longer than 1 minute (the longest a session may run) and were ended, "
            + "the last one \"" + third.name + "\""));
        assertTrue(problem, problem.contains("Resume"));
        assertTrue(st.get("notice").asText().contains(third.name));
        watchdog();
        Thread.sleep(500);
        assertEquals("no other session opens", 5, sessions(experiment).size());

        // Resume: it opens sessions again, the notice is gone, and the count starts again.
        assertEquals(OK, status(resume(researcher)));
        assertTrue(autopilotStatus(researcher).get("notice").isNull());
        nextSession(experiment, 6);
        assertEquals(0, getStatic("tooLongInARow"));
    }

    @Test
    public void theCheckNeverWaitsLongForABusyEngineAndTheEndFollowsOnceItIsFree() throws Throwable {
        final User researcher = user("busy");
        final Experiment experiment = createExperiment("Busy engine");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 1, 1, 1);
        s.put("continuous", false);
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("busy-1");
        await("the game", () -> ((Number) engineValue("a.actions.size()")).intValue() == 1);

        // An engine change holds the engine lock (a script that takes its time, say).
        Field lockField = ScriptBoard.class.getDeclaredField("REBUILD_LOCK");
        lockField.setAccessible(true);
        final Object lock = lockField.get(null);
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread busy = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                try { release.await(); } catch (InterruptedException ignored) {}
            }
        });
        busy.start();
        held.await();
        try {
            setStatic("guardWaitMs", 200L);
            ahead = 61000L;
            long before = System.currentTimeMillis();
            watchdog();
            watchdog();
            assertTrue("the checks didn't wait for the engine", System.currentTimeMillis() - before < 2000);
            assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        } finally {
            release.countDown();
            busy.join();
        }
        // Once the engine is free, the end asked for goes ahead, once.
        await("the session to be finished", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
        Thread.sleep(300);
        assertEquals(1, events(session, "sessionTooLong").size());
    }

    @Test
    public void withNoLimitAGameMayRunAsLongAsItTakes() throws Exception {
        final User researcher = user("nolimit");
        final Experiment experiment = createExperiment("No limit");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 1, 1, null))));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("nolimit-1");
        await("the game", () -> ((Number) engineValue("a.actions.size()")).intValue() == 1);
        ahead = 24L * 3600 * 1000;
        watchdog();
        Thread.sleep(300);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertEquals(0, events(session, "sessionTooLong").size());
    }

    @Test
    public void aSessionAResearcherRunsByHandIsLeftToThem() throws Exception {
        final User researcher = user("byhand");
        final Experiment experiment = createExperiment("Run by hand too");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 2, null, 1))));
        nextSession(experiment, 1);

        // The researcher takes over with a session of their own (the autopilot pauses).
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("their session", () -> sessions(experiment).size() == 2 && running(experiment) == 1
            && sessions(experiment).get(1).status == ExperimentInstance.Status.RUNNING);
        final ExperimentInstance byHand = sessions(experiment).get(1);
        engine().eval("g.addPlayer('hand-1'); null");
        tab.send("RunGame");
        await("their game", () -> engineValue("a.actions.size()") != null && ((Number) engineValue("a.actions.size()")).intValue() == 1);

        ahead = 3L * 3600 * 1000;
        watchdog();
        Thread.sleep(300);
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(byHand.id).status);
        assertEquals(0, events(byHand, "sessionTooLong").size());
    }

    // ---- the stall backstop ------------------------------------------------------------------------

    @Test
    public void anOnLeaveStepThatStartsAnEndStepWhichFailsLeavesNothingToDoAndTheSessionEnds() throws Exception {
        final User researcher = user("leaveend");
        final Experiment experiment = createExperiment("Ends on a leave");
        createStep(experiment, "OnLeaveStep", "onLeaveStep = stepFactory.createNoUserActionStep()\n"
            + "onLeaveStep.run = { playerId -> if (g.V.count() < 2) finishStep.start() }\n"
            + "onLeaveStep.done = { }");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = { }");
        createStep(experiment, "FinishStep", "finishStep = stepFactory.createNoUserActionStep()\n"
            + "finishStep.run = {\n"
            + "  g.V.each { it.text = \"Too few are left. Thank you, ${playerz}\" }\n"
            + "}\n"
            + "finishStep.done = { finishStep.finish() }");
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 2, 2, 120))));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("leave-a");
        arrives("leave-b");
        await("the start", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        // One is dropped (as an idle-drop closure does): the OnLeaveStep's end step fails as it starts.
        engine().eval("g.removePlayer('leave-b'); null");
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        assertEquals("finishStep", value(events(session, "stepFailed").get(0), "step"));

        // The round still waits for its time: nothing ends.
        watchWhileRunning("the round's time is still to come", session, 1000);
        // Once it is over, the game has nothing left to do.
        watchUntil("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);

        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("paused").asBoolean());
        String problem = st.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"finishStep\" failed during its session \"" + session.name
            + "\" (line 3: No such property: playerz"));
        assertTrue(problem, problem.contains("and then the game had nothing left to do, so that session was stopped"));
        List<Event> stalled = events(session, "sessionStalled");
        assertEquals(1, stalled.size());
        assertEquals("finishStep", value(stalled.get(0), "step"));
        assertEquals(Boolean.FALSE, engineValue("a.ai.isOn"));
        assertNull("its link leads nowhere now", Autopilot.openInstance(experiment.id));
        watchdog();
        Thread.sleep(300);
        assertEquals("nothing else opens until Resume", 1, sessions(experiment).size());
    }

    @Test
    public void aDoneThatStartsASideStepAndThenFailsBeforeTheNextRoundEndsTheSessionOnceTheSideStepIsOver() throws Exception {
        final User researcher = user("sidestep");
        final Experiment experiment = createExperiment("Side step");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "round2Starts = 0\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = {\n"
            + "  noticeStep.start()\n"
            + "  println \"round ${roundd} done\"\n"
            + "  round2Step.start()\n"
            + "}\n"
            + "noticeStep = stepFactory.createStep('NoticeStep')\n"
            + "noticeStep.duration = 1.5\n"
            + "noticeStep.run = { }\n"
            + "noticeStep.done = { }\n"
            + "round2Step = stepFactory.createStep('Round2Step')\n"
            + "round2Step.duration = 60\n"
            + "round2Step.run = { round2Starts++ }\n"
            + "round2Step.done = { }");
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 1, 1, 120))));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("side-1");
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        // The notice got going, so the failure didn't stall the game by the rule: it waits for the
        // notice's time.
        watchWhileRunning("the notice's time is still to come", session, 600);
        watchUntil("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals(0, engineValue("round2Starts"));
        String problem = autopilotStatus(researcher).get("problem").asText();
        assertTrue(problem, problem.contains("the step \"RoundStep\" failed during its session \"" + session.name
            + "\" (line 7: No such property: roundd"));
        assertTrue(problem, problem.contains("nothing left to do"));
    }

    @Test
    public void aClockAGameStartHandlerStartedDoesNotKeepAFailedFirstRoundGoing() throws Exception {
        final User researcher = user("clock");
        final Experiment experiment = createExperiment("Game clock");
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
        createStep(experiment, "ClockStep", "clockStep = stepFactory.createStep('ClockStep')\n"
            + "clockStep.duration = 60\n"
            + "clockStep.run = { }\n"
            + "clockStep.done = { }\n"
            + "events.on('bb:game-start', { info -> clockStep.start() })");
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 2, null, 120);
        ((ObjectNode) s.get("waitingRoom")).put("enabled", false);
        assertEquals(OK, status(post(researcher, s)));
        await("the session", () -> sessions(experiment).size() == 1 && running(experiment) == 1);
        await("its steps", () -> engineValue("roundStep") != null && engineValue("clockStep") != null);
        final ExperimentInstance session = sessions(experiment).get(0);
        engine().eval("g.addPlayer('clock-a'); null");
        Thread.sleep(300);
        engine().eval("g.addPlayer('clock-b'); null");
        // The clock got going as the game started, but the game didn't: the failure stalls it at once,
        // with no need for the backstop.
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals(1, events(session, "ClockStepStart").size());
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("paused").asBoolean());
        assertTrue(st.get("problem").asText(), st.get("problem").asText().contains("the step \"RoundStep\" failed"));
        assertEquals(0, events(session, "sessionStalled").size());
    }

    @Test
    public void aGameThatGoesOnAfterAFailureIsNotEndedWhileItWaitsForTimeTimersChoicesOrInput() throws Exception {
        final User researcher = user("goeson");
        final Experiment experiment = createExperiment("Goes on");
        // Round 1 ends on its timer; its done starts round 2, then has a typo (recorded; the game goes
        // on). Round 2 waits for its time, then a timer starts round 3 a moment later, which waits for
        // a choice, then for a participant's input (player.on, as a form does), then the game ends.
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }");
        createStep(experiment, "RoundStep", "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.duration = 0.3\n"
            + "roundStep.run = { }\n"
            + "roundStep.done = {\n"
            + "  round2Step.start()\n"
            + "  println \"round ${roundd} done\"\n"
            + "}\n"
            + "round2Step = stepFactory.createStep('Round2Step')\n"
            + "round2Step.duration = 1\n"
            + "round2Step.run = { }\n"
            + "round2Step.done = { timers.newTimer().runAfter(1000) { round3Step.start() } }\n"
            + "round3Step = stepFactory.createStep('Round3Step')\n"
            + "round3Step.run = { g.V.filter { !it.id.startsWith('_') }.each { p -> a.add(p, [name: 'Next', result: { }]) } }\n"
            + "round3Step.done = { g.V.filter { !it.id.startsWith('_') }.each { p -> p.on('f-next', { v, data -> round3Step.finish() }) } }");
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 1, 1, 120);
        s.put("continuous", false);   // (no next session as the test ends)
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("on-1");
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        assertEquals("RoundStep", value(events(session, "stepFailed").get(0), "step"));

        // round 2's time, then the timer between the rounds, then round 3's choice
        watchWhileRunning("a round, a timer or a choice is pending", session, 2600);
        await("round 3", () -> ((Number) engineValue("a.actions.size()")).intValue() == 1);
        watchWhileRunning("a choice is on screen", session, 500);
        everyoneChooses();
        // now it listens for the participant's input
        watchWhileRunning("it listens for the participant", session, 700);
        engine().eval("events.emit('CustomEvent', [playerId: 'on-1', eventName: 'f-next', data: [:]], [clientId: 'on-1']); null");
        await("the end of the game", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.FINISHED);
        assertFalse(autopilotStatus(researcher).get("paused").asBoolean());
        assertEquals(0, events(session, "sessionStalled").size());
    }

    @Test
    public void aChoiceLeftToAPlayerRemovedSinceIsNothingToWaitFor() throws Exception {
        final User researcher = user("dangling");
        final Experiment experiment = createExperiment("Dangling choice");
        // The OnLeaveStep has a typo (recorded; the game goes on). The round waits for everyone's
        // choice, also the one left to a player who was removed.
        createStep(experiment, "OnLeaveStep", "onLeaveStep = stepFactory.createNoUserActionStep()\n"
            + "onLeaveStep.run = { playerId -> println \"left: ${leftt}\" }\n"
            + "onLeaveStep.done = { }");
        createStep(experiment, "InitStep", WAITS_FOR_A_CHOICE);
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 2, 2, 120))));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("dangle-a");
        arrives("dangle-b");
        await("the round", () -> ((Number) engineValue("a.actions.size()")).intValue() == 2);
        engine().eval("g.removePlayer('dangle-b'); null");
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        assertEquals("onLeaveStep", value(events(session, "stepFailed").get(0), "step"));
        watchWhileRunning("the one still here has a choice to make", session, 700);

        engine().eval("a.choose(a.actions.find { it.value.player.id == 'dangle-a' }.key); null");
        assertEquals("the round still waits for the removed player", 1, ((Number) engineValue("a.actions.size()")).intValue());
        watchUntil("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        String problem = autopilotStatus(researcher).get("problem").asText();
        assertTrue(problem, problem.contains("the step \"onLeaveStep\" failed") && problem.contains("nothing left to do"));
    }

    @Test
    public void aGameWithNothingToDoButNoFailureIsLeftToTheLongestSession() throws Exception {
        final User researcher = user("nofailure");
        final Experiment experiment = createExperiment("Quiet but fine");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { }");
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 1, 1, 120))));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("quiet-1");
        await("the start", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        watchWhileRunning("no failure was recorded", session, 1000);
        assertEquals(0, events(session, "sessionStalled").size());
    }

    // A one-page consent form (groovy/form.groovy) for everyone; once a participant is done with it,
    // a round whose done starts a side step and then fails before the next round, as above.
    private static final String CONSENT_THEN_A_ROUND_THAT_FAILS = "consent = new Form([name: 'consent'])\n"
        + "consent.addPage([title: 'Consent', blocks: [[type: 'html', content: 'Do you agree to take part?']]])\n"
        + "consent.onDone { p -> roundStep.start() }\n"
        + "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = {\n"
        + "  g.V.filter { !it.id.startsWith('_') }.each { consent.addPlayer(it) }\n"
        + "  consent.start()\n"
        + "}\n"
        + "initStep.done = { }\n"
        + "roundStep = stepFactory.createStep('RoundStep')\n"
        + "roundStep.duration = 0.3\n"
        + "roundStep.run = { }\n"
        + "roundStep.done = {\n"
        + "  noticeStep.start()\n"
        + "  println \"round ${roundd} done\"\n"
        + "  round2Step.start()\n"
        + "}\n"
        + "noticeStep = stepFactory.createStep('NoticeStep')\n"
        + "noticeStep.duration = 0.5\n"
        + "noticeStep.run = { }\n"
        + "noticeStep.done = { }\n"
        + "round2Step = stepFactory.createStep('Round2Step')\n"
        + "round2Step.duration = 60\n"
        + "round2Step.run = { }\n"
        + "round2Step.done = { }";

    private static final String[] FORM_EVENTS = {"f-next", "f-prev", "f-seek", "f-fetch"};

    @Test
    public void aParticipantWhoFinishedAConsentFormLeavesNothingToWaitForSoTheStalledGameEnds() throws Throwable {
        final User researcher = user("consent");
        final Experiment experiment = createExperiment("Consent first");
        createStep(experiment, "InitStep", CONSENT_THEN_A_ROUND_THAT_FAILS);
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 1, 1, 120))));
        final ExperimentInstance session = nextSession(experiment, 1);
        Page page = joins(experiment, session, "consent-1");
        await("the consent form", () -> Boolean.TRUE.equals(engineValue("g.getVertex('consent-1')?.private?.forms?.consent != null")));
        for (String event : FORM_EVENTS) assertEquals(event, 1, listening("consent-1", event));

        // They press Done on its only page: the round starts, and its done fails after the notice got going.
        page.send("consent-1", "f-next", next("consent"));
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        assertEquals("RoundStep", value(events(session, "stepFailed").get(0), "step"));
        // Done with the form, they have none of its listeners left.
        assertEquals(Boolean.FALSE, engineValue("'forms' in g.getVertex('consent-1').private"));
        for (String event : FORM_EVENTS) assertEquals(event, 0, listening("consent-1", event));

        // Once the notice's time is over, the game has nothing left to wait for: the backstop ends it.
        watchUntil("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals(1, events(session, "sessionStalled").size());
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("paused").asBoolean());
        String problem = st.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"RoundStep\" failed during its session \"" + session.name + "\"")
            && problem.contains("No such property: roundd") && problem.contains("nothing left to do"));
        // Its participant is told the session had to stop because of a problem with the study: no
        // researcher stopped it. A page that comes back gets the same.
        JsonNode screen = page.lastPlayer().get("player");
        assertEquals(screen.toString(), "problem", screen.get("ended").asText());
        assertTrue(screen.toString(), screen.get("text").asText().contains("It had to stop because of a problem with the study."));
        assertFalse(screen.toString(), screen.get("text").asText().contains("researcher stopped"));
        assertTrue(page.closed);
        assertEquals("problem", endedScreen(session, "consent-1").get("player").get("ended").asText());
    }

    // A round whose done shows a one-page survey to everyone and starts a side step, then fails: from
    // then on the game waits for nothing but what its participant does.
    private static final String SHOWS_A_SURVEY_THEN_FAILS = "survey = new Form([name: 'survey'])\n"
        + "survey.addPage([title: 'Survey', blocks: [[type: 'html', content: 'Thank you!']]])\n"
        + "chat = new ChatManager()\n"
        + "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = { }\n"
        + "initStep.done = { roundStep.start() }\n"
        + "roundStep = stepFactory.createStep('RoundStep')\n"
        + "roundStep.duration = 0.3\n"
        + "roundStep.run = { }\n"
        + "roundStep.done = {\n"
        + "  g.V.filter { !it.id.startsWith('_') }.each { survey.addPlayer(it) }\n"
        + "  survey.start()\n"
        + "  noticeStep.start()\n"
        + "  println \"round ${roundd} done\"\n"
        + "}\n"
        + "noticeStep = stepFactory.createStep('NoticeStep')\n"
        + "noticeStep.duration = 0.3\n"
        + "noticeStep.run = { }\n"
        + "noticeStep.done = { }";

    @Test
    public void aFormStillToFinishOrAChatThatIsOnKeepsItWaitingButListenersLeftOnAfterwardsDoNot() throws Exception {
        final User researcher = user("waitsfor");
        final Experiment experiment = createExperiment("Survey and chat");
        createStep(experiment, "InitStep", SHOWS_A_SURVEY_THEN_FAILS);
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 1, 1, 120);
        s.put("continuous", false);   // (no next session as the test ends)
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        arrives("waits-1");
        await("the failure", () -> events(session, "stepFailed").size() == 1);
        assertEquals("RoundStep", value(events(session, "stepFailed").get(0), "step"));

        // The survey is still to be finished.
        watchWhileRunning("the survey is still to be finished", session, 1000);
        // Chat is on for them, and they finish the survey (its listeners come off): the chat is still on.
        engine().eval("chat.enableTextChat(g.getVertex('waits-1')); null");
        engine().eval("events.emit('CustomEvent', [playerId: 'waits-1', eventName: 'f-next', data: [name: 'survey', results: [:]]], "
            + "[clientId: 'waits-1']); null");
        for (String event : FORM_EVENTS) assertEquals(event, 0, listening("waits-1", event));
        assertEquals(1, listening("waits-1", "chat"));
        watchWhileRunning("chat is on for them", session, 1000);

        // Listeners left on all the same count for nothing: the finished survey's own one put back on,
        // and the chat's once chat is off for them (its state taken away, not by disableTextChat).
        engine().eval("def v = g.getVertex('waits-1'); v.on('f-next', survey.navigationListeners['f-next']); "
            + "v.private.remove('chatState'); null");
        assertEquals(1, listening("waits-1", "f-next"));
        assertEquals(1, listening("waits-1", "chat"));
        watchUntil("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertEquals(1, events(session, "sessionStalled").size());
        assertTrue(autopilotStatus(researcher).get("paused").asBoolean());
    }

    // ---- the console's Start in the autopilot's own session ----------------------------------------

    @Test
    public void aConsoleStartInItsOwnSessionThatDidntGetGoingEndsTheSessionAndPausesIt() throws Exception {
        final User researcher = user("consolestart");
        final Experiment experiment = createExperiment("Started from the console");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = {\n"
            + "  println \"init ${initt}\"\n"
            + "}\n"
            + "initStep.done = { }");
        own(researcher, experiment);
        ObjectNode s = settings(experiment, 2, null, 120);
        ((ObjectNode) s.get("waitingRoom")).put("countdownSeconds", 5);
        assertEquals(OK, status(post(researcher, s)));
        final ExperimentInstance session = nextSession(experiment, 1);
        final AdminSocketsTest.Tab tab = watching(researcher);
        Page page = joins(experiment, session, "cs-a");

        // A researcher watching presses Start on the console: its waiting room is told the game began.
        tab.send("RunGame");
        await("the answer", () -> tab.count(AdminSocketsTest.error("RunGame")) == 1);
        await("the session to be stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        JsonNode st = autopilotStatus(researcher);
        assertTrue(st.toString(), st.get("paused").asBoolean());
        String problem = st.get("problem").asText();
        assertTrue(problem, problem.contains("the step \"InitStep\" failed during its session \"" + session.name
            + "\" (line 3: No such property: initt"));
        List<Event> failed = events(session, "stepFailed");
        assertEquals(1, failed.size());
        assertEquals("InitStep", value(failed.get(0), "step"));
        assertEquals("the console that pressed Start has the error once", 1,
            tab.count(f -> f.has("error") && f.get("error").asText().contains("initt")));
        // Its participant is told the session had to stop because of a problem with the study.
        await("the participant told", () -> page.closed);
        assertEquals(page.last().toString(), "problem", page.lastPlayer().get("player").get("ended").asText());
        assertTrue(page.lastPlayer().get("player").get("text").asText().contains("because of a problem with the study"));
    }

    @Test
    public void aConsoleStartThatDidntGetGoingInASessionRunByHandIsOnlyTakenBack() throws Exception {
        final User researcher = user("handstart");
        final Experiment experiment = createExperiment("Started by hand");
        createStep(experiment, "InitStep", "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = {\n"
            + "  println \"init ${initt}\"\n"
            + "}\n"
            + "initStep.done = { }");
        own(researcher, experiment);
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "by hand", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);
        tab.send("RunGame");
        await("the answer", () -> tab.count(AdminSocketsTest.error("RunGame")) == 1);
        Thread.sleep(500);
        ExperimentInstance now = ExperimentInstance.findById(session.id);
        assertEquals(ExperimentInstance.Status.RUNNING, now.status);
        assertFalse("taken back: it takes people again", Boolean.TRUE.equals(now.hasStarted));
        assertNull("Start works again", engine().get("initStep.start()"));
        assertEquals(0, events(session, "stepFailed").size());
    }

    // ---- the computer players of a stopped game ----------------------------------------------------

    @Test
    public void aStoppedGameStaysStoppedWhenATakeoverOrAComputerPlayerComesAfterTheStop() throws Throwable {
        final User researcher = user("stopai");
        final Experiment experiment = createExperiment("Stopped with a takeover due");
        // A round the computer finishes at once for whoever it plays; the game ends after it.
        createStep(experiment, "InitStep", "picks = 0\n"
            + "picksAtOnce = { p -> a.ai.pickLater(p, 50) { a.ai.chooseFor(p, p.choices[0]) } }\n"
            + "autopilot.replaceDisconnected([afterSeconds: 30, behavior: picksAtOnce])\n"
            + "initStep = stepFactory.createStep('InitStep')\n"
            + "initStep.run = { }\n"
            + "initStep.done = { roundStep.start() }\n"
            + "roundStep = stepFactory.createStep('RoundStep')\n"
            + "roundStep.run = { g.V.filter { !it.id.startsWith('_') }.each { p -> a.add(p, [name: 'Next', result: { picks++ }]) } }\n"
            + "roundStep.done = { roundStep.finish() }");
        own(researcher, experiment);
        final AdminSocketsTest.Tab tab = console(researcher, experiment);
        tab.send("LaunchGame", "name", "to be stopped", "parameters", Json.newObject());
        await("the session", () -> running(experiment) == 1);
        final ExperimentInstance session = sessions(experiment).get(0);
        Page first = joins(experiment, session, "stop-1");
        Page second = joins(experiment, session, "stop-2");
        tab.send("RunGame");
        await("the round", () -> ((Number) engineValue("a.actions.size()")).intValue() == 2);

        // The first page goes just before the stop: its seat's takeover is due after it. (Its page
        // going afterwards would change nothing: the session's participants are gone by then.)
        first.socketClosed();
        assertEquals(Boolean.TRUE, engineValue("autopilot.takeoverTimers.containsKey('stop-1')"));
        tab.send("StopGame", "id", session.id);
        await("stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        // The takeover comes 0.2 s after the stop (brought forward from its 30 s, so that it surely
        // comes after). A script then hands the other seat to the computer.
        engine().eval("autopilot.replace.afterSeconds = 0.2; autopilot.handleDeparture('stop-1', false); "
            + "a.ai.add(g.getVertex('stop-2'), picksAtOnce); null");
        Thread.sleep(1500);
        assertEquals("the computer made no pick", 0, engineValue("picks"));
        assertEquals(Integer.valueOf(1), engineValue("g.getVertex('stop-1').ai"));
        assertEquals("still stopped, so it can be resumed", ExperimentInstance.Status.STOPPED,
            ExperimentInstance.findById(session.id).status);
        assertTrue(second.closed);
        // (a researcher stopped it, and its pages say so)
        assertEquals("stopped", second.lastPlayer().get("player").get("ended").asText());
        assertTrue(second.lastPlayer().get("player").get("text").asText().contains("The researcher stopped this session"));
    }

    @Test
    public void aSessionStoppedBecauseItsStepsDidntLoadLeavesTheComputerPlayersOff() throws Exception {
        final User researcher = user("noload");
        final Experiment experiment = createExperiment("Doesn't load");
        createStep(experiment, "InitStep", "NETWORK_SIZE = 3\nSEATS = five");
        own(researcher, experiment);
        assertEquals(OK, status(post(researcher, settings(experiment, 2, null, 120))));
        await("the session to be stopped", () -> sessions(experiment).size() == 1
            && sessions(experiment).get(0).status == ExperimentInstance.Status.STOPPED);
        assertEquals(Boolean.FALSE, engineValue("a.ai.isOn"));
    }
}
