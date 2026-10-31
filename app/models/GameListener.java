package models;

import javax.script.ScriptEngine;

public class GameListener {

  public ExperimentInstance experimentInstance;
  public User user;
  public ScriptEngine engine;
  public ThrottledWebSocketOut out;

  public GameListener() {
  }

  public void hasStarted() {
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.setHasStarted(Boolean.TRUE);
      this.experimentInstance.save();
    }
  }

  /**
   * As hasStarted(), for a start of the game (initStep.start()): returns the session's hasStarted from
   * before, which startFailed puts back if the start throws.
   */
  public Boolean closeToNewcomers() {
    Boolean before = this.experimentInstance == null ? null : this.experimentInstance.hasStarted;
    hasStarted();
    return before;
  }

  /**
   * The start that closed the session to newcomers threw: its hasStarted goes back to {@code before}
   * and is saved, so a game that never began doesn't turn away the people it is waiting for.
   */
  public void startFailed(Boolean before) {
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.setHasStarted(before);
      this.experimentInstance.save();
    }
  }

  /**
   * Called by every step as it starts (groovy/step.groovy); see ScriptBoard.stepStarting. Returns what
   * it marked (null for nothing), for stepStartFailed.
   */
  public Object stepStarting(Object step) {
    return ScriptBoard.stepStarting(this, step);
  }

  /**
   * The step got going: its start() is returning without having thrown (groovy/step.groovy); see
   * ScriptBoard.stepStarted.
   */
  public void stepStarted(Object step) {
    ScriptBoard.stepStarted(step);
  }

  /** How many steps have got going on this thread so far (stepStarted), for stepFailed. */
  public Long stepsStarted() {
    return ScriptBoard.stepsStarted();
  }

  /** The step whose start stepStarting marked threw before it got going; see ScriptBoard.stepStartFailed. */
  public void stepStartFailed(Object mark) {
    ScriptBoard.stepStartFailed(mark);
  }

  /**
   * A step's own code threw (groovy/step.groovy); see ScriptBoard.stepFailed. {@code going} is what
   * stepsStarted() answered as that code began (null when not known). True when it has been reported,
   * false when the step throws it on to the script the server is running.
   */
  public boolean stepFailed(Object step, Throwable failure, Long going) {
    return ScriptBoard.stepFailed(this, step, failure, going);
  }

  /**
   * Whether the step (groovy/step.groovy) is of the engine running now (ScriptBoard.ofRunningEngine):
   * not when its engine has been replaced since and its code ran on. An actor gives every engine it
   * builds this same listener, so such a step's finish() would finish the session running now. A
   * listener of no server engine (a test's) has only its own.
   */
  public boolean ofRunningEngine(Object step) {
    return this.engine == null || ScriptBoard.ofRunningEngine(step);
  }

  /** The autopilot's start of the game failed (groovy/autopilot.groovy); see ScriptBoard.gameStartFailed. */
  public void gameStartFailed(String error) {
    ScriptBoard.gameStartFailed(this, error);
  }

  public void start() {
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.start();
      this.experimentInstance.save();
    }
  }

  public void stop() {
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.stop();
      this.experimentInstance.save();
    }
  }

  /**
   * The game came to its end (a step's finish(), a waiting room giving up): its session is marked
   * finished, and the autopilot and the consoles hear of it. Not by a game whose engine a rebuild is
   * replacing (its waiting room giving up as the engine was rebuilt finished the session the new one
   * carries on), nor in a session that was stopped: its game played on to its end by code that
   * outlived the Stop (a participant's choice made as it ran, a task of its timers) finished it, so it
   * could no longer be resumed.
   */
  public void finish() {
    if (this.engine != null && !ScriptBoard.inService(this.engine)) {
      play.Logger.info("The game of an engine that is being replaced came to its end; its session goes on in the new one");
      return;
    }
    if (this.experimentInstance != null && this.experimentInstance.status == ExperimentInstance.Status.STOPPED) {
      play.Logger.info("The game of session " + this.experimentInstance.id + ", which was stopped, came to its end; it stays stopped");
      return;
    }
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.finish();
      this.experimentInstance.save();
      Autopilot.sessionFinished(this.experimentInstance);
      // Every console hears that the engine runs no session now (another account's, which may be
      // following this one, too).
      ScriptBoard.engineChanged();
    }
    if (user != null && engine != null) {
      // Every console tab of this account learns the session finished. The tab that last sent this
      // account an action only gets it when no tab is registered (e.g. the autopilot's output, which
      // then writes it to the log).
      User fresh = User.findByEmail(user.email);
      com.fasterxml.jackson.databind.node.ObjectNode json = (fresh != null ? fresh : user).toJson();
      if (!ScriptBoard.toAccount(user.email, json) && this.out != null) {
        this.out.write(json);
      }
    }
  }

}
