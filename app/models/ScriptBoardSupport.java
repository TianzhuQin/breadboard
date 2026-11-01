package models;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, stateless helpers extracted from {@link ScriptBoard} so the logic can be
 * unit-tested without the actor's static state, the script engine, or a database.
 *
 * <p>Keeping these here (rather than duplicating the logic in a test) means the
 * production code path and the tested code path are the same — see
 * {@code ScriptBoard.initParam} and {@code ScriptBoard.makeUniqueClientId}, which
 * delegate here.
 */
public final class ScriptBoardSupport {

    private ScriptBoardSupport() {}

    /** A never-null, human-readable description of a Throwable for the admin/client console. */
    public static String describeError(Throwable t) {
        if (t == null) {
            return "Unknown error (null)";
        }
        ScriptLoader.humanizeStackTrace(t);
        StringBuilder sb = new StringBuilder(headline(t));
        appendGroovyFrames(sb, t);
        return sb.toString();
    }

    /**
     * What went wrong in a step, in one line a researcher can act on, e.g. "line 13: No such
     * property: five" (the innermost cause's message, without Groovy's generated class names, and the
     * line of the step it happened on, when known). Without knowing which step's code each line is
     * in: see {@link #briefError(Throwable, Located)}.
     */
    public static String briefError(Throwable t) {
        return briefError(t, null);
    }

    /**
     * As above, saying where by {@code at}, the lines of the error named by step ({@link #locate}):
     * <ul>
     *   <li>"line 71: …", the failing step's own line (the step is named alongside: the event's
     *       {@code step}, "the step RoundStep failed …", "RoundStep: …" for a step that didn't load);</li>
     *   <li>"line 14, in isHuman (InitStep line 30): …" when it went wrong in another step's code that
     *       the failing step called there, a function it is known by, else "line 14, in InitStep line
     *       30: …";</li>
     *   <li>"line 30 of InitStep: …" when the failing step's own line isn't known, for the innermost
     *       line, when it is a step's;</li>
     *   <li>"line 3: …" for a script that is no step's, as before.</li>
     * </ul>
     * Without {@code at} (null), the innermost line of a script the engine compiled, unnamed.
     */
    public static String briefError(Throwable t, Located at) {
        if (t == null) {
            return "unknown error";
        }
        Throwable inner = t;
        Set<Throwable> seen = new HashSet<Throwable>();
        while (inner.getCause() != null && seen.add(inner) && inner.getCause().getMessage() != null) {
            inner = inner.getCause();
        }
        String message = inner.getMessage() == null ? inner.getClass().getSimpleName() : inner.getMessage();
        // "startup failed:\nScript1205.groovy: 1: unexpected token: @ line 1, column 21.\n1 error"
        java.util.regex.Matcher compile = COMPILE_ERROR.matcher(message);
        if (compile.find()) {
            // (the line the compiler names, unless code the step compiled as it ran, evaluate("…"), didn't
            // compile: then the step's own line that ran it)
            String where = at != null && !at.frames.isEmpty() ? at.where() : "line " + compile.group(2);
            return where + ": " + compile.group(3).trim();
        }
        message = message.replaceAll("\\s+for class: Script\\d+", "").replaceAll("\\s+", " ").trim();
        if (message.startsWith("javax.script.ScriptException: ")) {
            message = message.substring("javax.script.ScriptException: ".length());
        }
        message = message.replaceFirst("^[a-z][\\w.]*\\.(\\w+(Exception|Error)): ", "");
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        String where = at != null ? at.where() : innermostLine(t);
        return where == null ? message : where + ": " + message;
    }

    // "Script1205.groovy: 1: unexpected token: @ line 1, column 21." in a compiler's message
    private static final java.util.regex.Pattern COMPILE_ERROR = java.util.regex.Pattern
        .compile("(Script\\d+\\.groovy): (\\d+): ([^\\n]*?)(?: @ line \\d+, column (\\d+)\\.)?\\s*(?:\\n|$)");

    /** "line 8": the line of a script the engine compiled that is innermost in {@code t}; null when none. */
    private static String innermostLine(Throwable t) {
        List<StackTraceElement> frames = scriptFrames(t);
        return frames.isEmpty() ? null : "line " + frames.get(0).getLineNumber();
    }

    /**
     * The lines of scripts the engine compiled ("Script&lt;N&gt;.groovy": a step's code, a script typed
     * in the console or run by the server; Breadboard's own are named after their files by then,
     * ScriptLoader.humanizeStackTrace) that {@code t} went through, innermost first: of its innermost
     * cause that has any. An outer exception's lines are where it was wrapped, which can be in another
     * script (one that ran this one, e.g. a step that started the game); the innermost cause's are the
     * whole way there.
     */
    private static List<StackTraceElement> scriptFrames(Throwable t) {
        List<Throwable> chain = new ArrayList<Throwable>();
        for (Throwable cur = t; cur != null && !chain.contains(cur); cur = cur.getCause()) {
            chain.add(cur);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            List<StackTraceElement> frames = new ArrayList<StackTraceElement>();
            StackTraceElement[] stack = chain.get(i).getStackTrace();
            for (StackTraceElement f : stack == null ? new StackTraceElement[0] : stack) {
                if (f.getFileName() != null && f.getFileName().matches("Script\\d+\\.groovy") && f.getLineNumber() > 0) {
                    frames.add(f);
                }
            }
            if (!frames.isEmpty()) {
                return frames;
            }
        }
        return new ArrayList<StackTraceElement>();
    }

    /** A line of an engine error in a script the engine compiled, and the step whose code it is. */
    public static final class Frame {
        /** As the error's text names it: "Script649.groovy". */
        public final String file;
        public final int line;
        /** The step whose code it is ("RoundStep"); null when it is no step's (a script typed in the console). */
        public final String step;

        Frame(String file, int line, String step) {
            this.file = file;
            this.line = line;
            this.step = step;
        }

        /** "InitStep line 30", or "line 3" when it is no step's. */
        public String toString() {
            return step == null ? "line " + line : step + " line " + line;
        }
    }

    /** Where in the experiment's code an engine error happened: its lines, named by step ({@link #locate}). */
    public static final class Located {
        /** Every line of a script the engine compiled that the error went through, innermost first, once each. */
        public final List<Frame> frames;
        /** The failing step's own line: the innermost one in its code. Null when that isn't known. */
        public final Frame own;
        /**
         * Where it went wrong, when that is in another step's code than the failing step's own line
         * (the innermost line; a function of another step that it called): else null.
         */
        public final Frame in;
        /** The function of that step the failing step called, e.g. "isHuman", when known; else null. */
        public final String inName;

        Located(List<Frame> frames, Frame own, Frame in, String inName) {
            this.frames = java.util.Collections.unmodifiableList(frames);
            this.own = own;
            this.in = in;
            this.inName = inName;
        }

        /**
         * Where it happened, as briefError starts with it (see there): "line 71", "line 14, in isHuman
         * (InitStep line 30)", "line 30 of InitStep", "line 3"; null when no line is known.
         */
        public String where() {
            if (own != null) {
                if (in == null) {
                    return "line " + own.line;
                }
                return "line " + own.line + ", in " + (inName == null ? in.toString() : inName + " (" + in + ")");
            }
            if (frames.isEmpty()) {
                return null;
            }
            Frame first = frames.get(0);
            return first.step == null ? "line " + first.line : "line " + first.line + " of " + first.step;
        }
    }

    /**
     * The lines of {@code t} in scripts the engine compiled (once Breadboard's own are named after their
     * files, ScriptLoader.humanizeStackTrace), innermost first, each named by the step
     * whose code it is ({@code stepOf}: a script's file, "Script649.groovy", to its step, or null), and
     * the failing step's own line: the innermost line in the code of {@code ownSteps} (the steps whose
     * code the failing step is, StepScripts.stepsOfCode; for code that failed as it loaded, that step).
     * When that isn't the innermost line, the error went wrong in another step's code that it called:
     * {@code in}, with the name of the function there it called ({@code functionName}, of a line's class
     * and method), when that is another step's code. A compiler's error has no lines but the one it
     * names, which is {@code compiling}'s, the step whose code was being compiled (or null).
     */
    public static Located locate(Throwable t, java.util.function.Function<String, String> stepOf, java.util.Collection<String> ownSteps,
                                 String compiling, java.util.function.BiFunction<String, String, String> functionName) {
        List<Frame> frames = new ArrayList<Frame>();
        if (t == null) {
            return new Located(frames, null, null, null);
        }
        // a compiler's error in the step's code being compiled: the line it names (code a step's code
        // compiles as it runs, evaluate("…"), has the lines that ran it)
        Throwable innermost = t;
        Set<Throwable> seen = new HashSet<Throwable>();
        while (innermost.getCause() != null && seen.add(innermost)) {
            innermost = innermost.getCause();
        }
        for (Throwable cur = compiling == null ? null : t; cur != null; cur = cur == innermost ? null : cur.getCause()) {
            java.util.regex.Matcher compile = cur.getMessage() == null ? null : COMPILE_ERROR.matcher(cur.getMessage());
            if (compile != null && compile.find()) {
                Frame at = new Frame(compile.group(1), Integer.parseInt(compile.group(2)), compiling);
                frames.add(at);
                return new Located(frames, at, null, null);
            }
        }
        List<StackTraceElement> lines = scriptFrames(t);
        List<String> classes = new ArrayList<String>();
        List<String> methods = new ArrayList<String>();
        Set<String> once = new HashSet<String>();
        for (StackTraceElement f : lines) {
            if (once.add(f.getFileName() + ":" + f.getLineNumber())) {
                String step = stepOf == null ? null : stepOf.apply(f.getFileName());
                frames.add(new Frame(f.getFileName(), f.getLineNumber(), step));
                classes.add(f.getClassName());
                methods.add(f.getMethodName());
            }
        }
        Frame own = null;
        int ownAt = -1;
        if (ownSteps != null && !ownSteps.isEmpty()) {
            for (int i = 0; i < frames.size() && own == null; i++) {
                if (frames.get(i).step != null && ownSteps.contains(frames.get(i).step)) {
                    own = frames.get(i);
                    ownAt = i;
                }
            }
        }
        if (own == null || ownAt == 0 || frames.get(0).step == null) {
            // (no other step's code it went wrong in: its own, or a script that is no step's)
            return new Located(frames, own, null, null);
        }
        Frame in = frames.get(0);
        // The function the failing step called: the outermost of the lines in that script before its own,
        // where the failing step's code called it (isHuman, when it went wrong in a closure inside it).
        int entry = 0;
        while (entry + 1 < ownAt && frames.get(entry + 1).file.equals(in.file)) {
            entry++;
        }
        String name = null;
        if (functionName != null) {
            try {
                name = functionName.apply(classes.get(entry), methods.get(entry));
            } catch (RuntimeException e) {
                name = null;
            }
        }
        return new Located(frames, own, in, name);
    }

    private static String headline(Throwable t) {
        String type = t.getClass().getSimpleName();
        if (type == null || type.isEmpty()) {
            type = t.getClass().getName();
        }
        String msg = ScriptLoader.humanize(t.getMessage());
        return (msg == null || msg.isEmpty()) ? type : type + ": " + msg;
    }

    private static void appendGroovyFrames(StringBuilder sb, Throwable t) {
        Set<Throwable> seenCauses = new HashSet<Throwable>();
        Set<String> frames = new LinkedHashSet<String>();
        for (Throwable cur = t; cur != null && seenCauses.add(cur); cur = cur.getCause()) {
            StackTraceElement[] elements = cur.getStackTrace();
            if (elements == null) continue;
            for (StackTraceElement f : elements) {
                String file = f.getFileName();
                if (file != null && file.endsWith(".groovy")) {
                    frames.add(file + ":" + f.getLineNumber());
                }
            }
        }
        for (String frame : frames) {
            sb.append("\n  at ").append(frame);
        }
    }

    /**
     * Coerce a parameter's string value to the type declared by its {@code Parameter}.
     * Mirrors the original {@code ScriptBoard.initParam} branching:
     * <ul>
     *   <li>{@code null} type (no Parameter defined) &rarr; the raw string (default binding)</li>
     *   <li>{@code "Integer"} &rarr; {@link Integer}</li>
     *   <li>{@code "Decimal"} &rarr; {@link Double}</li>
     *   <li>{@code "Boolean"} &rarr; {@link Boolean} (via {@link Boolean#parseBoolean})</li>
     *   <li>{@code "Text"} &rarr; the raw string</li>
     * </ul>
     *
     * @return the coerced value, or {@code null} to signal "do not bind" — which happens
     *         when an {@code Integer}/{@code Decimal} value cannot be parsed, or when the
     *         declared type is unrecognised. ({@code initParam} historically bound nothing
     *         in exactly those cases.)
     */
    public static Object coerceParam(String type, String value) {
        if (type == null) {
            return value; // no Parameter -> default string value
        }
        if (type.equals("Integer")) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (type.equals("Decimal")) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (type.equals("Boolean")) {
            return Boolean.parseBoolean(value);
        }
        if (type.equals("Text")) {
            return value;
        }
        return null; // unrecognised type -> leave unbound
    }

    /**
     * Compose the per-instance unique client id ({@code "<experimentId>-<instanceId>-<clientId>"}).
     * Takes {@code Object} ids so the null-safe string-concatenation semantics of the
     * original (a null id renders as {@code "null"}) are preserved exactly.
     */
    public static String makeUniqueClientId(Object experimentId, Object instanceId, String clientId) {
        return experimentId + "-" + instanceId + "-" + clientId;
    }
}
