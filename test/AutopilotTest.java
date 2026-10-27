import models.*;
import org.junit.Test;
import play.mvc.Result;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The stable /play/{experimentId}/... entry links lead to whichever session is open.
 */
public class AutopilotTest extends BaseTest {

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
    public void theLinkLeadsToTheOpenSessionKeepingProlificParameters() {
        Experiment experiment = createExperiment("PlayOpen");
        ExperimentInstance open = running(experiment, "open", false);
        Result result = route(fakeRequest(GET, "/play/" + experiment.id + "/prolific?PROLIFIC_PID=abc&STUDY_ID=s&SESSION_ID=x"));
        assertEquals(SEE_OTHER, status(result));
        assertEquals("/game/" + experiment.id + "/" + open.id + "/prolific?PROLIFIC_PID=abc&STUDY_ID=s&SESSION_ID=x",
            header(LOCATION, result));
    }

    @Test
    public void aSessionWhoseGameHasStartedIsNotOpen() {
        Experiment experiment = createExperiment("PlayStarted");
        ExperimentInstance waiting = running(experiment, "waiting", false);
        running(experiment, "playing", true);
        assertEquals(waiting.id, Autopilot.openInstance(experiment.id).id);
        Result result = route(fakeRequest(GET, "/play/" + experiment.id + "/login"));
        assertEquals("/game/" + experiment.id + "/" + waiting.id + "/login", header(LOCATION, result));
    }
}
