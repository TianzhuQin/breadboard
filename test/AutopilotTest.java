import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.Result;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The stable /play/{experimentId}/... entry links lead to whichever session is open.
 */
public class AutopilotTest extends BaseTest {

    private Field engineSession;
    private Object previousEngineSession;

    @Before
    public void keepEngineSession() throws Exception {
        engineSession = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        previousEngineSession = engineSession.get(null);
        engineSession.set(null, null);
    }

    @After
    public void restoreEngineSession() throws Exception {
        engineSession.set(null, previousEngineSession);
    }

    /** As if the engine ran this session. */
    private void runInEngine(ExperimentInstance instance) throws Exception {
        GameListener running = new GameListener();
        running.experimentInstance = instance;
        engineSession.set(null, running);
    }

    private ExperimentInstance running(Experiment experiment, String name, boolean started) {
        ExperimentInstance instance = createInstance(name, experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.setHasStarted(started);
        instance.save();
        return instance;
    }

    @Test
    public void withNoOpenSessionTheLinkSaysSo() {
        Experiment experiment = createExperiment("PlayNone");
        Result result = route(fakeRequest(GET, "/play/" + experiment.id + "/login"));
        assertEquals(OK, status(result));
        assertTrue(contentAsString(result).contains("No session is open"));
    }

    @Test
    public void theLinkLeadsToTheOpenSessionKeepingProlificParameters() throws Exception {
        Experiment experiment = createExperiment("PlayOpen");
        ExperimentInstance open = running(experiment, "open", false);
        runInEngine(open);
        Result result = route(fakeRequest(GET, "/play/" + experiment.id + "/prolific?PROLIFIC_PID=abc&STUDY_ID=s&SESSION_ID=x"));
        assertEquals(SEE_OTHER, status(result));
        assertEquals("/game/" + experiment.id + "/" + open.id + "/prolific?PROLIFIC_PID=abc&STUDY_ID=s&SESSION_ID=x",
            header(LOCATION, result));
    }

    @Test
    public void aSessionWhoseGameHasStartedIsNotOpen() throws Exception {
        Experiment experiment = createExperiment("PlayStarted");
        ExperimentInstance waiting = running(experiment, "waiting", false);
        ExperimentInstance playing = running(experiment, "playing", true);
        runInEngine(playing);
        assertNull(Autopilot.openInstance(experiment.id));
        assertTrue(contentAsString(route(fakeRequest(GET, "/play/" + experiment.id + "/login"))).contains("No session is open"));

        runInEngine(waiting);
        assertEquals(waiting.id, Autopilot.openInstance(experiment.id).id);
        Result result = route(fakeRequest(GET, "/play/" + experiment.id + "/login"));
        assertEquals("/game/" + experiment.id + "/" + waiting.id + "/login", header(LOCATION, result));
    }

    @Test
    public void aRunningSessionTheEngineDoesntRunIsNotOpen() throws Exception {
        // e.g. left running before a restart, while the engine runs another experiment
        Experiment experiment = createExperiment("PlayOrphan");
        Experiment other = createExperiment("PlayOther");
        running(experiment, "orphan", false);
        runInEngine(running(other, "elsewhere", false));
        assertNull(Autopilot.openInstance(experiment.id));
        assertTrue(contentAsString(route(fakeRequest(GET, "/play/" + experiment.id + "/login"))).contains("No session is open"));
    }
}
