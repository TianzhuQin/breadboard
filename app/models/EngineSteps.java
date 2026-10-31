package models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether the shared script engine runs the steps that are saved now. The engine loads an
 * experiment's steps when it is built (ScriptBoard.loadSteps), and one step's code again when a
 * researcher presses Run on the Steps page; saving a step only changes the database, so a session
 * running in the engine doesn't use what was saved until then. Every console reads this in what the
 * engine runs ({@code engine.stepsChanged}, ScriptBoard.engineJson) and hears it again whenever a step
 * of that experiment is saved or deleted, so every console shows the same "Reload needed", and a
 * refresh keeps it. It used to be a mark kept by the console that saved, alone.
 *
 * <p>Steps are told apart by their id, and what the engine has of each is the code it last ran. A
 * step that was never saved doesn't count, even when it was run. In file mode there is nothing to
 * tell: a step file saved there runs at once while the engine runs its experiment.
 *
 * <p>While a build loads its steps, nothing counts as changed: the engine is loading what is saved.
 * A console asks what the engine runs as soon as the engine says it rebuilt, which is before its
 * steps have loaded, and it used to be told "Reload needed" for steps that nobody had saved. The
 * push that follows every build (ScriptBoard.engineChanged) says what the build loaded.
 */
public final class EngineSteps {

  private static final Object LOCK = new Object();
  // The experiment the engine was last built for, and what it has of each of its saved steps (id ->
  // code). Empty from the start of a build until its steps have loaded.
  private static Long experimentId = null;
  private static final Map<Long, String> loaded = new HashMap<Long, String>();
  // A build's steps are loading: from building() until loaded(), or settled() for a build that
  // stopped before its steps loaded.
  private static boolean loading = false;

  private EngineSteps() {}

  /** The engine is being built for {@code experiment} (null: none): it has none of its steps yet. */
  static void building(Experiment experiment) {
    synchronized (LOCK) {
      experimentId = experiment == null ? null : experiment.id;
      loaded.clear();
      loading = true;
    }
  }

  /** The engine has loaded {@code experiment}'s steps (including those that failed: it ran them). */
  static void loaded(Experiment experiment) {
    if (experiment == null) {
      return;
    }
    Map<Long, String> steps = new HashMap<Long, String>();
    if (!Boolean.TRUE.equals(experiment.fileMode)) {
      for (Step step : experiment.getSteps()) {
        if (step.id != null) {
          steps.put(step.id, step.source);
        }
      }
    }
    synchronized (LOCK) {
      experimentId = experiment.id;
      loaded.clear();
      loaded.putAll(steps);
      loading = false;
    }
  }

  /**
   * No build is under way now: the build failed, or the caller holds the engine's lock, which a
   * build holds from its start until its steps have loaded. A build that stopped before its steps
   * loaded left the engine without them, so they count as changed from now on ("Reload needed").
   */
  static void settled() {
    synchronized (LOCK) {
      loading = false;
    }
  }

  /**
   * A saved step's code ran in the engine without an error (the Steps page's Run), so the engine has
   * that code of it now. Returns whether that changed what the engine has.
   */
  static boolean ran(Long experiment, Long stepId, String source) {
    if (experiment == null || stepId == null || stepId < 0) {
      return false;
    }
    synchronized (LOCK) {
      if (!experiment.equals(experimentId)) {
        return false;
      }
      boolean known = loaded.containsKey(stepId);
      String before = loaded.put(stepId, source);
      return !known || !same(before, source);
    }
  }

  /**
   * Whether {@code experiment}'s saved steps differ from what the engine has of them: a step saved
   * since, a new one, or one deleted (the engine keeps what it defined). False for an experiment the
   * engine wasn't built for, in file mode, and while a build's steps are loading.
   */
  static boolean changed(Experiment experiment) {
    if (experiment == null || experiment.id == null || Boolean.TRUE.equals(experiment.fileMode)) {
      return false;
    }
    Map<Long, String> has;
    synchronized (LOCK) {
      if (!experiment.id.equals(experimentId) || loading) {
        return false;
      }
      has = new HashMap<Long, String>(loaded);
    }
    List<Step> saved = experiment.getSteps();
    if (saved.size() != has.size()) {
      return true;
    }
    for (Step step : saved) {
      if (step.id == null || !has.containsKey(step.id) || !same(has.get(step.id), step.source)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A step of experiment {@code id} was saved or deleted: when the engine runs that experiment, every
   * open console is told what the engine runs again, with whether it runs the saved steps.
   */
  public static void saved(Long id) {
    boolean ours;
    synchronized (LOCK) {
      ours = id != null && id.equals(experimentId);
    }
    if (ours) {
      ScriptBoard.engineChanged();
    }
  }

  /** The experiment a saved step belongs to; null when there is no such step. */
  public static Long experimentOf(Long stepId) {
    return ScriptBoard.experimentOfStep(stepId);
  }

  private static boolean same(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}
