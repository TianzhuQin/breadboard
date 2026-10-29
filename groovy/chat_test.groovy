// Tests for chat.groovy: the listener a chat puts on a player comes off when its chat is switched off.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.6 target: no lambdas / method refs.

// How many listeners the scripts' event bus has for this player's event (events.groovy names them
// "__player-<id>-<event>").
def listening(Vertex player, String event) {
  def handler = events.events["__player-" + player.id + "-" + event]
  return handler == null ? 0 : handler.closures.size()
}

// What the participant's page sends with a chat message.
def say(Vertex player, String text, List recipients) {
  events.emit("CustomEvent", [playerId: player.id, eventName: "chat", data: [text: text, recipients: recipients]],
    [clientId: player.id])
}

test("switching a player's chat off takes its listener away") {
  def chat = new ChatManager()
  def a = g.addPlayer("chat-off-a")
  def b = g.addPlayer("chat-off-b")
  g.addEdge(a, b)
  chat.enableTextChat(a)
  assert listening(a, "chat") == 1
  chat.disableTextChat(a)
  assert listening(a, "chat") == 0
  assert !("chatState" in a.private)
}

test("chat switched on again handles every message once") {
  def chat = new ChatManager()
  def a = g.addPlayer("chat-again-a")
  def b = g.addPlayer("chat-again-b")
  chat.enableTextChat(a, [recipients: [b.id]])
  chat.enableTextChat(b, [recipients: [a.id]])
  chat.enableTextChat(a, [recipients: [b.id]])
  assert listening(a, "chat") == 1
  say(a, "hello", [b.id])
  assert a.private.chatState.messages*.text == ["hello"]
  assert b.private.chatState.messages*.text == ["hello"]
}

test("a chat whose state was taken away stops listening at its next message") {
  def chat = new ChatManager()
  def a = g.addPlayer("chat-gone-a")
  def b = g.addPlayer("chat-gone-b")
  chat.enableTextChat(a, [recipients: [b.id]])
  a.private.remove("chatState")
  say(a, "anyone?", [b.id])
  assert listening(a, "chat") == 0
}
