package models;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ScriptBoardSupport} -- the pure logic extracted from
 * ScriptBoard.initParam / makeUniqueClientId. No engine, no DB, no Play app.
 */
public class ScriptBoardSupportTest {

    @Test
    public void coercesIntegerParam() {
        assertEquals(Integer.valueOf(7), ScriptBoardSupport.coerceParam("Integer", "7"));
    }

    @Test
    public void badIntegerYieldsNull() {
        // null signals "leave unbound" -- the original logged and bound nothing.
        assertNull(ScriptBoardSupport.coerceParam("Integer", "notAnInt"));
    }

    @Test
    public void coercesDecimalParam() {
        assertEquals(Double.valueOf(0.5), ScriptBoardSupport.coerceParam("Decimal", "0.5"));
    }

    @Test
    public void badDecimalYieldsNull() {
        assertNull(ScriptBoardSupport.coerceParam("Decimal", "x.y"));
    }

    @Test
    public void coercesBooleanParam() {
        assertEquals(Boolean.TRUE, ScriptBoardSupport.coerceParam("Boolean", "true"));
        assertEquals(Boolean.TRUE, ScriptBoardSupport.coerceParam("Boolean", "TRUE"));
        // Boolean.parseBoolean: anything that isn't "true" (case-insensitive) is false.
        assertEquals(Boolean.FALSE, ScriptBoardSupport.coerceParam("Boolean", "nope"));
    }

    @Test
    public void textIsTheRawString() {
        assertEquals("hello world", ScriptBoardSupport.coerceParam("Text", "hello world"));
    }

    @Test
    public void nullTypeFallsBackToRawString() {
        // No Parameter defined for the key -> bound as the default string value.
        assertEquals("raw", ScriptBoardSupport.coerceParam(null, "raw"));
    }

    @Test
    public void unrecognisedTypeYieldsNull() {
        assertNull(ScriptBoardSupport.coerceParam("Mystery", "value"));
    }

    @Test
    public void makeUniqueClientIdComposesIds() {
        assertEquals("3-7-abc", ScriptBoardSupport.makeUniqueClientId(3L, 7L, "abc"));
    }

    @Test
    public void makeUniqueClientIdIsNullSafe() {
        // Mirrors the original's string concatenation, where a null id renders as "null".
        assertEquals("null-null-abc", ScriptBoardSupport.makeUniqueClientId(null, null, "abc"));
    }

    @Test
    public void describeErrorNeverReturnsBareNullForANullMessageException() {
        String desc = ScriptBoardSupport.describeError(new NullPointerException());
        assertNotNull(desc);
        assertFalse("must not collapse to the string \"null\"", "null".equals(desc));
        assertTrue("should name the exception type", desc.contains("NullPointerException"));
    }

    @Test
    public void describeErrorIncludesTheMessageWhenThereIsOne() {
        String desc = ScriptBoardSupport.describeError(new IllegalStateException("boom"));
        assertTrue(desc.contains("IllegalStateException"));
        assertTrue(desc.contains("boom"));
    }

    @Test
    public void describeErrorHandlesNullThrowable() {
        assertEquals("Unknown error (null)", ScriptBoardSupport.describeError(null));
    }

    @Test
    public void describeErrorAppendsGroovyStackFrames() {
        NullPointerException e = new NullPointerException();
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("Script7", "run", "graph.groovy", 42),
            new StackTraceElement("models.ScriptBoard", "processScript", "ScriptBoard.java", 772)
        });
        String desc = ScriptBoardSupport.describeError(e);
        assertTrue("should include the groovy frame", desc.contains("graph.groovy:42"));
        assertFalse("should not include non-groovy (java) frames",
            desc.contains("ScriptBoard.java"));
    }

    @Test
    public void describeErrorWalksCausesForGroovyFrames() {
        NullPointerException cause = new NullPointerException();
        cause.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("Script7", "doCall", "actions.groovy", 269)
        });
        RuntimeException wrapper = new RuntimeException("wrapped", cause);
        wrapper.setStackTrace(new StackTraceElement[0]);
        String desc = ScriptBoardSupport.describeError(wrapper);
        assertTrue("should surface the cause's groovy frame", desc.contains("actions.groovy:269"));
    }

    @Test
    public void briefErrorNamesTheLineOfTheStepAndWhatWentWrong() {
        IllegalStateException e = new IllegalStateException("No such property: roundz for class: Script34");
        e.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("org.codehaus.groovy.runtime.ScriptBytecodeAdapter", "unwrap", "ScriptBytecodeAdapter.java", 53),
            new StackTraceElement("Script34$_run_closure1", "doCall", "Script34.groovy", 8),
            new StackTraceElement("Step", "start", "step.groovy", 62)
        });
        assertEquals("line 8: No such property: roundz", ScriptBoardSupport.briefError(e));
    }

    @Test
    public void briefErrorTakesTheLineWhereItWentWrongNotWhereItWasWrapped() {
        // A step's error, wrapped as the engine passes it on by a script running inside another one
        // (the autopilot's start of the game from the bb:connection handler or a step being loaded):
        // the wrapper's own frames are the outer script's.
        IllegalStateException inner = new IllegalStateException("No such property: roundStepp");
        inner.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("Script45$_run_closure2", "doCall", "Script45.groovy", 4)
        });
        RuntimeException wrapper = new RuntimeException("javax.script.ScriptException: " + inner, inner);
        wrapper.setStackTrace(new StackTraceElement[]{
            new StackTraceElement("com.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine", "eval", "GremlinGroovyScriptEngine.java", 219),
            new StackTraceElement("Script50", "run", "Script50.groovy", 1)
        });
        assertEquals("line 4: No such property: roundStepp", ScriptBoardSupport.briefError(wrapper));
    }
}
