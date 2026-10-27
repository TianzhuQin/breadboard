// Tests for edge_edits.groovy: participants and researchers editing ties.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Each test builds its own small graph with unique ids:
//   me - x, me - y, x - y (a tie among my neighbors), y - z (z is beyond my view)

def triangle(String prefix) {
  def me = g.addPlayer(prefix + '-me')
  def x = g.addPlayer(prefix + '-x')
  def y = g.addPlayer(prefix + '-y')
  def z = g.addPlayer(prefix + '-z')
  g.addEdge(me, x)
  g.addEdge(me, y)
  g.addEdge(x, y)
  g.addEdge(y, z)
  return [me: me, x: x, y: y, z: z]
}

def edit(Vertex player, String op, Vertex a, Vertex b) {
  edgeEdits.handle(player, [op: op, a: g.clientIdOf(a), b: g.clientIdOf(b)])
}

test("a participant can cut a tie between two of their neighbors") {
  def n = triangle('ee-cut')
  edgeEdits.allow(n.me, [add: false, remove: true, maxRemoves: 1])
  edit(n.me, "remove", n.x, n.y)
  assert !g.hasEdge(n.x, n.y)
  assert n.me.private.edgeEdits.remainingRemoves == 0
  assert n.me.private.edgeEdits.remove == false   // used up
  edgeEdits.disallow(n.me)
}

test("amongNeighbors does not let a participant cut their own tie") {
  def n = triangle('ee-own')
  edgeEdits.allow(n.me, [remove: true])
  edit(n.me, "remove", n.me, n.x)
  assert g.hasEdge(n.me, n.x)
  edgeEdits.disallow(n.me)
}

test("people beyond the participant's view are out of reach") {
  def n = triangle('ee-far')
  edgeEdits.allow(n.me, [scope: "visible", remove: true])
  edit(n.me, "remove", n.y, n.z)
  assert g.hasEdge(n.y, n.z)
  edgeEdits.disallow(n.me)
}

test("a participant can tie two unconnected neighbors, up to maxAdds") {
  def n = triangle('ee-add')
  def w = g.addPlayer('ee-add-w')
  g.addEdge(n.me, w)
  g.removeConnectedEdge(n.x, n.y)
  edgeEdits.allow(n.me, [add: true, remove: false, maxAdds: 1])
  edit(n.me, "add", n.x, n.y)
  assert g.hasEdge(n.x, n.y)
  edit(n.me, "add", n.x, w)
  assert !g.hasEdge(n.x, w) : "a second add went through despite maxAdds: 1"
  edgeEdits.disallow(n.me)
}

test("validate can refuse an edit") {
  def n = triangle('ee-val')
  edgeEdits.allow(n.me, [remove: true, validate: { p, op, a, b -> "Each person must keep two ties." }])
  edit(n.me, "remove", n.x, n.y)
  assert g.hasEdge(n.x, n.y)
  edgeEdits.disallow(n.me)
}

test("candidates limit edits to the listed moves and are published with aliases") {
  def n = triangle('ee-cand')
  edgeEdits.allow(n.me, [remove: true, candidates: { p ->
    [[op: "remove", a: n.x, b: n.y, available: false, reason: "Would leave someone with one tie."]]
  }])
  def published = n.me.private.edgeEdits.candidates
  assert published.size() == 1
  assert published[0].a == g.clientIdOf(n.x)
  assert published[0].a != n.x.id : "a real id was sent to the client"
  assert published[0].available == false
  edit(n.me, "remove", n.x, n.y)
  assert g.hasEdge(n.x, n.y) : "an unavailable candidate was applied"
  edgeEdits.disallow(n.me)
}

test("in candidates the player's own vertex keeps the id their browser knows it by") {
  def n = triangle('ee-own')
  edgeEdits.allow(n.me, [remove: true, scope: "own", candidates: { p ->
    [[op: "remove", a: n.me, b: n.x]]
  }])
  def published = n.me.private.edgeEdits.candidates
  assert published[0].a == n.me.id
  assert published[0].b == g.clientIdOf(n.x)
  edgeEdits.disallow(n.me)
}

test("onEdit replaces applying the edit") {
  def n = triangle('ee-prop')
  def proposals = []
  edgeEdits.allow(n.me, [remove: true, onEdit: { p, op, a, b -> proposals << [op, a.id, b.id] }])
  edit(n.me, "remove", n.x, n.y)
  assert g.hasEdge(n.x, n.y)
  assert proposals == [["remove", n.x.id, n.y.id]]
  edgeEdits.disallow(n.me)
}

// The results sent to the player's browser with player.send (ScriptBoard delivers "__send-event").
def sentResults(Closure body) {
  def sent = []
  def listener = { id, name, args -> if (name == "bb:edge-edit-result") sent << args[0] }
  events.on("__send-event", listener)
  try {
    body()
  } finally {
    events.off("__send-event", listener)
  }
  return sent
}

test("the result says whether the edit was applied or only handed to onEdit") {
  def n = triangle('ee-res')
  def m = triangle('ee-res2')
  edgeEdits.allow(n.me, [remove: true])
  edgeEdits.allow(m.me, [remove: true, onEdit: { p, op, a, b -> }])
  def sent = sentResults {
    edit(n.me, "remove", n.x, n.y)
    edit(m.me, "remove", m.x, m.y)
    edit(n.me, "remove", n.me, n.x)   // refused: own tie
  }
  assert sent.size() == 3
  assert sent[0].ok == true && sent[0].applied == true
  assert sent[1].ok == true && sent[1].applied == false
  assert sent[2].ok == false && sent[2].applied == false
  edgeEdits.disallow(n.me)
  edgeEdits.disallow(m.me)
}

test("ties hidden by the default visibility only bring a warning, nothing else changes") {
  def n = triangle('ee-ego')
  g.setVisibility("ego")
  edgeEdits.allow(n.me, [scope: "amongNeighbors", remove: true])
  edgeEdits.allow(n.x, [scope: "visible", remove: true])
  assert edgeEdits.warnedTiesHidden
  assert n.me.private.edgeEdits.enabled == true
  edit(n.me, "remove", n.x, n.y)
  assert !g.hasEdge(n.x, n.y)
  edgeEdits.disallow(n.me)
  edgeEdits.disallow(n.x)

  def other = new EdgeEdits(g: g, editEvent: "bb:edge-edit", resultEvent: "bb:edge-edit-result")
  g.setVisibility("egoTies")
  try {
    other.allow(n.y, [scope: "amongNeighbors", remove: true])
    assert !other.warnedTiesHidden
  } finally {
    g.setVisibility("ego")
    other.disallow(n.y)
  }
}

test("edits arrive through the client event path, and only from the player themselves") {
  def n = triangle('ee-bus')
  edgeEdits.allow(n.me, [remove: true])
  def payload = [op: "remove", a: g.clientIdOf(n.x), b: g.clientIdOf(n.y)]
  // Someone else's socket claiming to be n.me is ignored by events.groovy.
  events.emit("CustomEvent", [playerId: n.me.id, eventName: "bb:edge-edit", data: payload], [clientId: n.z.id])
  assert g.hasEdge(n.x, n.y)
  events.emit("CustomEvent", [playerId: n.me.id, eventName: "bb:edge-edit", data: payload], [clientId: n.me.id])
  assert !g.hasEdge(n.x, n.y)
  edgeEdits.disallow(n.me)
}

test("every edit is logged with the acting player") {
  def n = triangle('ee-log')
  def logged = []
  def oldTracker = edgeEdits.eventTracker
  edgeEdits.eventTracker = [track: { name, data -> logged << [name: name, data: data.collectEntries { [it.name, it.value] }] }]
  try {
    edgeEdits.allow(n.me, [remove: true])
    edit(n.me, "remove", n.me, n.x)   // refused: own tie
    edit(n.me, "remove", n.x, n.y)    // accepted
    def edits = logged.findAll { it.name == "edgeEdit" }*.data
    assert edits.size() == 2
    assert edits[0].actor == n.me.id && edits[0].accepted == "false"
    assert edits[1].actor == n.me.id && edits[1].accepted == "true"
    assert [edits[1].playerId1, edits[1].playerId2].toSet() == [n.x.id, n.y.id].toSet()
  } finally {
    edgeEdits.eventTracker = oldTracker
    edgeEdits.disallow(n.me)
  }
}

test("a researcher can add and remove ties from the admin") {
  def n = triangle('ee-admin')
  edgeEdits.adminEdit("add", n.x.id, n.z.id)
  assert g.hasEdge(n.x, n.z)
  edgeEdits.adminEdit("remove", n.x.id, n.z.id)
  assert !g.hasEdge(n.x, n.z)
}

test("the page is told when it cannot see the ties among neighbors") {
  def n = triangle('ee-hidden')
  def before = models.ClientView.getVisibility()
  try {
    g.setVisibility("ego")
    edgeEdits.allow(n.me, [add: true, scope: "own"])
    assert n.me.private.edgeEdits.tiesHidden == true
    g.setVisibility("egoTies")
    edgeEdits.publish(n.me)
    assert n.me.private.edgeEdits.tiesHidden == false
  } finally {
    g.setVisibility(before == models.ClientView.Visibility.EGO_TIES ? "egoTies" : "ego")
    edgeEdits.disallow(n.me)
  }
}
