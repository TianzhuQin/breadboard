package models;

import org.junit.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * ScriptClasspath, the class loader every script engine's class loader asks for what isn't a class of
 * its scripts: it remembers the names the server's classpath hasn't got, so that Groovy's compiler
 * no longer reads every jar for each of its misses at every rebuild, and never asks the classpath for
 * names made up from the scripts' own.
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
        // A name only one engine asked for (a step that has changed since, say) doesn't stay for good.
        // The names every build asks for stay.
        Counted classpath = new Counted();
        ScriptClasspath loader = ScriptClasspath.forNextEngine(classpath);
        missing(loader, "groovy.util.OnlyOnce");
        missing(loader, "groovy.util.EveryTime");
        int remembered = loader.remembered();
        for (int engine = 2; engine <= 4; engine++) {
            assertSame("the same one serves every engine", loader, ScriptClasspath.forNextEngine(classpath));
            missing(loader, "groovy.util.EveryTime");
        }
        assertEquals("asked of the classpath once", 1, classpath.times("groovy.util.EveryTime"));
        assertEquals(remembered - 1, loader.remembered());
        missing(loader, "groovy.util.OnlyOnce");
        assertEquals("forgotten, so asked again", 2, classpath.times("groovy.util.OnlyOnce"));
    }

    @Test
    public void namesMadeUpFromAScriptsNameAreNeverAskedOfTheClasspath() throws Exception {
        // New with every engine (the numbers go on), and Java's class loaders keep a lock for every name
        // they are asked for, for good.
        Counted classpath = new Counted();
        ScriptClasspath loader = ScriptClasspath.forNextEngine(classpath);
        classpath.asked.clear(); // (whether it has Groovy's own classes)
        for (String name : new String[]{"Script141", "Script141$UP", "Script141$_run_closure2",
            "com.tinkerpop.gremlin.Script141$UP", "static com.tinkerpop.blueprints.Direction.Script141$UP",
            "Script141BeanInfo", "Script141Customizer", "groovy.runtime.metaclass.Script141MetaClass"}) {
            missing(loader, name);
        }
        for (String resource : new String[]{"Script141.groovy", "Script141$UP.groovy",
            "com/tinkerpop/gremlin/Script141$UP.groovy", "Script141BeanInfo.groovy"}) {
            assertNull(resource, loader.getResource(resource));
        }
        assertEquals(Collections.emptyList(), classpath.asked);
        assertEquals("nor remembered", 0, loader.remembered());
        // Names that only look like those are asked as usual.
        assertSame(ScriptBoard.class, loader.loadClass("models.ScriptBoard"));
        assertSame(groovy.lang.Script.class, loader.loadClass("groovy.lang.Script"));
        missing(loader, "Script141Helper");
        missing(loader, "groovy.util.Scripts");
        missing(loader, "ScriptX$UP");
        assertNull(loader.getResource("Script141Helper.groovy"));
        assertEquals(Arrays.asList("models.ScriptBoard", "groovy.lang.Script", "Script141Helper", "groovy.util.Scripts",
            "ScriptX$UP", "Script141Helper.groovy"), classpath.asked);
    }

    @Test
    public void theServersLoaderLetsGoOfItsLocksForNamesMadeUpFromAScripts() throws Exception {
        // It keeps a lock object for every name it is asked, for good; Java's search for a class's
        // BeanInfo asks it directly.
        ClassLoader server = ScriptClasspathTest.class.getClassLoader();
        for (String name : new String[]{"Script9141BeanInfo", "Script9141$_run_closure1Customizer",
            "groovy.runtime.metaclass.Script9141MetaClass", "groovy.util.NoSuchThing9141"}) {
            try {
                Class.forName(name, false, server);
                fail(name + " was found");
            } catch (ClassNotFoundException expected) {
                // (as it should)
            }
        }
        assertTrue(locks(server).containsKey("Script9141BeanInfo"));
        ScriptClasspath.forgetMadeUpNames();
        for (ClassLoader l = server; l != null; l = l.getParent()) {
            Map<String, Object> locks = locks(l);
            if (locks == null) continue;
            assertFalse(locks.containsKey("Script9141BeanInfo"));
            assertFalse(locks.containsKey("Script9141$_run_closure1Customizer"));
            assertFalse(locks.containsKey("groovy.runtime.metaclass.Script9141MetaClass"));
        }
        assertTrue("other names keep theirs", locks(server).containsKey("groovy.util.NoSuchThing9141"));
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

    /** The lock object a class loader keeps per class name (ClassLoader.parallelLockMap), or null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> locks(ClassLoader loader) throws Exception {
        Field locks = ClassLoader.class.getDeclaredField("parallelLockMap");
        locks.setAccessible(true);
        return (Map<String, Object>) locks.get(loader);
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
