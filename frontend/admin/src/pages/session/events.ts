import type { EventItem, LiveEvent } from '@/store/types'

/**
 * The live event feed: what the session has recorded, newest first.
 *
 * Two sources describe the same events: the session's User JSON carries everything recorded so
 * far (`experimentInstance.events`, `eventData: [{name, value}]`, server local time
 * "yyyy-MM-dd HH:mm:ss,S"), and the server pushes each new one as it happens (`{"event": …}`,
 * `data: {key: value}`, ISO-8601 UTC). They overlap whenever a fresh User JSON arrives, so they
 * are merged by event id.
 */

export interface FeedEvent {
  /** Stable key for rendering. */
  key: string
  id: number | null
  name: string
  /** Epoch ms; 0 when the server's time could not be read. */
  at: number
  data: Record<string, unknown>
  /** When this console received it live (this computer's clock); only on live pushes. */
  receivedAt?: number
}

/** How many events the feed keeps at most (newest first). */
export const FEED_CAP = 500

const SERVER_TIME = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:[,.](\d{1,3}))?$/

/**
 * Read a server timestamp: ISO-8601 (live pushes), or the instance JSON's
 * "yyyy-MM-dd HH:mm:ss,S" in server local time (milliseconds are not zero-padded: ",5" is 5 ms).
 */
export function parseServerTime (s: unknown): number {
  if (typeof s === 'number') return Number.isFinite(s) ? s : 0
  if (typeof s !== 'string' || !s.trim()) return 0
  const t = s.trim()
  const m = SERVER_TIME.exec(t)
  if (m) {
    const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], m[7] ? +m[7] : 0)
    return Number.isNaN(d.getTime()) ? 0 : d.getTime()
  }
  const p = Date.parse(t)
  return Number.isNaN(p) ? 0 : p
}

function signature (name: string, at: number, data: Record<string, unknown>) {
  let d = ''
  try { d = JSON.stringify(data) } catch (e) { d = '' }
  return `${name}|${at}|${d}`
}

export function fromSeed (e: EventItem): FeedEvent {
  const data: Record<string, unknown> = {}
  for (const item of e.eventData || []) {
    if (item && item.name != null) data[item.name] = item.value
  }
  const id = typeof e.id === 'number' ? e.id : null
  const at = parseServerTime(e.datetime)
  return { key: id != null ? `e${id}` : `s${signature(e.name, at, data)}`, id, name: String(e.name ?? ''), at, data }
}

export function fromLive (e: LiveEvent): FeedEvent {
  const raw = e && e.data
  let data: Record<string, unknown> = {}
  if (Array.isArray(raw)) {
    // tolerate the [{name, value}] shape too
    for (const item of raw) if (item && item.name != null) data[item.name] = item.value
  } else if (raw && typeof raw === 'object') {
    data = { ...raw }
  }
  const id = typeof e.id === 'number' ? e.id : null
  const at = parseServerTime(e.datetime)
  const f: FeedEvent = { key: id != null ? `e${id}` : `l${signature(String(e.name), at, data)}`, id, name: String(e.name ?? ''), at, data }
  if (typeof e.receivedAt === 'number') f.receivedAt = e.receivedAt
  return f
}

/**
 * Recorded and live events as one list, newest first, without duplicates, at most `cap` long.
 * Both lists may overlap: an event pushed live is also in the next User JSON, with the same id.
 */
export function mergeFeed (seed: FeedEvent[], live: FeedEvent[], cap = Infinity): FeedEvent[] {
  const byKey = new Map<string, FeedEvent>()
  for (const f of seed) if (!byKey.has(f.key)) byKey.set(f.key, f)
  let sigs: Set<string> | null = null
  for (const f of live) {
    if (byKey.has(f.key)) continue
    if (f.id == null) {
      // without an id it is the same event as an earlier copy with the same name, time and data
      // (built only when needed: the recorded list can hold thousands of events)
      if (!sigs) sigs = new Set(Array.from(byKey.values(), x => signature(x.name, x.at, x.data)))
      const sig = signature(f.name, f.at, f.data)
      if (sigs.has(sig)) continue
      sigs.add(sig)
    }
    byKey.set(f.key, f)
  }
  const order = (f: FeedEvent) => (f.id == null ? Number.MAX_SAFE_INTEGER : f.id)
  const list = Array.from(byKey.values()).sort((a, b) => (b.at - a.at) || (order(b) - order(a)))
  return list.length > cap ? list.slice(0, cap) : list
}

/** mergeFeed for the server's own shapes. */
export function mergeEvents (seed: EventItem[] | null | undefined, live: LiveEvent[] | null | undefined, cap = Infinity): FeedEvent[] {
  return mergeFeed((seed || []).filter(Boolean).map(fromSeed), (live || []).filter(Boolean).map(fromLive), cap)
}

/** Event names with how often each occurs, most frequent first. */
export function eventNameCounts (events: FeedEvent[]): { name: string, count: number }[] {
  const counts = new Map<string, number>()
  for (const e of events) counts.set(e.name, (counts.get(e.name) || 0) + 1)
  return Array.from(counts, ([name, count]) => ({ name, count }))
    .sort((a, b) => (b.count - a.count) || a.name.localeCompare(b.name))
}

export function filterEvents (events: FeedEvent[], name: string | null): FeedEvent[] {
  return name ? events.filter(e => e.name === name) : events
}

/** A value as short text for the feed. */
export function formatValue (v: unknown, max = 60): string {
  let s: string
  if (v == null) s = '—'
  else if (typeof v === 'string') s = v
  else if (typeof v === 'number' || typeof v === 'boolean') s = String(v)
  else {
    try { s = JSON.stringify(v) } catch (e) { s = String(v) }
  }
  s = s.replace(/\s+/g, ' ').trim()
  if (s === '') s = '""'
  return s.length > max ? `${s.slice(0, max - 1)}…` : s
}

/** The first few key/value pairs of an event, as text, and how many are left over. */
export function summarizeData (data: Record<string, unknown>, max = 3): { shown: [string, string][], more: number } {
  const entries = Object.entries(data || {})
  return {
    shown: entries.slice(0, max).map(([k, v]) => [k, formatValue(v)] as [string, string]),
    more: Math.max(0, entries.length - max),
  }
}

export type EventTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral'

/** A colour for the event's dot, so arrivals, departures and milestones stand out. */
export function eventTone (name: string): EventTone {
  const n = (name || '').toLowerCase()
  if (n === 'autopilotcancelled' || n.endsWith('error')) return 'danger'
  // ("Disconnected" itself is a tie being cut, not someone dropping out)
  if (n === 'clientdisconnected' || n === 'aitakeover' || n === 'sessionstopped' || n.endsWith('timeout')) return 'warning'
  if (n === 'clientconnected' || n === 'clientlogin' || n === 'aireturned' || n === 'prolificentry') return 'success'
  if (n.endsWith('start') || n === 'finished' || n === 'sessionresumed') return 'accent'
  return 'neutral'
}

const KNOWN: Record<string, string> = {
  clientlogin: 'Someone signed in',
  clientconnected: 'A participant connected',
  clientdisconnected: 'A participant lost connection',
  prolificentry: 'Arrived from Prolific',
  aitakeover: 'The computer took over a seat',
  aireturned: 'A seat went back to its person',
  autopilotstart: 'The waiting room started the game',
  autopilotcancelled: 'The waiting room gave up: too few people',
  finished: 'The session finished',
  edgeedit: 'A tie was changed',
  connected: 'Two people were tied',
  disconnected: 'A tie was cut',
  sessionstopped: 'A researcher stopped the session',
  sessionresumed: 'The session was resumed: its game starts again',
}

/** A plain-words line for well-known events; '' for the experiment's own. */
export function describeEvent (name: string): string {
  const n = (name || '').toLowerCase()
  if (KNOWN[n]) return KNOWN[n]
  if (n.length > 5 && n.endsWith('start')) return `Step “${name.slice(0, -5)}” started`
  if (n.length > 7 && n.endsWith('timeout')) return `Step “${name.slice(0, -7)}” timed out`
  return ''
}

/** Distinct participants named in the events (client ids, not AI seats starting with "_"). */
export function participantsSeen (events: FeedEvent[]): Set<string> {
  const ids = new Set<string>()
  for (const e of events) {
    const n = e.name.toLowerCase()
    if (n !== 'clientlogin' && n !== 'clientconnected' && n !== 'prolificentry') continue
    const id = e.data.clientId ?? e.data.playerId ?? e.data.id
    if (id != null && String(id) && !String(id).startsWith('_')) ids.add(String(id))
  }
  return ids
}

/** "14:05:09", and the day ("27 Oct") when it isn't today. */
export function clockParts (at: number, now = Date.now()): { time: string, date: string | null } {
  if (!at) return { time: '—', date: null }
  const d = new Date(at)
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  if (d.toDateString() === new Date(now).toDateString()) return { time, date: null }
  return { time, date: d.toLocaleDateString([], { day: 'numeric', month: 'short' }) }
}
