package models;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What EngineSteps says while a build of the engine loads its steps, and after a build that stopped
 * before they loaded. EngineStepsTest checks what the consoles are told.
 */
public class EngineStepsLoadingTest {

    /** An experiment (not saved) with these steps' code. */
    private static Experiment experiment(long id, String... sources) {
        Experiment e = new Experiment();
        e.id = id;
        e.fileMode = false;
        long stepId = id * 10;
        for (String source : sources) {
            Step step = new Step();
            step.id = ++stepId;
            step.name = "Step" + stepId;
            step.source = source;
            e.addStep(step);
        }
        return e;
    }

    @After
    public void forget() {
        EngineSteps.building(null);
        EngineSteps.settled();
    }

    @Test
    public void nothingCountsAsChangedWhileABuildLoadsTheSteps() {
        Experiment e = experiment(990001L, "a = 1", "b = 2");
        EngineSteps.building(e);
        assertFalse("the engine is loading what is saved", EngineSteps.changed(e));
        EngineSteps.loaded(e);
        assertFalse(EngineSteps.changed(e));

        Experiment saved = experiment(990001L, "a = 1", "b = 3");
        assertTrue("a step saved since", EngineSteps.changed(saved));
        EngineSteps.building(saved);
        assertFalse(EngineSteps.changed(saved));
        EngineSteps.loaded(saved);
        assertFalse(EngineSteps.changed(saved));
    }

    @Test
    public void aBuildThatStoppedBeforeItsStepsLoadedLeavesThemToReload() {
        Experiment e = experiment(990002L, "a = 1");
        EngineSteps.building(e);
        assertFalse(EngineSteps.changed(e));
        EngineSteps.settled();
        assertTrue("the engine has none of them", EngineSteps.changed(e));

        // after a build that loaded them, nothing changes
        EngineSteps.building(e);
        EngineSteps.loaded(e);
        EngineSteps.settled();
        assertFalse(EngineSteps.changed(e));
    }
}
