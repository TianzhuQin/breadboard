import type { GraphNode } from '@/lib/graph'
import { getPath, splitPath } from './players'

/**
 * The player filter (Players page and network search), compatible with the old console's:
 *
 *   score=5        equal (numbers compare as numbers, text ignores case)
 *   score<5        less than        score>5   more than
 *   name~ann       contains (ignores case)
 *   abc            plain text: the player's id contains it (ignores case)
 *
 * Paths can be dotted (`_system.connected=true`, `timers.0.id~x`). New: `<=`, `>=`, `!=`, and
 * several conditions joined with `&` (all must hold). Fixed from the old console: `score>=5` used
 * to be read as `score>` = `5`; `~` on a missing property crashed the list; `a=b=c` dropped `=c`.
 */

export type FilterOp = '=' | '!=' | '<' | '<=' | '>' | '>=' | '~'
export type Condition =
  | { kind: 'text', text: string }
  | { kind: 'expr', path: string[], op: FilterOp, value: string, source: string }

export interface ParsedFilter {
  conditions: Condition[]
  /** Set when part of the input can't be used; the rest still applies. */
  error?: string
}

const TWO = ['!=', '<=', '>=']
const ONE = ['=', '<', '>', '~']

function parseOne (raw: string): Condition | { error: string } | null {
  const s = raw.trim()
  if (!s) return null
  for (let i = 0; i < s.length; i++) {
    const two = s.slice(i, i + 2)
    const op = (TWO.includes(two) ? two : (ONE.includes(s[i]) ? s[i] : null)) as FilterOp | null
    if (!op) continue
    const left = s.slice(0, i).trim()
    const value = s.slice(i + op.length).trim()
    if (!left) return { error: `Put a property name before "${op}", e.g. score${op}5.` }
    const path = splitPath(left)
    if (!path.length) return { error: `"${left}" isn't a property name.` }
    return { kind: 'expr', path, op, value, source: s }
  }
  return { kind: 'text', text: s }
}

export function parseFilter (input: string | null | undefined): ParsedFilter {
  const out: ParsedFilter = { conditions: [] }
  if (!input || !input.trim()) return out
  for (const part of input.split('&')) {
    const c = parseOne(part)
    if (!c) continue
    if ('error' in c) { out.error = out.error || c.error; continue }
    out.conditions.push(c)
  }
  return out
}

function isNum (v: any): boolean {
  return typeof v === 'number' ? isFinite(v) : (typeof v === 'string' && v.trim() !== '' && isFinite(Number(v)))
}
function text (v: any): string {
  return typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)
}

function equals (actual: any, expected: string): boolean {
  if (actual === undefined || actual === null) return expected === 'null' || expected === ''
  if (typeof actual === 'boolean') return String(actual) === expected.toLowerCase() || (expected === '1' && actual) || (expected === '0' && !actual)
  if (typeof actual === 'number' || (isNum(actual) && isNum(expected))) {
    return isNum(expected) && Number(actual) === Number(expected)
  }
  return text(actual).toLowerCase() === expected.toLowerCase()
}

function compare (actual: any, expected: string): number | null {
  if (actual === undefined || actual === null || typeof actual === 'object') return null
  if (isNum(actual) && isNum(expected)) return Number(actual) - Number(expected)
  return String(actual).localeCompare(expected, undefined, { numeric: true, sensitivity: 'base' })
}

export interface MatchOptions {
  /** How plain text matches (default: the id contains it). */
  text?: (n: GraphNode, text: string) => boolean
}

export function matchCondition (c: Condition, n: GraphNode, opts: MatchOptions = {}): boolean {
  if (c.kind === 'text') return opts.text ? opts.text(n, c.text) : n.id.toLowerCase().includes(c.text.toLowerCase())
  const actual = getPath(n, c.path)
  switch (c.op) {
    case '=': return equals(actual, c.value)
    case '!=': return !equals(actual, c.value)
    case '~': return actual !== undefined && actual !== null && text(actual).toLowerCase().includes(c.value.toLowerCase())
    default: {
      const d = compare(actual, c.value)
      if (d === null) return false
      return c.op === '<' ? d < 0 : c.op === '<=' ? d <= 0 : c.op === '>' ? d > 0 : d >= 0
    }
  }
}

/** Whether a player passes every condition (an empty filter lets everyone through). */
export function matchFilter (f: ParsedFilter, n: GraphNode, opts: MatchOptions = {}): boolean {
  for (const c of f.conditions) if (!matchCondition(c, n, opts)) return false
  return true
}

const OP_WORDS: Record<FilterOp, string> = {
  '=': 'is', '!=': 'is not', '<': 'is less than', '<=': 'is at most', '>': 'is more than', '>=': 'is at least', '~': 'contains',
}
/** The filter in words, for screen readers and the hint under the box. */
export function describeFilter (f: ParsedFilter): string {
  if (!f.conditions.length) return ''
  return f.conditions.map(c => c.kind === 'text'
    ? `id contains "${c.text}"`
    : `${c.path.join('.')} ${OP_WORDS[c.op]} "${c.value}"`).join(' and ')
}

/** Whether the input uses the expression syntax (vs. a plain id search). */
export function isExpression (f: ParsedFilter): boolean {
  return f.conditions.some(c => c.kind === 'expr')
}
