import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.Http;
import play.mvc.Result;
import security.ParticipantAuth;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Someone who comes back from Prolific after taking part (e.g. after a server restart, which forgets
 * seats and end screens) is given their completion code again, not a seat in the next session.
 * Someone whose session was cut short, and someone new, join the open session as before.
 */
public class ProlificReturningTest extends BaseTest {

    private Experiment experiment;
    private String e;
    private Field engineSession;
    private Object previousEngineSession;
    private Field trackerField;
    private Object previousTracker;
    private EventTracker tracker;

    @Before
    public void setUp() throws Exception {
        ParticipantAuth.reset();
        Recruitment.reset();
        experiment = createExperiment("Returning");
        e = experiment.id.toString();
        engineSession = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        previousEngineSession = engineSession.get(null);
        engineSession.set(null, null);
        // Entries are recorded where the engine's events go: openSession points them at its session.
        trackerField = ScriptBoard.class.getDeclaredField("eventTracker");
        trackerField.setAccessible(true);
        previousTracker = trackerField.get(null);
        tracker = new EventTracker();
        trackerField.set(null, tracker);
    }

    @After
    public void restore() throws Exception {
        ParticipantAuth.reset();
        Recruitment.reset();
        engineSession.set(null, previousEngineSession);
        trackerField.set(null, previousTracker);
    }

    /** A new session of the experiment, taking participants, as if the engine ran it. */
    private ExperimentInstance openSession(Experiment of, String name) throws Exception {
        ExperimentInstance session = createInstance(name, of);
        // (Through the entity's own methods: test code isn't Ebean-enhanced, so plain field writes aren't saved.)
        session.start();
        session.save();
        GameListener running = new GameListener();
        running.experimentInstance = session;
        engineSession.set(null, running);
        tracker.enable();
        tracker.setExperimentInstance(session);
        return session;
    }

    private ExperimentInstance openSession(String name) throws Exception {
        return openSession(experiment, name);
    }

    private static String query(String pid) {
        return "?PROLIFIC_PID=" + pid + "&STUDY_ID=study-1&SESSION_ID=session-" + pid;
    }

    /** Opens the study's link as Prolific sends people to it, and the session it leads to. */
    private Result enter(Experiment of, String pid) {
        Result link = route(fakeRequest(GET, "/play/" + of.id + "/prolific" + query(pid)));
        if (status(link) != SEE_OTHER) {
            return link;
        }
        return route(fakeRequest(GET, header(LOCATION, link)));
    }

    private Result enter(String pid) {
        return enter(experiment, pid);
    }

    private static String seat(ExperimentInstance session, String pid) {
        return ParticipantAuth.prolificClientId(session.id.toString(), pid);
    }

    /** The experiment's last step gives this player a code: recruitment.completeProlific, in an engine. */
    private void completeProlific(String player, String code) throws Exception {
        ScriptTestHarness harness = new ScriptTestHarness();
        try {
            // (where a session's engine records its events)
            harness.put("eventTracker", tracker);
            harness.eval("def v = g.addPlayer('" + player + "'); recruitment.registerProlific(v); "
                + "recruitment.completeProlific(v, [completionCode: '" + code + "']); null");
        } finally {
            harness.close();
        }
    }

    /** A participant's page connecting or leaving, as ScriptBoard.setConnected records it. */
    private void connection(String player, boolean connected) {
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("clientId", player);
        tracker.track(connected ? "clientConnected" : "clientDisconnected", data);
    }

    private static void finishGame(ExperimentInstance session) {
        session.setHasStarted(Boolean.TRUE);
        session.finish();
        session.save();
    }

    private static int entries(ExperimentInstance session) {
        return Event.find.where().eq("experimentInstance.id", session.id).eq("name", "prolificEntry").findRowCount();
    }

    // === they took part ===

    @Test
    public void someoneWhoCompletedIsGivenTheirCodeAgainInsteadOfASeat() throws Exception {
        ExperimentInstance first = openSession("first");
        assertEquals(SEE_OTHER, status(enter("PIDDONE")));
        connection(seat(first, "PIDDONE"), true);
        completeProlific(seat(first, "PIDDONE"), "C0DE1234");
        finishGame(first);
        // The server restarts: seats, players and end screens are forgotten. The next session opens.
        ParticipantAuth.reset();
        Recruitment.reset();
        ExperimentInstance next = openSession("next");

        Result back = enter("PIDDONE");
        assertEquals(OK, status(back));
        String page = contentAsString(back);
        assertTrue(page, page.contains("already taken part"));
        assertTrue(page.contains("<code>C0DE1234</code>"));
        assertTrue(page.contains("href=\"https://app.prolific.com/submissions/complete?cc=C0DE1234\""));
        assertFalse("the Prolific ID isn't on the page", page.contains("PIDDONE"));
        assertFalse("no seat in the next session", ParticipantAuth.isClaimed(next.id.toString(), seat(next, "PIDDONE")));
        assertEquals("nor an entry in its data", 0, entries(next));

        // The old session's own link, and the study link while no session is open, say the same
        // (rather than "return the study").
        Result old = route(fakeRequest(GET, "/game/" + e + "/" + first.id + "/prolific" + query("PIDDONE")));
        assertEquals(OK, status(old));
        assertTrue(contentAsString(old).contains("<code>C0DE1234</code>"));
        engineSession.set(null, null);
        Result closed = route(fakeRequest(GET, "/play/" + e + "/prolific" + query("PIDDONE")));
        assertEquals(OK, status(closed));
        assertTrue(contentAsString(closed).contains("<code>C0DE1234</code>"));
    }

    @Test
    public void someoneStillThereWhenTheirSessionFinishedTookPartEvenWithoutACode() throws Exception {
        // e.g. a session recorded before completions were, or an experiment that gives no code
        ExperimentInstance first = openSession("first");
        assertEquals(SEE_OTHER, status(enter("PIDEND")));
        connection(seat(first, "PIDEND"), true);
        connection(seat(first, "PIDEND"), false);
        connection(seat(first, "PIDEND"), true);
        finishGame(first);
        ParticipantAuth.reset();
        ExperimentInstance next = openSession("next");

        Result back = enter("PIDEND");
        assertEquals(OK, status(back));
        String page = contentAsString(back);
        assertTrue(page, page.contains("already taken part"));
        assertTrue(page.contains("message the researcher on Prolific"));
        assertFalse(page.contains("<code>"));
        assertFalse(page.contains("cc="));
        assertTrue(page.contains("href=\"https://app.prolific.com/\""));
        assertFalse(ParticipantAuth.isClaimed(next.id.toString(), seat(next, "PIDEND")));
    }

    @Test
    public void theBrowserHoldingTheSeatGoesBackToItAndAnotherBrowserIsGivenTheCode() throws Exception {
        // They completed while others still play: the session is still running.
        ExperimentInstance session = openSession("running");
        Result entered = enter("PIDHERE");
        Http.Cookie cookie = cookie(ParticipantAuth.cookieName(session.id.toString()), entered);
        completeProlific(seat(session, "PIDHERE"), "HERE5678");
        session.setHasStarted(Boolean.TRUE);

        String link = "/game/" + e + "/" + session.id + "/prolific" + query("PIDHERE");
        Result back = route(fakeRequest(GET, link).withCookies(cookie));
        assertEquals(SEE_OTHER, status(back));
        assertEquals("/game/" + e + "/" + session.id + "/" + seat(session, "PIDHERE") + "/connected", header(LOCATION, back));

        Result elsewhere = route(fakeRequest(GET, link));
        assertEquals(OK, status(elsewhere));
        assertTrue(contentAsString(elsewhere).contains("<code>HERE5678</code>"));
    }

    // === they didn't ===

    @Test
    public void someoneWhoseEarlierSessionWasCutShortJoinsTheOpenOne() throws Exception {
        // The researcher stopped their session while they were in it ...
        ExperimentInstance stopped = openSession("stopped");
        assertEquals(SEE_OTHER, status(enter("PIDCUT")));
        connection(seat(stopped, "PIDCUT"), true);
        stopped.setHasStarted(Boolean.TRUE);
        stopped.stop();
        stopped.save();
        // ... and the next one was cut short by a restart, still marked running.
        ExperimentInstance interrupted = openSession("interrupted");
        assertEquals(SEE_OTHER, status(enter("PIDCUT")));
        connection(seat(interrupted, "PIDCUT"), true);
        interrupted.setHasStarted(Boolean.TRUE);
        ParticipantAuth.reset();

        ExperimentInstance open = openSession("open");
        Result again = enter("PIDCUT");
        assertEquals(SEE_OTHER, status(again));
        assertTrue(header(LOCATION, again), header(LOCATION, again).startsWith(
            "/game/" + e + "/" + open.id + "/" + seat(open, "PIDCUT") + "/connected#"));
        assertTrue(ParticipantAuth.isClaimed(open.id.toString(), seat(open, "PIDCUT")));
        assertEquals(1, entries(open));
    }

    @Test
    public void someoneCutShortWhoseSessionWasResumedAndFinishedWithoutThemJoinsTheOpenOne() throws Exception {
        // In the session when the researcher stopped it (or the server restarted) ...
        ExperimentInstance resumed = openSession("resumed");
        assertEquals(SEE_OTHER, status(enter("PIDRES")));
        connection(seat(resumed, "PIDRES"), true);
        tracker.track("sessionStopped", new LinkedHashMap<Object, Object>());
        Thread.sleep(5);
        // ... which was resumed and finished, its game played again by others
        tracker.track("sessionResumed", new LinkedHashMap<Object, Object>());
        connection("someone-else", true);
        finishGame(resumed);
        ParticipantAuth.reset();

        ExperimentInstance open = openSession("open");
        assertEquals(SEE_OTHER, status(enter("PIDRES")));
        assertTrue(ParticipantAuth.isClaimed(open.id.toString(), seat(open, "PIDRES")));

        // someone who came back after the resume and was there at the end did take part
        ExperimentInstance again = openSession("again");
        assertEquals(SEE_OTHER, status(enter("PIDBACK")));
        tracker.track("sessionInterrupted", new LinkedHashMap<Object, Object>());
        Thread.sleep(5);
        tracker.track("sessionResumed", new LinkedHashMap<Object, Object>());
        Thread.sleep(5);
        connection(seat(again, "PIDBACK"), true);
        finishGame(again);
        ParticipantAuth.reset();
        openSession("later");
        Result took = enter("PIDBACK");
        assertEquals(OK, status(took));
        assertTrue(contentAsString(took).contains("already taken part"));
    }

    @Test
    public void aSessionThatFinishedWithoutThemDoesntCount() throws Exception {
        // They left before the end (the game went on without them) ...
        ExperimentInstance left = openSession("left");
        assertEquals(SEE_OTHER, status(enter("PIDLEFT")));
        connection(seat(left, "PIDLEFT"), true);
        connection(seat(left, "PIDLEFT"), false);
        finishGame(left);
        // ... and the autopilot gave up waiting for enough people in the next: its game never started.
        ExperimentInstance cancelled = openSession("cancelled");
        assertEquals(SEE_OTHER, status(enter("PIDLEFT")));
        connection(seat(cancelled, "PIDLEFT"), true);
        cancelled.finish();
        cancelled.save();

        ExperimentInstance open = openSession("open");
        assertEquals(SEE_OTHER, status(enter("PIDLEFT")));
        assertTrue(ParticipantAuth.isClaimed(open.id.toString(), seat(open, "PIDLEFT")));
    }

    @Test
    public void someoneNewJoinsAsBefore() throws Exception {
        // Someone else took part already.
        ExperimentInstance first = openSession("first");
        assertEquals(SEE_OTHER, status(enter("PIDOTHER")));
        connection(seat(first, "PIDOTHER"), true);
        completeProlific(seat(first, "PIDOTHER"), "OTHER123");
        finishGame(first);

        ExperimentInstance open = openSession("open");
        Result link = route(fakeRequest(GET, "/play/" + e + "/prolific" + query("PIDNEW")));
        assertEquals(SEE_OTHER, status(link));
        assertEquals("/game/" + e + "/" + open.id + "/prolific" + query("PIDNEW"), header(LOCATION, link));
        Result entered = route(fakeRequest(GET, header(LOCATION, link)));
        assertEquals(SEE_OTHER, status(entered));
        String location = header(LOCATION, entered);
        assertTrue(location, location.startsWith("/game/" + e + "/" + open.id + "/" + seat(open, "PIDNEW") + "/connected#"));
        assertFalse(location.contains("PIDNEW"));
        assertTrue(ParticipantAuth.isClaimed(open.id.toString(), seat(open, "PIDNEW")));
        assertEquals(1, entries(open));

        // Taking part in one experiment doesn't keep anyone out of another.
        Experiment other = createExperiment("Another study");
        ExperimentInstance elsewhere = openSession(other, "elsewhere");
        assertEquals(SEE_OTHER, status(enter(other, "PIDOTHER")));
        assertTrue(ParticipantAuth.isClaimed(elsewhere.id.toString(), seat(elsewhere, "PIDOTHER")));
    }
}
