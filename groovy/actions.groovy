import groovy.json.JsonSlurper
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import com.fasterxml.jackson.annotation.JsonIgnore

class PlayerActions {
  // To handle player AI
  def ai = new PlayerAI(playerActions: this)

  def eventTracker
  // closure: Breadboard's own words to participants, in the language of the session's texts
  // (util.groovy's bbText); English when not given
  def texts = { String key, Map params = [:] -> models.SystemTexts.text(null, key, params) }
  def idleTime
  def warnTime
  def dropTime
  def dropPlayers = false

  Closure dropPlayerClosure

  //all actions across all players
  def actions = new ObservableMap(new ConcurrentHashMap())
  //def actions = [:] as ObservableMap

  //map the player to the player's action queue in a round
  def playerToActionQueue = [:]

  def setIdleTime(idleTime) {
    this.idleTime = idleTime
  }

  def setWarnTime(warnTime) {
    this.warnTime = warnTime
  }

  def setDropTime(dropTime) {
    this.dropTime = dropTime
  }

  def setIdleTimer(playerAction) {
    if ((idleTime != null || (warnTime != null && dropTime != null)) && (dropPlayers != null && dropPlayers == true)) {
      def idleTimer1 = [:]
      idleTimer1.player = playerAction.player
      idleTimer1.timer = new BBTimer()
      idleTimer1.fired = false

      def idleTimer2 = [:]
      idleTimer2.player = playerAction.player
      idleTimer2.timer = new BBTimer()

      def time1 = (warnTime != null) ? warnTime : idleTime
      def time2 = (dropTime != null) ? dropTime : idleTime
      idleTimer1.cancelled = false
      // Whole milliseconds, so fractional second settings (e.g. 0.5) work too.
      idleTimer1.timer.runAfter((int) Math.round(time1 * 1000)) {
        if (idleTimer1.cancelled) {
          return
        }

        idleTimer1.fired = true

        def timerName = "dropTimer"

        def dropTimer = [
            name          : timerName,
            type          : "time",
            elapsed       : 0,
            duration      : Math.round(time2 * 1000),
            timerText     : texts("timer.dropped"),
            direction     : "down",
            currencyAmount: "0",
            appearance    : "warning",
            order         : -1
        ]

        if (playerAction.player.timers == null) {
          playerAction.player.timers = [:]
        }

        playerAction.player.timers[timerName] = dropTimer
        // TODO: Find out why modifying the timers map doesn't trigger a client update without the next line
        playerAction.player.lastupdated = System.currentTimeMillis()

        try {
          idleTimer2.timer.runAfter((int) Math.round(time2 * 1000)) {
            idleTimer2.timer.cancel()
            if (idleTimer1.cancelled) {
              return
            }

            if (dropPlayerClosure) {
              dropPlayerClosure(playerAction.player)
            }
            playerAction.player.timers.remove(timerName)
          }

          def timerUpdateRate = 1000
          idleTimer2.timer.scheduleAtFixedRate({
            if (timerName in playerAction.player.timers) {
              playerAction.player.timers[timerName].elapsed += timerUpdateRate
            }
          } as GroovyTimerTask, timerUpdateRate, timerUpdateRate)
        } catch (IllegalStateException ignored) {
          // The action was answered or removed while the warning fired: the drop timer is already
          // cancelled, so there is nothing to schedule.
        }

      }

      playerAction.idleTimer1 = idleTimer1
      playerAction.idleTimer2 = idleTimer2
    }
  }

  def setDropPlayers(dropPlayers) {
    this.dropPlayers = dropPlayers
    // actions holds one entry per choice uid, all pointing at the same PlayerAction. Timing each
    // entry gave a player with N options N countdowns, of which answering cancelled only one.
    def pending = pendingActions()
    if (dropPlayers) {
      // Now dropping players, set timers for any players with active choices
      pending.each { action ->
        if (action.player && action.player.getProperty("choices") && action.idleTimer1 == null) {
          setIdleTimer(action)
        }
      }
    } else {
      pending.each { it.cancelIdleTimers() }
    }
  }

  // The distinct PlayerAction objects behind the per-uid entries of actions.
  def pendingActions() {
    def seen = [] as Set
    def result = []
    actions.values().each { action ->
      if (action != null && seen.add(System.identityHashCode(action))) {
        result << action
      }
    }
    return result
  }

  def setDropPlayerClosure(Closure dropPlayerClosure) {
    this.dropPlayerClosure = dropPlayerClosure
  }

  def addEvent(name, data) {
    eventTracker.track(name, data)
  }

  def empty() {
    pendingActions().each { it.cancelIdleTimers() }
    actions.each {
      if (it.value.player)
        it.value.player.removeProperty("choices")
    }

    def propertyChangeListeners = actions.propertyChangeListeners?.clone()
    propertyChangeListeners.each {
      actions.removePropertyChangeListener(it)
    }
    actions.clear()
    playerToActionQueue.clear()

    // Off first: a pick asked for meanwhile would otherwise start another timer for this AI.
    ai.off()
    ai.timer.cancel()
    ai = new PlayerAI(playerActions: this)

  }

  def remove(Vertex p) {
    remove(p.id.toString())
  }

  def remove(String pid) {
    def removedChoices = false
    def pActions = getActions(pid)
    def pActionsKeys = pActions.keySet()
    new ArrayList(pActions.values()).unique { System.identityHashCode(it) }.each { it?.cancelIdleTimers() }
    pActionsKeys.each {
      def action = actions.get(it)
      if (! removedChoices && action.hasProperty("player")) {
        playerToActionQueue[action.player] = [] as Queue
        action.player.choices = []
        removedChoices = true
      }

      actions.remove(it)
    }
  }

  def add(Vertex player, HashMap... choices) {
    add(player, null, choices)
    // TODO:  Should this be 'add(player, {}, choices)'?
  }

  def add(Vertex player, Closure init, HashMap... choices) {
    //make sure the queue is ready for each player
    if (playerToActionQueue[player] == null) {
      playerToActionQueue[player] = [] as Queue
    }

    //action is queue up for the individual player
    playerToActionQueue[player] << {

      def choiceArray = []
      int i = 1

      if (init) {
        init()
      }

      def playerAction = new PlayerAction(player: player, eventTracker: eventTracker)

      setIdleTimer(playerAction)

      for (HashMap c : choices) {
        // If result is not provided, default to empty closure
        def result = {}
        if (c.result) {
          result = c.result
        } else if (c.results) {
          // Let's accept both result and results
          result = c.results
        }

        def choiceMap = [:]

        // Name
        choiceMap.name = (c.name) ? c.name : "Option " + i

        // Class
        if (c.class)
          choiceMap.class = c.class

        // Colour of the button on the new participant page (BBGame), e.g. color: "#2563eb"
        if (c.color)
          choiceMap.color = c.color.toString()

        // Custom question type
        if (c.custom)
          choiceMap.custom = c.custom

        // UUID
        String uuid = UUID.randomUUID().toString()
        playerAction.putResult(uuid, result)
        playerAction.putName(uuid, choiceMap.name)
        if (c.event) {
          playerAction.putEvent(uuid, c.event);
        }

        choiceMap.uid = uuid

        //all the uuid point to the same playerAction in a round
        actions[uuid] = playerAction

        // Add the choiceMap to the choiceArray
        choiceArray.add(choiceMap)

        i++
      }
      String jsonString = new groovy.json.JsonBuilder(choiceArray).toString()

      player.setProperty("choices", choiceArray)
      return jsonString
    }

    if (playerToActionQueue[player].size() == 1) {
      def playerFirstAction = playerToActionQueue[player][0]

      playerFirstAction()
    }

    // When we add new actions, start the AI
    if (player.getProperty("ai") == 1) {
      ai.choose(player);
    }
  }

  def choose(String uid) {
    choose(uid, "")
  }

  // Id of the player a pending choice uid was offered to (null if there is no such choice), so the
  // server can refuse a choice sent by anyone else.
  String ownerOf(String uid) {
    PlayerAction action = actions[uid]
    return action?.player?.getId()?.toString()
  }

  def choose(String uid, String params) {
    PlayerAction action = actions[uid]

    if (action != null) {
      // Perform the action
      def parsedParams = [:]
      if (params != null && params != "") {
        def jsonSlurper = new JsonSlurper()
        parsedParams = jsonSlurper.parseText(params) as Map
      }
      choose(uid, parsedParams)
    }
  }

  def choose(String uid, Map parsedParams) {
    PlayerAction action = actions[uid]

    //if (params != null) println("params: " + params)

    if (action != null) {
      def choiceArray = action.player.removeProperty("choices")

      action.execute(uid, parsedParams)

      //remove the head action now
      playerToActionQueue[action.player]?.poll()

      def nextAction = playerToActionQueue[action.player].peek()
      if (nextAction) {
        nextAction()

        // If there is more than one action, we need to start the AI on the next action
        if (action.player.getProperty("ai") == 1)
          ai.choose(action.player);
      }

      // Remove this round of action
      choiceArray.each { actions.remove(it.uid) }
    }
  }


  def size() {
    return actions.size()
  }

  def getActions(String pid) {
    return actions.findAll { a -> a.value.player.id == pid }
  }

  def addActionPropertyChangeListener(PropertyChangeListener listener) {
    actions.addPropertyChangeListener(listener)
  }

  def removeActionPropertyChangeListener(PropertyChangeListener listener) {
    actions.removePropertyChangeListener(listener)
  }

  def turnAIOff() {
    ai.off()
  }

  def turnAIOn() {
    ai.on()
  }

  /**
   * Each round the player might need to make a series of choices.
   * The PlayerAction hold one choice which associates with multiple actions.
   */
  class PlayerAction {

    Vertex player
    def eventTracker
    def idleTimer1
    def idleTimer2

    //player will choose only one of the choices... which means only one result closure should be executed
    def uidToAction = [:]
    def uidToName = [:]
    def uidToEvent = [:]

    def putResult(String uid, Closure result) {
      uidToAction[uid] = result
    }

    def putName(String uid, String name) {
      uidToName[uid] = name
    }

    def putEvent(String uid, def event) {
      uidToEvent[uid] = event
    }

    def execute(String uid, Object params) {
      uidToAction[uid](params)
      if (eventTracker) {
        def event = uidToEvent[uid]
        if (event) {
          eventTracker.track(event.name, event.data)
        }
      }

      /*
      if (idleTimer1 != null && idleTimer1.fired) {
          if (idleTimer1.player.tempTimer) {
              idleTimer1.player.timer = idleTimer1.player.tempTimer;
              idleTimer1.player.removeProperty("tempTimer");
          } else {
              idleTimer1.player.timer = ""
          }
      }
      */

      cancelIdleTimers()
    }

    // Stop this action's warn/drop countdown and take the "You will be dropped" timer off the
    // player's screen. Called when the action is answered, removed or cleared; without it a removed
    // action's countdown kept running and dropped a player who had answered everything since.
    def cancelIdleTimers() {
      if (idleTimer1) {
        idleTimer1.cancelled = true
        if (idleTimer1.timer) {
          idleTimer1.timer.cancel()
        }
        if (idleTimer1.player && idleTimer1.fired) {
          if (idleTimer1.player.timers) {
            idleTimer1.player.timers.remove("dropTimer")
            // TODO: Find out why modifying the timers map doesn't trigger a client update without the next line
            idleTimer1.player.lastupdated = System.currentTimeMillis()
          }
        }
      }

      if (idleTimer2 && idleTimer2.timer) {
        idleTimer2.timer.cancel()
      }
      idleTimer1 = null
      idleTimer2 = null
    }

    def getNameByUid(String uid) {
      return uidToName[uid]
    }
  }
}

class PlayerAI {
  @JsonIgnore
  PlayerActions playerActions

  @JsonIgnore
  def r = new Random()

  // A random one of the choices on screen, 1 to 4 seconds later.
  @JsonIgnore
  def defaultBehavior = { player ->
    def randomDelay = 1000 + r.nextInt(3000)
    // The pick is for these choices, not for whatever is offered after them.
    def offered = (player.getProperty("choices") ?: []).findAll { it instanceof Map }.collect { it.uid }
    try {
      pickLater(player, randomDelay) {
        // Only while the seat is still the computer's (its person may have come back), and only
        // among those choices that are still offered to the player.
        if (player.getProperty("ai") != 1) {
          return
        }
        def choices = (player.getProperty("choices") ?: []).findAll {
          it instanceof Map && offered.contains(it.uid) && playerActions.ownerOf(it.uid) == player.id.toString()
        }
        if (choices) {
          chooseFor(player, choices[r.nextInt(choices.size())])
        }
      }
    } catch (IllegalStateException e) {
      // The computer players are off and their timer is cancelled: the engine is being rebuilt (or
      // a.empty() replaced them).
      println "The computer's pick was not scheduled: " + e
    }
  }

  // Every pick waits on this one timer. A timer of its own per pick started a thread that lived
  // until the engine was rebuilt: 80 of them in a 12-round game with 6 computer players.
  // timers.cancel() cancels it with the others; the next pick then starts another (schedulePick).
  def timer = new BBTimer()

  // Picks scheduled and not made yet, by player id, so remove() can call them off.
  @JsonIgnore
  def pendingPicks = [:]

  // Is the AI behavior globally turned on?
  // Changed to default to true
  def isOn = true

  // We have the ability to assign custom AI behavior for each ai player
  // Map of Vertex player : Closure behavior
  def behaviors = [:]

  // (synchronized: a pick being scheduled sees the change, see schedulePick)
  synchronized def off() {
    this.isOn = false
  }

  synchronized def on() {
    this.isOn = true
  }

  def add(Vertex player, Closure behavior = defaultBehavior) {
    def newAi = false
    if (player.getProperty("ai") != 1) {
      newAi = true
    }

    player.setProperty("ai", 1)
    behaviors[player] = behavior

    if (newAi) {
      choose(player)
    }
    /*
    playerActionListener = { event ->
        if (event instanceof ObservableMap.PropertyUpdatedEvent) {
        println ("event.getKey() " + event.getKey())
            if (event.getKey() == player)
                choose(player)
        }
    } as PropertyChangeListener
    */
  }

  def remove(Vertex player) {
    // Remove the "ai" property
    player.removeProperty("ai")
    // The computer's picks not made yet are called off: the person decides from here.
    cancelPicks(player.id.toString())
    // If a custom behavior was defined, remove it
    if (behaviors.containsKey(player))
      behaviors.remove(player)
  }

  def choose(Vertex player) {
    if (!this.isOn)
      return

    if (behaviors.containsKey(player)) {
      behaviors[player](player)
    }
  }

  // Run pick on this AI's timer after delayMs, unless remove() calls it off first. A GroovyTimerTask
  // logs what pick throws: an error escaping a task would stop the timer, and every pick after it.
  def pickLater(Vertex player, long delayMs, Closure pick) {
    def id = player.id.toString()
    def task
    task = new GroovyTimerTask(closure: {
      forgetPick(id, task)
      pick()
    })
    schedulePick(id, task, delayMs)
  }

  // Schedules the pick and remembers it in one go (its task's forgetPick waits for this).
  // timers.cancel() (the console's "Stop every timer", an experiment's own call) cancels this timer
  // too: while the computer players are on, a new one takes over, or they would never answer again,
  // those standing in for departed participants included. They are off when the engine is being
  // rebuilt (ScriptBoard turns them off before it cancels the timers): then the pick throws, and no
  // timer starts in an engine on its way out.
  synchronized void schedulePick(String id, TimerTask task, long delayMs) {
    try {
      timer.schedule(task, delayMs)
    } catch (IllegalStateException cancelled) {
      if (!isOn) {
        throw cancelled
      }
      // The picks still remembered were dropped with the cancelled timer.
      pendingPicks.clear()
      timer = new BBTimer()
      timer.schedule(task, delayMs)
    }
    rememberPick(id, task)
  }

  // The computer makes this choice for the player, recorded first as an "aiChoice" event so the
  // data tells its choices from the participant's.
  def chooseFor(Vertex player, Map choice) {
    playerActions.eventTracker?.track("aiChoice", [playerId: player.id.toString(), choice: choice.name, choiceUID: choice.uid])
    playerActions.choose(choice.uid, null)
  }

  synchronized void rememberPick(String id, TimerTask task) {
    def tasks = pendingPicks[id]
    if (tasks == null) {
      tasks = []
      pendingPicks[id] = tasks
    }
    tasks << task
  }

  synchronized void forgetPick(String id, TimerTask task) {
    def tasks = pendingPicks[id]
    if (tasks != null) {
      tasks.remove(task)
      if (tasks.isEmpty()) {
        pendingPicks.remove(id)
      }
    }
  }

  synchronized void cancelPicks(String id) {
    pendingPicks.remove(id)?.each { it.cancel() }
  }
}

a = new PlayerActions(eventTracker: eventTracker, texts: bbText)

def makeChoice(String uid) {
  a.choose(uid)
}
