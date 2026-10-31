import models.*;
import org.junit.After;
import org.junit.Test;

import javax.script.ScriptEngine;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

/**
 * The engine ScriptBoard replaces (here by a reload; every launch does the same) can be garbage
 * collected: it used to stay in memory for good, one per session, until the server ran out of memory.
 * So can its classes: they stayed loaded until the class space was full. Later rebuilds unload them.
 */
public class EngineReplacementTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
        // The engine is shared with the next test: without the players and timers played here (a
        // console logging in there was sent a player 'p1' of this game, and its timer's updates).
        try {
            engine().eval("g.empty(); timers.cancel(); null");
        } catch (Exception ignored) {}
    }

    @Test
    public void theEngineAReloadReplacesCanBeGarbageCollected() throws Exception {
        User user = user("replaced");
        final AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Replaced");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        // The JVM's first engines can stay through Groovy's caches of Vertex's metaclasses (see
        // EngineCleanupTest): one played before the engine under test takes that place.
        play(t);
        reload(t);
        play(t);
        WeakReference<ScriptEngine> replaced = new WeakReference<ScriptEngine>(engine());
        WeakReference<ClassLoader> itsClasses = new WeakReference<ClassLoader>(EngineCleanup.scriptLoader(engine()));
        // (It asks the server's classpath through ScriptClasspath, which serves every engine and so
        // must not keep one.)
        assertTrue("the engine's loader asks " + itsClasses.get().getParent(),
            itsClasses.get().getParent() instanceof ScriptClasspath);
        int keptMetaClasses = EngineCleanupTest.keptMetaClassesOfScripts();
        reload(t);
        play(t);
        // Its classes go too, and not only once memory runs short: Groovy's caches held them through
        // soft references, so they stayed loaded until the class space was full. A soft reference
        // that outlives the collections shows that memory didn't run short.
        SoftReference<Object> noShortage = new SoftReference<Object>(new Object());
        assertTrue("the engine the reload replaced is still in memory", EngineCleanupTest.collected(replaced, 20000));
        assertTrue("the classes of the engine the reload replaced are still loaded",
            EngineCleanupTest.collected(itsClasses, 20000));
        assertNotNull("memory ran short: soft references were cleared", noShortage.get());
        assertEquals("Groovy keeps the changed metaclasses of one engine's classes, not two",
            keptMetaClasses, EngineCleanupTest.keptMetaClassesOfScripts());
    }

    @Test
    public void laterRebuildsUnloadAReplacedEnginesClassesWithoutHelp() throws Exception {
        // The JVM collects in full only now and then on its own, so the classes of replaced engines
        // still piled up towards the class space's cap. Each rebuild collects garbage now: an engine is
        // unloaded by the second rebuild after the one that replaced it (the first runs its timers'
        // finalizers). Nothing here collects garbage itself.
        User user = user("unloaded");
        final AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Unloaded");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        play(t);
        reload(t);
        play(t);
        WeakReference<ClassLoader> itsClasses = new WeakReference<ClassLoader>(EngineCleanup.scriptLoader(engine()));
        // (A fourth rebuild in case its timers' finalizers hadn't run yet at the third.)
        for (int rebuild = 1; rebuild <= 4 && itsClasses.get() != null; rebuild++) {
            reload(t);
            play(t);
        }
        assertNull("the classes of an engine replaced four rebuilds ago are still loaded", itsClasses.get());
    }

    @Test
    public void anEngineWhoseTimerTaskFinishesAfterTheReloadIsUnloadedToo() throws Exception {
        // A rebuild cancels the timers, but a timer's task under way then runs on, and so runs the old
        // engine's code after its release. That put the classes it used back in Groovy's caches, which
        // kept them, and so the whole engine, until the class space was full. The next rebuild takes
        // them out again.
        User user = user("task");
        final AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Task");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        play(t);
        reload(t);
        play(t);
        for (String signal : new String[]{TASK_STARTED, ENGINE_REPLACED, TASK_FINISHED}) {
            System.clearProperty(signal);
        }
        final String sent = "slow-task-sent";
        t.send("SendScript", "script", "class SlowWork { String finish() { 'finished' } }\n"
            + "timers.newTimer().runAfter(10) {\n"
            + "  System.setProperty('" + TASK_STARTED + "', 'yes')\n"
            + "  def until = System.currentTimeMillis() + 30000\n"
            + "  while (System.getProperty('" + ENGINE_REPLACED + "') == null && System.currentTimeMillis() < until) {\n"
            + "    Thread.sleep(20)\n"
            + "  }\n"
            + "  System.setProperty('" + TASK_FINISHED + "', new SlowWork().finish())\n"
            + "}\n"
            + "'" + sent + "'");
        await(sent, () -> t.count(AdminSocketsTest.output(sent)) == 1);
        await("the task under way", () -> System.getProperty(TASK_STARTED) != null);
        WeakReference<ClassLoader> itsClasses = new WeakReference<ClassLoader>(EngineCleanup.scriptLoader(engine()));
        try {
            reload(t);
        } finally {
            System.setProperty(ENGINE_REPLACED, "yes");
        }
        await("the task finished, after the engine was replaced", () -> System.getProperty(TASK_FINISHED) != null);
        assertEquals("an object of the replaced engine's own class", "finished", System.getProperty(TASK_FINISHED));
        for (int rebuild = 1; rebuild <= 3 && itsClasses.get() != null; rebuild++) {
            play(t);
            reload(t);
        }
        SoftReference<Object> noShortage = new SoftReference<Object>(new Object());
        assertTrue("the engine whose timer task finished after the reload is still loaded",
            EngineCleanupTest.collected(itsClasses, 20000));
        assertNotNull("memory ran short: soft references were cleared", noShortage.get());
    }

    @Test
    public void aTimerTaskAtWorkDuringTheReloadGoesOnWorking() throws Exception {
        // A timer's task under way at a reload (or a launch) runs on while the old engine is released,
        // making objects of its script's own class. Every one it made from the reload on used to fail:
        // the release emptied the entry Groovy keeps for the class while the task held it.
        User user = user("working");
        final AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Working");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        play(t);
        for (String signal : new String[]{BUSY_STARTED, BUSY_STOP, BUSY_FINISHED}) {
            System.clearProperty(signal);
        }
        final String sent = "busy-task-sent";
        t.send("SendScript", "script", "class Busy { int n; Busy twice() { new Busy(n: n * 2) } }\n"
            + "timers.newTimer().runAfter(10) {\n"
            + "  int made = 0; int failed = 0; String first = null\n"
            + "  def until = System.currentTimeMillis() + 30000\n"
            + "  while (System.getProperty('" + BUSY_STOP + "') == null && System.currentTimeMillis() < until) {\n"
            + "    try { assert new Busy(n: made).twice().n == made * 2; made++ }\n"
            + "    catch (Throwable e) { failed++; if (first == null) first = e.toString() }\n"
            + "    if (made == 1000) System.setProperty('" + BUSY_STARTED + "', 'yes')\n"
            + "  }\n"
            + "  System.setProperty('" + BUSY_FINISHED + "', 'made ' + made + ', failed ' + failed + (first ? ', first ' + first : ''))\n"
            + "}\n"
            + "'" + sent + "'");
        await(sent, () -> t.count(AdminSocketsTest.output(sent)) == 1);
        await("the task at work", () -> System.getProperty(BUSY_STARTED) != null);
        try {
            reload(t);
            Thread.sleep(500); // (and on after it)
        } finally {
            System.setProperty(BUSY_STOP, "yes");
        }
        await("the task finished", () -> System.getProperty(BUSY_FINISHED) != null);
        String result = System.getProperty(BUSY_FINISHED);
        assertTrue("the task at work during the reload: " + result, result.matches("made \\d+, failed 0"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** What the busy task of aTimerTaskAtWorkDuringTheReloadGoesOnWorking and the test tell each other. */
    private static final String BUSY_STARTED = "EngineReplacementTest.busyStarted";
    private static final String BUSY_STOP = "EngineReplacementTest.busyStop";
    private static final String BUSY_FINISHED = "EngineReplacementTest.busyFinished";

    /** What the slow task of anEngineWhoseTimerTaskFinishesAfterTheReloadIsUnloadedToo and the test tell each other. */
    private static final String TASK_STARTED = "EngineReplacementTest.taskStarted";
    private static final String ENGINE_REPLACED = "EngineReplacementTest.engineReplaced";
    private static final String TASK_FINISHED = "EngineReplacementTest.taskFinished";

    private int played = 0;

    /** Uses the engine as a session does, from the console: players and ties, timers, a computer player. */
    private void play(final AdminSocketsTest.Tab t) throws Exception {
        final String done = "played-" + (++played);
        t.send("SendScript", "script", "class Note { String text = 'an object of an experiment\\'s own class' }\n"
            + "def p = g.addPlayer('p1'); def q = g.addPlayer('p2'); g.addEdge(p, q, 'connected')\n"
            + "p._system.connected = true; p.note = new Note()\n"
            + "new SharedTimer(time: 60, player: p); timers.newTimer().runAfter(60000) { }\n"
            + "a.ai.add(q); a.add(q, [name: 'Go', result: { }])\n"
            + "p.on('ping') { v, data -> }\n"
            + "'" + done + "'");
        await(done, () -> t.count(AdminSocketsTest.output(done)) == 1);
    }

    private static void reload(final AdminSocketsTest.Tab t) throws Exception {
        final int before = t.count(AdminSocketsTest.notify("ScriptEngineReloaded"));
        t.send("ReloadEngine");
        await("reloaded", () -> t.count(AdminSocketsTest.notify("ScriptEngineReloaded")) > before);
    }

    private AdminSocketsTest.Tab loggedIn(User user) throws Exception {
        final AdminSocketsTest.Tab t = new AdminSocketsTest.Tab(user.uid);
        tabs.add(t);
        t.send("LogIn");
        await("LogIn", () -> t.count(AdminSocketsTest.userJson()) >= 1);
        return t;
    }

    private User user(String prefix) {
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        return createUser(prefix + "-" + UUID.randomUUID() + "@test.org", prefix, "secret-1", lang);
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    private static ScriptEngine engine() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (ScriptEngine) f.get(null);
    }

    private static Long engineExperimentId() throws Exception {
        Field f = ScriptBoard.class.getDeclaredField("engineExperimentId");
        f.setAccessible(true);
        return (Long) f.get(null);
    }
}
