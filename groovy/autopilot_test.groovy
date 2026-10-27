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

test("waiting participants are told how many are here") {
  def ap = pilot('ap-text-', [:])
  ap.waitingRoom([minPlayers: 3, waitSeconds: 60])
  def v = arrive(ap, 'ap-text-1')
  arrive(ap, 'ap-text-2')
  assert v.text.contains("2 joined")
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
