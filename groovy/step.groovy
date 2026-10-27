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

    // Record the start time of each step.
    if (name != null) {
      eventTracker.track(name + "Start", ["startStep": name])
    }

    ended = new java.util.concurrent.atomic.AtomicBoolean(false)
    run(player)

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
              done(player)
            }
          }
        } as PropertyChangeListener

        playerActions.addActionPropertyChangeListener(emptyListener)
      } else {
        done(player)
      }
    } else {
      done(player)
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

  // Ends a timed step exactly once, whichever of "all chosen" and "time is up" happens first.
  private void endOnce(String player, boolean timedOut) {
    if (!ended.compareAndSet(false, true)) {
      return
    }
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
