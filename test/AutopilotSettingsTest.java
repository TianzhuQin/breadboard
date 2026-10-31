import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.ConfigFactory;
import models.*;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.Configuration;
import play.libs.Json;
import play.mvc.Result;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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
        openerField.set(null, new Autopilot.Opener() {
            public void open(String why, boolean onlyIfIdle, boolean automatic) { opened.add(why); }
        });
        // As if the engine ran no session (an earlier test's may still look live to it).
        engineSessionField = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSessionField.setAccessible(true);
        originalEngineSession = engineSessionField.get(null);
        engineSessionField.set(null, null);
        // Nor is a launch from an earlier test under way, nor is it paused, done or waiting to open one.
        Field launch = Autopilot.class.getDeclaredField("launchRequestedAt");
        launch.setAccessible(true);
        launch.set(null, 0L);
        resetTiming();
    }

    @After
    public void restoreOpener() throws Exception {
        // Settings gone first, so a task still queued on the autopilot's thread does nothing.
        Ebean.createSqlUpdate("delete from autopilot_settings").execute();
        resetTiming();
        Thread.sleep(150);
        openerField.set(null, originalOpener);
        engineSessionField.set(null, originalEngineSession);
    }

    private static void resetTiming() throws Exception {
        forgetHaltInMemory();
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);
        due.set(null, 0L);   // a pending delayed open then does nothing
        // Nor does an earlier test's researcher still hold the engine.
        Field builtFor = ScriptBoard.class.getDeclaredField("engineBuiltFor");
        builtFor.setAccessible(true);
        builtFor.set(null, null);
        // Nor is an earlier test's session the autopilot's.
        for (String name : new String[]{"ownSession", "openedSession"}) {
            Field f = Autopilot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    /**
     * The autopilot's own session, as when the engine actor has carried out its launch
     * (Autopilot.launchCarriedOut): a researcher who ends it pauses the autopilot. (Ending a session
     * a researcher opened by hand doesn't.)
     */
    private static void itsSession(ExperimentInstance instance) throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("launchCarriedOut", Long.class);
        m.setAccessible(true);
        m.invoke(null, instance.id);
    }

    /** As a restart does: paused and done are gone from memory (the database keeps them). */
    private static void forgetHaltInMemory() throws Exception {
        for (String name : new String[]{"pausedBecause", "doneBecause"}) {
            Field f = Autopilot.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        }
    }

    private static void restoreHalt() throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("restoreHalt");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static Result resume(User user) {
        return callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume").withSession("uid", user.uid));
    }

    private static JsonNode statusJson(Autopilot.Effective effective) throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("statusJson", Autopilot.Effective.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(null, effective);
    }

    /** As if the engine ran this session, started by this account. */
    private void runInEngine(ExperimentInstance instance, User owner) throws Exception {
        GameListener running = new GameListener();
        running.experimentInstance = instance;
        running.user = owner;
        engineSessionField.set(null, running);
    }

    private static void watchdog() throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("watchdog");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void stoppedBy(ExperimentInstance instance, User user) throws Exception {
        java.lang.reflect.Method m = Autopilot.class.getDeclaredMethod("sessionStopped", ExperimentInstance.class, User.class);
        m.setAccessible(true);
        m.invoke(null, instance, user);
    }

    private ExperimentInstance live(String name, Experiment experiment) {
        ExperimentInstance instance = createInstance(name, experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        return instance;
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
        s.put("maxSessionMinutes", 90);
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
        assertEquals(120, s.get("maxSessionMinutes").asInt());
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
        assertTrue(status.get("notice").isNull());
        assertTrue(status.get("noticeAt").isNull());
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
        assertEquals(Integer.valueOf(90), saved.maxSessionMinutes);
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
    public void theLongestSessionIsAWholeNumberOfMinutesUpToADayOrNoLimit() {
        User user = user("limiter");
        Experiment experiment = createExperiment("Limited");
        own(user, experiment);
        // New settings get two hours.
        assertEquals(Integer.valueOf(120), new AutopilotSettings().maxSessionMinutes);
        ObjectNode s = settings(experiment.id, false);
        s.remove("maxSessionMinutes");
        assertEquals(120, json(post(user, s)).get("settings").get("maxSessionMinutes").asInt());
        assertEquals(Integer.valueOf(120), AutopilotSettings.load().maxSessionMinutes);

        // Kept when left out; whole numbers may come as 45.0 or "45"; null is no limit.
        assertEquals(45, json(post(user, Json.parse("{\"maxSessionMinutes\": 45.0}"))).get("settings").get("maxSessionMinutes").asInt());
        assertEquals(45, json(post(user, Json.parse("{\"continuous\": true}"))).get("settings").get("maxSessionMinutes").asInt());
        assertEquals(1440, json(post(user, Json.parse("{\"maxSessionMinutes\": \"1440\"}"))).get("settings").get("maxSessionMinutes").asInt());
        assertEquals(Integer.valueOf(1440), AutopilotSettings.load().maxSessionMinutes);
        JsonNode none = json(post(user, Json.parse("{\"maxSessionMinutes\": null}"))).get("settings");
        assertTrue(none.toString(), none.has("maxSessionMinutes") && none.get("maxSessionMinutes").isNull());
        assertNull(AutopilotSettings.load().maxSessionMinutes);
        assertTrue(json(get(user)).get("settings").get("maxSessionMinutes").isNull());

        for (String body : new String[]{"{\"maxSessionMinutes\": 0}", "{\"maxSessionMinutes\": 1441}", "{\"maxSessionMinutes\": -5}",
                "{\"maxSessionMinutes\": 2.5}", "{\"maxSessionMinutes\": \"two hours\"}"}) {
            Result result = post(user, Json.parse(body));
            assertEquals(body + " -> " + contentAsString(result), BAD_REQUEST, status(result));
            String message = json(result).get("message").asText();
            assertTrue(body + " -> " + message, message.contains("The longest a session may run, in minutes (maxSessionMinutes)"));
        }
        assertNull("nothing was saved", AutopilotSettings.load().maxSessionMinutes);
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
    public void anyResearcherCanChooseAnyExperimentAndSessionsOpenAsWhoeverChoseIt() throws Exception {
        User owner = user("owner");
        User other = user("other");
        Experiment experiment = createExperiment("Shared");
        own(owner, experiment);

        // Every researcher works on every experiment, so also on one another account made.
        Result chosen = post(other, settings(experiment.id, true));
        assertEquals(contentAsString(chosen), OK, status(chosen));
        awaitOpens(opened, 1);
        assertEquals(other.email, AutopilotSettings.load().operatorEmail);
        // Another researcher can turn it off (or change the waiting room); sessions stay the chooser's.
        Result off = post(owner, Json.parse("{\"enabled\": false}"));
        assertEquals(contentAsString(off), OK, status(off));
        AutopilotSettings saved = AutopilotSettings.load();
        assertFalse(saved.enabled);
        assertEquals(other.email, saved.operatorEmail);
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

    @Test
    public void theLongestSessionComesFromEvolution34AndSettingsSavedBeforeKeepNoLimit() throws Exception {
        com.avaje.ebean.SqlRow row = Ebean.createSqlQuery("select state from play_evolutions where id = 34").findUnique();
        assertNotNull("evolution 34 was applied", row);
        assertEquals("applied", String.valueOf(row.values().iterator().next()));
        // The evolution and the server's start (Global.addLongestSession) make the column the same way.
        String evolution = FileUtils.readFileToString(new File("conf/evolutions/default/34.sql"), "UTF-8");
        assertTrue(evolution, evolution.contains("# --- !Ups\n\n" + AutopilotSettings.ADD_LONGEST_SESSION + ";\n"));

        // Settings an older release saved, before the column was there: the server's start adds it
        // (twice: it does nothing where the column is), and they keep no limit, as before the upgrade.
        Ebean.createSqlUpdate("alter table autopilot_settings drop column if exists max_session_minutes").execute();
        try {
            Ebean.createSqlUpdate("insert into autopilot_settings (id, enabled, continuous, next_session_delay_seconds, "
                + "waiting_room_enabled, min_players, wait_seconds, countdown_seconds, replace_enabled, replace_after_seconds, "
                + "return_to_human) values (1, false, true, 10, true, 2, 60, 5, true, 30, true)").execute();
        } finally {
            Global.addLongestSession();
            Global.addLongestSession();
        }
        assertNull("no limit", AutopilotSettings.load().maxSessionMinutes);
        assertEquals(10, AutopilotSettings.load().nextSessionDelaySeconds);
        // New settings start with two hours.
        assertEquals(Integer.valueOf(120), new AutopilotSettings().maxSessionMinutes);
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
        // Left out: no limit, so a study the file set up before there was one goes on as it was.
        assertNull("no limit", fromFile.settings.maxSessionMinutes);
        assertEquals("runner@lab.org", fromFile.settings.operatorEmail);
        file.put("breadboard.autopilot.maxSessionMinutes", 45);
        assertEquals(Integer.valueOf(45), Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(file))).settings.maxSessionMinutes);
        file.put("breadboard.autopilot.maxSessionMinutes", 5000);
        assertEquals("a day at most", Integer.valueOf(1440),
            Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(file))).settings.maxSessionMinutes);
        file.put("breadboard.autopilot.maxSessionMinutes", 0);
        assertNull("no limit", Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(file))).settings.maxSessionMinutes);
        file.remove("breadboard.autopilot.maxSessionMinutes");
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

        // Another experiment's session finishing frees the engine: the autopilot's turn again.
        Autopilot.sessionFinished(ExperimentInstance.findById(otherSession.id));
        awaitOpens(opened, 2);
        assertEquals("session elsewhere of another experiment finished", opened.get(1));

        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        awaitOpens(opened, 3);
        assertEquals("session first finished", opened.get(2));

        s.put("continuous", false);
        post(user, s);
        awaitOpens(opened, 4);   // saving with it on asks again (no session of it is open)
        // A session of its experiment that a researcher opened by hand finishes: that wasn't its one
        // session, which opens after the pause. (It used to be counted as its one session, and called so.)
        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        awaitOpens(opened, 5);
        assertEquals("session first finished", opened.get(4));
        assertFalse(json(get(user)).get("status").get("done").asBoolean());
        // Its own session finishes.
        itsSession(session);
        Autopilot.sessionFinished(ExperimentInstance.findById(session.id));
        Thread.sleep(300);
        assertEquals(5, opened.size());
        // That was its one session: the periodic check leaves it at that, and says why.
        watchdog();
        Thread.sleep(100);
        assertEquals(5, opened.size());
        assertTrue(json(get(user)).get("status").get("problem").asText().contains("has finished"));
    }

    // ---- other sessions, the periodic check, pausing ---------------------------------------------

    @Test
    public void aSaveLeavesAnotherExperimentsLiveSessionAloneUnlessToldToTakeOver() throws Exception {
        User user = user("polite");
        User someone = user("someone");
        Experiment experiment = createExperiment("Ours");
        Experiment other = createExperiment("Theirs");
        own(user, experiment);
        ExperimentInstance theirs = live("their session", other);
        runInEngine(theirs, someone);

        JsonNode status = json(post(user, settings(experiment.id, true))).get("status");
        assertTrue(status.get("on").asBoolean());
        JsonNode engineSession = status.get("engineSession");
        assertEquals(theirs.id.longValue(), engineSession.get("id").asLong());
        assertEquals("their session", engineSession.get("name").asText());
        assertEquals(other.id.longValue(), engineSession.get("experimentId").asLong());
        assertEquals("Theirs", engineSession.get("experimentName").asText());
        assertEquals(someone.email, engineSession.get("owner").asText());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("Another session is running"));

        // A plain edit doesn't end it either.
        ObjectNode edit = settings(experiment.id, true);
        ((ObjectNode) edit.get("waitingRoom")).put("minPlayers", 4);
        assertEquals(OK, status(post(user, edit)));
        watchdog();
        Thread.sleep(300);
        assertTrue(opened.toString(), opened.isEmpty());

        // Asked to (after the console's confirmation), it takes the engine over.
        edit.put("takeOver", true);
        assertEquals(OK, status(post(user, edit)));
        awaitOpens(opened, 1);
        assertTrue(opened.get(0), opened.get(0).contains("taking over"));
        assertFalse("takeOver is not a setting", json(get(user)).get("settings").has("takeOver"));
    }

    @Test
    public void theWatchdogOpensASessionWhenNoneRunsUnlessPaused() throws Exception {
        User user = user("watched");
        Experiment experiment = createExperiment("Watched");
        Experiment other = createExperiment("Elsewhere");
        own(user, experiment);

        post(user, settings(experiment.id, true));
        awaitOpens(opened, 1);   // the save

        // Its session ended some other way (another launch, a reload): the check opens one.
        watchdog();
        awaitOpens(opened, 2);
        assertEquals("no session was open", opened.get(1));

        // Not while another experiment's session runs.
        ExperimentInstance elsewhere = live("elsewhere", other);
        runInEngine(elsewhere, user);
        watchdog();
        Thread.sleep(200);
        assertEquals(2, opened.size());

        // A researcher stopped its session: paused until the next save.
        ExperimentInstance ours = live("ours", experiment);
        itsSession(ours);
        runInEngine(ours, user);
        ours.status = ExperimentInstance.Status.STOPPED;
        ours.save();
        stoppedBy(ExperimentInstance.findById(ours.id), user);
        watchdog();
        Thread.sleep(200);
        assertEquals(2, opened.size());
        JsonNode status = json(get(user)).get("status");
        assertTrue(status.get("paused").asBoolean());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("paused"));
        assertTrue(status.get("problem").asText().contains(user.email));

        JsonNode saved = json(post(user, settings(experiment.id, true))).get("status");
        assertFalse(saved.get("paused").asBoolean());
        awaitOpens(opened, 3);

        // While a finished session's pause runs, the check waits for it.
        ObjectNode later = settings(experiment.id, true);
        later.put("continuous", true);
        later.put("nextSessionDelaySeconds", 60);
        post(user, later);
        awaitOpens(opened, 4);
        Autopilot.sessionFinished(ExperimentInstance.findById(ours.id));
        assertFalse(json(get(user)).get("status").get("nextOpenAt").isNull());
        watchdog();
        Thread.sleep(200);
        assertEquals(4, opened.size());
    }

    @Test
    public void nothingIsDueWhilePausedOrDone() throws Exception {
        User user = user("halted");
        Experiment experiment = createExperiment("Halted");
        own(user, experiment);
        ObjectNode s = settings(experiment.id, true);
        s.put("continuous", true);
        s.put("nextSessionDelaySeconds", 60);
        post(user, s);
        awaitOpens(opened, 1);
        Field due = Autopilot.class.getDeclaredField("nextOpenAt");
        due.setAccessible(true);

        // Paused by a researcher: a session that finishes then schedules nothing, and none is shown as due.
        ExperimentInstance ours = live("ours", experiment);
        itsSession(ours);
        stoppedBy(ExperimentInstance.findById(ours.id), user);
        Autopilot.sessionFinished(ExperimentInstance.findById(ours.id));
        assertEquals(0L, due.get(null));
        JsonNode status = json(get(user)).get("status");
        assertTrue(status.get("paused").asBoolean());
        assertTrue(status.get("nextOpenAt").isNull());
        // Whatever is counting down, nothing is due while it is paused.
        due.set(null, System.currentTimeMillis() + 60000);
        assertTrue(json(get(user)).get("status").get("nextOpenAt").isNull());
        due.set(null, 0L);

        // A next session counting down when the pause comes: it no longer opens, nor shows as due.
        post(user, s);
        awaitOpens(opened, 2);
        Autopilot.sessionFinished(ExperimentInstance.findById(ours.id));
        assertFalse(json(get(user)).get("status").get("nextOpenAt").isNull());
        stoppedBy(ExperimentInstance.findById(ours.id), user);
        assertEquals(0L, due.get(null));
        assertTrue(json(get(user)).get("status").get("nextOpenAt").isNull());

        // Done (as after a non-continuous run's one session): the same.
        post(user, s);
        awaitOpens(opened, 3);
        Field done = Autopilot.class.getDeclaredField("doneBecause");
        done.setAccessible(true);
        done.set(null, "Its session \"s1\" has finished, and opening the next session (continuous) is off");
        Autopilot.sessionFinished(ExperimentInstance.findById(ours.id));
        assertEquals(0L, due.get(null));
        status = json(get(user)).get("status");
        assertTrue(status.get("done").asBoolean());
        assertEquals("done", status.get("problemKind").asText());
        assertTrue(status.get("nextOpenAt").isNull());
        assertEquals(3, opened.size());
    }

    @Test
    public void withNoExperimentNoSessionIsInItsWay() throws Exception {
        User user = user("nothing chosen");
        Experiment other = createExperiment("Someone's");
        runInEngine(live("theirs", other), user);
        Autopilot.Effective off = Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(new HashMap<String, Object>())));
        assertNull(off.settings.experimentId);
        JsonNode status = statusJson(off);
        assertTrue(status.get("engineSession").isNull());
        assertTrue(status.get("problem").isNull());
        assertTrue(status.get("problemKind").isNull());
        // With one chosen, another experiment's live session is named.
        AutopilotSettings mine = new AutopilotSettings();
        mine.enabled = true;
        mine.experimentId = createExperiment("Mine").id;
        JsonNode waiting = statusJson(Autopilot.effective(mine, new Configuration(ConfigFactory.parseMap(new HashMap<String, Object>()))));
        assertEquals("theirs", waiting.get("engineSession").get("name").asText());
    }

    @Test
    public void pausedAndDoneAreKeptOverARestartAndResumeEndsThem() throws Exception {
        User user = user("restarted");
        Experiment experiment = createExperiment("Kept paused");
        own(user, experiment);
        post(user, settings(experiment.id, true));
        awaitOpens(opened, 1);

        // A researcher stopped its session: paused, and kept.
        ExperimentInstance ours = live("ours", experiment);
        itsSession(ours);
        stoppedBy(ExperimentInstance.findById(ours.id), user);
        assertNotNull(AutopilotSettings.loadHalt());

        // The server restarts: nothing opens by itself, and the console is told why.
        forgetHaltInMemory();
        restoreHalt();
        watchdog();
        Thread.sleep(200);
        assertEquals(1, opened.size());
        JsonNode status = json(get(user)).get("status");
        assertTrue(status.get("paused").asBoolean());
        assertEquals("paused", status.get("problemKind").asText());
        assertTrue(status.get("problem").asText(), status.get("problem").asText().contains("Resume"));

        // Resume, which changes no settings, lifts it and opens a session.
        Result resumed = resume(user);
        assertEquals(contentAsString(resumed), OK, status(resumed));
        assertFalse(json(resumed).get("status").get("paused").asBoolean());
        awaitOpens(opened, 2);
        assertTrue(opened.get(1), opened.get(1).contains("resumed by " + user.email));
        assertNull(AutopilotSettings.loadHalt());

        // Done (its one session finished with continuous off) is kept the same way.
        Autopilot.sessionFinished(ExperimentInstance.findById(ours.id));
        assertNotNull(AutopilotSettings.loadHalt().doneBecause);
        forgetHaltInMemory();
        restoreHalt();
        status = json(get(user)).get("status");
        assertTrue(status.get("done").asBoolean());
        assertEquals("done", status.get("problemKind").asText());
        watchdog();
        Thread.sleep(200);
        assertEquals(2, opened.size());

        // One kept for another experiment than the one it runs now is forgotten.
        AutopilotSettings.saveHalt(987654321L, "its session \"elsewhere\" was stopped by someone", null);
        forgetHaltInMemory();
        restoreHalt();
        assertFalse(json(get(user)).get("status").get("paused").asBoolean());
        assertNull(AutopilotSettings.loadHalt());
    }

    @Test
    public void runFromApplicationConfItIsToldToResumeNeverToSave() throws Exception {
        User user = user("configured");
        Experiment experiment = createExperiment("Configured " + UUID.randomUUID());
        Map<String, Object> file = new HashMap<String, Object>();
        file.put("breadboard.autopilot.experiment", experiment.name);
        Autopilot.Effective fromFile = Autopilot.effective(null, new Configuration(ConfigFactory.parseMap(file)));
        assertEquals(Autopilot.CONFIG, fromFile.source);

        Field paused = Autopilot.class.getDeclaredField("pausedBecause");
        paused.setAccessible(true);
        paused.set(null, "its session \"s1\" was stopped by " + user.email);
        JsonNode status = statusJson(fromFile);
        assertEquals("paused", status.get("problemKind").asText());
        String text = status.get("problem").asText();
        assertTrue(text, text.contains("Resume"));
        assertFalse(text, text.toLowerCase().contains("save"));

        paused.set(null, null);
        Field done = Autopilot.class.getDeclaredField("doneBecause");
        done.setAccessible(true);
        done.set(null, "Its session \"s1\" has finished, and opening the next session (continuous) is off");
        status = statusJson(fromFile);
        assertEquals("done", status.get("problemKind").asText());
        assertTrue(status.get("done").asBoolean());
        text = status.get("problem").asText();
        // The page's button for a done autopilot, whoever is in charge.
        assertTrue(text, text.contains("Open another session"));
        assertFalse(text, text.toLowerCase().contains("save"));

        // Resume needs it switched on (here nothing is saved and the server's file names nothing).
        Result off = resume(user);
        assertEquals(BAD_REQUEST, status(off));
        assertEquals(UNAUTHORIZED, status(callAction(controllers.routes.ref.AutopilotController.resume(),
            fakeRequest(POST, "/autopilot/resume"))));
    }

    @Test
    public void itOpensNothingOnceTheApplicationHasStopped() throws Exception {
        User user = user("stopping");
        Experiment experiment = createExperiment("Stopping");
        own(user, experiment);
        post(user, settings(experiment.id, true));
        awaitOpens(opened, 1);
        Field stopped = Autopilot.class.getDeclaredField("stopped");
        stopped.setAccessible(true);
        try {
            stopped.set(null, true);
            watchdog();
            Thread.sleep(200);
            assertEquals(1, opened.size());
        } finally {
            stopped.set(null, false);
        }
        watchdog();
        awaitOpens(opened, 2);
    }

    @Test
    public void itCanBeSwitchedOffAfterItsExperimentWasDeleted() {
        User user = user("tidy");
        Experiment experiment = createExperiment("Deleted later");
        own(user, experiment);
        post(user, settings(experiment.id, false));
        AutopilotSettings saved = AutopilotSettings.load();
        saved.enabled = true;
        saved.experimentId = 987654321L;   // as if deleted
        saved.save();

        // Turning it on for it is still refused...
        assertEquals(BAD_REQUEST, status(post(user, settings(987654321L, true))));
        // ...but switching it off works, and forgets the experiment.
        Result off = post(user, settings(987654321L, false));
        assertEquals(contentAsString(off), OK, status(off));
        AutopilotSettings now = AutopilotSettings.load();
        assertFalse(now.enabled);
        assertNull(now.experimentId);
        assertTrue(json(off).get("settings").get("experimentId").isNull());
    }
}
