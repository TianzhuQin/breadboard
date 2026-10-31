import models.*;
import org.junit.Test;
import play.mvc.Result;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/** GET /csv/experiment/:id/data: every session's events in one file, for Sessions & data. */
public class DataExportTest extends BaseTest {

    private static void param(ExperimentInstance instance, String name, String value) {
        Data d = new Data();
        d.name = name;
        d.value = value;
        d.experimentInstance = instance;
        d.save();
    }

    private static void event(ExperimentInstance instance, String name, String... nameValues) {
        Event e = new Event();
        e.name = name;
        e.experimentInstance = instance;
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            EventData d = new EventData();
            d.name = nameValues[i];
            d.value = nameValues[i + 1];
            e.addEventData(d);
        }
        e.save();
    }

    @Test
    public void everySessionsEventsComeInOneFileWithTheirSessionAndParameters() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        Experiment experiment = createExperiment("Net");
        ExperimentInstance a = createInstance("first", experiment);
        ExperimentInstance b = createInstance("second", experiment);
        param(a, "rounds", "3");
        param(b, "rounds", "5");
        event(a, "ColourPick", "player", "p1", "colour", "blue");
        event(a, "Finished");
        event(b, "ColourPick", "player", "p9", "colour", "orange");

        Result all = route(fakeRequest(GET, "/csv/experiment/" + experiment.id + "/data").withSession("uid", me.uid));
        assertEquals(contentAsString(all), OK, status(all));
        String[] lines = contentAsString(all).split("\n");
        assertEquals("\"session_id\",\"session_name\",\"session_status\",\"rounds\",\"id\",\"event\",\"event_date\",\"data_name\",\"data_value\"", lines[0]);
        assertEquals("a header, two rows for the first pick, one for Finished, two for the second pick", 6, lines.length);
        assertTrue(lines[1], lines[1].startsWith("\"" + a.id + "\",\"first\","));
        assertTrue(lines[1], lines[1].contains(",\"3\","));
        assertTrue(lines[3], lines[3].contains("\"Finished\""));
        assertTrue(lines[5], lines[5].startsWith("\"" + b.id + "\",\"second\","));
        assertTrue(lines[5], lines[5].contains(",\"5\","));

        Result one = route(fakeRequest(GET, "/csv/experiment/" + experiment.id + "/data?ids=" + b.id).withSession("uid", me.uid));
        assertEquals(3, contentAsString(one).split("\n").length);

        assertEquals(BAD_REQUEST, status(route(fakeRequest(GET, "/csv/experiment/" + experiment.id + "/data?ids=x").withSession("uid", me.uid))));
        assertEquals(BAD_REQUEST, status(route(fakeRequest(GET, "/csv/experiment/987654/data").withSession("uid", me.uid))));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/csv/experiment/" + experiment.id + "/data"))));
    }

    /**
     * ?excel=true, the console's "For Excel": the same file with the UTF-8 byte-order mark first, without
     * which Excel and WPS read it in the computer's own encoding and Chinese turns into garbage. Without
     * it, the files are as they always were: analysis scripts read them.
     */
    @Test
    public void forExcelTheFilesStartWithAByteOrderMarkAndAreOtherwiseTheSame() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        Experiment experiment = createExperiment("网络小游戏");
        ExperimentInstance pilot = createInstance("试点1", experiment);
        param(pilot, "rounds", "3");
        event(pilot, "ColourPick", "player", "张伟", "colour", "Blue");
        String[] urls = {"/csv/data/" + pilot.id, "/csv/instances/" + experiment.id,
            "/csv/experiment/" + experiment.id + "/data", "/csv/experiment/" + experiment.id + "/data?ids=" + pilot.id};
        for (String url : urls) {
            Result plain = route(fakeRequest(GET, url).withSession("uid", me.uid));
            assertEquals(url, OK, status(plain));
            byte[] file = contentAsBytes(plain);
            assertEquals(url + ": no byte-order mark", '"', file[0]);
            assertTrue(url, new String(file, StandardCharsets.UTF_8).contains("试点1") || url.startsWith("/csv/data/"));
            for (String yes : new String[]{"true", "1"}) {
                Result excel = route(fakeRequest(GET, url + (url.contains("?") ? "&" : "?") + "excel=" + yes).withSession("uid", me.uid));
                assertEquals(url, OK, status(excel));
                byte[] forExcel = contentAsBytes(excel);
                assertArrayEquals(url, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, Arrays.copyOfRange(forExcel, 0, 3));
                assertArrayEquals(url, file, Arrays.copyOfRange(forExcel, 3, forExcel.length));
                assertEquals("text/csv", contentType(excel));
                assertEquals("utf-8", charset(excel));
            }
            Result no = route(fakeRequest(GET, url + (url.contains("?") ? "&" : "?") + "excel=false").withSession("uid", me.uid));
            assertArrayEquals(url, file, contentAsBytes(no));
        }
        assertTrue(new String(contentAsBytes(route(fakeRequest(GET, urls[0]).withSession("uid", me.uid))), StandardCharsets.UTF_8).contains("张伟"));
    }

    /**
     * Events of the same millisecond come out in the order they were recorded, which their ids give:
     * Ebean took ids from H2 twenty at a time, in the order of H2's hash (…, 20, 33, …, 40, 21, …), and
     * such events came out of order (conf/ebean.properties).
     */
    @Test
    public void eventsOfTheSameMillisecondComeOutInTheOrderTheyWereRecorded() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        Experiment experiment = createExperiment("Busy");
        ExperimentInstance session = createInstance("busy", experiment);
        java.util.Date now = new java.util.Date();
        for (int i = 0; i < 70; i++) {
            Event e = new Event();
            e.name = "Step" + i;
            e.datetime = now;
            e.experimentInstance = session;
            e.save();
        }
        for (String url : new String[]{"/csv/data/" + session.id, "/csv/experiment/" + experiment.id + "/data"}) {
            Result csv = route(fakeRequest(GET, url).withSession("uid", me.uid));
            assertEquals(contentAsString(csv), OK, status(csv));
            String[] lines = contentAsString(csv).split("\n");
            assertEquals(71, lines.length);
            for (int i = 0; i < 70; i++) {
                assertTrue(url + " row " + (i + 1) + ": " + lines[i + 1], lines[i + 1].contains("\"Step" + i + "\""));
            }
        }
    }
}
