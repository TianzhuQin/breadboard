import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.test.FakeRequest;
import security.ParticipantAuth;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * A participant's comment for the researcher (the Prolific finish screen's "Comments for the
 * researcher"): POST /game/{e}/{s}/{client}/comment, with the seat's token, records participantComment
 * (player, text) in their own session, whatever the engine runs now, so it is in the data that is
 * downloaded. It used to go to the engine as a message nothing listened to.
 */
public class ParticipantCommentTest extends BaseTest {

    @After
    public void forgetSeats() {
        ParticipantAuth.reset();
    }

    private static FakeRequest post(Experiment experiment, ExperimentInstance session, String clientId) {
        return fakeRequest(POST, "/game/" + experiment.id + "/" + session.id + "/" + clientId + "/comment");
    }

    private static ObjectNode text(String text) {
        ObjectNode body = Json.newObject();
        body.put("text", text);
        return body;
    }

    private static List<Event> comments(ExperimentInstance session) {
        return Event.find.where().eq("experimentInstance.id", session.id).eq("name", "participantComment").orderBy("id asc").findList();
    }

    private static String value(Event event, String name) {
        for (EventData d : event.eventData) {
            if (name.equals(d.name)) return d.value;
        }
        return null;
    }

    @Test
    public void aCommentGoesIntoTheParticipantsOwnSessionAndItsData() {
        Language lang = createLanguage("eng", "English");
        User researcher = createUser("comments@lab.org", "PI", "password123", lang);
        Experiment experiment = createExperiment("Comments");
        // Their game is over, and the next session is open by now.
        ExperimentInstance theirs = createInstance("theirs", experiment);
        theirs.status = ExperimentInstance.Status.FINISHED;
        theirs.save();
        ExperimentInstance next = createInstance("next", experiment);
        next.status = ExperimentInstance.Status.RUNNING;
        next.save();
        String token = ParticipantAuth.claim(theirs.id.toString(), "pc-1");

        Result saved = route(post(experiment, theirs, "pc-1").withHeader(ParticipantAuth.HEADER, token)
            .withJsonBody(text("  The second round was confusing.  ")));
        assertEquals(contentAsString(saved), NO_CONTENT, status(saved));
        List<Event> recorded = comments(theirs);
        assertEquals(1, recorded.size());
        assertEquals("pc-1", value(recorded.get(0), "player"));
        assertEquals("The second round was confusing.", value(recorded.get(0), "text"));
        assertEquals(0, comments(next).size());

        // With the seat's cookie, as the page's own request carries it.
        Result byCookie = route(post(experiment, theirs, "pc-1")
            .withCookies(new Http.Cookie(ParticipantAuth.cookieName(theirs.id.toString()), token, null, null, null, false, true))
            .withJsonBody(text("Thanks!")));
        assertEquals(NO_CONTENT, status(byCookie));
        assertEquals(2, comments(theirs).size());

        // In the data that is downloaded.
        String csv = contentAsString(route(fakeRequest(GET, "/csv/experiment/" + experiment.id + "/data").withSession("uid", researcher.uid)));
        assertTrue(csv, csv.contains("\"participantComment\""));
        assertTrue(csv, csv.contains("\"The second round was confusing.\""));
    }

    @Test
    public void onlyTheSeatsOwnTokenAndOnlyJson() {
        Experiment experiment = createExperiment("Refused");
        ExperimentInstance session = createInstance("s", experiment);
        String token = ParticipantAuth.claim(session.id.toString(), "pc-2");
        ParticipantAuth.claim(session.id.toString(), "pc-3");

        assertEquals("no token", FORBIDDEN, status(route(post(experiment, session, "pc-2").withJsonBody(text("hi")))));
        assertEquals("another seat's", FORBIDDEN, status(route(post(experiment, session, "pc-3")
            .withHeader(ParticipantAuth.HEADER, token).withJsonBody(text("hi")))));
        assertEquals("a wrong one", FORBIDDEN, status(route(post(experiment, session, "pc-2")
            .withHeader(ParticipantAuth.HEADER, "not-the-token").withJsonBody(text("hi")))));
        // A page of another site could post a form with the participant's cookie: forms are refused.
        assertEquals(BAD_REQUEST, status(route(post(experiment, session, "pc-2").withHeader(ParticipantAuth.HEADER, token)
            .withFormUrlEncodedBody(Collections.singletonMap("text", "hi")))));
        // Another experiment's session in the address.
        Experiment other = createExperiment("Other");
        assertEquals(NOT_FOUND, status(route(fakeRequest(POST, "/game/" + other.id + "/" + session.id + "/pc-2/comment")
            .withHeader(ParticipantAuth.HEADER, token).withJsonBody(text("hi")))));
        assertEquals(0, comments(session).size());

        // An empty comment records nothing; a very long one is kept cut short.
        assertEquals(NO_CONTENT, status(route(post(experiment, session, "pc-2").withHeader(ParticipantAuth.HEADER, token)
            .withJsonBody(text("   ")))));
        assertEquals(0, comments(session).size());
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 12000) longText.append("abcdefghij");
        assertEquals(NO_CONTENT, status(route(post(experiment, session, "pc-2").withHeader(ParticipantAuth.HEADER, token)
            .withJsonBody(text(longText.toString())))));
        assertEquals(10000, value(comments(session).get(0), "text").length());
        // (and never between the two halves of an emoji)
        StringBuilder withEmoji = new StringBuilder();
        while (withEmoji.length() < 9999) withEmoji.append('a');
        withEmoji.append("\uD83D\uDE00 and more");
        assertEquals(NO_CONTENT, status(route(post(experiment, session, "pc-2").withHeader(ParticipantAuth.HEADER, token)
            .withJsonBody(text(withEmoji.toString())))));
        assertEquals(9999, value(comments(session).get(1), "text").length());
    }
}
