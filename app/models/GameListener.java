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

  /** The step whose start stepStarting marked threw before it got going; see ScriptBoard.stepStartFailed. */
  public void stepStartFailed(Object mark) {
    ScriptBoard.stepStartFailed(mark);
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

  public void finish() {
    if (this.experimentInstance != null && (!this.experimentInstance.isTestInstance())) {
      this.experimentInstance.finish();
      this.experimentInstance.save();
      Autopilot.sessionFinished(this.experimentInstance);
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
