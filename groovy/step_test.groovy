// Tests for step.groovy when a step's own code throws: on a timer, a participant's choice or a
// computer player's, the failure goes to gameListener.stepFailed (the server reports it, see
// ScriptBoard.stepFailed) instead of ending the thread it ran on.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// The harness fails a case that leaves a reported failure behind, so each case takes out the ones
// it expects (reported()). Groovy 1.8.6 target: no lambdas / method refs.

// The failures steps reported so far, taken out.
def reported() {
  def failures = new ArrayList(gameListener.stepFailures)
  gameListener.stepFailures.clear()
  return failures
}

test.async("a run that throws on a timer is reported, and the timer goes on with its next task", 3000) { done ->
  def step = stepFactory.createStep("BrokenRun")
  step.run = { throw new IllegalStateException("broken run") }
  def timer = timers.newTimer()
  timer.runAfter(50) { step.start() }
  // Runs only if the timer's thread lived through the first task.
  timer.runAfter(300) {
    done {
      def failures = reported()
      assert failures.size() == 1
      assert failures[0].step.is(step)
      assert failures[0].failure.message == "broken run"
    }
  }
}

test.async("a done that throws when the step's time runs out is reported", 3000) { done ->
  def step = stepFactory.createStep("BrokenTimedDone")
  step.duration = 0.1
  step.run = { }
  step.done = { throw new IllegalStateException("broken done") }
  step.start()
  timers.newTimer().runAfter(500) {
    done {
      def failures = reported()
      assert failures.size() == 1
      assert failures[0].step.is(step)
      assert failures[0].failure.message == "broken done"
    }
  }
}

test.async("an onTimeout that throws is reported", 3000) { done ->
  def step = stepFactory.createStep("BrokenOnTimeout")
  step.duration = 0.1
  step.run = { }
  step.onTimeout = { player -> throw new IllegalStateException("broken onTimeout") }
  step.start()
  timers.newTimer().runAfter(500) {
    done {
      def failures = reported()
      assert failures.size() == 1
      assert failures[0].failure.message == "broken onTimeout"
    }
  }
}

test("a done that throws when the last choice is made is reported, not thrown at the one who chose") {
  def p = g.addPlayer('sf-chooser')
  def chosen = false
  def step = stepFactory.createStep("BrokenAfterChoice")
  step.run = { a.add(p, [name: "OK", result: { chosen = true }]) }
  step.done = { throw new IllegalStateException("broken after the choice") }
  step.start()
  a.choose(p.choices[0].uid)
  assert chosen
  def failures = reported()
  assert failures.size() == 1
  assert failures[0].failure.message == "broken after the choice"
}

test("a timed step whose done throws when the last choice is made is reported") {
  def p = g.addPlayer('sf-timed-chooser')
  def step = stepFactory.createStep("BrokenTimedChoice")
  step.duration = 5
  step.run = { a.add(p, [name: "OK", result: { }]) }
  step.done = { throw new IllegalStateException("broken timed done") }
  step.start()
  a.choose(p.choices[0].uid)
  def failures = reported()
  assert failures.size() == 1
  assert failures[0].failure.message == "broken timed done"
}

test("a run that throws goes no further: its done doesn't run") {
  def doneRan = false
  def step = stepFactory.createStep("StopsAtRun")
  step.run = { throw new IllegalStateException("stops here") }
  step.done = { doneRan = true }
  step.start()
  assert !doneRan
  assert reported()*.failure*.message == ["stops here"]
}

test("a step that fails in the done of the one before is reported once, as itself") {
  def second = stepFactory.createStep("Second")
  second.run = { throw new IllegalStateException("second broke") }
  def first = stepFactory.createStep("First")
  def after = false
  first.run = { }
  first.done = {
    second.start()
    after = true
  }
  first.start()
  assert after : "the first step's done went on"
  def failures = reported()
  assert failures.size() == 1
  assert failures[0].step.is(second)
}

test("a step that doesn't throw reports nothing, and start() answers what done answers, as before") {
  def step = stepFactory.createStep("Fine")
  step.run = { }
  step.done = { "the done's answer" }
  assert step.start() == "the done's answer"
  assert gameListener.stepFailures.isEmpty()
}

test("a step whose listener isn't the server's throws as before") {
  def step = new Step(name: "Plain", playerActions: a, gameListener: [:], eventTracker: eventTracker)
  step.run = { throw new IllegalStateException("plain") }
  def thrown = null
  try {
    step.start()
  } catch (IllegalStateException e) {
    thrown = e
  }
  assert thrown?.message == "plain"
}
