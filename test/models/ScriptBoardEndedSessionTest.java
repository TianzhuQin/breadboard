package models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import play.libs.Json;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A page that reconnects after its session has ended must not join whatever session the engine
 * runs now. It gets back the last screen it had (a completion code, a "could not start" notice),
 * or a plain "this session has ended".
 */
public class ScriptBoardEndedSessionTest {

    private static Client clientOf(long instanceId, String clientId, ObjectNode lastFrame) {
        ExperimentInstance instance = new ExperimentInstance();
        instance.id = instanceId;
        Client client = new Client(clientId, instance, null, null);
        client.lastFrame = lastFrame;
        return client;
    }

    @Test
    public void aReturningParticipantGetsTheirLastScreenBack() {
        ObjectNode frame = Json.newObject();
        frame.putObject("player").put("text", "Your completion code is ABC");
        ScriptBoard.rememberLastScreens(Arrays.asList(clientOf(9001L, "p1", frame), clientOf(9001L, "p2", null)));

        assertSame(frame, ScriptBoard.endedSessionScreen(9001L, "p1"));
    }

    @Test
    public void anyoneElseIsToldTheSessionHasEnded() {
        ObjectNode unknown = ScriptBoard.endedSessionScreen(9002L, "nobody");
        assertTrue(unknown.get("player").get("text").asText().contains("This session has ended"));
        // the new participant page shows text once it has _system
        assertTrue(unknown.get("player").has("_system"));

        ScriptBoard.rememberLastScreens(Arrays.asList(clientOf(9003L, "p2", null)));
        assertTrue(ScriptBoard.endedSessionScreen(9003L, "p2").get("player").get("text").asText().contains("ended"));
    }

    @Test
    public void onlyTheLastFewSessionsAreKept() {
        for (long id = 9100L; id < 9100L + 30; id++) {
            ObjectNode frame = Json.newObject();
            frame.putObject("player").put("text", "session " + id);
            ScriptBoard.rememberLastScreens(Arrays.asList(clientOf(id, "p", frame)));
        }
        assertEquals("session 9129", ScriptBoard.endedSessionScreen(9129L, "p").get("player").get("text").asText());
        assertTrue(ScriptBoard.endedSessionScreen(9100L, "p").get("player").get("text").asText().contains("ended"));
    }
}
