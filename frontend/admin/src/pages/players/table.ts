import type { GraphNode } from '@/lib/graph'
import { choicesOf, compareIds, compareValues, connectionOf, isAi, recruitmentOf, statusOf, valueOf } from '@/network/players'

/** The Players table: what each column shows and sorts by, the quick filters, and CSV. */

export interface Column { key: string, label: string, numeric?: boolean, base?: boolean }

export const BASE_COLUMNS: Column[] = [
  { key: 'id', label: 'Player', base: true },
  { key: '@status', label: 'Status', base: true },
  { key: '@ties', label: 'Ties', numeric: true, base: true },
  { key: '@choice', label: 'Choice', base: true },
]

export type Degree = (id: string) => number

/** The value a column sorts by. */
export function cellValue (n: GraphNode, key: string, degree: Degree): any {
  if (key === '@choice') return choicesOf(n).length || undefined
  return valueOf(n, key, degree)
}

export interface Sort { key: string, dir: 1 | -1 }

export function sortNodes (nodes: GraphNode[], sort: Sort, degree: Degree): GraphNode[] {
  const out = nodes.slice()
  if (sort.key === 'id') return out.sort((a, b) => compareIds(a.id, b.id) * sort.dir)
  return out.sort((a, b) => {
    const va = cellValue(a, sort.key, degree)
    const vb = cellValue(b, sort.key, degree)
    const ma = va === undefined || va === null || va === ''
    const mb = vb === undefined || vb === null || vb === ''
    // people without a value stay at the bottom either way
    if (ma || mb) return ma === mb ? compareIds(a.id, b.id) : ma ? 1 : -1
    return (compareValues(va, vb) * sort.dir) || compareIds(a.id, b.id)
  })
}

export type QuickFilter = 'all' | 'people' | 'ai' | 'connected' | 'disconnected' | 'choice'

export const QUICK_FILTERS: { value: QuickFilter, label: string }[] = [
  { value: 'all', label: 'Everyone' },
  { value: 'people', label: 'People' },
  { value: 'ai', label: 'AI players' },
  { value: 'connected', label: 'Connected' },
  { value: 'disconnected', label: 'Disconnected' },
  { value: 'choice', label: 'Has a choice' },
]

export function quickMatch (n: GraphNode, q: QuickFilter): boolean {
  switch (q) {
    case 'people': return !isAi(n)
    case 'ai': return isAi(n)
    case 'connected': return !isAi(n) && connectionOf(n) === 'connected'
    case 'disconnected': return !isAi(n) && connectionOf(n) === 'disconnected'
    case 'choice': return choicesOf(n).length > 0
    default: return true
  }
}

const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i

/**
 * One CSV cell. Text a spreadsheet would run as a formula (starting with = + - @, a tab or a
 * carriage return) gets a leading apostrophe, because ids and answers are typed by participants;
 * numbers, and text that is just a number like -0.5, are left alone.
 */
export function csvCell (v: any): string {
  if (v === undefined || v === null) return ''
  let s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  if (typeof v === 'string' && /^[=+\-@\t\r]/.test(s) && !NUMBER.test(s)) s = `'${s}`
  // quotes doubled and fields quoted when needed (the old CSVs didn't escape quotes)
  return /[",\n\r]/.test(s) || /^\s|\s$/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** The engine's plumbing on a player, left out of the CSV (status, ties and choices have columns). */
const CSV_SKIP = new Set(['text', 'choices', 'timers', '_system', 'private', 'edgeEdits', 'lastupdated', 'timerUpdatedAt', 'ai'])
const RECRUITMENT_KEYS = ['source', 'prolificPid', 'studyId', 'sessionId']

/**
 * The property columns of the CSV: every property the experiment set on anyone (maps and lists
 * as JSON), then the engine group and how people were recruited (Prolific ids), when anyone has them.
 */
export function csvKeys (nodes: GraphNode[]): string[] {
  const own = new Set<string>()
  let group = false
  const recruitment = new Set<string>()
  for (const n of nodes) {
    for (const k of Object.keys(n.data)) if (!CSV_SKIP.has(k)) own.add(k)
    const sys = n.data._system
    if (!sys || typeof sys !== 'object') continue
    if (sys.groupId !== undefined && sys.groupId !== null && sys.groupId !== '') group = true
    const r = recruitmentOf(n)
    if (r) for (const k of RECRUITMENT_KEYS) if ((r as any)[k] != null && (r as any)[k] !== '') recruitment.add(k)
  }
  const keys = Array.from(own).sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }))
  if (group) keys.push('_system.groupId')
  for (const k of RECRUITMENT_KEYS) if (recruitment.has(k)) keys.push(`_system.recruitment.${k}`)
  return keys
}

/**
 * A CSV of the given players: id, status, ties, then the given properties. It starts with a byte
 * order mark so Excel reads names and answers in any language as UTF-8.
 */
export function toCsv (nodes: GraphNode[], keys: string[], degree: Degree): string {
  const head = ['id', 'status', 'ties', 'choices waiting', ...keys]
  const lines = [head.map(csvCell).join(',')]
  for (const n of nodes) {
    lines.push([
      n.id,
      statusOf(n),
      degree(n.id),
      choicesOf(n).length,
      ...keys.map(k => valueOf(n, k, degree)),
    ].map(csvCell).join(','))
  }
  return '\uFEFF' + lines.join('\r\n') + '\r\n'
}
