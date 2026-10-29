package controllers;

import org.junit.Test;

import static org.junit.Assert.*;

/** GET /health says the server is failing below 100 MB free where the database lives. */
public class HealthLimitsTest {

    @Test
    public void lessThan100MbFreeIsFailing() {
        assertNull(HealthController.diskProblem(100));
        assertNull(HealthController.diskProblem(250000));
        String problem = HealthController.diskProblem(99);
        assertNotNull(problem);
        assertEquals("only 99 MB is free on the disk that holds the database", problem);
        assertNotNull(HealthController.diskProblem(0));
    }
}
