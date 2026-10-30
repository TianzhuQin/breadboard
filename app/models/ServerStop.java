package models;

import play.Logger;

import java.util.LinkedHashMap;

/**
 * What a normal stop of the server (SIGTERM, Ctrl+C: Global.onStop) does to the session the engine
 * runs. Its game lives in the server's memory and ends with it; it used to end without a word. Now
 * the game stops at once, as for a researcher's Stop, and a page that connects from then on is given
 * its end screen (ScriptBoard.stopForServerStop); its data records the stop (a serverStopping event)
 * and its participants' pages say that the session ended because the server was stopped or restarted,
 * and what they can do (ScriptBoard.serverStoppedScreen; a completion code on screen stays), instead
 * of a game that no longer answers. Its status is left to the next start, which marks it stopped
 * (Global.markInterruptedSessions).
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
    // First, so nothing happens in it after the stop is recorded: no page joins it again, no computer
    // player or timer plays it on, nothing more is recorded in it.
    java.util.function.Function<Client, com.fasterxml.jackson.databind.node.ObjectNode> screens =
        ScriptBoard.stopForServerStop(live.id, recordable);
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
      // (not the screen of a researcher's Stop: nobody stopped it)
      ScriptBoard.endParticipants(live.id, screens);
    } catch (Exception e) {
      Logger.warn("Stopping: could not tell the participants of session " + live.id + ": " + e);
    }
    Logger.info("Stopping: session " + live.id + " (\"" + live.name + "\") was running; its participants were told it has ended");
    return live.id;
  }
}
