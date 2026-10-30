import type { EventItem, LiveEvent } from '@/store/types'
import { aiName, playerName } from '@/network/players'

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

/** Data names whose value is a player's id. */
const PLAYER_KEYS = new Set(['player', 'playerid', 'clientid'])

/**
 * A data value as short text for the feed (formatValue), with a computer player's id named as the
 * other pages name it: "AI 4 (_4)".
 */
export function formatDataValue (key: string, v: unknown, max = 60): string {
  if (typeof v === 'string' && PLAYER_KEYS.has(key.toLowerCase())) return formatValue(playerName(v), max)
  return formatValue(v, max)
}

/** The first few key/value pairs of an event, as text, and how many are left over. */
export function summarizeData (data: Record<string, unknown>, max = 3): { shown: [string, string][], more: number } {
  const entries = Object.entries(data || {})
  return {
    shown: entries.slice(0, max).map(([k, v]) => [k, formatDataValue(k, v)] as [string, string]),
    more: Math.max(0, entries.length - max),
  }
}

export type EventTone = 'success' | 'warning' | 'danger' | 'accent' | 'neutral'

// Failures, and what interrupted or ended a game, stand out; arrivals and milestones too.
const DANGER = new Set(['autopilotcancelled', 'sessioninterrupted', 'stepsfailedtoload', 'stepfailed', 'sessionstalled', 'sessionfailed'])
// ("Disconnected" itself is a tie being cut, not someone dropping out)
// (and what a participant lost: a code they weren't given, an answer that came too late)
const WARNING = new Set(['clientdisconnected', 'aitakeover', 'sessionstopped', 'sessiontoolong', 'serverstopping', 'missedstart', 'sessionfull',
  'nocompletioncode', 'answerdropped'])
const SUCCESS = new Set(['clientconnected', 'clientlogin', 'aireturned', 'prolificentry', 'prolificcomplete'])
const ACCENT = new Set(['finished', 'sessionresumed', 'enginereloaded'])

/** A colour for the event's dot, so arrivals, departures, failures and milestones stand out. */
export function eventTone (name: string): EventTone {
  const n = (name || '').toLowerCase()
  if (DANGER.has(n) || n.endsWith('error')) return 'danger'
  if (WARNING.has(n) || n.endsWith('timeout')) return 'warning'
  if (SUCCESS.has(n)) return 'success'
  // (a step's own "…Start"; missedStart, above, isn't one)
  if (ACCENT.has(n) || n.endsWith('start')) return 'accent'
  return 'neutral'
}

const KNOWN: Record<string, string> = {
  clientlogin: 'Someone signed in',
  clientconnected: 'A participant connected',
  clientdisconnected: 'A participant lost connection',
  connectiongap: 'A participant’s connection dropped for a moment, then came back',
  answerdropped: 'A participant’s answer was dropped: it waited too long for the connection',
  participantcomment: 'A participant left a comment',
  prolificentry: 'Arrived from Prolific',
  prolificcomplete: 'A Prolific participant was given their completion code',
  nocompletioncode: 'A Prolific participant finished, but no completion code is set',
  aitakeover: 'The computer took over a seat',
  aireturned: 'A seat went back to its person',
  aichoice: 'The computer chose for a seat it plays',
  autopilotstart: 'The waiting room started the game',
  autopilotcancelled: 'The waiting room gave up: too few people',
  missedstart: 'The game started without someone who was away',
  sessionfull: 'Someone arrived when the session was already full',
  finished: 'The session finished',
  edgeedit: 'A tie was changed',
  connected: 'Two people were tied',
  disconnected: 'A tie was cut',
  sessionstopped: 'A researcher stopped the session',
  sessionresumed: 'The session was resumed: its game starts again',
  enginereloaded: 'A researcher reloaded the engine',
  sessioninterrupted: 'The server stopped while the session ran (a restart or crash)',
  serverstopping: 'The server was stopped while the session ran',
  stepsfailedtoload: 'The steps didn\'t load, so the autopilot stopped the session',
  stepfailed: 'A step failed: its code threw an error',
  sessionfailed: 'A step failure stalled the game, so the autopilot ended the session',
  sessionstalled: 'After a step failed, the game had nothing left to do, so it was ended',
  sessiontoolong: 'The game ran longer than the longest session allowed, so it was ended',
}

/** "RoundStep, line 72": a failure's step, and its line (its own `line`, or the "line 72: …" its error starts with). */
function failurePlace (data: Record<string, unknown>): string {
  const step = typeof data.step === 'string' ? data.step.trim() : ''
  const n = Number(data.line)
  let line = Number.isInteger(n) && n > 0 ? n : null
  if (line == null) {
    const m = /^line (\d+):/.exec(String(data.error ?? ''))
    if (m) line = Number(m[1])
  }
  return [step, line ? `line ${line}` : ''].filter(Boolean).join(', ')
}

/** "25 s", "3 min": how long, from milliseconds; '' when it isn't a time. */
function lasted (ms: unknown): string {
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return ''
  const s = Math.max(1, Math.round(n / 1000))
  return s < 120 ? `${s} s` : `${Math.round(s / 60)} min`
}

/** The line, with what the event's data says that matters: the step that failed, whether a game started again. */
function withData (n: string, data: Record<string, unknown>): string {
  const place = failurePlace(data)
  if (n === 'stepfailed' && place) return `A step failed (${place}): its code threw an error`
  if (n === 'sessionfailed' && place) return `A step failure (${place}) stalled the game, so the autopilot ended the session`
  if (n === 'sessionstalled' && place) return `After a step failed (${place}), the game had nothing left to do, so it was ended`
  if (n === 'enginereloaded' && 'restarted' in data) {
    return data.restarted === true || data.restarted === 'true'
      ? 'A researcher reloaded the engine: the game started again from the beginning'
      : 'A researcher reloaded the engine; the session carried on'
  }
  if (n === 'connectiongap' && lasted(data.awayMs)) return `A participant’s connection dropped for ${lasted(data.awayMs)}, then came back`
  if (n === 'answerdropped' && lasted(data.ageMs)) return `A participant’s answer was dropped: it waited ${lasted(data.ageMs)} for the connection`
  return ''
}

/**
 * A plain-words line for well-known events; '' for the experiment's own. With the event's `data`, a
 * researcher's preview signing in as a player (recorded with preview = true) says so, and a failure
 * names its step and line.
 */
export function describeEvent (name: string, data?: Record<string, unknown> | null): string {
  const n = (name || '').toLowerCase()
  if (n === 'clientlogin' && data && (data.preview === true || data.preview === 'true')) {
    const id = data.clientId
    const who = typeof id === 'string' && id ? aiName(id) ?? id : ''
    return who ? `A researcher’s preview signed in as ${who}` : 'A researcher’s preview signed in'
  }
  const said = data ? withData(n, data) : ''
  if (said) return said
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
