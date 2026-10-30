package models;

import groovy.lang.Script;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.net.URL;
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
 * while the server runs, so a name missing once is missing for good, and asking again is cheap. The
 * scripts' own classes are never asked here: each engine's loader has them, and answers for them
 * before it asks this one.
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
   * engine before last go (each build asks for some made of its scripts' names, Script141$UP, which
   * never come again), the others stay.
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
    if (classes.contains(name)) {
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
    if (resources.contains(name)) {
      return null;
    }
    URL found = classpath.getResource(name);
    if (found == null) {
      resources.add(name);
    }
    return found;
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
