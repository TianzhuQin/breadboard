package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import org.junit.Test;
import play.mvc.WebSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * OnJoinStep / OnLeaveStep run once per player added or removed, however many admin consoles are
 * open: none (the autopilot alone), one, or several tabs. They used to run once per registered
 * Admin, and Admins were never removed. No Play application or database is needed: the trigger and
 * the Admins are plain graph listeners.
 */
public class StepTriggerTest {

    static class Recorder implements WebSocket.Out<JsonNode> {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        public void write(JsonNode frame) { frames.add(frame); }
        public void close() {}

        int count(String action, String id) {
            int n = 0;
            for (JsonNode f : frames) {
                if (f.has("action") && action.equals(f.get("action").asText()) && id.equals(f.get("id").asText())) n++;
            }
            return n;
        }
    }

    private final List<String> joins = new ArrayList<>();
    private final List<String> leaves = new ArrayList<>();

    private EventGraph<TinkerGraph> graphWith(int consoles, List<Recorder> recorders) {
        EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
        EventGraphChangedListener listener = new EventGraphChangedListener(graph);
        listener.addAdminListener(new ScriptBoard.StepTrigger(new BiConsumer<Boolean, Vertex>() {
            public void accept(Boolean joined, Vertex v) {
                (joined ? joins : leaves).add(v.getId().toString());
            }
        }));
        for (int i = 0; i < consoles; i++) {
            Recorder r = new Recorder();
            recorders.add(r);
            // No ScriptBoard actor: an Admin that still tried to run the step itself would throw.
            listener.addAdminListener(new Admin(null, null, new ThrottledWebSocketOut(r, 0L)));
        }
        graph.addListener(listener);
        return graph;
    }

    @Test
    public void withSeveralConsolesTheStepsRunOncePerPlayer() {
        List<Recorder> consoles = new ArrayList<>();
        EventGraph<TinkerGraph> graph = graphWith(3, consoles);

        Vertex p1 = graph.addVertex("p1");
        graph.addVertex("p2");
        graph.removeVertex(p1);

        assertEquals("[p1, p2]", joins.toString());
        assertEquals("[p1]", leaves.toString());
        for (Recorder console : consoles) {
            assertEquals(1, console.count("addNode", "p1"));
            assertEquals(1, console.count("addNode", "p2"));
            assertEquals(1, console.count("removeNode", "p1"));
        }
    }

    @Test
    public void withNoConsoleTheStepsStillRun() {
        EventGraph<TinkerGraph> graph = graphWith(0, new ArrayList<Recorder>());
        Vertex p1 = graph.addVertex("p1");
        graph.removeVertex(p1);
        assertEquals("[p1]", joins.toString());
        assertEquals("[p1]", leaves.toString());
    }

    @Test
    public void aClosedConsoleIsNoLongerSentAnything() {
        EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
        EventGraphChangedListener listener = new EventGraphChangedListener(graph);
        graph.addListener(listener);
        Recorder open = new Recorder();
        Recorder closed = new Recorder();
        Admin openAdmin = new Admin(null, null, new ThrottledWebSocketOut(open, 0L));
        Admin closedAdmin = new Admin(null, null, new ThrottledWebSocketOut(closed, 0L));
        listener.addAdminListener(openAdmin);
        listener.addAdminListener(closedAdmin);
        listener.addAdminListener(openAdmin); // a second LogIn on the same socket adds nothing
        assertEquals(2, listener.getAdminListeners().size());

        listener.removeAdminListener(closedAdmin);
        graph.addVertex("p1");
        assertEquals(1, open.count("addNode", "p1"));
        assertEquals(0, closed.count("addNode", "p1"));
    }

    @Test
    public void aReplaySendsTheNodeAndEachProperty() {
        Recorder r = new Recorder();
        Admin admin = new Admin(null, null, new ThrottledWebSocketOut(r, 0L));
        Vertex v = new TinkerGraph().addVertex("p1");
        v.setProperty("score", 3);
        admin.replayVertex(v);
        assertEquals(1, r.count("addNode", "p1"));
        assertEquals(1, r.count("nodePropertyChanged", "p1"));
    }

    @Test
    public void aFailingConsoleDoesNotStopTheOthers() {
        EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
        EventGraphChangedListener listener = new EventGraphChangedListener(graph);
        graph.addListener(listener);
        WebSocket.Out<JsonNode> broken = new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { throw new IllegalStateException("socket closed"); }
            public void close() {}
        };
        Recorder fine = new Recorder();
        listener.addAdminListener(new Admin(null, null, new ThrottledWebSocketOut(broken, 0L)));
        listener.addAdminListener(new Admin(null, null, new ThrottledWebSocketOut(fine, 0L)));
        graph.addVertex("p1");
        assertEquals(1, fine.count("addNode", "p1"));
    }

    @Test
    public void playerIdsAreQuotedSoTheyCannotRunAsCode() {
        assertEquals("'p1'", ScriptBoard.groovyString("p1"));
        assertEquals("'${System.exit(0)}'", ScriptBoard.groovyString("${System.exit(0)}"));
        assertEquals("'it\\'s'", ScriptBoard.groovyString("it's"));
        assertEquals("'a\\\\b'", ScriptBoard.groovyString("a\\b"));
        assertTrue(ScriptBoard.groovyString("x\ny").contains("\\n"));
    }
}
