import com.fasterxml.jackson.databind.JsonNode;
import models.ScriptBoard;
import models.ThrottledWebSocketOut;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.mvc.WebSocket;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * EditTie: a researcher adds or cuts a tie from the admin network view (ScriptBoard.editTie, through
 * edgeEdits.adminEdit in groovy/edge_edits.groovy). The console is always told the outcome as
 * {"tieEdit": {"op", "a", "b", "ok", "reason"?}}, accepted or refused.
 *
 * <p>Runs on the real Groovy scripts (ScriptTestHarness), put into ScriptBoard's static engine by
 * reflection and restored afterwards. No database or Play application is needed.
 */
public class EditTieTest {

    static class Recorder implements WebSocket.Out<JsonNode> {
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        public void write(JsonNode frame) { frames.add(frame); }
        public void close() {}
        JsonNode last() { return frames.get(frames.size() - 1).get("tieEdit"); }
    }

    private Field engineField;
    private Object previousEngine;
    private ScriptTestHarness harness;
    private Recorder recorder;
    private ThrottledWebSocketOut out;

    @Before
    public void useScriptEngine() throws Exception {
        engineField = ScriptBoard.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        previousEngine = engineField.get(null);
        harness = new ScriptTestHarness();
        engineField.set(null, harness.engine);
        harness.eval("g.addPlayer('x'); g.addPlayer('y'); g.addPlayer('z'); null");
        recorder = new Recorder();
        out = new ThrottledWebSocketOut(recorder, 0L);
    }

    @After
    public void restoreEngine() throws Exception {
        engineField.set(null, previousEngine);
    }

    private boolean tied(String a, String b) throws Exception {
        return Boolean.TRUE.equals(harness.eval("g.hasEdge(g.getVertex('" + a + "'), g.getVertex('" + b + "'))"));
    }

    @Test
    public void addingAndCuttingATie() throws Exception {
        ScriptBoard.editTie("add", "x", "y", out);
        JsonNode result = recorder.last();
        assertTrue(result.toString(), result.get("ok").asBoolean());
        assertEquals("add", result.get("op").asText());
        assertEquals("x", result.get("a").asText());
        assertEquals("y", result.get("b").asText());
        assertFalse(result.has("reason"));
        assertTrue(tied("x", "y"));

        ScriptBoard.editTie("remove", "y", "x", out);
        assertTrue(recorder.last().toString(), recorder.last().get("ok").asBoolean());
        assertFalse(tied("x", "y"));
    }

    @Test
    public void refusalsSayWhy() throws Exception {
        ScriptBoard.editTie("add", "x", "y", out);
        ScriptBoard.editTie("add", "x", "y", out);
        assertFalse(recorder.last().get("ok").asBoolean());
        assertEquals("They are already connected.", recorder.last().get("reason").asText());

        ScriptBoard.editTie("remove", "y", "z", out);
        assertEquals("They are not connected.", recorder.last().get("reason").asText());

        ScriptBoard.editTie("add", "x", "nobody", out);
        assertFalse(recorder.last().get("ok").asBoolean());
        assertTrue(recorder.last().get("reason").asText().contains("two different existing players"));

        ScriptBoard.editTie("add", "x", "x", out);
        assertFalse(recorder.last().get("ok").asBoolean());

        ScriptBoard.editTie("flip", "x", "y", out);
        assertEquals("Unknown edit \"flip\"", recorder.last().get("reason").asText());
        assertEquals("one reply per edit", 6, recorder.frames.size());
    }

    @Test
    public void withoutAnEngineTheEditIsRefused() throws Exception {
        engineField.set(null, null);
        ScriptBoard.editTie("add", "x", "y", out);
        assertFalse(recorder.last().get("ok").asBoolean());
        assertTrue(recorder.last().get("reason").asText().contains("isn't loaded"));
    }
}
