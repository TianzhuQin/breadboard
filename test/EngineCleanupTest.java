import com.fasterxml.jackson.databind.type.TypeFactory;
import groovy.lang.Closure;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import models.EngineCleanup;
import models.JsonSetup;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.util.ManagedReference;
import org.junit.Test;

import javax.script.ScriptEngine;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Each launch rebuilds the script engine, and every engine it replaced used to stay in memory for
 * good, one per session, until the server ran out of memory: Groovy's global registry kept the
 * metaclasses the scripts change on their own classes (BBTimer.metaClass.register = ...), and
 * Jackson kept the classes it had written. Groovy's caches of those classes (their other
 * metaclasses, their call sites) kept the engine too, while memory lasted. ScriptBoard hands the
 * engine it replaces to EngineCleanup.release; these tests replace engines built the way ScriptBoard
 * builds them (ScriptTestHarness), as a release does (no experimental scripts).
 */
public class EngineCleanupTest {

    @Test
    public void aReplacedEngineCanBeGarbageCollected() throws Exception {
        JsonSetup.install(); // as at startup
        // Groovy keeps a metaclass for Vertex's classes made when they are first used, with the
        // closures events.groovy gave Vertex then: the JVM's first engines can stay for that. One
        // played before the engine under test takes that place.
        replaced(played(new ScriptTestHarness(false)));
        WeakReference<ScriptEngine> old = new WeakReference<ScriptEngine>(replaced(played(new ScriptTestHarness(false))));
        ScriptTestHarness next = played(new ScriptTestHarness(false));
        try {
            assertTrue("the replaced engine is still in memory", collected(old, 20000));
        } finally {
            next.close();
        }
    }

    @Test
    public void aReplacedEnginesClassesAreUnloadedWithoutAShortageOfMemory() throws Exception {
        // Groovy's caches (and Java's of bean properties) held each engine's classes through soft
        // references, which only a shortage of memory clears: the class space filled up, one engine's
        // classes per session. A soft reference that outlives the collections below shows there was none.
        JsonSetup.install();
        replaced(played(new ScriptTestHarness(false)));
        WeakReference<ClassLoader> classes = classesOfAReplacedEngine();
        ScriptTestHarness next = played(new ScriptTestHarness(false));
        try {
            SoftReference<Object> noShortage = new SoftReference<Object>(new Object());
            assertTrue("the replaced engine's classes are still loaded", collected(classes, 20000));
            assertNotNull("memory ran short: soft references were cleared", noShortage.get());
        } finally {
            next.close();
        }
    }

    @Test
    public void aReplacedEnginesClassesStillWorkWhenTheirCodeRunsAgain() throws Exception {
        // A task of the old engine under way when its timers were cancelled can go on after the release:
        // its classes, gone from Groovy's caches, still make objects (Groovy looks them up anew).
        JsonSetup.install();
        replaced(played(new ScriptTestHarness(false))); // (the JVM's first engines can stay: see above)
        ScriptTestHarness engine = played(new ScriptTestHarness(false));
        Closure<?> laterTask = (Closure<?>) engine.eval("{ -> new Note().text }");
        WeakReference<ClassLoader> classes = new WeakReference<ClassLoader>(scriptLoader(engine));
        replaced(engine);
        engine = null;
        for (int i = 0; i < 3; i++) {
            System.gc(); // (what Groovy holds weakly goes)
            Thread.sleep(50);
        }
        assertEquals("an object of an experiment's own class", laterTask.call());
        laterTask = null;
        // That run put the classes it used back in Groovy's caches, which kept them, and so the whole
        // engine, until memory ran short. The next release takes them out again.
        replaced(played(new ScriptTestHarness(false)));
        ScriptTestHarness next = played(new ScriptTestHarness(false));
        try {
            SoftReference<Object> noShortage = new SoftReference<Object>(new Object());
            assertTrue("the classes of an engine whose code ran again after its release are still loaded",
                collected(classes, 20000));
            assertNotNull("memory ran short: soft references were cleared", noShortage.get());
        } finally {
            next.close();
        }
    }

    @Test
    public void codeOfAnEngineAtWorkWhileItIsReleasedGoesOnWorking() throws Exception {
        // A task of the old engine under way when it is replaced (a timer's task at a reload or a launch)
        // goes on making objects of its scripts' own classes, during the release and after it. Every one
        // of them used to fail from the release on: the task had put Groovy's entry for its class back in
        // the class's own field during the release, and the release then emptied that entry.
        JsonSetup.install();
        replaced(played(new ScriptTestHarness(false))); // (the JVM's first engines can stay: see above)
        ScriptTestHarness engine = played(new ScriptTestHarness(false));
        WeakReference<ClassLoader> classes = new WeakReference<ClassLoader>(scriptLoader(engine));
        Task task = new Task((Closure<?>) engine.eval("class Work { int n; Work twice() { new Work(n: n * 2) } }\n"
            + "def task = { int i -> new Work(n: i).twice().n }\n"
            + "task"), 4);
        try {
            task.awaitCalls(1000);
            replaced(engine);
            engine = null;
            task.awaitCalls(1000);
            // The next release takes the classes the task uses out of Groovy's caches again.
            replaced(played(new ScriptTestHarness(false)));
            task.awaitCalls(1000);
        } finally {
            task.stop();
        }
        task.assertNoneFailed();
        task = null;
        // The engine still goes once the task is over, by the release after it.
        replaced(played(new ScriptTestHarness(false)));
        ScriptTestHarness next = played(new ScriptTestHarness(false));
        try {
            SoftReference<Object> noShortage = new SoftReference<Object>(new Object());
            assertTrue("the classes of an engine whose code was at work during its release are still loaded",
                collected(classes, 20000));
            assertNotNull("memory ran short: soft references were cleared", noShortage.get());
        } finally {
            next.close();
        }
    }

    @Test
    public void replacingEnginesLeavesNothingBehindInJavasClassLoaders() throws Exception {
        // Java's class loaders keep a lock object for every class name they are asked for, found or
        // not, for good, and each engine asked them for about 250 names made up from its scripts' own
        // (Script141$UP, Script141BeanInfo), new with every engine: a little memory per session that
        // never came back.
        replaced(played(new ScriptTestHarness(false)));
        replaced(played(new ScriptTestHarness(false)));
        int before = classLoaderLocks();
        for (int i = 0; i < 3; i++) {
            replaced(played(new ScriptTestHarness(false)));
        }
        int added = classLoaderLocks() - before;
        assertTrue("3 more engines left " + added + " names in Java's class loaders", added < 30);
    }

    @Test
    public void replacingEnginesDoesNotGrowGroovysChangedMetaclasses() throws Exception {
        replaced(played(new ScriptTestHarness(false)));
        int ofScripts = keptMetaClassesOfScripts();
        int all = modifiedExpandos().size();
        for (int i = 1; i <= 3; i++) {
            ScriptTestHarness engine = played(new ScriptTestHarness(false));
            assertFalse("the scripts change the metaclasses of their own classes",
                keptMetaClassesUnder(scriptLoader(engine)).isEmpty());
            replaced(engine);
            assertEquals("engine " + i + ": no class of it keeps a changed metaclass", Collections.emptyList(),
                keptMetaClassesUnder(scriptLoader(engine)));
            assertEquals("engine " + i, ofScripts, keptMetaClassesOfScripts());
            assertEquals("engine " + i, all, modifiedExpandos().size());
        }
    }

    @Test
    public void releasingEnginesDoesNotGrowGroovysRegistryList() throws Exception {
        // Each changed metaclass removed through Groovy's registry (six or more per engine) left an
        // entry that holds nothing in a list of the registry's, for good.
        replaced(played(new ScriptTestHarness(false)));
        int before = emptyRegistryEntries();
        for (int i = 1; i <= 3; i++) {
            replaced(played(new ScriptTestHarness(false)));
        }
        assertEquals("entries that hold nothing, after 3 more engines", before, emptyRegistryEntries());
    }

    @Test
    public void onlyTheReplacedEnginesClassesLoseTheirMetaclasses() throws Exception {
        ScriptTestHarness other = new ScriptTestHarness(false);
        try {
            ScriptTestHarness engine = new ScriptTestHarness(false);
            replaced(engine);
            assertEquals(Collections.emptyList(), keptMetaClassesUnder(scriptLoader(engine)));
            assertFalse("the other engine's classes keep theirs", keptMetaClassesUnder(scriptLoader(other)).isEmpty());
            // The other engine still works: a BBTimer registers itself through its metaclass.
            assertEquals(1, other.eval("def before = timers.timers.size(); new BBTimer(); timers.timers.size() - before"));
        } finally {
            other.close();
        }
    }

    @Test
    public void jacksonForgetsTheReplacedEnginesClasses() throws Exception {
        JsonSetup.install();
        ScriptTestHarness engine = played(new ScriptTestHarness(false));
        ClassLoader scripts = scriptLoader(engine);
        assertTrue("the mapper keeps the classes it wrote", reachesClassUnder(playMapper(), scripts));
        assertTrue("so does the type factory", reachesClassUnder(TypeFactory.defaultInstance(), scripts));
        replaced(engine);
        assertFalse("the mapper still holds a class of the replaced engine", reachesClassUnder(playMapper(), scripts));
        assertFalse("the type factory still holds a class of the replaced engine",
            reachesClassUnder(TypeFactory.defaultInstance(), scripts));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * Uses the engine as a session does: players and ties, their _system, timers, a computer player,
     * JSON, and an experiment's step that changes the metaclass of a class of its own, edited and
     * run again (which defines its classes anew).
     */
    private static ScriptTestHarness played(ScriptTestHarness engine) throws Exception {
        for (String greeting : new String[]{"hello", "hello again"}) {
            engine.eval("class Greeter { }; Greeter.metaClass.greet = { -> '" + greeting + "' };"
                + "assert new Greeter().greet() == '" + greeting + "'; null");
        }
        engine.eval("class Note { String text = 'an object of an experiment\\'s own class' }; null");
        engine.eval("def p = g.addPlayer('p1'); def q = g.addPlayer('p2'); g.addEdge(p, q, 'connected');"
            + "p._system.connected = true; p.text = \"${p.id} joined\".toString();"
            + "new SharedTimer(time: 60, player: p); timers.newTimer().runAfter(60000) { };"
            + "a.ai.add(q); a.add(q, [name: 'Go', result: { }]);"
            + "p.on('ping') { v, data -> };"
            + "p.note = new Note(); play.libs.Json.toJson(p._system); play.libs.Json.toJson(p.note); null");
        return engine;
    }

    /** What ScriptBoard does with the engine it replaces: its timers cancelled, then released. */
    private static ScriptEngine replaced(ScriptTestHarness engine) {
        engine.close();
        EngineCleanup.release(engine.engine);
        return engine.engine;
    }

    /**
     * A task of an engine at work: threads that call {@code body} with 1, 2, 3, ... until stopped, each
     * call to give twice its number.
     */
    private static final class Task {
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        private final List<Thread> threads = new ArrayList<Thread>();

        Task(final Closure<?> body, int threadCount) {
            for (int t = 1; t <= threadCount; t++) {
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        for (int i = 1; !stopped.get(); i++) {
                            try {
                                Object result = body.call(i);
                                if (!Integer.valueOf(2 * i).equals(result)) {
                                    throw new AssertionError("call " + i + " gave " + result);
                                }
                            } catch (Throwable e) {
                                failed.incrementAndGet();
                                firstFailure.compareAndSet(null, e);
                            }
                            calls.incrementAndGet();
                        }
                    }
                }, "EngineCleanupTest task " + t);
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }

        /** Waits for {@code n} more calls, for at most 30 seconds. */
        void awaitCalls(long n) throws InterruptedException {
            long until = System.currentTimeMillis() + 30000;
            long target = calls.get() + n;
            while (calls.get() < target && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            assertTrue("the task made no more calls", calls.get() >= target);
        }

        void stop() throws InterruptedException {
            stopped.set(true);
            for (Thread thread : threads) {
                thread.join(10000);
            }
        }

        void assertNoneFailed() {
            if (failed.get() > 0) {
                AssertionError error = new AssertionError(failed.get() + " of the task's " + calls.get() + " calls failed");
                error.initCause(firstFailure.get());
                throw error;
            }
        }
    }

    /** The class loader of an engine played, then replaced (nothing else of it is kept here). */
    private static WeakReference<ClassLoader> classesOfAReplacedEngine() throws Exception {
        ScriptTestHarness engine = played(new ScriptTestHarness(false));
        WeakReference<ClassLoader> classes = new WeakReference<ClassLoader>(scriptLoader(engine));
        replaced(engine);
        return classes;
    }

    /**
     * How many class names the class loaders above the engines (the server's classpath) keep a lock
     * object for (ClassLoader.parallelLockMap).
     */
    private static int classLoaderLocks() throws Exception {
        Field locks = ClassLoader.class.getDeclaredField("parallelLockMap");
        locks.setAccessible(true);
        int n = 0;
        for (ClassLoader l = Thread.currentThread().getContextClassLoader(); l != null; l = l.getParent()) {
            Map<?, ?> map = (Map<?, ?>) locks.get(l);
            if (map != null) n += map.size();
        }
        return n;
    }

    /** Collects garbage until {@code ref} is cleared, for at most {@code timeoutMs}. */
    static boolean collected(Reference<?> ref, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (ref.get() != null && System.currentTimeMillis() < until) {
            System.gc();
            // A Timer is let go of through a finalizer, and a cancelled one's thread ends on its own.
            System.runFinalization();
            Thread.sleep(50);
        }
        return ref.get() == null;
    }

    /** The loader the engine compiles its scripts with: each script's classes are loaded by a child of it. */
    private static ClassLoader scriptLoader(ScriptTestHarness engine) {
        return engine.get("timers").getClass().getClassLoader().getParent();
    }

    private static boolean loadedUnder(Class<?> c, ClassLoader loader) {
        for (ClassLoader l = c.getClassLoader(); l != null; l = l.getParent()) {
            if (l == loader) return true;
        }
        return false;
    }

    /** The classes loaded under {@code loader} whose changed metaclass Groovy keeps (modifiedExpandos). */
    private static List<Class<?>> keptMetaClassesUnder(ClassLoader loader) throws Exception {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> c : keptMetaClasses()) {
            if (loadedUnder(c, loader)) classes.add(c);
        }
        return classes;
    }

    /** How many of the changed metaclasses Groovy keeps belong to classes a script defined. */
    static int keptMetaClassesOfScripts() throws Exception {
        int n = 0;
        for (Class<?> c : keptMetaClasses()) {
            if (c.getClassLoader() instanceof GroovyClassLoader.InnerLoader) n++;
        }
        return n;
    }

    private static List<Class<?>> keptMetaClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Object info : new ArrayList<Object>(modifiedExpandos())) {
            MetaClass mc = ((ClassInfo) info).getStrongMetaClass();
            if (mc != null) classes.add(mc.getTheClass());
        }
        return classes;
    }

    /**
     * The entries of the registry's list of metaclasses (MetaClassRegistryImpl.metaClassInfo) that
     * hold none and never will, so never leave it. (One whose metaclass was collected is queued to
     * leave, and doesn't count.) Counted after collecting garbage, so that all those are queued:
     * the least of a few counts, should one come between a collection and the queueing.
     */
    private static int emptyRegistryEntries() throws Exception {
        Object registry = GroovySystem.getMetaClassRegistry();
        Field list = registry.getClass().getDeclaredField("metaClassInfo");
        list.setAccessible(true);
        Object entries = list.get(registry);
        Field head = entries.getClass().getDeclaredField("head");
        head.setAccessible(true);
        Field next = Class.forName("org.codehaus.groovy.util.ManagedLinkedList$Element").getDeclaredField("next");
        next.setAccessible(true);
        Field ref = ManagedReference.class.getDeclaredField("ref");
        ref.setAccessible(true);
        int least = Integer.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            System.gc();
            Thread.sleep(100);
            int n = 0;
            synchronized (entries) { // as the registry adds to and reads the list
                for (Object e = head.get(entries); e != null; e = next.get(e)) {
                    Object r = ref.get(e);
                    boolean queued = r instanceof Reference && ((Reference<?>) r).isEnqueued();
                    if (((ManagedReference<?>) e).get() == null && !queued) n++;
                }
            }
            least = Math.min(least, n);
        }
        return least;
    }

    /**
     * Groovy's global set of the classes whose metaclass a script changed: it holds each metaclass,
     * and so its class, for good (ClassInfo.modifiedExpandos).
     */
    private static Set<?> modifiedExpandos() throws Exception {
        Field f = ClassInfo.class.getDeclaredField("modifiedExpandos");
        f.setAccessible(true);
        return (Set<?>) f.get(null);
    }

    /** The mapper Play's Json uses now. */
    private static Object playMapper() throws Exception {
        Field f = play.libs.Json.class.getDeclaredField("objectMapper");
        f.setAccessible(true);
        return f.get(null);
    }

    /**
     * Whether a class loaded under {@code loader} (or an object of one) can be reached from {@code root}
     * through fields, the way the garbage collector would find it: not through what weak and soft
     * references point to, nor through Class objects and class loaders, which are only checked.
     */
    private static boolean reachesClassUnder(Object root, ClassLoader loader) throws Exception {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Deque<Object> queue = new ArrayDeque<Object>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Object o = queue.poll();
            if (!seen.add(o)) continue;
            if (o instanceof Class) {
                if (loadedUnder((Class<?>) o, loader)) return true;
                continue;
            }
            if (o instanceof ClassLoader || o instanceof Thread) continue;
            Class<?> type = o.getClass();
            if (loadedUnder(type, loader)) return true;
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    for (Object e : (Object[]) o) if (e != null) queue.add(e);
                }
                continue;
            }
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()
                        || (c == Reference.class && f.getName().equals("referent"))) continue;
                    f.setAccessible(true);
                    Object value = f.get(o);
                    if (value != null) queue.add(value);
                }
            }
        }
        return false;
    }
}
