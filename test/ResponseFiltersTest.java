import filters.AssetsCors;
import filters.AssetsCors$;
import filters.SessionCookieSameSite;
import filters.SessionCookieSameSite$;
import models.*;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import play.api.libs.iteratee.Done$;
import play.api.libs.iteratee.Input;
import play.api.libs.iteratee.Iteratee;
import play.api.mvc.EssentialAction;
import play.api.mvc.EssentialAction$;
import play.api.mvc.RequestHeader;
import play.api.mvc.ResponseHeader;
import play.api.mvc.SimpleResult;
import play.core.server.netty.NettyResultStreamer$;
import play.core.server.netty.PlayDefaultUpstreamHandler;
import play.mvc.Result;
import play.test.FakeRequest;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.runtime.AbstractFunction1;
import security.ParticipantAuth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The filters every response passes through (app/filters, registered in Global, which the suite's
 * application runs with): the researcher's session cookie goes out with SameSite=Lax, on sign-in,
 * sign-out and password change, and again when a console loads (for a sign-in from before), never at
 * the cost of the cookie itself, and no other cookie changes; files under /assets may be read from
 * any origin (a sandboxed preview loads them). Each result is handed to Play's own server code that
 * writes the response headers (NettyResultStreamer), which is where an attribute Play doesn't know
 * used to get lost.
 */
public class ResponseFiltersTest extends BaseTest {

    private static SimpleResult simple(Result result) throws Exception {
        return Await.result(result.getWrappedResult(), Duration.create(10, TimeUnit.SECONDS));
    }

    /** The Set-Cookie lines the server sends for this result. */
    private static List<String> sentCookies(SimpleResult result) {
        DefaultHttpResponse response = NettyResultStreamer$.MODULE$.createNettyResponse(result.header(), false, HttpVersion.HTTP_1_1);
        return new ArrayList<String>(response.getHeaders("Set-Cookie"));
    }

    private static SimpleResult sameSite(SimpleResult result) {
        return SessionCookieSameSite$.MODULE$.rewrite(result);
    }

    private static SimpleResult withHeader(SimpleResult result, String name, String value) {
        return result.copy(new ResponseHeader(result.header().status(), result.header().headers().updated(name, value)),
            result.body(), result.connection());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) n++;
        return n;
    }

    /** One line, the session cookie's, with SameSite=Lax once; no stray cookie made of the attribute. */
    private static String assertLaxSessionCookie(SimpleResult result) {
        List<String> lines = sentCookies(result);
        assertEquals(lines.toString(), 1, lines.size());
        String line = lines.get(0);
        assertTrue(line, line.startsWith("PLAY_SESSION="));
        assertEquals(line, 1, count(line, "SameSite"));
        assertTrue(line, line.endsWith("; SameSite=Lax"));
        return line;
    }

    private User user() {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser("cookies-" + UUID.randomUUID() + "@test.org", "Cookies", "secret-123", lang);
    }

    private static Map<String, String> form(String... pairs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int k = 0; k < pairs.length; k += 2) m.put(pairs[k], pairs[k + 1]);
        return m;
    }

    @Test
    public void theSessionCookieGoesOutWithSameSiteLaxOnSignInSignOutAndPasswordChange() throws Exception {
        User user = user();
        SimpleResult signIn = simple(route(fakeRequest(POST, "/login")
            .withFormUrlEncodedBody(form("email", user.email, "password", "secret-123"))));
        assertEquals(OK, signIn.header().status());
        String line = assertLaxSessionCookie(signIn);
        assertTrue("its other attributes are kept: " + line, line.contains("HTTPOnly") && line.contains("Path=/"));

        String uid = User.findByEmail(user.email).uid;
        SimpleResult changed = simple(route(fakeRequest(POST, "/saveUserSettings").withSession("uid", uid)
            .withFormUrlEncodedBody(form("currentPassword", "secret-123", "newPassword", "secret-456", "confirmPassword", "secret-456"))));
        assertLaxSessionCookie(changed);

        uid = User.findByEmail(user.email).uid;
        SimpleResult signOut = simple(route(fakeRequest(POST, "/logout").withSession("uid", uid)));
        String cleared = assertLaxSessionCookie(signOut);
        assertTrue("the cookie that clears the session: " + cleared, cleared.startsWith("PLAY_SESSION=;") && cleared.contains("Expires="));
    }

    @Test
    public void appendingTheAttributeToSetCookieIsNotEnoughAndTheFilterAddsItOnce() throws Exception {
        SimpleResult any = simple(route(fakeRequest(GET, "/assets/css/codemirror.css")));
        SimpleResult plain = withHeader(any, "Set-Cookie", "PLAY_SESSION=abc; Path=/; HTTPOnly");
        // Play alone: no SameSite.
        List<String> unfiltered = sentCookies(plain);
        assertEquals(1, unfiltered.size());
        assertFalse(unfiltered.get(0).contains("SameSite"));
        // Appended to Set-Cookie, the server reads it back as a cookie of its own and drops it from the
        // session cookie: why the filter sends the session cookie under another spelling of the name.
        List<String> appended = sentCookies(withHeader(any, "Set-Cookie", "PLAY_SESSION=abc; Path=/; HTTPOnly; SameSite=Lax"));
        assertEquals(appended.toString(), 2, appended.size());
        assertFalse(appended.get(0).contains("SameSite"));

        // Through the filter twice (registered twice, say): still once.
        SimpleResult once = sameSite(plain);
        SimpleResult twice = sameSite(once);
        assertEquals(once.header().headers(), twice.header().headers());
        assertTrue(assertLaxSessionCookie(twice).startsWith("PLAY_SESSION=abc;"));
    }

    /**
     * What Play's server does to a result after the filters, before it writes it: it clears a flash
     * cookie the request carries by adding a Set-Cookie header of its own (cleanFlashCookie).
     */
    private static SimpleResult flashCleaned(SimpleResult result, FakeRequest request) {
        return new PlayDefaultUpstreamHandler(null, null)
            .play$core$server$netty$PlayDefaultUpstreamHandler$$cleanFlashCookie$1(result, request.getWrappedRequest());
    }

    @Test
    public void aFlashCookieTheServerClearsDoesNotCostTheSessionCookie() throws Exception {
        // A PLAY_FLASH cookie from another Play application on the same host (cookies don't keep to a port).
        User user = user();
        FakeRequest signIn = fakeRequest(POST, "/login").withFlash("success", "Saved")
            .withFormUrlEncodedBody(form("email", user.email, "password", "secret-123"));
        SimpleResult result = simple(route(signIn));
        assertEquals(OK, result.header().status());
        List<String> lines = sentCookies(flashCleaned(result, signIn));
        assertEquals(lines.toString(), 2, lines.size());
        assertTrue("the sign-in is sent: " + lines, lines.get(0).startsWith("PLAY_SESSION=") || lines.get(1).startsWith("PLAY_SESSION="));
        assertTrue("and so is the flash cookie's clearing: " + lines, lines.get(0).startsWith("PLAY_FLASH=;") || lines.get(1).startsWith("PLAY_FLASH=;"));
    }

    @Test
    public void aSignInFromBeforeIsSentWithSameSiteLaxWhenAConsoleLoads() throws Exception {
        User user = user();
        for (String page : new String[]{"/", "/legacy-admin"}) {
            Result loaded = route(fakeRequest(GET, page).withSession("uid", user.uid));
            assertEquals(OK, status(loaded));
            assertLaxSessionCookie(simple(loaded));
            assertEquals(page, user.uid, sessionValue(loaded, "uid"));
        }
        // Nothing to send again: no sign-in, one that is no longer the account's, or another page.
        assertTrue(sentCookies(simple(route(fakeRequest(GET, "/")))).isEmpty());
        assertTrue(sentCookies(simple(route(fakeRequest(GET, "/").withSession("uid", "stale-" + UUID.randomUUID())))).isEmpty());
        assertTrue(sentCookies(simple(route(fakeRequest(GET, "/state").withSession("uid", user.uid)))).isEmpty());
    }

    @Test
    public void otherCookiesAreLeftAlone() throws Exception {
        // A participant's entry sets the seat cookie only, which keeps its attributes.
        Experiment experiment = createExperiment("Cookies");
        ExperimentInstance instance = createInstance("cookie-session", experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        String i = instance.id.toString();
        // As if the engine ran this session: an invitation seats no one in a session it doesn't run.
        java.lang.reflect.Field engineSession = models.ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        Object previousEngineSession = engineSession.get(null);
        models.GameListener running = new models.GameListener();
        running.experimentInstance = instance;
        engineSession.set(null, running);
        try {
            SimpleResult seat = simple(route(fakeRequest(GET, "/game/" + experiment.id + "/" + i + "/P1/connected?invite="
                + ParticipantAuth.inviteSignature(i, "P1"))));
            assertTrue(seat.header().headers().get("Set-Cookie").get().startsWith(ParticipantAuth.cookieName(i) + "="));
            assertTrue(seat.header().headers().get(SessionCookieSameSite$.MODULE$.RawSetCookie()).isEmpty());
            assertSame(seat, sameSite(seat));
            assertFalse(sentCookies(seat).toString().contains("SameSite"));
        } finally {
            ParticipantAuth.reset();
            engineSession.set(null, previousEngineSession);
        }
        SimpleResult page = simple(route(fakeRequest(GET, "/assets/css/codemirror.css")));
        assertSame(page, sameSite(page));
    }

    @Test
    public void filesUnderAssetsCanBeReadFromAnyOrigin() throws Exception {
        Result file = route(fakeRequest(GET, "/assets/css/codemirror.css"));
        assertEquals(OK, status(file));
        assertEquals("*", header("Access-Control-Allow-Origin", file));
        assertNull("console routes are not opened up", header("Access-Control-Allow-Origin", route(fakeRequest(GET, "/state"))));
        assertTrue(AssetsCors$.MODULE$.applies("/assets/bundles/client.js"));
        assertFalse(AssetsCors$.MODULE$.applies("/assetsx"));
        assertFalse(AssetsCors$.MODULE$.applies("/admin"));
    }

    // ---- the filter classes, as Global registers them -------------------------------------------------

    private static SimpleResult through(play.api.mvc.EssentialFilter filter, String path, final SimpleResult answer) throws Exception {
        EssentialAction next = EssentialAction$.MODULE$.apply(new AbstractFunction1<RequestHeader, Iteratee<byte[], SimpleResult>>() {
            @SuppressWarnings("unchecked")
            public Iteratee<byte[], SimpleResult> apply(RequestHeader request) {
                return Done$.MODULE$.apply(answer, (Input<byte[]>) (Object) Done$.MODULE$.apply$default$2());
            }
        });
        RequestHeader request = fakeRequest(GET, path).getWrappedRequest();
        return Await.result(filter.apply(next).apply(request).run(), Duration.create(10, TimeUnit.SECONDS));
    }

    @Test
    public void globalRegistersBothFilters() throws Exception {
        assertEquals(Arrays.<Class<?>>asList(SessionCookieSameSite.class, AssetsCors.class), Arrays.<Class<?>>asList(new Global().filters()));

        SimpleResult unauthorized = simple(route(fakeRequest(GET, "/state")));
        SimpleResult plain = withHeader(unauthorized, "Set-Cookie", "PLAY_SESSION=abc; Path=/; HTTPOnly");
        assertLaxSessionCookie(through(new SessionCookieSameSite(), "/login", plain));

        assertTrue(unauthorized.header().headers().get("Access-Control-Allow-Origin").isEmpty());
        assertEquals("*", through(new AssetsCors(), "/assets/css/codemirror.css", unauthorized).header().headers()
            .get("Access-Control-Allow-Origin").get());
        assertTrue(through(new AssetsCors(), "/csv/instances/1", unauthorized).header().headers()
            .get("Access-Control-Allow-Origin").isEmpty());
    }
}
