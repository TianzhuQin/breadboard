import com.tinkerpop.blueprints.Vertex

final CUSTOM_EVENT = "CustomEvent"
final PLAYER_ACTION_PROP = "eventName"
final PLAYER_DATA_PROP = "data"
final PLAYER_ID_PROP = "playerId"
final SEND_EVENT = "__send-event"

// Set to true to print every player.on / off / send on the server's console (when looking into a problem).
logPlayerEvents = false

def makePlayerEventHash (String id, String eventName) {
  return "__player-" + id + "-" + eventName
}

log = { Object ...vals ->
  if (logPlayerEvents) {
    println vals.collect{ "${it}" }.join(" ")
  }
}

Vertex.metaClass.on = { String eventName, Closure cb ->
  log("vertex.on", delegate.id, eventName)
  try {
    def globalEventName = makePlayerEventHash(delegate.id, eventName)
    events.onForPlayer(delegate.id.toString(), globalEventName, cb)
  } catch (Exception e) {
    e.printStackTrace()
  }
}
Vertex.metaClass.once = { String eventName, Closure cb ->
  log("vertex.once", delegate.id, eventName)
  def internalClosure
  internalClosure = { Vertex v, Object ...data ->
    log("vertex.once callback", v.id)
    v.off(eventName, internalClosure)
    cb(v, *data)
  }
  delegate.on(eventName, internalClosure)
}

// Method overloads
Vertex.metaClass.off = { String eventName, Closure cb ->
  wasRemoved = events.off(makePlayerEventHash(delegate.id, eventName), cb)
  log("vertex.off 1", delegate.id, eventName, wasRemoved)
  return wasRemoved
}
Vertex.metaClass.off << { String eventName ->
  wasRemoved = events.off(makePlayerEventHash(delegate.id, eventName))
  log("vertex.off 2", delegate.id, eventName, wasRemoved)
  return wasRemoved
}
Vertex.metaClass.send = { String eventName, Object ...data ->
  log("vertex.send", delegate.id, eventName)
  events.emit(SEND_EVENT, delegate.id, eventName, data)
}
// Every listener on this player's events, whoever put it there (a form, the chat, the experiment).
// It used to go by a set of event names that every vertex shared, which off() took names out of: once
// one player's form was done, everyone else's listeners of that form were missed.
Vertex.metaClass.clearListeners = {
  log("vertex.clearListeners", delegate.id)
  events.offPlayer(delegate.id.toString(), makePlayerEventHash(delegate.id, ""))
}

clearAllPlayerListeners = {
  log("clearing all player listeners")
  g.V.each{
    it.clearListeners()
  }
}


/**
 * All events in breadboard use the global event bus to pass events between the 
 * ScriptBoard side and the groovy scripting scope. Player scoped events are 
 * routed using a simple hash of the player id and the event name. Player scoped
 * events are also passed in the player vertex as an argument.
 */ 
events.on(CUSTOM_EVENT, { Map params, Map clientData ->
  try {
    if (PLAYER_ID_PROP in params && PLAYER_ACTION_PROP in params) {
      def playerId = params[PLAYER_ID_PROP]
      if (clientData.clientId != playerId) {
        println "Hacking attempt thwarted! Player " + clientData.clientId + " tried to impersonate " + playerId
        return
      }
      def player = g.getVertex(playerId)
      if (player != null) {
        def globalEventName = makePlayerEventHash(player.id, params[PLAYER_ACTION_PROP])
        events.emit(globalEventName, player, params[PLAYER_DATA_PROP])
      }
    }
  } catch (Exception e) {
    println "custom event exception"
    e.printStackTrace()
  }
})