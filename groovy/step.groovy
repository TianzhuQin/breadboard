import java.beans.PropertyChangeListener
import com.fasterxml.jackson.annotation.JsonIgnore

class Step {
  @JsonIgnore
  PlayerActions playerActions
  String name
  @JsonIgnore
  boolean ignoreUserAction = false

  Closure run = { println "run" }
  Closure done = {
    println "done"
    //assume this is the last step since no chain step
    finish()
  }
  @JsonIgnore
  def gameListener
  def params = [:]
  @JsonIgnore
  def eventTracker
  private def emptyListener

  // Optional time limit in seconds. With a duration the step ends when every pending choice has
  // been made or when time runs out, whichever comes first, and a step that offered no choices
  // waits for the whole duration. Without one, behaviour is unchanged: the step ends as soon as no
  // choice is pending anywhere, which is immediately if run() offered none.
  Number duration = null
  // With a duration: keep waiting for the full time even after every choice has been made.
  boolean waitForDuration = false
  // With a duration: what to do when time runs out, called with the step's player argument before
  // done(). By default the choices still on screen are withdrawn.
  @JsonIgnore
  Closure onTimeout = null
  boolean clearChoicesOnTimeout = true
  @JsonIgnore
  private BBTimer durationTimer
  @JsonIgnore
  private java.util.concurrent.atomic.AtomicBoolean ended

  def propertyMissing(String name, value) {
    params[name] = value
  }

  def propertyMissing(String name) {
    return params[name]
  }

  def start(String player = null) {

    // The server learns when the init step starts, however it is started, and takes it back if the
    // step throws before it gets going (see ScriptBoard.stepStarting).
    def startMark = (gameListener instanceof models.GameListener) ? gameListener.stepStarting(this) : null

    // Record the start time of each step.
    if (name != null) {
      eventTracker.track(name + "Start", ["startStep": name])
    }

    ended = new java.util.concurrent.atomic.AtomicBoolean(false)
    try {
      run(player)
    } catch (Throwable t) {
      if (startMark != null) {
        gameListener.stepStartFailed(startMark)
      }
      reportFailure(t)
      return
    }

    boolean hasUserActions = !playerActions.actions.isEmpty()

    if (duration != null && duration > 0) {
      startTimed(player, hasUserActions)
      return
    }

    if (hasUserActions) {
      //println("playerActions.actions.size() = " + playerActions.actions.size())

      if (!ignoreUserAction) {
        //if has user actions, the done should be triggered after all the actions are completed
        emptyListener = { event ->
          if (event instanceof ObservableMap.PropertyRemovedEvent) {
            //println("playerActions.actions.size() = " + playerActions.actions.size())
            if (playerActions.actions.isEmpty()) {
              playerActions.removeActionPropertyChangeListener(emptyListener)
              callDone(player)
            }
          }
        } as PropertyChangeListener

        playerActions.addActionPropertyChangeListener(emptyListener)
      } else {
        callDone(player)
      }
    } else {
      callDone(player)
    }
  }

  // done(player), and what it returns; what it throws goes to reportFailure.
  private def callDone(String player) {
    try {
      return done(player)
    } catch (Throwable t) {
      reportFailure(t)
      return null
    }
  }

  // The step's own code (run, done, onTimeout) threw. On a timer, a participant's choice or a
  // computer player's, that used to end the thread, with the error only in the server's output, and
  // the game waited for good: the server reports it now (ScriptBoard.stepFailed). Inside a script
  // the server runs (the console's Start, the autopilot's start, a step run from the Steps page) it
  // is thrown on to that script, which reports it, as before.
  private void reportFailure(Throwable t) {
    if (!(gameListener instanceof models.GameListener) || !gameListener.stepFailed(this, t)) {
      throw t
    }
  }

  private void startTimed(String player, boolean hasUserActions) {
    if (hasUserActions && !ignoreUserAction && !waitForDuration) {
      emptyListener = { event ->
        if (event instanceof ObservableMap.PropertyRemovedEvent && playerActions.actions.isEmpty()) {
          endOnce(player, false)
        }
      } as PropertyChangeListener
      playerActions.addActionPropertyChangeListener(emptyListener)
    }
    durationTimer = new BBTimer()
    durationTimer.runAfter((int) Math.round(duration * 1000)) {
      endOnce(player, true)
    }
  }

  // Ends a timed step exactly once, whichever of "all chosen" and "time is up" happens first. It
  // runs on the step's own timer or a participant's choice: what it throws goes to reportFailure.
  private void endOnce(String player, boolean timedOut) {
    if (!ended.compareAndSet(false, true)) {
      return
    }
    try {
      if (emptyListener != null) {
        playerActions.removeActionPropertyChangeListener(emptyListener)
        emptyListener = null
      }
      if (durationTimer != null) {
        durationTimer.cancel()
        durationTimer = null
      }
      if (timedOut) {
        if (name != null) {
          eventTracker.track(name + "Timeout", ["step": name])
        }
        if (onTimeout != null) {
          onTimeout(player)
        }
        if (clearChoicesOnTimeout) {
          playerActions.pendingActions().collect { it.player?.getId()?.toString() }.findAll { it != null }.unique().each {
            playerActions.remove(it)
          }
        }
      }
      done(player)
    } catch (Throwable t) {
      reportFailure(t)
    }
  }

  def finish() {
    eventTracker.track("Finished", [])
    gameListener.finish()
  }
}

class StepFactory {

  PlayerActions playerActions
  def gameListener
  def eventTracker

  Step createStep(String name) {
    return new Step(name: name, playerActions: playerActions, gameListener: gameListener, eventTracker: eventTracker)
  }

  Step createNoUserActionStep(String name) {
    return new Step(name: name, playerActions: playerActions, gameListener: gameListener, eventTracker: eventTracker, ignoreUserAction: true)
  }

}

stepFactory = new StepFactory(playerActions: a, gameListener: gameListener, eventTracker: eventTracker)
