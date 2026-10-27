import com.tinkerpop.blueprints.Vertex

// Running a session from first arrival to last answer with nobody at the admin console.
// Together with breadboard.autopilot.* in application.conf (models.Autopilot), which opens the
// session, an experiment can run unattended. Call these from a step that runs at load time,
// e.g. the OnJoinStep's file or InitStep's setup code outside run:
//
//   autopilot.waitingRoom([
//     minPlayers: 15,        // never start with fewer
//     maxPlayers: 25,        // start as soon as this many are here
//     waitSeconds: 120,      // once minPlayers are here, wait this long for more, then start
//     maxWaitSeconds: 900,   // give up if minPlayers haven't arrived this long after the first person
//     countdownSeconds: 10,  // "Starting in ..." before the game begins
//     waitingText: { here, min, max -> "..." },   // what waiting participants see; null keeps the
//                                                 // experiment's own text
//     onStart: { players -> },   // just before InitStep starts, with the players who are in
//     onCancel: { players -> },  // not enough people came: e.g. recruitment.completeProlific(p, ...)
//   ])
//
//   autopilot.replaceDisconnected([
//     afterSeconds: 30,      // how long someone may be gone before the computer takes their seat
//     behavior: null,        // an a.ai behavior closure; null = the default random choice
//     returnToHuman: true,   // give the seat back if they reconnect
//   ])
//
// People who left before the start are taken out of the network before InitStep runs, so nobody
// starts next to an empty seat. Every start, cancellation and takeover is logged as an event.

class Autopilot {
  def g
  def a
  def gameListener
  def eventTracker
  def startGame    // closure: starts InitStep through ScriptBoard (guarded, closes the game to newcomers)
  // Which vertices are participants. AI players added with g.addAI have ids starting with "_".
  Closure isParticipant = { v -> !v.id.toString().startsWith("_") }

  Map room = null
  Map replace = null
  boolean starting = false
  boolean finished = false
  Long firstArrivalAt = null
  BBTimer startTimer = null
  BBTimer giveUpTimer = null
  Map<String, BBTimer> takeoverTimers = [:]

  synchronized def waitingRoom(Map opts) {
    room = [
      minPlayers      : opts.minPlayers ?: 2,
      maxPlayers      : opts.maxPlayers,
      waitSeconds     : opts.waitSeconds == null ? 60 : opts.waitSeconds,
      maxWaitSeconds  : opts.maxWaitSeconds,
      countdownSeconds: opts.countdownSeconds == null ? 5 : opts.countdownSeconds,
      waitingText     : opts.containsKey("waitingText") ? opts.waitingText : { here, min, max ->
        def target = max ? "${min}–${max}" : "${min}"
        "<h3>Waiting for other participants</h3><p>${here} joined so far. The session starts when ${target} people are here.</p><p>Please keep this page open.</p>"
      },
      onStart         : opts.onStart,
      onCancel        : opts.onCancel,
    ]
    evaluate()
  }

  synchronized def replaceDisconnected(Map opts = [:]) {
    replace = [
      afterSeconds : opts.afterSeconds == null ? 30 : opts.afterSeconds,
      behavior     : opts.behavior,
      returnToHuman: opts.returnToHuman == null ? true : opts.returnToHuman,
    ]
  }

  synchronized def stop() {
    room = null
    replace = null
    cancelTimer("start")
    cancelTimer("giveUp")
    takeoverTimers.values().each { it.cancel() }
    takeoverTimers.clear()
  }

  boolean gameStarted() {
    return starting || gameListener?.experimentInstance?.hasStarted == true
  }

  // Players (not AI vertices, whose ids start with "_") whose browser is connected right now.
  List<Vertex> connectedPlayers() {
    g.getVertices().findAll { v ->
      isParticipant(v) && v.getProperty("_system")?.connected == true
    }.toList()
  }

  synchronized def onConnection(String clientId, boolean connected) {
    if (gameStarted()) {
      handleDeparture(clientId, connected)
    } else {
      evaluate()
    }
  }

  // Before the game: decide whether to start, wait or give up, and keep the waiting text current.
  synchronized def evaluate() {
    if (room == null || gameStarted() || finished) return
    def players = connectedPlayers()
    int here = players.size()
    if (here > 0 && firstArrivalAt == null) {
      firstArrivalAt = System.currentTimeMillis()
      if (room.maxWaitSeconds) {
        giveUpTimer = schedule(room.maxWaitSeconds) { giveUp() }
      }
    }
    if (room.waitingText != null) {
      String text = room.waitingText(here, room.minPlayers, room.maxPlayers)?.toString()
      players.each { it.text = text }
    }
    if (room.maxPlayers && here >= room.maxPlayers) {
      cancelTimer("start")
      beginCountdown()
    } else if (here >= room.minPlayers) {
      if (startTimer == null) {
        startTimer = schedule(room.waitSeconds) { beginCountdown() }
      }
    } else {
      cancelTimer("start")
    }
  }

  synchronized def beginCountdown() {
    if (gameStarted() || room == null) return
    starting = true
    cancelTimer("start")
    cancelTimer("giveUp")
    int seconds = room.countdownSeconds
    if (seconds > 0) {
      String text = "<h3>Starting in ${seconds} seconds…</h3>".toString()
      connectedPlayers().each { it.text = text }
      schedule(seconds) { launch() }
    } else {
      launch()
    }
  }

  synchronized def launch() {
    // Whoever left before the start doesn't get a seat in the network.
    g.getVertices().findAll { v ->
      isParticipant(v) && v.getProperty("_system")?.connected != true
    }.toList().each { v -> g.removePlayer(v.id) }
    def players = connectedPlayers()
    track("autopilotStart", [[name: "players", value: players.size().toString()]])
    if (room?.onStart != null) room.onStart(players)
    startGame()
  }

  synchronized def giveUp() {
    if (gameStarted() || room == null) return
    def players = connectedPlayers()
    if (players.size() >= room.minPlayers) return
    finished = true
    cancelTimer("start")
    track("autopilotCancelled", [[name: "players", value: players.size().toString()]])
    if (room.onCancel != null) {
      room.onCancel(players)
    } else {
      players.each { it.text = "<h3>Not enough participants joined</h3><p>Thank you for waiting. This session could not start.</p>" }
    }
    gameListener?.finish()
  }

  // During the game: hand a departed player's seat to the computer after a grace period.
  def handleDeparture(String clientId, boolean connected) {
    if (replace == null) return
    def v = g.getVertex(clientId)
    if (v == null) return
    if (connected) {
      takeoverTimers.remove(clientId)?.cancel()
      if (replace.returnToHuman && v.getProperty("ai") == 1) {
        a.ai.remove(v)
        track("aiReturned", [[name: "playerId", value: clientId]])
      }
      return
    }
    takeoverTimers.remove(clientId)?.cancel()
    takeoverTimers[clientId] = schedule(replace.afterSeconds) {
      synchronized (this) {
        takeoverTimers.remove(clientId)
        def player = g.getVertex(clientId)
        if (player != null && player.getProperty("_system")?.connected != true) {
          if (replace.behavior != null) {
            a.ai.add(player, replace.behavior)
          } else {
            a.ai.add(player)
          }
          track("aiTakeover", [[name: "playerId", value: clientId]])
        }
      }
    }
  }

  private BBTimer schedule(Number seconds, Closure action) {
    BBTimer timer = new BBTimer()
    timer.runAfter((int) Math.round(seconds * 1000)) {
      try {
        action()
      } finally {
        timer.cancel()
      }
    }
    return timer
  }

  private void cancelTimer(String which) {
    if (which == "start" && startTimer != null) {
      startTimer.cancel()
      startTimer = null
    }
    if (which == "giveUp" && giveUpTimer != null) {
      giveUpTimer.cancel()
      giveUpTimer = null
    }
  }

  private void track(String name, List data) {
    if (eventTracker != null) eventTracker.track(name, data)
  }
}

autopilot = new Autopilot(g: g, a: a, gameListener: gameListener, eventTracker: eventTracker,
    startGame: { models.ScriptBoard.processScript("initStep.start()", null, "autopilot") })

events.on("bb:connection", { Map info ->
  try {
    autopilot.onConnection(info.clientId.toString(), info.connected == true)
  } catch (Exception e) {
    models.ScriptLoader.humanizeStackTrace(e)
    play.Logger.error("autopilot: error handling a connection change", e)
  }
})
