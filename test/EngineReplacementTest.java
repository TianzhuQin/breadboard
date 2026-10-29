import models.*;
import org.junit.After;
import org.junit.Test;

import javax.script.ScriptEngine;
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
 */
public class EngineReplacementTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();

    @After
    public void closeTabs() throws Throwable {
        for (AdminSocketsTest.Tab t : tabs) t.close();
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
        int keptMetaClasses = EngineCleanupTest.keptMetaClassesOfScripts();
        reload(t);
        play(t);
        assertTrue("the engine the reload replaced is still in memory", EngineCleanupTest.collected(replaced, 20000));
        assertEquals("Groovy keeps the changed metaclasses of one engine's classes, not two",
            keptMetaClasses, EngineCleanupTest.keptMetaClassesOfScripts());
    }

    // ---- helpers ------------------------------------------------------------------------------------

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
