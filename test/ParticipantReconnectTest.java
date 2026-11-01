import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.WebSocket;
import play.test.FakeRequest;
import security.ParticipantAuth;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * A participant page that was open while the server restarted reconnects with a seat token the
 * server has forgotten (seats live in memory). Its socket used to be closed without a word, which the
 * page took for a dropped connection and retried every few seconds, for ever. Now it is told why: the
 * session isn't running (the page shows what a reload would show), or the session runs and the page
 * has to sign in again. A page whose token is still good reconnects as it always did.
 */
public class ParticipantReconnectTest extends BaseTest {

    private Experiment experiment;
    private ExperimentInstance instance;
    private String e;
    private String i;
    private Field engineSession;
    private Object previousEngineSession;

    @Before
    public void setUp() throws Exception {
        ParticipantAuth.reset();
        experiment = createExperiment("ReconnectTest");
        instance = createInstance("reconnect-session", experiment);
        instance.status = ExperimentInstance.Status.RUNNING;
        instance.save();
        e = experiment.id.toString();
        i = instance.id.toString();
        // As in ParticipantAuthTest: the engine's session is what runsSession looks at.
        engineSession = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        previousEngineSession = engineSession.get(null);
        GameListener running = new GameListener();
        running.experimentInstance = instance;
        engineSession.set(null, running);
    }

    @After
    public void tearDown() throws Exception {
        ParticipantAuth.reset();
        engineSession.set(null, previousEngineSession);
    }

    /** A participant page's socket: the frames the server writes to it, and whether it closed it. */
    private static final class Page {
        final List<JsonNode> frames = new CopyOnWriteArrayList<JsonNode>();
        volatile boolean closed;
    }

    /** Open the participant websocket with this handshake, as the page reconnecting does. */
    private Page reconnect(String clientId, String token) {
        return reconnect(e, i, clientId, token);
    }

    private Page reconnect(String e, String i, String clientId, String token) {
        FakeRequest handshake = fakeRequest(GET, "/game/" + e + "/" + i + "/" + clientId + "/connect?"
            + ParticipantAuth.QUERY_PARAM + "=" + token);
        Http.Context.current.set(play.core.j.JavaHelpers$.MODULE$.createJavaContext(handshake.getWrappedRequest()));
        final Page page = new Page();
        try {
            WebSocket<JsonNode> socket = controllers.ClientController.connectClient(e, i, clientId);
            socket.onReady(new WebSocket.In<JsonNode>(), new WebSocket.Out<JsonNode>() {
                public void write(JsonNode frame) { page.frames.add(frame); }
                public void close() { page.closed = true; }
            });
        } finally {
            Http.Context.current.remove();
        }
        return page;
    }

    /** The server restarts: it forgets every seat, its engine runs nothing, and the session is marked stopped. */
    private void restart() throws Exception {
        ParticipantAuth.reset();
        engineSession.set(null, null);
        Global.markInterruptedSessions();
    }

    /** The words of a screen, as the page shows them (the reload's page escapes the apostrophes). */
    private static String words(JsonNode player) {
        String text = player.get("text").asText();
        return text.substring(text.indexOf("<p>") + 3, text.indexOf("</p>")).replace("'", "&#x27;");
    }

    /** Someone entered the session from Prolific (ClientLogin.prolificEntry records it so). */
    private void enteredFromProlific(String clientId) {
        Event entry = new Event();
        entry.name = "prolificEntry";
        entry.experimentInstance = instance;
        EventData who = new EventData();
        who.name = "clientId";
        who.value = clientId;
        entry.addEventData(who);
        entry.save();
    }

    @Test
    public void aPageLeftOpenAcrossARestartIsToldTheServerRestarted() throws Exception {
        String token = ParticipantAuth.claim(i, "P1");
        String prolificToken = ParticipantAuth.claim(i, "PP1");
        enteredFromProlific("PP1");
        restart();
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(instance.id).status);

        Page page = reconnect("P1", token);
        assertTrue("still closed", page.closed);
        assertEquals(page.frames.toString(), 1, page.frames.size());
        // (not a "player" frame: pages from before 3.1 would show it over a completion code)
        assertNull(page.frames.get(0).get("player"));
        JsonNode player = page.frames.get(0).path("sessionOver").get("player");
        assertNotNull(page.frames.toString(), player);
        // the last screen of a session that is over for this page: it shows it and stops reconnecting.
        // A crash (kill -9, a power cut) cut it short, which it says, as a normal stop told the pages.
        assertTrue(player.get("finished").asBoolean());
        assertEquals("serverStopped", player.get("ended").asText());
        assertTrue("the new participant page shows text once it has _system", player.has("_system"));
        String text = player.get("text").asText();
        assertTrue(text, text.contains("It ended because the study's server was stopped or restarted."));
        assertTrue(text, text.contains("If you were given a link to the study"));

        // The same words a reload of the page shows.
        Result reload = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P1/connected"));
        assertEquals(OK, status(reload));
        assertTrue(contentAsString(reload), contentAsString(reload).contains(words(player)));
        assertFalse("no seat taken", ParticipantAuth.isClaimed(i, "P1"));

        // A participant who came from Prolific reads what to do on Prolific, on the page and on a reload.
        JsonNode prolific = reconnect("PP1", prolificToken).frames.get(0).path("sessionOver").get("player");
        assertEquals("serverStopped", prolific.get("ended").asText());
        assertTrue(prolific.toString(), prolific.get("text").asText().contains("open the study again from Prolific"));
        Result prolificReload = route(fakeRequest(GET, "/game/" + e + "/" + i + "/PP1/connected"));
        assertTrue(contentAsString(prolificReload), contentAsString(prolificReload).contains(words(prolific)));
    }

    @Test
    public void aSessionResumedAfterTheRestartAndStoppedAgainIsJustNotRunning() throws Exception {
        restart();
        // The researcher resumed it, then stopped it: the restart is no longer why it isn't running.
        ExperimentInstance resumed = ExperimentInstance.findById(instance.id);
        Event again = new Event();
        again.name = "sessionResumed";
        again.experimentInstance = resumed;
        again.save();
        Page page = reconnect("P4", "a-token-from-before");
        JsonNode player = page.frames.get(0).path("sessionOver").get("player");
        assertEquals("notRunning", player.get("ended").asText());
        assertTrue(player.get("text").asText().contains("running right now"));
        assertTrue(contentAsString(route(fakeRequest(GET, "/game/" + e + "/" + i + "/P4/connected"))).contains("running right now"));
    }

    @Test
    public void aPageOfASessionTheEngineMovedOffIsToldItIsntRunning() throws Exception {
        // Marked running, but the engine runs nothing (or another session): no game to join.
        engineSession.set(null, null);
        Page page = reconnect("P2", "a-token-from-before");
        assertTrue(page.closed);
        assertEquals(page.frames.toString(), 1, page.frames.size());
        assertEquals("notRunning", page.frames.get(0).path("sessionOver").path("player").path("ended").asText());
        // where a reload ends up: the sign-in page, which says so
        Result signIn = route(fakeRequest(GET, "/game/" + e + "/" + i + "/login"));
        assertTrue(contentAsString(signIn).contains("running right now"));
    }

    @Test
    public void theNoticeHasTheWordsAReloadShowsForAHitOrASessionThatIsGone() throws Exception {
        restart();
        // a session that doesn't exist (any more)
        Page gone = reconnect(e, "987654", "P5", "a-token-from-before");
        String goneText = gone.frames.get(0).path("sessionOver").path("player").path("text").asText();
        assertTrue(goneText, goneText.contains(models.SystemTexts.text(null, "link.invalidTitle")));
        // a Mechanical Turk session: the worker is asked to return the HIT, as on a reload
        AMTHit hit = new AMTHit();
        hit.experimentInstance = instance;
        hit.creationDate = new java.util.Date();
        hit.save();
        Page amt = reconnect("P6", "a-token-from-before");
        String amtText = amt.frames.get(0).path("sessionOver").path("player").path("text").asText();
        assertTrue(amtText, amtText.contains(controllers.ClientLogin.AMT_NOT_RUNNING[1]));
        Result reload = route(fakeRequest(GET, "/game/" + e + "/" + i + "/P6/connected"));
        assertTrue(contentAsString(reload).contains(controllers.ClientLogin.AMT_NOT_RUNNING[1]));
    }

    @Test
    public void aPageWhoseSeatIsForgottenInASessionThatRunsIsAskedToSignInAgain() {
        // e.g. the researcher resumed the session after the restart
        String token = ParticipantAuth.claim(i, "P3");
        ParticipantAuth.reset();
        Page page = reconnect("P3", token);
        assertTrue(page.closed);
        assertEquals(page.frames.toString(), 1, page.frames.size());
        JsonNode frame = page.frames.get(0);
        assertTrue(frame.toString(), frame.get("reauth").asBoolean());
        assertNull("no screen of its own: the page asks where to sign in", frame.get("player"));
        assertFalse(ParticipantAuth.isClaimed(i, "P3"));
    }

    @Test
    public void aPageThatStillHoldsItsSeatReconnectsAsBefore() {
        // A short network drop: the server kept the seat. (The session here is over, so the page gets
        // its last screen back and joins no game.)
        instance.finish();
        instance.save();
        String clientId = "blip-" + UUID.randomUUID();
        String token = ParticipantAuth.claim(i, clientId);
        Page page = reconnect(clientId, token);
        assertFalse("taken", page.closed);
        assertFalse(page.frames.isEmpty());
        for (JsonNode frame : page.frames) {
            assertNull(frame.toString(), frame.get("reauth"));
            assertNull(frame.toString(), frame.get("sessionOver"));
            assertFalse(frame.toString(), frame.path("player").path("text").asText().contains("running right now"));
        }
        assertTrue("the seat is still theirs", ParticipantAuth.verify(i, clientId, token));
    }
}
