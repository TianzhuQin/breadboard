import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.WebSocket;
import security.ParticipantAuth;

import javax.script.ScriptEngine;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * The server knows the language of a session's texts without asking the engine: what its steps chose
 * with c.setDefaultLanguage (the network game example: its language launch parameter) is recorded with
 * the session as they load (experiment_instances.content_language, SessionLanguage), and again when
 * they choose another while it runs, and its participants' pages are told. Breadboard's own words to
 * them follow it: the engine's waiting room, and the end the server gives them.
 */
public class SessionLanguageTest extends BaseTest {

    // As the network game's InitStep: the language launch parameter (English when there is none), and
    // a waiting room, whose words are the engine's own.
    private static final String STEP = "paramOr = { String name, fallback ->\n"
        + "  def value = null\n"
        + "  try { value = binding.getVariable(name) } catch (MissingPropertyException noSessionOpen) { }\n"
        + "  value != null ? value : fallback\n"
        + "}\n"
        + "c.setDefaultLanguage(paramOr('language', 'eng'))\n"
        + "autopilot.waitingRoom([minPlayers: 3, waitSeconds: 60])\n";

    // The InitStep with a syntax error: none of it runs, so it chooses no language.
    private static final String BROKEN = STEP + "autopilot.waitingRoom([minPlayers: 3,\n";

    // A step that holds the steps' load while a test keeps its gate shut: the session runs, so takes
    // people, a moment before its steps have loaded and chosen the language of its texts.
    private static final String REACHED = "breadboard.test.stepsReached";
    private static final String GATE = "breadboard.test.stepsGate";
    private static final String GATE_STEP = "def reached = System.getProperties().get('" + REACHED + "')\n"
        + "def gate = System.getProperties().get('" + GATE + "')\n"
        + "if (reached != null) reached.countDown()\n"
        + "if (gate != null) gate.await(20, java.util.concurrent.TimeUnit.SECONDS)\n"
        + "null\n";

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();
    private AdminSocketsTest.Tab console;
    private Experiment experiment;
    private Step initStep;

    @Before
    public void select() throws Exception {
        SessionLanguage.reset();
        Language english = Language.find.where().eq("code", "eng").findUnique();
        if (english == null) english = createLanguage("eng", "English");
        if (Language.find.where().eq("code", "zho").findUnique() == null) createLanguage("zho", "Chinese");
        User user = createUser("lang-" + UUID.randomUUID() + "@test.org", "lang", "secret-1", english);
        experiment = createExperiment("Languages");
        createParameter(experiment, "language", "Text", "eng");
        initStep = createStep(experiment, "InitStep", STEP);
        createStep(experiment, "GateStep", GATE_STEP);
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        console = new AdminSocketsTest.Tab(user.uid);
        tabs.add(console);
        console.send("LogIn");
        await("LogIn", () -> console.count(AdminSocketsTest.userJson()) >= 1);
        console.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(field("engineExperimentId").get(null)));
        afterTheEngineChange();
    }

    @After
    @SuppressWarnings("unchecked")
    public void closeTabs() throws Throwable {
        System.getProperties().remove(REACHED);
        Object gate = System.getProperties().remove(GATE);
        if (gate instanceof CountDownLatch) ((CountDownLatch) gate).countDown();
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Map<String, Client> clients = (Map<String, Client>) field("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ParticipantAuth.reset();
        SessionLanguage.reset();
        ((EventTracker) field("eventTracker").get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A participant's page: its socket, as the server sees it. */
    private static final class Page {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        final WebSocket.Out<JsonNode> socketOut = new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { }
        };
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(socketOut, 0L);

        boolean got(java.util.function.Predicate<JsonNode> what) {
            for (JsonNode f : frames) if (what.test(f)) return true;
            return false;
        }

        String lastText() {
            for (int i = frames.size() - 1; i >= 0; i--) {
                JsonNode text = frames.get(i).path("player").path("text");
                if (text.isTextual()) return text.asText();
            }
            return null;
        }
    }

    private Page connect(ExperimentInstance session, String clientId) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), clientId, page.in, page.out, false);
        return page;
    }

    private ExperimentInstance launch(String name, String language) throws Exception {
        console.send("LaunchGame", "name", name, "parameters", Json.parse("{\"language\": \"" + language + "\"}"));
        await("session " + name, () -> named(name) != null && named(name).status == ExperimentInstance.Status.RUNNING
            && named(name).id.equals(engineSessionId()));
        afterTheEngineChange();
        return named(name);
    }

    private static Field field(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ScriptEngine engine() throws Exception {
        return (ScriptEngine) field("engine").get(null);
    }

    private static Long engineSessionId() throws Exception {
        java.lang.reflect.Method m = ScriptBoard.class.getDeclaredMethod("engineSession");
        m.setAccessible(true);
        ExperimentInstance running = (ExperimentInstance) m.invoke(null);
        return running == null ? null : running.id;
    }

    private ExperimentInstance named(String name) {
        return ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", name).findUnique();
    }

    /** What the database holds for the session (as a restarted server would read it). */
    private static String recorded(ExperimentInstance session) {
        SqlRow row = Ebean.createSqlQuery("select content_language as lang from experiment_instances where id = :id")
            .setParameter("id", session.id).findUnique();
        return row.getString("lang");
    }

    private static void afterTheEngineChange() throws Exception {
        synchronized (field("REBUILD_LOCK").get(null)) {
            // (nothing: the change has let go of the lock)
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

    // ---- what the steps chose ---------------------------------------------------------------------

    @Test
    public void aSessionTakesTheLanguageItsStepsChoseAndItsParticipantsHearBreadboardInIt() throws Exception {
        ExperimentInstance chinese = launch("中文", "zho");
        assertEquals("zho", SessionLanguage.of(chinese.id));
        assertEquals("kept with the session", "zho", recorded(chinese));
        SessionLanguage.reset();
        assertEquals("also once the server has forgotten it", "zho", SessionLanguage.of(chinese.id));

        // A page that connects is told, and the engine's waiting room speaks Chinese.
        final Page p1 = connect(chinese, "p1");
        assertTrue(p1.frames.toString(), p1.got(f -> "zho".equals(f.path("bbLanguage").asText())));
        await("the waiting room", () -> p1.lastText() != null && p1.lastText().contains("正在等待其他参与者"));
        assertEquals("<h3>正在等待其他参与者</h3><p>目前已有 1 人加入。人数达到 3 人后，实验就会开始。</p><p>请保持这个页面打开。</p>", p1.lastText());

        // The researcher stops it: they are told in Chinese.
        console.send("StopGame", "id", chinese.id);
        await("told it stopped", () -> p1.lastText() != null && p1.lastText().contains("本场实验已被停止"));
        afterTheEngineChange();

        // A session in English: as before.
        ExperimentInstance english = launch("English", "eng");
        assertEquals("eng", SessionLanguage.of(english.id));
        assertEquals("the earlier session keeps its own", "zho", SessionLanguage.of(chinese.id));
        final Page p2 = connect(english, "p2");
        assertTrue(p2.frames.toString(), p2.got(f -> "eng".equals(f.path("bbLanguage").asText())));
        await("the waiting room", () -> p2.lastText() != null && p2.lastText().contains("Waiting for other participants"));
        assertEquals("<h3>Waiting for other participants</h3><p>1 joined so far. The session starts when 3 people are here.</p>"
            + "<p>Please keep this page open.</p>", p2.lastText());
        console.send("StopGame", "id", english.id);
        await("told it stopped", () -> p2.lastText() != null && p2.lastText().contains("This session has been stopped"));
    }

    @Test
    public void theSessionFollowsTheStepsWhenTheyChooseAnotherLanguageWhileItRuns() throws Exception {
        ExperimentInstance session = launch("Switch", "zho");
        final Page p1 = connect(session, "p1");
        // e.g. as the game starts, or from the Script console
        engine().eval("c.setDefaultLanguage('eng'); null");
        assertEquals("eng", SessionLanguage.of(session.id));
        assertEquals("eng", recorded(session));
        await("told", () -> p1.got(f -> f.size() == 1 && "eng".equals(f.path("bbLanguage").asText())));
        engine().eval("c.setDefaultLanguage(null); null");
        assertNull("none chosen: English", SessionLanguage.of(session.id));
        await("told", () -> p1.got(f -> f.size() == 1 && f.has("bbLanguage") && f.get("bbLanguage").isNull()));
    }

    @Test
    public void aNewSessionsPagesAreInItsExperimentsLanguageWhileItsStepsLoad() throws Exception {
        final ExperimentInstance first = launch("First", "zho");
        console.send("StopGame", "id", first.id);
        await("stopped", () -> ExperimentInstance.findById(first.id).status == ExperimentInstance.Status.STOPPED);
        afterTheEngineChange();

        // The next session runs, and the study's link leads to it, before its steps have loaded and
        // chosen the language of its texts: its pages were in English until then.
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        System.getProperties().put(REACHED, reached);
        System.getProperties().put(GATE, gate);
        final Long next;
        try {
            console.send("LaunchGame", "name", "Next", "parameters", Json.parse("{\"language\": \"zho\"}"));
            assertTrue("its steps are loading", reached.await(30, TimeUnit.SECONDS));
            next = whileItsStepsLoad("Next");
            assertEquals("still loading", 1, gate.getCount());
        } finally {
            gate.countDown();
        }
        afterTheEngineChange();
        // what its steps chose, once they have loaded
        assertEquals("zho", recorded(ExperimentInstance.findById(next)));

        // A session whose steps choose another language: theirs, once they have loaded.
        console.send("StopGame", "id", next);
        await("stopped", () -> ExperimentInstance.findById(next).status == ExperimentInstance.Status.STOPPED);
        afterTheEngineChange();
        System.getProperties().remove(REACHED);
        System.getProperties().remove(GATE);
        ExperimentInstance english = launch("English", "eng");
        assertEquals("eng", recorded(english));
        assertEquals("eng", SessionLanguage.of(english.id));
    }

    /**
     * The session {@code name} runs, while its steps are held loading: it has its experiment's most
     * recent session's language, and the study's link leads to its sign-in page, in that language.
     * (Not inline in the try above: Ebean's enhancement of field reads there makes a class Java won't load.)
     */
    private Long whileItsStepsLoad(String name) throws Exception {
        ExperimentInstance next = named(name);
        assertEquals(ExperimentInstance.Status.RUNNING, next.status);
        assertEquals(next.id, engineSessionId());

        play.mvc.Result toIt = play.test.Helpers.route(play.test.Helpers.fakeRequest("GET", "/play/" + experiment.id + "/login"));
        assertEquals(play.test.Helpers.SEE_OTHER, play.test.Helpers.status(toIt));
        String signInUrl = play.test.Helpers.header("Location", toIt);
        assertEquals("/game/" + experiment.id + "/" + next.id + "/login", signInUrl);
        String signIn = play.test.Helpers.contentAsString(play.test.Helpers.route(play.test.Helpers.fakeRequest("GET", signInUrl)));
        assertTrue(signIn, signIn.contains("<html lang=\"zh-CN\">"));
        assertTrue(signIn, signIn.contains("<h1>加入实验</h1>"));
        assertEquals("its experiment's most recent session's, for now", "zho", recorded(next));
        assertEquals("zho", SessionLanguage.of(next.id));
        return next.id;
    }

    @Test
    public void aReloadOrAResumeRecordsTheLanguageItsStepsChooseAgain() throws Exception {
        final ExperimentInstance session = launch("Kept", "zho");
        // Switched to English from the Script console; the steps choose Chinese again as the reload
        // loads them, for the session that carries on.
        engine().eval("c.setDefaultLanguage('eng'); null");
        assertEquals("eng", recorded(session));
        final ScriptEngine before = engine();
        console.send("ReloadEngine");
        await("a new engine", () -> engine() != before);
        afterTheEngineChange();
        assertEquals(session.id, engineSessionId());
        assertEquals("zho", recorded(session));
        assertEquals("zho", SessionLanguage.of(session.id));

        console.send("StopGame", "id", session.id);
        await("stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        afterTheEngineChange();
        // As for a session from before 3.1: nothing recorded. Resumed, its steps record it.
        Ebean.createSqlUpdate("update experiment_instances set content_language = null where id = :id")
            .setParameter("id", session.id).execute();
        SessionLanguage.reset();
        assertNull(recorded(session));
        final ScriptEngine stopped = engine();
        console.send("SelectInstance", "id", session.id);
        await("resumed", () -> engine() != stopped && session.id.equals(engineSessionId()));
        afterTheEngineChange();
        assertEquals("zho", recorded(session));
        assertEquals("zho", SessionLanguage.of(session.id));

        // Taken up again as its experiment is selected while the engine holds no session (as after a
        // start of the server that couldn't mark it interrupted): its steps record it too.
        engine().eval("c.setDefaultLanguage('eng'); null");
        assertEquals("eng", recorded(session));
        field("engineGameListener").set(null, null);
        final ScriptEngine held = engine();
        console.send("SelectExperiment", "experimentId", experiment.id);
        await("taken up again", () -> engine() != held && session.id.equals(engineSessionId()));
        afterTheEngineChange();
        assertEquals("zho", recorded(session));
    }

    /** The step's code, as the console's Steps page saves it. */
    private static void setSource(Step step, String source) {
        Ebean.createSqlUpdate("update steps set source = :source where id = :id")
            .setParameter("source", source).setParameter("id", step.id).execute();
    }

    private void stop(final ExperimentInstance session) throws Exception {
        console.send("StopGame", "id", session.id);
        await("stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        afterTheEngineChange();
    }

    @Test
    public void stepsThatFailToLoadLeaveTheSessionsLanguageUnlessTheyChoseOneFirst() throws Exception {
        stop(launch("First", "zho"));

        // A launch whose InitStep doesn't compile: the steps chose no language, and the session keeps the
        // one it was given (its experiment's most recent session's). It was recorded as none, English,
        // so the pages between sessions (the experiment's most recent session's language) were English.
        setSource(initStep, BROKEN);
        ExperimentInstance broken = launch("Broken", "zho");
        assertEquals("zho", recorded(broken));
        assertEquals("zho", SessionLanguage.of(broken.id));
        stop(broken);
        assertEquals("zho", SessionLanguage.ofExperiment(experiment.id));
        String between = play.test.Helpers.contentAsString(play.test.Helpers.route(
            play.test.Helpers.fakeRequest("GET", "/play/" + experiment.id + "/login")));
        assertTrue(between, between.contains("<html lang=\"zh-CN\">"));
        assertTrue(between, between.contains("<h1>" + SystemTexts.text("zho", "noSession.title") + "</h1>"));

        // Steps that chose a language before a later one failed to load: the one they chose.
        setSource(initStep, STEP);
        createStep(experiment, "LaterStep", "def broken = (\n");
        ExperimentInstance chose = launch("Chose", "eng");
        assertEquals("eng", recorded(chose));
        assertEquals("eng", SessionLanguage.of(chose.id));
    }

    @Test
    public void aReloadWhoseStepsFailToLoadLeavesTheLiveSessionsLanguageAndItsPages() throws Exception {
        final ExperimentInstance session = launch("Live", "zho");
        setSource(initStep, BROKEN);
        // A Reload engine with the InitStep broken. Its participants' pages, which the rebuild closes,
        // come back while the steps load: one is held loading here.
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        System.getProperties().put(REACHED, reached);
        System.getProperties().put(GATE, gate);
        final ScriptEngine before = engine();
        final Page page;
        try {
            console.send("ReloadEngine");
            assertTrue("its steps are loading", reached.await(30, TimeUnit.SECONDS));
            page = connect(session, "p1");
        } finally {
            gate.countDown();
        }
        await("a new engine", () -> engine() != before);
        afterTheEngineChange();
        assertEquals("carried on", session.id, engineSessionId());
        assertTrue(page.frames.toString(), page.got(f -> "zho".equals(f.path("bbLanguage").asText())));
        // The session stays Chinese, and its page isn't told English (it was, {"bbLanguage": null}).
        assertEquals("zho", recorded(session));
        assertEquals("zho", SessionLanguage.of(session.id));
        assertFalse(page.frames.toString(), page.got(f -> f.has("bbLanguage") && f.get("bbLanguage").isNull()));
        String signIn = play.test.Helpers.contentAsString(play.test.Helpers.route(
            play.test.Helpers.fakeRequest("GET", "/game/" + experiment.id + "/" + session.id + "/login")));
        assertTrue(signIn, signIn.contains("<html lang=\"zh-CN\">"));
    }

    @Test
    public void theColumnComesFromEvolution35AndTheServersStart() throws Exception {
        SqlRow row = Ebean.createSqlQuery("select state from play_evolutions where id = 35").findUnique();
        assertNotNull("evolution 35 was applied", row);
        assertEquals("applied", String.valueOf(row.values().iterator().next()));
        // The evolution and the server's start (Global.addContentLanguage) make the column the same way.
        String evolution = FileUtils.readFileToString(new File("conf/evolutions/default/35.sql"), "UTF-8");
        assertTrue(evolution, evolution.contains("# --- !Ups\n\n" + SessionLanguage.ADD_COLUMN + ";\n"));

        // A database from an older release: the server's start adds it (twice: it does nothing where the
        // column is), and its sessions have none recorded, so their pages are in English.
        ExperimentInstance old = createInstance("Old", experiment);
        Ebean.createSqlUpdate("alter table experiment_instances drop column if exists content_language").execute();
        try {
            assertNull("without the column: English", SessionLanguage.ofExperiment(experiment.id));
        } finally {
            Global.addContentLanguage();
            Global.addContentLanguage();
        }
        assertNull(SessionLanguage.of(old.id));
        SessionLanguage.record(old.id, "zho");
        SessionLanguage.reset();
        assertEquals("zho", SessionLanguage.of(old.id));
    }
}
