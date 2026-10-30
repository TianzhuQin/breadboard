// Tests for events.groovy: a player's listeners, and clearing them.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.6 target: no lambdas / method refs.

// How many listeners the scripts' event bus has for this player's event ("__player-<id>-<event>").
def listening(Vertex player, String event) {
  def handler = events.events["__player-" + player.id + "-" + event]
  return handler == null ? 0 : handler.closures.size()
}

test("clearListeners takes off all of a player's listeners, also after another player's one went") {
  def a = g.addPlayer("ev-a")
  def b = g.addPlayer("ev-b")
  def next = { v, data -> }
  [a, b].each { p ->
    p.on("f-next", next)
    p.on("chat", { v, data -> })
  }
  // a is done with the form: its listener comes off (the name used to go for everyone)
  assert a.off("f-next", next)
  b.clearListeners()
  assert listening(b, "f-next") == 0
  assert listening(b, "chat") == 0
  assert listening(a, "chat") == 1
}

test("clearListeners leaves alone a player whose id starts with this one's") {
  def p = g.addPlayer("ev-p")
  def p1 = g.addPlayer("ev-p-1")
  p.on("chat", { v, data -> })
  p1.on("chat", { v, data -> })
  p.clearListeners()
  assert listening(p, "chat") == 0
  assert listening(p1, "chat") == 1
}

test("clearAllPlayerListeners takes off every player's listeners") {
  def a = g.addPlayer("ev-all-a")
  def b = g.addPlayer("ev-all-b")
  def next = { v, data -> }
  a.on("f-next", next)
  b.on("f-next", next)
  a.on("chat", { v, data -> })
  a.off("f-next", next)
  clearAllPlayerListeners()
  assert listening(a, "chat") == 0
  assert listening(b, "f-next") == 0
}

test("a player's event reaches only that player's listeners, once") {
  def a = g.addPlayer("ev-once-a")
  def b = g.addPlayer("ev-once-b")
  def heard = []
  def heardByB = []
  a.once("ping", { v, data -> heard << data.n })
  // another player listening to the same event hears only their own
  b.on("ping", { v, data -> heardByB << data.n })
  events.emit("CustomEvent", [playerId: a.id, eventName: "ping", data: [n: 1]], [clientId: a.id])
  events.emit("CustomEvent", [playerId: a.id, eventName: "ping", data: [n: 2]], [clientId: a.id])
  assert heard == [1]
  assert heardByB == []
  assert listening(b, "ping") == 1
  events.emit("CustomEvent", [playerId: b.id, eventName: "ping", data: [n: 3]], [clientId: b.id])
  assert heardByB == [3]
  assert heard == [1]
}
