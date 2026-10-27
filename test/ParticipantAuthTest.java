import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import security.ParticipantAuth;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * A participant's page must not be usable by someone who merely knows its URL / client id.
 */
public class ParticipantAuthTest extends BaseTest {

    private Experiment experiment;
    private ExperimentInstance instance;
    private String e;
    private String i;
    private Field engineSession;
    private Object previousEngineSession;

    @Before
    public void setUpInstance() throws Exception {
        ParticipantAuth.reset();
        experiment = createExperiment("AuthTest");
        instance = createInstance("auth-instance", experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        e = experiment.id.toString();
        i = instance.id.toString();
        // As if the engine ran this session: only the engine's session lets newcomers in.
        engineSession = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        previousEngineSession = engineSession.get(null);
        GameListener running = new GameListener();
        running.experimentInstance = instance;
        engineSession.set(null, running);
    }

    @After
    public void clearSeats() throws Exception {
        ParticipantAuth.reset();
        engineSession.set(null, previousEngineSession);
    }

    // === ParticipantAuth itself ===

    @Test
    public void claimedSeatVerifiesOnlyItsOwnToken() {
        String token = ParticipantAuth.claim(i, "P1");
        assertNotNull(token);
        assertTrue(ParticipantAuth.verify(i, "P1", token));
        assertFalse("another seat must not accept it", ParticipantAuth.verify(i, "P2", token));
        assertFalse("another instance must not accept it", ParticipantAuth.verify(i + "9", "P1", token));
        assertFalse(ParticipantAuth.verify(i, "P1", token + "x"));
        assertFalse(ParticipantAuth.verify(i, "P1", null));
    }

    @Test
    public void aSeatCannotBeClaimedTwice() {
        assertNotNull(ParticipantAuth.claim(i, "P1"));
        assertNull("second claim of the same id is refused", ParticipantAuth.claim(i, "P1"));
        ParticipantAuth.release(i, "P1");
        assertNotNull("released seats can be claimed again", ParticipantAuth.claim(i, "P1"));
    }

    @Test
    public void releaseInstanceForgetsOnlyThatInstance() {
        String a = ParticipantAuth.claim(i, "P1");
        String b = ParticipantAuth.claim("other", "P1");
        ParticipantAuth.releaseInstance(i);
        assertFalse(ParticipantAuth.verify(i, "P1", a));
        assertTrue(ParticipantAuth.verify("other", "P1", b));
    }

    // === /state ===

    @Test
    public void stateRefusesARequestThatOnlyKnowsTheUrl() {
        ParticipantAuth.claim(i, "P1");
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state"));
        assertEquals(UNAUTHORIZED, status(result));
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("not-logged-in", json.get("error").asText());
        assertEquals("/game/" + e + "/" + i + "/login", json.get("login").asText());
    }

    @Test
    public void stateAcceptsTheSeatTokenAsHeaderAndLeaksNoRequestDetails() {
        String token = ParticipantAuth.claim(i, "P1");
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state")
            .withHeader(ParticipantAuth.HEADER, token)
            .withHeader("Cookie", "something=private"));
        assertEquals(OK, status(result));
        JsonNode json = Json.parse(contentAsString(result));
        assertEquals("P1", json.get("clientId").asText());
        assertNull("cookie header must not be echoed", json.get("cookie"));
        assertNull("IP address must not be echoed", json.get("ipAddress"));
        assertNull("request URI must not be echoed", json.get("requestURI"));
        assertTrue("websocket URL carries the token when cookies weren't used",
            json.get("connectSocket").asText().contains(ParticipantAuth.QUERY_PARAM + "=" + token));
    }

    @Test
    public void stateAcceptsTheSeatCookie() {
        String token = ParticipantAuth.claim(i, "P1");
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state")
            .withCookies(new Http.Cookie(ParticipantAuth.cookieName(i), token, null, "/", null, false, true)));
        assertEquals(OK, status(result));
        JsonNode json = Json.parse(contentAsString(result));
        assertFalse("with the cookie the socket URL needs no token",
            json.get("connectSocket").asText().contains(ParticipantAuth.QUERY_PARAM + "="));
    }

    @Test
    public void anotherParticipantsTokenDoesNotOpenThisSeat() {
        ParticipantAuth.claim(i, "P1");
        String p2Token = ParticipantAuth.claim(i, "P2");
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state")
            .withHeader(ParticipantAuth.HEADER, p2Token));
        assertEquals(UNAUTHORIZED, status(result));
    }

    @Test
    public void aLoggedInResearcherCanOpenAnyParticipantPage() {
        Language lang = createLanguage("eng", "English");
        User user = createUser("researcher@test.com", "Researcher", "pass", lang);
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/state")
            .withSession("uid", user.uid));
        assertEquals(OK, status(result));
    }

    // === login form ===

    @Test
    public void loginClaimsTheSeatAndRedirectsWithTheToken() {
        Result result = route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("P1")));
        assertEquals(SEE_OTHER, status(result));
        String location = header(LOCATION, result);
        assertTrue(location, location.startsWith("/game/" + e + "/" + i + "/P1/connected#" + ParticipantAuth.FRAGMENT_KEY + "="));
        String token = location.substring(location.indexOf('=') + 1);
        assertTrue(ParticipantAuth.verify(i, "P1", token));
        Http.Cookie cookie = cookie(ParticipantAuth.cookieName(i), result);
        assertNotNull("seat cookie is set", cookie);
        assertEquals(token, cookie.value());
        assertTrue("cookie is HttpOnly", cookie.httpOnly());
        assertEquals(ParticipantAuth.cookiePath(e, i), cookie.path());
    }

    @Test
    public void loggingInWithSomeoneElsesIdIsRefused() {
        route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("P1")));
        Result second = route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("P1")));
        assertEquals(FORBIDDEN, status(second));
        assertTrue(contentAsString(second).contains("already in use"));
        assertNull("no new token is issued", header(LOCATION, second));
    }

    // === invitation links ===

    @Test
    public void aSignedInvitationClaimsTheSeatOnce() {
        String sig = ParticipantAuth.inviteSignature(i, "P1");
        assertNotNull(sig);
        String url = "/game/" + e + "/" + i + "/P1/connected?invite=" + sig;
        Result first = route(fakeRequest(GET, url));
        assertEquals(SEE_OTHER, status(first));
        assertTrue(header(LOCATION, first).contains("#" + ParticipantAuth.FRAGMENT_KEY + "="));
        Result reused = route(fakeRequest(GET, url));
        assertEquals("an invitation works only until the seat is taken", FORBIDDEN, status(reused));
    }

    @Test
    public void anInvitationForOneIdDoesNotWorkForAnother() {
        String sig = ParticipantAuth.inviteSignature(i, "P1");
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P2/connected?invite=" + sig));
        assertEquals(FORBIDDEN, status(result));
        assertFalse(ParticipantAuth.isClaimed(i, "P2"));
    }

    // === Prolific entry ===

    private String prolificUrl(String pid) {
        return "/game/" + e + "/" + i + "/prolific?PROLIFIC_PID=" + pid + "&STUDY_ID=study-1&SESSION_ID=session-1";
    }

    @Test
    public void prolificEntryClaimsAPseudonymousSeat() {
        Result result = route(fakeRequest(GET, prolificUrl("5f0c1a2b3c4d5e6f7a8b9c0d")));
        assertEquals(SEE_OTHER, status(result));
        String location = header(LOCATION, result);
        String clientId = ParticipantAuth.prolificClientId(i, "5f0c1a2b3c4d5e6f7a8b9c0d");
        assertTrue(location, location.startsWith("/game/" + e + "/" + i + "/" + clientId + "/connected#"));
        assertFalse("the Prolific id must not appear in the participant URL", location.contains("5f0c1a2b3c4d5e6f7a8b9c0d"));
        assertTrue(ParticipantAuth.isClaimed(i, clientId));
    }

    @Test
    public void theSameProlificIdFromAnotherBrowserIsRefused() {
        route(fakeRequest(GET, prolificUrl("PIDA")));
        Result again = route(fakeRequest(GET, prolificUrl("PIDA")));
        assertEquals(FORBIDDEN, status(again));
        assertTrue(contentAsString(again).contains("already joined"));
    }

    @Test
    public void theSameBrowserReturnsToItsSeat() {
        Result first = route(fakeRequest(GET, prolificUrl("PIDB")));
        Http.Cookie cookie = cookie(ParticipantAuth.cookieName(i), first);
        Result back = route(fakeRequest(GET, prolificUrl("PIDB")).withCookies(cookie));
        assertEquals(SEE_OTHER, status(back));
        String clientId = ParticipantAuth.prolificClientId(i, "PIDB");
        assertEquals("/game/" + e + "/" + i + "/" + clientId + "/connected", header(LOCATION, back));
    }

    @Test
    public void prolificEntryNeedsAProlificId() {
        Result result = route(fakeRequest(GET, "/game/" + e + "/" + i + "/prolific"));
        assertEquals(BAD_REQUEST, status(result));
    }

    @Test
    public void newcomersAreTurnedAwayOnceTheGameHasStarted() {
        // Through the setter: test code isn't Ebean-enhanced, so a plain field write isn't saved.
        instance.setHasStarted(Boolean.TRUE);
        instance.save();
        Result prolific = route(fakeRequest(GET, prolificUrl("PIDLATE")));
        assertEquals(OK, status(prolific));
        assertTrue(contentAsString(prolific).contains("already started"));
        assertFalse(ParticipantAuth.isClaimed(i, ParticipantAuth.prolificClientId(i, "PIDLATE")));

        Result login = route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("LATE1")));
        assertEquals(FORBIDDEN, status(login));
        assertTrue(contentAsString(login).contains("already started"));
    }

    @Test
    public void aSessionTheEngineDoesntRunTakesNoNewcomers() throws Exception {
        // e.g. one left running before a restart, or one the engine moved off
        engineSession.set(null, null);
        Result login = route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("P9")));
        assertEquals(FORBIDDEN, status(login));
        assertTrue(contentAsString(login), contentAsString(login).contains("running right now"));
        assertFalse(ParticipantAuth.isClaimed(i, "P9"));

        Result prolific = route(fakeRequest(GET, prolificUrl("PIDGONE")));
        assertEquals(OK, status(prolific));
        assertTrue(contentAsString(prolific).contains("not running"));
        assertFalse(ParticipantAuth.isClaimed(i, ParticipantAuth.prolificClientId(i, "PIDGONE")));

        // Nor does a session of another experiment that the URL claims as this one's.
        Experiment other = createExperiment("Other");
        Result wrong = route(fakeRequest(POST, "/game/" + other.id + "/" + i + "/login").withFormUrlEncodedBody(form("P8")));
        assertEquals(FORBIDDEN, status(wrong));
    }

    @Test
    public void aComputerPlayersIdCantBeUsedToSignIn() {
        Result login = route(fakeRequest(POST, "/game/" + e + "/" + i + "/login").withFormUrlEncodedBody(form("_1")));
        assertEquals(BAD_REQUEST, status(login));
        assertTrue(contentAsString(login), contentAsString(login).contains("reserved"));
        assertFalse(ParticipantAuth.isClaimed(i, "_1"));
    }

    @Test
    public void theTestSessionGivesParticipantsNoSeat() {
        // The engine runs a real session (this one): the test session's login would have put a
        // newcomer into its game, even after it started.
        Result login = route(fakeRequest(POST, "/game/" + e + "/0/login").withFormUrlEncodedBody(form("3")));
        assertEquals(FORBIDDEN, status(login));
        assertFalse(ParticipantAuth.isClaimed("0", "3"));
        assertNull(header(LOCATION, login));

        // A token for it from before (they lived in memory) opens neither its page data nor its socket.
        String token = ParticipantAuth.claim("0", "3");
        Result state = route(fakeRequest(GET, "/game/" + e + "/0/3/state").withHeader(ParticipantAuth.HEADER, token));
        assertEquals(UNAUTHORIZED, status(state));
        // A researcher's own preview may still use it.
        Language lang = createLanguage("eng", "English");
        User researcher = createUser("tester@test.com", "Tester", "pass", lang);
        assertEquals(OK, status(route(fakeRequest(GET, "/game/" + e + "/0/3/state").withSession("uid", researcher.uid))));
    }

    private static Map<String, String> form(String id) {
        Map<String, String> data = new HashMap<String, String>();
        data.put("id", id);
        data.put("password", "");
        return data;
    }
}
