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
  waitingRoom: WaitingRoomSettings
  replaceDisconnected: ReplaceDisconnectedSettings
}
export interface AutopilotStatus {
  on: boolean
  experimentId: number | null
  experimentName: string | null
  instance: { id: number, name: string, status: string } | null
  links: { login: string, prolific: string } | null
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
  waitingRoom: { enabled: true, minPlayers: 2, maxPlayers: null, waitSeconds: 60, maxWaitSeconds: null, countdownSeconds: 5 },
  replaceDisconnected: { enabled: true, afterSeconds: 30, returnToHuman: true },
}

/** Longest wait accepted: a day (the engine's timers count milliseconds in an int). */
export const MAX_SECONDS = 86400
export const MAX_PLAYERS = 10000

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
  return {
    enabled: bool(r.enabled, DEFAULTS.enabled),
    experimentId: r.experimentId == null ? null : num(r.experimentId, NaN) || null,
    continuous: bool(r.continuous, DEFAULTS.continuous),
    nextSessionDelaySeconds: num(r.nextSessionDelaySeconds, DEFAULTS.nextSessionDelaySeconds),
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

export function normalizeState (raw: any): AutopilotState {
  const r = raw && typeof raw === 'object' ? raw : {}
  const s = r.status && typeof r.status === 'object' ? r.status : {}
  const inst = s.instance && typeof s.instance === 'object' && s.instance.id != null ? s.instance : null
  const links = s.links && typeof s.links === 'object' && (s.links.login || s.links.prolific) ? s.links : null
  return {
    settings: normalizeSettings(r.settings),
    source: r.source === 'config' || r.source === 'admin' ? r.source : 'off',
    status: {
      on: !!s.on,
      experimentId: s.experimentId == null ? null : Number(s.experimentId),
      experimentName: typeof s.experimentName === 'string' ? s.experimentName : null,
      instance: inst ? { id: Number(inst.id), name: String(inst.name ?? ''), status: String(inst.status ?? '') } : null,
      links: links ? { login: String(links.login || ''), prolific: String(links.prolific || '') } : null,
    },
  }
}

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
  else if (v > opts.max) errors[field] = opts.max === MAX_SECONDS ? 'At most a day (86400 seconds).' : `At most ${opts.max}.`
}

/**
 * Checks the form. Keys are field paths ("waitingRoom.minPlayers"); a section that is switched off
 * is not checked (its values are replaced by defaults when saving, see toSettings).
 */
export function validateAutopilot (f: AutopilotForm): AutopilotErrors {
  const e: AutopilotErrors = {}
  if (f.enabled && f.experimentId == null) e.experimentId = 'Choose the experiment to run.'
  if (f.continuous) {
    whole(f.nextSessionDelaySeconds, 'nextSessionDelaySeconds', e, { min: 0, max: MAX_SECONDS, required: true, what: 'a number of seconds (0 opens it at once)' })
  }
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
export function describeWaitingRoom (f: AutopilotForm): string {
  const w = f.waitingRoom
  if (!w.enabled) return 'There is no waiting room: the game starts when you press Start, or when the experiment\'s own script starts it.'
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

export async function saveAutopilot (s: AutopilotSettings): Promise<AutopilotState> {
  return normalizeState(await http.post('/autopilot', s))
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

const AUTOPILOT_EVENTS = new Set(['autopilotStart', 'autopilotCancelled', 'aiTakeover', 'aiReturned'])

/** The autopilot's recent events in words, newest first. */
export function autopilotActivity (events: { name: string, datetime: string, data: Record<string, any> }[], limit = 6) {
  const out: { key: string, at: string, text: string, tone: 'success' | 'warning' | 'info' | 'neutral' }[] = []
  for (let i = events.length - 1; i >= 0 && out.length < limit; i--) {
    const e = events[i]
    if (!AUTOPILOT_EVENTS.has(e.name)) continue
    const d = e.data || {}
    let text = ''
    let tone: 'success' | 'warning' | 'info' | 'neutral' = 'neutral'
    if (e.name === 'autopilotStart') { text = `Game started with ${d.players ?? '?'} ${String(d.players) === '1' ? 'person' : 'people'}`; tone = 'success' }
    if (e.name === 'autopilotCancelled') { text = `Called off: only ${d.players ?? '?'} joined`; tone = 'warning' }
    if (e.name === 'aiTakeover') { text = `The computer took over ${d.playerId ?? 'a player'}'s seat`; tone = 'info' }
    if (e.name === 'aiReturned') { text = `${d.playerId ?? 'A player'} came back and plays again`; tone = 'info' }
    out.push({ key: `${i}-${e.name}`, at: e.datetime, text, tone })
  }
  return out
}
