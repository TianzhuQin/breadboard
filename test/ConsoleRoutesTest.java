import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import controllers.ExperimentController;
import controllers.ImagesController;
import models.*;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * HTTP routes the new console relies on, and the old bugs in them (INVENTORY.md §3): parameter
 * values sent as JSON booleans or numbers, the password change trusting the posted email, CSV
 * escaping, imports leaving empty experiments behind, image upload failures answered as 200,
 * customize refusing an empty text, file mode's steps folder, and logout leaving the console token
 * valid.
 */
public class ConsoleRoutesTest extends BaseTest {

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static Map<String, String> form(String... pairs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    // ---- parameters (§3.3.3) ---------------------------------------------------------------------

    private static Result createParameter(User user, Experiment experiment, String body) {
        return callAction(controllers.routes.ref.ParametersController.createParameter(experiment.id),
            fakeRequest().withJsonBody(Json.parse(body)).withSession("uid", user.uid));
    }

    @Test
    public void parameterValuesMayBeBooleansAndNumbers() {
        User user = user("params");
        Experiment experiment = createExperiment("Parameters");

        Result flag = createParameter(user, experiment,
            "{\"name\": \"showNetwork\", \"type\": \"Boolean\", \"defaultVal\": true, \"description\": \"d\"}");
        assertEquals(contentAsString(flag), OK, status(flag));
        assertEquals("true", Json.parse(contentAsString(flag)).get("parameter").get("defaultVal").asText());

        Result rounds = createParameter(user, experiment,
            "{\"name\": \"rounds\", \"type\": \"Integer\", \"minVal\": 1, \"maxVal\": 20, \"defaultVal\": 5}");
        assertEquals(OK, status(rounds));
        Result rate = createParameter(user, experiment,
            "{\"name\": \"rate\", \"type\": \"Decimal\", \"minVal\": \"0\", \"maxVal\": \"1\", \"defaultVal\": 0.25}");
        assertEquals(OK, status(rate));

        Experiment stored = Experiment.findById(experiment.id);
        Parameter showNetwork = stored.getParameterByName("showNetwork");
        assertEquals("true", showNetwork.defaultVal);
        assertEquals("", showNetwork.minVal);
        Parameter r = stored.getParameterByName("rounds");
        assertEquals("1", r.minVal);
        assertEquals("20", r.maxVal);
        assertEquals("5", r.defaultVal);
        assertEquals("0.25", stored.getParameterByName("rate").defaultVal);
        assertNull("absent stays absent", r.description);
    }

    @Test
    public void aParameterNeedsANameATypeAndANewName() {
        User user = user("params2");
        Experiment experiment = createExperiment("Parameters 2");
        assertEquals(BAD_REQUEST, status(createParameter(user, experiment, "{\"name\": \"x\", \"defaultVal\": \"1\"}")));
        assertEquals(BAD_REQUEST, status(createParameter(user, experiment, "{\"name\": \"x\", \"type\": \"Colour\"}")));
        assertEquals(BAD_REQUEST, status(createParameter(user, experiment, "{\"name\": \" \", \"type\": \"Text\"}")));
        assertEquals(OK, status(createParameter(user, experiment, "{\"name\": \"x\", \"type\": \"Text\", \"defaultVal\": \"\"}")));
        Result twice = createParameter(user, experiment, "{\"name\": \"x\", \"type\": \"Integer\"}");
        assertEquals(BAD_REQUEST, status(twice));
        assertTrue(contentAsString(twice).contains("already"));
        assertEquals(1, Experiment.findById(experiment.id).parameters.size());
    }

    // ---- customize ---------------------------------------------------------------------------------

    @Test
    public void customizedTextsCanBeCleared() {
        User user = user("customizer");
        Experiment experiment = createExperiment("Customized");
        experiment.setStyle("body { color: red }");
        experiment.setClientHtml("<p>custom</p>");
        experiment.setClientGraph("// graph");
        experiment.save();

        assertEquals(OK, status(callAction(controllers.routes.ref.CustomizeController.updateStyle(experiment.id),
            fakeRequest().withJsonBody(Json.parse("{\"style\": \"\"}")).withSession("uid", user.uid))));
        assertEquals(OK, status(callAction(controllers.routes.ref.CustomizeController.updateClientHtml(experiment.id),
            fakeRequest().withJsonBody(Json.parse("{\"clientHtml\": \"\"}")).withSession("uid", user.uid))));
        assertEquals(OK, status(callAction(controllers.routes.ref.CustomizeController.updateClientGraph(experiment.id),
            fakeRequest().withJsonBody(Json.parse("{\"clientGraph\": null}")).withSession("uid", user.uid))));
        Experiment stored = Experiment.findById(experiment.id);
        assertEquals("", stored.getStyle());
        assertEquals("", stored.getClientHtml());
        assertEquals("", stored.getClientGraph());

        Result missing = callAction(controllers.routes.ref.CustomizeController.updateStyle(experiment.id),
            fakeRequest().withJsonBody(Json.parse("{\"css\": \"x\"}")).withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(missing));
        assertEquals(OK, status(callAction(controllers.routes.ref.CustomizeController.updateStyle(experiment.id),
            fakeRequest().withJsonBody(Json.parse("{\"style\": \"p { margin: 0 }\"}")).withSession("uid", user.uid))));
        assertEquals("p { margin: 0 }", Experiment.findById(experiment.id).getStyle());
    }

    // ---- password change (§3.1.3) ----------------------------------------------------------------

    private static Result changePassword(User session, Map<String, String> body) {
        return route(fakeRequest(POST, "/saveUserSettings").withFormUrlEncodedBody(body).withSession("uid", session.uid));
    }

    private static String error(Result result) {
        assertEquals(OK, status(result));
        JsonNode json = Json.parse(contentAsString(result));
        return json.get("success").asBoolean() ? null : json.get("error").asText();
    }

    @Test
    public void thePasswordChangeIsForTheSignedInAccountOnly() {
        User alice = user("alice");
        User bob = user("bob");

        // Bob's email with Bob's password, from Alice's session: refused, and nobody's password changes.
        String refused = error(changePassword(alice, form("email", bob.email, "currentPassword", "secret-1",
            "newPassword", "taken-over-1", "confirmPassword", "taken-over-1")));
        assertNotNull(refused);
        assertTrue(User.findByEmail(bob.email).passwordMatches("secret-1"));
        assertTrue(User.findByEmail(alice.email).passwordMatches("secret-1"));

        assertEquals("Current password doesn't match your password", error(changePassword(alice, form("email", alice.email,
            "currentPassword", "wrong", "newPassword", "a-new-one-1", "confirmPassword", "a-new-one-1"))));
        assertEquals("Current password is missing", error(changePassword(alice, form("email", alice.email,
            "newPassword", "a-new-one-1", "confirmPassword", "a-new-one-1"))));
        assertEquals("New password is missing", error(changePassword(alice, form("currentPassword", "secret-1"))));
        assertTrue(error(changePassword(alice, form("currentPassword", "secret-1",
            "newPassword", "short", "confirmPassword", "short"))).contains("at least 8"));
        assertEquals("Confirm Password doesn't match the new password", error(changePassword(alice, form(
            "currentPassword", "secret-1", "newPassword", "a-new-one-1", "confirmPassword", "a-new-one-2"))));
        assertTrue(User.findByEmail(alice.email).passwordMatches("secret-1"));

        String oldToken = alice.uid;
        Result changedResult = changePassword(alice, form("email", alice.email.toUpperCase(), "currentPassword", "secret-1",
            "newPassword", "a-new-one-1", "confirmPassword", "a-new-one-1"));
        assertNull(error(changedResult));
        User changed = User.findByEmail(alice.email);
        assertTrue(changed.passwordMatches("a-new-one-1"));
        assertTrue("stored hashed", changed.password.startsWith("$2"));

        // The sign-in token is replaced: whoever held the old one is signed out; this session goes on
        // with the new one, which the answer carries for the console's websocket.
        String newToken = Json.parse(contentAsString(changedResult)).get("uid").asText();
        assertNotEquals(oldToken, newToken);
        assertEquals(newToken, changed.uid);
        assertEquals(newToken, session(changedResult).get("uid"));
        assertNull(User.findByUID(oldToken));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/state").withSession("uid", oldToken))));
        assertEquals(OK, status(route(fakeRequest(GET, "/state").withSession("uid", newToken))));

        // JSON works too (no email: the session says whose).
        ObjectNode json = Json.newObject();
        json.put("currentPassword", "a-new-one-1");
        json.put("newPassword", "another-one-2");
        json.put("confirmPassword", "another-one-2");
        Result viaJson = route(fakeRequest(POST, "/saveUserSettings").withJsonBody(json).withSession("uid", newToken));
        assertNull(error(viaJson));
        assertTrue(User.findByEmail(alice.email).passwordMatches("another-one-2"));
    }

    // ---- logout ------------------------------------------------------------------------------------

    @Test
    public void signingOutRetiresTheConsoleToken() {
        User user = user("leaver");
        String token = user.uid;
        assertEquals(OK, status(route(fakeRequest(GET, "/state").withSession("uid", token))));

        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/logout").withSession("uid", token))));
        assertNull("the old token no longer finds the account", User.findByUID(token));
        String fresh = User.findByEmail(user.email).uid;
        assertNotNull(fresh);
        assertNotEquals(token, fresh);
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/state").withSession("uid", token))));

        // A stale session signing out leaves the account's current sign-in alone.
        route(fakeRequest(GET, "/logout").withSession("uid", "stale-" + UUID.randomUUID()));
        assertEquals(fresh, User.findByEmail(user.email).uid);
        route(fakeRequest(GET, "/logout"));
        assertEquals(fresh, User.findByEmail(user.email).uid);
    }

    // ---- CSV (§3.6.2) ------------------------------------------------------------------------------

    @Test
    public void csvFieldsAreQuotedAndEscaped() {
        User user = user("csv");
        Experiment experiment = createExperiment("CSV");
        ExperimentInstance instance = createInstance("pilot \"A\", morning", experiment);
        Data a = new Data();
        a.name = "condition \"x\"";
        a.value = "say \"hi\", then go";
        a.experimentInstance = instance;
        a.save();
        Data b = new Data();
        b.name = "empty";
        b.value = null;
        b.experimentInstance = instance;
        b.save();
        ExperimentInstance other = createInstance("other", experiment);
        Data c = new Data();
        c.name = "only here";
        c.value = "1";
        c.experimentInstance = other;
        c.save();

        Result result = route(fakeRequest(GET, "/csv/instances/" + experiment.id).withSession("uid", user.uid));
        assertEquals(OK, status(result));
        String[] lines = contentAsString(result).split("\n");
        assertEquals("\"id\",\"instance\",\"status\",\"created_at\",\"condition \"\"x\"\"\",\"empty\",\"only here\"", lines[0]);
        assertTrue(lines[1], lines[1].startsWith("\"" + instance.id + "\",\"pilot \"\"A\"\", morning\",\"STOPPED\",\""));
        assertTrue(lines[1], lines[1].endsWith(",\"say \"\"hi\"\", then go\",\"\",\"\""));
        assertTrue(lines[2], lines[2].endsWith(",\"\",\"\",\"1\""));

        Event event = new Event();
        event.name = "chose \"left\"";
        event.datetime = new Date(0L);
        event.experimentInstance = instance;
        event.save();
        EventData data = new EventData();
        data.name = "note, \"quoted\"";
        data.value = null;
        data.event = event;
        data.save();

        Result events = route(fakeRequest(GET, "/csv/data/" + instance.id).withSession("uid", user.uid));
        assertEquals(OK, status(events));
        String[] eventLines = contentAsString(events).split("\n");
        assertEquals("\"id\",\"event\",\"event_date\",\"data_name\",\"data_value\"", eventLines[0]);
        assertEquals("\"" + event.id + "\",\"chose \"\"left\"\"\",\"1970-01-01T00:00:00:000Z\",\"note, \"\"quoted\"\"\",\"\"",
            eventLines[1]);

        assertEquals(BAD_REQUEST, status(route(fakeRequest(GET, "/csv/instances/987654321").withSession("uid", user.uid))));
        assertEquals(BAD_REQUEST, status(route(fakeRequest(GET, "/csv/data/987654321").withSession("uid", user.uid))));
        assertEquals(BAD_REQUEST, status(route(fakeRequest(GET, "/csv/data/0").withSession("uid", user.uid))));
    }

    // ---- import (§3.2.8, §3.6.4) ---------------------------------------------------------------------

    private static File zip(Map<String, String> entries) throws Exception {
        File file = File.createTempFile("bb-import", ".zip");
        file.deleteOnExit();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return file;
    }

    @Test
    public void anInvalidUploadCreatesNoExperiment() throws Exception {
        User user = user("importer");
        int before = Experiment.findAll().size();

        File notAZip = File.createTempFile("bb-import", ".zip");
        notAZip.deleteOnExit();
        FileUtils.writeStringToFile(notAZip, "this is not a zip");
        Result notZip = ExperimentController.importZip(user, "Broken", notAZip);
        assertEquals(BAD_REQUEST, status(notZip));
        assertTrue(contentAsString(notZip).contains("not a zip"));

        Map<String, String> noFolders = new HashMap<String, String>();
        noFolders.put("readme.txt", "hello");
        Result empty = ExperimentController.importZip(user, "Empty", zip(noFolders));
        assertEquals(BAD_REQUEST, status(empty));
        assertTrue(contentAsString(empty).contains("No Steps or Content"));

        assertEquals("no orphan experiments", before, Experiment.findAll().size());
    }

    @Test
    public void aZipThatWouldUnpackOutsideItsFolderIsRefused() throws Exception {
        User user = user("slip");
        int before = Experiment.findAll().size();
        String evil = "evil-" + UUID.randomUUID() + ".groovy";
        File outside = new File(System.getProperty("java.io.tmpdir"), evil);
        Map<String, String> entries = new HashMap<String, String>();
        entries.put("Steps/InitStep.groovy", "initStep = stepFactory.createStep('InitStep')");
        entries.put("../" + evil, "println 'pwned'");
        try {
            Result result = ExperimentController.importZip(user, "Slip", zip(entries));
            assertEquals(contentAsString(result), BAD_REQUEST, status(result));
            assertTrue(contentAsString(result), contentAsString(result).contains("outside its folder"));
            assertFalse("nothing was written next to the temporary folder", outside.exists());
            assertEquals("no experiment", before, Experiment.findAll().size());
        } finally {
            FileUtils.deleteQuietly(outside);
        }
    }

    @Test
    public void anExportWithoutContentCanBeImported() throws Exception {
        User user = user("importer2");
        Map<String, String> entries = new HashMap<String, String>();
        entries.put(".breadboard", "{\"version\":\"v2.4.0\",\"experimentName\":\"x\",\"experimentUid\":\"u\"}");
        entries.put("style.css", "");
        entries.put("client-html.html", "<p>page</p>");
        entries.put("client-graph.js", "");
        entries.put("Steps/InitStep.groovy", "initStep = stepFactory.createStep('InitStep')");
        entries.put("parameters.csv", "Name,Type,Min.,Max.,Default,Short Description\n");
        Result result = ExperimentController.importZip(user, "My study", zip(entries));
        assertEquals(contentAsString(result), OK, status(result));
        Experiment imported = Experiment.findById(Json.parse(contentAsString(result)).get("id").asLong());
        assertEquals("My study", imported.name);
        assertEquals(1, imported.getSteps().size());
        assertEquals("<p>page</p>", imported.getClientHtml());
        assertNotNull(User.findByEmail(user.email).getExperimentByName("My study"));
    }

    @Test
    public void everyBundledExportStillImports() throws Exception {
        controllers.LanguageController.seedLanguages();
        User user = user("bundled");
        File[] zips = new File("test/data").listFiles((dir, name) -> name.endsWith(".zip"));
        assertNotNull(zips);
        assertTrue(zips.length >= 5);
        for (File zip : zips) {
            Result result = ExperimentController.importZip(user, zip.getName(), zip);
            assertEquals(zip.getName() + ": " + contentAsString(result), OK, status(result));
            Experiment imported = Experiment.findById(Json.parse(contentAsString(result)).get("id").asLong());
            assertFalse(zip.getName(), imported.getSteps().isEmpty() && imported.getContent().isEmpty());
        }
    }

    @Test
    public void theImportNameIsDecodedFromThePath() {
        User user = user("namer");
        // " " decoded and trimmed is no name at all; without decoding "%20" would pass as one.
        Result blank = route(fakeRequest(POST, "/experiment/import/%20").withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(blank));
        assertTrue(contentAsString(blank), contentAsString(blank).contains("provide a name"));
        Result named = route(fakeRequest(POST, "/experiment/import/My%20study").withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(named));
        assertFalse(contentAsString(named).contains("provide a name"));
    }

    // ---- image upload (§3.3.8) -----------------------------------------------------------------------

    @Test
    public void aFailedImageUploadIsA400WithAMessage() throws Exception {
        User user = user("uploader");
        Experiment experiment = createExperiment("Images");

        Result notMultipart = route(fakeRequest(POST, "/images/upload")
            .withJsonBody(Json.parse("{\"experimentId\": 1}")).withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(notMultipart));
        assertTrue(Json.parse(contentAsString(notMultipart)).get("message").asText().length() > 10);

        File png = File.createTempFile("bb-image", ".png");
        png.deleteOnExit();
        Files.write(png.toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        Result unknown = ImagesController.storeImage("987654321", "logo.png", "image/png", png);
        assertEquals(BAD_REQUEST, status(unknown));
        assertTrue(Json.parse(contentAsString(unknown)).get("message").asText().contains("no experiment"));
        assertEquals(BAD_REQUEST, status(ImagesController.storeImage("twelve", "logo.png", "image/png", png)));
        assertEquals(BAD_REQUEST, status(ImagesController.storeImage(experiment.id.toString(), "logo.png", "image/png", null)));

        Result stored = ImagesController.storeImage(experiment.id.toString(), "logo.png", "image/png", png);
        assertEquals(OK, status(stored));
        assertEquals("logo.png", Json.parse(contentAsString(stored)).get("fileName").asText());
        Result again = ImagesController.storeImage(" " + experiment.id + " ", "C:\\Users\\me\\logo.png", null, png);
        assertEquals(OK, status(again));
        JsonNode second = Json.parse(contentAsString(again));
        assertEquals("logo-2.png", second.get("fileName").asText());
        assertEquals("image/png", Image.findById(second.get("id").asLong()).contentType);
    }

    // ---- file-mode images stay in their folder ---------------------------------------------------------

    @Test
    public void aFileModeImageIsServedButNothingOutsideItsFolder() throws Exception {
        Experiment experiment = new Experiment();
        experiment.name = "File images";
        experiment.uid = UUID.randomUUID().toString();
        experiment.fileMode = true;
        experiment.save();
        File dev = new File(play.Play.application().path(), "dev");
        boolean devExisted = dev.exists();
        File folder = new File(dev, experiment.getDirectoryName());
        try {
            File images = new File(folder, "Images");
            assertTrue(images.mkdirs());
            Files.write(new File(images, "logo.png").toPath(), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            FileUtils.writeStringToFile(new File(folder, "Steps/InitStep.groovy"), "// a step");

            Result image = route(fakeRequest(GET, "/images/" + experiment.id + "/logo.png"));
            assertEquals(OK, status(image));
            assertEquals("nosniff", header("X-Content-Type-Options", image));

            // No sign-in is needed for this route; the name is decoded before it arrives.
            for (String name : new String[]{"..%2FSteps%2FInitStep.groovy", "..%2F..%2F..%2Fconf%2Fapplication.conf",
                "..%2F..%2F..%2Fbuild.sbt", "..", "%2E%2E", "missing.png"}) {
                Result result = route(fakeRequest(GET, "/images/" + experiment.id + "/" + name));
                assertEquals(name, NOT_FOUND, status(result));
            }
        } finally {
            FileUtils.deleteQuietly(devExisted ? folder : dev);
        }
    }

    // ---- file mode's steps folder (§3.6.1) -----------------------------------------------------------

    @Test
    public void fileModeReadsTheStepsFolderExportWrites() throws Exception {
        File dir = Files.createTempDirectory("bb-filemode").toFile();
        try {
            FileUtils.writeStringToFile(new File(dir, "Steps/InitStep.groovy"), "// init");
            File steps = Experiment.fileModeStepsDirectory(dir);
            assertTrue(new File(steps, "InitStep.groovy").isFile());
            assertEquals(1, ExperimentController.getStepsFromDirectory(steps).size());

            File older = Files.createTempDirectory("bb-filemode").toFile();
            try {
                FileUtils.writeStringToFile(new File(older, "steps/OnJoinStep.groovy"), "// join");
                assertTrue(new File(Experiment.fileModeStepsDirectory(older), "OnJoinStep.groovy").isFile());
            } finally {
                FileUtils.deleteQuietly(older);
            }
        } finally {
            FileUtils.deleteQuietly(dir);
        }
    }
}
