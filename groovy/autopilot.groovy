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
//                                                 // experiment's own text. The new participant page
//                                                 // (BBGame) shows its own waiting screen, with this
//                                                 // text under it when the experiment sets one.
//     onStart: { players -> },   // just before InitStep starts, with the players who are in
//     onCancel: { players -> },  // not enough people came: e.g. recruitment.completeProlific(p, ...)
//                                // Whoever was away then is passed to it when they come back.
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
  def startGame    // closure: starts InitStep through ScriptBoard (guarded, closes the game to newcomers);
                   // returns null, or what went wrong (a String)
  def startFailed  // closure, given what went wrong: the session is dealt with (ScriptBoard.gameStartFailed)
  // Which vertices are participants. AI players added with g.addAI have ids starting with "_".
  Closure isParticipant = { v -> !v.id.toString().startsWith("_") }

  Map room = null
  Map replace = null
  boolean starting = false   // the countdown has begun
  boolean launched = false   // the game has started, through launch() or otherwise
  boolean finished = false   // given up: not enough people came
  Long firstArrivalAt = null
  Long countdownEndsAt = null
  BBTimer startTimer = null
  Long startAt = null        // when startTimer fires: the waiting screen counts down to it
  BBTimer giveUpTimer = null
  BBTimer countdownTimer = null
  Map<String, BBTimer> takeoverTimers = [:]
  Set<String> cancelHandled = [] as Set   // players already passed to onCancel

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
      customText      : opts.containsKey("waitingText"),
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
    cancelTimer("countdown")
    takeoverTimers.values().each { it.cancel() }
    takeoverTimers.clear()
    clearStatus()
  }

  boolean gameStarted() {
    return starting || launched || gameListener?.experimentInstance?.hasStarted == true
  }

  // Every participant vertex, connected or not.
  List<Vertex> participants() {
    g.getVertices().findAll { v -> isParticipant(v) }.toList()
  }

  // Players (not AI vertices, whose ids start with "_") whose browser is connected right now.
  List<Vertex> connectedPlayers() {
    g.getVertices().findAll { v ->
      isParticipant(v) && v.getProperty("_system")?.connected == true
    }.toList()
  }

  synchronized def onConnection(String clientId, boolean connected) {
    if (finished && !launched) {
      if (connected) cancelLate(clientId)
    } else if (gameStarted()) {
      if (connected && starting && !launched) showCountdown(clientId)
      handleDeparture(clientId, connected)
    } else {
      evaluate()
    }
  }

  // ScriptBoard sends "bb:game-start" however the game starts: launch() below, the admin's Run
  // button, the AMT timer or a script calling initStep.start(). From then on the waiting room is
  // done, and nobody's page may keep showing it. Safe to call more than once.
  synchronized def onGameStart() {
    launched = true
    cancelTimer("start")
    cancelTimer("giveUp")
    cancelTimer("countdown")
    clearStatus()
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
    boolean full = room.maxPlayers && here >= room.maxPlayers
    if (!full && here >= room.minPlayers) {
      if (startTimer == null) {
        startAt = System.currentTimeMillis() + Math.round(room.waitSeconds * 1000)
        startTimer = schedule(room.waitSeconds) { beginCountdown() }
      }
    } else if (!full) {
      cancelTimer("start")
    }
    // Once enough are here: the seconds until the game starts unless more come (the page counts down).
    Integer startsIn = startAt == null ? null : (int) Math.max(0, Math.ceil((startAt - System.currentTimeMillis()) / 1000d))
    players.each { status(it, [state: "waiting", here: here, min: room.minPlayers, max: room.maxPlayers,
                               customText: room.customText, startsIn: startsIn]) }
    if (room.waitingText != null) {
      String text = room.waitingText(here, room.minPlayers, room.maxPlayers)?.toString()
      players.each { it.text = text }
    }
    if (full) {
      cancelTimer("start")
      beginCountdown()
    }
  }

  synchronized def beginCountdown() {
    if (gameStarted() || room == null) return
    starting = true
    cancelTimer("start")
    cancelTimer("giveUp")
    int seconds = room.countdownSeconds
    if (seconds > 0) {
      countdownEndsAt = System.currentTimeMillis() + seconds * 1000L
      // The timer is set whatever happens to the pages: with starting set, nothing else starts the game.
      try {
        def players = connectedPlayers()
        players.each { countdown(it, seconds, players.size()) }
      } finally {
        countdownTimer = schedule(seconds) { launch() }
      }
    } else {
      launch()
    }
  }

  // Someone who (re)connects during the countdown sees the seconds that are left.
  private void showCountdown(String clientId) {
    def v = g.getVertex(clientId)
    if (v == null || !isParticipant(v) || room == null || countdownEndsAt == null) return
    int seconds = Math.max(1, (int) Math.ceil((countdownEndsAt - System.currentTimeMillis()) / 1000d))
    countdown(v, seconds, connectedPlayers().size())
  }

  private void countdown(Vertex v, int seconds, int here) {
    status(v, [state: "starting", seconds: seconds, here: here, min: room.minPlayers, max: room.maxPlayers])
    v.text = "<h3>Starting in ${seconds} seconds…</h3>".toString()
  }

  synchronized def launch() {
    // startGame() comes back here through onGameStart(); this keeps a late countdown from
    // starting a game that already began some other way.
    if (launched) return
    launched = true
    // From here the game starts even if something below fails (a page update, the experiment's
    // onStart): with launched set, nothing else would start it, and everyone would wait for good.
    try {
      // Whoever left before the start doesn't get a seat in the network.
      g.getVertices().findAll { v ->
        isParticipant(v) && v.getProperty("_system")?.connected != true
      }.toList().each { v -> g.removePlayer(v.id) }
      def players = connectedPlayers()
      clearStatus()
      track("autopilotStart", [[name: "players", value: players.size().toString()]])
      if (room?.onStart != null) room.onStart(players)
    } finally {
      // A start that failed (a mistake in InitStep, or in a step it starts) is taken back, and with
      // launched set nothing would start it again: everyone would stay on "Starting in…" for good,
      // with newcomers let in. ScriptBoard says why, and ends the session if the autopilot opened it
      // (one a researcher runs by hand is left to them).
      def failed = startGame()
      if (failed instanceof String && startFailed != null) startFailed(failed)
    }
  }

  synchronized def giveUp() {
    if (gameStarted() || room == null) return
    def players = connectedPlayers()
    if (players.size() >= room.minPlayers) return
    finished = true
    cancelTimer("start")
    track("autopilotCancelled", [[name: "players", value: players.size().toString()]])
    players.each { cancelHandled << it.id.toString() }
    try {
      runCancel(players)
    } finally {
      // Everyone's page says so, also of those who are away right now and see it when they
      // return, even if onCancel failed.
      participants().each { status(it, [state: "cancelled"]) }
      gameListener?.finish()
    }
  }

  // Someone who was away when the session was given up, or arrives after, is told too, and
  // passed to onCancel once.
  private void cancelLate(String clientId) {
    if (room == null || cancelHandled.contains(clientId)) return
    def v = g.getVertex(clientId)
    if (v == null || !isParticipant(v)) return
    cancelHandled << clientId
    try {
      runCancel([v])
    } finally {
      status(v, [state: "cancelled"])
    }
  }

  // The default notice replaces the autopilot's own waiting text first, so a page that onCancel
  // doesn't give new text ("Please keep this page open") doesn't keep telling people to wait. With
  // waitingText: null the text is the experiment's, and left to onCancel.
  private void runCancel(List<Vertex> players) {
    if (room.onCancel == null || room.waitingText != null) {
      players.each { it.text = "<h3>Not enough participants joined</h3><p>Thank you for waiting. This session could not start.</p>" }
    }
    if (room.onCancel != null) room.onCancel(players)
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

  private void clearStatus() {
    participants().each { status(it, null) }
  }

  // What the participant's page shows while waiting: player._system.autopilot (only they see it).
  // Reassigning _system sends the change to the browser at once.
  private void status(Vertex v, Map state) {
    def sys = v.getProperty("_system")
    sys = sys instanceof Map ? sys : [:]
    if (state == null) {
      if (!sys.containsKey("autopilot")) return
      sys.remove("autopilot")
    } else {
      sys.autopilot = state
    }
    v.setProperty("_system", sys)
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
    if (which == "start") {
      startAt = null
      if (startTimer != null) {
        startTimer.cancel()
        startTimer = null
      }
    }
    if (which == "giveUp" && giveUpTimer != null) {
      giveUpTimer.cancel()
      giveUpTimer = null
    }
    if (which == "countdown" && countdownTimer != null) {
      countdownTimer.cancel()
      countdownTimer = null
    }
  }

  private void track(String name, List data) {
    if (eventTracker != null) eventTracker.track(name, data)
  }
}

autopilot = new Autopilot(g: g, a: a, gameListener: gameListener, eventTracker: eventTracker,
    startGame: { models.ScriptBoard.processScript("initStep.start()", null, "autopilot") },
    startFailed: { String failed -> gameListener.gameStartFailed(failed) })

events.on("bb:connection", { Map info ->
  try {
    autopilot.onConnection(info.clientId.toString(), info.connected == true)
  } catch (Exception e) {
    models.ScriptLoader.humanizeStackTrace(e)
    play.Logger.error("autopilot: error handling a connection change", e)
  }
})

events.on("bb:game-start", { Map info ->
  try {
    autopilot.onGameStart()
  } catch (Exception e) {
    models.ScriptLoader.humanizeStackTrace(e)
    play.Logger.error("autopilot: error handling the game start", e)
  }
})
