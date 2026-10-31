import groovy.lang.Closure;
import models.EventBus;
import models.EventTracker;
import models.GameListener;
import models.ScriptClasspath;
import models.ScriptLoader;
import org.apache.commons.io.FileUtils;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.InvokerInvocationException;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A test harness for driving the Gremlin-Groovy ScriptEngine the same way
 * {@code ScriptBoard.resetEngine} does, but WITHOUT a database or running Play
 * application:
 *
 * <ul>
 *   <li><b>Persistence is disabled</b> -- the {@link EventTracker} is disabled and the
 *       {@link GameListener} has no {@code ExperimentInstance}, so {@code track()} /
 *       {@code finish()} / {@code start()} etc. become no-ops instead of DB writes.</li>
 *   <li><b>No FakeApplication</b> -- the groovy scripts are Play-free, so the engine is
 *       built standalone. Scripts are resolved relative to the project working directory.</li>
 * </ul>
 *
 * <h3>Async testing</h3>
 * The DSL is event-driven: steps complete via listeners, timers fire later on their own
 * threads. To test that, write the test body as a Groovy closure and signal completion
 * with the injected {@code done} closure. Supported signatures:
 *
 * <pre>{@code
 * // 1. Synchronous
 * harness.runSync("{ -> g.addPlayer('p1'); assert g.V.count() == 1 }");
 *
 * // 2. Async -- closure receives `done`; call it when the async work resolves
 * harness.runAsync("{ done ->\n" +
 *     "  timers.newTimer().runAfter(50) { done() }\n" +
 *     "}", 2000);
 *
 * // 3. Async with assertions on the callback thread (failures surface as AssertionError,
 * //    instead of being swallowed by the timer/listener thread):
 * harness.runAsync("{ done ->\n" +
 *     "  def s = stepFactory.createStep('s1')\n" +
 *     "  s.run  = { g.addPlayer('p1') }\n" +
 *     "  s.done = { done { assert g.getVertex('p1') != null } }\n" +
 *     "  s.start()\n" +
 *     "}", 2000);
 * }</pre>
 *
 * {@code done} accepts three forms, callable from anywhere in the engine (it is also a
 * global binding, so nested timer/listener closures can call it directly):
 * <ul>
 *   <li>{@code done()} -- success</li>
 *   <li>{@code done { assert ... }} -- run assertions safely, then complete</li>
 *   <li>{@code done(throwable)} -- fail with the given throwable</li>
 * </ul>
 */
public class ScriptTestHarness {

    /**
     * The eval-ready source of each script (raw file contents + ";null;"), keyed by file name
     * and read from disk once per JVM. ~29 harnesses are built across the suite; without this
     * cache each one re-read every script. Script bodies don't change during a run, so the text
     * is stable; only the (cheap) {@code engine.eval} still runs per harness, preserving isolation.
     */
    private static final ConcurrentHashMap<String, String> SOURCE_CACHE =
        new ConcurrentHashMap<String, String>();

    /**
     * The JVM's one script bus ({@link EventBus#forScripts}), the same instance the server's engine
     * binds, cleared in each constructor. events.groovy installs
     * {@code Vertex.metaClass.on/once/off/send} -- JVM-global metaclass mutations whose closures
     * permanently capture the {@code events} binding of the FIRST engine to evaluate events.groovy
     * (re-assigning a global metaclass method from a later engine does NOT replace the closure). With a
     * bus of its own, every engine after the first desynced: {@code player.on(...)} routed through the
     * stale metaclass closure registered listeners on the first engine's bus, while this engine's own
     * CustomEvent handler emitted on its own bus -- so player-scoped events never delivered and group
     * choices never resolved. That first engine may be another harness or, when engine tests such as
     * AdminSocketsTest ran earlier in the same JVM, the server's own ScriptBoard engine; a bus of the
     * harness's own (as before) then failed ~20 Groovy event tests depending on test order. One bus for
     * every engine keeps the metaclass closures and every engine pointed at the same instance; clearing
     * it per harness keeps listeners from leaking between cases (ScriptBoard clears it on every rebuild
     * too). Safe because tests run sequentially (parallelExecution in Test := false).
     */
    private static final EventBus SHARED_EVENTS = EventBus.forScripts();

    public final ScriptEngine engine;
    public final EventTracker eventTracker;
    public final RecordingGameListener gameListener;

    public ScriptTestHarness() {
        this(true);
    }

    /**
     * @param experimental also load the experimental scripts (ScriptLoader.EXPERIMENTAL_SCRIPTS); false
     *     loads exactly what a release with the default settings loads
     */
    public ScriptTestHarness(boolean experimental) {
        try {
            this.engine = ScriptClasspath.newEngine(new ScriptEngineManager()); // as ScriptBoard makes it
            if (this.engine == null) {
                throw new IllegalStateException("gremlin-groovy ScriptEngine not found on the classpath");
            }
            this.eventTracker = new EventTracker();
            this.eventTracker.disable();                 // persistence off: track() becomes a no-op
            this.gameListener = new RecordingGameListener(); // no ExperimentInstance: lifecycle is a no-op

            Bindings b = engine.getBindings(ScriptContext.ENGINE_SCOPE);
            b.put("r", new Random());
            b.put("results", new HashMap());
            b.put("eventTracker", eventTracker);
            b.put("gameListener", gameListener);
            SHARED_EVENTS.clear();                        // see SHARED_EVENTS: per-harness bus desyncs from the global Vertex.metaClass closures
            b.put("events", SHARED_EVENTS);

            // Load in the same order production uses (ScriptLoader is the single source of
            // truth, so the harness and ScriptBoard can't drift). A core script failing to
            // load is fatal; a non-core drop-in script failing is skipped, matching production
            // and keeping the rest of the suite runnable.
            File dir = groovyDir();
            for (String name : ScriptLoader.resolveLoadOrder(dir, experimental)) {
                try {
                    ScriptLoader.evalNamed(engine, name, loadSource(dir, name));
                } catch (ScriptException | RuntimeException ex) {
                    if (ScriptLoader.isCore(name)) throw ex;
                    System.err.println("ScriptTestHarness: skipping non-core script " + name + " -> " + ex.getMessage());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize ScriptTestHarness", e);
        }
    }

    /** Bind an extra value into the engine (e.g. a stub ContentFetcher as {@code c}). */
    public void put(String name, Object value) {
        engine.getBindings(ScriptContext.ENGINE_SCOPE).put(name, value);
    }

    /** Evaluate a raw script and return its result. */
    public Object eval(String script) throws Exception {
        return engine.eval(script);
    }

    public Object get(String name) {
        return engine.get(name);
    }

    /**
     * Run a synchronous test body. The source must evaluate to a closure; it is invoked
     * with no arguments. Any thrown exception (including AssertionError) propagates.
     */
    public void runSync(String closureSource) {
        Object callable;
        try {
            callable = engine.eval(closureSource);
        } catch (Throwable t) {
            cancelTimers();
            rethrow(t);
            return;
        }
        if (!(callable instanceof Closure)) {
            cancelTimers();
            throw new IllegalArgumentException("runSync body must evaluate to a closure");
        }
        runSyncClosure((Closure) callable);
    }

    /**
     * Run a synchronous test body that is already a {@link Closure} (e.g. one registered by a
     * {@code *_test.groovy} file). Any thrown exception (including AssertionError) propagates.
     */
    public void runSyncClosure(Closure<?> body) {
        try {
            body.call();
        } catch (Throwable t) {
            rethrow(t);
        } finally {
            cancelTimers();
        }
        failOnStepFailures();
    }

    /**
     * Run an async test body and block until {@code done} is signalled or the timeout
     * elapses. The source must evaluate to a closure. If it declares one parameter it is
     * passed {@code done}; if it declares none, it is run and treated as already complete.
     *
     * @throws AssertionError if {@code done} is never called within the timeout, or if an
     *                        assertion routed through {@code done}/{@code check} failed.
     */
    public void runAsync(String closureSource, long timeoutMs) {
        Object callable;
        try {
            callable = engine.eval(closureSource);
        } catch (Throwable t) {
            // Synchronous failure before any closure could be invoked.
            cancelTimers();
            rethrow(t);
            return;
        }
        runAsyncClosure(callable, timeoutMs);
    }

    /**
     * Run an async test body that is already a {@link Closure} (or callable), blocking until
     * {@code done} is signalled or {@code timeoutMs} elapses. Same contract as
     * {@link #runAsync(String, long)}, but for a closure registered by a {@code *_test.groovy}
     * file rather than an eval'd string.
     */
    public void runAsyncClosure(Object callable, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean(false);

        // done() | done { assertions } | done(throwable)
        Closure done = new Closure(null) {
            public Object doCall(Object... args) {
                if (!completed.compareAndSet(false, true)) {
                    return null; // idempotent: first signal wins
                }
                try {
                    if (args != null && args.length > 0 && args[0] != null) {
                        Object first = args[0];
                        if (first instanceof Closure) {
                            ((Closure) first).call();          // run assertions on this thread, captured below
                        } else if (first instanceof Throwable) {
                            error.set((Throwable) first);
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
                return null;
            }
        };

        // check { assert ... } -- assert without completing; a failure fails the test immediately.
        Closure check = new Closure(null) {
            public Object doCall(Object... args) {
                try {
                    if (args != null && args.length > 0 && args[0] instanceof Closure) {
                        ((Closure) args[0]).call();
                    }
                } catch (Throwable t) {
                    if (completed.compareAndSet(false, true)) {
                        error.set(t);
                        latch.countDown();
                    }
                }
                return null;
            }
        };

        Bindings b = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        b.put("done", done);
        b.put("check", check);

        try {
            invoke(callable, done);
        } catch (Throwable t) {
            // Synchronous failure before any async callback registered.
            cancelTimers();
            rethrow(t);
            return;
        }

        boolean signalled;
        try {
            signalled = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cancelTimers();
            throw new RuntimeException("Interrupted while awaiting done()", ie);
        }
        cancelTimers();

        if (!signalled) {
            failOnStepFailures(); // (a step that threw is the likelier reason)
            throw new AssertionError("Async test did not call done() within " + timeoutMs + "ms");
        }
        Throwable e = error.get();
        if (e != null) {
            rethrow(e);
        }
        failOnStepFailures();
    }

    /** Cancel any timers the test scheduled, so their threads don't outlive the test. */
    public void cancelTimers() {
        try {
            engine.eval("timers.cancel()");
        } catch (Exception ignored) {
            // engine may already be unusable; nothing to clean up
        }
    }

    /** Release resources. Safe to call from an @After method. */
    public void close() {
        cancelTimers();
    }

    // --- *_test.groovy registration support ---

    /**
     * Bind a fresh {@link GroovyTestRegistry} as {@code test} and evaluate the given
     * {@code *_test.groovy} file (resolved from {@link #groovyDir()}) into this engine, so its
     * {@code test(...)} / {@code test.async(...)} calls register their cases. Returns the
     * registry holding what the file registered. The file is named for error attribution, the
     * same way production scripts are.
     */
    public GroovyTestRegistry loadTestFile(String fileName) throws Exception {
        GroovyTestRegistry registry = new GroovyTestRegistry();
        put("test", registry);
        String source = FileUtils.readFileToString(new File(groovyDir(), fileName), "UTF-8") + ";null;";
        ScriptLoader.evalNamed(engine, fileName, source);
        return registry;
    }

    /** Run one registered case: synchronous bodies via {@link #runSyncClosure}, async via {@link #runAsyncClosure}. */
    public void runCase(GroovyTestRegistry.Case c) {
        if (c.async) {
            runAsyncClosure(c.body, c.timeoutMs > 0 ? c.timeoutMs : DEFAULT_ASYNC_TIMEOUT_MS);
        } else {
            runSyncClosure(c.body);
        }
    }

    /** Default await for {@code test.async("name") { done -> ... }} when no explicit timeout is given. */
    public static final long DEFAULT_ASYNC_TIMEOUT_MS = 2000L;

    // --- internals ---

    private void invoke(Object callable, Closure done) {
        if (callable instanceof Closure) {
            Closure c = (Closure) callable;
            if (c.getMaximumNumberOfParameters() == 0) {
                c.call();
                done.call();            // explicit 0-arg closure: synchronous, auto-complete
            } else {
                c.call(done);
            }
        } else if (callable != null) {
            InvokerHelper.invokeMethod(callable, "call", new Object[]{ done });
        } else {
            throw new IllegalArgumentException("runAsync body must evaluate to a closure or callable");
        }
    }

    private static void rethrow(Throwable t) {
        Throwable u = unwrap(t);
        if (u instanceof Error) throw (Error) u;          // AssertionError / PowerAssertionError land here
        if (u instanceof RuntimeException) throw (RuntimeException) u;
        throw new RuntimeException("Async test failed", u);
    }

    /** Groovy wraps closure-body exceptions in InvokerInvocationException; peel that off. */
    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvokerInvocationException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }

    /** Eval-ready source for a script file (raw contents + ";null;"), cached across harnesses. */
    private String loadSource(File groovyDir, String name) throws Exception {
        String cached = SOURCE_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String source = FileUtils.readFileToString(new File(groovyDir, name), "UTF-8") + ";null;";
        SOURCE_CACHE.put(name, source);
        return source;
    }

    /** Locate the groovy script directory: relative to the test cwd (project root), else via Play. */
    public static File groovyDir() {
        File local = new File("groovy");                   // sbt runs tests with cwd = project root
        if (local.isDirectory()) return local;
        try {
            if (play.Play.application() != null) {
                File viaPlay = new File(play.Play.application().path().toString(), "groovy");
                if (viaPlay.isDirectory()) return viaPlay;
            }
        } catch (Throwable ignored) {
            // Play not running -- fall through to the error below
        }
        throw new IllegalStateException(
            "Cannot find groovy script directory (cwd=" + new File(".").getAbsolutePath() + ")");
    }

    /**
     * A GameListener that records finish() calls and the steps that failed. With no ExperimentInstance,
     * nothing persists.
     */
    public static class RecordingGameListener extends GameListener {
        public final AtomicInteger finishCount = new AtomicInteger(0);
        /** Each step whose code threw (groovy/step.groovy reports it here), oldest first. */
        public final List<StepFailure> stepFailures = new CopyOnWriteArrayList<StepFailure>();

        @Override
        public void finish() {
            finishCount.incrementAndGet();
            super.finish(); // safe: experimentInstance == null and user == null
        }

        public boolean finished() {
            return finishCount.get() > 0;
        }

        @Override
        public boolean stepFailed(Object step, Throwable failure, Long going) {
            stepFailures.add(new StepFailure(step, failure));
            // (the server's own handling: this engine isn't the server's, so it is only logged)
            return super.stepFailed(step, failure, going);
        }
    }

    /** A step whose code threw, and what it threw. */
    public static class StepFailure {
        public final Object step;
        public final Throwable failure;

        StepFailure(Object step, Throwable failure) {
            this.step = step;
            this.failure = failure;
        }
    }

    /**
     * A step that throws no longer fails the thread it runs on (it is reported to the game listener
     * instead), so a case with a broken step would pass: it fails here, unless the case took the
     * failure out of gameListener.stepFailures, having expected it.
     */
    private void failOnStepFailures() {
        if (!gameListener.stepFailures.isEmpty()) {
            Throwable first = gameListener.stepFailures.get(0).failure;
            throw new AssertionError("A step threw: " + first, first);
        }
    }
}
