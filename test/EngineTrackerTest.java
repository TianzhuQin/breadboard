import models.EventTracker;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * An engine's own event tracker (EventTracker.forEngine), which ScriptBoard binds into each engine it
 * builds: what the engine's scripts track is recorded as the server's tracker records it, until the
 * engine is replaced or its game ends (retire), and then nothing. retire() waits for a recording under
 * way, so nothing of the replaced engine's game lands after what the server records next
 * (engineReloaded, sessionStopped). No database: the server's tracker here only notes what it is given.
 */
public class EngineTrackerTest {

    /** The server's tracker: notes the names it is given, and can be held in the middle of one. */
    static final class Server extends EventTracker {
        final List<String> recorded = new CopyOnWriteArrayList<>();
        volatile CountDownLatch hold;
        final CountDownLatch holding = new CountDownLatch(1);

        @Override
        public void track(String name, List<Map<String, String>> nameValues) {
            CountDownLatch h = hold;
            if (h != null) {
                holding.countDown();
                try {
                    h.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            recorded.add(name);
        }
    }

    private static LinkedHashMap<Object, Object> data() {
        LinkedHashMap<Object, Object> data = new LinkedHashMap<>();
        data.put("round", 1);
        return data;
    }

    @Test
    public void anEnginesTrackerRecordsAsTheServersUntilRetiredThenNothing() {
        Server server = new Server();
        EventTracker replaced = server.forEngine();
        replaced.track("ColourPick", data());
        replaced.retire();
        replaced.track("RoundStepStart", data());
        // The next engine's own tracker records as before.
        EventTracker next = server.forEngine();
        next.track("InitStepStart", data());
        assertEquals(Arrays.asList("ColourPick", "InitStepStart"), server.recorded);
        // An engine's own tracker of an engine's own tracker records through the server's too.
        next.forEngine().track("RoundStepStart", data());
        assertEquals(Arrays.asList("ColourPick", "InitStepStart", "RoundStepStart"), server.recorded);
    }

    @Test
    public void retiringWaitsForARecordingUnderWay() throws Exception {
        final Server server = new Server();
        server.hold = new CountDownLatch(1);
        final EventTracker replaced = server.forEngine();
        Thread choice = new Thread(() -> replaced.track("ColourPick", data()), "a participant's choice");
        choice.start();
        assertTrue(server.holding.await(10, TimeUnit.SECONDS));

        // The rebuild begins while the choice is being recorded: it waits for that recording.
        Thread rebuild = new Thread(replaced::retire, "the rebuild");
        rebuild.start();
        rebuild.join(300);
        assertTrue("retire() waits for the recording under way", rebuild.isAlive());
        server.hold.countDown();
        rebuild.join(10000);
        choice.join(10000);
        assertFalse(rebuild.isAlive());
        // So the choice is recorded before what the rebuild records next, and nothing after it.
        assertEquals(Arrays.asList("ColourPick"), server.recorded);
        server.hold = null;
        replaced.track("RoundStepStart", data());
        assertEquals(Arrays.asList("ColourPick"), server.recorded);
        assertFalse(replaced.sessionLive());
    }
}
