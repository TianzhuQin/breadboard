import { watch } from 'vue'
import { HttpError } from '@/lib/http'
import type { Instance, InstanceStatus } from '@/store/types'

/**
 * Pure helpers for the Sessions & data page: dates, ordering, filtering, events and CSV.
 * Kept free of components so they can be tested on their own (test/sessions.test.ts).
 */

// ---- dates ------------------------------------------------------------------------------------

/**
 * Parse a time the server sends. Two shapes reach the console:
 * - "yyyy-MM-dd HH:mm:ss,S" (session creationTime, ShowEvent and User JSON events): the server's
 *   local time, from Java's SimpleDateFormat with ONE "S", so milliseconds are not padded:
 *   ",5" is 5 ms and ",95" is 95 ms (a string sort put ",95" after ",123", §3.6.3). Read as the
 *   browser's local time, which matches whenever server and browser share a time zone.
 * - ISO-8601 (live {"event"} pushes), e.g. "2026-10-27T12:06:00.005Z".
 * Returns null for anything else.
 */
export function parseServerTime (text: string | null | undefined): Date | null {
  if (!text || typeof text !== 'string') return null
  const s = text.trim()
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?(?:[,.:](\d{1,9}))?$/.exec(s)
  if (m) {
    const [, y, mo, d, h, mi, sec, frac] = m
    if (+mo < 1 || +mo > 12 || +d < 1 || +d > 31 || +h > 23 || +mi > 59 || +(sec || 0) > 59) return null
    // the comma form counts whole milliseconds; a dot (or colon) is a decimal fraction
    let ms = 0
    if (frac) ms = s.includes(',') ? Number(frac) : Math.round(Number(`0.${frac}`) * 1000)
    if (ms > 999) ms = 999
    const date = new Date(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(sec || 0), ms)
    return isNaN(date.getTime()) ? null : date
  }
  if (/^\d{4}-\d{2}-\d{2}T/.test(s)) {
    const t = Date.parse(s)
    return isNaN(t) ? null : new Date(t)
  }
  return null
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0')

/** "Oct 27, 2026, 1:06 PM" in the researcher's own locale. */
export function formatDateTime (d: Date | null, opts: { seconds?: boolean } = {}): string {
  if (!d) return '—'
  try {
    return d.toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
      second: opts.seconds ? '2-digit' : undefined,
    })
  } catch (e) {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }
}

/** "13:06:00.005": event times need the milliseconds to tell close events apart. */
export function formatClock (d: Date | null): string {
  if (!d) return '—'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}

/** "Oct 27" */
export function formatDay (d: Date | null): string {
  if (!d) return ''
  try { return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) } catch (e) { return `${d.getMonth() + 1}/${d.getDate()}` }
}

export function sameDay (a: Date | null, b: Date | null): boolean {
  return !!a && !!b && a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** "4 min", "2 h 5 min", "3 days" between two times. */
export function formatSpan (from: Date | null, to: Date | null): string {
  if (!from || !to) return ''
  const s = Math.max(0, Math.round((to.getTime() - from.getTime()) / 1000))
  if (s < 60) return `${s} s`
  const min = Math.floor(s / 60)
  if (min < 60) return `${min} min`
  const h = Math.floor(min / 60)
  if (h < 48) return min % 60 ? `${h} h ${min % 60} min` : `${h} h`
  return `${Math.floor(h / 24)} days`
}

// ---- sessions ---------------------------------------------------------------------------------

export interface StatusMeta {
  label: string
  tone: 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'
  pulse?: boolean
  /** One plain sentence on what the status means. */
  hint: string
}

const STATUS: Record<InstanceStatus, StatusMeta> = {
  RUNNING: { label: 'Running', tone: 'success', pulse: true, hint: 'Participants can join and play.' },
  TESTING: { label: 'Testing', tone: 'info', hint: 'The built-in test session.' },
  STOPPED: { label: 'Stopped', tone: 'warning', hint: 'Paused. Nobody can join until it is resumed.' },
  FINISHED: { label: 'Finished', tone: 'neutral', hint: 'The game ended. Its data is complete.' },
  ARCHIVED: { label: 'Archived', tone: 'neutral', hint: 'Archived. It can no longer run.' },
}
export function statusMeta (status: string): StatusMeta {
  const known = STATUS[status as InstanceStatus]
  if (known) return known
  const label = status ? status.charAt(0) + status.slice(1).toLowerCase() : 'Unknown'
  return { label, tone: 'neutral', hint: '' }
}

/** Only these can be deleted (the server ignores the rest, Breadboard.java DeleteInstance). */
export function canDelete (s: Pick<Instance, 'status'>): boolean {
  return s.status === 'STOPPED' || s.status === 'FINISHED'
}
/** SelectInstance restarts only runnable sessions; finished and archived ones would just be selected. */
export function canResume (s: Pick<Instance, 'status'>): boolean {
  return s.status === 'STOPPED' || s.status === 'RUNNING' || s.status === 'TESTING'
}
export function canStop (s: Pick<Instance, 'status'>): boolean {
  return s.status === 'RUNNING' || s.status === 'TESTING'
}

export type SessionSortKey = 'created' | 'name' | 'status' | 'params'
export type SortDir = 'asc' | 'desc'
const STATUS_ORDER: Record<string, number> = { RUNNING: 0, TESTING: 1, STOPPED: 2, FINISHED: 3, ARCHIVED: 4 }
const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

/**
 * Sessions in order. "created" orders by id: ids follow creation exactly, while creationTime is a
 * local-time string with unpadded milliseconds that the old console sorted as text (§3.6.3).
 * Ties always fall back to the id, so the order is stable.
 */
export function sortSessions<T extends Pick<Instance, 'id' | 'name' | 'status' | 'data'>> (list: readonly T[], key: SessionSortKey = 'created', dir: SortDir = 'desc'): T[] {
  const sign = dir === 'asc' ? 1 : -1
  const byId = (a: T, b: T) => (a.id || 0) - (b.id || 0)
  const cmp = (a: T, b: T): number => {
    switch (key) {
      case 'name': return collator.compare(a.name || '', b.name || '') || byId(a, b)
      case 'status': return ((STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9)) || byId(a, b)
      case 'params': return ((a.data?.length || 0) - (b.data?.length || 0)) || byId(a, b)
      default: return byId(a, b)
    }
  }
  return [...list].sort((a, b) => sign * cmp(a, b))
}

/** Search by name, id ("12" or "#12") or a launch parameter's name or value. */
export function matchesSession (s: Pick<Instance, 'id' | 'name' | 'data'>, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const idq = q.startsWith('#') ? q.slice(1) : q
  if (String(s.id) === idq) return true
  if ((s.name || '').toLowerCase().includes(q)) return true
  return (s.data || []).some(d => `${d.name}`.toLowerCase().includes(q) || `${d.value}`.toLowerCase().includes(q))
}

// ---- events -----------------------------------------------------------------------------------

export interface DataPair { name: string, value: string }
export interface SessionEvent {
  /** Database id; live pushes may lack it. */
  id: number | null
  name: string
  time: Date | null
  /** As the server sent it. */
  raw: string
  data: DataPair[]
}

function valueText (v: unknown): string {
  if (v === null || v === undefined) return ''
  if (typeof v === 'object') { try { return JSON.stringify(v) } catch (e) { return String(v) } }
  return String(v)
}

/**
 * One event in a common shape. The server has three: ShowEvent's `data: [{name,value}]`, User
 * JSON's `eventData: [{name,value}]`, and the live push's `data: {name: value}`.
 */
export function normalizeEvent (e: any): SessionEvent {
  const list = Array.isArray(e?.data) ? e.data : Array.isArray(e?.eventData) ? e.eventData : null
  let data: DataPair[]
  if (list) {
    data = list.map((d: any) => ({ name: valueText(d?.name), value: valueText(d?.value) }))
  } else if (e?.data && typeof e.data === 'object') {
    data = Object.keys(e.data).map(k => ({ name: k, value: valueText(e.data[k]) }))
  } else {
    data = []
  }
  const id = typeof e?.id === 'number' ? e.id : (e?.id != null && !isNaN(Number(e.id)) ? Number(e.id) : null)
  const raw = typeof e?.datetime === 'string' ? e.datetime : ''
  return { id, name: valueText(e?.name) || '(unnamed)', time: parseServerTime(raw), raw, data }
}

/**
 * Whether an event the server pushed live belongs to this session. The push goes to every console
 * whatever session recorded it; newer servers name that session (experimentInstanceId). Without
 * the name, an event is only taken while this session is the one running, the best guess there is.
 */
export function liveEventOf (e: { experimentInstanceId?: unknown }, sessionId: number, running: boolean): boolean {
  const id = e.experimentInstanceId
  if (id === undefined) return running
  return id !== null && id !== '' && Number(id) === sessionId
}

/** By time, then by id (events in the same millisecond keep the order they were recorded in). */
export function sortEvents (events: readonly SessionEvent[]): SessionEvent[] {
  return [...events].sort((a, b) => {
    const ta = a.time ? a.time.getTime() : -Infinity
    const tb = b.time ? b.time.getTime() : -Infinity
    if (ta !== tb) return ta < tb ? -1 : 1
    return (a.id ?? Number.MAX_SAFE_INTEGER) - (b.id ?? Number.MAX_SAFE_INTEGER)
  })
}

/** Join two lists of events, keeping one copy of each id. */
export function mergeEvents (base: readonly SessionEvent[], extra: readonly SessionEvent[]): SessionEvent[] {
  const seen = new Set<number>()
  const out: SessionEvent[] = []
  for (const e of [...base, ...extra]) {
    if (e.id != null) {
      if (seen.has(e.id)) continue
      seen.add(e.id)
    }
    out.push(e)
  }
  return out
}

export interface EventFilter { query?: string, name?: string | null }
export function filterEvents (events: readonly SessionEvent[], f: EventFilter): SessionEvent[] {
  const q = (f.query || '').trim().toLowerCase()
  return events.filter(e => {
    if (f.name && e.name !== f.name) return false
    if (!q) return true
    if (e.name.toLowerCase().includes(q)) return true
    if (e.id != null && String(e.id) === q) return true
    return e.data.some(d => d.name.toLowerCase().includes(q) || d.value.toLowerCase().includes(q))
  })
}

export interface EventGroup {
  name: string
  events: SessionEvent[]
  first: Date | null
  last: Date | null
  /** Every data name seen in this event, in first-seen order: the columns of its table. */
  keys: string[]
}
/** Events grouped by name, alphabetically; each group keeps the events' order. */
export function groupEvents (events: readonly SessionEvent[]): EventGroup[] {
  const map = new Map<string, EventGroup>()
  for (const e of events) {
    let g = map.get(e.name)
    if (!g) { g = { name: e.name, events: [], first: null, last: null, keys: [] }; map.set(e.name, g) }
    g.events.push(e)
    if (e.time) {
      if (!g.first || e.time < g.first) g.first = e.time
      if (!g.last || e.time > g.last) g.last = e.time
    }
    for (const d of e.data) if (!g.keys.includes(d.name)) g.keys.push(d.name)
  }
  return [...map.values()].sort((a, b) => collator.compare(a.name, b.name))
}

/** Event names with how often each occurs, for the filter menu. */
export function eventNameCounts (events: readonly SessionEvent[]): { name: string, count: number }[] {
  const counts = new Map<string, number>()
  for (const e of events) counts.set(e.name, (counts.get(e.name) || 0) + 1)
  return [...counts.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => collator.compare(a.name, b.name))
}

// ---- CSV --------------------------------------------------------------------------------------

/**
 * One CSV field: always quoted, quotes doubled (RFC 4180), so commas, quotes and newlines survive.
 * The value is written exactly as recorded, with no spreadsheet-formula escaping (no ' put before
 * = + - @), like the server's own file (Application.csvField): researchers analyse both, so a
 * participant's "@anna yes" or "-" must stay exactly that.
 */
export function csvField (v: unknown): string {
  return `"${valueText(v).replace(/"/g, '""')}"`
}
export function toCsv (rows: readonly (readonly unknown[])[]): string {
  return rows.map(r => r.map(csvField).join(',')).join('\n') + '\n'
}

/**
 * An event's time as the server's CSV files write it (Timestamps.csv): UTC, with a colon before the
 * milliseconds, "2026-10-28T13:24:23:757Z", so one way of reading the times reads every file. These
 * files used to write "2026-10-28T13:24:23.757Z", which a script for the server's files can't read.
 */
export function csvTime (e: SessionEvent): string {
  return e.time ? e.time.toISOString().replace(/\.(\d{3})Z$/, ':$1Z') : e.raw
}

/**
 * The same columns and values as the server's event file (GET /csv/data/:id), so analysis scripts
 * read both: one row per data item, and, as there, a row with empty data columns for an event
 * without data.
 */
export function eventsToCsv (events: readonly SessionEvent[]): string {
  const rows: unknown[][] = [['id', 'event', 'event_date', 'data_name', 'data_value']]
  for (const e of events) {
    if (!e.data.length) rows.push([e.id ?? '', e.name, csvTime(e), '', ''])
    for (const d of e.data) rows.push([e.id ?? '', e.name, csvTime(e), d.name, d.value])
  }
  return toCsv(rows)
}

/** One row per event, one column per data name: handy for a single kind of event. Times as in eventsToCsv. */
export function eventsToWideCsv (events: readonly SessionEvent[], keys?: readonly string[]): string {
  const cols = keys ? [...keys] : groupEvents(events).flatMap(g => g.keys).filter((k, i, all) => all.indexOf(k) === i)
  const rows: unknown[][] = [['id', 'event', 'event_date', ...cols]]
  for (const e of events) {
    const byName = new Map(e.data.map(d => [d.name, d.value]))
    rows.push([e.id ?? '', e.name, csvTime(e), ...cols.map(c => byName.get(c) ?? '')])
  }
  return toCsv(rows)
}

/** A name that every operating system accepts as a file name (spaces are kept, like before). */
export function safeFileName (name: string, fallback = 'session'): string {
  // eslint-disable-next-line no-control-regex
  // (letters in any script, digits, spaces and . _ ( ) - only: "Autopilot · 2026-10-30 13:20:47 UTC"
  // becomes "Autopilot - 2026-10-30 13-20-47 UTC", easier in a shell or an analysis script. Any space
  // is a plain one first: the autopilot's names have no-break spaces, which became "-".)
  const cleaned = (name || '').replace(/\s+/gu, ' ').replace(/[,;]/g, '').replace(/[^\p{L}\p{N} ._()-]+/gu, '-').replace(/\s+/g, ' ').trim().replace(/^\.+/, '')
  return (cleaned || fallback).slice(0, 120)
}

/** How many data rows a CSV text holds (lines after the header, ignoring a trailing newline). */
export function csvDataRows (text: string): number {
  const lines = (text || '').split(/\r?\n/).filter(l => l.trim() !== '')
  return Math.max(0, lines.length - 1)
}

// ---- errors and waiting -----------------------------------------------------------------------

/** An error in words a researcher can act on (never a raw HTML error page). */
export function describeError (e: unknown): string {
  if (e instanceof HttpError) {
    let text = (e.message || '').trim()
    if (/^\s*</.test(text)) {
      // Play's error pages are HTML; keep only their title
      const title = /<title>([^<]*)<\/title>/i.exec(text)
      text = title ? title[1].trim() : ''
    }
    if (e.status === 404) return 'The server could not find it. It may have been deleted.'
    if (e.status === 403) return 'The server refused: your account may not have access to it.'
    if (e.status >= 500) return text ? `The server ran into an error (${e.status}): ${text}` : `The server ran into an error (${e.status}).`
    return text || `The server answered with error ${e.status}.`
  }
  if (e instanceof TypeError) return 'Could not reach the server. Check that it is running and that you are online.'
  if (e instanceof Error) return e.message
  return String(e)
}

/** Resolve true as soon as `test()` holds, or false after `ms`. */
export function waitUntil (test: () => boolean, ms: number): Promise<boolean> {
  if (test()) return Promise.resolve(true)
  return new Promise(resolve => {
    let stop: (() => void) | null = null
    const timer = setTimeout(() => { stop?.(); resolve(false) }, ms)
    stop = watch(test, (ok) => {
      if (!ok) return
      clearTimeout(timer)
      stop?.()
      resolve(true)
    })
  })
}

/** The page participants open to join this session. */
export function participantLink (experimentId: number, instanceId: number, origin = window.location.origin): string {
  return `${origin}/game/${experimentId}/${instanceId}/login`
}
/** A link built on this computer's own name only works for people on this computer. */
export function isLocalHost (hostname = window.location.hostname): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]' || hostname === '::1' || hostname.endsWith('.localhost')
}
