import com.avaje.ebean.Ebean;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AutopilotHooks.afterStepsLoaded: settings saved in the console override the experiment's own
 * autopilot.waitingRoom / autopilot.replaceDisconnected calls (groovy/autopilot.groovy), keeping what
 * the console can't set (callbacks, a custom waiting text, the AI behavior). Runs on the real Groovy
 * scripts (ScriptTestHarness).
 */
public class AutopilotHooksTest extends BaseTest {

    private ScriptTestHarness harness;
    private Experiment experiment;

    @Before
    public void engine() {
        harness = new ScriptTestHarness();
        experiment = createExperiment("Hooked up");
    }

    @After
    public void stopAutopilot() throws Exception {
        harness.eval("autopilot.stop(); null");
        harness.close();
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
    }

    private AutopilotSettings saved(boolean enabled) {
        AutopilotSettings s = new AutopilotSettings();
        s.enabled = enabled;
        s.experimentId = experiment.id;
        s.minPlayers = 3;
        s.maxPlayers = 5;
        s.waitSeconds = 40;
        s.maxWaitSeconds = 300;
        s.countdownSeconds = 0;
        s.replaceAfterSeconds = 12;
        s.returnToHuman = false;
        s.save();
        return s;
    }

    private Object eval(String script) throws Exception {
        return harness.eval(script);
    }

    /** The experiment's own setup, as a step would do it. */
    private void scriptSetsItsOwnAutopilot() throws Exception {
        eval("autopilot.waitingRoom([minPlayers: 9, maxPlayers: 20, waitSeconds: 1, countdownSeconds: 2,\n"
            + "  waitingText: { here, min, max -> 'custom ' + here },\n"
            + "  onStart: { players -> }, onCancel: { players -> }])\n"
            + "autopilot.replaceDisconnected([afterSeconds: 99, returnToHuman: true, behavior: { p -> }]); null");
    }

    @Test
    public void savedSettingsOverrideTheExperimentsOwnKeepingItsCallbacks() throws Exception {
        scriptSetsItsOwnAutopilot();
        saved(true);

        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);

        assertEquals(3, eval("autopilot.room.minPlayers"));
        assertEquals(5, eval("autopilot.room.maxPlayers"));
        assertEquals(40, eval("autopilot.room.waitSeconds"));
        assertEquals(300, eval("autopilot.room.maxWaitSeconds"));
        assertEquals(0, eval("autopilot.room.countdownSeconds"));
        assertEquals(true, eval("autopilot.room.onStart != null && autopilot.room.onCancel != null"));
        assertEquals(true, eval("autopilot.room.customText"));
        assertEquals("custom 0", eval("autopilot.room.waitingText(0, 3, 5)"));

        assertEquals(12, eval("autopilot.replace.afterSeconds"));
        assertEquals(false, eval("autopilot.replace.returnToHuman"));
        assertEquals(true, eval("autopilot.replace.behavior != null"));

        // Applying again (every load of the steps does) changes nothing.
        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(3, eval("autopilot.room.minPlayers"));
        assertEquals(true, eval("autopilot.room.customText"));
    }

    @Test
    public void withoutAnExperimentSetupTheAutopilotsOwnDefaultsFillIn() throws Exception {
        AutopilotSettings s = saved(true);
        s.maxPlayers = null;
        s.maxWaitSeconds = null;
        s.save();

        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);

        assertEquals(3, eval("autopilot.room.minPlayers"));
        assertNull("no upper limit", eval("autopilot.room.maxPlayers"));
        assertNull("never gives up", eval("autopilot.room.maxWaitSeconds"));
        assertEquals(false, eval("autopilot.room.customText"));
        assertTrue(eval("autopilot.room.waitingText(1, 3, null)").toString().contains("Waiting for other participants"));
        assertEquals(12, eval("autopilot.replace.afterSeconds"));
        assertNull(eval("autopilot.replace.behavior"));
    }

    @Test
    public void aSectionSwitchedOffLeavesTheExperimentsOwnSetup() throws Exception {
        scriptSetsItsOwnAutopilot();
        AutopilotSettings s = saved(true);
        s.waitingRoomEnabled = false;
        s.save();

        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(9, eval("autopilot.room.minPlayers"));
        assertEquals(12, eval("autopilot.replace.afterSeconds"));

        s.waitingRoomEnabled = true;
        s.replaceEnabled = false;
        s.save();
        eval("autopilot.replaceDisconnected([afterSeconds: 99]); null");
        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(3, eval("autopilot.room.minPlayers"));
        assertEquals(99, eval("autopilot.replace.afterSeconds"));
    }

    @Test
    public void onlyTheAutopilotsExperimentAndOnlyWhileItIsOn() throws Exception {
        scriptSetsItsOwnAutopilot();
        Experiment other = createExperiment("Not on autopilot");

        // Nothing saved: the experiment's own setup stays.
        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(9, eval("autopilot.room.minPlayers"));

        saved(false);
        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals("switched off", 9, eval("autopilot.room.minPlayers"));

        saved(true);
        AutopilotHooks.afterStepsLoaded(other, harness.engine);
        assertEquals("another experiment", 9, eval("autopilot.room.minPlayers"));
        AutopilotHooks.afterStepsLoaded(null, harness.engine);
        assertEquals("no experiment loaded", 9, eval("autopilot.room.minPlayers"));

        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(3, eval("autopilot.room.minPlayers"));
    }

    @Test
    public void theNewWaitingRoomTakesEffectAtOnce() throws Exception {
        // Two people are waiting when the console's settings (2 needed, room for 2) arrive: the
        // countdown starts right away (countdownSeconds 0 launches, here a recorded start).
        eval("startCount = 0; autopilot.startGame = { startCount++ }; null");
        eval("['p1', 'p2'].each { id -> def v = g.addPlayer(id); v._system.connected = true }; null");
        eval("autopilot.waitingRoom([minPlayers: 5]); null");
        assertEquals("waiting", eval("g.getVertex('p1')._system.autopilot.state"));

        AutopilotSettings s = saved(true);
        s.minPlayers = 2;
        s.maxPlayers = 2;
        s.save();
        AutopilotHooks.afterStepsLoaded(experiment, harness.engine);
        assertEquals(1, eval("startCount"));
    }
}
