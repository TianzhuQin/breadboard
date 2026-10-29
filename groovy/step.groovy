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
    // (the steps that had got going on this thread before this one's code runs, see reportFailure)
    def going = startedSoFar()

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
      reportFailure(t, going)
      return
    }

    boolean hasUserActions = !playerActions.actions.isEmpty()

    if (duration != null && duration > 0) {
      startTimed(player, hasUserActions)
      gotGoing()
      return
    }

    if (hasUserActions && !ignoreUserAction) {
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
      gotGoing()
      return
    }

    // Nothing to wait for: done runs now, and the step has got going once it returns. A done that
    // throws hands the start back too, as a run that throws does: an init step whose done fails at
    // once (e.g. a typo in the next step's name) never got the game going, and the start may be tried
    // again, as when the console's Start fails. One whose done started the next step first (its first
    // round), which got going, did, and the server leaves its start (ScriptBoard.stepStartFailed).
    def answer
    try {
      answer = done(player)
    } catch (Throwable t) {
      if (startMark != null) {
        gameListener.stepStartFailed(startMark)
      }
      reportFailure(t, going)
      return null
    }
    gotGoing()
    return answer
  }

  // done(player) once the last pending choice is made (on the thread that made it); what it throws
  // goes to reportFailure.
  private void callDone(String player) {
    def going = startedSoFar()
    try {
      done(player)
    } catch (Throwable t) {
      reportFailure(t, going)
    }
  }

  // The step got going: its start() returns without having thrown, waiting for choices or for its
  // time, or with its done run (ScriptBoard.stepStarted counts it on this thread).
  private void gotGoing() {
    if (gameListener instanceof models.GameListener) {
      gameListener.stepStarted(this)
    }
  }

  // How many steps the server has seen get going on this thread so far; null when the listener isn't
  // the server's.
  private def startedSoFar() {
    return (gameListener instanceof models.GameListener) ? gameListener.stepsStarted() : null
  }

  // The step's own code (run, done, onTimeout) threw. On a timer, a participant's choice or a
  // computer player's, that used to end the thread, with the error only in the server's output, and
  // the game waited for good: the server reports it now (ScriptBoard.stepFailed). With going (what
  // startedSoFar() said as that code began) it tells whether the failure stalls the game: not when a
  // step got going since (the done started the next round, then threw). Inside a script the server
  // runs (the console's Start, a step run from the Steps page, the autopilot's start, OnJoinStep and
  // OnLeaveStep) it is thrown on to that script, whose error reports it; for the last three, which
  // nobody waits on, the server then deals with it as above.
  private void reportFailure(Throwable t, going) {
    if (!(gameListener instanceof models.GameListener) || !gameListener.stepFailed(this, t, going)) {
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
    def going = startedSoFar()
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
      reportFailure(t, going)
    }
  }

  def finish() {
    // A step of an engine since replaced, whose code ran on after the rebuild (a timer task under
    // way, code after its own finish()), would finish the session running now: the server gives
    // every engine it builds the same game listener and event tracker.
    if (gameListener instanceof models.GameListener && !gameListener.ofRunningEngine(this)) {
      return
    }
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
