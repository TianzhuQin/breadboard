import models.Client;
import models.ExperimentInstance;
import models.ScriptBoard;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;
import security.ParticipantAuth;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Participants' seat tokens used to be kept for good, a few per session. ScriptBoard remembers the
 * last screens of the last few sessions that ended (for pages that come back); a session's seats now
 * go when its screens do.
 */
public class EndedSessionSeatsTest extends BaseTest {

    @After
    public void forgetSeats() {
        ParticipantAuth.reset();
    }

    @Test
    public void aSessionsSeatsGoWhenItsLastScreensAreForgotten() throws Exception {
        long first = 9700L;
        ParticipantAuth.claim("9699", "p1"); // a session whose screens were never remembered
        for (long id = first; id <= first + 10; id++) {
            String session = String.valueOf(id);
            assertNotNull(ParticipantAuth.claim(session, "p1"));
            assertNotNull(ParticipantAuth.claim(session, "p2"));
            remember(id, "p1", "p2");
            if (id < first + 10) {
                assertTrue("kept while its screens are", ParticipantAuth.isClaimed(String.valueOf(first), "p1"));
            }
        }
        // Eleven sessions ended; the screens of the last ten are kept.
        assertFalse("its seats went with its screens", ParticipantAuth.isClaimed(String.valueOf(first), "p1"));
        assertFalse("its seats went with its screens", ParticipantAuth.isClaimed(String.valueOf(first), "p2"));
        for (long id = first + 1; id <= first + 10; id++) {
            assertTrue("session " + id, ParticipantAuth.isClaimed(String.valueOf(id), "p1"));
            assertTrue("session " + id, ParticipantAuth.isClaimed(String.valueOf(id), "p2"));
        }
        assertTrue(ParticipantAuth.isClaimed("9699", "p1"));
    }

    /** ScriptBoard remembers the last screens of these participants of a session that has ended. */
    private static void remember(long instanceId, String... clientIds) throws Exception {
        ExperimentInstance instance = new ExperimentInstance();
        instance.id = instanceId;
        Method remember = ScriptBoard.class.getDeclaredMethod("rememberLastScreens", Collection.class);
        remember.setAccessible(true);
        for (String clientId : clientIds) {
            Client client = new Client(clientId, instance, null, null);
            client.lastFrame = Json.newObject();
            remember.invoke(null, Collections.singletonList(client));
        }
    }
}
