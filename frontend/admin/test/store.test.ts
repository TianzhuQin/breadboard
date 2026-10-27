import { describe, it, expect, beforeEach } from 'vitest'
import { admin, graph, actions, experiments, __receive } from '../src/store/admin'

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
})
