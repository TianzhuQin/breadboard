import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * GET /experiment/:id/prolific: the Prolific participants of every session of an experiment, read
 * from the sessions' prolificEntry, prolificComplete and noCompletionCode events, for the console's
 * Recruitment page. The autopilot opens the next session about ten seconds after a game ends, and the
 * live list there only shows the session the engine runs, so who finished an earlier session, with
 * their codes and bonuses, had to be dug out of the downloaded data.
 */
public class ProlificParticipantsTest extends BaseTest {

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@example.org", prefix, "secret-1", lang);
    }

    private static LinkedHashMap<Object, Object> data(Object... pairs) {
        LinkedHashMap<Object, Object> m = new LinkedHashMap<Object, Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    /** What the server records as a Prolific participant joins the tracker's session (ClientLogin.prolificEntry). */
    private static void entry(EventTracker t, String player, String pid) {
        t.track("prolificEntry", data("clientId", player, "prolificPid", pid, "studyId", "study-1", "sessionId", "ps-" + pid));
    }

    private static Result participants(User user, Long experimentId) {
        return callAction(controllers.routes.ref.ProlificController.participants(experimentId),
            fakeRequest().withSession("uid", user.uid));
    }

    private static List<String> players(JsonNode list) {
        List<String> out = new ArrayList<String>();
        for (JsonNode p : list) out.add(p.get("player").asText());
        return out;
    }

    @Test
    public void listsWhoJoinedAndWhoFinishedInEverySessionNewestFirst() {
        User alice = user("alice");
        Experiment experiment = createExperiment("Network game");
        Experiment other = createExperiment("Another study");
        EventTracker tracker = new EventTracker();

        ExperimentInstance first = createInstance("Autopilot 1", experiment);
        tracker.setExperimentInstance(first);
        entry(tracker, "pp-a", "PID-A");
        entry(tracker, "pp-b", "PID-B");
        tracker.track("prolificComplete", data("player", "pp-a", "completionCode", "C7K2M9QX", "bonus", 1.5));
        // (an event of the game's own: not a Prolific one)
        tracker.track("ColourPick", data("player", "pp-b", "choice", "Blue"));
        first.finish();
        first.save();

        ExperimentInstance second = createInstance("Autopilot 2", experiment);
        // (through the entity's own methods: test code isn't Ebean-enhanced, so plain field writes aren't saved)
        second.start();
        second.save();
        tracker.setExperimentInstance(second);
        entry(tracker, "pp-c", "PID-C");
        entry(tracker, "pp-d", "PID-D");
        // the same seat again (its page came back after a reload): still one row, joined when it first did
        entry(tracker, "pp-c", "PID-C");
        tracker.track("prolificComplete", data("player", "pp-c", "completionCode", "C7K2M9QX"));
        // the network game example's own event when its prolificCode is empty
        tracker.track("noCompletionCode", data("player", "pp-d"));

        ExperimentInstance elsewhere = createInstance("Elsewhere 1", other);
        tracker.setExperimentInstance(elsewhere);
        entry(tracker, "pp-x", "PID-X");

        Result result = participants(alice, experiment.id);
        assertEquals(contentAsString(result), OK, status(result));
        JsonNode list = Json.parse(contentAsString(result)).get("participants");
        // the newest session first, then in the order they joined; not another experiment's
        assertEquals(java.util.Arrays.asList("pp-c", "pp-d", "pp-a", "pp-b"), players(list));

        JsonNode c = list.get(0);
        assertEquals(second.id.longValue(), c.get("sessionId").asLong());
        assertEquals("Autopilot 2", c.get("sessionName").asText());
        assertEquals("RUNNING", c.get("sessionStatus").asText());
        assertEquals("PID-C", c.get("prolificPid").asText());
        assertEquals("study-1", c.get("studyId").asText());
        assertEquals("ps-PID-C", c.get("prolificSessionId").asText());
        assertTrue(c.get("completed").asBoolean());
        assertEquals("C7K2M9QX", c.get("completionCode").asText());
        assertTrue(c.get("bonus").isNull());
        assertFalse(c.get("finishedWithoutCode").asBoolean());
        assertTrue(c.get("joinedAt").asText().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        assertTrue(c.get("completedAt").asText().endsWith("Z"));

        JsonNode d = list.get(1);
        assertFalse(d.get("completed").asBoolean());
        assertTrue(d.get("completionCode").isNull());
        assertTrue("finished, but no code was set", d.get("finishedWithoutCode").asBoolean());

        JsonNode a = list.get(2);
        assertEquals("FINISHED", a.get("sessionStatus").asText());
        assertEquals("Autopilot 1", a.get("sessionName").asText());
        assertTrue(a.get("completed").asBoolean());
        assertEquals(1.5, a.get("bonus").asDouble(), 0.0001);

        JsonNode b = list.get(3);
        assertEquals("PID-B", b.get("prolificPid").asText());
        assertFalse(b.get("completed").asBoolean());
        assertFalse(b.get("finishedWithoutCode").asBoolean());
        assertTrue(b.get("completedAt").isNull());
    }

    @Test
    public void anExperimentWithoutProlificParticipantsHasAnEmptyList() {
        User alice = user("alice");
        Experiment experiment = createExperiment("No Prolific");
        createInstance("Session 1", experiment);
        Result result = participants(alice, experiment.id);
        assertEquals(OK, status(result));
        assertEquals(0, Json.parse(contentAsString(result)).get("participants").size());
    }

    @Test
    public void onlyResearchersSeeThemAndAnUnknownExperimentIsNotFound() {
        User alice = user("alice");
        Experiment experiment = createExperiment("Network game");
        EventTracker tracker = new EventTracker();
        tracker.setExperimentInstance(createInstance("Autopilot 1", experiment));
        entry(tracker, "pp-a", "PID-A");

        Result signedOut = callAction(controllers.routes.ref.ProlificController.participants(experiment.id), fakeRequest());
        assertEquals(UNAUTHORIZED, status(signedOut));
        assertFalse(contentAsString(signedOut).contains("PID-A"));

        // every researcher works on every experiment: not only the one who made it
        Result anyone = participants(user("bob"), experiment.id);
        assertEquals(OK, status(anyone));
        assertEquals(1, Json.parse(contentAsString(anyone)).get("participants").size());

        assertEquals(NOT_FOUND, status(participants(alice, experiment.id + 1000)));
    }
}
