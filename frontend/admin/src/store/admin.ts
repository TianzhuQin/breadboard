import { computed, reactive } from 'vue'
import { AdminConnection } from '@/lib/connection'
import { http, HttpError, onUnauthorized } from '@/lib/http'
import { LiveGraph } from '@/lib/graph'
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
  /** Everything the engine said, newest last (capped). */
  output: [] as OutputEntry[],
  unseenErrors: 0,
  /** Events as they are recorded during the session (pushed by the server), newest last. */
  events: [] as LiveEvent[],
  /** The last tie edit's outcome, for the network page. */
  lastTieEdit: null as TieEditResult | null,
})

const OUTPUT_CAP = 500
const EVENTS_CAP = 1000
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
  if (graph.apply(m)) return

  if ('user' in m && m.user && typeof m.user === 'object') {
    applyUserJson(m)
  }
  if (m.notify) {
    if (m.notify.ScriptEngineReloading) {
      state.engine = 'loading'
      // the engine empties the network when it rebuilds
      graph.clear()
    }
    if (m.notify.ScriptEngineReloaded) {
      state.engine = 'loaded'
      state.engineReloadedAt = Date.now()
      setTimeout(() => { if (state.engine === 'loaded') state.engine = 'ready' }, 2500)
    }
  }
  if (typeof m.error === 'string' && m.error.trim()) {
    pushOutput('error', m.error.trim())
  }
  if (typeof m.output === 'string' && m.output.trim()) {
    pushOutput('output', m.output.trim())
  }
  if (m.tieEdit) {
    state.lastTieEdit = { ...m.tieEdit, at: Date.now() }
  }
  if (m.event && typeof m.event === 'object') {
    state.events.push(m.event)
    if (state.events.length > EVENTS_CAP) state.events.splice(0, state.events.length - EVENTS_CAP)
  }
}

function applyUserJson (m: any) {
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

onUnauthorized((body) => {
  conn.stop()
  state.auth = body && body.status === 'create-first-user' ? 'firstUser' : 'signedOut'
  state.ready = false
})

async function boot () {
  state.auth = 'checking'
  try {
    const cfg = await http.get<{ uid: string, email: string, connectSocket: string }>('/state', { quiet401: true })
    if (!cfg || !cfg.uid) throw new HttpError(401, 'no session')
    state.uid = cfg.uid
    state.email = cfg.email
    state.auth = 'signedIn'
    state.ready = false
    graph.clear()
    conn.start(cfg.uid, cfg.connectSocket)
  } catch (e: any) {
    if (e instanceof HttpError && e.status === 401) {
      state.auth = e.body && e.body.status === 'create-first-user' ? 'firstUser' : 'signedOut'
    } else {
      state.auth = 'unreachable'
    }
  }
}

async function login (email: string, password: string) {
  await http.post('/login', { email, password }, { quiet401: true })
  await boot()
}

async function logout () {
  conn.stop()
  try { await http.get('/logout', { quiet401: true }) } catch (e) { /* always 401 */ }
  state.user = null
  state.experiment = null
  state.instance = null
  state.ready = false
  state.output = []
  state.events = []
  graph.clear()
  state.auth = 'signedOut'
}

async function createFirstUser (email: string, password: string, defaultLanguageId: number) {
  await http.post('/createFirstUser', { email, password, defaultLanguageId }, { quiet401: true })
  await login(email, password)
}

// ---- actions ----------------------------------------------------------------------------------

function send (action: string, payload: Record<string, any> = {}) {
  conn.send(action, payload)
}

/** Wait for the server's next User JSON (most actions end with one). */
function nextUserJson (ms = 20000) {
  return nextMessage(m => 'user' in m && m.user && typeof m.user === 'object', ms)
}

const actions = {
  selectExperiment (id: number) {
    send('SelectExperiment', { experimentId: id })
    return nextUserJson()
  },
  /** Create and start a session of the selected experiment. */
  launch (name: string, parameters: Record<string, string | number | boolean>) {
    send('LaunchGame', { experimentId: state.experiment?.id, name, parameters })
    return nextMessage(m => 'user' in m && m.experimentInstance && m.experimentInstance.name === name, 60000)
  },
  /** Make a session the current one and (re)start it; rebuilds the engine. */
  selectInstance (id: number) {
    send('SelectInstance', { id })
    return nextUserJson(60000)
  },
  stopInstance (id: number) {
    send('StopGame', { id })
    return nextUserJson()
  },
  deleteInstance (id: number) {
    send('DeleteInstance', { id })
    return nextUserJson()
  },
  /** Run initStep.start(): the game begins and closes to newcomers. */
  startGame () {
    send('RunGame')
  },
  reloadEngine () {
    send('ReloadEngine')
  },
  runScript (script: string) {
    pushOutput('input', script, 'console')
    send('SendScript', { script })
  },
  runStep (step: { id: number | null, name: string, source: string }) {
    send('SendStep', { id: step.id ?? -1, name: step.name, source: step.source })
  },
  makeChoice (choiceUID: string) {
    send('MakeChoice', { choiceUID })
  },
  editTie (op: 'add' | 'remove', a: string, b: string) {
    send('EditTie', { op, a, b })
    return nextMessage(m => m.tieEdit && m.tieEdit.op === op && m.tieEdit.a === a && m.tieEdit.b === b, 15000)
      .then(m => (m ? m.tieEdit : { ok: false, op, a, b, reason: 'The server did not answer.' }) as TieEditResult)
  },
  deleteExperiment (exp: Experiment) {
    send('DeleteExperiment', { selectedExperiment: exp.name, experimentId: exp.id })
    return nextUserJson()
  },
  toggleFileMode () {
    send('ToggleFileMode')
    return nextUserJson()
  },
  /** Ask the server for the newest User JSON. */
  refresh () {
    send('Update')
    return nextUserJson()
  },
  /** An earlier session's recorded events. */
  async instanceEvents (id: number): Promise<{ name: string, events: any[] } | null> {
    send('ShowEvent', { id })
    const m = await nextMessage(x => 'events' in x && 'eventExperimentInstance' in x, 30000)
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

export const admin = state
export const connection = conn
export { graph, boot, login, logout, createFirstUser, actions, experiments, instances, running, nextMessage }

/** For tests: feed a message as if it came from the server. */
export function __receive (m: any) { handle(m) }
export const signedIn = computed(() => state.auth === 'signedIn')
export const connectionState = conn.state
export const hasExperiment = computed(() => !!state.experiment)
export const fileMode = computed(() => !!state.experiment?.fileMode)
