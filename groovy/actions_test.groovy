// Tests for the computer players of actions.groovy (PlayerAI): the timer their picks wait on, when a
// pick is still made, and how the data records it. Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.9 target: no lambdas / method refs.

import java.util.concurrent.atomic.AtomicInteger

// A computer player's pick comes 1000 + nextInt(3000) ms after the choices are offered, and takes
// choices[nextInt(size)]. These answers make it come after 1 second, and take the first choice.
class ScriptedRandom extends Random {
  List answers = [0]
  int calls = 0

  int nextInt(int bound) {
    return answers[calls++ % answers.size()]
  }
}

def timerThreads() {
  Thread.getAllStackTraces().keySet().findAll { it.isAlive() && it.name.startsWith("Timer-") }.size()
}

test.async("the computer's picks don't start a thread each", 8000) { done ->
  // Each pick used to start a timer of its own, whose thread lived until the engine was rebuilt.
  a.ai.r = new ScriptedRandom()
  def poll = timers.newTimer()
  int before = timerThreads()
  int n = 12
  def made = new AtomicInteger()
  n.times { i ->
    def p = g.addPlayer("ai-threads-" + i)
    a.add(p, [name: "Go", result: { made.incrementAndGet() }])
    a.ai.add(p)
  }
  int waiting = timerThreads()
  poll.scheduleAtFixedRate({
    if (made.get() == n) {
      poll.cancel()
      int after = timerThreads()
      done {
        assert waiting - before <= 1 : "${waiting - before} new timer threads for ${n} picks"
        assert after - before <= 1 : "${after - before} timer threads left after ${n} picks"
        assert a.ai.pendingPicks.isEmpty()
      }
    }
  } as GroovyTimerTask, 100, 100)
}

test.async("the computer still answers the rounds offered after every timer was stopped", 8000) { done ->
  // timers.cancel() (the console's "Stop every timer", an experiment's own call) cancels the timer
  // the picks wait on too. Every pick after it was dropped: a seat the computer had taken over from
  // someone who left never answered again, and the game waited for good.
  a.ai.r = new ScriptedRandom()
  def players = (0..<3).collect { g.addPlayer("ai-after-cancel-" + it) }
  players.each { a.ai.add(it) }   // a pick each, for no choices yet, which the cancel drops
  timers.cancel()
  def answered = new AtomicInteger()
  players.each { p ->
    a.add(p, [name: "Next round", result: {
      if (answered.incrementAndGet() == players.size()) {
        done {
          assert timers.timers.size() == 1 : "one new timer for the picks, not one each"
          assert a.ai.pendingPicks.isEmpty() : "the picks the cancel dropped are still remembered"
        }
      }
    }])
  }
}

test("no pick starts a timer once the computer players are off and the timers cancelled") {
  // As in an engine being replaced: ScriptBoard turns them off, then cancels the timers. A pick
  // asked for just before (past the check that they are on) must not start a timer nothing cancels.
  def p = g.addPlayer('ai-engine-replaced')
  a.add(p, [name: "Go", result: { }])
  def cancelled = a.ai.timer
  a.turnAIOff()
  timers.cancel()
  a.ai.defaultBehavior.call(p)
  assert a.ai.timer.is(cancelled) : "a timer was started for the pick"
  assert timers.timers.isEmpty()
  assert a.ai.pendingPicks.isEmpty()
}

test("the computer players a.empty() replaced start no timer again") {
  def p = g.addPlayer('ai-emptied')
  def old = a.ai
  def cancelled = old.timer
  a.empty()
  int registered = timers.timers.size()
  old.defaultBehavior.call(p)   // a pick they were asked for as they were replaced
  assert old.timer.is(cancelled) : "a timer was started for the pick"
  assert timers.timers.size() == registered
}

test.async("a pick the computer scheduled is not made once the person has the seat back", 5000) { done ->
  a.ai.r = new ScriptedRandom()
  def p = g.addPlayer('ai-came-back')
  def made = []
  a.add(p, [name: "Mine", result: { made << "chosen" }])
  a.ai.add(p)      // the computer takes the seat, and would pick in 1 second
  a.ai.remove(p)   // the person is back before that
  timers.newTimer().runAfter(1800) {
    done {
      assert made.isEmpty() : "the computer chose for a person who had come back"
      assert p.choices : "the choice is still the person's to make"
      assert a.ai.pendingPicks.isEmpty() : "the pick was not called off"
    }
  }
}

test.async("a pick is only made while the seat is still the computer's", 5000) { done ->
  a.ai.r = new ScriptedRandom()
  def p = g.addPlayer('ai-flag-gone')
  def made = []
  a.add(p, [name: "Mine", result: { made << "chosen" }])
  a.ai.add(p)
  p.removeProperty("ai")   // the seat given back without a.ai.remove (e.g. by an experiment's own code)
  timers.newTimer().runAfter(1800) {
    done { assert made.isEmpty() : "the computer chose although the seat was no longer its" }
  }
}

test.async("a pick for choices answered meanwhile is not made for the ones offered after them", 5000) { done ->
  // The first pick comes after 1 second; the next one, for round 2, after 4.
  a.ai.r = new ScriptedRandom(answers: [0, 2999])
  def p = g.addPlayer('ai-stale')
  def made = []
  a.add(p, [name: "Round 1", result: { made << 1 }])
  a.ai.add(p)                      // a pick for round 1, in 1 second
  a.choose(p.choices[0].uid)       // round 1 is answered before that (from the console, say)
  a.add(p, [name: "Round 2", result: { made << 2 }])
  timers.newTimer().runAfter(2000) {
    done { assert made == [1] : "round 2 was answered by the pick meant for round 1" }
  }
}

test.async("the computer's choices are recorded as aiChoice events, and a person's are not", 5000) { done ->
  def tracked = []
  a.eventTracker = [track: { name, data -> tracked << [name: name, data: data] }]
  a.ai.r = new ScriptedRandom()
  def person = g.addPlayer('ai-recorded-person')
  a.add(person, [name: "Mine", result: { }])
  a.choose(person.choices[0].uid)
  def p = g.addPlayer('ai-recorded')
  a.add(p, [name: "Cooperate", result: {
    done {
      def picks = tracked.findAll { it.name == "aiChoice" }
      assert picks.size() == 1
      assert picks[0].data.playerId == 'ai-recorded'
      assert picks[0].data.choice == 'Cooperate'
      assert picks[0].data.choiceUID != null
    }
  }])
  a.ai.add(p)
}
