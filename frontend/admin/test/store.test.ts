import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  admin, graph, actions, experiments, engineInUse, engineElsewhere, reloadBlocked, runBlocked, fileStepsWait, connection, NOT_CONNECTED, ENGINE_SETTLE_MS,
  __receive,
} from '../src/store/admin'

const exp = (id: number, name: string) => ({ id, name, uid: 'u' + id, fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 })

describe('admin store', () => {
  beforeEach(() => {
    actions.clearOutput()
    graph.clear()
  })

  it('replaces the whole picture on each User JSON push, so a deleted experiment disappears', () => {
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'B'), exp(2, 'a')], currentScript: '' }, experiment: exp(1, 'B'), experimentInstance: '' })
    expect(admin.experiment!.id).toBe(1)
    expect(experiments.value.map(e => e.name)).toEqual(['a', 'B'])
    expect(admin.instance).toBe(null)
    // the server omits "experiment" when nothing is selected
    __receive({ user: { email: 'r@lab', experiments: [exp(2, 'a')], currentScript: '' }, experimentInstance: '' })
    expect(admin.experiment).toBe(null)
    expect(experiments.value.length).toBe(1)
    expect(admin.ready).toBe(true)
  })

  it('says what each frame answers, or where it came from', () => {
    actions.clearOutput()
    __receive({ error: 'The game has started already.', request: 'RunGame' })
    __receive({ error: '', output: 'a = 1\n\n==>1', request: 'SendScript' })
    __receive({ error: 'Caught error: boom', source: 'OnJoinStep' })
    __receive({ output: 'hello', source: 'OnLeaveStep', request: 'SendStep' })
    __receive({ output: 'plain' })
    expect(admin.output.map(o => o.source)).toEqual(['RunGame', 'SendScript', 'OnJoinStep', 'OnLeaveStep', undefined])
    // only an error naming its request is that request's refusal
    expect(admin.requestError).toMatchObject({ request: 'RunGame', text: 'The game has started already.' })
  })

  it('keeps the step an engine error happened in, when the server names it', () => {
    __receive({ error: 'Caught error: boom\n', source: 'roundStep', step: 'RoundStep' })
    __receive({ error: 'Caught error: boom\n', source: 'OnJoinStep' })
    expect(admin.output.map(o => o.step)).toEqual(['RoundStep', undefined])
  })

  it('keeps every output and error, and counts errors not yet seen', () => {
    __receive({ error: '', output: 'x\n\n==>1' })
    __receive({ error: 'Caught error: boom\n' })
    __receive({ error: '', output: 'y\n\n==>2' })
    expect(admin.output.map(o => o.kind)).toEqual(['output', 'error', 'output'])
    expect(admin.unseenErrors).toBe(1)
    actions.markErrorsSeen()
    expect(admin.unseenErrors).toBe(0)
  })

  it('follows the engine reloading and empties the network', async () => {
    __receive({ action: 'addNode', id: 'p1' })
    expect(graph.nodes.size).toBe(1)
    __receive({ notify: { ScriptEngineReloading: 1 } })
    expect(admin.engine).toBe('loading')
    expect(graph.nodes.size).toBe(0)
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    expect(admin.engine).toBe('loaded')
  })

  it('collects live events and starts again for another session', () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: { id: 5, name: 's', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] } })
    const before = Date.now()
    __receive({ event: { id: 1, name: 'clientLogIn', datetime: '2026-10-27T10:00:00.000Z', data: { clientId: 'p1' } } })
    expect(admin.events.length).toBe(1)
    // with when it arrived here (to tell it from what an older User JSON said)
    expect(admin.events[0]).toMatchObject({ id: 1, name: 'clientLogIn', receivedAt: expect.any(Number) })
    expect(admin.events[0].receivedAt).toBeGreaterThanOrEqual(before)
    // same session again: the feed stays
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: { id: 5, name: 's', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] } })
    expect(admin.events.length).toBe(1)
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: { id: 6, name: 't', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] } })
    expect(admin.events.length).toBe(0)
  })

  it('remembers the outcome of a tie edit', () => {
    __receive({ tieEdit: { ok: false, op: 'add', a: 'p1', b: 'p2', reason: 'They are already connected.' } })
    expect(admin.lastTieEdit!.reason).toBe('They are already connected.')
  })

  it('keeps only the selected session\'s live events (every console gets every session\'s)', () => {
    const inst = (id: number, status: string) => ({ id, name: 's' + id, status, creationTime: '', hitId: '', hits: [], data: [] })
    const ev = (extra: Record<string, any> = {}) => ({ event: { id: 1, name: 'clientLogIn', datetime: '2026-10-27T10:00:00.000Z', data: { clientId: 'p1' }, ...extra } })
    // nothing selected: nothing is ours
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    __receive(ev({ experimentInstanceId: 5 }))
    __receive(ev())
    expect(admin.events.length).toBe(0)
    // a stopped session is selected: another researcher's live events stay out of its feed
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: inst(5, 'STOPPED') })
    __receive(ev({ experimentInstanceId: 9 }))
    __receive(ev())
    expect(admin.events.length).toBe(0)
    // the server says the event is this session's
    __receive(ev({ experimentInstanceId: 5 }))
    expect(admin.events.length).toBe(1)
    __receive(ev({ experimentInstanceId: '5' }))
    // recorded while the engine ran no session
    __receive(ev({ experimentInstanceId: null }))
    expect(admin.events.length).toBe(2)
    // an older server sends no session id: taken as ours only while ours is the running one
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: inst(5, 'RUNNING') })
    __receive(ev())
    expect(admin.events.length).toBe(3)
  })

  it('knows when an engine rebuild would disconnect people', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    await vi.waitFor(() => expect(engineInUse.value).toBe(false))
    // a player of someone else's session: this account has no running session of its own
    __receive({ action: 'addNode', id: 'p1' })
    await vi.waitFor(() => expect(engineInUse.value).toBe(true))
    graph.clear()
    await vi.waitFor(() => expect(engineInUse.value).toBe(false))
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: { id: 3, name: 's', status: 'TESTING', creationTime: '', hitId: '', hits: [], data: [] } })
    expect(engineInUse.value).toBe(true)
  })
})

describe('the engine\'s reload', () => {
  afterEach(() => { vi.useRealTimers() })

  it('leaves "Reloading…" when the rebuild fails, so it can be retried', () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    expect(admin.engine).toBe('loading')
    __receive({ error: 'Something went wrong: the core script did not load', request: 'SelectExperiment' })
    expect(admin.engine).toBe('stale')
    expect(admin.engineProblem).toMatch(/did not finish reloading/)
    // the next successful reload clears it
    __receive({ notify: { ScriptEngineReloading: 2 } })
    expect(admin.engineProblem).toBe('')
    __receive({ notify: { ScriptEngineReloaded: 3 } })
    expect(admin.engine).toBe('loaded')
  })

  it('ignores errors of requests that do not rebuild the engine', () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ error: 'Caught error: boom', request: 'SendScript' })
    expect(admin.engine).toBe('loading')
    __receive({ notify: { ScriptEngineReloaded: 2 } })
  })

  it('follows a server that tells every console the rebuild failed', () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloadFailed: 2 } })
    expect(admin.engine).toBe('stale')
    __receive({ notify: { ScriptEngineReloaded: 3 } })
  })

  it('says why the rebuild failed, also on a console that did not ask for it', () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloadFailed: 'the step "init" has an error: unexpected token' } })
    expect(admin.engine).toBe('stale')
    expect(admin.engineProblem).toBe('The engine did not finish reloading: the step "init" has an error: unexpected token. Fix the problem, then reload it.')

    // the console that asked hears its error first; the reason replaces the general words
    __receive({ notify: { ScriptEngineReloading: 2 } })
    __receive({ error: 'Something went wrong: the core script did not load', request: 'ReloadEngine' })
    expect(admin.engineProblem).toMatch(/output panel says why/)
    __receive({ notify: { ScriptEngineReloadFailed: 'The core script did not load.' } })
    expect(admin.engineProblem).toBe('The engine did not finish reloading: The core script did not load. Fix the problem, then reload it.')
    // a reason that is not words keeps what is already said
    __receive({ notify: { ScriptEngineReloadFailed: 4 } })
    expect(admin.engineProblem).toMatch(/core script did not load/)
    __receive({ notify: { ScriptEngineReloading: 5 } })
    __receive({ notify: { ScriptEngineReloaded: 6 } })
  })

  it('is marked current again once the steps run, unless a reload did not finish', () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    actions.markEngineStale()
    expect(admin.engine).toBe('stale')
    actions.markEngineCurrent()
    expect(admin.engine).toBe('ready')

    __receive({ notify: { ScriptEngineReloading: 3 } })
    // not while it reloads
    actions.markEngineCurrent()
    expect(admin.engine).toBe('loading')
    __receive({ error: 'Something went wrong', request: 'ReloadEngine' })
    actions.markEngineCurrent()
    expect(admin.engine).toBe('stale')
    __receive({ notify: { ScriptEngineReloading: 4 } })
    __receive({ notify: { ScriptEngineReloaded: 5 } })
  })

  it('gives up waiting after a while, and after reconnecting mid-reload', () => {
    vi.useFakeTimers()
    __receive({ notify: { ScriptEngineReloading: 1 } })
    vi.advanceTimersByTime(89000)
    expect(admin.engine).toBe('loading')
    vi.advanceTimersByTime(2000)
    expect(admin.engine).toBe('stale')
    expect(admin.engineProblem).toMatch(/has not said/)

    __receive({ notify: { ScriptEngineReloading: 2 } })
    // the connection dropped and came back
    connection.state.value = 'retrying'
    expect(admin.engine).toBe('loading')
    connection.state.value = 'open'
    expect(admin.engine).toBe('stale')
    expect(admin.engineProblem).toMatch(/connection dropped/)
    connection.state.value = 'idle'
  })
})

describe('while the connection is down', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('sends nothing, and the actions say so at once', async () => {
    expect(connection.state.value).not.toBe('open')
    const spy = vi.spyOn(connection, 'send')
    expect(await actions.selectExperiment(2)).toBe(null)
    expect(admin.requestError).toMatchObject({ request: 'SelectExperiment', text: NOT_CONNECTED })
    expect(await actions.launch('Pilot', {})).toBe(null)
    expect(await actions.selectInstance(1)).toBe(null)
    expect(await actions.stopInstance(1)).toBe(null)
    expect(await actions.deleteInstance(1)).toBe(null)
    expect(await actions.toggleFileMode()).toBe(null)
    expect(await actions.refresh()).toBe(null)
    expect(await actions.instanceEvents(1)).toBe(null)
    expect(await actions.deleteExperiment({ id: 1, name: 'E' } as any)).toBe(null)
    expect(await actions.editTie('add', 'a', 'b')).toMatchObject({ ok: false, reason: expect.stringMatching(/Not connected/) })
    expect(actions.startGame()).toBe(false)
    expect(actions.reloadEngine()).toBe(false)
    expect(actions.makeChoice('c1')).toBe(false)
    expect(actions.runStep({ id: 1, name: 's', source: '' })).toBe(null)
    // a script that was not sent is not shown as run
    const before = admin.output.length
    expect(actions.runScript('a = 1')).toBe(null)
    expect(admin.output.length).toBe(before)
    // every call reached the connection, which refused each one
    expect(spy.mock.results.every(r => r.value === false)).toBe(true)
  })

  it('sends and waits as usual once it is open', async () => {
    const spy = vi.spyOn(connection, 'send').mockReturnValue(true)
    const reply = actions.selectExperiment(2)
    expect(spy).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 })
    __receive({ user: { email: 'r', experiments: [exp(2, 'a')], currentScript: '' }, experiment: exp(2, 'a'), experimentInstance: '' })
    expect(await reply).not.toBe(null)
    expect(typeof actions.runScript('a = 1')).toBe('string')
    expect(admin.output[admin.output.length - 1]).toMatchObject({ kind: 'input', text: 'a = 1' })
  })
})

describe('running scripts in the engine', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('sends each script with a fresh request id and the selected experiment', () => {
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    __receive({ user: { email: 'r', experiments: [exp(3, 'C')], currentScript: '' }, experiment: exp(3, 'C'), experimentInstance: '' })
    const a = actions.runScript('a = 1')!
    const b = actions.runStep({ id: null, name: 'NewStep', source: 'b = 2' })!
    expect(send).toHaveBeenNthCalledWith(1, 'SendScript', { script: 'a = 1', requestId: a, experimentId: 3 })
    expect(send).toHaveBeenNthCalledWith(2, 'SendStep', { id: -1, name: 'NewStep', source: 'b = 2', requestId: b, experimentId: 3 })
    expect(a).not.toBe(b)
    for (const id of [a, b]) expect(id.length).toBeLessThanOrEqual(64)
    expect(actions.runScript('a = 1')).not.toBe(a)
  })

  it('keeps the request id the server echoes on its answer', () => {
    actions.clearOutput()
    __receive({ error: 'Nothing was run: the engine has another experiment loaded now. Reload the page and try again.', request: 'SendScript', requestId: 'r1' })
    __receive({ error: '', output: 'a = 1\n\n==>1', request: 'SendStep', requestId: 'r2' })
    __receive({ error: '', output: 'b\n\n==>2', request: 'SendScript' })
    expect(admin.output.map(o => o.requestId)).toEqual(['r1', 'r2', undefined])
    expect('requestId' in admin.output[2]).toBe(false)
    // a refusal is shown like any other answer, and as the request's error
    expect(admin.output[0]).toMatchObject({ kind: 'error', source: 'SendScript' })
    expect(admin.requestError).toMatchObject({ request: 'SendScript', text: expect.stringMatching(/^Nothing was run/) })
  })
})

describe('waiting for the answer to a request', () => {
  // The server also sends the User JSON to every tab of the account after another tab's action,
  // unasked: an action must not take one of those for its answer.
  const inst = (id: number, status: string) => ({ id, name: 's' + id, status, creationTime: '', hitId: '', hits: [], data: [] })
  const withSessions = (e: ReturnType<typeof exp>, ...list: any[]) => ({ ...e, instances: list })
  const push = (extra: Record<string, any> = {}, list = [exp(1, 'A'), exp(2, 'B')]) =>
    __receive({ user: { email: 'r', experiments: list, currentScript: '' }, experimentInstance: '', ...extra })
  const flush = () => new Promise(r => setTimeout(r, 0))
  function track (p: Promise<any>) {
    const t = { done: false, value: undefined as any }
    void p.then(v => { t.done = true; t.value = v })
    return t
  }

  beforeEach(() => { vi.spyOn(connection, 'send').mockReturnValue(true) })
  afterEach(() => { vi.restoreAllMocks() })

  it('selecting an experiment waits for the one that shows it selected', async () => {
    const t = track(actions.selectExperiment(2))
    push({ experiment: exp(1, 'A') })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: exp(2, 'B') })
    await flush()
    expect(t.value.experiment.id).toBe(2)
    // a refusal is still the answer (null; the reason is in admin.requestError)
    const refused = track(actions.selectExperiment(3))
    __receive({ error: 'There is no experiment with id 3.', request: 'SelectExperiment' })
    await flush()
    expect(refused).toEqual({ done: true, value: null })
  })

  it('selecting a session waits for the one that shows it current', async () => {
    const t = track(actions.selectInstance(5))
    push({ experimentInstance: inst(4, 'RUNNING') })
    await flush()
    expect(t.done).toBe(false)
    push({ experimentInstance: inst(5, 'RUNNING') })
    await flush()
    expect(t.value.experimentInstance.id).toBe(5)
  })

  it('stopping a session waits until it no longer runs', async () => {
    const t = track(actions.stopInstance(5))
    push({ experiment: withSessions(exp(1, 'A'), inst(5, 'RUNNING')) })
    push({ experiment: withSessions(exp(1, 'A')), experimentInstance: inst(5, 'TESTING') })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: withSessions(exp(1, 'A'), inst(5, 'STOPPED')) })
    await flush()
    expect(t.done).toBe(true)
    expect(t.value).not.toBe(null)
    // gone altogether (e.g. another experiment's) counts too
    const gone = track(actions.stopInstance(8))
    push({ experiment: withSessions(exp(1, 'A'), inst(5, 'STOPPED')) })
    await flush()
    expect(gone.done).toBe(true)
  })

  it('deleting a session waits until it is gone, and takes a refusal as the answer', async () => {
    const t = track(actions.deleteInstance(5))
    push({ experiment: withSessions(exp(1, 'A'), inst(5, 'STOPPED')) })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: withSessions(exp(1, 'A'), inst(4, 'STOPPED')) })
    await flush()
    expect(t.value.experiment.instances.map((i: any) => i.id)).toEqual([4])

    const refused = track(actions.deleteInstance(6))
    __receive({ error: 'Only finished or stopped sessions can be deleted; stop it first.', request: 'DeleteInstance' })
    await flush()
    expect(refused.value).toMatchObject({ request: 'DeleteInstance' })
  })

  it('deleting an experiment waits until it is gone from the list', async () => {
    const t = track(actions.deleteExperiment(exp(2, 'B') as any))
    push({ experiment: exp(1, 'A') })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: exp(1, 'A') }, [exp(1, 'A')])
    await flush()
    expect(t.value.user.experiments.length).toBe(1)
  })

  it('switching file mode waits until the selected experiment\'s mode changed', async () => {
    push({ experiment: exp(1, 'A') })
    const t = track(actions.toggleFileMode())
    push({ experiment: exp(1, 'A') })
    // another tab selected another experiment, which is in file mode
    push({ experiment: { ...exp(2, 'B'), fileMode: true } })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: { ...exp(1, 'A'), fileMode: true } })
    await flush()
    expect(t.value.experiment).toMatchObject({ id: 1, fileMode: true })
  })

  it('a file-mode switch the server could not make ends soon after its unchanged answer', async () => {
    vi.useFakeTimers()
    try {
      push({ experiment: exp(1, 'A') })
      const t = track(actions.toggleFileMode())
      // the server only logs that it could not write the folder, and answers with the mode as it was
      push({ experiment: exp(1, 'A') })
      await vi.advanceTimersByTimeAsync(2000)
      expect(t.done).toBe(false)
      await vi.advanceTimersByTimeAsync(1000)
      expect(t.done).toBe(true)
      expect(t.value.experiment).toMatchObject({ id: 1, fileMode: false })

      // an unchanged push that came first does not hide the switched answer that follows
      const ok = track(actions.toggleFileMode())
      push({ experiment: exp(1, 'A') })
      await vi.advanceTimersByTimeAsync(500)
      push({ experiment: { ...exp(1, 'A'), fileMode: true } })
      await vi.advanceTimersByTimeAsync(5000)
      expect(ok.value.experiment).toMatchObject({ id: 1, fileMode: true })
    } finally {
      vi.useRealTimers()
    }
  })

  it('a file-mode switch the server says it could not make ends at once, with the reason', async () => {
    push({ experiment: exp(1, 'A') })
    const t = track(actions.toggleFileMode())
    __receive({ error: 'Couldn\'t switch file mode: dev/a is not writable', request: 'ToggleFileMode' })
    await flush()
    expect(t.done).toBe(true)
    expect(t.value).toBe(null)
    expect(admin.requestError).toMatchObject({ request: 'ToggleFileMode', text: 'Couldn\'t switch file mode: dev/a is not writable' })
  })

  it('launching sends the experiment the page showed, when it says', () => {
    const send = vi.mocked(connection.send)
    push({ experiment: exp(2, 'B') })
    void actions.launch('Pilot 3', { n: 4 }, 1)
    expect(send).toHaveBeenLastCalledWith('LaunchGame', { experimentId: 1, name: 'Pilot 3', parameters: { n: 4 } })
    void actions.launch('Pilot 4', {})
    expect(send).toHaveBeenLastCalledWith('LaunchGame', { experimentId: 2, name: 'Pilot 4', parameters: {} })
  })

  it('launching waits for the session it named', async () => {
    const t = track(actions.launch('Pilot 2', {}))
    push({ experiment: exp(1, 'A'), experimentInstance: inst(4, 'RUNNING') })
    await flush()
    expect(t.done).toBe(false)
    push({ experiment: exp(1, 'A'), experimentInstance: { ...inst(9, 'RUNNING'), name: 'Pilot 2' } })
    await flush()
    expect(t.value.experimentInstance.id).toBe(9)
  })
})

describe('what the engine runs', () => {
  const engine = (experimentId: number | null, sessionId: number | null = null) => ({
    experimentId, experimentName: experimentId === null ? null : 'E' + experimentId, sessionId, sessionName: sessionId === null ? null : 'S' + sessionId, owner: 'other@lab',
  })
  const push = (selected: number, eng: any) =>
    __receive({ user: { email: 'r', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(selected, selected === 1 ? 'A' : 'B'), experimentInstance: '', engine: eng })
  /** A rebuild ends, and the console asks again what the engine runs. */
  function rebuildEnds () {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
  }

  beforeEach(() => {
    rebuildEnds()
    push(1, engine(1))
  })
  afterEach(() => { vi.restoreAllMocks() })

  it('reads it beside the user, and says when it is another experiment', () => {
    push(1, engine(2, 7))
    expect(admin.user!.engine).toEqual({ experimentId: 2, experimentName: 'E2', sessionId: 7, sessionName: 'S7', owner: 'other@lab', gameStarted: null, stepsChanged: null, autopilotSession: null })
    expect(engineElsewhere.value).toMatchObject({ experimentId: 2, sessionName: 'S7' })
    push(2, engine(2, 7))
    expect(engineElsewhere.value).toBe(null)
    // nothing loaded, or an older server that does not say
    push(1, engine(null))
    expect(engineElsewhere.value).toBe(null)
    push(1, undefined)
    expect(admin.user!.engine).toBe(null)
    expect(engineElsewhere.value).toBe(null)
  })

  it('reads whether the engine runs the game, and when that was said', () => {
    push(1, { ...engine(1, 7), gameStarted: true })
    expect(admin.user!.engine!.gameStarted).toBe(true)
    const at = admin.engineInfoAt
    expect(at).toBeGreaterThan(0)
    push(1, { ...engine(1, 7), gameStarted: false })
    expect(admin.user!.engine!.gameStarted).toBe(false)
    expect(admin.engineInfoAt).toBeGreaterThanOrEqual(at)
    push(1, { ...engine(1, 7), gameStarted: 'yes' })
    expect(admin.user!.engine!.gameStarted).toBe(null)
    // what a User JSON says during a rebuild is not current, so it has no time either
    __receive({ notify: { ScriptEngineReloading: 1 } })
    expect(admin.engineInfoAt).toBe(0)
    push(1, { ...engine(1, 7), gameStarted: true })
    expect(admin.engineInfoAt).toBe(0)
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    push(1, { ...engine(1, 7), gameStarted: false })
    expect(admin.engineInfoAt).toBeGreaterThan(0)
  })

  it('reads whether its session is the autopilot\'s own, from the User JSON and from every push', () => {
    push(1, { ...engine(1, 7), autopilotSession: true })
    expect(admin.user!.engine!.autopilotSession).toBe(true)
    // switched off: the session goes on, no longer the autopilot's
    __receive({ engine: { ...engine(1, 7), autopilotSession: false } })
    expect(admin.user!.engine!.autopilotSession).toBe(false)
    __receive({ engine: { ...engine(1, 7), autopilotSession: true } })
    expect(admin.user!.engine!.autopilotSession).toBe(true)
    // an older server that does not say
    __receive({ engine: engine(1, 7) })
    expect(admin.user!.engine!.autopilotSession).toBe(null)
  })

  it('asks what the engine runs when a game starts, or a start is taken back, without this console asking', () => {
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    __receive({ notify: { GameStarted: 5 } })
    expect(send).toHaveBeenLastCalledWith('Update')
    send.mockClear()
    __receive({ notify: { GameStartFailed: 6 } })
    expect(send).toHaveBeenLastCalledWith('Update')
    send.mockRestore()
  })

  it('does not trust what the reply to a rebuild says, and asks again once the rebuild is done', () => {
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    void actions.selectExperiment(2)
    // the reply is made before the rebuild: it still names what the engine ran before
    push(2, engine(1))
    expect(engineElsewhere.value).toBe(null)
    expect(admin.engineInfoCurrent).toBe(false)
    __receive({ notify: { ScriptEngineReloading: 3 } })
    __receive({ notify: { ScriptEngineReloaded: 4 } })
    expect(send).toHaveBeenLastCalledWith('Update')
    push(2, engine(2))
    expect(admin.engineInfoCurrent).toBe(true)
    expect(engineElsewhere.value).toBe(null)

    // another account rebuilds it for its own experiment
    __receive({ notify: { ScriptEngineReloading: 5 } })
    expect(admin.engineInfoCurrent).toBe(false)
    __receive({ notify: { ScriptEngineReloaded: 6 } })
    push(2, engine(1, 9))
    expect(engineElsewhere.value).toMatchObject({ experimentId: 1, sessionId: 9 })
  })

  it('asks once more a little after a rebuild, which may be for a session not started yet', () => {
    vi.useFakeTimers()
    try {
      const send = vi.spyOn(connection, 'send').mockReturnValue(true)
      // another account resumes a session: the engine says it rebuilt before the session starts again
      __receive({ notify: { ScriptEngineReloading: 7 } })
      __receive({ notify: { ScriptEngineReloaded: 8 } })
      expect(send).toHaveBeenCalledTimes(1)
      push(1, engine(2))
      expect(engineElsewhere.value).toMatchObject({ experimentId: 2, sessionId: null })
      vi.advanceTimersByTime(ENGINE_SETTLE_MS)
      expect(send).toHaveBeenCalledTimes(2)
      expect(send).toHaveBeenLastCalledWith('Update')
      push(1, engine(2, 9))
      expect(engineElsewhere.value).toMatchObject({ experimentId: 2, sessionId: 9 })

      // not while the next rebuild is under way: that one asks when it ends
      __receive({ notify: { ScriptEngineReloaded: 9 } })
      expect(send).toHaveBeenCalledTimes(3)
      __receive({ notify: { ScriptEngineReloading: 10 } })
      vi.advanceTimersByTime(ENGINE_SETTLE_MS * 2)
      expect(send).toHaveBeenCalledTimes(3)
      __receive({ notify: { ScriptEngineReloaded: 11 } })
      push(1, engine(1))
    } finally {
      vi.useRealTimers()
    }
  })

  it('trusts the answer to a selection that left the engine alone, and says what is off then', () => {
    vi.useFakeTimers()
    try {
      const send = vi.spyOn(connection, 'send').mockReturnValue(true)
      void actions.selectExperiment(2)
      expect(admin.engineInfoCurrent).toBe(false)
      // a session is live in the engine: the server changed only the selection, and says so
      __receive({ user: { email: 'r', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine: engine(1, 9), engineKept: true })
      expect(admin.engineInfoCurrent).toBe(true)
      expect(engineElsewhere.value).toMatchObject({ experimentId: 1, sessionId: 9 })
      expect(runBlocked.value).toBe('The engine is running “E1”’s session “S9”, so this experiment’s steps can’t run in it until that session has ended.')
      expect(reloadBlocked.value).toBe('The engine is running “E1”’s session “S9”, and reloading it would end that session for everyone in it. End that session first, or open a new session of this experiment.')
      // nothing more to ask for: no rebuild is coming
      send.mockClear()
      vi.advanceTimersByTime(20000)
      expect(send).not.toHaveBeenCalled()

      // the session over, the experiment is only loaded there: reloading is how to load this one
      push(2, engine(1))
      expect(runBlocked.value).toBe('The engine has “E1” loaded, not this one: reload the engine to load this experiment’s steps, then run.')
      expect(reloadBlocked.value).toBe('')
      push(2, engine(2))
      expect(runBlocked.value).toBe('')
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not trust "engineKept" on a frame it did not ask for while a rebuild is under way', () => {
    __receive({ notify: { ScriptEngineReloading: 12 } })
    __receive({ user: { email: 'r', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine: engine(1, 9), engineKept: true })
    expect(admin.engineInfoCurrent).toBe(false)
    expect(engineElsewhere.value).toBe(null)
    __receive({ notify: { ScriptEngineReloaded: 13 } })
  })

  it('asks again at once when a rebuild is refused', () => {
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    void actions.selectInstance(5)
    expect(admin.engineInfoCurrent).toBe(false)
    __receive({ error: 'There is no session with id 5.', request: 'SelectInstance' })
    expect(send).toHaveBeenLastCalledWith('Update')
    push(1, engine(2))
    expect(engineElsewhere.value).toMatchObject({ experimentId: 2 })
  })
})

describe('the account\'s session while it works on another experiment', () => {
  const live = (id: number) => ({ id, name: 'S' + id, status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] })
  const engine = (experimentId: number, sessionId: number | null) =>
    ({ experimentId, experimentName: 'E' + experimentId, sessionId, sessionName: sessionId === null ? null : 'S' + sessionId, owner: 'r@lab' })
  const push = (selected: any, inst: any, eng: any) =>
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: selected, experimentInstance: inst, engine: eng })

  beforeEach(() => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
  })

  it('is not shown as the selected experiment\'s while the engine runs it for another one', () => {
    // its own session of A runs on; the account selected B (selecting left the session running)
    push(exp(2, 'B'), live(9), engine(1, 9))
    expect(admin.instance).toBe(null)
    expect(engineElsewhere.value).toMatchObject({ experimentId: 1, sessionId: 9 })
    // back on A, whose session it is: shown again
    push({ ...exp(1, 'A'), instances: [live(9)] }, live(9), engine(1, 9))
    expect(admin.instance?.id).toBe(9)
  })

  it('is kept while it may be the selected experiment\'s own (the engine doesn\'t say otherwise)', () => {
    // not in the list sent, but the engine runs it for the selected experiment (or doesn't say)
    push(exp(2, 'B'), live(9), engine(2, 9))
    expect(admin.instance?.id).toBe(9)
    push(exp(2, 'B'), live(9), undefined)
    expect(admin.instance?.id).toBe(9)
    push(exp(2, 'B'), '', engine(2, null))
  })
})

describe('what the server says the engine runs now ({"engine": …})', () => {
  const live = (id: number, status = 'RUNNING') => ({ id, name: 'S' + id, status, creationTime: '', hitId: '', hits: [], data: [] })
  const engine = (experimentId: number, sessionId: number | null) =>
    ({ experimentId, experimentName: 'E' + experimentId, sessionId, sessionName: sessionId === null ? null : 'S' + sessionId, owner: 'boss@lab' })
  const push = (selected: any, inst: any, eng: any) =>
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: selected, experimentInstance: inst, engine: eng })

  beforeEach(() => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
  })
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

  it('lifts the blocks once the other experiment\'s session has ended, though another account ended it', () => {
    vi.useFakeTimers()
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    // working on B while the engine runs A's session 9
    push(exp(2, 'B'), '', engine(1, 9))
    expect(reloadBlocked.value).toContain('“E1”’s session “S9”')
    expect(runBlocked.value).toContain('until that session has ended')
    // another account stops it: what the engine runs reaches this console too
    __receive({ engine: engine(1, null) })
    expect(admin.user!.engine).toMatchObject({ experimentId: 1, sessionId: null })
    expect(admin.engineInfoCurrent).toBe(true)
    expect(reloadBlocked.value).toBe('')
    expect(runBlocked.value).toBe('The engine has “E1” loaded, not this one: reload the engine to load this experiment’s steps, then run.')
    expect(fileStepsWait.value).toBe('The engine has “E1” loaded; a saved file loads when this experiment’s next session starts, or when you reload the engine.')
    // (nothing of B's changed: no need to ask for more)
    vi.advanceTimersByTime(1000)
    expect(send).not.toHaveBeenCalled()
    // a new session of A starts: blocked again
    __receive({ engine: engine(1, 12) })
    expect(reloadBlocked.value).toContain('“E1”’s session “S12”')
    expect(fileStepsWait.value).toBe('The engine is running “E1”’s session “S12”; a saved file loads when this experiment’s next session starts.')
    // working on A, whose session it is: nothing is held back
    push(exp(1, 'A'), '', engine(1, 12))
    expect(fileStepsWait.value).toBe('')
  })

  it('asks for the User JSON when the session followed here (or one of the selected experiment) begins or ends', () => {
    vi.useFakeTimers()
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    // following A's session 9, which another account runs
    push({ ...exp(1, 'A'), instances: [live(9)] }, live(9), engine(1, 9))
    expect(admin.instance?.id).toBe(9)
    // the game started: the same session, nothing more to ask (GameStarted is asked about anyway)
    __receive({ engine: { ...engine(1, 9), gameStarted: true } })
    vi.advanceTimersByTime(1000)
    expect(send).not.toHaveBeenCalled()
    // it finished: this console asks how things stand, once
    __receive({ engine: engine(1, null) })
    __receive({ engine: engine(1, null) })
    vi.advanceTimersByTime(300)
    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0][0]).toBe('Update')
    push({ ...exp(1, 'A'), instances: [live(9, 'FINISHED')] }, live(9, 'FINISHED'), engine(1, null))
    expect(admin.instance?.status).toBe('FINISHED')
    // another session of A begins: its list of sessions changed
    send.mockClear()
    __receive({ engine: engine(1, 10) })
    vi.advanceTimersByTime(300)
    expect(send).toHaveBeenCalledWith('Update', {})
  })

  it('is not taken for what runs while a rebuild this console asked for is under way', () => {
    vi.spyOn(connection, 'send').mockReturnValue(true)
    push(exp(1, 'A'), '', engine(1, null))
    actions.reloadEngine()
    expect(admin.engineInfoCurrent).toBe(false)
    __receive({ engine: engine(2, 5) })
    expect(admin.engineInfoCurrent).toBe(false)
    expect(engineElsewhere.value).toBe(null)
    __receive({ notify: { ScriptEngineReloading: 3 } })
    __receive({ notify: { ScriptEngineReloaded: 4 } })
    // the rebuild is over (and asked about): the next word on it is current
    __receive({ engine: engine(1, null) })
    expect(admin.engineInfoCurrent).toBe(true)
    expect(admin.user!.engine).toMatchObject({ experimentId: 1, sessionId: null })
  })
})

describe('"Reload needed" is about the experiment the engine runs', () => {
  const engine = (experimentId: number, sessionId: number | null) =>
    ({ experimentId, experimentName: 'E' + experimentId, sessionId, sessionName: sessionId === null ? null : 'S' + sessionId, owner: 'boss@lab' })
  const push = (selected: number, eng: any) =>
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(selected, selected === 1 ? 'A' : 'B'), experimentInstance: '', engine: eng })

  beforeEach(() => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    admin.engine = 'ready'
  })

  it('isn\'t marked for steps of an experiment the engine doesn\'t run', () => {
    // B selected, the engine runs A's session: B's next build loads what is saved
    push(2, engine(1, 9))
    actions.markEngineStale()
    expect(admin.engine).toBe('ready')
    actions.markEngineStale(2)
    expect(admin.engine).toBe('ready')
    // A's steps are what it runs
    actions.markEngineStale(1)
    expect(admin.engine).toBe('stale')
    actions.markEngineCurrent()
    // on A
    push(1, engine(1, 9))
    actions.markEngineStale()
    expect(admin.engine).toBe('stale')
    actions.markEngineCurrent()
    // not known what runs (a rebuild under way, an older server): marked, as before
    push(2, undefined)
    actions.markEngineStale(2)
    expect(admin.engine).toBe('stale')
    actions.markEngineCurrent()
  })
})

describe('"Reload needed" as the server says', () => {
  const engine = (experimentId: number, stepsChanged: boolean | undefined, sessionId: number | null = 9) =>
    ({ experimentId, experimentName: 'E' + experimentId, sessionId, sessionName: sessionId === null ? null : 'S' + sessionId, owner: 'boss@lab', stepsChanged })
  const push = (selected: number, eng: any) =>
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(selected, selected === 1 ? 'A' : 'B'), experimentInstance: '', engine: eng })
  const tick = () => new Promise(r => setTimeout(r, 0))

  beforeEach(() => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    admin.engine = 'ready'
  })

  it('follows the server, which every console hears, rather than this console\'s own marks', async () => {
    // a console opened (or refreshed) after the save: the server says so
    push(1, engine(1, true))
    await tick()
    expect(admin.engine).toBe('stale')
    // the push after a Run, or a save back to what the engine has: no reload needed
    __receive({ engine: engine(1, false) })
    await tick()
    expect(admin.engine).toBe('ready')
    // this console's own marks don't decide any more
    actions.markEngineStale(1)
    await tick()
    expect(admin.engine).toBe('ready')
    __receive({ engine: engine(1, true) })
    await tick()
    expect(admin.engine).toBe('stale')
    actions.markEngineCurrent()
    await tick()
    expect(admin.engine).toBe('stale')
    __receive({ engine: engine(1, false) })
    await tick()
    expect(admin.engine).toBe('ready')
  })

  it('is only about the experiment the engine runs, and keeps a reload that didn\'t finish', async () => {
    // B selected while the engine runs A, whose steps changed: B's next build loads what is saved
    push(2, engine(1, true))
    await tick()
    expect(admin.engine).toBe('ready')
    // working on A again
    push(1, engine(1, true))
    await tick()
    expect(admin.engine).toBe('stale')
    // a reload that didn't finish stays, whatever the server says of the steps
    __receive({ notify: { ScriptEngineReloading: 3 } })
    __receive({ error: 'Something went wrong', request: 'ReloadEngine' })
    expect(admin.engineProblem).not.toBe('')
    push(1, engine(1, false))
    await tick()
    expect(admin.engine).toBe('stale')
    __receive({ notify: { ScriptEngineReloading: 4 } })
    __receive({ notify: { ScriptEngineReloaded: 5 } })
    expect(admin.engineProblem).toBe('')
  })
})

describe('another researcher changing the experiments', () => {
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

  it('asks once for the newest list after a burst of changes', () => {
    vi.useFakeTimers()
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    __receive({ experimentsChanged: true })
    __receive({ experimentsChanged: true })
    expect(send).not.toHaveBeenCalled()
    vi.advanceTimersByTime(300)
    expect(send).toHaveBeenCalledTimes(1)
    expect(send.mock.calls[0][0]).toBe('Update')
  })
})
