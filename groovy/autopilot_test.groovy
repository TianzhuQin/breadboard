// Tests for autopilot.groovy: the unattended waiting room and computer takeover.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Each test uses its own Autopilot with stubbed game start / finish, and only counts its own
// players (by id prefix), because the engine's graph is shared between tests.

def pilot(String prefix, Map hooks) {
  def instance = [hasStarted: false]
  def listener = [experimentInstance: instance, finish: { hooks.finished = true }]
  def ap = new Autopilot(g: g, a: a, gameListener: listener, eventTracker: null,
      startGame: { hooks.started = (hooks.started ?: 0) + 1; instance.hasStarted = true })
  ap.isParticipant = { v -> v.id.toString().startsWith(prefix) }
  return ap
}

def arrive(Autopilot ap, String id) {
  def v = g.getVertex(id) ?: g.addPlayer(id)
  v._system.connected = true
  ap.onConnection(id, true)
  return v
}

def leave(Autopilot ap, String id) {
  g.getVertex(id)._system.connected = false
  ap.onConnection(id, false)
}

test("the game starts as soon as maxPlayers are here") {
  def hooks = [:]
  def ap = pilot('ap-max-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 3, waitSeconds: 60, countdownSeconds: 0])
  arrive(ap, 'ap-max-1'); arrive(ap, 'ap-max-2')
  assert hooks.started == null
  arrive(ap, 'ap-max-3')
  assert hooks.started == 1
  ap.stop()
}

test("the game starts even when the experiment's onStart fails") {
  def hooks = [:]
  def ap = pilot('ap-onstart-', hooks)
  ap.waitingRoom([minPlayers: 1, maxPlayers: 1, countdownSeconds: 0,
                  onStart: { players -> throw new IllegalStateException("a broken onStart") }])
  try {
    arrive(ap, 'ap-onstart-1')
  } catch (IllegalStateException expected) {
    // reported, but the game was started all the same
  }
  assert hooks.started == 1
  ap.stop()
}

test("a start that fails is handed on with what went wrong, and one that works is not") {
  def failedWith = []
  def ap = pilot('ap-startfail-', [:])
  ap.startGame = { "line 3: No such property: roundStepp" }
  ap.startFailed = { String error -> failedWith << error }
  ap.waitingRoom([minPlayers: 1, maxPlayers: 1, countdownSeconds: 0])
  arrive(ap, 'ap-startfail-1')
  assert failedWith == ["line 3: No such property: roundStepp"]
  ap.stop()

  def fine = pilot('ap-startok-', [:])
  fine.startGame = { null }
  fine.startFailed = { String error -> failedWith << error }
  fine.waitingRoom([minPlayers: 1, maxPlayers: 1, countdownSeconds: 0])
  arrive(fine, 'ap-startok-1')
  assert fine.launched
  assert failedWith.size() == 1
  fine.stop()
}

test("waiting participants are told how many are here") {
  def ap = pilot('ap-text-', [:])
  ap.waitingRoom([minPlayers: 3, waitSeconds: 60])
  def v = arrive(ap, 'ap-text-1')
  arrive(ap, 'ap-text-2')
  assert v.text.contains("2 joined")
  ap.stop()
}

test("the waiting screen state is published to each waiting participant and cleared at the start") {
  def ap = pilot('ap-state-', [:])
  ap.waitingRoom([minPlayers: 2, maxPlayers: 2, countdownSeconds: 0])
  def v = arrive(ap, 'ap-state-1')
  assert v._system.autopilot.state == "waiting"
  assert v._system.autopilot.here == 1
  assert v._system.autopilot.min == 2
  arrive(ap, 'ap-state-2')
  assert !v._system.containsKey("autopilot")
  ap.stop()
}

test("once enough are here, the waiting screen gets the seconds until the start") {
  def ap = pilot('ap-soon-', [:])
  ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 30])
  def v = arrive(ap, 'ap-soon-1')
  assert v._system.autopilot.startsIn == null
  arrive(ap, 'ap-soon-2')
  assert v._system.autopilot.startsIn in 29..30
  // someone leaves: not enough any more, no countdown to the start
  leave(ap, 'ap-soon-2')
  assert v._system.autopilot.startsIn == null
  ap.stop()
}

test.async("with minPlayers here the game starts after waitSeconds", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-min-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 10, waitSeconds: 0.3, countdownSeconds: 0])
  arrive(ap, 'ap-min-1'); arrive(ap, 'ap-min-2')
  assert hooks.started == null
  timers.newTimer().runAfter(900) {
    done { assert hooks.started == 1; ap.stop() }
  }
}

test.async("dropping below minPlayers cancels a pending start", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-drop-', hooks)
  ap.waitingRoom([minPlayers: 2, waitSeconds: 0.4, countdownSeconds: 0])
  arrive(ap, 'ap-drop-1'); arrive(ap, 'ap-drop-2')
  leave(ap, 'ap-drop-2')
  timers.newTimer().runAfter(900) {
    done { assert hooks.started == null; ap.stop() }
  }
}

test.async("too few people within maxWaitSeconds cancels the session", 4000) { done ->
  def hooks = [:]
  def cancelled = []
  def ap = pilot('ap-give-', hooks)
  ap.waitingRoom([minPlayers: 3, maxWaitSeconds: 0.3, onCancel: { players -> cancelled.addAll(players*.id) }])
  arrive(ap, 'ap-give-1')
  timers.newTimer().runAfter(900) {
    done {
      assert hooks.started == null
      assert hooks.finished == true
      assert cancelled == ['ap-give-1']
      ap.stop()
    }
  }
}

test("people who left before the start are taken out of the network") {
  def hooks = [:]
  def ap = pilot('ap-ghost-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 3, countdownSeconds: 0])
  arrive(ap, 'ap-ghost-1'); arrive(ap, 'ap-ghost-2')
  leave(ap, 'ap-ghost-2')
  arrive(ap, 'ap-ghost-3'); arrive(ap, 'ap-ghost-4')
  assert hooks.started == 1
  assert g.getVertex('ap-ghost-2') == null : "an empty seat was left in the network"
  assert g.getVertex('ap-ghost-1') != null
  ap.stop()
}

test.async("a player who leaves mid-game is replaced by the computer, and gets the seat back on return", 5000) { done ->
  def hooks = [:]
  def ap = pilot('ap-ai-', hooks)
  ap.waitingRoom([minPlayers: 1, maxPlayers: 1, countdownSeconds: 0])
  ap.replaceDisconnected([afterSeconds: 0.3])
  def v = arrive(ap, 'ap-ai-1')
  assert hooks.started == 1
  leave(ap, 'ap-ai-1')
  timers.newTimer().runAfter(800) {
    def tookOver = v.getProperty("ai") == 1
    arrive(ap, 'ap-ai-1')
    def returned = v.getProperty("ai") != 1
    a.ai.remove(v)
    done {
      assert tookOver : "the computer did not take the seat"
      assert returned : "the seat was not given back"
      ap.stop()
    }
  }
}

test("during the countdown participants see how many seconds are left") {
  def hooks = [:]
  def ap = pilot('ap-count-', hooks)
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 2, countdownSeconds: 30])
    def v = arrive(ap, 'ap-count-1')
    arrive(ap, 'ap-count-2')
    assert hooks.started == null
    assert v._system.autopilot.state == "starting"
    assert v._system.autopilot.seconds == 30
    assert v._system.autopilot.here == 2
    assert v.text.contains("30 seconds")
  } finally {
    ap.stop()
  }
}

test.async("someone who comes back during the countdown sees the seconds that are left", 5000) { done ->
  def hooks = [:]
  def ap = pilot('ap-back-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 2, countdownSeconds: 10])
  arrive(ap, 'ap-back-1'); arrive(ap, 'ap-back-2')
  leave(ap, 'ap-back-2')
  timers.newTimer().runAfter(1100) {
    def v = arrive(ap, 'ap-back-2')
    done {
      try {
        assert v._system.autopilot.state == "starting"
        assert v._system.autopilot.seconds in 8..9
        assert v.text.contains("${v._system.autopilot.seconds} seconds")
        assert hooks.started == null
      } finally {
        ap.stop()
      }
    }
  }
}

test("when the session is given up every participant is told, and onCancel gets those who are here") {
  def hooks = [:]
  def cancelled = []
  def ap = pilot('ap-cancel-', hooks)
  try {
    ap.waitingRoom([minPlayers: 3, waitSeconds: 60, onCancel: { players -> cancelled.addAll(players*.id) }])
    def v = arrive(ap, 'ap-cancel-1')
    def away = arrive(ap, 'ap-cancel-2')
    leave(ap, 'ap-cancel-2')
    ap.giveUp()
    assert hooks.finished == true
    assert cancelled == ['ap-cancel-1']
    assert v._system.autopilot.state == "cancelled"
    assert away._system.autopilot.state == "cancelled"
  } finally {
    ap.stop()
  }
}

test("whoever comes back after the session was given up is told and passed to onCancel once") {
  def hooks = [:]
  def cancelled = []
  def ap = pilot('ap-late-', hooks)
  try {
    ap.waitingRoom([minPlayers: 3, waitSeconds: 60, onCancel: { players -> cancelled.addAll(players*.id) }])
    arrive(ap, 'ap-late-1')
    arrive(ap, 'ap-late-2')
    leave(ap, 'ap-late-2')
    ap.giveUp()
    def back = arrive(ap, 'ap-late-2')
    leave(ap, 'ap-late-2')
    arrive(ap, 'ap-late-2')
    def newcomer = arrive(ap, 'ap-late-3')
    leave(ap, 'ap-late-1')
    arrive(ap, 'ap-late-1')
    assert cancelled == ['ap-late-1', 'ap-late-2', 'ap-late-3']
    assert back._system.autopilot.state == "cancelled"
    assert newcomer._system.autopilot.state == "cancelled"
    assert hooks.started == null
  } finally {
    ap.stop()
  }
}

test("without onCancel those who come back late get the default text") {
  def ap = pilot('ap-sorry-', [:])
  try {
    ap.waitingRoom([minPlayers: 3, waitSeconds: 60])
    arrive(ap, 'ap-sorry-1')
    ap.giveUp()
    def late = arrive(ap, 'ap-sorry-2')
    assert late.text.contains("Not enough participants")
    assert late._system.autopilot.state == "cancelled"
  } finally {
    ap.stop()
  }
}

test("everyone is told the session was given up even if onCancel fails") {
  def hooks = [:]
  def ap = pilot('ap-throw-', hooks)
  try {
    ap.waitingRoom([minPlayers: 3, waitSeconds: 60, onCancel: { players -> throw new IllegalStateException("onCancel failed") }])
    def v = arrive(ap, 'ap-throw-1')
    try {
      ap.giveUp()
      assert false : "the onCancel error should come through"
    } catch (IllegalStateException expected) {}
    assert v._system.autopilot.state == "cancelled"
    assert hooks.finished == true
  } finally {
    ap.stop()
  }
}

test("stopping the autopilot clears the waiting screen of every participant") {
  def ap = pilot('ap-halt-', [:])
  ap.waitingRoom([minPlayers: 3, waitSeconds: 60])
  def v = arrive(ap, 'ap-halt-1')
  def away = arrive(ap, 'ap-halt-2')
  leave(ap, 'ap-halt-2')
  assert v._system.autopilot.state == "waiting"
  assert away._system.autopilot.state == "waiting"
  ap.stop()
  assert !v._system.containsKey("autopilot")
  assert !away._system.containsKey("autopilot")
}

test("a game started some other way (bb:game-start) ends the waiting room for every participant") {
  def hooks = [:]
  def ap = pilot('ap-other-', hooks)
  // The global autopilot listens the same way (autopilot.groovy); this one is the test's own.
  def listener = { Map info -> ap.onGameStart() }
  events.on("bb:game-start", listener)
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 3, waitSeconds: 60, countdownSeconds: 0])
    def v = arrive(ap, 'ap-other-1')
    def away = arrive(ap, 'ap-other-2')
    leave(ap, 'ap-other-2')
    assert v._system.autopilot.state == "waiting"
    assert away._system.autopilot.state == "waiting"
    events.emit("bb:game-start", [:])
    assert !v._system.containsKey("autopilot")
    assert !away._system.containsKey("autopilot")
    // The waiting room no longer acts: no waiting screen, and launch() does not start it again.
    def late = arrive(ap, 'ap-other-3')
    arrive(ap, 'ap-other-4')
    assert !late._system.containsKey("autopilot")
    assert hooks.started == null
    assert g.getVertex('ap-other-2') != null : "launch() ran after the game had started"
  } finally {
    events.off("bb:game-start", listener)
    ap.stop()
  }
}

test("the game start event also ends a countdown") {
  def hooks = [:]
  def ap = pilot('ap-halfway-', hooks)
  try {
    ap.waitingRoom([minPlayers: 1, maxPlayers: 1, countdownSeconds: 30])
    def v = arrive(ap, 'ap-halfway-1')
    assert v._system.autopilot.state == "starting"
    ap.onGameStart()
    ap.onGameStart()
    assert !v._system.containsKey("autopilot")
    assert ap.countdownTimer == null
    ap.launch()
    assert hooks.started == null
  } finally {
    ap.stop()
  }
}

test("customText says whether the experiment chose the waiting text") {
  def custom = pilot('ap-custom-', [:])
  def kept = pilot('ap-kept-', [:])
  def plain = pilot('ap-plain-', [:])
  try {
    custom.waitingRoom([minPlayers: 3, waitingText: { here, min, max -> "<p>${here} of ${min}</p>" }])
    kept.waitingRoom([minPlayers: 3, waitingText: null])
    plain.waitingRoom([minPlayers: 3])
    g.addPlayer('ap-kept-1').text = "<p>The experiment's own text</p>"
    def c = arrive(custom, 'ap-custom-1')
    def k = arrive(kept, 'ap-kept-1')
    def p = arrive(plain, 'ap-plain-1')
    assert c._system.autopilot.customText == true
    assert c.text == "<p>1 of 3</p>"
    assert k._system.autopilot.customText == true
    assert k.text == "<p>The experiment's own text</p>"
    assert p._system.autopilot.customText == false
    assert p.text.contains("1 joined")
  } finally {
    custom.stop(); kept.stop(); plain.stop()
  }
}

test.async("an onCancel that sets no text still replaces the waiting text", 4000) { done ->
  def ap = pilot('ap-quiet-', [:])
  ap.waitingRoom([minPlayers: 3, maxWaitSeconds: 0.3, onCancel: { players -> }])
  def v = arrive(ap, 'ap-quiet-1')
  assert v.text.contains("Waiting for other participants")
  timers.newTimer().runAfter(900) {
    done {
      try {
        assert !v.text.contains("keep this page open")
        assert v.text.contains("could not start")
      } finally {
        ap.stop()
      }
    }
  }
}
