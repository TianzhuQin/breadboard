import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import security.ParticipantAuth;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/**
 * A session's launch parameters are bound in the engine before its steps load, however the engine is
 * built for it: launched, resumed after a Stop, carried on by a Reload engine, selected again while it
 * runs, or taken up again when its experiment is selected. Steps read them as they load (the network
 * game's waiting room takes its size from them): bound only after them, the steps of a resumed session
 * ran with the experiment's defaults, and a session launched for 3 people waited for 2.
 */
public class ResumedParametersTest extends BaseTest {

    // A step that keeps what it read as it loaded, as the network game's InitStep reads its waiting room's
    // size: the session's minPlayers, or the default here (2) when there is none.
    private static final String STEP = "paramOr = { String name, fallback ->\n"
        + "  def value = null\n"
        + "  try { value = binding.getVariable(name) } catch (MissingPropertyException noSessionOpen) { }\n"
        + "  value != null ? value : fallback\n"
        + "}\n"
        + "loadedWith = paramOr('minPlayers', 2)\n";

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();
    private AdminSocketsTest.Tab console;
    private Experiment experiment;
    private ExperimentInstance session;

    @Before
    public void launch() throws Exception {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        User user = createUser("params-" + UUID.randomUUID() + "@test.org", "params", "secret-1", lang);
        experiment = createExperiment("Parameters");
        createParameter(experiment, "minPlayers", "Integer", "2");
        createStep(experiment, "Setup", STEP);
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        console = new AdminSocketsTest.Tab(user.uid);
        tabs.add(console);
        console.send("LogIn");
        await("LogIn", () -> console.count(AdminSocketsTest.userJson()) >= 1);
        console.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(field("engineExperimentId").get(null)));
        afterTheEngineChange();
        assertEquals("with no session, the steps load with the default", 2, loadedWith());

        console.send("LaunchGame", "name", "S", "parameters", Json.parse("{\"minPlayers\": 3}"));
        await("session S running", () -> named("S") != null && named("S").status == ExperimentInstance.Status.RUNNING
            && named("S").id.equals(engineSessionId()));
        afterTheEngineChange();
        session = named("S");
        assertEquals("a launch binds them before the steps load", 3, loadedWith());
    }

    @After
    @SuppressWarnings("unchecked")
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        Map<String, Client> clients = (Map<String, Client>) field("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ParticipantAuth.reset();
        ((EventTracker) field("eventTracker").get(null)).setExperimentInstance(null);
        try {
            engine().eval("autopilot.stop(); timers.cancel(); null");
        } catch (Exception ignored) {}
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static Field field(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ScriptEngine engine() throws Exception {
        return (ScriptEngine) field("engine").get(null);
    }

    /** What the steps read as they loaded into the engine there is now. */
    private static int loadedWith() throws Exception {
        return ((Number) engine().eval("loadedWith")).intValue();
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

    private ExperimentInstance.Status status() {
        return ExperimentInstance.findById(session.id).status;
    }

    /** Until the change of the engine under way is over: it holds the engine lock throughout. */
    private static void afterTheEngineChange() throws Exception {
        synchronized (field("REBUILD_LOCK").get(null)) {
            // (nothing: the change has let go of the lock)
        }
    }

    /** Send this request, then wait until it has built a new engine and is done with it. */
    private void rebuiltBy(String action, Object... pairs) throws Exception {
        final ScriptEngine before = engine();
        console.send(action, pairs);
        await("a new engine", () -> engine() != before);
        afterTheEngineChange();
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    // ---- the ways an engine is built for a session that exists --------------------------------------

    @Test
    public void aResumedSessionsStepsLoadWithItsLaunchParameters() throws Exception {
        console.send("StopGame", "id", session.id);
        await("stopped", () -> status() == ExperimentInstance.Status.STOPPED);
        afterTheEngineChange();
        rebuiltBy("SelectInstance", "id", session.id);
        assertEquals(ExperimentInstance.Status.RUNNING, status());
        assertEquals(session.id, engineSessionId());
        assertEquals(3, loadedWith());
        assertEquals("and bound for its game", 3, ((Number) engine().get("minPlayers")).intValue());
    }

    @Test
    public void aReloadThatCarriesTheSessionOnLoadsItsStepsWithItsLaunchParameters() throws Exception {
        rebuiltBy("ReloadEngine");
        assertEquals(ExperimentInstance.Status.RUNNING, status());
        assertEquals(session.id, engineSessionId());
        assertEquals(3, loadedWith());
        assertEquals(3, ((Number) engine().get("minPlayers")).intValue());
    }

    @Test
    public void theRunningSessionSelectedAgainLoadsItsStepsWithItsLaunchParameters() throws Exception {
        rebuiltBy("SelectInstance", "id", session.id);
        assertEquals(ExperimentInstance.Status.RUNNING, status());
        assertEquals(session.id, engineSessionId());
        assertEquals(3, loadedWith());
    }

    @Test
    public void theAccountsRunningSessionTakenUpAsItsExperimentIsSelectedLoadsItsStepsWithItsLaunchParameters() throws Exception {
        // The engine runs no session (its game listener let go of it), while the account's session is
        // still marked running: selecting its experiment builds the engine again and takes that session
        // up (ChangeExperiment), as nothing live keeps the engine as it is.
        field("engineGameListener").set(null, null);
        rebuiltBy("SelectExperiment", "experimentId", experiment.id);
        assertEquals(ExperimentInstance.Status.RUNNING, status());
        assertEquals(session.id, engineSessionId());
        assertEquals(3, loadedWith());
    }
}
