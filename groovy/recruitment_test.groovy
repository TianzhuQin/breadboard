// Tests for recruitment.groovy (the prolific/mturk register + complete helpers), written as Groovy
// and registered through the `test` DSL bound by ScriptTestHarness. Production's ScriptLoader skips
// any file ending in `_test.groovy`, so this never loads into a real experiment. Run via:
//   sbt -java-home "$JAVA8_HOME" "testOnly GroovyScriptTests"
//
//   test("name") { ... }                            -> synchronous
//   test.async("name", timeoutMs) { done -> ... }   -> async; signal completion with done()
//   test.skip("name") { ... }                       -> reported as ignored, not run
//
// `g`, `a`, `events` etc. are the same engine bindings the platform scripts see; `recruitment` and
// `RecruitmentSource` come from recruitment.groovy (a core script, already loaded). `_system` reads
// (`v._system...`) and `v.id` are safe; only `v.<gremlin-pipe-method>` reads (e.g. `v.step`) throw.
//
// Groovy 1.8.6 target: no closure->functional-interface coercion, no lambdas / `::` refs.

// --- RecruitmentSource: the wire contract --------------------------------------------------------

test("RecruitmentSource exposes the exact lowercase strings the frontend compares against") {
  // Replaced the old enum with a plain string map precisely so these values are unambiguous on the
  // wire (BBMain.vue checks recruitment.source === 'prolific' / 'mturk').
  assert RecruitmentSource.PROLIFIC == 'prolific'
  assert RecruitmentSource.MTURK == 'mturk'
}

// --- register: stamps the source onto the player -------------------------------------------------

test("registerProlific stamps _system.recruitment.source = 'prolific'") {
  def v = g.addPlayer('rec-prolific-1')
  recruitment.registerProlific(v)
  assert v._system.recruitment.source == 'prolific'
}

test("registerMturk stamps _system.recruitment.source = 'mturk'") {
  def v = g.addPlayer('rec-mturk-1')
  recruitment.registerMturk(v)
  assert v._system.recruitment.source == 'mturk'
}

// --- completeProlific ----------------------------------------------------------------------------

test("completeProlific marks the player completed and records its completion code") {
  def v = g.addPlayer('rec-prolific-2')
  recruitment.registerProlific(v)
  recruitment.completeProlific(v, [completionCode: 'CODE123', message: 'Thanks for playing'])
  assert v._system.recruitment.completed == true
  assert v._system.recruitment.completionCode == 'CODE123'
  assert v._system.recruitment.message == 'Thanks for playing'
  assert v._system.recruitment.source == 'prolific'   // unchanged by completion
}

test("completeProlific requires a completion code") {
  def v = g.addPlayer('rec-prolific-3')
  recruitment.registerProlific(v)
  def threw = false
  try { recruitment.completeProlific(v, [:]) } catch (Exception e) { threw = true }
  assert threw : 'completeProlific without a completion code must throw'
}

test("completeProlific does nothing for someone who didn't come from Prolific, and logs why") {
  // (it used to throw, which stopped the step that called it, and with it the Prolific participants'
  // end: the code copied from the console, called for everyone, did that in a lab session)
  def tracked = []
  eventTracker = [track: { name, data -> tracked << name }]
  def log = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("application")
  def logged = new ch.qos.logback.core.read.ListAppender()
  logged.start()
  log.addAppender(logged)
  def level = log.level
  log.level = ch.qos.logback.classic.Level.INFO
  def mturk = g.addPlayer('rec-prolific-4')
  recruitment.registerMturk(mturk)
  def signedIn = g.addPlayer('rec-prolific-4b')   // from the session's sign-in link: no source at all
  def prolific = g.addPlayer('rec-prolific-4c')
  recruitment.registerProlific(prolific)
  try {
    assert recruitment.completeProlific(mturk, [completionCode: 'X']) == false
    assert recruitment.completeProlific(signedIn, [completionCode: 'X']) == false
    assert recruitment.completeProlific(prolific, [completionCode: 'X']) == true
  } finally {
    log.detachAppender(logged)
    log.level = level
  }
  [mturk, signedIn].each { v ->
    assert !v._system.recruitment.completed
    assert v._system.recruitment.completionCode == null
    assert logged.list.any { it.formattedMessage.contains(v.id) && it.formattedMessage.contains("didn't come from Prolific") }
  }
  assert mturk._system.recruitment.source == 'mturk'
  assert prolific._system.recruitment.completionCode == 'X'
  assert tracked == ['prolificComplete']
}

// --- completeProlific: the completion in the session's data --------------------------------------
// The harness's own eventTracker records nothing, so a recording double stands in for the binding
// (recruitment.groovy reads it when completeProlific runs).

test("completeProlific records a prolificComplete event with the player, the code and the bonus") {
  def tracked = []
  eventTracker = [track: { name, data -> tracked << [name: name, data: data] }]
  def v = g.addPlayer('rec-prolific-5')
  recruitment.registerProlific(v)
  recruitment.completeProlific(v, [completionCode: 'CODE5', bonus: 0.5])
  assert tracked.size() == 1
  assert tracked[0].name == 'prolificComplete'
  assert tracked[0].data.keySet() as List == ['player', 'completionCode', 'bonus']
  assert tracked[0].data.player == 'rec-prolific-5'
  assert tracked[0].data.completionCode == 'CODE5'
  assert tracked[0].data.bonus == 0.5
}

test("completeProlific leaves the bonus out of the event when there is none") {
  def tracked = []
  eventTracker = [track: { name, data -> tracked << data }]
  def v = g.addPlayer('rec-prolific-6')
  recruitment.registerProlific(v)
  recruitment.completeProlific(v, [completionCode: 'CODE6'])
  assert tracked.size() == 1
  assert tracked[0].keySet() as List == ['player', 'completionCode']
}

test("completeProlific completes the player even when the event can't be recorded") {
  eventTracker = [track: { name, data -> throw new RuntimeException('the database is unreachable') }]
  def v = g.addPlayer('rec-prolific-7')
  recruitment.registerProlific(v)
  recruitment.completeProlific(v, [completionCode: 'CODE7'])
  assert v._system.recruitment.completed == true
  assert v._system.recruitment.completionCode == 'CODE7'

  // nor without an event tracker at all
  eventTracker = null
  def w = g.addPlayer('rec-prolific-8')
  recruitment.registerProlific(w)
  recruitment.completeProlific(w, [completionCode: 'CODE8'])
  assert w._system.recruitment.completionCode == 'CODE8'
}

// --- completeMturk -------------------------------------------------------------------------------

test("completeMturk marks the player completed and records its bonus") {
  def v = g.addPlayer('rec-mturk-2')
  recruitment.registerMturk(v)
  recruitment.completeMturk(v, [bonus: 1.5, reason: 'Good work'])
  assert v._system.recruitment.completed == true
  assert v._system.recruitment.bonus == 1.5
  assert v._system.recruitment.reason == 'Good work'
}

test("completeMturk requires a bonus") {
  def v = g.addPlayer('rec-mturk-3')
  recruitment.registerMturk(v)
  def threw = false
  try { recruitment.completeMturk(v, [:]) } catch (Exception e) { threw = true }
  assert threw : 'completeMturk without a bonus must throw'
}

test("completeMturk refuses a non-mturk player") {
  def v = g.addPlayer('rec-mturk-4')
  recruitment.registerProlific(v)
  def threw = false
  try { recruitment.completeMturk(v, [bonus: 1.0]) } catch (Exception e) { threw = true }
  assert threw : 'completeMturk on a prolific player must throw'
}

// --- the console's completion code (Recruitment → When a participant finishes) --------------------
// The code the console gives to paste into a step (completionSnippet in the console's prolific.ts),
// with `p` for the participant: it completes those from Prolific and leaves everyone else alone,
// where completeProlific alone threw for them and so stopped the step.

test("the console's completion code completes Prolific participants and leaves everyone else alone") {
  eventTracker = null
  def finish = { p ->
    // (as the console gives it)
    if (p._system?.recruitment?.source == "prolific") {
      recruitment.completeProlific(p, [
        completionCode: 'C7K2M9QX',
        message: 'Thank you for taking part!'
      ])
    }
  }
  def fromProlific = g.addPlayer('rec-snippet-1')
  recruitment.registerProlific(fromProlific)
  def fromMturk = g.addPlayer('rec-snippet-2')
  recruitment.registerMturk(fromMturk)
  def signedIn = g.addPlayer('rec-snippet-3')
  def computer = g.addAI(a, 1, { player -> })[0]
  [fromProlific, fromMturk, signedIn, computer].each { finish(it) }
  assert fromProlific._system.recruitment.completed == true
  assert fromProlific._system.recruitment.completionCode == 'C7K2M9QX'
  assert !fromMturk._system.recruitment.completed
  assert signedIn._system?.recruitment == null
  assert computer._system?.recruitment == null
}
