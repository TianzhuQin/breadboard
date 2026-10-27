import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import security.ParticipantAuth;

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

    @Before
    public void setUpInstance() {
        ParticipantAuth.reset();
        experiment = createExperiment("AuthTest");
        instance = createInstance("auth-instance", experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        e = experiment.id.toString();
        i = instance.id.toString();
    }

    @After
    public void clearSeats() {
        ParticipantAuth.reset();
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

    private static Map<String, String> form(String id) {
        Map<String, String> data = new HashMap<String, String>();
        data.put("id", id);
        data.put("password", "");
        return data;
    }
}
