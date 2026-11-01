import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.WebSocket;
import play.test.FakeRequest;
import security.ParticipantAuth;
import security.PreviewTokens;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Previews of participant pages in the researcher's console: POST /preview-token gives a token for one
 * page (experiment, session, client id), good for 12 hours and dropped at sign-out and password
 * change, which opens that page's data and websocket as the researcher's sign-in does, without ever
 * touching the participant's own seat. The page asked for as a preview is sandboxed, and its data
 * can be read from the opaque origin it then runs in.
 */
public class PreviewTokenTest extends BaseTest {

    private User researcher;
    private Experiment experiment;
    private ExperimentInstance instance;
    private String e;
    private String i;

    @Before
    public void setUp() {
        ParticipantAuth.reset();
        PreviewTokens.reset();
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        researcher = createUser("previewer-" + UUID.randomUUID() + "@test.org", "Previewer", "secret-123", lang);
        experiment = createExperiment("Previewed");
        researcher.ownedExperiments.add(experiment);
        researcher.saveManyToManyAssociations("ownedExperiments");
        instance = createInstance("previewed-session", experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        e = experiment.id.toString();
        i = instance.id.toString();
    }

    @After
    public void tearDown() {
        ParticipantAuth.reset();
        PreviewTokens.reset();
    }

    private Result mint(User as, Object experimentId, Object instanceId, Object clientId) {
        return mint(as.uid, experimentId, instanceId, clientId);
    }

    private static Result mint(String uid, Object experimentId, Object instanceId, Object clientId) {
        ObjectNode body = Json.newObject();
        body.put("experimentId", Json.toJson(experimentId));
        body.put("experimentInstanceId", Json.toJson(instanceId));
        body.put("clientId", Json.toJson(clientId));
        return route(fakeRequest(POST, "/preview-token").withJsonBody(body).withSession("uid", uid));
    }

    private String token(Object experimentId, Object instanceId, String clientId) {
        return token(researcher.uid, experimentId, instanceId, clientId);
    }

    private static String token(String uid, Object experimentId, Object instanceId, String clientId) {
        Result result = mint(uid, experimentId, instanceId, clientId);
        assertEquals(contentAsString(result), OK, status(result));
        return Json.parse(contentAsString(result)).get("token").asText();
    }

    private Result state(String experimentId, String instanceId, String clientId, String token) {
        return route(fakeRequest(GET, "/game/" + experimentId + "/" + instanceId + "/" + clientId + "/state")
            .withHeader(ParticipantAuth.HEADER, token));
    }

    // ---- POST /preview-token ------------------------------------------------------------------------

    @Test
    public void aTokenIsGoodForTwelveHours() throws Exception {
        long before = System.currentTimeMillis();
        Result result = mint(researcher, experiment.id, instance.id, "P1");
        assertEquals(contentAsString(result), OK, status(result));
        JsonNode json = Json.parse(contentAsString(result));
        assertTrue(json.get("token").asText().length() >= 32);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        long expiresAt = iso.parse(json.get("expiresAt").asText()).getTime();
        assertTrue(json.get("expiresAt").asText(), Math.abs(expiresAt - (before + 12L * 3600 * 1000)) < 60000);

        String token = json.get("token").asText();
        assertEquals(OK, status(state(e, i, "P1", token)));
        PreviewTokens.moveClockForTests(12L * 3600 * 1000 - 60000);
        assertEquals("still good just before", OK, status(state(e, i, "P1", token)));
        PreviewTokens.moveClockForTests(61000);
        assertEquals("gone after 12 hours", UNAUTHORIZED, status(state(e, i, "P1", token)));
    }

    @Test
    public void signingInIsRequired() {
        ObjectNode body = Json.newObject();
        body.put("experimentId", experiment.id);
        body.put("experimentInstanceId", instance.id);
        body.put("clientId", "P1");
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(POST, "/preview-token").withJsonBody(body))));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(POST, "/preview-token").withJsonBody(body)
            .withSession("uid", "stale-" + UUID.randomUUID()))));
    }

    @Test
    public void aBadBodyOrAnotherExperimentsSessionIsRefused() {
        Experiment other = createExperiment("Not this one");
        ExperimentInstance othersSession = createInstance("theirs", other);
        Object[][] refused = {
            {"x", instance.id, "P1"},                    // not a number
            {experiment.id, null, "P1"},                 // missing
            {experiment.id, instance.id, null},          // missing
            {experiment.id, instance.id, "  "},          // blank, as the login refuses
            {experiment.id, instance.id, 5},             // not text
            {987654321L, instance.id, "P1"},             // no such experiment
            {experiment.id, 987654321L, "P1"},           // no such session
            {experiment.id, othersSession.id, "P1"},     // another experiment's session
        };
        for (Object[] r : refused) {
            Result result = mint(researcher, r[0], r[1], r[2]);
            assertEquals(java.util.Arrays.toString(r) + ": " + contentAsString(result), BAD_REQUEST, status(result));
            assertTrue(Json.parse(contentAsString(result)).get("error").asText().length() > 0);
        }
        Result notJson = route(fakeRequest(POST, "/preview-token").withFormUrlEncodedBody(form("clientId", "P1"))
            .withSession("uid", researcher.uid));
        assertEquals(BAD_REQUEST, status(notJson));
        assertTrue(Json.parse(contentAsString(notJson)).has("error"));
    }

    @Test
    public void anyExperimentOnTheServerCanBePreviewed() {
        // Every researcher works on every experiment, so also on one another account made.
        Experiment other = createExperiment("Someone else's");
        Result result = mint(researcher, other.id, 0, "P1");
        assertEquals(contentAsString(result), OK, status(result));
    }

    @Test
    public void computerPlayersAndTheTestSessionCanBePreviewed() {
        String ai = token(experiment.id, instance.id, "_1");
        assertEquals(OK, status(state(e, i, "_1", ai)));
        // Numbers may come as text too.
        String test = token(e, "0", "3");
        Result result = state(e, "0", "3", test);
        assertEquals(OK, status(result));
        assertEquals("3", Json.parse(contentAsString(result)).get("clientId").asText());
    }

    // ---- what a token opens ---------------------------------------------------------------------------

    @Test
    public void aTokenOpensExactlyItsOwnPage() {
        String token = token(experiment.id, instance.id, "P1");
        ExperimentInstance second = createInstance("second", experiment);
        Experiment other = createExperiment("Other");

        Result viaQuery = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state?" + ParticipantAuth.QUERY_PARAM + "=" + token));
        assertEquals(OK, status(viaQuery));
        Result viaHeader = state(e, i, "P1", token);
        assertEquals(OK, status(viaHeader));
        assertTrue("its websocket URL carries the token",
            Json.parse(contentAsString(viaHeader)).get("connectSocket").asText().endsWith("?" + ParticipantAuth.QUERY_PARAM + "=" + token));

        assertEquals("another player", UNAUTHORIZED, status(state(e, i, "P2", token)));
        assertEquals("another session", UNAUTHORIZED, status(state(e, second.id.toString(), "P1", token)));
        assertEquals("another experiment", UNAUTHORIZED, status(state(other.id.toString(), i, "P1", token)));
        assertEquals("the test session", UNAUTHORIZED, status(state(e, "0", "P1", token)));
        assertEquals("a wrong token", UNAUTHORIZED, status(state(e, i, "P1", token + "x")));
    }

    @Test
    public void aPreviewLeavesTheParticipantsSeatAlone() {
        // Nobody holds the seat yet: previewing it claims nothing, so the participant can still sign in.
        String preview = token(experiment.id, instance.id, "P1");
        assertFalse(ParticipantAuth.isClaimed(i, "P1"));
        assertFalse("not a seat token", ParticipantAuth.verify(i, "P1", preview));

        // Someone holds a seat: their token keeps working, beside the preview's, and neither opens the other's page.
        String seat = ParticipantAuth.claim(i, "P2");
        String previewOfP2 = token(experiment.id, instance.id, "P2");
        assertTrue(ParticipantAuth.verify(i, "P2", seat));
        assertFalse(ParticipantAuth.verify(i, "P2", previewOfP2));
        assertEquals(OK, status(state(e, i, "P2", seat)));
        assertEquals(OK, status(state(e, i, "P2", previewOfP2)));
        assertEquals(UNAUTHORIZED, status(state(e, i, "P1", seat)));
        assertNull("no seat cookie is set", cookie(ParticipantAuth.cookieName(i), mint(researcher, experiment.id, instance.id, "P2")));
    }

    @Test
    public void signingOutOrChangingThePasswordDropsTheAccountsTokens() {
        User colleague = createUser("colleague-" + UUID.randomUUID() + "@test.org", "Colleague", "secret-456",
            Language.find.where().eq("code", "eng").findUnique());
        Result theirs = mint(colleague, experiment.id, instance.id, "P9");
        String colleagues = Json.parse(contentAsString(theirs)).get("token").asText();

        String first = token(experiment.id, instance.id, "P1");
        route(fakeRequest(POST, "/logout").withSession("uid", researcher.uid));
        assertEquals("signed out", UNAUTHORIZED, status(state(e, i, "P1", first)));
        assertEquals("another account's stay", OK, status(state(e, i, "P9", colleagues)));

        // Signed in again; a password change on the Settings page.
        Result signIn = route(fakeRequest(POST, "/login").withFormUrlEncodedBody(form("email", researcher.email, "password", "secret-123")));
        assertEquals(contentAsString(signIn), OK, status(signIn));
        String uid = sessionValue(signIn, "uid");
        String second = token(uid, experiment.id, instance.id, "P1");
        Result changed = route(fakeRequest(POST, "/saveUserSettings").withSession("uid", uid)
            .withFormUrlEncodedBody(form("currentPassword", "secret-123", "newPassword", "secret-789", "confirmPassword", "secret-789")));
        assertTrue(contentAsString(changed), Json.parse(contentAsString(changed)).get("success").asBoolean());
        assertEquals("password changed", UNAUTHORIZED, status(state(e, i, "P1", second)));
        assertEquals(OK, status(state(e, i, "P9", colleagues)));
    }

    @Test
    public void aPasswordChangeOverTheConsoleSocketDropsThemToo() throws Throwable {
        String token = token(experiment.id, instance.id, "P1");
        assertEquals(OK, status(state(e, i, "P1", token)));
        AdminSocketsTest.Tab tab = new AdminSocketsTest.Tab(researcher.uid);
        try {
            tab.send("SaveUserSettings", "currentPassword", "secret-123", "newPassword", "secret-789", "confirmPassword", "secret-789");
            long until = System.currentTimeMillis() + 10000;
            while (status(state(e, i, "P1", token)) == OK && System.currentTimeMillis() < until) Thread.sleep(25);
            assertEquals(UNAUTHORIZED, status(state(e, i, "P1", token)));
        } finally {
            tab.close();
        }
    }

    // ---- the participant websocket --------------------------------------------------------------------

    /**
     * Whether the participant websocket takes this handshake: a refused one is closed at once, after a
     * frame that says why (ParticipantReconnectTest). (The session here is over, so a taken one gets
     * its final screen and joins nothing.)
     */
    private static boolean socketAccepted(String experimentId, String instanceId, String clientId, FakeRequest handshake) throws Exception {
        Http.Context.current.set(play.core.j.JavaHelpers$.MODULE$.createJavaContext(handshake.getWrappedRequest()));
        final AtomicBoolean closed = new AtomicBoolean(false);
        final List<JsonNode> frames = new CopyOnWriteArrayList<JsonNode>();
        try {
            WebSocket<JsonNode> socket = controllers.ClientController.connectClient(experimentId, instanceId, clientId);
            socket.onReady(new WebSocket.In<JsonNode>(), new WebSocket.Out<JsonNode>() {
                public void write(JsonNode frame) { frames.add(frame); }
                public void close() { closed.set(true); }
            });
        } finally {
            Http.Context.current.remove();
        }
        long until = System.currentTimeMillis() + 5000;
        while (!closed.get() && frames.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
        assertTrue("answered one way or the other", closed.get() || !frames.isEmpty());
        return !closed.get();
    }

    @Test
    public void theWebsocketTakesThePreviewTokenAsTheResearchersSignInWouldIncludingTheTestSession() throws Exception {
        instance.status = ExperimentInstance.Status.FINISHED;
        instance.save();
        String player = "pv-" + UUID.randomUUID();
        String token = token(experiment.id, instance.id, player);
        String base = "/game/" + e + "/" + i + "/" + player + "/connect";
        assertTrue(socketAccepted(e, i, player, fakeRequest(GET, base + "?" + ParticipantAuth.QUERY_PARAM + "=" + token)));
        assertTrue(socketAccepted(e, i, player, fakeRequest(GET, base).withHeader(ParticipantAuth.HEADER, token)));
        assertFalse(socketAccepted(e, i, player, fakeRequest(GET, base)));
        assertFalse(socketAccepted(e, i, "someone-else", fakeRequest(GET, base + "?" + ParticipantAuth.QUERY_PARAM + "=" + token)));
        // The researcher's own sign-in still works (the previous console's Testing panel).
        assertTrue(socketAccepted(e, i, player, fakeRequest(GET, base).withSession("uid", researcher.uid)));

        String test = token(experiment.id, 0, player);
        assertTrue(socketAccepted(e, "0", player, fakeRequest(GET, "/game/" + e + "/0/" + player + "/connect?"
            + ParticipantAuth.QUERY_PARAM + "=" + test)));
        // A seat token for the test session still opens nothing there.
        String seat = ParticipantAuth.claim("0", player);
        assertFalse(socketAccepted(e, "0", player, fakeRequest(GET, "/game/" + e + "/0/" + player + "/connect?"
            + ParticipantAuth.QUERY_PARAM + "=" + seat)));
    }

    // ---- a preview runs sandboxed, and reads its data from there ---------------------------------------

    @Test
    public void thePreviewPageIsSandboxedAndOnlyThePreview() {
        Result preview = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/connected?preview=1"));
        assertEquals(OK, status(preview));
        assertEquals("sandbox allow-scripts allow-forms allow-modals allow-popups", header("Content-Security-Policy", preview));
        Result page = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/connected"));
        assertEquals(OK, status(page));
        assertNull(header("Content-Security-Policy", page));
    }

    @Test
    public void theStateCanBeReadFromAnyOriginButNeverWithCredentials() {
        String token = token(experiment.id, instance.id, "P1");
        for (Result result : new Result[]{state(e, i, "P1", token), state(e, i, "P1", "nope")}) {
            assertEquals("*", header("Access-Control-Allow-Origin", result));
            assertNull(header("Access-Control-Allow-Credentials", result));
        }
        Result preflight = route(fakeRequest("OPTIONS", "/game/" + e + "/" + i + "/P1/state")
            .withHeader("Origin", "null")
            .withHeader("Access-Control-Request-Method", "GET")
            .withHeader("Access-Control-Request-Headers", "x-breadboard-token"));
        assertTrue(String.valueOf(status(preflight)), status(preflight) == NO_CONTENT || status(preflight) == OK);
        assertEquals("*", header("Access-Control-Allow-Origin", preflight));
        assertTrue(header("Access-Control-Allow-Methods", preflight).contains("GET"));
        assertTrue(header("Access-Control-Allow-Headers", preflight).toLowerCase().contains("x-breadboard-token"));
        assertNull(header("Access-Control-Allow-Credentials", preflight));
    }

    private static Map<String, String> form(String... pairs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int k = 0; k < pairs.length; k += 2) m.put(pairs[k], pairs[k + 1]);
        return m;
    }
}
