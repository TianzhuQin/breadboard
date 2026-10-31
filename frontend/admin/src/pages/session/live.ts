import type { GraphNode } from '@/lib/graph'
import type { EngineInfo, OutputEntry } from '@/store/types'
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
  /** Seconds until the waiting room starts the game unless more people come (enough are here). */
  startsIn?: number | null
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
      // once enough are here: seconds until the start unless more come (newer servers send it)
      startsIn: state === 'waiting' ? num(ap.startsIn) : null,
    }
    if (!best || rank[state] > rank[best.state]) {
      best = cur
    } else if (rank[state] === rank[best.state]) {
      best.here = Math.max(best.here, cur.here)
      best.min = best.min ?? cur.min
      best.max = best.max ?? cur.max
      // the player told most recently has the fewest seconds left
      if (cur.seconds != null && (best.seconds == null || cur.seconds < best.seconds)) best.seconds = cur.seconds
      if (cur.startsIn != null && (best.startsIn == null || cur.startsIn < best.startsIn)) best.startsIn = cur.startsIn
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
  /**
   * How we know: the session's flag says so (server), the engine's own flag (engine), the engine
   * answered a start (output), or the events show it.
   */
  source: 'server' | 'engine' | 'output' | 'events' | null
  at: number | null
  /**
   * Whether the engine itself said so (the server's flag, or its answer to a start since its last
   * reload). The session's log alone never makes it certain: a start that threw still logged its
   * start event (Step.start() records it before the step runs), and a reload forgets a start the
   * log keeps. So the page offers to start it anyway unless this is true.
   */
  sure: boolean
  /**
   * The start event may be from before the engine's last reload: its time is unknown, or within
   * the few seconds the server's and this computer's clocks may disagree by.
   */
  nearReload: boolean
  /**
   * Not started, though the session's flag says the game started: the engine says it isn't running,
   * because it was reloaded since (a resume, a reload), which forgets a start.
   */
  forgotten: boolean
}

/** What the engine itself says about the game (EngineInfo.gameStarted), and when this console heard it. */
export interface EngineGameFlag {
  started: boolean
  /** When the User JSON that said it arrived (this computer's clock). */
  at: number
}

/**
 * The engine's own word on whether the selected session's game runs: only when the server says it
 * (newer servers), the info is current (not from before a rebuild under way) and the engine runs
 * this very session. Null otherwise: then the signs in detectGameStarted decide.
 */
export function engineGameFlag (engine: EngineInfo | null | undefined, current: boolean, sessionId: number | null | undefined, at: number): EngineGameFlag | null {
  if (!current || !engine || typeof engine.gameStarted !== 'boolean') return null
  if (sessionId == null || engine.sessionId !== sessionId) return null
  return { started: engine.gameStarted, at }
}

/** How far the server's clock may be off from this computer's when comparing event times. */
const CLOCK_SLACK_MS = 5000

/**
 * The engine's answer to this console's RunGame when initStep.start() threw (or there was no
 * engine): the start was undone, so the game can be started again. Not "started already", and not
 * a refusal ("Nothing was done: …"), which changed nothing.
 */
function isFailedStart (o: OutputEntry): boolean {
  return o.kind === 'error' && o.source === 'RunGame' && /^Caught error:/.test(o.text) && !o.text.includes(ALREADY)
}

const isStartEvent = (e: FeedEvent) => START_EVENTS.has(e.name.toLowerCase())

/**
 * Whether initStep.start() has run since the engine last loaded.
 *
 * Newer servers say so themselves (`engine`, see engineGameFlag). That flag is as old as the User
 * JSON it came in, so only what this console heard after it overrules it: the engine's answer to a
 * start made here, or a start event pushed live (another console's start, the autopilot's). The
 * session's `hasStarted` does not: it outlives a reload, which forgets the start.
 *
 * Without it (older servers) this reads the signs: the engine's answer to a start since the last
 * reload (output "initStep.start() ==> …", the error "initStep started already", or a start that
 * threw), an explicit `hasStarted` on the session, or a start event in the session's log
 * (InitStepStart, or the autopilot's autopilotStart when it started the game itself).
 */
export function detectGameStarted (opts: { hasStarted?: unknown, events: FeedEvent[], output: OutputEntry[], since: number, engine?: EngineGameFlag | null }): StartedInfo {
  const flag = opts.engine || null
  // the engine's newest answer to a start since it last reloaded decides (and since its flag was read)
  const since = flag ? Math.max(opts.since, flag.at) : opts.since
  let failedAt = 0
  for (let i = opts.output.length - 1; i >= 0; i--) {
    const o = opts.output[i]
    if (o.at < since) break
    if (o.kind === 'output' && o.text.startsWith(START_OUTPUT)) return { started: true, source: 'output', at: o.at, sure: true, nearReload: false, forgotten: false }
    if (o.kind === 'error' && o.text.includes(ALREADY)) return { started: true, source: 'output', at: o.at, sure: true, nearReload: false, forgotten: false }
    if (isFailedStart(o)) { failedAt = o.at; break }
  }
  if (flag && !failedAt) {
    if (flag.started) return { started: true, source: 'engine', at: null, sure: true, nearReload: false, forgotten: false }
    // a start heard of since the flag was read; only the log shows it, so not for certain
    let later: FeedEvent | null = null
    for (const e of opts.events) {
      if (isStartEvent(e) && e.receivedAt !== undefined && e.receivedAt >= flag.at && (!later || e.at > later.at)) later = e
    }
    if (later) return { started: true, source: 'events', at: later.at || null, sure: false, nearReload: false, forgotten: false }
    // (the server sets the session's flag back when a start throws, so it means a start that ran)
    return { started: false, source: 'engine', at: null, sure: true, nearReload: false, forgotten: opts.hasStarted === true }
  }
  // (a start that threw leaves the session's flag set on older servers, so the answer wins over it)
  if (opts.hasStarted === true && !failedAt) return { started: true, source: 'server', at: null, sure: true, nearReload: false, forgotten: false }
  // the engine forgets a start when it reloads; events from before that don't count, and one that
  // can't be placed against the reload leaves it open
  const reloaded = opts.since
  let unsure: FeedEvent | null = null
  for (const e of opts.events) {
    if (!isStartEvent(e)) continue
    // the failed start logged its own start event: only a clearly later one is another start
    if (failedAt) {
      if (e.at && e.at > failedAt + CLOCK_SLACK_MS) return { started: true, source: 'events', at: e.at, sure: false, nearReload: false, forgotten: false }
      continue
    }
    if (!reloaded || (e.at && e.at >= reloaded)) return { started: true, source: 'events', at: e.at || null, sure: false, nearReload: false, forgotten: false }
    if (e.at && e.at < reloaded - CLOCK_SLACK_MS) continue
    unsure = unsure || e
  }
  if (unsure) return { started: true, source: 'events', at: unsure.at || null, sure: false, nearReload: true, forgotten: false }
  // (with the engine's flag, only a start that threw here gets this far: the server put the
  // session's flag back as it was, so a set one is still a start the engine forgot)
  return { started: false, source: null, at: null, sure: true, nearReload: false, forgotten: !!flag && opts.hasStarted === true }
}

export type StartOutcome = { kind: 'started' } | { kind: 'already' } | { kind: 'error', message: string } | { kind: 'timeout' }

/**
 * Is this server message the engine's answer to RunGame? Newer servers tag every frame of that
 * answer {"request": "RunGame"} (success, "started already" and failures alike), and tag other
 * requests' answers and OnJoinStep/OnLeaveStep's frames with their own names, so a tag decides.
 * An untagged frame (an older server) counts only when it is the start's output
 * ("initStep.start() ==> …") or the "started already" error. Other untagged engine errors are
 * common while people join (a late joiner's OnJoinStep, a timer) and are not taken for the answer:
 * there, a failed start ends in "no answer yet", which is better than a false failure.
 */
export function isStartReply (m: any): boolean {
  if (!m || typeof m !== 'object' || 'action' in m) return false
  if (typeof m.request === 'string') return m.request === 'RunGame'
  if (typeof m.source === 'string') return false
  if (typeof m.output === 'string' && m.output.startsWith(START_OUTPUT)) return true
  return typeof m.error === 'string' && m.error.includes(ALREADY)
}

export function classifyStartReply (m: any): StartOutcome {
  if (!m) return { kind: 'timeout' }
  const err = typeof m.error === 'string' ? m.error : ''
  if (err.includes(ALREADY)) return { kind: 'already' }
  // the tagged answer: no error means it started, whatever the output says
  if (m.request === 'RunGame' && !err.trim()) return { kind: 'started' }
  if (m.request !== 'RunGame' && typeof m.output === 'string' && m.output.startsWith(START_OUTPUT)) return { kind: 'started' }
  return { kind: 'error', message: cleanError(err) || 'The engine reported an error.' }
}

/**
 * Why the game can't be started (or changed) here while the shared engine runs another experiment
 * (admin.engineElsewhere); the server refuses it then. The top bar's notice says whose it is.
 */
export function engineElsewhereText (e: { experimentName: string | null }): string {
  const what = e.experimentName ? `“${e.experimentName}”` : 'another experiment'
  return `The engine is running ${what} right now, not this session, so its game can't be started or changed from here.`
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
  // (as Sessions & data names its downloads: letters, digits, spaces and . _ ( ) - only, any space a
  // plain one first, as the autopilot's names have no-break spaces)
  const cleaned = (s || '').replace(/\s+/gu, ' ').replace(/[,;]/g, '').replace(/[^\p{L}\p{N} ._()-]+/gu, '-').replace(/\s+/g, ' ').trim()
  return (cleaned || 'session').slice(0, 120)
}
