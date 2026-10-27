import { computed, reactive, watch } from 'vue'
import { AdminConnection } from '@/lib/connection'
import { http, HttpError, onUnauthorized } from '@/lib/http'
import { LiveGraph } from '@/lib/graph'
import { notify } from '@/ui/toast'
import type {
  AuthState, EngineState, Experiment, Instance, LiveEvent, OutputEntry, TieEditResult, User,
} from './types'

/**
 * Everything the console knows about the server, in one place.
 *
 * The server keeps the researcher's selection (experiment and session) itself and pushes the whole
 * picture as "User JSON" after most actions (models/User.java toJson). That push replaces the
 * fields here wholesale, so a key the server stopped sending (e.g. a deleted experiment) disappears
 * too; the old console merged pushes and kept stale ones.
 *
 * Pages read from `admin` and call the actions below; they never talk to the socket themselves.
 */

const conn = new AdminConnection()
const graph = new LiveGraph()

const state = reactive({
  auth: 'checking' as AuthState,
  email: '',
  uid: '',
  /** The newest User JSON (null until the first one arrives after sign-in). */
  user: null as User | null,
  experiment: null as Experiment | null,
  /** The session the server has selected; null when none. */
  instance: null as Instance | null,
  /** Whether the first full state has arrived since signing in. */
  ready: false,
  engine: 'ready' as EngineState,
  engineReloadedAt: 0,
  /** Why the engine needs a reload when it is not (only) about changed steps; '' otherwise. */
  engineProblem: '',
  /** Everything the engine said, newest last (capped). */
  output: [] as OutputEntry[],
  unseenErrors: 0,
  /** Events as they are recorded during the session (pushed by the server), newest last. */
  events: [] as LiveEvent[],
  /** The last tie edit's outcome, for the network page. */
  lastTieEdit: null as TieEditResult | null,
  /** The newest error the server sent in reply to a request ({"error", "request"}). */
  requestError: null as { request: string, text: string, at: number } | null,
})

const OUTPUT_CAP = 500
const EVENTS_CAP = 1000
/** What a request says when the connection is down (it is not sent at all). */
export const NOT_CONNECTED = 'Not connected to the server'
let outputSeq = 0

function pushOutput (kind: OutputEntry['kind'], text: string, source?: string) {
  if (!text) return
  state.output.push({ id: ++outputSeq, kind, text, source, at: Date.now() })
  if (state.output.length > OUTPUT_CAP) state.output.splice(0, state.output.length - OUTPUT_CAP)
  if (kind === 'error') state.unseenErrors++
}

// ---- incoming messages ----------------------------------------------------------------------

type Waiter = { match: (m: any) => boolean, resolve: (m: any) => void, timer: any }
let waiters: Waiter[] = []
/** Resolve with the next message that matches (or null after `ms`). */
function nextMessage (match: (m: any) => boolean, ms = 15000): Promise<any> {
  return new Promise(resolve => {
    const w: Waiter = { match, resolve, timer: null }
    w.timer = setTimeout(() => { waiters = waiters.filter(x => x !== w); resolve(null) }, ms)
    waiters.push(w)
  })
}

function handle (m: any) {
  if (!m || typeof m !== 'object') return
  for (const w of waiters.slice()) {
    if (w.match(m)) {
      clearTimeout(w.timer)
      waiters = waiters.filter(x => x !== w)
      w.resolve(m)
    }
  }
  // the server announces a full replay of the network (after LogIn); drop what we had, which
  // also forgets people who left while the connection was down
  if (m.graphReplay) { graph.clear(); return }
  if (graph.apply(m)) return

  if ('user' in m && m.user && typeof m.user === 'object') {
    applyUserJson(m)
  }
  if (m.notify) {
    if (m.notify.ScriptEngineReloading) {
      state.engine = 'loading'
      state.engineProblem = ''
      armEngineWatchdog()
      // the engine empties the network when it rebuilds
      graph.clear()
    }
    if (m.notify.ScriptEngineReloaded) {
      clearEngineWatchdog()
      state.engine = 'loaded'
      state.engineProblem = ''
      state.engineReloadedAt = Date.now()
      setTimeout(() => { if (state.engine === 'loaded') state.engine = 'ready' }, 2500)
    }
    // (a newer server tells every console when a rebuild fails)
    if (m.notify.ScriptEngineReloadFailed && state.engine === 'loading') engineDidNotFinish(ENGINE_FAILED)
  }
  if (typeof m.error === 'string' && m.error.trim()) {
    pushOutput('error', m.error.trim(), typeof m.request === 'string' ? m.request : undefined)
    if (typeof m.request === 'string') state.requestError = { request: m.request, text: m.error.trim(), at: Date.now() }
    // a rebuild that threw: the server answers only the console that asked, and never says Reloaded
    if (state.engine === 'loading' && REBUILDS.has(m.request)) engineDidNotFinish(ENGINE_FAILED)
    if (m.status === 'unauthorized' || /^You are not signed in/.test(m.error)) void refreshSignIn(state.uid)
  }
  if (typeof m.output === 'string' && m.output.trim()) {
    pushOutput('output', m.output.trim())
  }
  if (m.tieEdit) {
    state.lastTieEdit = { ...m.tieEdit, at: Date.now() }
  }
  if (m.event && typeof m.event === 'object' && isSelectedSessionEvent(m.event)) {
    state.events.push(m.event)
    if (state.events.length > EVENTS_CAP) state.events.splice(0, state.events.length - EVENTS_CAP)
  }
}

/**
 * Every console of every account gets every recorded event, so keep only the selected session's.
 * Newer servers say which session an event belongs to. Older ones don't: only one session runs on
 * the shared engine, so an event is this session's only while this session is the running one.
 */
function isSelectedSessionEvent (ev: any): boolean {
  const inst = state.instance
  if (!inst) return false
  const id = 'instanceId' in ev ? ev.instanceId : ev.experimentInstanceId
  if (id !== undefined) return id !== null && id !== '' && Number(id) === inst.id
  return inst.status === 'RUNNING' || inst.status === 'TESTING'
}

// ---- the engine's reload ------------------------------------------------------------------------

/** Requests that rebuild the engine; an error in reply means the rebuild did not finish. */
const REBUILDS = new Set(['ReloadEngine', 'SelectExperiment', 'SelectInstance', 'LaunchGame'])
const ENGINE_FAILED = 'The engine did not finish reloading. The output panel says why. Fix the problem, then reload it.'
const ENGINE_MISSED = 'The connection dropped while the engine was reloading. Reload it if the experiment does not behave as expected.'
const ENGINE_SILENT = 'The engine has not said that it finished reloading. Reload it if the experiment does not respond.'
/** A rebuild that never says it finished is taken as failed after this long. */
const ENGINE_WATCHDOG_MS = 90000
let engineWatchdog: ReturnType<typeof setTimeout> | null = null

function armEngineWatchdog () {
  clearEngineWatchdog()
  engineWatchdog = setTimeout(() => {
    engineWatchdog = null
    if (state.engine === 'loading') engineDidNotFinish(ENGINE_SILENT)
  }, ENGINE_WATCHDOG_MS)
}
function clearEngineWatchdog () {
  if (engineWatchdog) clearTimeout(engineWatchdog)
  engineWatchdog = null
}
/** Leave "Reloading…" for "Reload needed", so the researcher can try again. */
function engineDidNotFinish (why: string) {
  clearEngineWatchdog()
  state.engine = 'stale'
  state.engineProblem = why
}
// A reload under way when the connection dropped: how it ended went to the old socket. (If it is
// still going, its "Reloaded" arrives on the new one.)
watch(conn.state, (s) => { if (s === 'open' && state.engine === 'loading') engineDidNotFinish(ENGINE_MISSED) }, { flush: 'sync' })

/**
 * The server sends times as local wall-clock text ("2026-10-27 14:03:05,123") and, newer, the same
 * moment as ISO-8601 UTC in a *Utc twin. Use the UTC one wherever it is present, so every page reads
 * the right moment whatever the server's and the browser's time zones.
 */
function preferUtcTimes (v: any, depth = 0) {
  if (!v || typeof v !== 'object' || depth > 6) return
  if (Array.isArray(v)) { for (const x of v) preferUtcTimes(x, depth + 1); return }
  if (typeof v.creationTimeUtc === 'string') v.creationTime = v.creationTimeUtc
  if (typeof v.datetimeUtc === 'string') v.datetime = v.datetimeUtc
  for (const k in v) {
    const x = v[k]
    if (x && typeof x === 'object') preferUtcTimes(x, depth + 1)
  }
}

function applyUserJson (m: any) {
  preferUtcTimes(m)
  state.user = m.user
  // missing key = nothing selected (User.java omits it)
  state.experiment = m.experiment && typeof m.experiment === 'object' ? m.experiment : null
  const inst = m.experimentInstance && typeof m.experimentInstance === 'object' ? m.experimentInstance : null
  if (!inst || !state.instance || inst.id !== state.instance.id) {
    // a different session: its live event feed starts again from what the server recorded
    state.events = []
  }
  state.instance = inst
  state.ready = true
}

conn.onMessage(handle)

// ---- sign-in ----------------------------------------------------------------------------------

/**
 * Forget everything the signed-in account saw (its output can hold participant data), so the next
 * account to sign in on this computer starts clean. Requests still waiting for an answer get null.
 */
function resetState () {
  state.user = null
  state.experiment = null
  state.instance = null
  state.ready = false
  state.output = []
  state.events = []
  state.unseenErrors = 0
  state.requestError = null
  state.lastTieEdit = null
  state.engine = 'ready'
  state.engineProblem = ''
  state.engineReloadedAt = 0
  clearEngineWatchdog()
  graph.clear()
  const pending = waiters
  waiters = []
  for (const w of pending) { clearTimeout(w.timer); w.resolve(null) }
}

/** The session is gone: show the sign-in page (or the first-account form). */
function signedOut (body?: any) {
  conn.stop()
  resetState()
  state.auth = body && body.status === 'create-first-user' ? 'firstUser' : 'signedOut'
}

onUnauthorized((body) => signedOut(body))

type StateReply = { uid: string, email: string, connectSocket: string }

async function boot () {
  state.auth = 'checking'
  try {
    const cfg = await http.get<StateReply>('/state', { quiet401: true })
    if (!cfg || !cfg.uid) throw new HttpError(401, 'no session')
    // another account: nothing the previous one saw carries over
    if (state.email && cfg.email !== state.email) resetState()
    state.uid = cfg.uid
    state.email = cfg.email
    state.auth = 'signedIn'
    state.ready = false
    graph.clear()
    conn.start(cfg.uid, cfg.connectSocket)
  } catch (e: any) {
    if (e instanceof HttpError && e.status === 401) {
      signedOut(e.body)
    } else {
      state.auth = 'unreachable'
    }
  }
}

let refreshingSignIn = false
/**
 * Signing in (or out) in another tab gives the account a new uid, while this tab keeps the old one
 * and the server refuses its socket; its HTTP calls still work, since the cookie is shared. Read
 * the current sign-in and reconnect with it. `refusedUid` is the uid the server just refused (null
 * when another tab said it signed in): sign out when there is no sign-in, or when it is the very
 * one refused, so this never loops.
 */
async function refreshSignIn (refusedUid: string | null) {
  if (refreshingSignIn || state.auth !== 'signedIn') return
  refreshingSignIn = true
  try {
    const cfg = await http.get<StateReply>('/state', { quiet401: true })
    if (!cfg || !cfg.uid) { signedOut(); return }
    if (cfg.uid === state.uid) {
      if (cfg.uid === refusedUid) signedOut()
      return
    }
    if (cfg.email !== state.email) { await boot(); return }
    state.uid = cfg.uid
    conn.start(cfg.uid, cfg.connectSocket)
    if (refusedUid) notify.info('Reconnected', 'You signed in again in another tab, so this tab reconnected. Try your last action again.')
  } catch (e: any) {
    if (e instanceof HttpError && e.status === 401) signedOut(e.body)
    // otherwise the server is unreachable right now; the next refusal tries again
  } finally {
    refreshingSignIn = false
  }
}

// Signing in or out in one tab changes the sign-in of every tab (they share the cookie); tell the
// others, so they follow at once instead of failing their next action.
const AUTH_KEY = 'bb-admin-auth'
function tellOtherTabs (what: 'in' | 'out') {
  try { localStorage.setItem(AUTH_KEY, `${what} ${Date.now()}`) } catch (e) { /* storage blocked: they catch up on their next action */ }
}
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (ev) => {
    if (ev.key !== AUTH_KEY || !ev.newValue || state.auth === 'checking') return
    const signedInThere = ev.newValue.startsWith('in')
    if (state.auth === 'signedIn') void (signedInThere ? refreshSignIn(null) : boot())
    else if (signedInThere) void boot()
  })
}

async function login (email: string, password: string) {
  await http.post('/login', { email, password }, { quiet401: true })
  tellOtherTabs('in')
  await boot()
}

/** Resolves false (and says so) when the server could not be told, so the session is still active. */
async function logout (): Promise<boolean> {
  try {
    await http.get('/logout', { quiet401: true })
  } catch (e) {
    // the server answers 401 once it has ended the session; anything else means it may not have
    if (!(e instanceof HttpError && e.status === 401)) {
      notify.error('Could not sign out', 'The server did not answer, so you are still signed in. Try again.')
      return false
    }
  }
  signedOut()
  tellOtherTabs('out')
  return true
}

async function createFirstUser (email: string, password: string, defaultLanguageId: number) {
  await http.post('/createFirstUser', { email, password, defaultLanguageId }, { quiet401: true })
  await login(email, password)
}

// ---- actions ----------------------------------------------------------------------------------

/** Whether requests can be sent now; when not, says so in a toast (for buttons to check first). */
function ensureConnected (): boolean {
  if (conn.state.value === 'open') return true
  notify.error(NOT_CONNECTED, 'Wait until the connection is back (see the top bar), then try again.')
  return false
}

/** Send a request; false (with admin.requestError saying so) when the connection is down. */
function send (action: string, payload: Record<string, any> = {}): boolean {
  if (conn.send(action, payload)) return true
  state.requestError = { request: action, text: NOT_CONNECTED, at: Date.now() }
  return false
}

const isUserJson = (m: any) => 'user' in m && m.user && typeof m.user === 'object'
const isErrorFor = (request: string) => (m: any) => typeof m.error === 'string' && m.request === request

/** Wait for the server's next User JSON (most actions end with one). */
function nextUserJson (ms = 20000) {
  return nextMessage(isUserJson, ms)
}
/**
 * Wait for the User JSON that follows `request`, or the error the server sends instead.
 * Resolves with the User JSON, the error message when `errorsCount` (it is an answer too), or null.
 */
async function replyTo (request: string, ms: number, errorsCount = false, match: (m: any) => boolean = isUserJson) {
  const isError = isErrorFor(request)
  const m = await nextMessage(x => match(x) || isError(x), ms)
  if (m && isError(m) && !errorsCount) return null
  return m
}

// While the connection is down nothing is sent: the actions that wait for an answer resolve null at
// once (admin.requestError says why), the others return false.
const actions = {
  selectExperiment (id: number) {
    if (!send('SelectExperiment', { experimentId: id })) return Promise.resolve(null)
    return replyTo('SelectExperiment', 20000)
  },
  /** Create and start a session of the selected experiment. */
  launch (name: string, parameters: Record<string, string | number | boolean>) {
    if (!send('LaunchGame', { experimentId: state.experiment?.id, name, parameters })) return Promise.resolve(null)
    // a refused launch ({"error", "request": "LaunchGame"}) resolves null at once; the reason is in admin.requestError
    return replyTo('LaunchGame', 60000, false, m => isUserJson(m) && m.experimentInstance && m.experimentInstance.name === name)
  },
  /** Make a session the current one and (re)start it; rebuilds the engine. */
  selectInstance (id: number) {
    if (!send('SelectInstance', { id })) return Promise.resolve(null)
    return replyTo('SelectInstance', 60000)
  },
  stopInstance (id: number) {
    if (!send('StopGame', { id })) return Promise.resolve(null)
    return replyTo('StopGame', 20000)
  },
  /** Resolves with the User JSON, or with the server's refusal ({error, request}); null when no answer. */
  deleteInstance (id: number) {
    if (!send('DeleteInstance', { id })) return Promise.resolve(null)
    return replyTo('DeleteInstance', 20000, true)
  },
  /** Run initStep.start(): the game begins and closes to newcomers. False when not connected. */
  startGame (): boolean {
    return send('RunGame')
  },
  /** False when not connected. */
  reloadEngine (): boolean {
    return send('ReloadEngine')
  },
  /** False when not connected (and nothing is logged). */
  runScript (script: string): boolean {
    if (!send('SendScript', { script })) return false
    pushOutput('input', script, 'console')
    return true
  },
  /** False when not connected. */
  runStep (step: { id: number | null, name: string, source: string }): boolean {
    return send('SendStep', { id: step.id ?? -1, name: step.name, source: step.source })
  },
  /** False when not connected. */
  makeChoice (choiceUID: string): boolean {
    return send('MakeChoice', { choiceUID })
  },
  editTie (op: 'add' | 'remove', a: string, b: string): Promise<TieEditResult> {
    if (!send('EditTie', { op, a, b })) {
      return Promise.resolve({ ok: false, op, a, b, reason: `${NOT_CONNECTED}. Try again once the connection is back.` })
    }
    return nextMessage(m => m.tieEdit && m.tieEdit.op === op && m.tieEdit.a === a && m.tieEdit.b === b, 15000)
      .then(m => (m ? m.tieEdit : { ok: false, op, a, b, reason: 'The server did not answer.' }) as TieEditResult)
  },
  deleteExperiment (exp: Experiment) {
    if (!send('DeleteExperiment', { selectedExperiment: exp.name, experimentId: exp.id })) return Promise.resolve(null)
    return replyTo('DeleteExperiment', 20000, true)
  },
  toggleFileMode () {
    if (!send('ToggleFileMode')) return Promise.resolve(null)
    return replyTo('ToggleFileMode', 20000)
  },
  /** Ask the server for the newest User JSON. */
  refresh () {
    if (!send('Update')) return Promise.resolve(null)
    return nextUserJson()
  },
  /** An earlier session's recorded events. */
  async instanceEvents (id: number): Promise<{ name: string, events: any[] } | null> {
    if (!send('ShowEvent', { id })) return null
    const m = await nextMessage(x => 'events' in x && 'eventExperimentInstance' in x, 30000)
    if (m) preferUtcTimes(m.events)
    return m ? { name: m.eventExperimentInstance, events: m.events } : null
  },
  clearOutput () {
    state.output = []
    state.unseenErrors = 0
  },
  markErrorsSeen () {
    state.unseenErrors = 0
  },
  /** Steps changed since the engine last loaded them (e.g. one was deleted). */
  markEngineStale () {
    if (state.engine !== 'loading') state.engine = 'stale'
  },
  note (text: string) {
    pushOutput('info', text)
  },
}

// ---- derived ----------------------------------------------------------------------------------

const experiments = computed<Experiment[]>(() => {
  const list = state.user?.experiments || []
  return [...list].sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }))
})
const instances = computed<Instance[]>(() => {
  const list = state.experiment?.instances || []
  return [...list].sort((a, b) => (b.id || 0) - (a.id || 0))
})
const running = computed(() => state.instance && (state.instance.status === 'RUNNING' || state.instance.status === 'TESTING'))
/**
 * Whether rebuilding the engine now would disconnect people. The engine is shared by every account
 * and the autopilot, and every console gets its network, so players in it may be in another
 * researcher's session. (See confirmEngineRebuild in ./engineGuard.)
 */
const engineInUse = computed(() => {
  void graph.version.value
  return !!running.value || graph.nodes.size > 0
})

export const admin = state
export const connection = conn
export { graph, boot, login, logout, createFirstUser, actions, experiments, instances, running, engineInUse, ensureConnected, nextMessage }

/** For tests: feed a message as if it came from the server. */
export function __receive (m: any) { handle(m) }
export const signedIn = computed(() => state.auth === 'signedIn')
export const connectionState = conn.state
export const hasExperiment = computed(() => !!state.experiment)
export const fileMode = computed(() => !!state.experiment?.fileMode)
