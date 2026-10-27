// Tests for step durations (step.groovy), idle/drop timers and choice ownership (actions.groovy).
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.6 target: no lambdas / method refs.

// --- Step.duration ------------------------------------------------------------------------------

test("a step without a duration still ends right away when it offers no choices") {
  def ended = false
  def step = stepFactory.createStep("NoDuration")
  step.run = { }
  step.done = { ended = true }
  step.start()
  assert ended
}

test.async("a step with a duration but no choices waits for the whole duration", 3000) { done ->
  long started = System.currentTimeMillis()
  def step = stepFactory.createStep("WaitsFull")
  step.duration = 0.4
  step.run = { }
  step.done = {
    long elapsed = System.currentTimeMillis() - started
    done { assert elapsed >= 380 : "ended after ${elapsed} ms" }
  }
  step.start()
}

test.async("a timed step ends as soon as every choice has been made", 4000) { done ->
  def p = g.addPlayer('dur-chooser')
  long started = System.currentTimeMillis()
  def step = stepFactory.createStep("EndsEarly")
  step.duration = 3
  step.run = {
    a.add(p, [name: "OK", result: { }])
  }
  step.done = {
    long elapsed = System.currentTimeMillis() - started
    done { assert elapsed < 2000 : "waited ${elapsed} ms although the choice was made" }
  }
  step.start()
  timers.newTimer().runAfter(100) {
    a.choose(p.choices[0].uid)
  }
}

test.async("a timed step that runs out withdraws the pending choices and calls onTimeout", 3000) { done ->
  def p = g.addPlayer('dur-idle')
  def timedOutWith = null
  def step = stepFactory.createStep("RunsOut")
  step.duration = 0.3
  step.run = {
    a.add(p, [name: "Too late", result: { }])
  }
  step.onTimeout = { player -> timedOutWith = "called" }
  step.done = {
    done {
      assert timedOutWith == "called"
      assert !p.choices : "choices left on screen: ${p.choices}"
      assert a.ownerOf("anything") == null
    }
  }
  step.start()
}

// --- choice ownership ---------------------------------------------------------------------------

test("ownerOf names the player a pending choice was offered to") {
  def p = g.addPlayer('owner-1')
  a.add(p, [name: "Mine", result: { }])
  def uid = p.choices[0].uid
  assert a.ownerOf(uid) == 'owner-1'
  a.remove('owner-1')
  assert a.ownerOf(uid) == null
}

// --- idle / drop timers -------------------------------------------------------------------------

test.async("removing a player's choices cancels their drop countdown", 3000) { done ->
  def p = g.addPlayer('idle-removed')
  def dropped = []
  a.setWarnTime(0.2)
  a.setDropTime(0.2)
  a.setDropPlayerClosure({ v -> dropped << v.id })
  a.setDropPlayers(true)
  a.add(p, [name: "Wait", result: { }])
  a.remove('idle-removed')
  timers.newTimer().runAfter(900) {
    a.setDropPlayers(false)
    done { assert dropped.isEmpty() : "dropped although the choice was withdrawn" }
  }
}

test.async("answering one option of several cancels the whole countdown", 3000) { done ->
  def p = g.addPlayer('idle-answered')
  def dropped = []
  a.setWarnTime(0.3)
  a.setDropTime(0.3)
  a.setDropPlayerClosure({ v -> dropped << v.id })
  a.add(p, [name: "A", result: { }], [name: "B", result: { }], [name: "C", result: { }])
  // Turned on while the choice is pending: this used to start one countdown per option.
  a.setDropPlayers(true)
  a.choose(p.choices[0].uid)
  timers.newTimer().runAfter(1000) {
    a.setDropPlayers(false)
    done { assert dropped.isEmpty() : "dropped although the choice was answered" }
  }
}

test.async("an unanswered choice still drops the player", 3000) { done ->
  def p = g.addPlayer('idle-silent')
  a.setWarnTime(0.2)
  a.setDropTime(0.2)
  a.setDropPlayerClosure({ v ->
    a.setDropPlayers(false)
    done { assert v.id == 'idle-silent' }
  })
  a.setDropPlayers(true)
  a.add(p, [name: "Never answered", result: { }])
}

// --- tracked edges ------------------------------------------------------------------------------

test("a tracked edge can be removed with _removeEdge and the removal is logged") {
  def logged = []
  def oldTracker = g.eventTracker
  g.eventTracker = [track: { name, data -> logged << name }]
  try {
    def v1 = g.addPlayer('edge-a')
    def v2 = g.addPlayer('edge-b')
    def e = g.addTrackedEdge(v1, v2, "connected")
    assert g._removeEdge(e, true) == true
    assert !g.hasEdge(v1, v2)
    assert logged == ["Connected", "Disconnected"]
  } finally {
    g.eventTracker = oldTracker
  }
}

test("a removal that fails is not logged as Disconnected") {
  def logged = []
  def oldTracker = g.eventTracker
  g.eventTracker = [track: { name, data -> logged << name }]
  try {
    def v1 = g.addPlayer('edge-c')
    def v2 = g.addPlayer('edge-d')
    def e = g.addTrackedEdge(v1, v2, "connected", false)
    assert g._removeEdge(e, false) == true
    assert g._removeEdge(e, true) == false   // already gone
    assert !logged.contains("Disconnected")
  } finally {
    g.eventTracker = oldTracker
  }
}

// --- player._system (graph.groovy) ---------------------------------------------------------------

test("_system is a concurrent map that treats a null value as removing the key") {
  def v = g.addPlayer('sys-map-1')
  assert v._system instanceof java.util.concurrent.ConcurrentHashMap
  v._system.connected = true
  v._system.groupId = 'g1'
  assert v._system.connected == true
  // a ConcurrentHashMap rejects null; scripts that clear a value this way must keep working
  v._system.groupId = null
  assert !v._system.containsKey('groupId')
  v._system.putAll([a: 1, b: null])
  assert v._system.a == 1
  assert !v._system.containsKey('b')
}

test("_system can be read while another thread changes it") {
  def v = g.addPlayer('sys-map-2')
  def failure = null
  def writer = Thread.start {
    try {
      2000.times { i -> v._system.put("k" + (i % 50), i); v._system.remove("k" + ((i + 25) % 50)) }
    } catch (Throwable t) { failure = t }
  }
  // what a console does when it serializes the property
  200.times { play.libs.Json.toJson(v._system).toString() }
  writer.join()
  assert failure == null
}
