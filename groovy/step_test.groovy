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

// Marks every step's start, as the server marks the game's (ScriptBoard.stepStarting), and keeps what
// was taken back, which steps got going and what was reported, with whether it stalls the game (no
// step got going since the failing code began). Inside a script the server runs, a failure is thrown
// on. (One count for every thread: each case runs one thread's steps at a time.)
class MarkingListener extends models.GameListener {
  boolean inScript = false
  List takenBack = []
  List gotGoing = []
  List reported = []
  List stalls = []

  Object stepStarting(Object step) { return "the start of " + step.name }

  void stepStarted(Object step) { gotGoing << step.name }

  Long stepsStarted() { return (long) gotGoing.size() }

  void stepStartFailed(Object mark) { takenBack << mark }

  boolean stepFailed(Object step, Throwable failure, Long going) {
    reported << failure.message
    stalls << (going == stepsStarted())
    return !inScript
  }
}

def marked(String name, MarkingListener listener) {
  return new Step(name: name, playerActions: a, gameListener: listener, eventTracker: eventTracker)
}

test("a done that throws as the step starts takes its start back, as a run that throws does") {
  def listener = new MarkingListener()
  def broken = marked("BrokenAtOnce", listener)
  broken.run = { }
  broken.done = { throw new IllegalStateException("broken at once") }
  broken.start()
  def throwing = marked("BrokenRun", listener)
  throwing.run = { throw new IllegalStateException("broken run") }
  throwing.start()
  assert listener.takenBack == ["the start of BrokenAtOnce", "the start of BrokenRun"]
  assert listener.reported == ["broken at once", "broken run"]
}

test("inside a script, a done that throws as the step starts takes its start back and is thrown on") {
  def listener = new MarkingListener(inScript: true)
  def step = marked("BrokenInScript", listener)
  step.run = { }
  step.done = { throw new IllegalStateException("thrown on") }
  def thrown = null
  try {
    step.start()
  } catch (IllegalStateException e) {
    thrown = e
  }
  assert thrown?.message == "thrown on"
  assert listener.takenBack == ["the start of BrokenInScript"]
}

test("a done that throws once the step is under way leaves its start alone") {
  def listener = new MarkingListener()
  def p = g.addPlayer('sf-under-way')
  def step = marked("UnderWay", listener)
  step.run = { a.add(p, [name: "OK", result: { }]) }
  step.done = { throw new IllegalStateException("broken later") }
  step.start()
  a.choose(p.choices[0].uid)
  assert listener.reported == ["broken later"]
  assert listener.takenBack.isEmpty()
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

test("a step gets going once its start() returns without having thrown") {
  def listener = new MarkingListener()
  def p = g.addPlayer('sf-going')
  def fine = marked("DoneAtOnce", listener)
  fine.run = { }
  fine.done = { }
  fine.start()
  def timed = marked("WaitsForItsTime", listener)
  timed.duration = 60
  timed.run = { }
  timed.start()
  def brokenRun = marked("RunThrows", listener)
  brokenRun.run = { throw new IllegalStateException("broken run") }
  brokenRun.start()
  def brokenDone = marked("DoneThrows", listener)
  brokenDone.run = { }
  brokenDone.done = { throw new IllegalStateException("broken done") }
  brokenDone.start()
  // (last: while a choice is pending anywhere, every step that starts waits for the choices)
  def waiting = marked("WaitsForAChoice", listener)
  waiting.run = { a.add(p, [name: "OK", result: { }]) }
  waiting.done = { }
  waiting.start()
  assert listener.gotGoing == ["DoneAtOnce", "WaitsForItsTime", "WaitsForAChoice"]
  assert listener.reported == ["broken run", "broken done"]
  assert listener.stalls == [true, true]
  a.choose(p.choices[0].uid)
  assert listener.gotGoing.size() == 3 : "making the choice starts nothing new"
}

test("inside a script, an init step whose done starts a round that throws as it starts gets nothing going") {
  def listener = new MarkingListener(inScript: true)
  def round = marked("Round", listener)
  round.duration = 60
  round.run = { throw new IllegalStateException("round broke") }
  def init = marked("Init", listener)
  init.run = { }
  init.done = { round.start() }
  def thrown = null
  try {
    init.start()
  } catch (IllegalStateException e) {
    thrown = e
  }
  assert thrown?.message == "round broke"
  assert listener.gotGoing.isEmpty()
  assert listener.takenBack == ["the start of Round", "the start of Init"]
}

test.async("a done on a timer that starts the next round before it throws doesn't stall the game", 3000) { done ->
  def listener = new MarkingListener()
  def next = marked("NextRound", listener)
  next.duration = 60
  next.run = { }
  def round = marked("Round", listener)
  round.duration = 0.1
  round.run = { }
  round.done = {
    next.start()
    throw new IllegalStateException("after the next round started")
  }
  round.start()
  timers.newTimer().runAfter(500) {
    done {
      assert listener.gotGoing == ["Round", "NextRound"]
      assert listener.reported == ["after the next round started"]
      assert listener.stalls == [false]
    }
  }
}

test.async("a done on a timer that throws before the next round gets going stalls the game", 3000) { done ->
  def listener = new MarkingListener()
  def next = marked("NextRound", listener)
  next.run = { throw new IllegalStateException("the next round broke") }
  def round = marked("Round", listener)
  round.duration = 0.1
  round.run = { }
  round.done = { next.start() }
  round.start()
  timers.newTimer().runAfter(500) {
    done {
      assert listener.gotGoing == ["Round"]
      assert listener.reported == ["the next round broke"]
      assert listener.stalls == [true]
    }
  }
}

test("a step of an engine since replaced doesn't finish the session running now") {
  // (the server's listener, which serves another engine: this step's isn't the one running)
  def listener = new models.GameListener()
  listener.engine = [:] as javax.script.ScriptEngine
  def finished = []
  def tracker = [track: { String name, data -> finished << name }]
  def step = new Step(name: "Replaced", playerActions: a, gameListener: listener, eventTracker: tracker)
  step.finish()
  assert finished.isEmpty()

  // A listener of no server engine (this harness's) finishes as before.
  def own = new Step(name: "Own", playerActions: a, gameListener: gameListener, eventTracker: tracker)
  own.finish()
  assert finished == ["Finished"]
  assert gameListener.finished()
}
