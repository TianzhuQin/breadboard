package models;

import play.Logger;

import java.util.LinkedHashMap;

/**
 * What a normal stop of the server (SIGTERM, Ctrl+C: Global.onStop) does to the session the engine
 * runs. Its game lives in the server's memory and ends with it; it used to end without a word. Now
 * its data records the stop (a serverStopping event) and its participants' pages say that the
 * session has stopped, instead of a game that no longer answers. Its status is left to the next
 * start, which marks it stopped (Global.markInterruptedSessions).
 */
public final class ServerStop {

  private ServerStop() {}

  /** The id of the session that was ended, or null when the engine ran none. Never throws. */
  public static Long endLiveSession() {
    ExperimentInstance live;
    boolean recordable = true;
    try {
      live = Autopilot.liveEngineSession();
    } catch (Exception e) {
      // The database doesn't answer (it may be closed already): the participants are told all the
      // same, going by the engine's own copy of its session.
      Logger.warn("Stopping: the database didn't say which session runs (" + e.getMessage() + ")");
      live = ScriptBoard.engineSession();
      if (live != null && live.status != ExperimentInstance.Status.RUNNING && live.status != ExperimentInstance.Status.TESTING) {
        live = null;
      }
      recordable = false;
    }
    if (live == null || live.id == null) {
      return null;
    }
    // (A researcher's test session is not in the database: nothing to record there.)
    if (recordable && !live.isTestInstance()) {
      // Its own tracker: the engine's may be turned off, or point elsewhere, after a researcher's change.
      EventTracker tracker = new EventTracker();
      tracker.setExperimentInstance(live);
      LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
      data.put("reason", "The server was stopped while this session ran.");
      tracker.track("serverStopping", data);
    }
    try {
      ScriptBoard.endParticipants(live.id);
    } catch (Exception e) {
      Logger.warn("Stopping: could not tell the participants of session " + live.id + ": " + e);
    }
    Logger.info("Stopping: session " + live.id + " (\"" + live.name + "\") was running; its participants were told it has stopped");
    return live.id;
  }
}
