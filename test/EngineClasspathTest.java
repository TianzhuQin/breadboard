import org.junit.Test;

import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * An engine rebuild no longer reads every jar of the server's classpath for each name Groovy's
 * compiler can't find there (thousands per build, seconds of every session's start): engines ask the
 * classpath through ScriptClasspath, which remembers those names. The scripts' own classes are found
 * as before.
 */
public class EngineClasspathTest {

    /** The server's classpath, counting the names it is asked for and hasn't got. */
    static final class Counted extends ClassLoader {
        final AtomicInteger missed = new AtomicInteger();
        final Set<String> missedNames = ConcurrentHashMap.newKeySet();

        Counted() {
            super(EngineClasspathTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return super.loadClass(name, resolve);
            } catch (ClassNotFoundException e) {
                missed(name);
                throw e;
            }
        }

        @Override
        public URL getResource(String name) {
            URL found = super.getResource(name);
            if (found == null) missed(name);
            return found;
        }

        private void missed(String name) {
            missed.incrementAndGet();
            missedNames.add(name);
        }
    }

    @Test
    public void aRebuildAsksTheClasspathForAlmostNoneOfTheNamesItHasntGot() throws Exception {
        Counted classpath = new Counted();
        int first = missed(classpath, null);
        int second = missed(classpath, null);
        int third = missed(classpath, null);
        assertTrue("the first build asks for thousands of names that aren't there: " + first, first > 5000);
        assertTrue("the second asked for " + second + " of the first's " + first, second < first / 20);
        assertTrue("the third asked for " + third + " of the first's " + first, third < first / 20);
    }

    @Test
    public void theScriptsOwnClassesAreFoundThoughTheClasspathWasAskedForTheirNames() throws Exception {
        Counted classpath = new Counted();
        missed(classpath, new Played() {
            public void play(ScriptTestHarness engine) throws Exception {
                // Compiled before Later exists: the compiler asks the classpath for it.
                assertEquals("not yet", engine.eval("try { Later.newInstance() } catch (MissingPropertyException e) { 'not yet' }"));
                assertTrue(classpath.missedNames.contains("Later"));
                engine.eval("class Later { String toString() { 'from a later script' } }; null");
                assertEquals("from a later script", engine.eval("new Later().toString()"));
                assertEquals("from a later script", engine.eval("Later.newInstance().toString()"));
                assertEquals("from a later script", engine.eval("Class.forName('Later', true, this.class.classLoader).newInstance().toString()"));
                assertEquals(7, engine.eval("import java.util.concurrent.atomic.AtomicInteger\nnew AtomicInteger(7).get()"));
                assertEquals("not there", engine.eval("try { NoSuchThing.go() } catch (MissingPropertyException e) { 'not there' }"));
            }
        });
        // The next engine (served by the same ScriptClasspath) has a Later of its own.
        missed(classpath, new Played() {
            public void play(ScriptTestHarness engine) throws Exception {
                engine.eval("class Later { String toString() { 'from the next engine' } }; null");
                assertEquals("from the next engine", engine.eval("new Later().toString()"));
            }
        });
    }

    interface Played {
        void play(ScriptTestHarness engine) throws Exception;
    }

    /**
     * Builds an engine as ScriptBoard builds it, with {@code classpath} as the server's, plays it, and
     * returns how many names it asked {@code classpath} for and didn't find.
     */
    private static int missed(Counted classpath, Played played) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader context = thread.getContextClassLoader();
        int before = classpath.missed.get();
        thread.setContextClassLoader(classpath);
        ScriptTestHarness engine;
        try {
            engine = new ScriptTestHarness(false);
        } finally {
            thread.setContextClassLoader(context);
        }
        try {
            if (played != null) played.play(engine);
        } finally {
            engine.close();
        }
        return classpath.missed.get() - before;
    }
}
