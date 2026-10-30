package models;

import org.junit.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ScriptClasspath, the class loader every script engine's class loader asks for what isn't a class of
 * its scripts: it remembers the names the server's classpath hasn't got, so that Groovy's compiler
 * no longer reads every jar for each of its misses at every rebuild.
 */
public class ScriptClasspathTest {

    /** The server's classpath, counting what it is asked for. */
    static final class Counted extends ClassLoader {
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());

        Counted() {
            super(ScriptClasspathTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            asked.add(name);
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            asked.add(name);
            return super.getResource(name);
        }

        int times(String name) {
            return Collections.frequency(asked, name);
        }
    }

    @Test
    public void aNameTheClasspathHasntGotIsAskedForOnce() throws Exception {
        Counted classpath = new Counted();
        ScriptClasspath loader = ScriptClasspath.forNextEngine(classpath);
        for (int i = 0; i < 3; i++) {
            try {
                loader.loadClass("java.util.NoSuchThing");
                fail("found a class that doesn't exist");
            } catch (ClassNotFoundException expected) {
                assertEquals("java.util.NoSuchThing", expected.getMessage());
            }
            assertNull(loader.getResource("java/util/NoSuchThing.groovy"));
        }
        assertEquals(1, classpath.times("java.util.NoSuchThing"));
        assertEquals(1, classpath.times("java/util/NoSuchThing.groovy"));
    }

    @Test
    public void whatTheClasspathHasIsFoundThere() throws Exception {
        Counted classpath = new Counted();
        ScriptClasspath loader = ScriptClasspath.forNextEngine(classpath);
        for (int i = 0; i < 2; i++) {
            assertSame(java.util.ArrayList.class, loader.loadClass("java.util.ArrayList"));
            assertSame(ScriptBoard.class, loader.loadClass("models.ScriptBoard"));
            assertEquals(classpath.getResource("models/ScriptBoard.class"), loader.getResource("models/ScriptBoard.class"));
        }
        assertEquals("asked each time", 2, classpath.times("models.ScriptBoard"));
    }

    @Test
    public void aNameNoLongerAskedForIsForgottenTwoEnginesLater() throws Exception {
        // Each build asks for some names made of its scripts' names (Script141$UP), which never come
        // again: they don't pile up. The names every build asks for stay.
        Counted classpath = new Counted();
        ScriptClasspath loader = ScriptClasspath.forNextEngine(classpath);
        missing(loader, "Script141$UP");
        missing(loader, "groovy.util.EveryTime");
        int remembered = loader.remembered();
        for (int engine = 2; engine <= 4; engine++) {
            assertSame("the same one serves every engine", loader, ScriptClasspath.forNextEngine(classpath));
            missing(loader, "groovy.util.EveryTime");
        }
        assertEquals("asked of the classpath once", 1, classpath.times("groovy.util.EveryTime"));
        assertEquals(remembered - 1, loader.remembered());
        missing(loader, "Script141$UP");
        assertEquals("forgotten, so asked again", 2, classpath.times("Script141$UP"));
    }

    @Test
    public void anEngineAsksItAndTheThreadKeepsItsContextLoader() throws Exception {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ScriptEngine engine = ScriptClasspath.newEngine(new ScriptEngineManager());
        assertSame(context, Thread.currentThread().getContextClassLoader());
        ClassLoader parent = EngineCleanup.scriptLoader(engine).getParent();
        assertTrue("the engine's loader asks " + parent, parent instanceof ScriptClasspath);
        assertSame(context, parent.getParent());
        assertEquals(42, engine.eval("new java.util.concurrent.atomic.AtomicInteger(42).get()"));
    }

    @Test
    public void anotherClasspathGetsOneOfItsOwn() throws Exception {
        Counted one = new Counted();
        Counted other = new Counted();
        ScriptClasspath forOne = ScriptClasspath.forNextEngine(one);
        missing(forOne, "groovy.util.Missing");
        ScriptClasspath forOther = ScriptClasspath.forNextEngine(other);
        assertNotSame(forOne, forOther);
        assertSame(other, forOther.getParent());
        missing(forOther, "groovy.util.Missing");
        assertEquals(1, other.times("groovy.util.Missing"));
    }

    private static void missing(ClassLoader loader, String name) {
        try {
            loader.loadClass(name);
            fail(name + " was found");
        } catch (ClassNotFoundException expected) {
            // (as it should)
        }
    }
}
