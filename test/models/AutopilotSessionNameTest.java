package models;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * What the autopilot names its sessions (Autopilot.sessionName): "Autopilot #N", N counting its
 * sessions of the experiment, with plain spaces. They were named by when they opened, in UTC
 * ("Autopilot · 2026-10-30 13:20:47 UTC"), which disagreed with the local time the console shows
 * beside the name, and had no-break spaces, which a filter in the data typed with plain ones didn't
 * match. Sessions named so keep their names, and count.
 */
public class AutopilotSessionNameTest {

    @Test
    public void itsSessionsAreNumberedOneAfterAnother() {
        assertEquals("Autopilot #1", Autopilot.sessionName(Collections.<String>emptyList()));
        assertEquals("Autopilot #2", Autopilot.sessionName(Arrays.asList("Autopilot #1")));
        assertEquals("Autopilot #3", Autopilot.sessionName(Arrays.asList("Autopilot #2", "Autopilot #1")));
        // (a researcher's own sessions don't count)
        assertEquals("Autopilot #3", Autopilot.sessionName(Arrays.asList("Autopilot #1", "Session 1", "Pilot", "Autopilot #2",
            "Autopilot 7", "Autopilot #x", null)));
    }

    @Test
    public void theSessionsNamedByTheTimeTheyOpenedCountToo() {
        // as 3.1's release candidates named them (no-break spaces), and 3.0
        assertEquals("Autopilot #4", Autopilot.sessionName(Arrays.asList(
            "Autopilot · 2026-10-30 13:20:47 UTC", "Autopilot · 2026-10-30 13:21:02 UTC", "Autopilot · Oct 28, 13:16:05 UTC")));
        assertEquals("Autopilot #3", Autopilot.sessionName(Arrays.asList("Autopilot · 2026-10-30 13:20:47 UTC", "Autopilot #2")));
    }

    @Test
    public void aNewNameEvenAfterSomeWereDeleted() {
        // #2 to #4 deleted: not a second #3
        assertEquals("Autopilot #6", Autopilot.sessionName(Arrays.asList("Autopilot #1", "Autopilot #5")));
        assertEquals("Autopilot #10", Autopilot.sessionName(Arrays.asList("Autopilot #9")));
    }

    @Test
    public void plainSpacesOnly() {
        String name = Autopilot.sessionName(Arrays.asList("Autopilot · 2026-10-30 13:20:47 UTC"));
        assertEquals("Autopilot #2", name);
        assertFalse(name.contains(" "));
    }
}
