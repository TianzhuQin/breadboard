package models;

import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.*;

/**
 * What the autopilot names its sessions (Autopilot.sessionName): "Autopilot · 2026-10-30 13:20:47 UTC",
 * the date in numbers rather than an English month next to UTC, and no-break spaces, so that a narrow
 * column breaks the name in the same place in every row (between the date and the time) or not at all.
 */
public class AutopilotSessionNameTest {

    @Test
    public void aSessionIsNamedByItsDateInNumbersAndItsTimeInUtc() {
        // 2026-10-30 13:20:47 UTC
        assertEquals("Autopilot\u00a0· 2026-10-30 13:20:47\u00a0UTC", Autopilot.sessionName(new Date(1793366447000L)));
        // (whatever the server's own time zone)
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("Autopilot\u00a0· 2026-10-30 13:20:47\u00a0UTC", Autopilot.sessionName(new Date(1793366447000L)));
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }

    @Test
    public void itBreaksOnlyAfterAutopilotOrBetweenTheDateAndTheTime() {
        String name = Autopilot.sessionName(new Date(1793366447000L));
        // ("Autopilot ·" and "13:20:47 UTC" stay whole; the two ordinary spaces are where it may break)
        assertEquals(java.util.Arrays.asList("Autopilot\u00a0·", "2026-10-30", "13:20:47\u00a0UTC"), java.util.Arrays.asList(name.split(" ")));
    }
}
