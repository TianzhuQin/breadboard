import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Configuration;
import play.libs.Json;
import play.mvc.Result;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * GET/POST /autopilot: the settings the console saves (models.AutopilotSettings), where the settings
 * in charge come from (saved, application.conf, or off), the checks on what is posted, and when a
 * session is asked to open. Opening itself is replaced by a recorder here (AutopilotSessionTest opens
 * real ones).
 */
public class AutopilotSettingsTest extends BaseTest {

    private final List<String> opened = new CopyOnWriteArrayList<>();
    private Field openerField;
    private Object originalOpener;
    private Field engineSessionField;
    private Object originalEngineSession;

    @Before
    public void recordOpens() throws Exception {
        openerField = Autopilot.class.getDeclaredField("opener");
        openerField.setAccessible(true);
        originalOpener = openerField.get(null);
        openerField.set(null, new Consumer<String>() {
            public void accept(String why) { opened.add(why); }
        });
        // As if the engine ran no session (an earlier test's may still look live to it).
        engineSessionField = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSessionField.setAccessible(true);
        originalEngineSession = engineSessionField.get(null);
        engineSessionField.set(null, null);
        // Nor is a launch from an earlier test under way.
        Field launch = Autopilot.class.getDeclaredField("launchRequestedAt");
        launch.setAccessible(true);
        launch.set(null, 0L);
    }

    @After
    public void restoreOpener() throws Exception {
        // Settings gone first, so a task still queued on the autopilot's thread does nothing.
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
        Thread.sleep(150);
        openerField.set(null, originalOpener);
        engineSessionField.set(null, originalEngineSession);
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static void own(User user, Experiment experiment) {
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
    }

    private static Result get(User user) {
        return route(fakeRequest(GET, "/autopilot").withSession("uid", user.uid));
    }

    private static Result post(User user, JsonNode body) {
        return callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(body).withSession("uid", user.uid));
    }

    private static JsonNode json(Result result) {
        return Json.parse(contentAsString(result));
    }

    private static ObjectNode settings(Long experimentId, boolean enabled) {
        ObjectNode s = Json.newObject();
        s.put("enabled", enabled);
        s.put("experimentId", experimentId);
        s.put("continuous", false);
        s.put("nextSessionDelaySeconds", 30);
        ObjectNode room = s.putObject("waitingRoom");
        room.put("enabled", true);
        room.put("minPlayers", 3);
        room.put("maxPlayers", 6);
        room.put("waitSeconds", 45);
        room.put("maxWaitSeconds", 600);
        room.put("countdownSeconds", 0);
        ObjectNode replace = s.putObject("replaceDisconnected");
        replace.put("enabled", false);
        replace.put("afterSeconds", 20);
        replace.put("returnToHuman", false);
        return s;
    }

    private static void awaitOpens(List<String> opened, int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 10000;
        while (opened.size() < n && System.currentTimeMillis() < until) Thread.sleep(20);
        assertEquals(opened.toString(), n, opened.size());
    }

    // ---- reading ---------------------------------------------------------------------------------

    @Test
    public void signingInIsRequired() {
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/autopilot"))));
        assertEquals(UNAUTHORIZED, status(callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withJsonBody(Json.newObject()))));
    }

    @Test
    public void withNothingSavedItIsOffWithTheScriptsDefaults() {
        Result result = get(user("reader"));
        assertEquals(OK, status(result));
        JsonNode state = json(result);
        assertEquals("off", state.get("source").asText());

        JsonNode s = state.get("settings");
        assertFalse(s.get("enabled").asBoolean());
        assertTrue(s.get("experimentId").isNull());
        assertTrue(s.get("continuous").asBoolean());
        assertEquals(10, s.get("nextSessionDelaySeconds").asInt());
        JsonNode room = s.get("waitingRoom");
        assertTrue(room.get("enabled").asBoolean());
        assertEquals(2, room.get("minPlayers").asInt());
        assertTrue(room.get("maxPlayers").isNull());
        assertEquals(60, room.get("waitSeconds").asInt());
        assertTrue(room.get("maxWaitSeconds").isNull());
        assertEquals(5, room.get("countdownSeconds").asInt());
        JsonNode replace = s.get("replaceDisconnected");
        assertTrue(replace.get("enabled").asBoolean());
        assertEquals(30, replace.get("afterSeconds").asInt());
        assertTrue(replace.get("returnToHuman").asBoolean());

        JsonNode status = state.get("status");
        assertFalse(status.get("on").asBoolean());
        assertTrue(status.get("experimentId").isNull());
        assertTrue(status.get("experimentName").isNull());
        assertTrue(status.get("instance").isNull());
        assertTrue(status.get("links").isNull());
        assertTrue(status.get("problem").isNull());
    }

    // ---- saving ----------------------------------------------------------------------------------

    @Test
    public void savingStoresTheSettingsAndAnswersWithTheState() throws Exception {
        User user = user("saver");
        Experiment experiment = createExperiment("Unattended");
        own(user, experiment);

        Result result = post(user, settings(experiment.id, false));
        assertEquals(contentAsString(result), OK, status(result));
        JsonNode state = json(result);
        assertEquals("admin", state.get("source").asText());
        assertEquals(Json.parse(settings(experiment.id, false).toString()), state.get("settings"));
        JsonNode status = state.get("status");
        assertFalse(status.get("on").asBoolean());
        assertEquals(experiment.id.longValue(), status.get("experimentId").asLong());
        assertEquals("Unattended", status.get("experimentName").asText());
        assertEquals("/play/" + experiment.id + "/login", status.get("links").get("login").asText());
        assertEquals("/play/" + experiment.id + "/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}",
            status.get("links").get("prolific").asText());
        assertTrue(status.get("instance").isNull());

        AutopilotSettings saved = AutopilotSettings.load();
        assertNotNull(saved);
        assertFalse(saved.enabled);
        assertEquals(experiment.id, saved.experimentId);
        assertEquals(30, saved.nextSessionDelaySeconds);
        assertEquals(3, saved.minPlayers);
        assertEquals(Integer.valueOf(6), saved.maxPlayers);
        assertEquals(Integer.valueOf(600), saved.maxWaitSeconds);
        assertEquals(0, saved.countdownSeconds);
        assertFalse(saved.replaceEnabled);
        assertFalse(saved.returnToHuman);
        assertEquals(user.email, saved.operatorEmail);
        assertNotNull(saved.updatedAt);

        // GET answers the same, and nothing was opened: it is off.
        assertEquals(state, json(get(user)));
        Thread.sleep(200);
        assertTrue(opened.toString(), opened.isEmpty());
    }

    @Test
    public void theRouteIsWired() {
        User user = user("router");
        Result result = route(fakeRequest(POST, "/autopilot").withJsonBody(Json.parse("{\"continuous\": false}"))
            .withSession("uid", user.uid));
        assertEquals(contentAsString(result), OK, status(result));
        assertFalse(json(result).get("settings").get("continuous").asBoolean());
    }

    @Test
    public void turningItOnAsksForASessionAndTurningItOffDoesNot() throws Exception {
        User user = user("switcher");
        Experiment experiment = createExperiment("Switched");
        own(user, experiment);

        JsonNode state = json(post(user, settings(experiment.id, true)));
        assertTrue(state.get("status").get("on").asBoolean());
        assertTrue(state.get("status").get("problem").isNull());
        awaitOpens(opened, 1);
        assertEquals("its settings were saved", opened.get(0));

        assertEquals(OK, status(post(user, settings(experiment.id, false))));
        Thread.sleep(300);
        assertEquals(1, opened.size());
        assertFalse(json(get(user)).get("status").get("on").asBoolean());
    }

    @Test
    public void keysLeftOutKeepTheirValues() {
        User user = user("partial");
        Experiment experiment = createExperiment("Partial");
        own(user, experiment);
        post(user, settings(experiment.id, false));

        JsonNode s = json(post(user, Json.parse("{\"waitingRoom\": {\"minPlayers\": 4}}"))).get("settings");
        assertEquals(4, s.get("waitingRoom").get("minPlayers").asInt());
        assertEquals(6, s.get("waitingRoom").get("maxPlayers").asInt());
        assertEquals(45, s.get("waitingRoom").get("waitSeconds").asInt());
        assertEquals(30, s.get("nextSessionDelaySeconds").asInt());
        assertEquals(experiment.id.longValue(), s.get("experimentId").asLong());

        // null clears an optional limit; whole numbers may come as 5.0 or "5"; 0 means "never give up".
        s = json(post(user, Json.parse("{\"waitingRoom\": {\"maxPlayers\": null, \"waitSeconds\": 5.0, "
            + "\"countdownSeconds\": \"7\", \"maxWaitSeconds\": 0}}"))).get("settings");
        assertTrue(s.get("waitingRoom").get("maxPlayers").isNull());
        assertEquals(5, s.get("waitingRoom").get("waitSeconds").asInt());
        assertEquals(7, s.get("waitingRoom").get("countdownSeconds").asInt());
        assertTrue(s.get("waitingRoom").get("maxWaitSeconds").isNull());
    }

    @Test
    public void invalidSettingsAreRefusedWithAMessageAndNotSaved() {
        User user = user("checker");
        Experiment experiment = createExperiment("Checked");
        own(user, experiment);
        String[] bodies = {
            "{\"waitingRoom\": {\"minPlayers\": 0}}",
            "{\"waitingRoom\": {\"minPlayers\": 5, \"maxPlayers\": 3}}",
            "{\"nextSessionDelaySeconds\": -1}",
            "{\"waitingRoom\": {\"waitSeconds\": \"soon\"}}",
            "{\"waitingRoom\": {\"countdownSeconds\": -5}}",
            "{\"waitingRoom\": {\"maxWaitSeconds\": 100000}}",
            "{\"replaceDisconnected\": {\"afterSeconds\": 1.5}}",
            "{\"replaceDisconnected\": {\"afterSeconds\": -1}}",
            "{\"enabled\": \"yes\"}",
            "{\"enabled\": true, \"experimentId\": null}",
            "{\"enabled\": true}",
            "{\"experimentId\": 987654321}",
            "{\"experimentId\": \"abc\"}",
            "{\"waitingRoom\": 5}",
            "[1, 2]",
        };
        for (String body : bodies) {
            Result result = post(user, Json.parse(body));
            assertEquals(body + " -> " + contentAsString(result), BAD_REQUEST, status(result));
            String message = json(result).get("message").asText();
            assertTrue(body + " -> " + message, message.length() > 10);
        }
        assertNull("nothing was saved", AutopilotSettings.load());

        Result notJson = callAction(controllers.routes.ref.AutopilotController.save(),
            fakeRequest(POST, "/autopilot").withTextBody("enabled=true").withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(notJson));
        assertTrue(json(notJson).get("message").asText().contains("JSON"));
    }

    @Test
    public void onlyYourOwnExperimentCanBeChosenButTheChosenOneCanBeSwitchedOffByAnyone() throws Exception {
        User owner = user("owner");
        User other = user("other");
        Experiment experiment = createExperiment("Owned");
        own(owner, experiment);

        Result refused = post(other, settings(experiment.id, true));
        assertEquals(BAD_REQUEST, status(refused));
        assertTrue(json(refused).get("message").asText().contains("isn't one of your experiments"));
        assertNull(AutopilotSettings.load());

        assertEquals(OK, status(post(owner, settings(experiment.id, true))));
        awaitOpens(opened, 1);
        // Another researcher can still turn it off (or change the waiting room); sessions stay the owner's.
        Result off = post(other, Json.parse("{\"enabled\": false}"));
        assertEquals(contentAsString(off), OK, status(off));
        AutopilotSettings saved = AutopilotSettings.load();
        assertFalse(saved.enabled);
        assertEquals(owner.email, saved.operatorEmail);
    }

    @Test
    public void aDeletedExperimentIsReportedAsAProblem() {
        User user = user("forgetful");
        Experiment experiment = createExperiment("Gone soon");
        own(user, experiment);
        post(user, settings(experiment.id, false));
        AutopilotSettings saved = AutopilotSettings.load();
        saved.enabled = true;
        saved.experimentId = 987654321L;
        saved.save();

        JsonNode status = json(get(user)).get("status");
        assertFalse(status.get("on").asBoolean());
        assertTrue(status.get("links").isNull());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("no longer exists"));
    }

    @Test
    public void theTableComesFromEvolution31() {
        com.avaje.ebean.SqlRow row = Ebean.createSqlQuery("select state from play_evolutions where id = 31").findUnique();
        assertNotNull("evolution 31 was applied", row);
        assertEquals("applied", String.valueOf(row.values().iterator().next()));
    }

    // ---- where the settings come from ------------------------------------------------------------

    @Test
    public void savedSettingsWinOverTheConfigFileWhichWinsOverOff() {
        Experiment experiment = createExperiment("From the file");
        Map<String, Object> file = new HashMap<String, Object>();
        file.put("breadboard.autopilot.experiment", " From the file ");
        file.put("breadboard.autopilot.continuous", true);
        file.put("breadboard.autopilot.nextSessionDelaySeconds", 7);
        file.put("breadboard.autopilot.user", "runner@lab.org");
        Configuration conf = new Configuration(ConfigFactory.parseMap(file));

        Autopilot.Effective fromFile = Autopilot.effective(null, conf);
        assertEquals("config", fromFile.source);
        assertTrue(fromFile.isOn());
        assertEquals(experiment.id, fromFile.settings.experimentId);
        assertTrue(fromFile.settings.continuous);
        assertEquals(7, fromFile.settings.nextSessionDelaySeconds);
        assertEquals("runner@lab.org", fromFile.settings.operatorEmail);
        // The file leaves the waiting room and takeovers to the experiment's scripts.
        assertFalse(fromFile.settings.waitingRoomEnabled);
        assertFalse(fromFile.settings.replaceEnabled);

        AutopilotSettings saved = new AutopilotSettings();
        saved.minPlayers = 9;
        Autopilot.Effective fromConsole = Autopilot.effective(saved, conf);
        assertEquals("admin", fromConsole.source);
        assertEquals(9, fromConsole.settings.minPlayers);
        assertFalse(fromConsole.isOn());

        Autopilot.Effective off = Autopilot.effective(null, new Configuration(ConfigFactory.empty()));
        assertEquals("off", off.source);
        assertFalse(off.isOn());

        file.put("breadboard.autopilot.experiment", "No such experiment");
        Autopilot.Effective missing = Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(file)));
        assertEquals("config", missing.source);
        assertNull(missing.settings.experimentId);
        assertFalse(missing.isOn());
    }

    // ---- after a session finishes ----------------------------------------------------------------

    @Test
    public void aFinishedSessionLeadsToTheNextOneOnlyWhenContinuous() throws Exception {
        User user = user("continuous");
        Experiment experiment = createExperiment("Again and again");
        Experiment other = createExperiment("Something else");
        own(user, experiment);
        own(user, other);
        ExperimentInstance session = createInstance("first", experiment);
        session.status = ExperimentInstance.Status.FINISHED;
        session.save();
        ExperimentInstance otherSession = createInstance("elsewhere", other);

        ObjectNode s = settings(experiment.id, false);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 0);
        post(user, s);
        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        Thread.sleep(300);
        assertTrue("off: nothing opens", opened.isEmpty());

        s.put("enabled", true);
        post(user, s);
        awaitOpens(opened, 1);   // the save itself

        Autopilot.sessionFinished(ExperimentInstance.findById(otherSession.id));
        Thread.sleep(300);
        assertEquals("another experiment's session doesn't count", 1, opened.size());

        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        awaitOpens(opened, 2);
        assertEquals("session first finished", opened.get(1));

        s.put("continuous", false);
        post(user, s);
        awaitOpens(opened, 3);   // saving with it on asks again (no session of it is open)
        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        Thread.sleep(300);
        assertEquals(3, opened.size());
    }
}
