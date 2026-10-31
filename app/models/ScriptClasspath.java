package models;

import groovy.lang.Script;
import play.Logger;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's classpath as every script engine sees it: the parent of each engine's class loader,
 * which passes what the engine asks for on to the server's own class loader and remembers the names
 * that aren't there.
 *
 * <p>Groovy's compiler tries each name a script uses in every package the scripts import (about 50:
 * java.lang, java.util, groovy.lang, Gremlin's and Blueprints'), and when a name isn't a class it
 * looks for a script of that name. Breadboard's own scripts make it ask for about 17,700 classes and
 * 6,700 resources that don't exist, and each miss looked in every jar (about 110 in a release):
 * seconds of every engine rebuild, that is of every session's start. The classpath doesn't change
 * while the server runs, so a name missing once is missing for good, and asking again is cheap. A
 * script's own class can be asked for here before a script defines it, and is then remembered as
 * missing. That does no harm: once it is defined, the engine's own loader answers for it before it
 * asks this one.
 *
 * <p>Some names are new with every engine: those made up from the names the engine gives its
 * scripts (see {@link #madeUpForAScript}). They are never on the classpath, so they aren't asked
 * there. Java's class loaders keep a lock object for every class name they are asked for, found or
 * not, for good, and Java and Groovy still ask the server's loader for some of those names directly
 * (a class's BeanInfo, its custom metaclass): {@link #forgetMadeUpNames} lets go of their locks when
 * an engine is dropped. Each engine used to leave about 250 such names behind, for good.
 */
public final class ScriptClasspath extends ClassLoader {

  /** A safety bound on the names kept from one engine to the next (a build asks for about 15,000). */
  static final int MAX_NAMES = 100000;

  private static ScriptClasspath shared;

  private final ClassLoader classpath;
  private final Misses classes = new Misses();
  private final Misses resources = new Misses();

  private ScriptClasspath(ClassLoader classpath) {
    super(classpath);
    this.classpath = classpath;
  }

  /**
   * A new gremlin-groovy engine from {@code manager}, whose class loader asks this one. The engine
   * makes its loader's parent the thread's context class loader when that one has Groovy's own
   * classes, so this one is the context loader while the engine is made.
   */
  public static ScriptEngine newEngine(ScriptEngineManager manager) {
    Thread thread = Thread.currentThread();
    ClassLoader context = thread.getContextClassLoader();
    thread.setContextClassLoader(forNextEngine(context));
    try {
      return manager.getEngineByName("gremlin-groovy");
    } finally {
      thread.setContextClassLoader(context);
    }
  }

  /**
   * The one an engine made now, on a thread with {@code context} as its context class loader, asks.
   * It is in front of the loader the engine would ask otherwise: {@code context} when that one has
   * Groovy's own classes, else Groovy's. The same one serves every engine while that loader stays,
   * and each engine made starts a new generation of what it remembers: names asked for only by the
   * engine before last go, the others stay.
   */
  static synchronized ScriptClasspath forNextEngine(ClassLoader context) {
    ClassLoader classpath = Script.class.getClassLoader();
    try {
      if (context != null && context.loadClass(Script.class.getName()) == Script.class) {
        classpath = context;
      }
    } catch (ClassNotFoundException e) {
      // (Groovy's own loader, as the engine would take)
    }
    if (shared == null || shared.classpath != classpath) {
      shared = new ScriptClasspath(classpath);
    } else {
      shared.classes.newGeneration();
      shared.resources.newGeneration();
    }
    return shared;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (madeUpForAScript(simpleName(name)) || classes.contains(name)) {
      throw new ClassNotFoundException(name);
    }
    try {
      return classpath.loadClass(name);
    } catch (ClassNotFoundException e) {
      classes.add(name);
      throw e;
    }
  }

  @Override
  public URL getResource(String name) {
    String file = name.substring(name.lastIndexOf('/') + 1);
    int extension = file.lastIndexOf('.');
    if (madeUpForAScript(extension > 0 ? file.substring(0, extension) : file) || resources.contains(name)) {
      return null;
    }
    URL found = classpath.getResource(name);
    if (found == null) {
      resources.add(name);
    }
    return found;
  }

  /**
   * Whether {@code simpleName} (a class's name without its package) is one made up from the name the
   * engine gave a script, Script141: the script's class itself, one inside it (Script141$UP, which
   * the compiler tries for every name the script uses, in every package it imports, and the
   * script's closures), or what Java and Groovy look for beside each class (Script141BeanInfo,
   * Script141Customizer, Script141MetaClass). The numbers go on from engine to engine.
   */
  static boolean madeUpForAScript(String simpleName) {
    if (!simpleName.startsWith("Script")) {
      return false;
    }
    int digits = "Script".length();
    int end = digits;
    while (end < simpleName.length() && simpleName.charAt(end) >= '0' && simpleName.charAt(end) <= '9') {
      end++;
    }
    if (end == digits) {
      return false;
    }
    String rest = simpleName.substring(end);
    return rest.isEmpty() || rest.charAt(0) == '$'
        || rest.equals("BeanInfo") || rest.equals("Customizer") || rest.equals("MetaClass");
  }

  /**
   * Lets go of the lock objects the server's class loaders (and Java's above them) keep for names
   * made up from the scripts' (see {@link #madeUpForAScript}). Those that load classes in parallel
   * keep one for every name they are asked for, found or not, for good: most of these names no longer
   * reach them, but Java looks for each class's BeanInfo and Customizer through the thread's loader
   * too, and Groovy for its custom metaclass through its own. No class of such a name is ever
   * defined there, so its lock guards nothing. Called when an engine is dropped; never throws.
   */
  static void forgetMadeUpNames() {
    if (LOCKS == null) {
      return;
    }
    ScriptClasspath current;
    synchronized (ScriptClasspath.class) {
      current = shared;
    }
    Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
    for (ClassLoader first : new ClassLoader[]{current == null ? null : current.classpath,
        Script.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
      for (ClassLoader l = first; l != null && loaders.add(l); l = l.getParent()) {
        try {
          Map<?, ?> locks = (Map<?, ?>) LOCKS.get(l);
          if (locks == null) {
            continue; // (not one that loads in parallel: it locks itself)
          }
          for (Iterator<?> it = locks.keySet().iterator(); it.hasNext(); ) {
            Object name = it.next();
            if (name instanceof String && madeUpForAScript(simpleName((String) name))) {
              it.remove();
            }
          }
        } catch (Exception e) {
          Logger.debug("Could not read the class loading locks of " + l + ": " + e);
        }
      }
    }
  }

  /** ClassLoader's map of a lock object per class name, or null on a Java that has none we can reach. */
  private static final Field LOCKS = locks();

  private static Field locks() {
    try {
      Field locks = ClassLoader.class.getDeclaredField("parallelLockMap");
      locks.setAccessible(true);
      return locks;
    } catch (Exception | LinkageError e) {
      return null;
    }
  }

  private static String simpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  /** How many class and resource names it remembers now (for the tests). */
  int remembered() {
    return classes.size() + resources.size();
  }

  /**
   * Names the classpath hasn't got: those asked for since the last engine was made, and those asked
   * for before that and not since (which go when the next one is made).
   */
  private static final class Misses {
    private volatile Set<String> recent = ConcurrentHashMap.newKeySet();
    private volatile Set<String> earlier = ConcurrentHashMap.newKeySet();

    boolean contains(String name) {
      if (recent.contains(name)) {
        return true;
      }
      if (earlier.remove(name)) {
        recent.add(name);
        return true;
      }
      return false;
    }

    void add(String name) {
      recent.add(name);
      if (recent.size() > MAX_NAMES) {
        newGeneration();
      }
    }

    void newGeneration() {
      earlier = recent;
      recent = ConcurrentHashMap.newKeySet();
    }

    int size() {
      return recent.size() + earlier.size();
    }
  }
}
