package models;

import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import com.tinkerpop.blueprints.util.wrappers.event.listener.GraphChangedListener;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * A participant's connection status and recruitment details live in {@code player._system}. They
 * used to be written into that map in place, which announced nothing, so the admin console only saw
 * them after a full replay. Now the property is set again after the write.
 */
public class LiveStatusTest {

    /** Records every vertex property change the graph announces. */
    static class Changes implements GraphChangedListener {
        final List<String> keys = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        public void vertexAdded(Vertex vertex) {}
        public void vertexPropertyChanged(Vertex vertex, String key, Object oldValue, Object setValue) {
            keys.add(key);
            values.add(setValue);
        }
        public void vertexPropertyRemoved(Vertex vertex, String key, Object removedValue) {}
        public void vertexRemoved(Vertex vertex, Map<String, Object> props) {}
        public void edgeAdded(Edge edge) {}
        public void edgePropertyChanged(Edge edge, String key, Object oldValue, Object setValue) {}
        public void edgePropertyRemoved(Edge edge, String key, Object removedValue) {}
        public void edgeRemoved(Edge edge, Map<String, Object> props) {}
    }

    private final EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
    private final Changes changes = new Changes();

    private Vertex player(String id) {
        Vertex v = graph.addVertex(id);
        v.setProperty("_system", new HashMap<String, Object>()); // as graph.groovy does on vertexAdded
        graph.addListener(changes);
        return v;
    }

    @After
    public void forgetPendingRecruitment() {
        Recruitment.reset();
    }

    @Test
    public void connectingIsAnnounced() {
        Vertex v = player("p1");
        Map<?, ?> system = (Map<?, ?>) v.getProperty("_system");

        ScriptBoard.setSystemValue(v, "connected", true);

        assertEquals(1, changes.keys.size());
        assertEquals("_system", changes.keys.get(0));
        assertEquals(true, ((Map<?, ?>) changes.values.get(0)).get("connected"));
        // The same map: a script holding it (or writing to it from a timer) is not left behind.
        assertSame(system, v.getProperty("_system"));

        ScriptBoard.setSystemValue(v, "connected", false);
        assertEquals(2, changes.keys.size());
        assertEquals(false, ((Map<?, ?>) v.getProperty("_system")).get("connected"));
    }

    @Test
    public void otherSystemValuesAreKept() {
        Vertex v = player("p1");
        ((Map<String, Object>) v.getProperty("_system")).put("groupId", "g7");
        ScriptBoard.setSystemValue(v, "connected", true);
        assertEquals("g7", ((Map<?, ?>) v.getProperty("_system")).get("groupId"));
    }

    @Test
    public void aVertexWithoutSystemMapIsLeftAlone() {
        Vertex v = graph.addVertex("p2");
        graph.addListener(changes);
        ScriptBoard.setSystemValue(v, "connected", true);
        assertNull(v.getProperty("_system"));
        assertTrue(changes.keys.isEmpty());
    }

    @Test
    public void recruitmentDetailsAreAnnounced() {
        Vertex v = player("p3");
        Recruitment.prolificEntry("p3", "pid-1", "study-1", "session-1");

        Recruitment.applyTo(v);

        assertEquals("[_system]", changes.keys.toString());
        Map<?, ?> recruitment = (Map<?, ?>) ((Map<?, ?>) v.getProperty("_system")).get("recruitment");
        assertEquals("prolific", recruitment.get("source"));
        assertEquals("pid-1", recruitment.get("prolificPid"));
    }

    @Test
    public void withNoRecruitmentDetailsNothingIsAnnounced() {
        Vertex v = player("p4");
        Recruitment.applyTo(v);
        assertTrue(changes.keys.isEmpty());
    }

    @Test
    public void neighboursAreNotSentAFrameForSystemChanges() {
        // _system is hidden from neighbours by default, so only the player's own page is updated...
        ClientView.reset();
        assertTrue(EventGraphChangedListener.onlyPlayerSees("_system"));
        assertTrue(EventGraphChangedListener.onlyPlayerSees("private"));
        assertTrue(EventGraphChangedListener.onlyPlayerSees("text"));
        assertFalse(EventGraphChangedListener.onlyPlayerSees("score"));
        // ...unless a script shows it to them.
        try {
            ClientView.showToNeighbors("_system");
            assertFalse(EventGraphChangedListener.onlyPlayerSees("_system"));
        } finally {
            ClientView.reset();
        }
    }
}
