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
