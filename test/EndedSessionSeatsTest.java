import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.WebSocket;
import security.ParticipantAuth;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/**
 * Participants' seat tokens used to be kept for good, a few per session. ScriptBoard remembers the
 * last screens of the last few sessions that ended (for pages that come back); a session's seats now
 * go when its screens do, unless it can still be resumed (a stopped session takes back those who
 * held a seat), and when it is deleted.
 */
public class EndedSessionSeatsTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @Before
    public void forgetOtherTestsScreens() throws Exception {
        // Their sessions' ids may come again: the tables are emptied after each test.
        endedSessionScreens().clear();
    }

    @After
    public void forgetSeats() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        ParticipantAuth.reset();
        // The engine's session is gone from the tables: no later test's events are recorded in it.
        Field f = ScriptBoard.class.getDeclaredField("eventTracker");
        f.setAccessible(true);
        ((EventTracker) f.get(null)).setExperimentInstance(null);
    }

    @Test
    public void aFinishedSessionsSeatsGoWhenItsLastScreensAreForgotten() throws Exception {
        Experiment experiment = createExperiment("Finished");
        ParticipantAuth.claim("9699", "p1"); // a session whose screens were never remembered
        List<String> ended = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            String session = session(experiment, ExperimentInstance.Status.FINISHED);
            ended.add(session);
            assertNotNull(ParticipantAuth.claim(session, "p1"));
            assertNotNull(ParticipantAuth.claim(session, "p2"));
            remember(session, "p1", "p2");
            if (i < 10) {
                assertTrue("kept while its screens are", ParticipantAuth.isClaimed(ended.get(0), "p1"));
            }
        }
        // Eleven sessions ended; the screens of the last ten are kept.
        assertFalse("its seats went with its screens", ParticipantAuth.isClaimed(ended.get(0), "p1"));
        assertFalse("its seats went with its screens", ParticipantAuth.isClaimed(ended.get(0), "p2"));
        for (String session : ended.subList(1, 11)) {
            assertTrue("session " + session, ParticipantAuth.isClaimed(session, "p1"));
            assertTrue("session " + session, ParticipantAuth.isClaimed(session, "p2"));
        }
        assertTrue(ParticipantAuth.isClaimed("9699", "p1"));
    }

    @Test
    public void aStoppedSessionKeepsItsSeatsForWhenItIsResumed() throws Exception {
        final User user = user("resumes");
        final AdminSocketsTest.Tab t = loggedIn(user);
        final Experiment experiment = createExperiment("Resumed");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        t.send("LaunchGame", "name", "S", "parameters", Json.newObject());
        await("running", () -> {
            ExperimentInstance launched = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
            return launched != null && launched.status == ExperimentInstance.Status.RUNNING;
        });
        final Long id = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique().id;
        final String session = String.valueOf(id);

        // A participant takes a seat and joins. The researcher stops the session: the participant's
        // page says so, which is the screen ScriptBoard remembers of them.
        String token = ParticipantAuth.claim(session, "p1");
        ScriptBoard.addClient(experiment.id.toString(), session, "p1", new WebSocket.In<JsonNode>(),
            new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
                public void write(JsonNode frame) {}
                public void close() {}
            }, 0L));
        t.send("StopGame", "id", id);
        await("the stopped screen remembered", () -> endedSessionScreens().containsKey(id));
        assertEquals(ExperimentInstance.Status.STOPPED, ExperimentInstance.findById(id).status);

        // Ten more sessions end: the stopped one's screens are forgotten, but it can be resumed.
        for (int i = 0; i < 10; i++) {
            remember(session(experiment, ExperimentInstance.Status.FINISHED), "p1");
        }
        assertFalse(endedSessionScreens().containsKey(id));
        assertTrue("the seat holder's token went", ParticipantAuth.verify(session, "p1", token));

        // Resumed, it takes back those who held a seat: theirs is still theirs alone.
        t.send("SelectInstance", "id", id);
        await("resumed", () -> ExperimentInstance.findById(id).status == ExperimentInstance.Status.RUNNING);
        assertTrue(ParticipantAuth.verify(session, "p1", token));
        assertNull("someone else typing their id took the seat", ParticipantAuth.claim(session, "p1"));
    }

    @Test
    public void aDeletedSessionsSeatsGoWithIt() throws Exception {
        String session = session(createExperiment("Deleted"), ExperimentInstance.Status.STOPPED);
        assertNotNull(ParticipantAuth.claim(session, "p1"));
        ExperimentInstance.findById(Long.valueOf(session)).delete();
        assertFalse(ParticipantAuth.isClaimed(session, "p1"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** A new session of the experiment, in this state: its id. */
    private static String session(Experiment experiment, ExperimentInstance.Status status) {
        ExperimentInstance instance = new ExperimentInstance("ended", experiment);
        instance.status = status; // (before it is first saved: Ebean doesn't see a field set after that)
        instance.save();
        return String.valueOf(instance.id);
    }

    /** ScriptBoard remembers the last screens of these participants of a session that has ended. */
    private static void remember(String instanceId, String... clientIds) throws Exception {
        ExperimentInstance instance = new ExperimentInstance();
        instance.id = Long.valueOf(instanceId);
        Method remember = ScriptBoard.class.getDeclaredMethod("rememberLastScreens", Collection.class);
        remember.setAccessible(true);
        for (String clientId : clientIds) {
            Client client = new Client(clientId, instance, null, null);
            client.lastFrame = Json.newObject();
            remember.invoke(null, Collections.singletonList(client));
        }
    }

    /** The sessions whose last screens ScriptBoard remembers, by id. */
    @SuppressWarnings("unchecked")
    private static Map<Long, ?> endedSessionScreens() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("endedSessionScreens");
        f.setAccessible(true);
        return (Map<Long, ?>) f.get(null);
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        return t;
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    private static Long engineExperimentId() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engineExperimentId");
        f.setAccessible(true);
        return (Long) f.get(null);
    }
}
