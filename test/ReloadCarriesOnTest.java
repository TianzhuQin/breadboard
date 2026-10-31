import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Vertex;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    // Threads acting as participants while the engine changes (atStage), and what went wrong in them.
    private final List<Thread> actors = new CopyOnWriteArrayList<>();
    private final List<Throwable> actorFailures = new CopyOnWriteArrayList<>();

    @After
    @SuppressWarnings("unchecked")
    public void cleanUp() throws Throwable {
        scriptBoardField("changeHook").set(null, null);
        for (Thread t : actors) t.join(10000);
        actors.clear();
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
        for (String name : new String[]{"pausedBecause", "doneBecause", "lastProblem", "ownSession", "openedSession"}) {
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

        /** The page sends this, as the participant's page does. */
        void send(String action, Object... pairs) {
            ObjectNode m = Json.newObject();
            m.put("action", action);
            for (int i = 0; i < pairs.length; i += 2) {
                m.put((String) pairs[i], Json.toJson(pairs[i + 1]));
            }
            for (F.Callback<JsonNode> cb : in.callbacks) {
                try { cb.invoke(m); } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }

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

    // ---- what reaches the game being replaced ------------------------------------------------------

    /**
     * c1 and c2, tied by InitStep, which starts RoundStep. Each round offers everyone a colour
     * (recorded as ColourPick, with the round), lets each cut their own ties, and has a time limit: a
     * timer, which an engine being replaced can't make once EngineCleanup has let go of its classes.
     * Once everyone has picked, the next round starts.
     */
    private static final String ROUNDS = "initStep = stepFactory.createStep('InitStep')\n"
        + "initStep.run = {\n"
        + "  def c1 = g.getVertex('c1')\n"
        + "  def c2 = g.getVertex('c2')\n"
        + "  if (c1 != null && c2 != null && !g.hasEdge(c1, c2)) g.addEdge(c1, c2)\n"
        + "}\n"
        + "initStep.done = { roundStep.start() }\n"
        + "round = 0\n"
        + "roundStep = stepFactory.createStep('RoundStep')\n"
        + "roundStep.duration = 120\n"
        + "roundStep.run = {\n"
        + "  round++\n"
        + "  def players = []\n"
        + "  g.getVertices().each { players << it }\n"
        + "  players.each { v ->\n"
        + "    edgeEdits.allow(v, [scope: 'own', confirm: false])\n"
        + "    a.add(v, [name: 'Blue', event: [name: 'ColourPick', data: [player: v.id, round: round]]])\n"
        + "  }\n"
        + "}\n"
        + "roundStep.done = { roundStep.start() }";

    /** Its OnJoinStep records each player who joins (Joined). */
    private static final String JOINS = "onJoinStep = stepFactory.createNoUserActionStep()\n"
        + "onJoinStep.run = { id -> eventTracker.track('Joined', [player: id]) }\n"
        + "onJoinStep.done = { }";

    /**
     * A running session of ROUNDS whose game is in round 1, with c1 and c2 offered their colour; or,
     * not {@code started}, waiting for its start.
     */
    private final class Rounds {
        final Experiment experiment;
        final ExperimentInstance session;
        final AdminSocketsTest.Tab tab;
        final Page c1;
        final Page c2;
        final String uid1;
        final String uid2;

        Rounds(User researcher, String name) throws Exception {
            this(researcher, name, true);
        }

        Rounds(User researcher, String name, boolean started) throws Exception {
            final Experiment e = createExperiment(name);
            createStep(e, "OnJoinStep", JOINS);
            createStep(e, "InitStep", ROUNDS);
            tab = console(researcher);
            tab.send("SelectExperiment", "experimentId", e.id);
            await("selected", () -> e.id.equals(scriptBoardField("engineExperimentId").get(null)));
            tab.send("LaunchGame", "name", name, "parameters", Json.newObject());
            await("running", () -> ExperimentInstance.find.where().eq("experiment.id", e.id)
                .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
            experiment = e;
            session = ExperimentInstance.find.where().eq("experiment.id", e.id).findUnique();
            c1 = connect(e, session, "c1");
            c2 = connect(e, session, "c2");
            if (!started) {
                uid1 = null;
                uid2 = null;
                return;
            }
            tab.send("RunGame");
            await("round 1 offered", () -> choiceOf("c1") != null && choiceOf("c2") != null);
            uid1 = choiceOf("c1");
            uid2 = choiceOf("c2");
            assertEquals(1, events(session, "RoundStepStart").size());
        }
    }

    /** The uid of the first choice the engine's game offers this player now, or null. */
    private static String choiceOf(String player) {
        Object uid = engineValue("g.getVertex('" + player + "')?.choices?.getAt(0)?.uid");
        return uid == null ? null : uid.toString();
    }

    interface Action { void run() throws Exception; }

    /**
     * At the engine change's {@code stage} (ScriptBoard.changeHook), once, {@code action} runs on a
     * thread of its own, as a participant's message comes in on one; the change goes on once it has
     * finished, or after {@code waitMs}. Whether it ran.
     */
    private AtomicBoolean atStage(final String stage, final long waitMs, final Action action) throws Exception {
        final AtomicBoolean ran = new AtomicBoolean();
        Consumer<String> hook = s -> {
            if (!stage.equals(s) || !ran.compareAndSet(false, true)) return;
            Thread t = new Thread(() -> {
                try {
                    action.run();
                } catch (Throwable e) {
                    actorFailures.add(e);
                }
            }, "participant at " + stage);
            actors.add(t);
            t.start();
            try {
                t.join(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        scriptBoardField("changeHook").set(null, hook);
        return ran;
    }

    /** Waits until no change of the engine is under way: each holds the engine's lock throughout. */
    private static void settled() throws Exception {
        Object lock = scriptBoardField("REBUILD_LOCK").get(null);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** The names of the session's events recorded after {@code mark}, in order. */
    private static List<String> recordedAfter(ExperimentInstance session, Event mark) {
        List<String> names = new ArrayList<>();
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).gt("id", mark.id).orderBy("id asc").findList()) {
            names.add(e.name);
        }
        return names;
    }

    /** ScriptBoard.failureRecorded: the step failure the autopilot's guards would count for this session. */
    private static Object failureRecorded(Long session) throws Exception {
        java.lang.reflect.Method m = ScriptBoard.class.getDeclaredMethod("failureRecorded", Long.class);
        m.setAccessible(true);
        return m.invoke(null, session);
    }

    /** An error of a step, as the consoles are told of one. */
    private static final AdminSocketsTest.Matcher STEP_ERROR = f -> f.has("error") && f.get("error").asText().length() > 0 && f.has("step");
    private static final AdminSocketsTest.Matcher CHOICE_ANSWER = f -> "MakeChoice".equals(f.path("request").asText()) && f.has("choice");
    private static final AdminSocketsTest.Matcher TIE_ANSWER = f -> f.has("tieEdit");

    @Test
    public void choicesMadeWhileTheEngineIsRebuiltAreNotTakenAndTheGameItReplacesRecordsNothingAfterTheReload() throws Exception {
        final Rounds game = new Rounds(user("picks"), "Picks during a reload");
        final Vertex oldC1 = (Vertex) engineValue("g.getVertex('c1')");
        // Both pick as the engine being replaced is cleaned up, once EngineCleanup has let go of its
        // classes. Its round used to end there, and its next round to start and fail to make its timer:
        // the picks, that round's start and a stepFailed were recorded after engineReloaded. Then the
        // round's time runs out there (a task of its timers under way), which fails the same way.
        final AtomicBoolean ran = atStage("released", 5000, () -> {
            game.c1.send("MakeChoice", "choiceUID", game.uid1);
            game.c2.send("MakeChoice", "choiceUID", game.uid2);
            engine().eval("roundStep.endOnce(null, true); null");
        });
        int consoleFrames = game.tab.frames.size();
        game.tab.send("ReloadEngine");
        await("rebuilt", () -> game.c1.closed && game.c2.closed && engineValue("roundStep") != null);
        settled();
        assertTrue("the participants picked while the engine was rebuilt", ran.get());
        assertTrue(actorFailures.toString(), actorFailures.isEmpty());
        assertNotNull("the game being replaced took no choice", oldC1.getProperty("choices"));

        // The reload, and nothing of the game it replaced after it.
        List<Event> reloaded = events(game.session, "engineReloaded");
        assertEquals(1, reloaded.size());
        assertEquals("true", value(reloaded.get(0), "restarted"));
        assertEquals(Collections.emptyList(), recordedAfter(game.session, reloaded.get(0)));
        assertEquals(0, events(game.session, "ColourPick").size());
        assertEquals(0, events(game.session, "stepFailed").size());
        // Nor is a failure of that game the restarted one's (for the autopilot's guards), or the consoles'.
        assertNull(failureRecorded(game.session.id));
        assertEquals(0, game.tab.countSince(consoleFrames, STEP_ERROR));

        // The restarted game records its own round 1, after the reload.
        Page c1 = connect(game.experiment, game.session, "c1");
        connect(game.experiment, game.session, "c2");
        game.tab.send("RunGame");
        await("round 1 again", () -> choiceOf("c1") != null && choiceOf("c2") != null);
        c1.send("MakeChoice", "choiceUID", choiceOf("c1"));
        await("c1's pick", () -> events(game.session, "ColourPick").size() == 1);
        Event pick = events(game.session, "ColourPick").get(0);
        assertTrue(pick.id > reloaded.get(0).id);
        assertEquals("c1", value(pick, "player"));
        assertEquals("1", value(pick, "round"));

        // Code of the old game that still runs (a task of its timers under way) shows nobody anything:
        // its network reaches no console or page any more. It used to show the consoles its c1 as the
        // new game's.
        int frames = game.tab.frames.size();
        oldC1.setProperty("text", "Round 2 of the old game");
        assertEquals(0, game.tab.countSince(frames, f -> f.toString().contains("Round 2 of the old game")));
    }

    @Test
    public void tieEditsChoicesAndALateStartAsTheRebuildBeginsChangeNothingInTheGameItReplaces() throws Exception {
        final Rounds game = new Rounds(user("ties"), "Tie edits during a reload");
        // Another researcher works on the experiment too, with a console open.
        final User other = user("helper");
        final AdminSocketsTest.Tab helper = console(other);
        helper.send("SelectExperiment", "experimentId", game.experiment.id);
        await("their selection", () -> {
            User u = User.findByEmail(other.email);
            return u.selectedExperiment != null && game.experiment.id.equals(u.selectedExperiment.id);
        });
        assertNotNull(engineValue("g.getEdge(g.getVertex('c1'), g.getVertex('c2'))"));

        // As the rebuild begins, while the engine being replaced still holds its game: c1 cuts their tie
        // to c2, c2 picks, the other researcher picks for c1 and cuts that tie too, that game's waiting
        // room leaves c1 out of a start and turns c2 away as the room is full, its network gains a
        // player, and it comes to its end (its waiting room gives up, say). That game used to take them
        // all, after engineReloaded: the player's OnJoinStep ran in the new engine, and the end finished
        // the session the new engine carries on.
        final AtomicBoolean ran = atStage("cleaning", 5000, () -> {
            Map<String, String> cut = new LinkedHashMap<>();
            cut.put("op", "remove");
            cut.put("a", "c1");
            cut.put("b", "c2");
            game.c1.send("CustomEvent", "eventName", "bb:edge-edit", "playerId", "c1", "data", cut);
            game.c2.send("MakeChoice", "choiceUID", game.uid2);
            // (the tie edit first: their engine actor takes one message at a time)
            helper.send("EditTie", "op", "remove", "a", "c1", "b", "c2");
            helper.send("MakeChoice", "choiceUID", game.uid1);
            ScriptBoard.leftOutAtStart("c1");
            ScriptBoard.sessionFullAtStart("c2");
            engine().eval("g.addPlayer('late'); null");
            engine().eval("gameListener.finish(); null");
            // (the other researcher's engine actor answers them, a moment later)
            long until = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < until && (helper.count(CHOICE_ANSWER) == 0 || helper.count(TIE_ANSWER) == 0)) {
                Thread.sleep(25);
            }
        });
        game.tab.send("ReloadEngine");
        await("rebuilt", () -> game.c1.closed && game.c2.closed && engineValue("roundStep") != null);
        settled();
        // (what the engine's actor had queued by then has run)
        Thread.sleep(500);
        assertTrue(ran.get());
        assertTrue(actorFailures.toString(), actorFailures.isEmpty());

        Event reloaded = events(game.session, "engineReloaded").get(0);
        assertEquals(Collections.emptyList(), recordedAfter(game.session, reloaded));
        for (String name : new String[]{"edgeEdit", "Disconnected", "Connected", "ColourPick", "missedStart", "sessionFull"}) {
            assertEquals(name, 0, events(game.session, name).size());
        }
        assertNull("c2 wasn't turned away: " + game.c2.frames, game.c2.ended());
        assertEquals("nor was c1 told their tie edit was made", 0,
            game.c1.frames.stream().filter(f -> "bb:edge-edit-result".equals(f.path("eventName").asText())).count());
        assertEquals(ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(game.session.id).status);
        // The other researcher hears that the choice is no longer offered and the tie wasn't made: the
        // game they acted on has gone.
        await("their answers", () -> helper.count(CHOICE_ANSWER) == 1 && helper.count(TIE_ANSWER) == 1);
        assertEquals("gone", helper.first(CHOICE_ANSWER).get("choice").asText());
        assertFalse(helper.first(TIE_ANSWER).get("tieEdit").get("ok").asBoolean());
        // c1 is nobody the restarted game left out: their page gets back in.
        Page back = connect(game.experiment, game.session, "c1");
        assertNull(back.frames.toString(), back.ended());
        assertNotNull(engineValue("g.getVertex('c1')"));
    }

    @Test
    public void selectingTheRunningSessionAgainTakesNoChoiceWhileTheEngineIsRebuilt() throws Exception {
        final Rounds game = new Rounds(user("again"), "Selected again");
        final AtomicBoolean ran = atStage("released", 5000, () -> {
            game.c1.send("MakeChoice", "choiceUID", game.uid1);
            game.c2.send("MakeChoice", "choiceUID", game.uid2);
        });
        int consoleFrames = game.tab.frames.size();
        // Selecting the session the engine runs carries it on, as a reload does.
        game.tab.send("SelectInstance", "id", game.session.id);
        await("rebuilt", () -> game.c1.closed && game.c2.closed && engineValue("roundStep") != null);
        settled();
        assertTrue(ran.get());
        List<Event> reloaded = events(game.session, "engineReloaded");
        assertEquals(1, reloaded.size());
        assertEquals(Collections.emptyList(), recordedAfter(game.session, reloaded.get(0)));
        assertEquals(0, events(game.session, "stepFailed").size());
        assertNull(failureRecorded(game.session.id));
        assertEquals(0, game.tab.countSince(consoleFrames, STEP_ERROR));
    }

    @Test
    public void aStartAsTheEngineIsRebuiltStartsNothingAndLeavesTheSessionOpenToThoseComingBack() throws Exception {
        // Waiting for its start: c1 and c2 are in, the game hasn't begun.
        final Rounds game = new Rounds(user("start"), "Start during a reload", false);
        final User other = user("starter");
        final AdminSocketsTest.Tab helper = console(other);
        helper.send("SelectExperiment", "experimentId", game.experiment.id);
        await("their selection", () -> {
            User u = User.findByEmail(other.email);
            return u.selectedExperiment != null && game.experiment.id.equals(u.selectedExperiment.id);
        });
        // As the rebuild begins, the game being replaced starts: by its own code (an OnJoinStep that
        // starts it once enough people are in, say), by its waiting room, and by another researcher's
        // Start. Such a start used to close the session the new engine carries on, so the pages coming
        // back to it were told it had begun without them, and told the consoles the game had started.
        final String[] roomsStart = new String[1];
        final Boolean[] closed = new Boolean[1];
        final AdminSocketsTest.Matcher startAnswer = f -> "RunGame".equals(f.path("request").asText()) && f.has("error");
        final AtomicBoolean ran = atStage("cleaning", 5000, () -> {
            engine().eval("initStep.start(); null");
            closed[0] = ExperimentInstance.findById(game.session.id).hasStarted;
            roomsStart[0] = ScriptBoard.processScript("initStep.start()", null, "autopilot");
            helper.send("RunGame");
            long until = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < until && helper.count(startAnswer) == 0) {
                Thread.sleep(25);
            }
        });
        int consoleFrames = game.tab.frames.size();
        game.tab.send("ReloadEngine");
        await("rebuilt", () -> game.c1.closed && game.c2.closed && engineValue("roundStep") != null);
        settled();
        assertTrue(ran.get());
        assertTrue(actorFailures.toString(), actorFailures.isEmpty());

        assertEquals(Boolean.FALSE, closed[0]);
        assertEquals("the engine is being reloaded", roomsStart[0]);
        assertEquals(0, game.tab.countSince(consoleFrames, AdminSocketsTest.notify("GameStarted")));
        await("the other researcher's answer", () -> helper.count(startAnswer) == 1);
        assertTrue(helper.first(startAnswer).toString(), helper.first(startAnswer).get("error").asText().contains("being reloaded"));
        Event reloaded = events(game.session, "engineReloaded").get(0);
        assertEquals("false", value(reloaded, "restarted"));
        assertEquals(Collections.emptyList(), recordedAfter(game.session, reloaded));
        assertFalse(ExperimentInstance.findById(game.session.id).hasStarted);
        assertFalse(gameStarted());
        // They come back to the session, whose game is still to start.
        Page back = connect(game.experiment, game.session, "c1");
        assertNull(back.frames.toString(), back.ended());
        assertNotNull(engineValue("g.getVertex('c1')"));
    }

    @Test
    public void anEngineReplacedByANewSessionOrAResumeRecordsNothingMore() throws Exception {
        final Rounds game = new Rounds(user("leftover"), "Leftovers");
        final ScriptEngine first = engine();
        // A new session replaces the engine, as the autopilot's next session does.
        game.tab.send("LaunchGame", "name", "Second", "parameters", Json.newObject());
        await("the second session", () -> ExperimentInstance.find.where().eq("experiment.id", game.experiment.id)
            .eq("name", "Second").eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        settled();
        final ExperimentInstance second = ExperimentInstance.find.where().eq("experiment.id", game.experiment.id)
            .eq("name", "Second").findUnique();
        assertNotSame(first, engine());
        // Code of a replaced engine that runs on (a task of its timers under way as it was replaced)
        // records nothing: it used to record into the session the new engine runs.
        first.eval("eventTracker.track('Leftover', [:]); null");
        assertEquals(0, events(second, "Leftover").size());

        // Resuming the first session replaces the engine again.
        final ScriptEngine secondEngine = engine();
        game.tab.send("SelectInstance", "id", game.session.id);
        await("the first session resumed", () -> events(game.session, "sessionResumed").size() == 1);
        settled();
        assertNotSame(secondEngine, engine());
        secondEngine.eval("eventTracker.track('Leftover', [:]); null");
        first.eval("eventTracker.track('Leftover', [:]); null");
        assertEquals(0, events(game.session, "Leftover").size());
        assertEquals(0, events(second, "Leftover").size());
        // (the engine running now records, of course)
        engine().eval("eventTracker.track('Leftover', [:]); null");
        assertEquals(1, events(game.session, "Leftover").size());
    }

    @Test
    public void choicesMadeWhileStopRunsAreNotRecordedAfterItAndTheStoppedGameCannotFinishTheSession() throws Exception {
        final Rounds game = new Rounds(user("stop"), "Picks during a stop");
        // Both pick once Stop has recorded sessionStopped, while their pages still hold their seats.
        // Their picks and the next round's start used to be recorded after it.
        final AtomicBoolean ran = atStage("stopping", 5000, () -> {
            game.c1.send("MakeChoice", "choiceUID", game.uid1);
            game.c2.send("MakeChoice", "choiceUID", game.uid2);
        });
        game.tab.send("StopGame", "id", game.session.id);
        await("stopped", () -> ExperimentInstance.findById(game.session.id).status == ExperimentInstance.Status.STOPPED
            && game.c1.closed && game.c2.closed);
        settled();
        assertTrue(ran.get());
        assertTrue(actorFailures.toString(), actorFailures.isEmpty());
        List<Event> stopped = events(game.session, "sessionStopped");
        assertEquals(1, stopped.size());
        assertEquals(Collections.emptyList(), recordedAfter(game.session, stopped.get(0)));

        // Its game played on to its end, by code of it that outlived the Stop (a task of its timers under
        // way, say), doesn't finish the session: it stays stopped, so it can be resumed.
        engine().eval("roundStep.finish(); null");
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(game.session.id).status);
    }
}
