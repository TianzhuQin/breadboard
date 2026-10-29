// Tests for form.groovy: the listeners a form puts on its players come off when they are done with it.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.6 target: no lambdas / method refs.

// How many listeners the scripts' event bus has for this player's event (events.groovy names them
// "__player-<id>-<event>").
def listening(Vertex player, String event) {
  def handler = events.events["__player-" + player.id + "-" + event]
  return handler == null ? 0 : handler.closures.size()
}

// What the participant's page sends when they press Next, or Done on the last page (Form.vue).
def pressNext(Vertex player, String form) {
  events.emit("CustomEvent", [playerId: player.id, eventName: "f-next", data: [name: form, results: [:]]], [clientId: player.id])
}

def onePageForm(String name) {
  def form = new Form([name: name])
  form.addPage([title: "Consent", blocks: [[type: "html", content: "Welcome"]]])
  return form
}

def navigation = ["f-next", "f-prev", "f-seek", "f-fetch"]

test("a player who finishes a form has none of its listeners left") {
  def p = g.addPlayer("form-done")
  def form = onePageForm("consent")
  def done = []
  form.onDone { v -> done << v.id }
  form.addPlayer(p)
  form.start()
  navigation.each { assert listening(p, it) == 1 : it }
  pressNext(p, "consent")
  assert done == ["form-done"]
  navigation.each { assert listening(p, it) == 0 : "${it} is still listened for" }
  assert !("forms" in p.private)
}

test("finishing one form leaves the listeners of another one the player still has") {
  def p = g.addPlayer("form-two")
  def consent = onePageForm("consent")
  def survey = onePageForm("survey")
  def done = []
  consent.onDone { v -> done << "consent" }
  survey.onDone { v -> done << "survey" }
  [consent, survey].each { it.addPlayer(p); it.start() }
  assert listening(p, "f-next") == 2
  pressNext(p, "consent")
  assert done == ["consent"]
  navigation.each { assert listening(p, it) == 1 : it }
  assert p.private.forms.keySet() == ["survey"] as Set
  pressNext(p, "survey")
  assert done == ["consent", "survey"]
  navigation.each { assert listening(p, it) == 0 : it }
}

test("a form started again listens once, so Next moves one page on") {
  def p = g.addPlayer("form-again")
  def form = new Form([name: "twice"])
  form.addPage([title: "One", blocks: [[type: "html", content: "1"]]])
  form.addPage([title: "Two", blocks: [[type: "html", content: "2"]]])
  form.addPlayer(p)
  form.start()
  form.start()
  navigation.each { assert listening(p, it) == 1 : it }
  pressNext(p, "twice")
  assert p.private.forms.twice.location.index == 1
}

test("ending a form someone already finished doesn't finish it for them again") {
  def p = g.addPlayer("form-end-again")
  def consent = onePageForm("consent")
  def survey = onePageForm("survey")
  def done = 0
  consent.onDone { v -> done++ }
  [consent, survey].each { it.addPlayer(p); it.start() }
  pressNext(p, "consent")
  consent.end()
  assert done == 1
  assert p.private.forms.keySet() == ["survey"] as Set
  navigation.each { assert listening(p, it) == 1 : it }
}

test("ending a form takes its listeners off everyone still in it") {
  def p = g.addPlayer("form-end-a")
  def q = g.addPlayer("form-end-b")
  def form = onePageForm("ended")
  form.addPlayers([p, q])
  form.start()
  form.end()
  [p, q].each { v -> navigation.each { assert listening(v, it) == 0 : "${v.id} ${it}" } }
}
