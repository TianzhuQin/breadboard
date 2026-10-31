package models;

import com.fasterxml.jackson.databind.type.TypeFactory;
import com.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaClassRegistry;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.runtime.metaclass.MetaMethodIndex;
import org.codehaus.groovy.util.AbstractConcurrentMap;
import org.codehaus.groovy.util.AbstractConcurrentMapBase;
import org.codehaus.groovy.util.ManagedReference;
import play.Logger;

import javax.script.ScriptEngine;
import java.beans.Introspector;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lets go of what kept every replaced script engine in memory. Each session runs in an engine of its
 * own (every launch rebuilds it), and each old engine used to stay for good with its graph, its
 * scripts' classes and everything they held: about 3.6 MB of heap and 2 MB of class space per
 * session, until the server did little but collect garbage. Its classes (each engine compiles the
 * scripts and steps again, about 300 classes) then stayed loaded until the class space (metaspace)
 * was full, as Groovy and Java kept them in caches that only a shortage of memory empties. Now each
 * engine's classes are unloaded by the second rebuild after it, as every rebuild collects garbage;
 * when its code ran again after it was dropped (a task of its timers still at work), by the second
 * rebuild after that.
 */
public final class EngineCleanup {

  private EngineCleanup() {}

  /**
   * The class loaders of the engines dropped so far, held weakly: each goes from here when its engine
   * is unloaded. See {@link #forgetClasses}.
   */
  private static final Set<ClassLoader> RELEASED =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>()));

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
      // Java's class loaders keep a lock for each class name made up from its scripts' (Script141$UP,
      // Script141BeanInfo), new with every engine.
      ScriptClasspath.forgetMadeUpNames();
      collectDropped();
    } catch (Exception e) {
      Logger.error("Could not release the replaced script engine; it may stay in memory", e);
    }
  }

  /**
   * Collects garbage, which unloads the engines dropped before this one. On its own the JVM collects
   * in full only now and then: when the old generation fills up, or when the class space reaches a
   * limit that it then raises to 1.67 times what it has committed. So over a long run the class space
   * still climbed towards its cap (512 MB), a few MB a session, before the JVM's own collections
   * brought it down. A dropped engine takes two collections: the first runs the finalizers of its
   * timers (a BBTimer is a java.util.Timer, which lets go of its thread in a finalizer, and that
   * finalizer reaches the engine's classes), the next unloads it. Here, between sessions, at every
   * rebuild, it takes about a tenth of a second, and no more than two dropped engines stay loaded.
   */
  private static void collectDropped() {
    long start = System.nanoTime();
    System.gc();
    Logger.debug("Collected the garbage of dropped engines in " + (System.nanoTime() - start) / 1000000 + " ms");
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
   * <p>The classes themselves go too (about 300 per engine). Caches keep them through soft
   * references, which a full collection clears only once memory runs short, so each engine's classes
   * stayed loaded until the class space was full: Groovy's global set of what it knows about each
   * class (ClassInfo), its compiler's descriptions of the classes scripts use (ClassHelper), Java's
   * descriptions of each class's bean properties (Introspector, which Groovy asks), and the method
   * choices the metaclasses of the classes that outlive the engine remember. The engine's classes
   * leave the first three last, as looking up a class's metaclass adds its entry back.
   *
   * <p>An engine's code can run again after it was dropped: a task of its timers that was at work when
   * they were cancelled carries on. Groovy then puts the classes that code uses back in its caches,
   * and they stayed there, with the whole engine, until memory ran short. So the classes of the
   * engines dropped before go as well, should they be back: their loaders are kept, weakly, in
   * {@link #RELEASED}.
   *
   * @param scripts the loader the dropped engine compiled with: only classes loaded under it or under
   *     the loader of an engine dropped before are touched, never those of an engine in use (the next
   *     one isn't built yet; a test may hold one).
   */
  static void forgetClasses(GroovyClassLoader scripts) {
    Set<ClassLoader> dropped = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    synchronized (RELEASED) {
      RELEASED.add(scripts);
      dropped.addAll(RELEASED);
    }
    List<Object> known = entries(groovyCache(ClassInfo.class.getName(), "globalClassSet"));
    AbstractConcurrentMap<?, ?> classNodes = groovyCache(ClassHelper.class.getName() + "$ClassHelperCache", "classCache");
    Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
    // The ones with a changed metaclass, and those Groovy's caches know: getLoadedClasses no longer
    // lists a class a re-run step has redefined, nor a class Groovy made at run time (a proxy made
    // by "as").
    MetaClassRegistry registry = GroovySystem.getMetaClassRegistry();
    for (Iterator<?> it = registry.iterator(); it.hasNext(); ) {
      Object metaClass = it.next();
      if (metaClass instanceof MetaClass) {
        Class<?> c = ((MetaClass) metaClass).getTheClass();
        if (c != null && loadedUnder(c, dropped)) {
          classes.add(c);
        }
      }
    }
    for (Class<?> c : scripts.getLoadedClasses()) {
      classes.add(c);
    }
    for (Object entry : known) {
      Class<?> c = keyOf(entry);
      if (c != null && loadedUnder(c, dropped)) {
        classes.add(c);
      } else if (c != null && entry instanceof ClassInfo) {
        forgetChoices(((ClassInfo) entry).getStrongMetaClass());
        forgetChoices(((ClassInfo) entry).getWeakMetaClass());
      }
    }
    for (Object entry : entries(classNodes)) {
      Class<?> c = keyOf(entry);
      if (c != null && loadedUnder(c, dropped)) {
        classes.add(c);
      }
    }
    for (Class<?> c : classes) {
      forgetMetaClass(c);
      forgetStaticCaches(c);
    }
    for (Class<?> c : classes) {
      ClassInfo.getClassInfo(c).finalizeRef();
      if (classNodes != null) {
        // The compiler's description of a class a later script used (one of an earlier script's),
        // kept softly with the class under a weak key.
        ((AbstractConcurrentMap<Object, ?>) classNodes).remove(c);
      }
      // (Java keeps one such cache per thread group; the server's threads are all in one.)
      Introspector.flushFromCaches(c);
    }
  }

  /**
   * What {@code registry.removeMetaClass(c)} does, without the event it sends when {@code c} had a
   * metaclass a script changed: the registry's own listener adds an entry for it to a list of the
   * registry's, one that holds no metaclass and so never leaves the list (six or more for every
   * engine released, for good). The event's other work (a flag the class's compiled code checks, a
   * note for Integer and the like) matters only for classes that go on running.
   */
  private static void forgetMetaClass(Class<?> c) {
    ClassInfo info = ClassInfo.getClassInfo(c);
    info.lock();
    try {
      info.setStrongMetaClass(null);
    } finally {
      info.unlock();
    }
  }

  /**
   * Whether {@code c} was loaded by one of {@code loaders} or by a loader below one of them (an
   * engine's loader has one for each script it compiles).
   */
  static boolean loadedUnder(Class<?> c, Set<ClassLoader> loaders) {
    for (ClassLoader l = c.getClassLoader(); l != null; l = l.getParent()) {
      if (loaders.contains(l)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Empties what a compiled Groovy class keeps in static fields of its own: its call-site cache, and
   * the ClassInfo its constructors look up its metaclass in, which no longer holds the class once
   * its entry has left Groovy's set. Should the class run again (a task of the old engine finishing),
   * Groovy makes both anew. The call-site cache is a soft reference, cleared rather than dropped:
   * the class's code reads the field twice, and would fail between the two. Java initializes a class
   * the engine never used before the field is read, so the static initializer of such a class runs
   * here (those of Breadboard's scripts only set up empty maps).
   */
  private static void forgetStaticCaches(Class<?> c) {
    Object callSites = staticField(c, "$callSiteArray");
    if (callSites instanceof Reference) {
      ((Reference<?>) callSites).clear();
    }
    if (staticField(c, "$staticClassInfo") != null) {
      setStaticField(c, "$staticClassInfo", null);
    }
  }

  /** The value of a static field of {@code c}, or null when it has none of that name. */
  private static Object staticField(Class<?> c, String name) {
    try {
      Field field = c.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
    } catch (NoSuchFieldException none) {
      return null; // (not a compiled Groovy class, or one without call sites)
    } catch (Exception | LinkageError e) {
      Logger.debug("Could not read " + name + " of " + c.getName() + ": " + e);
      return null;
    }
  }

  private static void setStaticField(Class<?> c, String name, Object value) {
    try {
      Field field = c.getDeclaredField(name);
      field.setAccessible(true);
      field.set(null, value);
    } catch (Exception | LinkageError e) {
      Logger.debug("Could not empty " + name + " of " + c.getName() + ": " + e);
    }
  }

  /**
   * The metaclass of a class that outlives the engine remembers, for each of its methods, the one it
   * last chose for a call by the types of the call's arguments: closures and objects of the engine
   * among them (graph.groovy's g.empty() hands Gremlin's lists of edges a closure of its own, for
   * one). It chooses again at the next call.
   */
  private static void forgetChoices(MetaClass metaClass) {
    if (metaClass instanceof MetaClassImpl && METHOD_INDEX != null) {
      try {
        ((MetaMethodIndex) METHOD_INDEX.get(metaClass)).clearCaches();
      } catch (Exception e) {
        Logger.debug("Could not empty the method choices of " + metaClass + ": " + e);
      }
    }
  }

  private static final Field METHOD_INDEX = accessible(MetaClassImpl.class, "metaMethodIndex");

  private static Field accessible(Class<?> c, String name) {
    try {
      Field field = c.getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (Exception e) {
      Logger.warn("Could not find " + c.getName() + "." + name
          + "; some classes of replaced engines stay loaded until memory runs short: " + e);
      return null;
    }
  }

  /**
   * One of Groovy's global maps keyed by class: ClassInfo's set of what Groovy knows about each class,
   * or ClassHelper's cache of the compiler's descriptions of classes. Neither can list its keys, nor
   * be reached from outside. Null, with a warning, should another Groovy keep them elsewhere: then
   * some of a replaced engine's classes stay loaded until memory runs short.
   */
  private static AbstractConcurrentMap<?, ?> groovyCache(String className, String field) {
    try {
      Field cache = Class.forName(className).getDeclaredField(field);
      cache.setAccessible(true);
      return (AbstractConcurrentMap<?, ?>) cache.get(null);
    } catch (Exception | LinkageError e) {
      Logger.warn("Could not find Groovy's cache " + className + "." + field
          + "; some classes of replaced engines stay loaded until memory runs short: " + e);
      return null;
    }
  }

  /** The entries of {@code cache} (see {@link #groovyCache}); none when it is null. */
  private static List<Object> entries(AbstractConcurrentMapBase cache) {
    List<Object> found = new ArrayList<Object>();
    if (cache == null) {
      return found;
    }
    try {
      Field segments = AbstractConcurrentMapBase.class.getDeclaredField("segments");
      segments.setAccessible(true);
      Field table = AbstractConcurrentMapBase.Segment.class.getDeclaredField("table");
      table.setAccessible(true);
      for (Object segment : (Object[]) segments.get(cache)) {
        // A slot holds one entry, or an array of those whose hashes share it.
        for (Object slot : (Object[]) table.get(segment)) {
          if (slot instanceof Object[]) {
            for (Object entry : (Object[]) slot) {
              if (entry != null) {
                found.add(entry);
              }
            }
          } else if (slot != null) {
            found.add(slot);
          }
        }
      }
    } catch (Exception | LinkageError e) {
      Logger.warn("Could not read Groovy's cache of classes; some classes of replaced engines stay loaded until memory runs short: " + e);
    }
    return found;
  }

  /** The class an entry of one of Groovy's caches is for, or null (when it has been collected). */
  private static Class<?> keyOf(Object entry) {
    Object key = entry instanceof ManagedReference ? ((ManagedReference<?>) entry).get() : null;
    return key instanceof Class ? (Class<?>) key : null;
  }

  /**
   * The loader {@code engine} compiles its scripts with, or null when it isn't the gremlin-groovy
   * engine. Every class its scripts define is loaded by a child of it (Groovy's InnerLoader, one per
   * script). The library keeps it in a protected field and has no getter.
   */
  public static GroovyClassLoader scriptLoader(ScriptEngine engine) {
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
