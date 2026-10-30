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

// As ScriptBoard.setConnected records it: connected, and since when someone is away.
def arrive(Autopilot ap, String id) {
  def v = g.getVertex(id) ?: g.addPlayer(id)
  v._system.connected = true
  v._system.disconnectedAt = null
  ap.onConnection(id, true)
  return v
}

// awayFor: how long ago they left (ms)
def leave(Autopilot ap, String id, long awayFor = 0) {
  def v = g.getVertex(id)
  v._system.connected = false
  v._system.disconnectedAt = System.currentTimeMillis() - awayFor
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

test("at most maxPlayers are seated, the first to arrive, and the others are told the session is full") {
  def hooks = [:]
  def full = []
  def started = []
  def ap = pilot('ap-cap-', hooks)
  ap.sessionFull = { String id -> full << id }
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 2, waitSeconds: 60, countdownSeconds: 30,
                    onStart: { players -> started.addAll(players*.id) }])
    arrive(ap, 'ap-cap-1'); Thread.sleep(5)
    assert !ap.roomFull()
    arrive(ap, 'ap-cap-2'); Thread.sleep(5)
    assert ap.starting && ap.roomFull() : "full, and counting down"
    // Someone sent here before the room was full arrives during the countdown.
    arrive(ap, 'ap-cap-3')
    ap.launch()
    assert hooks.started == 1
    assert started.toSet() == ['ap-cap-1', 'ap-cap-2'].toSet()
    assert g.getVertex('ap-cap-3') == null : "seated, although the room was full"
    assert full == ['ap-cap-3']
    assert !ap.roomFull()
  } finally {
    ap.stop()
  }
}

test("someone away for a moment keeps their place by arrival, and the room isn't full while a seat is free") {
  def hooks = [:]
  def full = []
  def ap = pilot('ap-capaway-', hooks)
  ap.sessionFull = { String id -> full << id }
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 3, waitSeconds: 60, countdownSeconds: 30])
    ap.replaceDisconnected([afterSeconds: 30])
    arrive(ap, 'ap-capaway-1'); Thread.sleep(5)
    arrive(ap, 'ap-capaway-2'); Thread.sleep(5)
    arrive(ap, 'ap-capaway-3'); Thread.sleep(5)
    assert ap.roomFull()
    // A drop for a moment during the countdown keeps the seat: the room stays full.
    leave(ap, 'ap-capaway-2', 2000)
    assert ap.roomFull()
    // One who stays away long enough loses it: a seat is free, and newcomers may come again.
    leave(ap, 'ap-capaway-3', 61000)
    assert !ap.roomFull()
    arrive(ap, 'ap-capaway-4'); Thread.sleep(5)
    arrive(ap, 'ap-capaway-5')
    ap.launch()
    assert g.getVertex('ap-capaway-2') != null : "the seat of someone away for a moment was given away"
    assert g.getVertex('ap-capaway-3') == null
    assert g.getVertex('ap-capaway-4') != null
    assert g.getVertex('ap-capaway-5') == null
    assert full == ['ap-capaway-5']
  } finally {
    ap.stop()
  }
}

test("without maxPlayers everyone here is seated, and the room is never full") {
  def hooks = [:]
  def full = []
  def ap = pilot('ap-nocap-', hooks)
  ap.sessionFull = { String id -> full << id }
  try {
    ap.waitingRoom([minPlayers: 2, waitSeconds: 60, countdownSeconds: 30])
    (1..4).each { arrive(ap, "ap-nocap-${it}".toString()) }
    ap.beginCountdown()
    assert !ap.roomFull()
    ap.launch()
    assert (1..4).every { g.getVertex("ap-nocap-${it}".toString()) != null }
    assert full.isEmpty()
  } finally {
    ap.stop()
  }
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

test.async("dropping below minPlayers holds a pending start back", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-drop-', hooks)
  ap.waitingRoom([minPlayers: 2, waitSeconds: 0.4, countdownSeconds: 0])
  arrive(ap, 'ap-drop-1'); arrive(ap, 'ap-drop-2')
  leave(ap, 'ap-drop-2')
  timers.newTimer().runAfter(900) {
    done { assert hooks.started == null; ap.stop() }
  }
}

test("a moment's drop in the waiting room doesn't start the wait again") {
  def ap = pilot('ap-blip-', [:])
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 30])
    def v = arrive(ap, 'ap-blip-1')
    arrive(ap, 'ap-blip-2')
    def startAt = ap.startAt
    def startTimer = ap.startTimer
    assert startAt != null
    leave(ap, 'ap-blip-2')
    assert v._system.autopilot.here == 1
    assert v._system.autopilot.startsIn == null : "too few right now: no countdown to the start"
    assert ap.startAt == startAt : "the wait was called off at once"
    arrive(ap, 'ap-blip-2')
    assert ap.startAt == startAt : "the wait started again from the beginning"
    assert ap.startTimer.is(startTimer)
    assert v._system.autopilot.startsIn in 29..30
    assert ap.graceTimer == null
  } finally {
    ap.stop()
  }
}

test.async("when the wait ends while someone is away for a moment, the game starts as they come back", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-due-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 0.2, countdownSeconds: 0])
  arrive(ap, 'ap-due-1'); arrive(ap, 'ap-due-2')
  leave(ap, 'ap-due-2')
  timers.newTimer().runAfter(600) {
    def heldBack = hooks.started == null && ap.startDue
    arrive(ap, 'ap-due-2')
    done {
      try {
        assert heldBack : "the game started with too few, or the start was called off"
        assert hooks.started == 1
      } finally {
        ap.stop()
      }
    }
  }
}

test.async("a departure that lasts departureGraceSeconds calls the start off", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-gone-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 30, departureGraceSeconds: 0.2])
  arrive(ap, 'ap-gone-1'); arrive(ap, 'ap-gone-2')
  def first = ap.startAt
  leave(ap, 'ap-gone-2')
  timers.newTimer().runAfter(600) {
    def calledOff = ap.startAt == null && ap.startTimer == null
    // enough people again: the wait begins anew
    arrive(ap, 'ap-gone-3')
    def fresh = ap.startAt
    done {
      try {
        assert calledOff : "the start was not called off"
        assert fresh != null && fresh > first
        assert hooks.started == null
      } finally {
        ap.stop()
      }
    }
  }
}

test("a start called off just as its timer fires waits again for enough people") {
  def hooks = [:]
  def ap = pilot('ap-off-', hooks)
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 30, countdownSeconds: 0])
    arrive(ap, 'ap-off-1'); arrive(ap, 'ap-off-2')
    def firing = ap.startTimer
    leave(ap, 'ap-off-2')
    // the departure has lasted, and the start timer fires as the start is called off
    ap.departureLasted()
    ap.waitOver(firing)
    assert !ap.startDue
    arrive(ap, 'ap-off-2')
    assert hooks.started == null : "the countdown began at once"
    assert ap.startAt != null : "no new wait for more"
  } finally {
    ap.stop()
  }
}

test.async("a room that replaces another while someone waits gives up as the new one says", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-later-', hooks)
  // The steps' own room never gives up; someone comes before the console's settings, which do.
  ap.waitingRoom([minPlayers: 3, waitSeconds: 60])
  arrive(ap, 'ap-later-1')
  ap.replaceWaitingRoom([minPlayers: 3, waitSeconds: 60, maxWaitSeconds: 0.3])
  timers.newTimer().runAfter(900) {
    done {
      try {
        assert hooks.finished == true : "the session never gave up"
      } finally {
        ap.stop()
      }
    }
  }
}

test("a room that replaces another while people wait for more times that wait as the new one says") {
  def ap = pilot('ap-retime-', [:])
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 60])
    def v = arrive(ap, 'ap-retime-1')
    arrive(ap, 'ap-retime-2')
    def began = ap.waitBeganAt
    assert ap.startAt == began + 60000
    ap.replaceWaitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 20])
    assert ap.waitBeganAt == began : "the wait started again"
    assert ap.startAt == began + 20000
    assert v._system.autopilot.startsIn in 19..20
    // the same values again (every load of the steps applies them) change nothing
    ap.replaceWaitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 20])
    assert ap.startAt == began + 20000
    assert ap.giveUpTimer == null : "no maxWaitSeconds: never gives up"
    // The steps' own room, set up again as they reload (the console's replaces it a moment later),
    // changes no timer: one counted from it could end the wait, or give up, at once.
    ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 1, maxWaitSeconds: 1])
    assert ap.startAt == began + 20000
    assert ap.giveUpTimer == null
    assert !ap.starting
  } finally {
    ap.stop()
  }
}

test.async("the session isn't given up while someone is away for a moment", 4000) { done ->
  def hooks = [:]
  def ap = pilot('ap-patience-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 30, maxWaitSeconds: 0.3, departureGraceSeconds: 0.6])
  arrive(ap, 'ap-patience-1'); arrive(ap, 'ap-patience-2')
  leave(ap, 'ap-patience-2')
  timers.newTimer().runAfter(500) {
    def kept = hooks.finished == null
    arrive(ap, 'ap-patience-2')
    timers.newTimer().runAfter(700) {
      done {
        try {
          assert kept : "given up while they were away for a moment"
          assert hooks.finished == null
          assert ap.startAt != null
        } finally {
          ap.stop()
        }
      }
    }
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

test("people who left well before the start are taken out of the network") {
  def hooks = [:]
  def ap = pilot('ap-ghost-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 3, countdownSeconds: 0])
  arrive(ap, 'ap-ghost-1'); arrive(ap, 'ap-ghost-2')
  leave(ap, 'ap-ghost-2', 61000)
  arrive(ap, 'ap-ghost-3'); arrive(ap, 'ap-ghost-4')
  assert hooks.started == 1
  assert g.getVertex('ap-ghost-2') == null : "an empty seat was left in the network"
  assert g.getVertex('ap-ghost-1') != null
  ap.stop()
}

test("someone away for a moment at the start keeps their seat, which the computer plays") {
  def hooks = [:]
  def started = []
  def ap = pilot('ap-seat-', hooks)
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 4, countdownSeconds: 0, onStart: { players -> started.addAll(players*.id) }])
    ap.replaceDisconnected([afterSeconds: 30])
    arrive(ap, 'ap-seat-1'); arrive(ap, 'ap-seat-2'); arrive(ap, 'ap-seat-3')
    leave(ap, 'ap-seat-3', 5000)
    // a player whose page never connected (e.g. one a script added) still goes
    g.addPlayer('ap-seat-ghost')
    // (the kept seat is one of the four: the room is full with the fourth here)
    arrive(ap, 'ap-seat-4')
    assert hooks.started == 1
    assert g.getVertex('ap-seat-3') != null : "their seat was taken away as their page reconnected"
    assert g.getVertex('ap-seat-ghost') == null
    assert started.toSet() == ['ap-seat-1', 'ap-seat-2', 'ap-seat-3', 'ap-seat-4'].toSet()
    assert ap.takeoverTimers.keySet() == ['ap-seat-3'] as Set : "nobody plays the kept seat"
  } finally {
    ap.stop()
  }
}

test("without replaceDisconnected someone away at the start is taken out, however briefly") {
  def hooks = [:]
  def started = []
  def told = []
  def ap = pilot('ap-nokeep-', hooks)
  ap.leftOut = { String id -> told << id }
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 4, countdownSeconds: 0, onStart: { players -> started.addAll(players*.id) }])
    arrive(ap, 'ap-nokeep-1'); arrive(ap, 'ap-nokeep-2'); arrive(ap, 'ap-nokeep-3')
    leave(ap, 'ap-nokeep-3', 2000)
    arrive(ap, 'ap-nokeep-4'); arrive(ap, 'ap-nokeep-5')
    assert hooks.started == 1
    // Nobody would play a seat kept for them: they get the "started without you" screen if they come back.
    assert g.getVertex('ap-nokeep-3') == null : "a seat was kept that nobody plays"
    assert told == ['ap-nokeep-3']
    assert started.toSet() == ['ap-nokeep-1', 'ap-nokeep-2', 'ap-nokeep-4', 'ap-nokeep-5'].toSet()
    assert ap.takeoverTimers.isEmpty()
  } finally {
    ap.stop()
  }
}

test("whoever launch() takes out is passed on, so their page is told when it comes back") {
  def hooks = [:]
  def told = []
  def ap = pilot('ap-out-', hooks)
  ap.leftOut = { String id -> told << id }
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 3, countdownSeconds: 0])
    ap.replaceDisconnected([afterSeconds: 30])
    arrive(ap, 'ap-out-1'); arrive(ap, 'ap-out-2')
    leave(ap, 'ap-out-2', 61000)
    arrive(ap, 'ap-out-3')
    leave(ap, 'ap-out-3', 5000)
    arrive(ap, 'ap-out-4')
    assert hooks.started == 1
    assert told == ['ap-out-2'] : "only those taken out"
  } finally {
    ap.stop()
  }
}

test("the game starts as it should when passing on whoever was taken out fails") {
  def hooks = [:]
  def started = []
  def ap = pilot('ap-outfail-', hooks)
  ap.leftOut = { String id -> throw new IllegalStateException("could not note it") }
  try {
    ap.waitingRoom([minPlayers: 2, maxPlayers: 3, countdownSeconds: 0, onStart: { players -> started.addAll(players*.id) }])
    ap.replaceDisconnected([afterSeconds: 30])
    arrive(ap, 'ap-outfail-1'); arrive(ap, 'ap-outfail-2')
    leave(ap, 'ap-outfail-2', 61000)
    arrive(ap, 'ap-outfail-3')
    leave(ap, 'ap-outfail-3', 5000)
    // (the kept seat is one of the three: the room is full with the fourth here)
    arrive(ap, 'ap-outfail-4')
    assert hooks.started == 1
    assert started.toSet() == ['ap-outfail-1', 'ap-outfail-3', 'ap-outfail-4'].toSet()
    assert ap.takeoverTimers.containsKey('ap-outfail-3') : "the kept seat was not handed to the computer"
  } finally {
    ap.stop()
  }
}

test("a seat is kept for as long as replaceDisconnected waits, when that is longer than a minute") {
  def hooks = [:]
  def ap = pilot('ap-long-', hooks)
  try {
    ap.waitingRoom([minPlayers: 1, maxPlayers: 3, countdownSeconds: 0])
    ap.replaceDisconnected([afterSeconds: 120])
    arrive(ap, 'ap-long-1'); arrive(ap, 'ap-long-2')
    leave(ap, 'ap-long-2', 90000)
    leave(ap, 'ap-long-1', 125000)
    arrive(ap, 'ap-long-3'); arrive(ap, 'ap-long-4'); arrive(ap, 'ap-long-5')
    assert hooks.started == 1
    assert g.getVertex('ap-long-2') != null
    assert g.getVertex('ap-long-1') == null
  } finally {
    ap.stop()
  }
}

test.async("the computer plays a seat kept at the start until its player is back", 5000) { done ->
  def hooks = [:]
  def ap = pilot('ap-stand-', hooks)
  ap.waitingRoom([minPlayers: 2, maxPlayers: 4, countdownSeconds: 0])
  ap.replaceDisconnected([afterSeconds: 0.3])
  arrive(ap, 'ap-stand-1'); arrive(ap, 'ap-stand-2'); arrive(ap, 'ap-stand-3')
  leave(ap, 'ap-stand-3', 2000)
  arrive(ap, 'ap-stand-4'); arrive(ap, 'ap-stand-5')
  def v = g.getVertex('ap-stand-3')
  timers.newTimer().runAfter(800) {
    def tookOver = v.getProperty("ai") == 1
    arrive(ap, 'ap-stand-3')
    def returned = v.getProperty("ai") != 1
    a.ai.remove(v)
    done {
      try {
        assert hooks.started == 1
        assert tookOver : "the computer did not play the kept seat"
        assert returned : "the seat was not given back"
      } finally {
        ap.stop()
      }
    }
  }
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

test("a computer player's page coming and going leaves its seat to the computer") {
  def hooks = [:]
  def ap = pilot('ap-cpu-', hooks)
  // As the global autopilot tells them apart: computer players (g.addAI) have ids starting with "_".
  ap.isParticipant = new Autopilot().isParticipant
  def behavior = { player -> }
  def bot = g.addAI(a, 1, behavior)[0]
  def id = bot.id.toString()
  try {
    ap.replaceDisconnected([afterSeconds: 30, behavior: { player -> }])
    ap.onGameStart()
    // a researcher's preview of it opens and closes
    ap.onConnection(id, true)
    assert bot.getProperty("ai") == 1 : "the computer was switched off"
    ap.onConnection(id, false)
    assert !ap.takeoverTimers.containsKey(id)
    assert a.ai.behaviors[bot].is(behavior) : "the experiment's own behaviour was replaced"
    // a person leaving is still handed to the computer
    arrive(ap, 'ap-cpu-1')
    leave(ap, 'ap-cpu-1')
    assert ap.takeoverTimers.containsKey('ap-cpu-1')
  } finally {
    ap.stop()
    a.ai.remove(bot)
    g.removeVertex(bot)
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
    // (long enough ago that launch() would take them out)
    leave(ap, 'ap-other-2', 61000)
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
