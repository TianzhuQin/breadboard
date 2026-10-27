import com.fasterxml.jackson.databind.JsonNode;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.WebSocket;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * What one participant's browser receives about everyone else (models.ClientView, Client.updateGraph).
 */
public class ClientViewTest extends BaseTest {

    static class CapturingOut implements WebSocket.Out<JsonNode> {
        final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        public void write(JsonNode frame) { messages.add(frame); }
        public void close() {}
        JsonNode last() { return messages.get(messages.size() - 1); }
    }

    private CapturingOut out;
    private Client client;
    private EventGraph<TinkerGraph> graph;
    private Vertex p1, p2, p3, p4;

    /** p1 - p2, p1 - p3, p2 - p3 (a tie among p1's neighbors), p3 - p4 (p4 is beyond p1's view). */
    @Before
    public void setUp() {
        ClientView.reset();
        out = new CapturingOut();
        Experiment exp = createExperiment("ViewExp");
        ExperimentInstance instance = createInstance("ViewRun", exp);
        client = new Client("p1", instance, null, new ThrottledWebSocketOut(out, 0L));
        graph = new EventGraph<>(new TinkerGraph());
        p1 = graph.addVertex("p1");
        p2 = graph.addVertex("p2");
        p3 = graph.addVertex("p3");
        p4 = graph.addVertex("p4");
        graph.addEdge(null, p1, p2, "connected");
        graph.addEdge(null, p1, p3, "connected");
        graph.addEdge(null, p2, p3, "connected");
        graph.addEdge(null, p3, p4, "connected");
    }

    @After
    public void tearDown() {
        ClientView.reset();
    }

    private JsonNode sentGraph() {
        client.updateGraph(p1);
        return out.last().get("graph");
    }

    private static Set<String> nodeIds(JsonNode g) {
        Set<String> ids = new HashSet<>();
        for (JsonNode n : g.get("nodes")) ids.add(n.get("id").asText());
        return ids;
    }

    /** Edges as unordered "a|b" pairs of the ids the browser received. */
    private static Set<String> edgePairs(JsonNode g) {
        List<String> index = new ArrayList<>();
        for (JsonNode n : g.get("nodes")) index.add(n.get("id").asText());
        Set<String> pairs = new HashSet<>();
        for (JsonNode l : g.get("links")) {
            String a = index.get(l.get("source").asInt());
            String b = index.get(l.get("target").asInt());
            pairs.add(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a);
        }
        return pairs;
    }

    private static String pair(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    @Test
    public void neighborsArriveUnderAliasesNotClientIds() {
        JsonNode g = sentGraph();
        String a2 = ClientView.publicId("p2");
        String a3 = ClientView.publicId("p3");
        assertNotEquals("p2", a2);
        assertEquals(new HashSet<>(Arrays.asList("p1", a2, a3)), nodeIds(g));
        String wire = out.last().toString();
        assertFalse("p2's client id must not be on the wire", wire.contains("\"p2\""));
        assertFalse("p3's client id must not be on the wire", wire.contains("\"p3\""));
    }

    @Test
    public void aliasesAreStableAndReversible() {
        String a2 = ClientView.publicId("p2");
        assertEquals(a2, ClientView.publicId("p2"));
        assertEquals("p2", ClientView.realId(a2));
        assertEquals("own ids pass through", "p1", ClientView.realId("p1"));
    }

    @Test
    public void realIdsCanBeRestoredByTheExperiment() {
        ClientView.setNeighborIds("real");
        assertEquals(new HashSet<>(Arrays.asList("p1", "p2", "p3")), nodeIds(sentGraph()));
    }

    @Test
    public void engineInternalPropertiesStayHiddenFromNeighbors() {
        p2.setProperty("name", "Bob");
        p2.setProperty("ai", 1);
        p2.setProperty("lastupdated", 123L);
        p2.setProperty("timers", new HashMap<String, Object>());
        p2.setProperty("_system", new HashMap<String, Object>());
        JsonNode bob = find(sentGraph(), ClientView.publicId("p2"));
        assertTrue(bob.has("name"));
        for (String key : Arrays.asList("ai", "lastupdated", "timers", "_system")) {
            assertFalse(key + " must not reach neighbors", bob.has(key));
        }
    }

    @Test
    public void experimentsCanAdjustNeighborProperties() {
        p2.setProperty("ai", 1);
        p2.setProperty("score", 7);
        ClientView.showToNeighbors("ai");
        ClientView.hideFromNeighbors("score");
        JsonNode bob = find(sentGraph(), ClientView.publicId("p2"));
        assertTrue("opted-in property is sent", bob.has("ai"));
        assertFalse("opted-out property is withheld", bob.has("score"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void privateStateCanNeverBeShown() {
        ClientView.showToNeighbors("private");
    }

    @Test
    public void defaultViewHasOnlyTheParticipantsOwnTies() {
        Set<String> pairs = edgePairs(sentGraph());
        String a2 = ClientView.publicId("p2");
        String a3 = ClientView.publicId("p3");
        assertEquals(new HashSet<>(Arrays.asList(pair("p1", a2), pair("p1", a3))), pairs);
    }

    @Test
    public void egoTiesAddsTiesAmongNeighborsButNothingBeyond() {
        ClientView.setVisibility("egoTies");
        JsonNode g = sentGraph();
        String a2 = ClientView.publicId("p2");
        String a3 = ClientView.publicId("p3");
        assertEquals("p4 stays out of view", new HashSet<>(Arrays.asList("p1", a2, a3)), nodeIds(g));
        assertEquals(new HashSet<>(Arrays.asList(pair("p1", a2), pair("p1", a3), pair(a2, a3))), edgePairs(g));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownVisibilityModeIsRejected() {
        ClientView.setVisibility("everything");
    }

    private static JsonNode find(JsonNode g, String id) {
        for (JsonNode n : g.get("nodes")) {
            if (id.equals(n.get("id").asText())) return n;
        }
        fail("node " + id + " not found");
        return null;
    }
}
