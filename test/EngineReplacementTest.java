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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.security.AccessControlContext;
import java.security.ProtectionDomain;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.BackgroundExecutor;
import com.avaje.ebean.event.BeanPersistListener;
import com.avaje.ebeaninternal.api.SpiEbeanServer;
import com.avaje.ebeaninternal.server.deploy.BeanDescriptor;
import com.avaje.ebeaninternal.server.lib.thread.ThreadPool;

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
        // Exercise an earlier replacement as well; EngineCleanupTest separately checks the very
        // first engine, including the library's inherited-method and call-site caches.
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

    @Test
    public void anEventSavedOnAScriptTimerDoesNotKeepThatEnginesClassesInEbeansThreads() throws Exception {
        databaseTaskDoesNotKeepEngine(Event.class, "eventTracker.track('background-write', [source: 'timer'])");
    }

    @Test
    public void dataSavedOnAScriptTimerDoesNotKeepThatEnginesClassesInEbeansThreads() throws Exception {
        databaseTaskDoesNotKeepEngine(Data.class, "d['background-write'] = 'timer'");
    }

    @Test
    public void aSessionFinishedOnAScriptTimerDoesNotKeepThatEnginesClassesInEbeansThreads() throws Exception {
        databaseTaskDoesNotKeepEngine(ExperimentInstance.class, "gameListener.finish()");
    }

    /**
     * Make the real Ebean pool grow while a script timer saves through one of the server's APIs.
     * A pool worker used to inherit that timer's script protection domains, keeping its whole engine
     * loaded for the worker's lifetime, even once the task had finished and all caches were emptied.
     */
    private <T> void databaseTaskDoesNotKeepEngine(Class<T> model, String write) throws Exception {
        User user = user("database-task");
        AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Database task");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        play(t);
        reload(t);
        t.send("LaunchGame", "name", "Background write", "parameters", play.libs.Json.newObject());
        await("a running session", () -> ExperimentInstance.find.where().eq("name", "Background write")
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        play(t);
        WeakReference<ClassLoader> scripts = new WeakReference<ClassLoader>(EngineCleanup.scriptLoader(engine()));
        BackgroundExecutor executor = Ebean.getServer("default").getBackgroundExecutor();
        Field poolField = executor.getClass().getDeclaredField("pool");
        poolField.setAccessible(true);
        ThreadPool pool = (ThreadPool) poolField.get(executor);
        CountDownLatch release = new CountDownLatch(1);
        BeanDescriptor<T> descriptor = ((SpiEbeanServer) Ebean.getServer("default")).getBeanDescriptor(model);
        AtomicReference<Thread> worker = new AtomicReference<>();
        BeanPersistListener<T> listener = new BeanPersistListener<T>() {
            public boolean inserted(T bean) { worker.set(Thread.currentThread()); return false; }
            public boolean updated(T bean, java.util.Set<String> properties) { worker.set(Thread.currentThread()); return false; }
            public boolean deleted(T bean) { return false; }
            public void remoteInsert(Object id) {}
            public void remoteUpdate(Object id) {}
            public void remoteDelete(Object id) {}
        };
        // Ebean 3.2 registers listeners by a concrete generic argument; this helper exercises three
        // model types, so install its listener directly and restore the descriptor in finally.
        Field persistListener = BeanDescriptor.class.getDeclaredField("persistListener");
        persistListener.setAccessible(true);
        Object originalListener = persistListener.get(descriptor);
        try {
            // Keep every existing worker busy, forcing this save to create one. Their tasks are
            // server/test code only, never closures loaded by the script engine under test.
            do {
                CountDownLatch started = new CountDownLatch(1);
                executor.execute(() -> {
                    started.countDown();
                    try { release.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                assertTrue("an Ebean worker started", started.await(5, TimeUnit.SECONDS));
            } while (pool.getBusyCount() < pool.size());
            int before = pool.size();
            persistListener.set(descriptor, listener);
            // Evaluate directly: SendScript also saves the researcher's buffer before the timer
            // runs, which would grow the pool from its actor and hide the timer's inherited context.
            engine().eval("timers.newTimer().runAfter(50) { " + write + " }; null");
            await("the saved bean's real background notification", () -> worker.get() != null);
            assertTrue("the script's save grew Ebean's pool", pool.size() > before);
            assertTrue("the notification ran in Ebean's worker", worker.get().getName().startsWith("Ebean-default."));
            assertNoScriptDomains(worker.get(), scripts.get());
        } finally {
            persistListener.set(descriptor, originalListener);
            release.countDown();
        }
        for (int rebuild = 0; rebuild < 3; rebuild++) { reload(t); play(t); }
        assertTrue("the engine whose timer grew Ebean's pool is still loaded",
            EngineCleanupTest.collected(scripts, 20000));
    }

    private static void assertNoScriptDomains(Thread worker, ClassLoader scripts) throws Exception {
        Field inherited = Thread.class.getDeclaredField("inheritedAccessControlContext");
        inherited.setAccessible(true);
        AccessControlContext context = (AccessControlContext) inherited.get(worker);
        if (context == null) return;
        Field domains = AccessControlContext.class.getDeclaredField("context");
        domains.setAccessible(true);
        ProtectionDomain[] values = (ProtectionDomain[]) domains.get(context);
        if (values == null) return;
        for (ProtectionDomain domain : values) {
            if (domain == null) continue;
            for (ClassLoader loader = domain.getClassLoader(); loader != null; loader = loader.getParent()) {
                assertNotSame("Ebean's idle worker inherited the replaced engine's script domain", scripts, loader);
            }
        }
    }

    @Test
    public void openingSessionsDoesNotKeepEveryPastSessionAndItsParametersInTheEngine() throws Exception {
        User user = user("session-records");
        AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Session records");
        createParameter(experiment, "rounds", "Integer", "3");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        for (int n = 1; n <= 6; n++) {
            final String name = "Records " + n;
            t.send("LaunchGame", "name", name, "parameters", play.libs.Json.newObject());
            await(name, () -> t.count(m -> m.path("experimentInstance").path("name").asText().equals(name)
                && m.path("experimentInstance").path("status").asText().equals("RUNNING")) > 0);
            ContentFetcher content = (ContentFetcher) engine().get("c");
            assertHistoryNotKept("the content fetcher", content.selectedExperiment, n);
            Field listenerField = ScriptBoard.class.getDeclaredField("engineGameListener");
            listenerField.setAccessible(true);
            GameListener listener = (GameListener) listenerField.get(null);
            assertHistoryNotKept("the current session", listener.experimentInstance.experiment, n);
            assertHistoryNotKept("the session's account", listener.user.selectedExperiment, n);
        }
        assertEquals("scripts can still explicitly ask for the complete history", 6,
            engine().eval("c.selectedExperiment.instances.size()"));
        Experiment fromDatabase = Experiment.findById(experiment.id);
        assertEquals("the saved history can still be read through experiment.instances", 6, fromDatabase.instances.size());
        for (ExperimentInstance saved : fromDatabase.instances) {
            assertEquals("each saved session keeps its launch parameter", "rounds", saved.data.get(0).name);
            assertEquals("3", saved.data.get(0).value);
        }
    }

    @Test
    public void aSessionCanFinishAfterItsResearcherAccountWasRemoved() throws Exception {
        User user = user("removed-owner");
        AdminSocketsTest.Tab t = loggedIn(user);
        Experiment experiment = createExperiment("Removed owner");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        t.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(engineExperimentId()));
        t.send("LaunchGame", "name", "Without its account", "parameters", play.libs.Json.newObject());
        await("a running session", () -> ExperimentInstance.find.where().eq("name", "Without its account")
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        Field f = ScriptBoard.class.getDeclaredField("engineGameListener");
        f.setAccessible(true);
        GameListener listener = (GameListener) f.get(null);
        Long id = listener.experimentInstance.id;
        User other = user("remaining-researcher");
        play.mvc.Result removed = play.test.Helpers.route(play.test.Helpers.fakeRequest("POST", "/researchers/remove")
            .withSession("uid", other.uid).withJsonBody(play.libs.Json.newObject().put("email", user.email)));
        assertEquals(200, play.test.Helpers.status(removed));
        listener.finish();
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(id).status);
    }

    private static void assertHistoryNotKept(String holder, Experiment experiment, int opened) {
        if (experiment == null) return;
        java.util.List<ExperimentInstance> history = experiment.instances;
        Object actual = history instanceof com.avaje.ebean.bean.BeanCollection
            ? ((com.avaje.ebean.bean.BeanCollection<?>) history).getActualCollection() : history;
        int retained = actual instanceof java.util.Collection ? ((java.util.Collection<?>) actual).size() : 0;
        assertTrue(holder + " keeps " + retained + " session records after opening " + opened, retained <= 1);
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
        return createUser(prefix + "-" + UUID.randomUUID() + "@example.org", prefix, "secret-1", lang);
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
