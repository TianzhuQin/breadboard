import { computed, reactive, watch } from 'vue'
import { AdminConnection } from '@/lib/connection'
import { http, HttpError, onUnauthorized } from '@/lib/http'
import { LiveGraph } from '@/lib/graph'
import { notify } from '@/ui/toast'
import { settleConfirm } from '@/ui/confirm'
import type {
  AuthState, EngineInfo, EngineState, Experiment, Instance, LiveEvent, OutputEntry, TieEditResult, User,
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
  /**
   * Why the sign-in page is showing, when this console didn't sign out itself: 'ended' when the
   * server ended this browser's session (the account signed in or out somewhere else, or its
   * password changed). Cleared by the next sign-in.
   */
  signOutReason: null as null | 'ended',
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
  /** Whether user.engine says what the engine runs now (false from a rebuild until it is asked again). */
  engineInfoCurrent: true,
  /**
   * When the User JSON that user.engine came from arrived (this computer's clock), while it is
   * current; 0 when unknown. What this console hears after it (an answer to a start, a live event)
   * is newer than what user.engine says.
   */
  engineInfoAt: 0,
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

function pushOutput (kind: OutputEntry['kind'], text: string, source?: string, requestId?: string) {
  if (!text) return
  const entry: OutputEntry = { id: ++outputSeq, kind, text, source, at: Date.now() }
  if (requestId) entry.requestId = requestId
  state.output.push(entry)
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
      engineInfoOutdated()
      // the engine empties the network when it rebuilds
      graph.clear()
    }
    if (m.notify.ScriptEngineReloaded) {
      clearEngineWatchdog()
      state.engine = 'loaded'
      state.engineProblem = ''
      state.engineReloadedAt = Date.now()
      setTimeout(() => { if (state.engine === 'loaded') state.engine = 'ready' }, 2500)
      askEngineInfo()
      askEngineInfoAgain()
    }
    // a game started (or a start was taken back) without this console asking: by the autopilot, the
    // AMT timer or the experiment's own code. Ask what the engine runs, so the session page knows.
    if (m.notify.GameStarted || m.notify.GameStartFailed) askEngineInfo()
    // every console hears when a rebuild fails, with the reason (it may not be the one that asked)
    if (m.notify.ScriptEngineReloadFailed) {
      const why = reloadFailedText(m.notify.ScriptEngineReloadFailed)
      if (why || !state.engineProblem) engineDidNotFinish(why || ENGINE_FAILED_ELSEWHERE)
      askEngineInfo()
    }
  }
  // what the frame answers (SendScript, SendStep, RunGame …) or where it came from (OnJoinStep, OnLeaveStep)
  const source = typeof m.source === 'string' ? m.source : typeof m.request === 'string' ? m.request : undefined
  // the id of the script request it answers, echoed by newer servers
  const requestId = typeof m.requestId === 'string' && m.requestId ? m.requestId : undefined
  if (typeof m.error === 'string' && m.error.trim()) {
    pushOutput('error', m.error.trim(), source, requestId)
    if (typeof m.request === 'string') state.requestError = { request: m.request, text: m.error.trim(), at: Date.now() }
    // a rebuild that threw: the server answers only the console that asked, and never says Reloaded
    if (state.engine === 'loading' && REBUILDS.has(m.request)) engineDidNotFinish(ENGINE_FAILED)
    // a refused rebuild changed nothing, and one that threw may have changed something
    if (REBUILDS.has(m.request) && engineInfoPhase === 'rebuilding') askEngineInfo()
    if (m.status === 'unauthorized' || /^You are not signed in/.test(m.error)) void refreshSignIn(state.uid)
  }
  if (typeof m.output === 'string' && m.output.trim()) {
    pushOutput('output', m.output.trim(), source, requestId)
  }
  if (m.tieEdit) {
    state.lastTieEdit = { ...m.tieEdit, at: Date.now() }
  }
  if (m.event && typeof m.event === 'object' && isSelectedSessionEvent(m.event)) {
    state.events.push({ ...m.event, receivedAt: Date.now() })
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
  const id = ev.experimentInstanceId
  if (id !== undefined) return id !== null && id !== '' && Number(id) === inst.id
  return inst.status === 'RUNNING' || inst.status === 'TESTING'
}

// ---- the engine's reload ------------------------------------------------------------------------

/** Requests that rebuild the engine; an error in reply means the rebuild did not finish. */
const REBUILDS = new Set(['ReloadEngine', 'SelectExperiment', 'SelectInstance', 'LaunchGame'])
const ENGINE_FAILED = 'The engine did not finish reloading. The output panel says why. Fix the problem, then reload it.'
const ENGINE_FAILED_ELSEWHERE = 'The engine did not finish reloading. Reload it to try again.'
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
/** The problem to show for {notify: {ScriptEngineReloadFailed: reason}}; '' when it gives no reason in words. */
function reloadFailedText (reason: unknown): string {
  let text = typeof reason === 'string' ? reason.trim() : ''
  if (!text) return ''
  if (text.length > 300) text = text.slice(0, 299) + '…'
  return `The engine did not finish reloading: ${text}${/[.!?…]$/.test(text) ? '' : '.'} Fix the problem, then reload it.`
}
// A reload under way when the connection dropped: how it ended went to the old socket. (If it is
// still going, its "Reloaded" arrives on the new one.)
watch(conn.state, (s) => {
  if (s !== 'open') return
  if (state.engine === 'loading') engineDidNotFinish(ENGINE_MISSED)
  // the server answers every (re)connect's LogIn with a fresh User JSON
  if (engineInfoPhase !== 'current') engineInfoPhase = 'asked'
}, { flush: 'sync' })

// ---- what the engine runs ---------------------------------------------------------------------

/**
 * The User JSON says what the shared engine runs (`engine`), as it is when the JSON is made. The
 * reply to a request that rebuilds the engine is made before the rebuild (Breadboard.java hands the
 * rebuild to the engine's actor and answers at once), so it still names what ran before, and
 * nothing is sent when the rebuild has changed it. So from the moment a rebuild is asked for or
 * announced, the engine info is out of date (admin.engineInfoCurrent); once the rebuild has ended,
 * ask for the User JSON again, and the next one is current.
 *
 * The engine says it rebuilt before the request that rebuilt it is done: a launch or a resume then
 * still loads the steps, ends the session the engine ran and starts its own. Only the console that
 * asked gets a User JSON after that, so every console asks once more a little later.
 */
let engineInfoPhase: 'current' | 'rebuilding' | 'asked' = 'current'
let engineInfoTimer: ReturnType<typeof setTimeout> | null = null
let engineSettleTimer: ReturnType<typeof setTimeout> | null = null
/** A rebuild this console asked for that is never announced (nor refused) is taken as not happening after this long. */
const ENGINE_INFO_WAIT_MS = 15000
/** How long after the engine says it rebuilt to ask again what it runs. */
export const ENGINE_SETTLE_MS = 2000

function engineInfoOutdated () {
  engineInfoPhase = 'rebuilding'
  state.engineInfoCurrent = false
  state.engineInfoAt = 0
  clearEngineSettle()
  if (engineInfoTimer) clearTimeout(engineInfoTimer)
  engineInfoTimer = setTimeout(() => {
    engineInfoTimer = null
    if (engineInfoPhase === 'rebuilding' && state.engine !== 'loading') askEngineInfo()
  }, ENGINE_INFO_WAIT_MS)
}
function askEngineInfo () {
  if (engineInfoTimer) clearTimeout(engineInfoTimer)
  engineInfoTimer = null
  engineInfoPhase = 'asked'
  // (when the connection is down, the LogIn after it comes back is answered with a User JSON too)
  conn.send('Update')
}
/** Ask again after ENGINE_SETTLE_MS, unless another rebuild has begun by then. */
function askEngineInfoAgain () {
  clearEngineSettle()
  engineSettleTimer = setTimeout(() => {
    engineSettleTimer = null
    if (engineInfoPhase !== 'rebuilding' && state.engine !== 'loading') askEngineInfo()
  }, ENGINE_SETTLE_MS)
}
function clearEngineSettle () {
  if (engineSettleTimer) clearTimeout(engineSettleTimer)
  engineSettleTimer = null
}
function engineInfoOf (v: any): EngineInfo | null {
  if (!v || typeof v !== 'object') return null
  const num = (x: any) => (typeof x === 'number' ? x : null)
  const str = (x: any) => (typeof x === 'string' && x ? x : null)
  return {
    experimentId: num(v.experimentId),
    experimentName: str(v.experimentName),
    sessionId: num(v.sessionId),
    sessionName: str(v.sessionName),
    owner: str(v.owner),
    gameStarted: typeof v.gameStarted === 'boolean' ? v.gameStarted : null,
  }
}

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
  // the server sends it beside the user (User.toJson)
  m.user.engine = engineInfoOf('engine' in m ? m.engine : m.user.engine)
  if (engineInfoPhase === 'asked') {
    engineInfoPhase = 'current'
    state.engineInfoCurrent = true
  }
  if (engineInfoPhase === 'current') state.engineInfoAt = Date.now()
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
  state.engineInfoCurrent = true
  state.engineInfoAt = 0
  engineInfoPhase = 'current'
  if (engineInfoTimer) clearTimeout(engineInfoTimer)
  engineInfoTimer = null
  clearEngineSettle()
  clearEngineWatchdog()
  graph.clear()
  const pending = waiters
  waiters = []
  for (const w of pending) { clearTimeout(w.timer); w.resolve(null) }
}

/** The session is gone: show the sign-in page (or the first-account form). */
function signedOut (body?: any) {
  conn.stop()
  // a question asked on a page that is gone now
  settleConfirm(false)
  resetState()
  state.signOutReason = body && body.reason === 'ended' ? 'ended' : null
  state.auth = body && body.status === 'create-first-user' ? 'firstUser' : 'signedOut'
}

onUnauthorized((body) => signedOut(body))

type StateReply = { uid: string, email: string, connectSocket: string }
/** The socket address /state gave (see connection.socketUrl), to reconnect with. */
let socketSetting: string | undefined

async function boot () {
  state.auth = 'checking'
  try {
    const cfg = await http.get<StateReply>('/state', { quiet401: true })
    if (!cfg || !cfg.uid) throw new HttpError(401, 'no session')
    // another account: nothing the previous one saw carries over
    if (state.email && cfg.email !== state.email) resetState()
    state.uid = cfg.uid
    state.email = cfg.email
    state.signOutReason = null
    state.auth = 'signedIn'
    state.ready = false
    socketSetting = cfg.connectSocket
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
 * Signing in (or out) or changing the password in another tab gives the account a new uid, while
 * this tab keeps the old one and the server refuses its socket (newer servers also close it, after
 * saying {"error", "status": "unauthorized"}); its HTTP calls still work, since the cookie is
 * shared. Read the current sign-in and reconnect with it. `refusedUid` is the uid the server just
 * refused (null when another tab said it signed in): sign out when there is no sign-in, or when it
 * is the very one refused, so this never loops. Refusals that arrive while /state is being read
 * (the closed socket reconnecting with the old uid) are answered by that one read.
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
    socketSetting = cfg.connectSocket
    conn.start(cfg.uid, cfg.connectSocket)
    if (refusedUid) notify.info('Reconnected', 'Your sign-in changed in another tab (you signed in again or changed your password), so this tab reconnected. Try your last action again.')
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

/**
 * Changing the password gives the account a new uid (the server answers /saveUserSettings with it),
 * and the server refuses every request that carries the old one: use the new one from now on and
 * reconnect with it. The account's other tabs are told to follow.
 */
function adoptUid (uid: string) {
  if (!uid || uid === state.uid) return
  state.uid = uid
  if (state.auth !== 'signedIn') return
  conn.start(uid, socketSetting)
  tellOtherTabs('in')
}

async function login (email: string, password: string) {
  await http.post('/login', { email, password }, { quiet401: true })
  tellOtherTabs('in')
  await boot()
}

/** Resolves false (and says so) when the server could not be told, so the session is still active. */
async function logout (): Promise<boolean> {
  try {
    // A POST: a link on another site must not be able to sign the researcher out everywhere.
    await http.post('/logout', undefined, { quiet401: true })
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
  if (!conn.send(action, payload)) {
    state.requestError = { request: action, text: NOT_CONNECTED, at: Date.now() }
    return false
  }
  if (REBUILDS.has(action)) engineInfoOutdated()
  return true
}

/**
 * A fresh id for a script request (at most 64 characters, as the server accepts). The server echoes
 * it on every frame of its answer, so the answer can't be taken for another script's. Only this
 * socket gets the answer, so unique within this page is enough.
 */
let requestSeq = 0
function newRequestId (): string {
  return `${Date.now().toString(36)}-${++requestSeq}`
}

const isUserJson = (m: any) => 'user' in m && m.user && typeof m.user === 'object'
const isErrorFor = (request: string) => (m: any) => typeof m.error === 'string' && m.request === request
/** The sessions of the experiment a User JSON shows selected ([] when none is). */
const instancesIn = (m: any): any[] => (m.experiment && Array.isArray(m.experiment.instances) ? m.experiment.instances : [])
const isLive = (i: any) => !!i && (i.status === 'RUNNING' || i.status === 'TESTING')

/** How long a file-mode switch waits for the switched mode after a reply that shows it unchanged. */
const FILE_MODE_GRACE_MS = 3000

/** Wait for the server's next User JSON (most actions end with one). */
function nextUserJson (ms = 20000) {
  return nextMessage(isUserJson, ms)
}
/**
 * Wait for the User JSON that shows `request` done (`done`), or the error the server sends instead.
 * Resolves with the User JSON, the error message when `errorsCount` (it is an answer too), or null.
 *
 * The server also sends the User JSON to every tab of the account after another tab's action,
 * unasked, so any User JSON is not necessarily the answer: each action waits for the one that
 * shows what it asked for.
 */
async function replyTo (request: string, ms: number, done: (m: any) => boolean, errorsCount = false) {
  const isError = isErrorFor(request)
  const m = await nextMessage(x => (isUserJson(x) && done(x)) || isError(x), ms)
  if (m && isError(m) && !errorsCount) return null
  return m
}

// While the connection is down nothing is sent: the actions that wait for an answer resolve null at
// once (admin.requestError says why), the others return false.
const actions = {
  /** Resolves with the User JSON that shows it selected; null when refused or no answer. */
  selectExperiment (id: number) {
    if (!send('SelectExperiment', { experimentId: id })) return Promise.resolve(null)
    return replyTo('SelectExperiment', 20000, m => !!m.experiment && m.experiment.id === id)
  },
  /**
   * Create and start a session of the selected experiment. `experimentId` is the experiment the
   * caller's page showed (and took the parameters from); the server refuses the launch when the
   * account has selected another one since, e.g. in another tab. Left out, it is the one selected now.
   */
  launch (name: string, parameters: Record<string, string | number | boolean>, experimentId?: number) {
    if (!send('LaunchGame', { experimentId: experimentId ?? state.experiment?.id, name, parameters })) return Promise.resolve(null)
    // a refused launch ({"error", "request": "LaunchGame"}) resolves null at once; the reason is in admin.requestError
    return replyTo('LaunchGame', 60000, m => !!m.experimentInstance && m.experimentInstance.name === name)
  },
  /** Make a session the current one and (re)start it; rebuilds the engine. */
  selectInstance (id: number) {
    if (!send('SelectInstance', { id })) return Promise.resolve(null)
    return replyTo('SelectInstance', 60000, m => !!m.experimentInstance && m.experimentInstance.id === id)
  },
  /** Resolves with the User JSON in which the session no longer runs (or is gone); null when refused or no answer. */
  stopInstance (id: number) {
    if (!send('StopGame', { id })) return Promise.resolve(null)
    return replyTo('StopGame', 20000, m => !instancesIn(m).some(i => i.id === id && isLive(i)) &&
      !(m.experimentInstance && m.experimentInstance.id === id && isLive(m.experimentInstance)))
  },
  /** Resolves with the User JSON without the session, or with the server's refusal ({error, request}); null when no answer. */
  deleteInstance (id: number) {
    if (!send('DeleteInstance', { id })) return Promise.resolve(null)
    return replyTo('DeleteInstance', 20000, m => !instancesIn(m).some(i => i.id === id), true)
  },
  /** Run initStep.start(): the game begins and closes to newcomers. False when not connected. */
  startGame (): boolean {
    return send('RunGame')
  },
  /** False when not connected. */
  reloadEngine (): boolean {
    return send('ReloadEngine')
  },
  /**
   * Run a script in the engine. Returns the request id its answer carries (see
   * console/engineReplies); null when not connected (and nothing is logged). It names the selected
   * experiment, so a server whose engine has another one loaded now runs nothing and says so.
   */
  runScript (script: string): string | null {
    const requestId = newRequestId()
    if (!send('SendScript', { script, requestId, experimentId: state.experiment?.id })) return null
    pushOutput('input', script, 'console')
    return requestId
  },
  /** Load a step's code into the engine, as runScript does; null when not connected. */
  runStep (step: { id: number | null, name: string, source: string }): string | null {
    const requestId = newRequestId()
    if (!send('SendStep', { id: step.id ?? -1, name: step.name, source: step.source, requestId, experimentId: state.experiment?.id })) return null
    return requestId
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
  /** Resolves with the User JSON without the experiment, or with the server's refusal ({error, request}); null when no answer. */
  deleteExperiment (exp: Experiment) {
    if (!send('DeleteExperiment', { selectedExperiment: exp.name, experimentId: exp.id })) return Promise.resolve(null)
    return replyTo('DeleteExperiment', 20000, m => !(Array.isArray(m.user.experiments) && m.user.experiments.some((e: any) => e && e.id === exp.id)), true)
  },
  /**
   * Resolves with the User JSON that shows the selected experiment's file mode switched; null when
   * refused or no answer. A newer server that cannot read or write the folder says so ({"error":
   * "Couldn't switch file mode: …", "request": "ToggleFileMode"}): null at once, with the reason in
   * admin.requestError. An older one only logs it, and answers with the mode unchanged: that answer
   * can't be told from another tab's push that came first, so a User JSON with the mode unchanged
   * counts as the answer once the switched one hasn't followed within FILE_MODE_GRACE_MS (it
   * resolves with that one; compare its fileMode).
   */
  toggleFileMode () {
    const before = state.experiment
    const id = before?.id
    const was = !!before?.fileMode
    if (!send('ToggleFileMode')) return Promise.resolve(null)
    const same = (m: any) => !!m.experiment && (id === undefined || m.experiment.id === id)
    let unchanged: ReturnType<typeof setTimeout> | null = null
    let notSwitched: (m: any) => void = () => {}
    const switched = replyTo('ToggleFileMode', 20000, (m) => {
      if (!same(m)) return false
      if (!!m.experiment.fileMode !== was) return true
      if (!unchanged) unchanged = setTimeout(() => notSwitched(m), FILE_MODE_GRACE_MS)
      return false
    })
    switched.then(() => { if (unchanged) clearTimeout(unchanged) })
    return Promise.race([switched, new Promise<any>(resolve => { notSwitched = resolve })])
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
  /**
   * The engine runs the saved steps again (each change was loaded with Run): undo markEngineStale.
   * A reload that did not finish (admin.engineProblem) stays: only a reload clears that.
   */
  markEngineCurrent () {
    if (state.engine === 'stale' && !state.engineProblem) state.engine = 'ready'
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
/**
 * What the engine runs when that is another experiment than the selected one, so live controls
 * here act on nothing (the server refuses them); null otherwise, and while that is not known.
 */
const engineElsewhere = computed<EngineInfo | null>(() => {
  const e = state.user?.engine
  const selected = state.experiment
  if (!e || e.experimentId === null || !selected || !state.engineInfoCurrent || state.engine === 'loading') return null
  return e.experimentId === selected.id ? null : e
})

export const admin = state
export const connection = conn
export {
  graph, boot, login, logout, createFirstUser, adoptUid, actions, experiments, instances, running, engineInUse,
  engineElsewhere, ensureConnected, nextMessage,
}

/** For tests: feed a message as if it came from the server. */
export function __receive (m: any) { handle(m) }
export const signedIn = computed(() => state.auth === 'signedIn')
export const connectionState = conn.state
export const hasExperiment = computed(() => !!state.experiment)
export const fileMode = computed(() => !!state.experiment?.fileMode)
