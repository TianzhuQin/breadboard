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

test("completeProlific refuses a non-prolific player") {
  def v = g.addPlayer('rec-prolific-4')
  recruitment.registerMturk(v)
  def threw = false
  try { recruitment.completeProlific(v, [completionCode: 'X']) } catch (Exception e) { threw = true }
  assert threw : 'completeProlific on an mturk player must throw'
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
