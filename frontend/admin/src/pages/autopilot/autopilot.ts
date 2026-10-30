import { http } from '@/lib/http'

/** The autopilot settings and state the server keeps (GET/POST /autopilot). */

export interface WaitingRoomSettings {
  enabled: boolean
  minPlayers: number
  maxPlayers: number | null
  waitSeconds: number
  maxWaitSeconds: number | null
  countdownSeconds: number
}
export interface ReplaceDisconnectedSettings {
  enabled: boolean
  afterSeconds: number
  returnToHuman: boolean
}
export interface AutopilotSettings {
  /** run this experiment unattended */
  enabled: boolean
  experimentId: number | null
  /** open the next session when one finishes */
  continuous: boolean
  nextSessionDelaySeconds: number
  /** how long the game of the autopilot's own session may run before it is ended; null: no limit */
  maxSessionMinutes: number | null
  waitingRoom: WaitingRoomSettings
  replaceDisconnected: ReplaceDisconnectedSettings
}
/** A live session of another experiment that holds the shared script engine. */
export interface EngineSession {
  id: number
  name: string
  experimentId: number | null
  experimentName: string | null
  /** the email of the account that runs it, when the server knows */
  owner: string | null
}
export interface AutopilotStatus {
  on: boolean
  experimentId: number | null
  experimentName: string | null
  instance: { id: number, name: string, status: string } | null
  links: { login: string, prolific: string } | null
  /**
   * Why the autopilot, switched on, opens no session (the server's words); null when all is well.
   * It can be by design (paused, done, waiting for another session: see problemKind) or a fault.
   */
  problem: string | null
  /** a researcher stopped its session, so it opens none until Resume (see resumeAutopilot) */
  paused: boolean
  /**
   * its one session finished with "continuous" off, so it opens none until Resume (see
   * resumeAutopilot). Only newer servers send it; haltOf also reads the server's message.
   */
  done: boolean
  /** what status.problem is about, when the server says (newer servers); see problemKind */
  problemKind: ProblemKind | null
  /** when the next session opens (ISO time), while the pause after a finished session runs */
  nextOpenAt: string | null
  /** another experiment's live session holds the engine: the autopilot waits for it to end */
  engineSession: EngineSession | null
  /**
   * The last session it ended for running longer than the longest session allowed, in the server's
   * words, and when (ISO time); kept until the settings are saved or Resume is pressed. Only newer
   * servers send it.
   */
  notice: string | null
  noticeAt: string | null
}
export interface AutopilotState {
  settings: AutopilotSettings
  /** 'config' = application.conf (breadboard.autopilot.*) is in charge, so the page is read-only */
  source: 'admin' | 'config' | 'off'
  status: AutopilotStatus
}

/** What the page edits: like the settings, but a number field can be empty while typing. */
export interface AutopilotForm {
  enabled: boolean
  experimentId: number | null
  continuous: boolean
  nextSessionDelaySeconds: number | null
  maxSessionMinutes: number | null
  waitingRoom: {
    enabled: boolean
    minPlayers: number | null
    maxPlayers: number | null
    waitSeconds: number | null
    maxWaitSeconds: number | null
    countdownSeconds: number | null
  }
  replaceDisconnected: { enabled: boolean, afterSeconds: number | null, returnToHuman: boolean }
}

/** The script's own defaults (groovy/autopilot.groovy), used when the server leaves a value out. */
export const DEFAULTS: AutopilotSettings = {
  enabled: false,
  experimentId: null,
  continuous: true,
  nextSessionDelaySeconds: 10,
  maxSessionMinutes: 120,
  waitingRoom: { enabled: true, minPlayers: 2, maxPlayers: null, waitSeconds: 60, maxWaitSeconds: null, countdownSeconds: 5 },
  replaceDisconnected: { enabled: true, afterSeconds: 30, returnToHuman: true },
}

/** Longest wait accepted: a day (the engine's timers count milliseconds in an int). */
export const MAX_SECONDS = 86400
export const MAX_PLAYERS = 10000
/** The longest session that can be set: a day, in minutes. */
export const MAX_SESSION_MINUTES = 1440

const num = (v: any, d: number) => (typeof v === 'number' && isFinite(v) ? v : (typeof v === 'string' && v.trim() !== '' && isFinite(+v) ? +v : d))
const optNum = (v: any) => (v == null || v === '' ? null : num(v, NaN)) as number | null
const bool = (v: any, d: boolean) => (typeof v === 'boolean' ? v : d)

/** Settings from the server, with anything missing or odd replaced by the default. */
export function normalizeSettings (raw: any): AutopilotSettings {
  const r = raw && typeof raw === 'object' ? raw : {}
  const w = r.waitingRoom && typeof r.waitingRoom === 'object' ? r.waitingRoom : {}
  const d = r.replaceDisconnected && typeof r.replaceDisconnected === 'object' ? r.replaceDisconnected : {}
  const maxPlayers = optNum(w.maxPlayers)
  const maxWait = optNum(w.maxWaitSeconds)
  // (null is "no limit"; left out, by an older server, the default)
  const maxSession = 'maxSessionMinutes' in r ? optNum(r.maxSessionMinutes) : DEFAULTS.maxSessionMinutes
  return {
    enabled: bool(r.enabled, DEFAULTS.enabled),
    experimentId: r.experimentId == null ? null : num(r.experimentId, NaN) || null,
    continuous: bool(r.continuous, DEFAULTS.continuous),
    nextSessionDelaySeconds: num(r.nextSessionDelaySeconds, DEFAULTS.nextSessionDelaySeconds),
    maxSessionMinutes: maxSession != null && isNaN(maxSession) ? DEFAULTS.maxSessionMinutes : maxSession,
    waitingRoom: {
      enabled: bool(w.enabled, DEFAULTS.waitingRoom.enabled),
      minPlayers: num(w.minPlayers, DEFAULTS.waitingRoom.minPlayers),
      maxPlayers: maxPlayers != null && isNaN(maxPlayers) ? null : maxPlayers,
      waitSeconds: num(w.waitSeconds, DEFAULTS.waitingRoom.waitSeconds),
      maxWaitSeconds: maxWait != null && isNaN(maxWait) ? null : maxWait,
      countdownSeconds: num(w.countdownSeconds, DEFAULTS.waitingRoom.countdownSeconds),
    },
    replaceDisconnected: {
      enabled: bool(d.enabled, DEFAULTS.replaceDisconnected.enabled),
      afterSeconds: num(d.afterSeconds, DEFAULTS.replaceDisconnected.afterSeconds),
      returnToHuman: bool(d.returnToHuman, DEFAULTS.replaceDisconnected.returnToHuman),
    },
  }
}

const text = (v: any) => (typeof v === 'string' && v.trim() ? v.trim() : null)
const idOrNull = (v: any) => (v == null || v === '' || isNaN(Number(v)) ? null : Number(v))

function normalizeEngineSession (raw: any): EngineSession | null {
  if (!raw || typeof raw !== 'object' || idOrNull(raw.id) == null) return null
  return {
    id: Number(raw.id),
    name: String(raw.name ?? ''),
    experimentId: idOrNull(raw.experimentId),
    experimentName: text(raw.experimentName),
    owner: text(raw.owner),
  }
}

export function normalizeState (raw: any): AutopilotState {
  const r = raw && typeof raw === 'object' ? raw : {}
  const s = r.status && typeof r.status === 'object' ? r.status : {}
  const inst = s.instance && typeof s.instance === 'object' && s.instance.id != null ? s.instance : null
  const links = s.links && typeof s.links === 'object' && (s.links.login || s.links.prolific) ? s.links : null
  const next = text(s.nextOpenAt)
  const noticeAt = text(s.noticeAt)
  return {
    settings: normalizeSettings(r.settings),
    source: r.source === 'config' || r.source === 'admin' ? r.source : 'off',
    status: {
      on: !!s.on,
      experimentId: s.experimentId == null ? null : Number(s.experimentId),
      experimentName: typeof s.experimentName === 'string' ? s.experimentName : null,
      instance: inst ? { id: Number(inst.id), name: String(inst.name ?? ''), status: String(inst.status ?? '') } : null,
      links: links ? { login: String(links.login || ''), prolific: String(links.prolific || '') } : null,
      problem: text(s.problem),
      paused: s.paused === true,
      done: s.done === true,
      problemKind: PROBLEM_KINDS.has(s.problemKind) ? s.problemKind : null,
      nextOpenAt: next && !isNaN(Date.parse(next)) ? next : null,
      engineSession: normalizeEngineSession(s.engineSession),
      notice: text(s.notice),
      noticeAt: noticeAt && !isNaN(Date.parse(noticeAt)) ? noticeAt : null,
    },
  }
}

const LIVE = new Set(['RUNNING', 'TESTING'])

/**
 * The live session that saving these settings would have to end to open the autopilot's own, as
 * the server reports it; null when there is none (the engine is free, or it already runs a session
 * of this experiment). That is another experiment's session (status.engineSession), or the
 * autopilot's own open session when the save moves it to another experiment. A plain save leaves
 * that session alone and waits for it to end; only a save with takeOver ends it.
 */
export function takeOverTarget (next: Pick<AutopilotSettings, 'enabled' | 'experimentId'>, status: AutopilotStatus): EngineSession | null {
  if (!next.enabled || next.experimentId == null) return null
  const other = status.engineSession
  if (other) return other.experimentId === next.experimentId ? null : other
  const own = status.instance
  if (own && LIVE.has(own.status) && status.experimentId != null && status.experimentId !== next.experimentId) {
    return { id: own.id, name: own.name, experimentId: status.experimentId, experimentName: status.experimentName, owner: null }
  }
  return null
}

/** “pilot 3” of “Trust game”, run by a@lab.org (the parts the server sent). */
export function describeEngineSession (s: EngineSession): string {
  const name = `“${s.name || `Session #${s.id}`}”`
  const of = s.experimentName ? ` of “${s.experimentName}”` : ''
  return `${name}${of}${s.owner ? `, run by ${s.owner}` : ''}`
}

/**
 * What status.problem is about. 'paused', 'done' and 'waiting' are the autopilot waiting by design
 * (for Resume, or for the engine to be free): information, not a fault. 'error' needs fixing.
 */
export type ProblemKind = 'paused' | 'done' | 'waiting' | 'error'
const PROBLEM_KINDS = new Set<any>(['paused', 'done', 'waiting', 'error'])
export function problemKind (state: Pick<AutopilotState, 'settings' | 'status'>): ProblemKind | null {
  const s = state.status
  if (!s.problem) return null
  if (s.problemKind) return s.problemKind
  // not running at all (no experiment chosen, or it was deleted): whatever else the status says,
  // that is the problem to fix
  if (!s.on) return 'error'
  if (s.paused) return 'paused'
  // its one session finished and "continuous" is off (the server's "has finished" message)
  if (s.done || (!state.settings.continuous && /has finished/i.test(s.problem))) return 'done'
  // The server names a live session (engineSession) whenever one holds the engine, also when the
  // real problem comes first (no admin account to open sessions as, say): only its own "Another
  // session is running" message means the autopilot is just waiting.
  if (s.engineSession && /^Another session is running/i.test(s.problem)) return 'waiting'
  return 'error'
}

/**
 * Why the autopilot opens nothing until someone presses Resume: 'paused' (a researcher stopped its
 * session) or 'done' (its one session finished, "continuous" off); null otherwise. The server's own
 * flags decide; an older server that sends no "done" is read from its message (problemKind).
 */
export function haltOf (state: Pick<AutopilotState, 'settings' | 'status'> | null): 'paused' | 'done' | null {
  const s = state?.status
  if (!s || !s.on) return null
  if (s.paused) return 'paused'
  if (s.done) return 'done'
  return problemKind(state!) === 'done' ? 'done' : null
}
/**
 * The step failure a pause names (status.problem: the steps of its session didn't load, or a step
 * failed during its game): which step, on which line, and what went wrong in the server's brief
 * words ("No such property: five"); `text` is the problem without them. Null for any other pause.
 */
export interface PauseFailure {
  step: string
  line: number | null
  message: string
  text: string
}
export function pauseFailure (problem: string | null | undefined): PauseFailure | null {
  if (!problem) return null
  // "the steps of its session "S" didn't load (InitStep: line 24: No such property: five), so that session was stopped …"
  const load = /didn't load (\(([^:()]+): (?:line (\d+): )?([\s\S]*?)\))(?=, so that session was stopped)/.exec(problem)
  // "the step "RoundStep" failed during its session "S" (line 71: No such property: x), so that session was stopped …"
  // (or ", and then the game had nothing left to do, …")
  const game = load ? null : /the step "([^"]+)" failed during its session "[\s\S]*?" (\((?:line (\d+): )?([\s\S]*?)\))(?=, (?:so that session was stopped|and then the game))/.exec(problem)
  if (load) return failureOf(load[2], load[3], load[4], problem, load[1])
  if (game) return failureOf(game[1], game[3], game[4], problem, game[2])
  return null
}
function failureOf (step: string, line: string | undefined, message: string, problem: string, bracket: string): PauseFailure {
  return {
    step: step.trim(),
    line: line ? Number(line) : null,
    message: message.trim(),
    text: problem.replace(` ${bracket}`, '').replace(bracket, ''),
  }
}

/**
 * The paused status card's line under its title, by the reason the server gives (status.problem):
 * a mistake in a step, sessions that ran out of time, or a researcher ending its session. Neutral
 * when the reason is none of those (the reason itself is said below it).
 */
export function pausedLine (problem: string | null | undefined): string {
  const p = problem || ''
  const until = 'so it opens no new session until you press Resume.'
  if (/didn't load \(/.test(p)) return `The steps of its last session didn’t load, ${until}`
  if (/the step "[^"]+" failed during its session/.test(p)) return `A step failed in its last session, ${until}`
  if (/sessions in a row ran longer than/.test(p)) return `Its last sessions ran out of time, ${until}`
  if (/its session "[\s\S]*?" was stopped/.test(p)) return `Its session was stopped, ${until}`
  if (/its session "[\s\S]*?" ended when/.test(p)) return `Its session was ended from the console, ${until}`
  return 'It opens no new session until you press Resume (see why below).'
}

/** The button that resumes it: "Resume" after a pause, "Open another session" once it is done. */
export function resumeLabel (halt: 'paused' | 'done'): string {
  return halt === 'paused' ? 'Resume' : 'Open another session'
}

/**
 * Whether stopping this session pauses the autopilot (the server's Autopilot.sessionStopped): it is
 * the session the engine runs, of the autopilot's experiment. `status.instance` is the engine's
 * session of that experiment; a test session is left out of it, so `engineSessionId` (what the
 * console's engine info says the engine runs) covers that case. Already paused: nothing changes.
 */
export function stopPausesAutopilot (
  ap: Pick<AutopilotState, 'settings' | 'status'> | null,
  session: { id: number, experimentId: number | null | undefined },
  engineSessionId?: number | null,
): boolean {
  if (!ap || !ap.status.on || ap.status.paused) return false
  if (ap.status.instance?.id === session.id) return true
  return engineSessionId === session.id && session.experimentId != null && ap.settings.experimentId === session.experimentId
}
/** Said before stopping a session that would pause the autopilot (see stopPausesAutopilot). */
export const STOP_PAUSES_AUTOPILOT = 'The autopilot runs this experiment, so stopping its session pauses it: it opens no new session, and its participant links (including Prolific’s) lead nowhere, until you press Resume on the Autopilot page.'

/** A deep copy to edit (from saved settings, or of another form). */
export function toForm (s: AutopilotSettings | AutopilotForm): AutopilotForm {
  return JSON.parse(JSON.stringify(s))
}

export type AutopilotErrors = Record<string, string>

function whole (v: number | null, field: string, errors: AutopilotErrors, opts: { min: number, max: number, required: boolean, what: string }) {
  if (v == null || (typeof v === 'number' && isNaN(v))) {
    if (opts.required) errors[field] = `Enter ${opts.what}.`
    return
  }
  if (!Number.isInteger(v)) errors[field] = 'Use a whole number.'
  else if (v < opts.min) errors[field] = opts.min === 0 ? 'Can\'t be negative.' : `At least ${opts.min}.`
  else if (v > opts.max) {
    errors[field] = opts.max === MAX_SECONDS ? 'At most a day (86400 seconds).'
      : opts.max === MAX_SESSION_MINUTES ? 'At most a day (1440 minutes).' : `At most ${opts.max}.`
  }
}

/**
 * Checks the form. Keys are field paths ("waitingRoom.minPlayers"); a section that is switched off
 * is not checked (its values are replaced by defaults when saving, see toSettings).
 */
export function validateAutopilot (f: AutopilotForm): AutopilotErrors {
  const e: AutopilotErrors = {}
  if (f.enabled && f.experimentId == null) e.experimentId = 'Choose the experiment to run.'
  if (f.continuous) {
    whole(f.nextSessionDelaySeconds, 'nextSessionDelaySeconds', e, { min: 0, max: MAX_SECONDS, required: true, what: 'a number of seconds (0 for no pause)' })
  }
  // (empty: no limit)
  whole(f.maxSessionMinutes, 'maxSessionMinutes', e, { min: 1, max: MAX_SESSION_MINUTES, required: false, what: '' })
  const w = f.waitingRoom
  if (w.enabled) {
    whole(w.minPlayers, 'waitingRoom.minPlayers', e, { min: 1, max: MAX_PLAYERS, required: true, what: 'how many people are needed' })
    whole(w.maxPlayers, 'waitingRoom.maxPlayers', e, { min: 1, max: MAX_PLAYERS, required: false, what: '' })
    if (!e['waitingRoom.maxPlayers'] && !e['waitingRoom.minPlayers'] && w.maxPlayers != null && w.minPlayers != null && w.maxPlayers < w.minPlayers) {
      e['waitingRoom.maxPlayers'] = `Can't be less than the minimum (${w.minPlayers}).`
    }
    whole(w.waitSeconds, 'waitingRoom.waitSeconds', e, { min: 0, max: MAX_SECONDS, required: true, what: 'a number of seconds (0 starts without waiting)' })
    whole(w.maxWaitSeconds, 'waitingRoom.maxWaitSeconds', e, { min: 1, max: MAX_SECONDS, required: false, what: '' })
    whole(w.countdownSeconds, 'waitingRoom.countdownSeconds', e, { min: 0, max: 3600, required: true, what: 'a number of seconds (0 for no countdown)' })
  }
  const r = f.replaceDisconnected
  if (r.enabled) {
    whole(r.afterSeconds, 'replaceDisconnected.afterSeconds', e, { min: 1, max: MAX_SECONDS, required: true, what: 'a number of seconds' })
  }
  return e
}

const ok = (v: number | null | undefined, min: number, max: number) => v != null && Number.isInteger(v) && v >= min && v <= max

/**
 * What is sent to the server. Values of switched-off sections that wouldn't pass the checks fall
 * back to the defaults, so a half-typed field in a hidden section never blocks saving.
 */
export function toSettings (f: AutopilotForm): AutopilotSettings {
  const w = f.waitingRoom
  const r = f.replaceDisconnected
  const D = DEFAULTS
  const minPlayers = ok(w.minPlayers, 1, MAX_PLAYERS) ? w.minPlayers! : D.waitingRoom.minPlayers
  let maxPlayers = ok(w.maxPlayers, 1, MAX_PLAYERS) ? w.maxPlayers : null
  if (maxPlayers != null && maxPlayers < minPlayers) maxPlayers = null
  return {
    enabled: f.enabled,
    experimentId: f.experimentId,
    continuous: f.continuous,
    nextSessionDelaySeconds: ok(f.nextSessionDelaySeconds, 0, MAX_SECONDS) ? f.nextSessionDelaySeconds! : D.nextSessionDelaySeconds,
    // empty is no limit (a value out of range never gets here: the checks stop the save)
    maxSessionMinutes: f.maxSessionMinutes == null || (typeof f.maxSessionMinutes === 'number' && isNaN(f.maxSessionMinutes)) ? null
      : ok(f.maxSessionMinutes, 1, MAX_SESSION_MINUTES) ? f.maxSessionMinutes : D.maxSessionMinutes,
    waitingRoom: {
      enabled: w.enabled,
      minPlayers,
      maxPlayers,
      waitSeconds: ok(w.waitSeconds, 0, MAX_SECONDS) ? w.waitSeconds! : D.waitingRoom.waitSeconds,
      maxWaitSeconds: ok(w.maxWaitSeconds, 1, MAX_SECONDS) ? w.maxWaitSeconds : null,
      countdownSeconds: ok(w.countdownSeconds, 0, 3600) ? w.countdownSeconds! : D.waitingRoom.countdownSeconds,
    },
    replaceDisconnected: {
      enabled: r.enabled,
      afterSeconds: ok(r.afterSeconds, 1, MAX_SECONDS) ? r.afterSeconds! : D.replaceDisconnected.afterSeconds,
      returnToHuman: r.returnToHuman,
    },
  }
}

/** Same settings? (Used for the unsaved-changes marker.) */
export function sameForm (a: AutopilotForm, b: AutopilotForm): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

// ---- words ------------------------------------------------------------------------------------

/** "45 seconds", "1 minute", "2 min 30 s", "1 hour". */
export function humanSeconds (s: number | null | undefined): string {
  if (s == null || !isFinite(s)) return ''
  const n = Math.max(0, Math.round(s))
  if (n < 60) return `${n} ${n === 1 ? 'second' : 'seconds'}`
  const h = Math.floor(n / 3600)
  const m = Math.floor((n % 3600) / 60)
  const sec = n % 60
  if (h) return m ? `${h} h ${m} min` : `${h} ${h === 1 ? 'hour' : 'hours'}`
  if (sec) return `${m} min ${sec} s`
  return `${m} ${m === 1 ? 'minute' : 'minutes'}`
}

/** The waiting room in one or two plain sentences, for the preview under the form. */
export function describeWaitingRoom (f: AutopilotForm, ownRoom = false): string {
  const w = f.waitingRoom
  if (!w.enabled) {
    return ownRoom
      ? 'The waiting room set in the experiment\'s steps is used.'
      : 'There is no waiting room: the game starts when you press Start, or when the experiment\'s own script starts it.'
  }
  const min = w.minPlayers ?? DEFAULTS.waitingRoom.minPlayers
  const people = (n: number) => `${n} ${n === 1 ? 'person' : 'people'}`
  const parts: string[] = []
  const are = min === 1 ? 'is' : 'are'
  const start = w.countdownSeconds ? `counts down ${humanSeconds(w.countdownSeconds)} and starts the game` : 'starts the game'
  if (w.maxPlayers != null && w.maxPlayers <= min) {
    // the room is full as soon as the minimum is there, so nobody waits for more
    parts.push(`As soon as ${people(min)} ${are} here, Breadboard ${start}.`)
  } else if (!w.waitSeconds) {
    parts.push(`As soon as ${people(min)} ${are} here, Breadboard ${start}.`)
  } else {
    const upTo = w.maxPlayers != null ? ` (or until ${w.maxPlayers} are here)` : ''
    parts.push(`Once ${people(min)} ${are} here, Breadboard waits ${humanSeconds(w.waitSeconds)} for more to join${upTo}, then ${start}.`)
  }
  if (w.maxWaitSeconds) {
    parts.push(`If fewer than ${people(min)} have come ${humanSeconds(w.maxWaitSeconds)} after the first person arrived, the session is called off and everyone waiting is told${f.continuous ? '; the next session then opens' : ''}.`)
  } else {
    parts.push(`If fewer than ${people(min)} come, those who did keep waiting until you step in; set “Give up after” to call the session off by itself.`)
  }
  return parts.join(' ')
}

// ---- links ------------------------------------------------------------------------------------

const PROLIFIC_QUERY = 'PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}'

/** The stable participant links as full URLs: from the server, or built from the experiment id. */
export function participantLinks (status: AutopilotStatus | null, experimentId: number | null, origin: string): { login: string, prolific: string } | null {
  const abs = (u: string) => (/^https?:\/\//i.test(u) ? u : `${origin.replace(/\/$/, '')}${u.startsWith('/') ? '' : '/'}${u}`)
  if (status?.links && (status.links.login || status.links.prolific)) {
    const id = status.experimentId ?? experimentId
    return {
      login: status.links.login ? abs(status.links.login) : (id != null ? abs(`/play/${id}/login`) : ''),
      prolific: status.links.prolific ? abs(status.links.prolific) : (id != null ? abs(`/play/${id}/prolific?${PROLIFIC_QUERY}`) : ''),
    }
  }
  const id = status?.experimentId ?? experimentId
  if (id == null) return null
  return { login: abs(`/play/${id}/login`), prolific: abs(`/play/${id}/prolific?${PROLIFIC_QUERY}`) }
}

// ---- server -----------------------------------------------------------------------------------

export async function fetchAutopilot (): Promise<AutopilotState> {
  return normalizeState(await http.get('/autopilot'))
}

/** GET /autopilot, or null when it fails or takes too long (an older server, a busy one, no connection). */
export async function autopilotNow (ms = 4000): Promise<AutopilotState | null> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<null>(resolve => { timer = setTimeout(() => resolve(null), ms) })
  try {
    return await Promise.race([fetchAutopilot().catch(() => null), late])
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Save the settings. With `takeOver` (sent only after the researcher agreed), opening the session
 * ends a live session of another experiment; without it the autopilot waits for that one to end.
 */
export async function saveAutopilot (s: AutopilotSettings, opts: { takeOver?: boolean } = {}): Promise<AutopilotState> {
  return normalizeState(await http.post('/autopilot', opts.takeOver ? { ...s, takeOver: true } : s))
}

/**
 * Let a paused or done autopilot open sessions again (POST /autopilot/resume), changing no
 * settings, so it works whether the console or application.conf is in charge. A live session of
 * another experiment is left to end. The server refuses (400) when the autopilot is off.
 */
export async function resumeAutopilot (): Promise<AutopilotState> {
  return normalizeState(await http.post('/autopilot/resume'))
}

// ---- live monitoring (the open session's waiting room and events) -----------------------------

export interface WaitingRoomLive {
  state: 'waiting' | 'starting' | 'cancelled'
  here: number
  min: number | null
  max: number | null
  seconds: number | null
}

/** What the participants' pages say right now (`_system.autopilot` on their nodes), if anything. */
export function waitingRoomLive (nodes: Iterable<{ id: string, data: Record<string, any> }>): WaitingRoomLive | null {
  let best: WaitingRoomLive | null = null
  for (const n of nodes) {
    if (String(n.id).startsWith('_')) continue
    const a = n.data?._system?.autopilot
    if (!a || typeof a !== 'object' || !a.state) continue
    const live: WaitingRoomLive = {
      state: a.state,
      here: typeof a.here === 'number' ? a.here : 0,
      min: typeof a.min === 'number' ? a.min : null,
      max: typeof a.max === 'number' ? a.max : null,
      seconds: typeof a.seconds === 'number' ? a.seconds : null,
    }
    // a countdown wins over waiting; otherwise the biggest head count is the newest
    if (!best || (live.state === 'starting' && best.state !== 'starting') || live.here > best.here) best = live
  }
  return best
}

const AUTOPILOT_EVENTS = new Set([
  'autopilotStart', 'autopilotCancelled', 'aiTakeover', 'aiReturned', 'sessionTooLong', 'sessionStalled',
  // failures, stops and restarts: what keeps a session from running as it should
  'stepFailed', 'sessionFailed', 'stepsFailedToLoad', 'sessionStopped', 'serverStopping', 'engineReloaded',
])

export type ActivityTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral'

/** The line a failure was on: the event's own `line`, or the "line 72: …" its error starts with. */
function lineOf (d: Record<string, any>): number | null {
  const n = Number(d.line)
  if (Number.isInteger(n) && n > 0) return n
  const m = /^line (\d+):/.exec(String(d.error ?? ''))
  return m ? Number(m[1]) : null
}
/** "RoundStep failed at line 72" */
function failedAt (step: unknown, line: number | null): string {
  return `${step ? String(step) : 'A step'} failed${line ? ` at line ${line}` : ''}`
}

/** The autopilot's recent events in words, newest first: its games, takeovers, failures and stops. */
export function autopilotActivity (events: { name: string, datetime: string, data: Record<string, any> }[], limit = 6) {
  const out: { key: string, at: string, text: string, tone: ActivityTone }[] = []
  // the step whose failure the session was then ended for (sessionFailed, newer): that failure didn't let the game go on
  let endedFor: string | null = null
  for (let i = events.length - 1; i >= 0 && out.length < limit; i--) {
    const e = events[i]
    if (!AUTOPILOT_EVENTS.has(e.name)) continue
    const d = e.data || {}
    let text = ''
    let tone: ActivityTone = 'neutral'
    if (e.name === 'autopilotStart') { text = `Game started with ${d.players ?? '?'} ${String(d.players) === '1' ? 'person' : 'people'}`; tone = 'success' }
    if (e.name === 'autopilotCancelled') { text = `Called off: only ${d.players ?? '?'} joined`; tone = 'warning' }
    if (e.name === 'aiTakeover') { text = `The computer took over ${d.playerId ?? 'a player'}'s seat`; tone = 'info' }
    if (e.name === 'aiReturned') { text = `${d.playerId ?? 'A player'} came back and plays again`; tone = 'info' }
    if (e.name === 'sessionTooLong') { text = `Ended: the game ran out of time (longest session: ${d.limit ?? '?'} min)`; tone = 'warning' }
    if (e.name === 'sessionStalled') { text = `Stopped: nothing was left to do after ${d.step ? `the step ${d.step}` : 'a step'} failed`; tone = 'danger' }
    if (e.name === 'sessionFailed') {
      endedFor = d.step ? String(d.step) : ''
      text = `Ended: ${failedAt(d.step, lineOf(d))} and stalled the game`
      tone = 'danger'
    }
    if (e.name === 'stepFailed') {
      const stalled = endedFor !== null && (endedFor === '' || endedFor === String(d.step ?? ''))
      if (stalled) endedFor = null
      text = stalled ? failedAt(d.step, lineOf(d)) : `${failedAt(d.step, lineOf(d))}; the game went on`
      tone = stalled ? 'danger' : 'warning'
    }
    if (e.name === 'stepsFailedToLoad') {
      // "InitStep: line 24: No such property: five"
      const m = /^([^:]+): (?:line (\d+):)?/.exec(String(d.error ?? ''))
      text = `Stopped before anyone joined: ${m ? failedAt(m[1].trim(), m[2] ? Number(m[2]) : null) : 'a step failed'} as the steps loaded`
      tone = 'danger'
    }
    if (e.name === 'sessionStopped') { text = `Stopped${d.by ? ` by ${d.by}` : ' by a researcher'}`; tone = 'warning' }
    if (e.name === 'serverStopping') { text = 'Ended: the server was stopped'; tone = 'warning' }
    if (e.name === 'engineReloaded') {
      const restarted = d.restarted === true || d.restarted === 'true'
      text = `The engine was reloaded${d.by ? ` by ${d.by}` : ''}${restarted ? '; the game started again from the beginning' : ''}`
      tone = 'info'
    }
    out.push({ key: `${i}-${e.name}`, at: e.datetime, text, tone })
  }
  return out
}

/**
 * The experiment's own `autopilot.<method>(…)` call in its steps (e.g. "waitingRoom"), as written,
 * up to its closing parenthesis and cut to `max` characters; null when its steps make none.
 */
export function stepsCall (steps: { source: string }[] | undefined, method: string, max = 220): string | null {
  const needle = `autopilot.${method}(`
  for (const step of steps || []) {
    const src = step.source || ''
    // (skipping commented-out lines)
    const lines = src.split('\n')
    let offset = 0
    for (const line of lines) {
      const at = line.indexOf(needle)
      if (at >= 0 && !line.slice(0, at).includes('//')) {
        const start = offset + at
        let depth = 0
        let end = src.length
        for (let i = start + needle.length - 1; i < src.length; i++) {
          if (src[i] === '(') depth++
          else if (src[i] === ')' && --depth === 0) { end = i + 1; break }
        }
        const call = src.slice(start, end).replace(/\s+/g, ' ').trim()
        return call.length > max ? call.slice(0, max - 1) + '…' : call
      }
      offset += line.length + 1
    }
  }
  return null
}
