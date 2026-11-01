package controllers;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/** GET /health says the server is failing below 100 MB free where the database lives. */
public class HealthLimitsTest {

    @Test
    public void lessThan100MbFreeIsFailing() {
        File folder = new File("/srv/lab/breadboard/db");
        assertNull(HealthController.diskProblem(100, folder));
        assertNull(HealthController.diskProblem(250000, folder));
        HealthController.Problem problem = HealthController.diskProblem(99, folder);
        assertNotNull(problem);
        // /health needs no sign-in: a fixed phrase there, the numbers and the folder in the log only
        assertEquals("less than 100 MB free where the database is kept", problem.reason);
        assertTrue(problem.detail, problem.detail.contains("only 99 MB"));
        assertTrue(problem.detail, problem.detail.contains(folder.toString()));
        assertNotNull(HealthController.diskProblem(0, folder));
    }
}
