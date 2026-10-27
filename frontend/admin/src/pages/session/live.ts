import type { GraphNode } from '@/lib/graph'
import type { OutputEntry } from '@/store/types'
import type { FeedEvent } from './events'

/** AI players added with g.addAI have ids starting with "_". */
export function isAiId (id: string): boolean {
  return String(id).startsWith('_')
}

function aiFlag (v: unknown): boolean {
  return v === 1 || v === true || v === '1' || v === 'true'
}

export interface NetworkStats {
  /** People in the network (not AI players). */
  people: number
  /** Of those, how many have the participant page open right now (`_system.connected`). */
  connected: number
  /** Seats the computer plays: AI players plus people it took over for. */
  aiSeats: number
  bots: number
  takenOver: number
  ties: number
}

export function networkStats (nodes: Iterable<GraphNode>, ties: number): NetworkStats {
  let people = 0
  let connected = 0
  let bots = 0
  let takenOver = 0
  for (const n of nodes) {
    if (isAiId(n.id)) { bots++; continue }
    people++
    const sys = n.data && n.data._system
    if (sys && typeof sys === 'object' && sys.connected === true) connected++
    if (aiFlag(n.data && n.data.ai)) takenOver++
  }
  return { people, connected, aiSeats: bots + takenOver, bots, takenOver, ties }
}

export type WaitingState = 'waiting' | 'starting' | 'cancelled'
export interface WaitingRoom {
  state: WaitingState
  here: number
  min: number | null
  max: number | null
  /** Seconds left in the countdown, as the server last told the participants. */
  seconds: number | null
}

function num (v: unknown): number | null {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
  return Number.isFinite(n) ? n : null
}

/**
 * The autopilot's waiting room as the participants see it (`_system.autopilot` on each player,
 * groovy/autopilot.groovy). A countdown wins over waiting, waiting over cancelled.
 */
export function waitingRoom (nodes: Iterable<GraphNode>): WaitingRoom | null {
  let best: WaitingRoom | null = null
  const rank: Record<WaitingState, number> = { starting: 3, waiting: 2, cancelled: 1 }
  for (const n of nodes) {
    if (isAiId(n.id)) continue
    const sys = n.data && n.data._system
    const ap = sys && typeof sys === 'object' ? sys.autopilot : null
    if (!ap || typeof ap !== 'object') continue
    const state = ap.state as WaitingState
    if (!(state in rank)) continue
    const cur: WaitingRoom = {
      state,
      here: num(ap.here) ?? 0,
      min: num(ap.min),
      max: num(ap.max),
      seconds: state === 'starting' ? num(ap.seconds) : null,
    }
    if (!best || rank[state] > rank[best.state]) {
      best = cur
    } else if (rank[state] === rank[best.state]) {
      best.here = Math.max(best.here, cur.here)
      best.min = best.min ?? cur.min
      best.max = best.max ?? cur.max
      // the player told most recently has the fewest seconds left
      if (cur.seconds != null && (best.seconds == null || cur.seconds < best.seconds)) best.seconds = cur.seconds
    }
  }
  return best
}

export interface JoinLinks {
  /** This session's sign-in page; stops working when the session ends. */
  session: string | null
  /** Whichever session of the experiment is open and not yet started. */
  current: string
  /** For Prolific's study URL; Prolific fills in the {{%…%}} placeholders. */
  prolific: string
}

export const PROLIFIC_QUERY = 'PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}'

export function joinLinks (origin: string, experimentId: number, instanceId: number | null): JoinLinks {
  const base = origin.replace(/\/+$/, '')
  return {
    session: instanceId != null && instanceId > 0 ? `${base}/game/${experimentId}/${instanceId}/login` : null,
    current: `${base}/play/${experimentId}/login`,
    prolific: `${base}/play/${experimentId}/prolific?${PROLIFIC_QUERY}`,
  }
}

/** Participants elsewhere can't open a link to this computer's own address. */
export function isLocalOrigin (origin: string): boolean {
  try {
    const h = new URL(origin).hostname
    return h === 'localhost' || h === '127.0.0.1' || h === '[::1]' || h === '::1' || h.endsWith('.localhost')
  } catch (e) {
    return false
  }
}

const START_OUTPUT = 'initStep.start()'
const ALREADY = 'initStep started already'
/** Events that mean the game began: the default InitStep's own, and the autopilot's start. */
const START_EVENTS = new Set(['initstepstart', 'autopilotstart'])

export interface StartedInfo {
  started: boolean
  /** How we know: the server said so, the engine answered a start, or the events show it. */
  source: 'server' | 'output' | 'events' | null
  at: number | null
}

/**
 * Whether initStep.start() has run since the engine last loaded. The server keeps that flag only
 * in the engine, so this reads the signs: an explicit `hasStarted` on the session (if the server
 * sends it), the engine's answer to a start (output "initStep.start() ==> …" or the error
 * "initStep started already") after the last reload, or a start event in the session's log.
 */
export function detectGameStarted (opts: { hasStarted?: unknown, events: FeedEvent[], output: OutputEntry[], since: number }): StartedInfo {
  if (opts.hasStarted === true) return { started: true, source: 'server', at: null }
  for (let i = opts.output.length - 1; i >= 0; i--) {
    const o = opts.output[i]
    if (o.at < opts.since) break
    if (o.kind === 'output' && o.text.startsWith(START_OUTPUT)) return { started: true, source: 'output', at: o.at }
    if (o.kind === 'error' && o.text.includes(ALREADY)) return { started: true, source: 'output', at: o.at }
  }
  // the engine forgets a start when it reloads; events from before that don't count
  const since = opts.since ? opts.since - 5000 : 0
  for (const e of opts.events) {
    if (since && e.at && e.at < since) continue
    if (START_EVENTS.has(e.name.toLowerCase())) return { started: true, source: 'events', at: e.at || null }
  }
  return { started: false, source: null, at: null }
}

export type StartOutcome = { kind: 'started' } | { kind: 'already' } | { kind: 'error', message: string } | { kind: 'timeout' }

/** Is this server message the engine's answer to RunGame? */
export function isStartReply (m: any): boolean {
  if (!m || typeof m !== 'object' || 'action' in m) return false
  if (typeof m.output === 'string' && m.output.startsWith(START_OUTPUT)) return true
  // a failed start answers with an error and no output
  return typeof m.error === 'string' && m.error.trim() !== '' && !(typeof m.output === 'string' && m.output.trim())
}

export function classifyStartReply (m: any): StartOutcome {
  if (!m) return { kind: 'timeout' }
  if (typeof m.output === 'string' && m.output.startsWith(START_OUTPUT)) return { kind: 'started' }
  const err = typeof m.error === 'string' ? m.error : ''
  if (err.includes(ALREADY)) return { kind: 'already' }
  return { kind: 'error', message: cleanError(err) || 'The engine reported an error.' }
}

/** An engine error without its "Caught error:" prefix and stack lines, for one-line display. */
export function cleanError (text: string): string {
  const first = (text || '').trim().split('\n')[0] || ''
  return first.replace(/^Caught error:\s*/, '').trim()
}

/** "45 s", "12 min", "1 h 5 min", "2 d 3 h". */
export function formatDuration (ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} min`
  const h = Math.floor(m / 60)
  if (h < 24) return m % 60 ? `${h} h ${m % 60} min` : `${h} h`
  const d = Math.floor(h / 24)
  return h % 24 ? `${d} d ${h % 24} h` : `${d} d`
}

/** "today at 14:05", "yesterday at 09:12", "3 Oct at 16:40". */
export function formatWhen (at: number, now = Date.now()): string {
  if (!at) return 'at an unknown time'
  const d = new Date(at)
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  const today = new Date(now)
  const yesterday = new Date(now - 86400000)
  if (d.toDateString() === today.toDateString()) return `today at ${time}`
  if (d.toDateString() === yesterday.toDateString()) return `yesterday at ${time}`
  const sameYear = d.getFullYear() === today.getFullYear()
  return `${d.toLocaleDateString([], { day: 'numeric', month: 'short', year: sameYear ? undefined : 'numeric' })} at ${time}`
}

/** A file name the browser and every OS accept. */
export function safeFileName (s: string): string {
  return (s || 'session').replace(/[\\/:*?"<>|\u0000-\u001f]+/g, '-').replace(/\s+/g, ' ').trim().slice(0, 120) || 'session'
}
