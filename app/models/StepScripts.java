package models;

import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import play.Logger;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of the experiment's steps each script an engine compiled is the code of.
 *
 * <p>Groovy names every script an engine compiles "Script&lt;N&gt;" (its closures
 * "Script&lt;N&gt;$_run_closure&lt;M&gt;", all in "Script&lt;N&gt;.groovy"), numbered across the
 * server, and that is all the lines of an engine error say of where they are. The console used to
 * guess which step a Script&lt;N&gt; was from its number: the newest script, or the innermost one
 * for a step that failed. Neither holds once a step's code calls a function another step defined
 * (the network game's RoundStep calls InitStep's isHuman): the line was another step's, named after
 * the failing one. The server knows: it compiles each step's code itself, as the steps load
 * (ScriptBoard.loadSteps), as a step is run again from the Steps page, and as a step file of file mode
 * is saved, and records here which class that compiled to, under the step's name. Kept per engine,
 * and dropped with it.
 */
public final class StepScripts {

  private StepScripts() {}

  // engine -> (script class, e.g. "Script649" -> step, e.g. "RoundStep"). Weak: an engine that is
  // dropped takes its own with it (the values hold only names).
  private static final Map<ScriptEngine, Map<String, String>> BY_ENGINE =
      Collections.synchronizedMap(new WeakHashMap<ScriptEngine, Map<String, String>>());

  /** "Script649", also from "Script649.groovy" or a closure's class, "Script649$_run_closure2". */
  private static final Pattern SCRIPT = Pattern.compile("^(Script\\d+)(?:\\.groovy|\\$.*)?$");

  /**
   * Compiles {@code source} in {@code engine}, as the code of the step {@code step}, and records the
   * class it compiled to. The engine keeps the class of each script it compiled, by its text, so the
   * eval of the same text that follows runs that class. Returns its name ("Script649"), or null when
   * it doesn't compile (the eval then says why) or can't be told. Never throws.
   */
  static String compiled(ScriptEngine engine, String source, String step) {
    if (engine == null || source == null || step == null || step.trim().isEmpty()) {
      return null;
    }
    String name = classOf(engine, source);
    if (name != null) {
      forEngine(engine).put(name, step.trim());
    }
    return name;
  }

  /** The class {@code engine} compiles {@code source} to (compiling it now if it hasn't), or null. */
  static String classOf(ScriptEngine engine, String source) {
    if (!(engine instanceof Compilable)) {
      return null;
    }
    try {
      CompiledScript compiled = ((Compilable) engine).compile(source);
      // (Groovy's GroovyCompiledScript keeps the class in a field of its own, and says nothing of it)
      for (Field field : compiled.getClass().getDeclaredFields()) {
        if (field.getType() == Class.class) {
          field.setAccessible(true);
          Object type = field.get(compiled);
          return type instanceof Class ? ((Class<?>) type).getName() : null;
        }
      }
      Logger.warn("Can't tell which class a step's code compiled to: the lines of its errors won't name it");
    } catch (ScriptException doesNotCompile) {
      // (the eval that follows says what is wrong with it)
    } catch (Exception | LinkageError e) {
      Logger.warn("Can't tell which class a step's code compiled to: the lines of its errors won't name it (" + e + ")");
    }
    return null;
  }

  private static Map<String, String> forEngine(ScriptEngine engine) {
    synchronized (BY_ENGINE) {
      Map<String, String> classes = BY_ENGINE.get(engine);
      if (classes == null) {
        classes = new ConcurrentHashMap<String, String>();
        BY_ENGINE.put(engine, classes);
      }
      return classes;
    }
  }

  /**
   * The step whose code {@code script} is ("Script649", "Script649.groovy" or one of its closures'
   * classes), in {@code engine}; else in another engine that is still in memory (a step of an engine
   * replaced since can still fail, a task of its timers under way); null when it is no step's code: a
   * script typed in the Script console, one the server ran (the start of the game), or Breadboard's own.
   */
  public static String stepOf(ScriptEngine engine, String script) {
    String key = scriptOf(script);
    if (key == null) {
      return null;
    }
    if (engine != null) {
      Map<String, String> own;
      synchronized (BY_ENGINE) {
        own = BY_ENGINE.get(engine);
      }
      String step = own == null ? null : own.get(key);
      if (step != null) {
        return step;
      }
    }
    List<Map<String, String>> all;
    synchronized (BY_ENGINE) {
      all = new ArrayList<Map<String, String>>(BY_ENGINE.values());
    }
    for (Map<String, String> classes : all) {
      String step = classes.get(key);
      if (step != null) {
        return step;
      }
    }
    return null;
  }

  /** "Script649" of a script's class, a closure's class or the file name of its lines; null for anything else. */
  static String scriptOf(String name) {
    if (name == null) {
      return null;
    }
    Matcher m = SCRIPT.matcher(name);
    return m.matches() ? m.group(1) : null;
  }

  /**
   * The steps whose code a step object's own code (run, done, onTimeout) is, by the classes of those
   * closures: the code the step is, which its failure is in. FinishStep's, for the network game's
   * finishStep, also when RoundStep started it. Empty when none of it is a step's code (a step made in
   * the Script console). Never throws.
   */
  static Set<String> stepsOfCode(ScriptEngine engine, Object step) {
    Set<String> steps = new LinkedHashSet<String>();
    if (!(step instanceof GroovyObject)) {
      return steps;
    }
    for (String part : new String[]{"run", "done", "onTimeout"}) {
      try {
        Object code = ((GroovyObject) step).getProperty(part);
        if (!(code instanceof Closure)) {
          continue;
        }
        String of = stepOf(engine, code.getClass().getName());
        if (of == null && ((Closure) code).getOwner() != null) {
          // (a method closure, this.&round: its script is its owner)
          of = stepOf(engine, ((Closure) code).getOwner().getClass().getName());
        }
        if (of != null) {
          steps.add(of);
        }
      } catch (Exception e) {
        // (the experiment's code may have given the step something odd) not its code then
      }
    }
    return steps;
  }

  /**
   * The name a line of a script's code is in, as the experiment's code calls it: a method of the script
   * ({@code def isHuman(v) { ... }}), or the variable that holds a closure ({@code isHuman = { v -> ... }},
   * found among the engine's variables). Null when it has none (the script's own code, a closure a step
   * holds as its run or done), or it can't be told. Never throws.
   */
  static String functionName(ScriptEngine engine, String className, String method) {
    if (className == null) {
      return null;
    }
    if (!className.contains("$")) {
      // (not the script's own code, run, nor what Groovy makes of it: $getStaticMetaClass, <init> …)
      return method == null || "run".equals(method) || "main".equals(method) || method.contains("$") || method.contains("<")
          ? null : method;
    }
    if (engine == null) {
      return null;
    }
    try {
      Bindings variables = engine.getBindings(ScriptContext.ENGINE_SCOPE);
      for (Map.Entry<String, Object> variable : new ArrayList<Map.Entry<String, Object>>(variables.entrySet())) {
        Object value = variable.getValue();
        if (value instanceof Closure && value.getClass().getName().equals(className)) {
          return variable.getKey();
        }
      }
    } catch (Exception e) {
      // (the experiment's code may change its variables while they are read) no name then
    }
    return null;
  }
}
