import type { GraphLink, GraphNode, LiveGraph } from '@/lib/graph'

/**
 * What the console knows about a player (a vertex of the session's graph), read from its
 * properties: the engine's own (`text`, `choices`, `timers`, `_system`, `ai`, the flattened
 * private keys such as `edgeEdits`) and whatever the experiment sets.
 */

export interface Choice {
  uid: string
  name: string
  class?: string
  custom?: any
  /** Set on group steps: those choices go through the participant page only. */
  _route?: string
  [key: string]: any
}

export type Connection = 'connected' | 'disconnected' | 'unknown'

/** Keys that are the engine's plumbing, not something to colour, label or sort by. */
export const NOISY_KEYS = new Set([
  'text', 'choices', 'timers', '_system', 'private', 'edgeEdits', 'lastupdated', 'timerUpdatedAt',
  'x', 'y', 'vx', 'vy', 'fx', 'fy', 'index',
])

/** Derived values every player has, usable like properties (colour by, sort by, filter by). */
export const DERIVED_KEYS: Record<string, string> = {
  '@status': 'Status',
  '@ties': 'Number of ties',
}

export function isAi (n: GraphNode | undefined | null): boolean {
  if (!n) return false
  const ai = n.data.ai
  return ai === 1 || ai === true || ai === '1' || ai === 'true' || n.id.startsWith('_')
}

export function connectionOf (n: GraphNode | undefined | null): Connection {
  const sys = n?.data?._system
  if (!sys || typeof sys !== 'object' || typeof sys.connected !== 'boolean') return 'unknown'
  return sys.connected ? 'connected' : 'disconnected'
}

/** Short status word: AI player, connected, disconnected (or '' when the server hasn't said). */
export function statusOf (n: GraphNode | undefined | null): string {
  if (!n) return ''
  // (a person whose seat the computer plays while they are away, e.g. autopilot.replaceDisconnected)
  if (isAi(n)) return /^_/.test(n.id) ? 'AI player' : 'Away: computer playing'
  const c = connectionOf(n)
  // (a researcher's preview has the seat while their own page is away: _system.preview. It plays a
  // test player's seat; on the seat of anyone whose own page has been in the session it only stands
  // in: they count as away.)
  const preview = n.data?._system?.preview === true
  if (c === 'connected' && preview) return 'Preview connected'
  if (c === 'disconnected' && preview) return 'Away: preview standing in'
  return c === 'connected' ? 'Connected' : c === 'disconnected' ? 'Disconnected' : ''
}

export interface Recruitment { source: string, prolificPid?: string, studyId?: string, sessionId?: string }
export function recruitmentOf (n: GraphNode | undefined | null): Recruitment | null {
  const r = n?.data?._system?.recruitment
  return r && typeof r === 'object' && r.source ? r as Recruitment : null
}

export interface Autopilot { state: 'waiting' | 'starting' | 'cancelled' | string, here?: number, min?: number, max?: number | null, seconds?: number }
export function autopilotOf (n: GraphNode | undefined | null): Autopilot | null {
  const a = n?.data?._system?.autopilot
  return a && typeof a === 'object' && a.state ? a as Autopilot : null
}

export function choicesOf (n: GraphNode | undefined | null): Choice[] {
  const c = n?.data?.choices
  if (!Array.isArray(c)) return []
  return c.filter(x => x && typeof x === 'object' && typeof x.uid === 'string')
}

/** A value from a dotted path: `score`, `_system.connected`, `timers.0.id`, or `id` itself. */
export function getPath (n: GraphNode, path: string | string[]): any {
  const parts = Array.isArray(path) ? path : splitPath(path)
  if (!parts.length) return undefined
  if (parts.length === 1 && parts[0] === 'id') return n.id
  let v: any = n.data
  for (const p of parts) {
    if (v === null || v === undefined || typeof v !== 'object') return undefined
    v = v[p]
  }
  return v
}
export function splitPath (path: string): string[] {
  return path.split('.').map(s => s.trim()).filter(Boolean)
}

/** The value of a property or a derived key for one player. */
export function valueOf (n: GraphNode, key: string, degree?: (id: string) => number): any {
  if (key === '@ties') return degree ? degree(n.id) : undefined
  if (key === '@status') return statusOf(n) || undefined
  if (key === 'id') return n.id
  return key in n.data ? n.data[key] : (key.includes('.') ? getPath(n, key) : undefined)
}

/** What to call someone: the chosen property when they have it, else their id. */
export function labelOf (n: GraphNode | undefined | null, labelBy?: string | null, degree?: (id: string) => number): string {
  if (!n) return ''
  if (!labelBy || labelBy === 'id') return n.id
  const v = valueOf(n, labelBy, degree)
  if (v === undefined || v === null || v === '') return n.id
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

/**
 * The console's name for a computer player g.addAI added: `_6` is "AI 6", as they are AI players on
 * its badges and filters. Null for anyone else. (A participant's page calls the people around them
 * Person A, Person B, … in the order that page first shows them, so no one letter is a player's.)
 */
export function aiName (id: string): string | null {
  return /^_\d+$/.test(id) ? `AI ${id.slice(1)}` : null
}

/**
 * What to call someone where one line of text names them (the network, the Players table, the
 * Preview page): "AI 6 (_6)" for a computer player, with the id that scripts, filters and the data
 * use; anyone else's id.
 */
export function playerName (id: string): string {
  const ai = aiName(id)
  return ai ? `${ai} (${id})` : id
}

/** The id to show under a name, unless the name shows it already ("AI 6 (_6)" does): '' then. */
export function idUnder (name: string, id: string): string {
  return name !== id && name !== playerName(id) ? id : ''
}

/** labelOf, with playerName where it would show the bare id. */
export function displayName (n: GraphNode | undefined | null, labelBy?: string | null, degree?: (id: string) => number): string {
  if (!n) return ''
  const label = labelOf(n, labelBy, degree)
  return label === n.id ? playerName(n.id) : label
}

export interface PropertyInfo {
  key: string
  label: string
  /** number, string, boolean, mixed (several kinds), object (maps and lists) */
  kind: 'number' | 'string' | 'boolean' | 'mixed' | 'object'
  /** How many people have it. */
  count: number
  /** How many different values (capped at 1000). */
  distinct: number
  /** Some values are long text (no use as a colour or a name on the network). */
  long: boolean
}

/**
 * The properties players have, for colour-by, label-by and table columns. Skips the engine's
 * plumbing (text, choices, timers, …); `_system.groupId` is offered as "Group" when present.
 */
export function discoverProperties (nodes: Iterable<GraphNode>): PropertyInfo[] {
  const info = new Map<string, { kinds: Set<string>, count: number, values: Set<string>, long: boolean }>()
  const see = (key: string, v: any) => {
    if (v === undefined || v === null || v === '') return
    let e = info.get(key)
    if (!e) { e = { kinds: new Set(), count: 0, values: new Set(), long: false }; info.set(key, e) }
    e.count++
    const kind = typeof v === 'object' ? 'object' : typeof v === 'number' ? 'number' : typeof v === 'boolean' ? 'boolean' : 'string'
    e.kinds.add(kind)
    const text = kind === 'object' ? JSON.stringify(v) : String(v)
    if (text.length > 60) e.long = true
    if (e.values.size < 1000) e.values.add(text)
  }
  for (const n of nodes) {
    for (const key of Object.keys(n.data)) {
      if (NOISY_KEYS.has(key) || key === 'ai') continue
      see(key, n.data[key])
    }
    const g = n.data._system && typeof n.data._system === 'object' ? n.data._system.groupId : undefined
    see('_system.groupId', g)
  }
  const out: PropertyInfo[] = []
  for (const [key, e] of info) {
    const kinds = Array.from(e.kinds)
    const kind = (kinds.length === 1 ? kinds[0] : (kinds.includes('object') ? 'object' : 'mixed')) as PropertyInfo['kind']
    out.push({ key, label: key === '_system.groupId' ? GROUP_LABEL : key, kind, count: e.count, distinct: e.values.size, long: e.long })
  }
  return out.sort((a, b) => a.label.localeCompare(b.label, undefined, { numeric: true, sensitivity: 'base' }))
}

/** Properties that make sense as colours: categories that repeat, or numbers. */
export function colorableProperties (props: PropertyInfo[], people: number): PropertyInfo[] {
  return props.filter(p => {
    if (p.kind === 'object' || p.long) return false
    if (p.kind === 'number') return true
    // a value per person (names, codes) colours everyone differently: no use
    return p.distinct <= 40 && (p.distinct < people || people <= 2)
  })
}

/** Properties that make sense as names on the network: anything that prints short. */
export function labelableProperties (props: PropertyInfo[]): PropertyInfo[] {
  return props.filter(p => p.kind !== 'object' && !p.long)
}

/** Properties that make sense as table columns: anything but maps and lists. */
export function columnProperties (props: PropertyInfo[]): PropertyInfo[] {
  return props.filter(p => p.kind !== 'object')
}

/** What the engine's group (`_system.groupId`, set by group steps) is called in menus. */
export const GROUP_LABEL = 'Engine group'

/** A property's name for people: derived keys and the engine group get words. */
export function propertyTitle (key: string): string {
  return DERIVED_KEYS[key] || (key === '_system.groupId' ? GROUP_LABEL : key)
}

/** A one-line rendering of any value, for tables, tooltips and labels. */
export function formatValue (v: any, max = 80): string {
  let s: string
  if (v === undefined) return ''
  if (v === null) s = 'null'
  else if (typeof v === 'number') s = Number.isInteger(v) ? String(v) : String(Math.round(v * 1000) / 1000)
  else if (typeof v === 'object') s = JSON.stringify(v)
  else s = String(v)
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

/** Compare two values for sorting: numbers numerically, text naturally, missing values last. */
export function compareValues (a: any, b: any): number {
  const ma = a === undefined || a === null || a === ''
  const mb = b === undefined || b === null || b === ''
  if (ma || mb) return ma === mb ? 0 : ma ? 1 : -1
  const na = typeof a === 'number' ? a : (typeof a === 'string' && a.trim() !== '' ? Number(a) : NaN)
  const nb = typeof b === 'number' ? b : (typeof b === 'string' && b.trim() !== '' ? Number(b) : NaN)
  if (!isNaN(na) && !isNaN(nb)) return na - nb
  if (typeof a === 'boolean' && typeof b === 'boolean') return a === b ? 0 : a ? -1 : 1
  const sa = typeof a === 'object' ? JSON.stringify(a) : String(a)
  const sb = typeof b === 'object' ? JSON.stringify(b) : String(b)
  return sa.localeCompare(sb, undefined, { numeric: true, sensitivity: 'base' })
}

/** Ids sort naturally, with AI seats (`_1`, `_2`) after people. */
export function compareIds (a: string, b: string): number {
  const aa = a.startsWith('_')
  const bb = b.startsWith('_')
  if (aa !== bb) return aa ? 1 : -1
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' })
}

// ---- ties ---------------------------------------------------------------------------------------

/**
 * Whether an edge is one of the network's ties, the kind the console can add and cut. EditTie
 * (edgeEdits.adminEdit) only touches edges labelled "connected"; an experiment may add other kinds
 * (e.g. "partner"), which are drawn but can't be edited here. An edge without a label counts as a tie.
 */
export function isTie (l: { label?: string } | null | undefined): boolean {
  return !!l && (!l.label || l.label === 'connected')
}

/** The tie between two people (either way round), ignoring edges of other kinds. */
export function tieBetween (graph: LiveGraph, a: string, b: string): GraphLink | undefined {
  for (const l of graph.links.values()) {
    if (!isTie(l)) continue
    if ((l.source === a && l.target === b) || (l.source === b && l.target === a)) return l
  }
  return undefined
}

/** Edge properties that are the engine's plumbing (each end's private values). */
export const NOISY_LINK_KEYS = new Set(['inProps', 'outProps'])
