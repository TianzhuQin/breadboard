package models;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.*;

/**
 * The live event pushed to admin consoles: {"event": {"id", "name", "datetime" (UTC ISO-8601 with
 * milliseconds), "data": {name: value}}}, long values cut short.
 */
public class LiveEventJsonTest {

    private static Event event(String name, String... nameValues) {
        Event event = new Event();
        event.name = name;
        event.datetime = new Date(1793109785123L); // 2026-10-27T14:03:05.123Z
        for (int i = 0; i < nameValues.length; i += 2) {
            EventData d = new EventData();
            d.name = nameValues[i];
            d.value = nameValues[i + 1];
            event.addEventData(d);
        }
        return event;
    }

    @Test
    public void theShapeTheConsoleReads() {
        Event e = event("edgeEdit", "actor", "admin", "op", "add");
        e.id = 42L;
        JsonNode body = EventTracker.liveJson(e).get("event");
        assertEquals(42L, body.get("id").asLong());
        assertEquals("edgeEdit", body.get("name").asText());
        assertEquals("2026-10-27T14:03:05.123Z", body.get("datetime").asText());
        assertEquals("admin", body.get("data").get("actor").asText());
        assertEquals("add", body.get("data").get("op").asText());
        assertEquals(2, body.get("data").size());
    }

    @Test
    public void anUnsavedEventHasANullId() {
        JsonNode body = EventTracker.liveJson(event("x")).get("event");
        assertTrue(body.has("id"));
        assertTrue(body.get("id").isNull());
        assertEquals(0, body.get("data").size());
    }

    @Test
    public void longValuesAreCutShort() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('x');
        JsonNode body = EventTracker.liveJson(event("big", "blob", sb.toString(), "none", null)).get("event");
        String value = body.get("data").get("blob").asText();
        assertEquals(EventTracker.LIVE_VALUE_LIMIT + 1, value.length());
        assertTrue(value.endsWith("…"));
        assertTrue(body.get("data").get("none").isNull());
    }
}
