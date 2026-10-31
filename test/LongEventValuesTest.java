import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.mvc.Result;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Event values longer than 255 characters (a free-text answer, a JSON value): event_data.value was
 * varchar(255), and EventTracker.track threw inside the experiment's script. The column is widened at
 * startup (Global.widenEventValues), values are kept whole up to EventTracker.VALUE_LIMIT characters,
 * and an event that can't be saved never throws into the script.
 */
public class LongEventValuesTest extends BaseTest {

    @After
    public void wideAgain() {
        // a test that narrowed the column puts it back for the others
        Global.widenEventValues();
    }

    private static String text(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < length; i++) {
            sb.append((char) ('a' + i % 26));
        }
        return sb.toString();
    }

    private static SqlRow valueColumn() {
        return Ebean.createSqlQuery("select type_name as type_name, character_maximum_length as max_length "
            + "from information_schema.columns where lower(table_name) = 'event_data' and lower(column_name) = 'value'")
            .findUnique();
    }

    private static void narrowTheColumn() {
        // as evolution 1 (and every release before this one) made it
        Ebean.createSqlUpdate("alter table event_data alter column value varchar(255)").execute();
        assertEquals(255, valueColumn().getInteger("max_length").intValue());
    }

    private static List<EventData> dataOf(ExperimentInstance instance, String event) {
        return EventData.find.where().eq("event.experimentInstance.id", instance.id).eq("event.name", event).findList();
    }

    private static EventTracker trackerFor(ExperimentInstance instance) {
        EventTracker tracker = new EventTracker();
        tracker.setExperimentInstance(instance);
        return tracker;
    }

    /** Whether the server's log said, at info level, that it widens the column. */
    private static boolean announced(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> log) {
        for (ch.qos.logback.classic.spi.ILoggingEvent e : log.list) {
            if (e.getLevel() == ch.qos.logback.classic.Level.INFO && e.getFormattedMessage().startsWith("Widening event_data.value")) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void wideningIsSaidInTheLogOnlyWhenTheTableHasRows() {
        ch.qos.logback.classic.Logger application = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("application");
        ch.qos.logback.classic.Level level = application.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> log =
            new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        log.start();
        application.addAppender(log);
        application.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            // Tables just made (a new database: no events yet): widened without a word at info level.
            assertEquals(0, EventData.find.findRowCount());
            narrowTheColumn();
            Global.widenEventValues();
            assertEquals(EventTracker.VALUE_LIMIT, valueColumn().getInteger("max_length").intValue());
            assertFalse("announced on tables just made", announced(log));

            // A database with events: it says so, as on a large one it takes a while.
            ExperimentInstance instance = createInstance("Has events", createExperiment("Has events"));
            LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
            data.put("note", "short");
            trackerFor(instance).track("Noted", data);
            narrowTheColumn();
            Global.widenEventValues();
            assertEquals(EventTracker.VALUE_LIMIT, valueColumn().getInteger("max_length").intValue());
            assertTrue(announced(log));
        } finally {
            application.setLevel(level);
            application.detachAppender(log);
        }
    }

    @Test
    public void theColumnHoldsLongValuesFromTheStart() {
        SqlRow column = valueColumn();
        assertEquals("varchar", column.getString("type_name").toLowerCase());
        assertEquals(EventTracker.VALUE_LIMIT, column.getInteger("max_length").intValue());
    }

    @Test
    public void aValueOf2000CharactersIsKeptWhole() throws Throwable {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        ExperimentInstance instance = createInstance("long answers", createExperiment("Free text"));
        String answer = text(2000);
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("player", "p1");
        data.put("answer", answer);

        trackerFor(instance).track("FreeTextAnswer", data);

        List<EventData> saved = dataOf(instance, "FreeTextAnswer");
        assertEquals(2, saved.size());
        for (EventData d : saved) {
            if ("answer".equals(d.name)) {
                assertEquals(answer, d.value);
            }
        }
        // the session's data, as the console downloads it
        Result csv = route(fakeRequest(GET, "/csv/data/" + instance.id).withSession("uid", me.uid));
        assertEquals(OK, status(csv));
        assertTrue("the whole value in the CSV", contentAsString(csv).contains("\"answer\",\"" + answer + "\""));

        // and as the console shows a session's events (ShowEvent on its socket)
        AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(me.uid);
        try {
            tab.send("ShowEvent", "id", instance.id);
            long until = System.currentTimeMillis() + 20000;
            JsonNode shown = null;
            while (shown == null && System.currentTimeMillis() < until) {
                shown = tab.first(f -> f.has("events"));
                Thread.sleep(25);
            }
            assertNotNull("the events were sent", shown);
            String value = null;
            for (JsonNode d : shown.get("events").get(0).get("data")) {
                if ("answer".equals(d.get("name").asText())) {
                    value = d.get("value").asText();
                }
            }
            assertEquals(answer, value);
        } finally {
            tab.close();
        }
    }

    @Test
    public void aValueTooLongEvenForTheWiderColumnIsKeptCutShortAndMarked() {
        ExperimentInstance instance = createInstance("huge", createExperiment("Huge values"));
        String huge = text(EventTracker.VALUE_LIMIT + 10);
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("blob", huge);

        trackerFor(instance).track("Huge", data);

        String kept = dataOf(instance, "Huge").get(0).value;
        assertEquals(EventTracker.VALUE_LIMIT, kept.length());
        assertTrue(kept.endsWith("…[truncated from " + huge.length() + " characters]"));
        assertTrue(huge.startsWith(kept.substring(0, 1000)));
    }

    @Test
    public void whereTheColumnCouldNotBeWidenedALongValueIsKeptCutToFit() {
        narrowTheColumn();
        ExperimentInstance instance = createInstance("narrow", createExperiment("Old database"));
        String answer = text(2000);
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("player", "p1");
        data.put("answer", answer);

        // no exception into the script
        trackerFor(instance).track("FreeTextAnswer", data);

        List<EventData> saved = dataOf(instance, "FreeTextAnswer");
        assertEquals("the event is kept, once", 2, saved.size());
        assertEquals(1, Event.find.where().eq("experimentInstance.id", instance.id).findRowCount());
        for (EventData d : saved) {
            if ("answer".equals(d.name)) {
                assertEquals(255, d.value.length());
                assertTrue(d.value, d.value.endsWith("…[truncated from 2000 characters]"));
                assertTrue(answer.startsWith(d.value.substring(0, 200)));
            } else {
                assertEquals("p1", d.value);
            }
        }
    }

    @Test
    public void anEventThatCantBeSavedIsLeftOutWithoutThrowing() {
        ExperimentInstance gone = new ExperimentInstance();
        gone.id = 987654L;   // not in the database: the event can't refer to it
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("k", "v");

        trackerFor(gone).track("Orphan", data);
        trackerFor(gone).track("Orphan", (LinkedHashMap<Object, Object>) null);

        assertEquals(0, Event.find.where().eq("name", "Orphan").findRowCount());
    }

    @Test
    public void wideningAtStartupIsIdempotentAndKeepsTheData() {
        narrowTheColumn();
        ExperimentInstance instance = createInstance("before", createExperiment("Upgraded"));
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("choice", "blue");
        trackerFor(instance).track("Pick", data);

        Global.widenEventValues();
        assertEquals(EventTracker.VALUE_LIMIT, valueColumn().getInteger("max_length").intValue());
        Global.widenEventValues();
        assertEquals(EventTracker.VALUE_LIMIT, valueColumn().getInteger("max_length").intValue());

        assertEquals("blue", dataOf(instance, "Pick").get(0).value);
        String answer = text(3000);
        data.put("choice", answer);
        trackerFor(instance).track("Pick", data);
        boolean found = false;
        for (EventData d : dataOf(instance, "Pick")) {
            found |= answer.equals(d.value);
        }
        assertTrue("kept whole after widening", found);
    }
}
