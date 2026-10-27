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

test("onEdit replaces applying the edit") {
  def n = triangle('ee-prop')
  def proposals = []
  edgeEdits.allow(n.me, [remove: true, onEdit: { p, op, a, b -> proposals << [op, a.id, b.id] }])
  edit(n.me, "remove", n.x, n.y)
  assert g.hasEdge(n.x, n.y)
  assert proposals == [["remove", n.x.id, n.y.id]]
  edgeEdits.disallow(n.me)
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
