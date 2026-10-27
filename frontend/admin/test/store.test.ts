import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { admin, graph, actions, experiments, engineInUse, connection, NOT_CONNECTED, __receive } from '../src/store/admin'

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
    __receive({ event: { id: 1, name: 'clientLogIn', datetime: '2026-10-27T10:00:00.000Z', data: { clientId: 'p1' } } })
    expect(admin.events.length).toBe(1)
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
    __receive(ev({ instanceId: 5 }))
    __receive(ev())
    expect(admin.events.length).toBe(0)
    // a stopped session is selected: another researcher's live events stay out of its feed
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: inst(5, 'STOPPED') })
    __receive(ev({ instanceId: 9 }))
    __receive(ev())
    expect(admin.events.length).toBe(0)
    // the server says the event is this session's
    __receive(ev({ instanceId: 5 }))
    expect(admin.events.length).toBe(1)
    __receive(ev({ experimentInstanceId: 5 }))
    __receive(ev({ instanceId: null }))
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
    expect(actions.runStep({ id: 1, name: 's', source: '' })).toBe(false)
    // a script that was not sent is not shown as run
    const before = admin.output.length
    expect(actions.runScript('a = 1')).toBe(false)
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
    expect(actions.runScript('a = 1')).toBe(true)
    expect(admin.output[admin.output.length - 1]).toMatchObject({ kind: 'input', text: 'a = 1' })
  })
})
