package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import groovy.lang.Closure;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.WebSocket;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;

/**
 * Two races around the engine's listeners: a participant's connection change is announced to the
 * scripts (bb:connection, which the autopilot counts) even when updating a page or console about it
 * fails; and a console socket that closed before its Admin was registered gets none, instead of a
 * dead Admin kept for good.
 */
public class ConnectionAndAdminRaceTest {

    private Field engineField;
    private Object previousEngine;

    @Before
    public void keepEngine() throws Exception {
        engineField = ScriptBoard.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        previousEngine = engineField.get(null);
    }

    @After
    public void restoreEngine() throws Exception {
        engineField.set(null, previousEngine);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aFailingListenerDoesNotSwallowTheConnectionAnnouncement() throws Exception {
        EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
        Vertex v = graph.addVertex("p1");
        v.setProperty("_system", new HashMap<String, Object>());
        graph.addListener(new LiveStatusTest.Changes() {
            @Override
            public void vertexPropertyChanged(Vertex vertex, String key, Object oldValue, Object setValue) {
                throw new IllegalStateException("a page update failed");
            }
        });
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("gremlin-groovy");
        engine.put("g", graph);
        engineField.set(null, engine);

        final List<Map<String, Object>> heard = new CopyOnWriteArrayList<>();
        Closure listener = new Closure(null) {
            public Object doCall(Object info) {
                heard.add((Map<String, Object>) info);
                return null;
            }
        };
        Field busField = ScriptBoard.class.getDeclaredField("eventBus");
        busField.setAccessible(true);
        EventBus bus = (EventBus) busField.get(null);
        bus.on("bb:connection", listener);
        try {
            ScriptBoard.setConnected("p1", false);
        } finally {
            bus.off("bb:connection", listener);
        }
        assertEquals(1, heard.size());
        assertEquals("p1", heard.get(0).get("clientId"));
        assertEquals(false, heard.get(0).get("connected"));
        assertEquals("the value is recorded all the same", false, ((Map<String, Object>) v.getProperty("_system")).get("connected"));
    }

    private static ThrottledWebSocketOut socket() {
        return new ThrottledWebSocketOut(new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) {}
            public void close() {}
        }, 0L);
    }

    @Test
    public void aSocketThatClosedFirstGetsNoAdmin() {
        int before = ScriptBoard.adminCount();
        ThrottledWebSocketOut closed = socket();
        closed.markClosed();
        ScriptBoard.removeAdmin(closed);           // what the socket's close did, before the AddAdmin
        ScriptBoard.registerAdmin(null, null, closed);
        assertEquals(before, ScriptBoard.adminCount());

        ThrottledWebSocketOut open = socket();
        ScriptBoard.registerAdmin(null, null, open);
        assertEquals(before + 1, ScriptBoard.adminCount());
        open.markClosed();
        ScriptBoard.removeAdmin(open);
        assertEquals(before, ScriptBoard.adminCount());
    }
}
