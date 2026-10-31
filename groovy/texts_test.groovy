// Tests for Breadboard's own words to participants in the engine (util.groovy's bbText): the waiting
// room and its countdown, refusals of tie changes, the idle drop timer and the ready-up sequence speak
// the language of the session's texts, which the steps choose with c.setDefaultLanguage: Chinese for
// zho, English for any other. They used to be English only, around a study written in Chinese.
// Run via:  bin/sbt8 "testOnly GroovyScriptTests"

// The steps chose this language for the session's texts: c stands in for them (ContentFetcher).
def chose(String code) {
  c = [languageCode: { -> code }]
}

def pilot(String prefix, Map hooks = [:]) {
  def instance = [hasStarted: false]
  def listener = [experimentInstance: instance, finish: { hooks.finished = true }]
  def ap = new Autopilot(g: g, a: a, gameListener: listener, eventTracker: null, texts: bbText, sentences: bbSentences,
      startGame: { hooks.started = (hooks.started ?: 0) + 1; instance.hasStarted = true })
  ap.isParticipant = { v -> v.id.toString().startsWith(prefix) }
  return ap
}

def arrive(Autopilot ap, String id) {
  def v = g.getVertex(id) ?: g.addPlayer(id)
  v._system.connected = true
  v._system.disconnectedAt = null
  ap.onConnection(id, true)
  return v
}

test("Breadboard's own words are English unless the steps chose Chinese") {
  assert bbText("waiting.countdown", [n: 1]) == "Starting in 1 second…"
  assert bbText("waiting.countdown", [n: 5]) == "Starting in 5 seconds…"
  chose("spa")
  assert bbText("waiting.countdown", [n: 5]) == "Starting in 5 seconds…"
  chose("zho")
  assert bbText("waiting.countdown", [n: 5]) == "5 秒后开始…"
  assert bbSentences("第一句。", "第二句。") == "第一句。第二句。"
  chose(null)
  assert bbText("waiting.notEnoughTitle") == "Not enough participants joined"
}

test("the autopilot's waiting room says how many are here in the session's language") {
  def english = pilot('tx-en-')
  english.waitingRoom([minPlayers: 3, waitSeconds: 60])
  arrive(english, 'tx-en-1')
  def v = arrive(english, 'tx-en-2')
  assert v.text == "<h3>Waiting for other participants</h3><p>2 joined so far. The session starts when 3 people are here.</p>" +
      "<p>Please keep this page open.</p>"
  english.stop()

  chose("zho")
  def chinese = pilot('tx-zh-')
  chinese.waitingRoom([minPlayers: 3, maxPlayers: 8, waitSeconds: 60])
  def w = arrive(chinese, 'tx-zh-1')
  assert w.text == "<h3>正在等待其他参与者</h3><p>目前已有 1 人加入。人数达到 3–8 人后，实验就会开始。</p><p>请保持这个页面打开。</p>"
  chinese.stop()
}

test("the countdown before the start is in the session's language") {
  chose("zho")
  def ap = pilot('tx-cd-')
  ap.waitingRoom([minPlayers: 2, maxPlayers: 2, countdownSeconds: 5])
  arrive(ap, 'tx-cd-1')
  def v = arrive(ap, 'tx-cd-2')
  assert v.text == "<h3>5 秒后开始…</h3>"
  ap.stop()
}

test.async("a session given up for too few people says so in the session's language", 4000) { done ->
  chose("zho")
  def hooks = [:]
  def ap = pilot('tx-give-', hooks)
  ap.waitingRoom([minPlayers: 3, maxWaitSeconds: 0.3])
  def v = arrive(ap, 'tx-give-1')
  timers.newTimer().runAfter(900) {
    done {
      assert hooks.finished == true
      assert v.text == "<h3>参与者人数不够</h3><p>感谢你的等待。本场实验没能开始。</p>"
      ap.stop()
    }
  }
}

test("a refused tie change is told in the session's language and recorded in English") {
  chose("zho")
  def me = g.addPlayer('tx-ee-me'), x = g.addPlayer('tx-ee-x'), y = g.addPlayer('tx-ee-y')
  g.addEdge(me, x)
  g.addEdge(me, y)
  def logged = []
  def sent = []
  def oldTracker = edgeEdits.eventTracker
  edgeEdits.eventTracker = [track: { name, data -> logged << data.collectEntries { [it.name, it.value] } }]
  def listener = { id, name, args -> if (name == "bb:edge-edit-result") sent << args[0] }
  events.on("__send-event", listener)
  try {
    edgeEdits.allow(me, [add: true, remove: true, scope: "visible", validate: { p, op, a, b -> op == "add" ? "实验自己的理由" : true }])
    // a tie that isn't there, then the experiment's own reason, then a change from a turn that is over
    edgeEdits.handle(me, [op: "remove", a: g.clientIdOf(x), b: g.clientIdOf(y)])
    edgeEdits.handle(me, [op: "add", a: g.clientIdOf(x), b: g.clientIdOf(y)])
    edgeEdits.handle(me, [op: "remove", a: g.clientIdOf(me), b: g.clientIdOf(x), turn: -1])
  } finally {
    events.off("__send-event", listener)
    edgeEdits.eventTracker = oldTracker
    edgeEdits.disallow(me)
  }
  assert sent*.reason == ["他们之间没有连接。", "实验自己的理由", "时间已经到了，这个修改没有完成。"]
  assert logged*.reason == ["They are not connected.", "实验自己的理由", "Time was up for that change, so it wasn't made."]
  // a researcher's edit is refused in English, as the console speaks it
  try {
    edgeEdits.adminEdit("remove", 'tx-ee-x', 'tx-ee-y')
    assert false : "no tie there to cut"
  } catch (IllegalStateException refused) {
    assert refused.message == "They are not connected."
  }
}

test("the built-in timers and the ready-up sequence speak the session's language") {
  assert a.texts("timer.dropped") == "You will be dropped in: "
  chose("zho")
  assert a.texts("timer.dropped") == "被移出前还剩："
  def ready = new ReadyUpSequence()
  assert ready.fetchContent(ready.readyButtonContent) == "准备好了"
  assert ready.fetchContent(ready.waitingContent) == "游戏马上开始。请等待其他玩家。"
  // the experiment's own words stay its own
  def own = new ReadyUpSequence([readyButtonContent: [content: "Go"]])
  assert own.fetchContent(own.readyButtonContent) == "Go"
}

test("the ready-up sequence's defaults keep the words an experiment puts into them") {
  chose("zho")
  def ready = new ReadyUpSequence()
  ready.readyButtonContent.content = "Go"
  ready.waitingContent.content = "Hold on"
  // (a text of the experiment's, as c.get gives it)
  c = [languageCode: { -> "zho" }, readyPrompt: "按下按钮"]
  ready.readyContent.contentKey = "readyPrompt"
  assert ready.fetchContent(ready.readyButtonContent) == "Go"
  assert ready.fetchContent(ready.waitingContent) == "Hold on"
  assert ready.fetchContent(ready.readyContent) == "按下按钮"
  // and the ones it leaves alone are Breadboard's own, in the session's language
  assert ready.fetchContent(ready.pendingContent) == "游戏马上开始。出现“准备好了”按钮时，按下它开始任务。"
}
