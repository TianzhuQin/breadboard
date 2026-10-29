package models;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassRegistry;
import play.Logger;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lets go of what kept every replaced script engine in memory. Each session runs in an engine of its
 * own (every launch rebuilds it), and each old engine used to stay for good with its graph, its
 * scripts' classes and everything they held: about 3.6 MB of heap and 2 MB of class space per
 * session, until the server did little but collect garbage.
 */
public final class EngineCleanup {

  private EngineCleanup() {}

  /**
   * Called when {@code old} is dropped: after its timers are cancelled, before the next engine is
   * built. Never throws: the rebuild goes on whatever happens here.
   */
  public static void release(ScriptEngine old) {
    try {
      GroovyClassLoader scripts = scriptLoader(old);
      if (scripts != null) {
        forgetClasses(scripts);
      }
      // Jackson keeps a serializer for every class it has written, the old engine's included (a
      // player's property can hold an object of an experiment's own class): a new mapper has none.
      // The type factory, which every mapper shares, keeps those classes too.
      JsonSetup.install();
      TypeFactory.defaultInstance().clearCache();
    } catch (Exception e) {
      Logger.error("Could not release the replaced script engine; it may stay in memory", e);
    }
  }

  /**
   * Groovy keeps, for each class it runs, a metaclass and a cache of the methods its call sites last
   * called. For the classes of the dropped engine (its scripts' and the experiment's steps') these
   * hold closures of that engine, and through them all of it:
   * <ul>
   *   <li>a metaclass a script changed (BBTimer.metaClass.register = ..., an experiment's steps may
   *       do the same) for good, in a global set;</li>
   *   <li>the other metaclasses (with the methods they inherit from a changed one) and the call-site
   *       caches for as long as there is memory to spare.</li>
   * </ul>
   * Those classes die with the engine, so all of that goes now. Classes that outlive it
   * (Breadboard's and Java's, e.g. Vertex.metaClass.on) keep theirs, which the next engine's scripts
   * set again.
   *
   * @param scripts the loader the dropped engine compiled with: only classes loaded under it are
   *     touched, never those of another engine (the next one isn't built yet; a test may hold one).
   */
  static void forgetClasses(GroovyClassLoader scripts) {
    MetaClassRegistry registry = GroovySystem.getMetaClassRegistry();
    // The changed ones first: getLoadedClasses no longer lists a class a re-run step has redefined.
    List<Class<?>> changed = new ArrayList<Class<?>>();
    for (Iterator<?> it = registry.iterator(); it.hasNext(); ) {
      Object metaClass = it.next();
      if (metaClass instanceof MetaClass) {
        Class<?> c = ((MetaClass) metaClass).getTheClass();
        if (c != null && loadedUnder(c, scripts)) {
          changed.add(c);
        }
      }
    }
    for (Class<?> c : changed) {
      registry.removeMetaClass(c);
    }
    for (Class<?> c : scripts.getLoadedClasses()) {
      registry.removeMetaClass(c);
      forgetCallSites(c);
    }
  }

  /** Whether {@code c} was loaded by {@code loader} or a loader below it (one per compiled script). */
  static boolean loadedUnder(Class<?> c, ClassLoader loader) {
    for (ClassLoader l = c.getClassLoader(); l != null; l = l.getParent()) {
      if (l == loader) {
        return true;
      }
    }
    return false;
  }

  /**
   * Empties a compiled Groovy class's call-site cache, the static field Groovy adds to it. Should
   * the class run again (a task of the old engine finishing), Groovy builds a new one. Java
   * initializes a class the engine never used before the field is written, so the static
   * initializer of such a class runs here (those of Breadboard's scripts only set up empty maps).
   */
  private static void forgetCallSites(Class<?> c) {
    try {
      Field callSites = c.getDeclaredField("$callSiteArray");
      callSites.setAccessible(true);
      callSites.set(null, null);
    } catch (NoSuchFieldException none) {
      // (a class without call sites)
    } catch (Exception | LinkageError e) {
      Logger.debug("Could not empty the call-site cache of " + c.getName() + ": " + e);
    }
  }

  /**
   * The loader {@code engine} compiles its scripts with, or null when it isn't the gremlin-groovy
   * engine. Every class its scripts define is loaded by a child of it (Groovy's InnerLoader, one per
   * script). The library keeps it in a protected field and has no getter.
   */
  static GroovyClassLoader scriptLoader(ScriptEngine engine) {
    if (!(engine instanceof GremlinGroovyScriptEngine)) {
      return null;
    }
    try {
      Field loader = GremlinGroovyScriptEngine.class.getDeclaredField("loader");
      loader.setAccessible(true);
      return (GroovyClassLoader) loader.get(engine);
    } catch (Exception e) {
      Logger.warn("Could not find the script engine's class loader; its classes stay in Groovy's caches: " + e);
      return null;
    }
  }
}
