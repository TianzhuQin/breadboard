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

    // ---- the lines of an error named by step (locate) --------------------------------------------
    // As the network game's steps load, in name order: FinishStep, InitStep, … RoundStep. Script900 is
    // no step's: the script the server runs to start the game (initStep.start()).

    private static final java.util.Map<String, String> STEPS = new java.util.HashMap<String, String>() {{
        put("Script530", "FinishStep");
        put("Script531", "InitStep");
        put("Script534", "RoundStep");
    }};

    private static String stepOf(String file) {
        return STEPS.get(file.replace(".groovy", ""));
    }

    private static StackTraceElement at(String type, String method, String file, int line) {
        return new StackTraceElement(type, method, file, line);
    }

    private static IllegalStateException thrown(String message, StackTraceElement... frames) {
        IllegalStateException e = new IllegalStateException(message);
        e.setStackTrace(frames);
        return e;
    }

    private static ScriptBoardSupport.Located locate(Throwable t, String... ownSteps) {
        return ScriptBoardSupport.locate(t, ScriptBoardSupportTest::stepOf, java.util.Arrays.asList(ownSteps), null,
            (type, method) -> "Script531$_run_closure1".equals(type) ? "isHuman" : null);
    }

    private static java.util.List<String> named(ScriptBoardSupport.Located at) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        for (ScriptBoardSupport.Frame f : at.frames) lines.add(f.toString());
        return lines;
    }

    @Test
    public void aFailureInTheFailingStepsOwnCodeIsItsOwnLine() {
        // RoundStep's done, on its timer, at its line 71
        IllegalStateException e = thrown("No such property: x for class: Script534",
            at("Script534$_run_closure5", "doCall", "Script534.groovy", 71),
            at("Step", "endOnce", "step.groovy", 200),
            at("BBTimer$1", "run", "timer.groovy", 76));
        ScriptBoardSupport.Located where = locate(e, "RoundStep");
        assertEquals(java.util.Arrays.asList("RoundStep line 71"), named(where));
        assertEquals("RoundStep line 71", where.own.toString());
        assertNull(where.in);
        assertEquals("line 71: No such property: x", ScriptBoardSupport.briefError(e, where));
    }

    @Test
    public void aFailureInAnotherStepsHelperIsTheFailingStepsLineThatCalledItAndTheHelpersLine() {
        // RoundStep's run calls InitStep's isHuman at its line 14 (in a closure of its g.V.each at line 12),
        // which throws at InitStep's line 65; RoundStep's done (line 80) had started this round
        IllegalStateException e = thrown("isHumanStrict fails",
            at("Script531$_run_closure1", "doCall", "Script531.groovy", 65),
            at("Script534$_run_closure1_closure4", "doCall", "Script534.groovy", 14),
            at("Script534$_run_closure1", "doCall", "Script534.groovy", 12),
            at("Step", "start", "step.groovy", 64),
            at("Step", "start", "step.groovy", -1),
            at("Script534$_run_closure5", "doCall", "Script534.groovy", 80),
            at("Step", "endOnce", "step.groovy", 200));
        ScriptBoardSupport.Located where = locate(e, "RoundStep");
        assertEquals(java.util.Arrays.asList("InitStep line 65", "RoundStep line 14", "RoundStep line 12", "RoundStep line 80"),
            named(where));
        assertEquals("RoundStep line 14", where.own.toString());
        assertEquals("InitStep line 65", where.in.toString());
        assertEquals("isHuman", where.inName);
        assertEquals("line 14, in isHuman (InitStep line 65): isHumanStrict fails", ScriptBoardSupport.briefError(e, where));
        // the same through a closure inside isHuman: the function it called is still isHuman
        IllegalStateException nested = thrown("isHumanStrict fails",
            at("Script531$_run_closure1_closure7", "doCall", "Script531.groovy", 66),
            at("Script531$_run_closure1", "doCall", "Script531.groovy", 65),
            at("Script534$_run_closure1_closure4", "doCall", "Script534.groovy", 14));
        assertEquals("line 14, in isHuman (InitStep line 66): isHumanStrict fails",
            ScriptBoardSupport.briefError(nested, locate(nested, "RoundStep")));
        // a function that can't be named: the step and line only
        ScriptBoardSupport.Located unnamed = ScriptBoardSupport.locate(e, ScriptBoardSupportTest::stepOf,
            java.util.Collections.singleton("RoundStep"), null, null);
        assertEquals("line 14, in InitStep line 65: isHumanStrict fails", ScriptBoardSupport.briefError(e, unnamed));
        // (it used to be InitStep's line, named after RoundStep)
        assertEquals("line 65: isHumanStrict fails", ScriptBoardSupport.briefError(e));
    }

    @Test
    public void aStepAnotherStepStartedFailsAtItsOwnLine() {
        // FinishStep, which RoundStep's done started at its line 82, fails at its line 5
        IllegalStateException e = thrown("FinishStep fails",
            at("Script530$_run_closure1", "doCall", "Script530.groovy", 5),
            at("Step", "start", "step.groovy", 64),
            at("Step", "start", "step.groovy", -1),
            at("Script534$_run_closure5", "doCall", "Script534.groovy", 82),
            at("Step", "endOnce", "step.groovy", 200));
        ScriptBoardSupport.Located where = locate(e, "FinishStep");
        assertEquals(java.util.Arrays.asList("FinishStep line 5", "RoundStep line 82"), named(where));
        assertEquals("FinishStep line 5", where.own.toString());
        assertNull(where.in);
        assertEquals("line 5: FinishStep fails", ScriptBoardSupport.briefError(e, where));
        // and when FinishStep calls InitStep's isHuman at its line 6, which throws
        IllegalStateException helper = thrown("isHumanStrict fails",
            at("Script531$_run_closure1", "doCall", "Script531.groovy", 65),
            at("Script530$_run_closure1_closure3", "doCall", "Script530.groovy", 6),
            at("Script530$_run_closure1", "doCall", "Script530.groovy", 5),
            at("Step", "start", "step.groovy", 64),
            at("Script534$_run_closure5", "doCall", "Script534.groovy", 82));
        assertEquals("line 6, in isHuman (InitStep line 65): isHumanStrict fails",
            ScriptBoardSupport.briefError(helper, locate(helper, "FinishStep")));
    }

    @Test
    public void initStepStartedByTheWaitingRoomFailsAtItsOwnLine() {
        // the waiting room runs initStep.start() as a script of its own (Script900, no step's), wrapped as
        // the engine passes it on
        IllegalStateException inner = thrown("InitStep fails",
            at("Script531$_run_closure3", "doCall", "Script531.groovy", 47),
            at("Step", "start", "step.groovy", 64),
            at("Step", "start", "step.groovy", -1),
            at("Script900", "run", "Script900.groovy", 1),
            at("Autopilot", "start", "autopilot.groovy", 621));
        RuntimeException wrapper = new RuntimeException("javax.script.ScriptException: " + inner, inner);
        wrapper.setStackTrace(new StackTraceElement[]{at("Autopilot", "start", "autopilot.groovy", 621)});
        ScriptBoardSupport.Located where = locate(wrapper, "InitStep");
        assertEquals(java.util.Arrays.asList("InitStep line 47", "line 1"), named(where));
        assertEquals("InitStep line 47", where.own.toString());
        assertNull(where.in);
        assertEquals("line 47: InitStep fails", ScriptBoardSupport.briefError(wrapper, where));
    }

    @Test
    public void aStepThatFailsAsItLoadsFailsAtItsOwnLine() {
        // RoundStep's own code, run as it loads, at its line 2
        IllegalStateException e = thrown("No such property: five for class: Script534",
            at("Script534", "run", "Script534.groovy", 2));
        ScriptBoardSupport.Located where = ScriptBoardSupport.locate(e, ScriptBoardSupportTest::stepOf,
            java.util.Collections.singleton("RoundStep"), "RoundStep", null);
        assertEquals("RoundStep line 2", where.own.toString());
        assertEquals("line 2: No such property: five", ScriptBoardSupport.briefError(e, where));
        // calling InitStep's isHuman as it loads
        IllegalStateException helper = thrown("isHumanStrict fails",
            at("Script531$_run_closure1", "doCall", "Script531.groovy", 65),
            at("Script534", "run", "Script534.groovy", 3));
        assertEquals("line 3, in isHuman (InitStep line 65): isHumanStrict fails",
            ScriptBoardSupport.briefError(helper, locate(helper, "RoundStep")));
        // code that doesn't compile: the line the compiler names, in the step being compiled (which
        // compiled to no class: the compiler's name for it is no step's)
        RuntimeException syntax = new RuntimeException("javax.script.ScriptException: org.codehaus.groovy.control."
            + "MultipleCompilationErrorsException: startup failed:\nScript600.groovy: 3: unexpected token: } @ line 3, column 1.\n1 error\n");
        syntax.setStackTrace(new StackTraceElement[0]);
        ScriptBoardSupport.Located compiled = ScriptBoardSupport.locate(syntax, ScriptBoardSupportTest::stepOf,
            java.util.Collections.singleton("RoundStep"), "RoundStep", null);
        assertEquals("RoundStep line 3", compiled.own.toString());
        assertEquals("Script600.groovy", compiled.own.file);
        assertEquals("line 3: unexpected token: }", ScriptBoardSupport.briefError(syntax, compiled));
        // code RoundStep's run compiles as it runs, evaluate("…"), that doesn't compile: RoundStep's line that ran it
        RuntimeException evaluated = new RuntimeException("org.codehaus.groovy.control.MultipleCompilationErrorsException: "
            + "startup failed:\nScript902.groovy: 1: unexpected token: ) @ line 1, column 3.\n1 error\n");
        evaluated.setStackTrace(new StackTraceElement[]{
            at("org.codehaus.groovy.control.ErrorCollector", "failIfErrors", "ErrorCollector.java", 309),
            at("Script534$_run_closure1", "doCall", "Script534.groovy", 20)});
        ScriptBoardSupport.Located ran = locate(evaluated, "RoundStep");
        assertEquals("RoundStep line 20", ran.own.toString());
        assertEquals("line 20: unexpected token: )", ScriptBoardSupport.briefError(evaluated, ran));
        // and a script typed in the console that doesn't compile: the line the compiler names, as before
        RuntimeException typed = new RuntimeException("startup failed:\nScript903.groovy: 2: unexpected token: ) @ line 2, column 5.\n1 error\n");
        typed.setStackTrace(new StackTraceElement[0]);
        assertEquals("line 2: unexpected token: )", ScriptBoardSupport.briefError(typed, locate(typed)));
    }

    @Test
    public void withoutTheFailingStepsOwnLineTheInnermostLineIsNamedByItsStep() {
        IllegalStateException e = thrown("isHumanStrict fails",
            at("Script531$_run_closure1", "doCall", "Script531.groovy", 65),
            at("Script777", "run", "Script777.groovy", 1));
        // a script typed in the console (no step's) that called isHuman
        assertEquals("line 65 of InitStep: isHumanStrict fails", ScriptBoardSupport.briefError(e, locate(e)));
        // and one that is no step's at all, as before
        IllegalStateException typed = thrown("boom", at("Script777", "run", "Script777.groovy", 1));
        assertEquals("line 1: boom", ScriptBoardSupport.briefError(typed, locate(typed)));
    }
}
