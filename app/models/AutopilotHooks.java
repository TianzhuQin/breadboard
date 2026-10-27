package models;

import groovy.lang.GroovyObject;
import play.Logger;

import javax.script.ScriptEngine;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the console's autopilot settings meet the script engine.
 *
 * <p>{@link #afterStepsLoaded} runs every time an experiment's steps have been loaded into the
 * engine (selecting an experiment or a session, launching, reloading, re-running a step, a file-mode
 * step change), after the steps themselves, so settings saved in the console override what the
 * experiment's own scripts set up (e.g. {@code autopilot.waitingRoom(...)} in groovy/autopilot.groovy).
 * ScriptBoard calls it inside a try/catch that logs, so a failure here never stops a load.
 */
public final class AutopilotHooks {

  private AutopilotHooks() {}

  /**
   * Called right after {@code experiment}'s steps were loaded into {@code engine}. When the autopilot
   * is on for this experiment with settings saved in the console, its waiting room and the takeover
   * of disconnected seats are set up from them (each only when switched on there; otherwise what the
   * experiment's scripts set up stays).
   *
   * @param experiment the experiment whose steps were loaded; null when the engine has none loaded
   * @param engine     the engine they were loaded into
   */
  public static void afterStepsLoaded(Experiment experiment, ScriptEngine engine) {
    if (experiment == null || experiment.id == null || engine == null) {
      return;
    }
    Autopilot.Effective effective = Autopilot.effective();
    // application.conf's autopilot leaves all of this to the experiment's scripts, as it always did.
    if (!Autopilot.ADMIN.equals(effective.source) || !effective.isOn()
        || !experiment.id.equals(effective.settings.experimentId)) {
      return;
    }
    apply(engine, effective.settings);
  }

  /**
   * Call {@code autopilot.waitingRoom([...])} and {@code autopilot.replaceDisconnected([...])} with
   * the saved values. What the console can't set is kept from the experiment's own call, if it made
   * one: the onStart / onCancel callbacks, a custom waiting text and the AI behavior.
   */
  static void apply(ScriptEngine engine, AutopilotSettings settings) {
    Object bound = engine.get("autopilot");
    if (!(bound instanceof GroovyObject)) {
      Logger.warn("Autopilot settings not applied: the engine has no autopilot (groovy/autopilot.groovy)");
      return;
    }
    GroovyObject autopilot = (GroovyObject) bound;
    if (settings.waitingRoomEnabled) {
      autopilot.invokeMethod("waitingRoom", new Object[]{waitingRoomOptions(settings, autopilot.getProperty("room"))});
    }
    if (settings.replaceEnabled) {
      autopilot.invokeMethod("replaceDisconnected", new Object[]{replaceOptions(settings, autopilot.getProperty("replace"))});
    }
  }

  /** The options for autopilot.waitingRoom(...): the saved values over the experiment's own room. */
  static Map<String, Object> waitingRoomOptions(AutopilotSettings settings, Object scriptRoom) {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("minPlayers", settings.minPlayers);
    options.put("maxPlayers", settings.maxPlayers);            // null: no upper limit
    options.put("waitSeconds", settings.waitSeconds);
    options.put("maxWaitSeconds", settings.maxWaitSeconds);    // null: never give up
    options.put("countdownSeconds", settings.countdownSeconds);
    if (scriptRoom instanceof Map) {
      Map<?, ?> room = (Map<?, ?>) scriptRoom;
      options.put("onStart", room.get("onStart"));
      options.put("onCancel", room.get("onCancel"));
      // Only a text the experiment chose itself (null included: "keep the experiment's own text").
      if (Boolean.TRUE.equals(room.get("customText"))) {
        options.put("waitingText", room.get("waitingText"));
      }
    }
    return options;
  }

  /** The options for autopilot.replaceDisconnected(...): the saved values, keeping the AI behavior. */
  static Map<String, Object> replaceOptions(AutopilotSettings settings, Object scriptReplace) {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("afterSeconds", settings.replaceAfterSeconds);
    options.put("returnToHuman", settings.returnToHuman);
    if (scriptReplace instanceof Map) {
      options.put("behavior", ((Map<?, ?>) scriptReplace).get("behavior"));
    }
    return options;
  }
}
