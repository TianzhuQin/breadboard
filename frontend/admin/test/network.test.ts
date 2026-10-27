import { describe, it, expect, beforeEach, vi } from 'vitest'
import ts from 'typescript'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { scaleFor, NEUTRAL, CATEGORICAL_LIGHT, CATEGORICAL_DARK, CATEGORY20, rampColor } from '../src/network/palette'
import { parseFilter, matchFilter, describeFilter } from '../src/network/filter'
import {
  isAi, connectionOf, discoverProperties, colorableProperties, compareValues, compareIds, labelOf, valueOf, choicesOf, formatValue, isTie, tieBetween,
} from '../src/network/players'
import { NetworkLayout, layoutFor } from '../src/network/layout'
import { escapeXml, placeLabels, sceneToSvg, PAPER_THEME, type Scene } from '../src/network/render'
import { networkGraphml, networkJson, exportFileName } from '../src/network/exporters'
import { LiveGraph, type GraphNode } from '../src/lib/graph'
import { actions, admin, graph, __receive } from '../src/store/admin'
import { toasts } from '../src/ui/toast'
import { editTie, undoLast, undoEdit, pendingEdits, editHistory, friendlyReason, inverseOf, tieEditsBlocked } from '../src/network/tieEdits'

const node = (id: string, data: Record<string, any> = {}): GraphNode => ({ id, data })

// ---- colour scale ---------------------------------------------------------------------------------

describe('colour scale', () => {
  it('gives each category its own colour and sorts the legend naturally', () => {
    const s = scaleFor(['g10', 'g2', 'g1', 'g2'])
    expect(s.kind).toBe('categorical')
    expect(s.legend.map(l => l.label)).toEqual(['g1', 'g2', 'g10'])
    expect(s.legend.find(l => l.label === 'g2')!.count).toBe(2)
    expect(new Set(['g1', 'g2', 'g10'].map(s.color)).size).toBe(3)
  })

  it('keeps colours with their values when new values appear (colour follows the value, not its rank)', () => {
    const memory = new Map<string, number>()
    const before = scaleFor(['b', 'c'], { memory })
    const after = scaleFor(['a', 'b', 'c'], { memory })
    expect(after.color('b')).toBe(before.color('b'))
    expect(after.color('c')).toBe(before.color('c'))
    expect(after.color('a')).not.toBe(after.color('b'))
  })

  it('folds categories beyond the palette into a grey "more" entry', () => {
    const values = Array.from({ length: 11 }, (_, i) => `v${i}`)
    const s = scaleFor(values)
    expect(s.legend.length).toBe(CATEGORICAL_LIGHT.length + 1)
    expect(s.legend[s.legend.length - 1]).toMatchObject({ label: '3 more', color: NEUTRAL, count: 3 })
  })

  it('uses a gradient for many numbers, with a legend from min to max', () => {
    const s = scaleFor(Array.from({ length: 30 }, (_, i) => i * 2))
    expect(s.kind).toBe('numeric')
    expect(s.legend.map(l => l.label)).toEqual(['0', '29', '58'])
    expect(s.color(0)).toBe(rampColor(0))
    expect(s.color(58)).toBe(rampColor(1))
    expect(s.gradient!.length).toBe(5)
    // numbers given as text count as numbers
    expect(scaleFor(Array.from({ length: 12 }, (_, i) => String(i))).kind).toBe('numeric')
  })

  it('treats a few numbers (group 1, 2, 3) as categories', () => {
    expect(scaleFor([1, 2, 3, 1]).kind).toBe('categorical')
  })

  it('counts people without a value and greys them', () => {
    const s = scaleFor(['x', null, undefined, ''])
    expect(s.missing).toBe(3)
    expect(s.color(null)).toBe(NEUTRAL)
    expect(scaleFor([undefined, null]).kind).toBe('none')
  })

  it('steps the colours for the dark canvas, keeping the same slots', () => {
    const memory = new Map<string, number>()
    const light = scaleFor(['a', 'b'], { memory })
    const dark = scaleFor(['a', 'b'], { memory, dark: true })
    expect(light.color('a')).toBe(CATEGORICAL_LIGHT[0])
    expect(dark.color('a')).toBe(CATEGORICAL_DARK[0])
    // the dark ramp runs the other way: big values are the light ones
    expect(rampColor(1, true)).not.toBe(rampColor(1, false))
  })

  it('keeps the old console\'s `color` hook: 1–20 pick category20, hex codes are used as given', () => {
    const s = scaleFor([1, 3, 20, 3], { colorHook: true })
    expect(s.color(1)).toBe(CATEGORY20[0])
    expect(s.color('3')).toBe(CATEGORY20[2])
    expect(s.color(20)).toBe(CATEGORY20[19])
    expect(s.legend.map(l => l.label)).toEqual(['1', '3', '20'])
    expect(scaleFor(['#ff0000', '#00f'], { colorHook: true }).color('#00f')).toBe('#00f')
    // anything else is an ordinary category
    expect(scaleFor(['big', 'small'], { colorHook: true }).color('big')).toBe(CATEGORICAL_LIGHT[0])
  })

  it('handles booleans and objects', () => {
    const s = scaleFor([true, false, true])
    expect(s.legend.map(l => l.label)).toEqual(['false', 'true'])
    const o = scaleFor([{ a: 1 }, { a: 1 }, { a: 2 }])
    expect(o.color({ a: 1 })).not.toBe(o.color({ a: 2 }))
  })
})

// ---- filter expressions -------------------------------------------------------------------------

describe('filter expressions', () => {
  const people = [
    node('p1', { score: 5, name: 'Ann', group: 'A', _system: { connected: true }, timers: [{ id: 'xa' }] }),
    node('p2', { score: 12, name: 'Bob', group: 'B', _system: { connected: false } }),
    node('p10', { score: '7', name: 'anna', ai: 1 }),
    node('_1', { name: 'bot' }),
  ]
  const ids = (q: string) => people.filter(n => matchFilter(parseFilter(q), n)).map(n => n.id)

  it('matches plain text against the id, ignoring case', () => {
    expect(ids('p1')).toEqual(['p1', 'p10'])
    expect(ids('P2')).toEqual(['p2'])
    expect(ids('')).toEqual(['p1', 'p2', 'p10', '_1'])
  })

  it('compares numbers as numbers, also when stored as text', () => {
    expect(ids('score>6')).toEqual(['p2', 'p10'])
    expect(ids('score<6')).toEqual(['p1'])
    expect(ids('score=7')).toEqual(['p10'])
    expect(ids('score > 6')).toEqual(['p2', 'p10'])
  })

  it('reads >=, <= and != (the old console read score>=5 as "score>" = "5")', () => {
    expect(ids('score>=7')).toEqual(['p2', 'p10'])
    expect(ids('score<=5')).toEqual(['p1'])
    expect(ids('group!=A')).toEqual(['p2', 'p10', '_1'])
  })

  it('finds text containing a value with ~, and missing properties never crash', () => {
    expect(ids('name~ann')).toEqual(['p1', 'p10'])
    expect(ids('nothing~x')).toEqual([])
    expect(ids('group=a')).toEqual(['p1'])
  })

  it('reaches inside with dotted paths, including list items', () => {
    expect(ids('_system.connected=false')).toEqual(['p2'])
    expect(ids('_system.connected=true')).toEqual(['p1'])
    expect(ids('timers.0.id~x')).toEqual(['p1'])
    expect(ids('ai=1')).toEqual(['p10'])
    expect(ids('id~_')).toEqual(['_1'])
  })

  it('joins conditions with &', () => {
    expect(ids('score>4 & name~ann')).toEqual(['p1', 'p10'])
    expect(ids('score>4 & group=B')).toEqual(['p2'])
    expect(ids('p1 & score>6')).toEqual(['p10'])
  })

  it('keeps everything after the first operator as the value', () => {
    const f = parseFilter('note=a=b')
    expect(f.conditions[0]).toMatchObject({ kind: 'expr', path: ['note'], op: '=', value: 'a=b' })
    expect(matchFilter(f, node('x', { note: 'a=b' }))).toBe(true)
  })

  it('explains a part it cannot use, and still applies the rest', () => {
    const f = parseFilter('=5 & score>6')
    expect(f.error).toMatch(/property name/)
    expect(f.conditions.length).toBe(1)
    expect(describeFilter(f)).toBe('score is more than "6"')
  })

  it('can match plain text another way (the network also searches names)', () => {
    const f = parseFilter('ann')
    const byName = (n: GraphNode, t: string) => String(n.data.name || '').toLowerCase().includes(t)
    expect(people.filter(n => matchFilter(f, n, { text: byName })).map(n => n.id)).toEqual(['p1', 'p10'])
  })
})

// ---- player helpers -----------------------------------------------------------------------------

describe('player helpers', () => {
  it('knows AI seats by their flag or their id', () => {
    expect(isAi(node('_3'))).toBe(true)
    expect(isAi(node('p1', { ai: 1 }))).toBe(true)
    expect(isAi(node('p1', { ai: 0 }))).toBe(false)
  })

  it('reads the connection only when the server has said', () => {
    expect(connectionOf(node('a', { _system: { connected: true } }))).toBe('connected')
    expect(connectionOf(node('a', { _system: { connected: false } }))).toBe('disconnected')
    expect(connectionOf(node('a', { _system: {} }))).toBe('unknown')
    expect(connectionOf(node('a'))).toBe('unknown')
  })

  it('offers experiment properties for colour and label, not the engine\'s plumbing', () => {
    const nodes = [
      node('a', { text: '<p>', choices: [], timers: [], score: 1, group: 'x', name: 'Ann', bag: { k: 1 }, _system: { groupId: 2 } }),
      node('b', { text: '<p>', score: 2, group: 'x', name: 'Bo', _system: { groupId: 3 } }),
      node('c', { score: 3, group: 'y', name: 'Cy' }),
    ]
    const props = discoverProperties(nodes)
    expect(props.map(p => p.key)).toEqual(['bag', '_system.groupId', 'group', 'name', 'score'])
    expect(props.find(p => p.key === '_system.groupId')!.label).toBe('Engine group')
    const colorable = colorableProperties(props, nodes.length).map(p => p.key)
    expect(colorable).toContain('group')
    expect(colorable).toContain('score')
    // a different name per person colours nobody alike, and maps can't be coloured
    expect(colorable).not.toContain('name')
    expect(colorable).not.toContain('bag')
    // long text is no use as a colour
    const withNote = discoverProperties([node('a', { note: 'x'.repeat(80) }), node('b', { note: 'x'.repeat(80) })])
    expect(withNote[0].long).toBe(true)
    expect(colorableProperties(withNote, 2)).toEqual([])
  })

  it('names people by a property, falling back to the id', () => {
    expect(labelOf(node('p1', { name: 'Ann' }), 'name')).toBe('Ann')
    expect(labelOf(node('p1', {}), 'name')).toBe('p1')
    expect(labelOf(node('p1', { name: 'Ann' }), null)).toBe('p1')
    expect(valueOf(node('p1'), '@ties', () => 4)).toBe(4)
    expect(valueOf(node('_1'), '@status')).toBe('AI player')
  })

  it('sorts values sensibly: numbers as numbers, text naturally, missing last', () => {
    expect([10, 2, '3', undefined, 1].sort(compareValues)).toEqual([1, 2, '3', 10, undefined])
    expect(['p10', '_1', 'p2'].sort(compareIds)).toEqual(['p2', 'p10', '_1'])
  })

  it('reads only well-formed choices', () => {
    expect(choicesOf(node('a', { choices: [{ uid: 'u1', name: 'Go' }, null, { name: 'no uid' }] })).map(c => c.uid)).toEqual(['u1'])
    expect(choicesOf(node('a', { choices: 'x' }))).toEqual([])
  })

  it('prints any value on one line', () => {
    expect(formatValue({ a: 1 })).toBe('{"a":1}')
    expect(formatValue(1 / 3)).toBe('0.333')
    expect(formatValue('x'.repeat(100), 10)).toBe('xxxxxxxxx…')
  })
})

// ---- layout -------------------------------------------------------------------------------------

function liveGraph (n: number, ties: [number, number][] = []) {
  const g = new LiveGraph()
  for (let i = 0; i < n; i++) g.apply({ action: 'addNode', id: `p${i}` })
  ties.forEach(([a, b], i) => g.apply({ action: 'addLink', id: `e${i}`, source: `p${a}`, target: `p${b}`, value: 'connected' }))
  return g
}
const finite = (l: NetworkLayout) => Array.from(l.nodes.values()).every(n => Number.isFinite(n.x) && Number.isFinite(n.y))

describe('network layout', () => {
  it('copes with an empty network', () => {
    const l = new NetworkLayout()
    const r = l.sync(new LiveGraph())
    expect(r.structural).toBe(false)
    expect(l.bounds()).toBe(null)
    expect(l.moving).toBe(false)
    expect(l.step()).toBe(false)
  })

  it('places one person, then two, without overlapping', () => {
    const l = new NetworkLayout()
    const g = liveGraph(1)
    l.sync(g)
    l.settle(50)
    expect(finite(l)).toBe(true)
    g.apply({ action: 'addNode', id: 'p1' })
    g.apply({ action: 'addLink', id: 'e', source: 'p0', target: 'p1' })
    const r = l.sync(g)
    expect(r).toMatchObject({ added: 1, structural: true })
    l.reheat(1)
    l.settle(100, 300)
    const [a, b] = Array.from(l.nodes.values())
    expect(Math.hypot(a.x! - b.x!, a.y! - b.y!)).toBeGreaterThan(a.r + b.r)
    expect(l.degree('p0')).toBe(1)
  })

  it('handles 300+ people: every position finite, settles within its time budget', () => {
    const ties: [number, number][] = []
    for (let i = 0; i < 320; i++) { ties.push([i, (i + 1) % 320]); ties.push([i, (i * 7) % 320]) }
    const g = liveGraph(320, ties)
    const l = new NetworkLayout()
    l.sync(g)
    const start = performance.now()
    l.settle(200, 300)
    expect(performance.now() - start).toBeLessThan(1500)
    expect(l.nodes.size).toBe(320)
    expect(finite(l)).toBe(true)
    const b = l.bounds()!
    expect(b.maxX - b.minX).toBeGreaterThan(100)
  })

  it('ignores ties to unknown people and ties to oneself', () => {
    const g = liveGraph(2)
    g.apply({ action: 'addLink', id: 'x', source: 'p0', target: 'ghost' })
    g.apply({ action: 'addLink', id: 'y', source: 'p1', target: 'p1' })
    const l = new NetworkLayout()
    l.sync(g)
    expect(l.links.length).toBe(0)
    expect(l.degree('p1')).toBe(0)
  })

  it('puts people back where they were after a replay (reconnect, engine reload)', () => {
    const g = liveGraph(3, [[0, 1]])
    const l = new NetworkLayout()
    l.sync(g)
    l.settle(50)
    const where = { x: l.nodes.get('p2')!.x, y: l.nodes.get('p2')!.y }
    g.clear()
    l.sync(g)
    expect(l.nodes.size).toBe(0)
    g.apply({ action: 'addNode', id: 'p2' })
    l.sync(g)
    expect(l.nodes.get('p2')).toMatchObject(where)
  })

  it('keeps a moved (pinned) person in place until everyone is freed', () => {
    const g = liveGraph(4, [[0, 1], [1, 2], [2, 3]])
    const l = new NetworkLayout()
    l.sync(g)
    const n = l.nodes.get('p0')!
    n.fx = 500
    n.fy = 500
    l.reheat(1)
    l.settle(50)
    expect([n.x, n.y]).toEqual([500, 500])
    l.unpinAll()
    expect(n.fx).toBe(null)
  })

  it('sizes dots by ties, or all alike', () => {
    const g = liveGraph(3, [[0, 1], [0, 2]])
    const l = new NetworkLayout()
    l.sync(g)
    expect(l.nodes.get('p0')!.r).toBeGreaterThan(l.nodes.get('p1')!.r)
    l.setSizeByDegree(false)
    expect(l.nodes.get('p0')!.r).toBe(l.nodes.get('p1')!.r)
  })
})

// ---- drawing and exports --------------------------------------------------------------------------

describe('drawing and exports', () => {
  it('shows the most important labels first and never overlaps them', () => {
    const measure = (t: string) => t.length * 7
    const cands = [
      { id: 'a', x: 100, y: 100, r: 6, text: 'alpha', priority: 10 },
      { id: 'b', x: 104, y: 102, r: 6, text: 'bravo', priority: 50 },
      { id: 'c', x: 300, y: 100, r: 6, text: 'charlie', priority: 10 },
      { id: 'off', x: -500, y: 100, r: 6, text: 'gone', priority: 10 },
    ]
    expect(Array.from(placeLabels(cands, measure, 800, 600)).sort()).toEqual(['b', 'c'])
    // the selected person's name always shows
    expect(placeLabels([...cands, { id: 's', x: 102, y: 100, r: 6, text: 'sel', priority: 1000 }], measure, 800, 600).has('s')).toBe(true)
  })

  it('writes an SVG that escapes names', () => {
    const scene: Scene = {
      w: 200,
      h: 100,
      nodes: [
        { id: 'a<b', x: 10, y: 10, r: 5, fill: '#2a78d6', ai: false, away: false, selected: false, focused: false, hover: false, from: false, target: false, dim: false, match: false, label: 'Ann & <Bo>', pop: 1 },
        { id: '_1', x: 50, y: 10, r: 5, fill: '#2a78d6', ai: true, away: false, selected: false, focused: false, hover: false, from: false, target: false, dim: false, match: false, label: null, pop: 1 },
      ],
      links: [{ id: 'e', a: 'a<b', b: '_1', x1: 10, y1: 10, x2: 50, y2: 10, kind: 'normal', pop: 1 }],
      pending: [],
      rubber: null,
      time: 0,
      grid: null,
      linkWidth: 1.4,
    }
    const svg = sceneToSvg(scene, PAPER_THEME, { title: 'Net', legend: { title: 'group', entries: [{ label: 'x', color: '#000', count: 2 }] } })
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true)
    expect(svg).toContain('Ann &amp; &lt;Bo&gt;')
    expect(svg).toContain('data-id="a&lt;b"')
    expect(svg).toContain('x (2)')
    expect(new DOMParser().parseFromString(svg, 'image/svg+xml').querySelector('parsererror')).toBe(null)
  })

  it('exports GraphML that network tools can read, with typed attributes', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: 'p1' })
    g.apply({ action: 'addNode', id: 'p2' })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'score', value: 3 })
    g.apply({ action: 'nodePropertyChanged', id: 'p2', key: 'score', value: 4.5 })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'note', value: 'a & "b" <c>' })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'text', value: '<p>big html</p>' })
    g.apply({ action: 'addLink', id: '9', source: 'p1', target: 'p2', value: 'connected' })
    g.apply({ action: 'addLink', id: '10', source: 'p1', target: 'nobody' })
    const xml = networkGraphml(g, { experiment: 'Demo', session: 'S1', positions: new Map([['p1', { x: 1.234, y: 2 }]]) })
    const doc = new DOMParser().parseFromString(xml, 'application/xml')
    expect(doc.querySelector('parsererror')).toBe(null)
    expect(doc.querySelectorAll('node').length).toBe(2)
    expect(doc.querySelectorAll('edge').length).toBe(1)
    expect(doc.querySelector('key[attr\\.name="score"]')!.getAttribute('attr.type')).toBe('double')
    expect(xml).not.toContain('big html')
    expect(xml).toContain('a &amp; &quot;b&quot; &lt;c&gt;')
    expect(xml).toContain('<data key="layout_x">1.2</data>')
  })

  it('keeps GraphML valid and unambiguous: a plain graph id, its own position keys, no illegal characters', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: 'p1' })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'x', value: 40 })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'answer', value: 'bad\u0001\u0008 text\uFFFF \uD800 ok 😀' })
    const xml = networkGraphml(g, { experiment: 'My study', session: 'Wave 1/2', positions: new Map([['p1', { x: 7, y: 8 }]]) })
    const doc = new DOMParser().parseFromString(xml, 'application/xml')
    expect(doc.querySelector('parsererror')).toBe(null)
    // ids are NMTOKENs; the names go in a description and graph data
    expect(doc.querySelector('graph')!.getAttribute('id')).toBe('network')
    expect(doc.querySelector('graph > desc')!.textContent).toBe('My study / Wave 1/2')
    expect(doc.querySelector('key[for="graph"][attr\\.name="session"]')).not.toBe(null)
    expect(xml).toContain('<data key="g_session">Wave 1/2</data>')
    // the experiment's own x is kept apart from where the picture put them
    const xKey = doc.querySelector('key[for="node"][attr\\.name="x"]')!.getAttribute('id')!
    expect(xml).toContain(`<data key="${xKey}">40</data>`)
    expect(xml).toContain('<data key="layout_x">7</data>')
    expect(doc.querySelectorAll('key[attr\\.name="x"]').length).toBe(1)
    expect(xml).toContain('bad text  ok 😀')
  })

  it('exports each tie\'s kind (its label) in GraphML and JSON', () => {
    const g = liveGraph(3, [[0, 1]])
    g.apply({ action: 'addLink', id: 'pp', source: 'p1', target: 'p2', value: 'partner' })
    const xml = networkGraphml(g)
    const doc = new DOMParser().parseFromString(xml, 'application/xml')
    expect(doc.querySelector('parsererror')).toBe(null)
    expect(doc.querySelector('key#e_label')!.getAttribute('attr.name')).toBe('label')
    expect(doc.querySelector('edge[id="pp"] > data[key="e_label"]')!.textContent).toBe('partner')
    expect(doc.querySelector('edge[id="e0"] > data[key="e_label"]')!.textContent).toBe('connected')
    // a tie property already called "label" keeps its name; the kind is then "tie_label"
    g.apply({ action: 'linkPropertyChanged', id: 'pp', key: 'label', value: 'mine' })
    expect(networkGraphml(g)).toContain('attr.name="tie_label"')
    const data = JSON.parse(networkJson(g))
    expect(data.links.find((l: any) => l.id === 'pp').label).toBe('partner')
    expect(data.links.find((l: any) => l.id === 'e0').label).toBe('connected')
  })

  it('exports JSON with every property, ties and positions', () => {
    const g = liveGraph(2, [[0, 1]])
    g.apply({ action: 'nodePropertyChanged', id: 'p0', key: 'text', value: '<b>hi</b>' })
    const data = JSON.parse(networkJson(g, { experiment: 'E', session: 'S', positions: new Map([['p0', { x: 1, y: 2 }]]) }))
    expect(data.nodes[0]).toMatchObject({ id: 'p0', x: 1, y: 2, properties: { text: '<b>hi</b>' } })
    expect(data.links[0]).toMatchObject({ source: 'p0', target: 'p1' })
    expect(data.experiment).toBe('E')
  })

  it('drops lone surrogates and characters XML can\'t hold, and keeps whole emoji', () => {
    expect(escapeXml('a\uD800b')).toBe('ab')
    expect(escapeXml('a\uDC00b')).toBe('ab')
    expect(escapeXml('\uD83D\uD83D\uDE00')).toBe('\uD83D\uDE00')
    expect(escapeXml('\uD83D\uDE00\uDE00x\uD83D')).toBe('\uD83D\uDE00x')
    expect(escapeXml('tab\tline\nend\u0000\u001F\uFFFE\uFFFF')).toBe('tab\tline\nend')
    expect(escapeXml('<a & "b">')).toBe('&lt;a &amp; &quot;b&quot;&gt;')
  })

  it('names files safely', () => {
    expect(exportFileName('png', { experiment: 'My study: wave 2/3', session: 'Pilot 1' }, new Date(2026, 9, 27, 14, 5)))
      .toBe('network-My-study-wave-2-3-Pilot-1-2026-10-27-1405.png')
  })
})

// ---- tie edits and undo -------------------------------------------------------------------------

describe('tie edits', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    // ties can be changed (and changes undone) only while the session runs
    if (admin.instance?.status !== 'RUNNING') admin.instance = { id: 1, name: 's1', status: 'RUNNING' } as any
    graph.clear()
    for (const id of ['a', 'b', 'c']) __receive({ action: 'addNode', id })
    __receive({ action: 'addLink', id: '1', source: 'a', target: 'b', value: 'connected' })
    toasts.splice(0, toasts.length)
    editHistory.value = []
    pendingEdits.value = []
  })

  it('shows the edit as pending until the server answers, then offers Undo', async () => {
    let answer!: (r: any) => void
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(() => new Promise(r => { answer = r }))
    const done = editTie('add', 'a', 'c')
    expect(spy).toHaveBeenCalledWith('add', 'a', 'c')
    expect(pendingEdits.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
    answer({ ok: true, op: 'add', a: 'a', b: 'c' })
    const r = await done
    expect(r.ok).toBe(true)
    expect(pendingEdits.value).toEqual([])
    expect(editHistory.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
    const t = toasts[toasts.length - 1]
    expect(t.kind).toBe('success')
    expect(t.title).toBe('Tied a and c')
    expect(t.action!.label).toBe('Undo')
  })

  it('Undo sends the opposite edit and is not itself undoable', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('remove', 'a', 'b', { name: id => id.toUpperCase() })
    expect(toasts[toasts.length - 1].title).toBe('Cut the tie between A and B')
    // the server removes the tie; then the researcher undoes
    __receive({ action: 'removeLink', id: '1' })
    toasts[toasts.length - 1].action!.run()
    await vi.waitFor(() => expect(spy).toHaveBeenCalledTimes(2))
    expect(spy).toHaveBeenLastCalledWith('add', 'a', 'b')
    await vi.waitFor(() => expect(toasts[toasts.length - 1].title).toBe('Put back the tie between A and B'))
    expect(toasts[toasts.length - 1].action).toBeUndefined()
    expect(editHistory.value).toEqual([])
  })

  it('Ctrl/Cmd+Z takes back the newest edit, one at a time', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('add', 'a', 'c')
    __receive({ action: 'addLink', id: '2', source: 'a', target: 'c' })
    await editTie('add', 'b', 'c')
    __receive({ action: 'addLink', id: '3', source: 'b', target: 'c' })
    await undoLast()
    expect(spy).toHaveBeenLastCalledWith('remove', 'b', 'c')
    expect(editHistory.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
    editHistory.value = []
    expect(undoLast()).toBe(null)
    expect(toasts[toasts.length - 1].title).toBe('Nothing to undo')
  })

  it('keeps an edit whose undo failed, so the next Ctrl/Cmd+Z retries it instead of an older one', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    // cut a–b, then tie a–c
    await editTie('remove', 'a', 'b')
    __receive({ action: 'removeLink', id: '1' })
    await editTie('add', 'a', 'c')
    __receive({ action: 'addLink', id: '2', source: 'a', target: 'c', value: 'connected' })
    expect(editHistory.value).toEqual([{ op: 'remove', a: 'a', b: 'b' }, { op: 'add', a: 'a', b: 'c' }])
    // the connection blips: undoing a–c fails
    spy.mockImplementationOnce(async (op, a, b) => ({ ok: false, op, a, b, reason: 'Not connected to the server. Try again once the connection is back.' }))
    expect((await undoLast())!.ok).toBe(false)
    expect(editHistory.value).toEqual([{ op: 'remove', a: 'a', b: 'b' }, { op: 'add', a: 'a', b: 'c' }])
    // once it is back, Ctrl/Cmd+Z undoes a–c, not the older cut
    expect((await undoLast())!.ok).toBe(true)
    expect(spy).toHaveBeenLastCalledWith('remove', 'a', 'c')
    expect(editHistory.value).toEqual([{ op: 'remove', a: 'a', b: 'b' }])
  })

  it('refuses a second undo of the same edit while the first is on its way, and keeps it if that fails', async () => {
    let answer!: (r: any) => void
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('add', 'a', 'c')
    __receive({ action: 'addLink', id: '2', source: 'a', target: 'c', value: 'connected' })
    const undo = toasts[toasts.length - 1].action!
    spy.mockImplementationOnce(() => new Promise(r => { answer = r }))
    const first = undoLast()!
    expect(undoLast()).not.toBe(null)
    await Promise.resolve()
    expect(toasts[toasts.length - 1].title).toBe('Already being undone')
    undo.run()
    expect(toasts[toasts.length - 1].title).toBe('Already being undone')
    expect(spy).toHaveBeenCalledTimes(2)
    answer({ ok: false, op: 'remove', a: 'a', b: 'c', reason: 'Boom' })
    expect((await first).ok).toBe(false)
    expect(editHistory.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
    // the toast's Undo can try again
    undo.run()
    await vi.waitFor(() => expect(editHistory.value).toEqual([]))
    expect(spy).toHaveBeenLastCalledWith('remove', 'a', 'c')
  })

  it('undoes nothing once the session stopped (the Tie and Cut tools are locked then too)', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('add', 'a', 'c')
    const undo = toasts[toasts.length - 1].action!
    admin.instance = { id: 1, name: 's1', status: 'STOPPED' } as any
    expect((await undoLast())!.ok).toBe(false)
    expect(toasts[toasts.length - 1]).toMatchObject({ title: 'Nothing was undone', detail: 'Start the session to change ties.' })
    undo.run()
    await Promise.resolve()
    expect(spy).toHaveBeenCalledTimes(1)
    expect(editHistory.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
  })

  it('shows the server\'s reason when it refuses', async () => {
    vi.spyOn(actions, 'editTie').mockResolvedValue({ ok: false, op: 'add', a: 'a', b: 'c', reason: 'They are already connected.' })
    const r = await editTie('add', 'a', 'c')
    expect(r.ok).toBe(false)
    const t = toasts[toasts.length - 1]
    expect(t.kind).toBe('error')
    expect(t.title).toBe('Couldn\'t tie a and c')
    expect(t.detail).toBe('They are already connected.')
    expect(editHistory.value).toEqual([])
  })

  it('says why without asking the server when the edit can\'t work', async () => {
    const spy = vi.spyOn(actions, 'editTie')
    expect((await editTie('add', 'a', 'b')).reason).toMatch(/already tied/)
    expect((await editTie('remove', 'a', 'c')).reason).toMatch(/aren't tied/)
    expect((await editTie('add', 'a', 'a')).reason).toMatch(/two different/)
    expect((await editTie('add', 'a', 'zz')).reason).toMatch(/no longer in the session/)
    expect(spy).not.toHaveBeenCalled()
  })

  it('refuses a second edit of the same pair while the first is on its way', async () => {
    let answer!: (r: any) => void
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(() => new Promise(r => { answer = r }))
    const first = editTie('add', 'a', 'c')
    expect((await editTie('add', 'c', 'a')).reason).toMatch(/already being changed/)
    expect(spy).toHaveBeenCalledTimes(1)
    answer({ ok: true, op: 'add', a: 'a', b: 'c' })
    await first
  })

  it('turns engine messages into plain words', () => {
    expect(friendlyReason('adminEdit needs two different existing players')).toMatch(/no longer in the session/)
    expect(friendlyReason('The server did not answer.')).toMatch(/connection/)
    expect(friendlyReason(undefined)).toMatch(/without saying why/)
    expect(friendlyReason('They are not connected.')).toBe('They are not connected.')
    expect(inverseOf({ op: 'add', a: 'x', b: 'y' })).toEqual({ op: 'remove', a: 'x', b: 'y' })
  })

  it('only adds and cuts ties ("connected" edges), not links of other kinds', async () => {
    __receive({ action: 'addLink', id: 'p', source: 'a', target: 'c', value: 'partner' })
    expect(isTie(graph.links.get('p'))).toBe(false)
    expect(isTie(graph.links.get('1'))).toBe(true)
    expect(isTie({})).toBe(true)
    expect(tieBetween(graph, 'c', 'a')).toBeUndefined()
    expect(tieBetween(graph, 'b', 'a')!.id).toBe('1')
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    const cut = await editTie('remove', 'a', 'c')
    expect(cut.reason).toMatch(/“partner” link, not a tie/)
    expect(spy).not.toHaveBeenCalled()
    // partners can still be tied
    expect((await editTie('add', 'a', 'c')).ok).toBe(true)
    expect(spy).toHaveBeenCalledWith('add', 'a', 'c')
  })

  it('forgets the undo history when the engine reloads, and an old toast\'s Undo does nothing', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('remove', 'a', 'b')
    const undo = toasts[toasts.length - 1].action!
    expect(editHistory.value.length).toBe(1)
    __receive({ notify: { ScriptEngineReloading: true } })
    await vi.waitFor(() => expect(editHistory.value).toEqual([]))
    __receive({ notify: { ScriptEngineReloaded: true } })
    // the rebuilt network has the same people and the old tie
    for (const id of ['a', 'b', 'c']) __receive({ action: 'addNode', id })
    __receive({ action: 'addLink', id: '7', source: 'a', target: 'b', value: 'connected' })
    expect(undoLast()).toBe(null)
    undo.run()
    expect(toasts[toasts.length - 1].title).toBe('Nothing to undo')
    expect(toasts[toasts.length - 1].detail).toMatch(/rebuilt/)
    expect(spy).toHaveBeenCalledTimes(1)
    expect(admin.engine).toBe('loaded')
  })

  it('an edit already taken back can\'t be undone again from its toast', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    await editTie('add', 'a', 'c')
    const first = toasts[toasts.length - 1].action!
    __receive({ action: 'addLink', id: '2', source: 'a', target: 'c' })
    await undoLast()
    __receive({ action: 'removeLink', id: '2' })
    await editTie('add', 'a', 'c')
    __receive({ action: 'addLink', id: '3', source: 'a', target: 'c' })
    expect(spy).toHaveBeenCalledTimes(3)
    // the first toast's Undo would cut the tie made by the second edit
    first.run()
    expect(toasts[toasts.length - 1].title).toBe('Nothing to undo')
    expect(spy).toHaveBeenCalledTimes(3)
    expect(editHistory.value).toEqual([{ op: 'add', a: 'a', b: 'c' }])
    expect((await undoEdit({ op: 'add', a: 'x', b: 'y' })).ok).toBe(false)
  })

  it('won\'t change ties while the engine runs another experiment (the server refuses)', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    const exp = { id: 1, name: 'E', uid: 'u', fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 }
    const user = (engine: any) => __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 1, name: 's1', status: 'RUNNING' }, engine })
    __receive({ notify: { ScriptEngineReloaded: true } })
    user({ experimentId: 2, experimentName: 'Other study', sessionId: 4, sessionName: 'S', owner: 'bob@lab.org' })
    expect(tieEditsBlocked.value).toBe('The engine is running “Other study” right now, so ties can\'t be changed here')
    const r = await editTie('add', 'a', 'c')
    expect(r.ok).toBe(false)
    expect(spy).not.toHaveBeenCalled()
    expect(toasts[toasts.length - 1].detail).toMatch(/running “Other study” right now/)
    // the engine runs this experiment's session: edits work again
    user({ experimentId: 1, experimentName: 'E', sessionId: 1, sessionName: 's1', owner: 'r' })
    expect(tieEditsBlocked.value).toBe('')
    expect((await editTie('add', 'a', 'c')).ok).toBe(true)
    // and a session that isn't running can't be edited either
    __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 1, name: 's1', status: 'STOPPED' } })
    expect(tieEditsBlocked.value).toBe('Start the session to change ties')
  })

  it('forgets the undo history when the session changes', async () => {
    vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    const exp = { id: 1, name: 'E', uid: 'u', fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 }
    __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 1, name: 's1', status: 'RUNNING' } })
    await editTie('add', 'a', 'c')
    expect(editHistory.value.length).toBe(1)
    __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 2, name: 's2', status: 'RUNNING' } })
    await vi.waitFor(() => expect(editHistory.value).toEqual([]))
  })
})

// ---- browser support -----------------------------------------------------------------------------

/** A lookbehind in a pattern's source, (?<= or (?<! (not \(?<=, which starts with an escaped bracket). */
const LOOKBEHIND = /(^|[^\\])(\\\\)*\(\?<[=!]/

/**
 * Patterns in this code that use a lookbehind: regex literals, and the text given to RegExp(…) or
 * new RegExp(…) as a string or template literal (read as the string RegExp gets, so '\\(?<=' is an
 * escaped bracket there).
 */
function lookbehindLiterals (code: string, file = 'x.ts'): string[] {
  const found: string[] = []
  const sf = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, false, ts.ScriptKind.TS)
  /** The pattern text RegExp gets from a literal argument (a template's substitutions as "x"); null for anything else. */
  const patternOf = (a: ts.Expression | undefined): string | null => {
    if (!a) return null
    if (ts.isStringLiteral(a) || ts.isNoSubstitutionTemplateLiteral(a)) return a.text
    if (ts.isTemplateExpression(a)) return a.head.text + a.templateSpans.map(t => 'x' + t.literal.text).join('')
    return null
  }
  const visit = (n: ts.Node) => {
    if (n.kind === ts.SyntaxKind.RegularExpressionLiteral && LOOKBEHIND.test(n.getText(sf))) found.push(n.getText(sf))
    if ((ts.isNewExpression(n) || ts.isCallExpression(n)) && ts.isIdentifier(n.expression) && n.expression.text === 'RegExp') {
      const pattern = patternOf(n.arguments?.[0])
      if (pattern !== null && LOOKBEHIND.test(pattern)) found.push(n.getText(sf))
    }
    ts.forEachChild(n, visit)
  }
  visit(sf)
  return found
}

describe('browser support', () => {
  it('finds lookbehinds in regex literals', () => {
    expect(lookbehindLiterals('const r = /(?<![a-z])x/g; const t = /a(?<=b)/')).toEqual(['/(?<![a-z])x/g', '/a(?<=b)/'])
    expect(lookbehindLiterals('const r = /\\(?<=x/; const n = /(?<name>x)/; const s = "(?<!x)"')).toEqual([])
  })

  it('finds lookbehinds in patterns built with RegExp(…)', () => {
    // a lookbehind in new RegExp(…) throws only when that line runs, so the page breaks later, not at once
    expect(lookbehindLiterals('const r = new RegExp(`(?<![A-Za-z0-9_$.])${name}(?![A-Za-z0-9_$])`)')).toHaveLength(1)
    expect(lookbehindLiterals('const r = new RegExp(\'(?<=a)b\', \'g\'); const t = RegExp("x(?<!y)")')).toHaveLength(2)
    expect(lookbehindLiterals('const r = new RegExp(`${a}(?<=b)`)')).toHaveLength(1)
    // an escaped bracket, a named group, a lookahead, or a pattern that isn't a literal
    expect(lookbehindLiterals('const r = new RegExp(\'\\\\(?<=x\'); const n = new RegExp(\'(?<name>x)\'); const a = new RegExp(`${x}(?!y)`); const v = new RegExp(source)')).toEqual([])
  })

  // Safari before 16.4 (all of Safari 15, iOS and iPadOS up to 16.3) can't parse a lookbehind. The
  // build (target es2020) leaves regex literals as written, and a literal is parsed when its module
  // loads, so one anywhere would stop the whole console from starting there; one built with
  // new RegExp(…) throws whenever its code runs.
  it('uses no lookbehind in any regex of the console', () => {
    const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
    const files: string[] = []
    const walk = (dir: string) => {
      for (const f of readdirSync(dir)) {
        const p = join(dir, f)
        if (statSync(p).isDirectory()) walk(p)
        else if (/\.(ts|vue)$/.test(f)) files.push(p)
      }
    }
    walk(root)
    expect(files.length).toBeGreaterThan(50)
    const found: string[] = []
    for (const file of files) {
      const text = readFileSync(file, 'utf8')
      const scripts = file.endsWith('.vue') ? Array.from(text.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g), m => m[1]) : [text]
      for (const code of scripts) for (const r of lookbehindLiterals(code, file)) found.push(`${relative(root, file)}: ${r}`)
      // a template's expressions can hold regex literals too
      if (file.endsWith('.vue')) {
        const template = text.replace(/<script\b[\s\S]*?<\/script>/g, '').replace(/<style\b[\s\S]*?<\/style>/g, '')
        if (/\(\?<[=!]/.test(template)) found.push(`${relative(root, file)}: (template)`)
      }
    }
    expect(found).toEqual([])
  })
})

describe('NetworkToolbar', () => {
  it('turns off Tie and Cut with the reason ties can\'t be changed', async () => {
    const { mount } = await import('@vue/test-utils')
    const NetworkToolbar = (await import('../src/pages/network/NetworkToolbar.vue')).default
    const props = {
      mode: 'view' as const, canEdit: false, editHint: 'The engine is running “Other study” right now, so ties can\'t be changed here', query: '', matchCount: 0,
      matchIndex: -1, colorBy: null, labelBy: null, colorOptions: [], labelOptions: [], showAllLabels: false, sizeByDegree: true, frozen: false, canExport: false,
    }
    const w = mount(NetworkToolbar, { props })
    const tie = w.find('.ntb__mode.is-connect')
    expect(tie.attributes('disabled')).toBeDefined()
    expect(tie.attributes('title')).toBe(props.editHint)
    expect(w.find('.ntb__mode.is-view').attributes('disabled')).toBeUndefined()
    await w.setProps({ canEdit: true, editHint: '' })
    expect(w.find('.ntb__mode.is-cut').attributes('disabled')).toBeUndefined()
    expect(w.find('.ntb__mode.is-cut').attributes('title')).toBe('Cut ties: click a tie (X)')
    w.unmount()
  })
})

// ---- the network view (keyboard and screen readers) -----------------------------------------------

describe('AdminNetwork', () => {
  // jsdom has no canvas; the view still works without drawing
  HTMLCanvasElement.prototype.getContext = (() => null) as any

  async function mountNet (n: number, props: Record<string, any> = {}) {
    const { mount } = await import('@vue/test-utils')
    const AdminNetwork = (await import('../src/network/AdminNetwork.vue')).default
    const g = liveGraph(n, n > 1 ? [[0, 1]] : [])
    const w = mount(AdminNetwork, { props: { graph: g, ...props }, slots: { empty: '<p class="nobody">Nobody yet</p>' }, attachTo: document.body })
    await new Promise(r => setTimeout(r, 20))
    return { w, g }
  }

  it('shows the empty state for no one, and describes the network for screen readers', async () => {
    const { w, g } = await mountNet(0)
    expect(w.find('.nobody').exists()).toBe(true)
    g.apply({ action: 'addNode', id: 'p0' })
    await new Promise(r => setTimeout(r, 40))
    expect(w.find('.nobody').exists()).toBe(false)
    expect(w.attributes('aria-label')).toBe('Network: 1 person, 0 ties')
    w.unmount()
  })

  it('selects with the arrow keys and Enter, and says who is focused', async () => {
    const { w } = await mountNet(2)
    await w.trigger('keydown', { key: 'ArrowRight' })
    await w.trigger('keydown', { key: 'Enter' })
    const picked = w.emitted('select')![0][0] as string
    expect(['p0', 'p1']).toContain(picked)
    expect(w.find('[aria-live="polite"]').text()).toMatch(/^p[01]\. 1 tie\./)
    w.unmount()
  })

  it('proposes a tie from the keyboard in connect mode (Enter on one, then the other)', async () => {
    const { w, g } = await mountNet(3, { mode: 'connect' })
    g.apply({ action: 'removeLink', id: 'e0' })
    await new Promise(r => setTimeout(r, 40))
    await w.trigger('keydown', { key: 'ArrowRight' })
    await w.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('select')).toBeUndefined()
    expect(w.find('[aria-live="polite"]').text()).toMatch(/Drawing a tie|Press Enter to cancel/)
    // move to someone else; the arrow search may need a couple of tries in a tiny layout
    for (const key of ['ArrowLeft', 'ArrowDown', 'ArrowUp', 'ArrowRight']) {
      await w.trigger('keydown', { key })
      await w.trigger('keydown', { key: 'Enter' })
      if (w.emitted('edit')) break
    }
    const edit = w.emitted('edit')![0][0] as any
    expect(edit.op).toBe('add')
    expect(edit.a).not.toBe(edit.b)
    w.unmount()
  })

  it('tells the page when a proposed tie already exists instead of proposing it', async () => {
    const { w } = await mountNet(2, { mode: 'connect' })
    await w.trigger('keydown', { key: 'ArrowRight' })
    await w.trigger('keydown', { key: 'Enter' })
    // find the other person, whichever side they are on
    for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
      await w.trigger('keydown', { key })
      if (/Press Enter to tie/.test(w.find('[aria-live="polite"]').text())) break
    }
    await w.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('edit')).toBeUndefined()
    expect(w.emitted('notice')![0][0]).toMatch(/already tied/)
    w.unmount()
  })

  it('zooms with the wheel, also in the small steps of a smooth-scrolling mouse; pans sideways or with Shift', async () => {
    const { w, g } = await mountNet(2)
    const layout = layoutFor(g)
    const viewNow = () => { w.unmount(); return layout.view! }
    await w.trigger('wheel', { deltaY: -4, deltaX: 0, deltaMode: 0 })
    const zoomed = viewNow()
    expect(zoomed.k).toBeGreaterThan(1)

    const second = await mountNet(2)
    const l2 = layoutFor(second.g)
    await second.w.trigger('wheel', { deltaY: 0, deltaX: 30, deltaMode: 0 })
    // the rest of the same gesture keeps panning, even straight up and down
    await second.w.trigger('wheel', { deltaY: 20, deltaX: 0, deltaMode: 0 })
    second.w.unmount()
    expect(l2.view!.k).toBe(1)
    expect(l2.view!.x).toBe(-30)
    expect(l2.view!.y).toBe(-20)
    expect(l2.follow).toBe(false)

    const third = await mountNet(2)
    await third.w.trigger('wheel.shift', { deltaY: 20, deltaX: 0, deltaMode: 0 })
    third.w.unmount()
    expect(layoutFor(third.g).view).toEqual({ k: 1, x: 0, y: -20 })
  })

  it('a preview fits its own view and leaves the Network page\'s zoom and pan alone', async () => {
    const { mount } = await import('@vue/test-utils')
    const AdminNetwork = (await import('../src/network/AdminNetwork.vue')).default
    const g = liveGraph(3, [[0, 1]])
    const layout = layoutFor(g)
    // the Network page was left zoomed into a corner
    layout.view = { k: 6, x: -4000, y: -3000 }
    layout.follow = false
    const w = mount(AdminNetwork, { props: { graph: g, rememberView: false, controls: false }, attachTo: document.body })
    await new Promise(r => setTimeout(r, 20))
    await w.trigger('wheel', { deltaY: -40, deltaX: 0, deltaMode: 0 })
    w.unmount()
    expect(layout.view).toEqual({ k: 6, x: -4000, y: -3000 })
    expect(layout.follow).toBe(false)
  })

  it('shows a tie\'s properties on hover, and won\'t cut a link that isn\'t a tie', async () => {
    const { mount } = await import('@vue/test-utils')
    const AdminNetwork = (await import('../src/network/AdminNetwork.vue')).default
    const g = liveGraph(2)
    g.apply({ action: 'addLink', id: 'pp', source: 'p0', target: 'p1', value: 'partner' })
    g.apply({ action: 'linkPropertyChanged', id: 'pp', key: 'weight', value: '2.5' })
    g.apply({ action: 'linkPropertyChanged', id: 'pp', key: 'inProps', value: '{}' })
    const w = mount(AdminNetwork, { props: { graph: g, frozen: true }, attachTo: document.body })
    await new Promise(r => setTimeout(r, 20))
    const pos = (w.vm as any).positions() as Map<string, { x: number, y: number }>
    const a = pos.get('p0')!
    const b = pos.get('p1')!
    const mid = { clientX: (a.x + b.x) / 2, clientY: (a.y + b.y) / 2 }
    await w.trigger('pointermove', { ...mid, pointerId: 1, pointerType: 'mouse' })
    const tip = w.find('.an__tip')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toContain('p0 – p1')
    expect(tip.text()).toContain('“partner” link')
    expect(tip.text()).toContain('weight')
    expect(tip.text()).toContain('2.5')
    expect(tip.text()).not.toContain('inProps')
    // in cut mode it says it can't be cut, and a click explains instead of proposing an edit
    await w.setProps({ mode: 'cut' })
    await w.trigger('pointermove', { ...mid, pointerId: 1, pointerType: 'mouse' })
    expect(w.find('.an__tip').text()).toContain('Only ties can be cut here')
    await w.trigger('pointerdown', { ...mid, pointerId: 1, pointerType: 'mouse', button: 0 })
    await w.trigger('pointerup', { ...mid, pointerId: 1, pointerType: 'mouse', button: 0 })
    expect(w.emitted('edit')).toBeUndefined()
    expect(w.emitted('notice')![0][0]).toMatch(/“partner” link, not a tie/)
    // when a tie runs along the same line, Cut picks the tie
    g.apply({ action: 'addLink', id: 'tt', source: 'p0', target: 'p1', value: 'connected' })
    await new Promise(r => setTimeout(r, 30))
    await w.trigger('pointermove', { ...mid, pointerId: 1, pointerType: 'mouse' })
    expect(w.find('.an__tip').text()).toContain('Click to cut this tie')
    await w.trigger('pointerdown', { ...mid, pointerId: 1, pointerType: 'mouse', button: 0 })
    await w.trigger('pointerup', { ...mid, pointerId: 1, pointerType: 'mouse', button: 0 })
    expect(w.emitted('edit')![0][0]).toEqual({ op: 'remove', a: 'p0', b: 'p1' })
    w.unmount()
  })

  it('reports the colour legend for the chosen property', async () => {
    const { w, g } = await mountNet(2, { colorBy: 'team' })
    g.apply({ action: 'nodePropertyChanged', id: 'p0', key: 'team', value: 'A' })
    g.apply({ action: 'nodePropertyChanged', id: 'p1', key: 'team', value: 'B' })
    await new Promise(r => setTimeout(r, 40))
    const all = w.emitted('legend')!
    const last = all[all.length - 1][0] as any[]
    expect(last.map(e => e.label)).toEqual(['A', 'B'])
    w.unmount()
  })
})
