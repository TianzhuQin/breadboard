/** Shapes of what the server sends (see models/User.java, Experiment.java, ExperimentInstance.java). */

export type AuthState = 'checking' | 'signedOut' | 'firstUser' | 'signedIn' | 'unreachable'
export type EngineState = 'ready' | 'loading' | 'loaded' | 'stale'
export type InstanceStatus = 'RUNNING' | 'TESTING' | 'STOPPED' | 'FINISHED' | 'ARCHIVED'
export type ParameterType = 'Boolean' | 'Decimal' | 'Integer' | 'Text'

export interface Language { id: number, code: string, name: string }
export interface Step { id: number | null, name: string, source: string }
export interface Translation { id: number | null, html: string, language: Language }
export interface Content { id: number | null, name: string, translations: Translation[] }
export interface Parameter {
  id: number | null
  name: string
  type: ParameterType
  minVal: string
  maxVal: string
  defaultVal: string
  description: string
}
export interface Image { id: number | null, fileName: string }
export interface DataItem { id: number, name: string, value: string }
export interface EventItem { id: number, name: string, datetime: string, eventData: DataItem[] }

export interface Instance {
  id: number
  name: string
  status: InstanceStatus
  /** ISO-8601 UTC when the server sends creationTimeUtc (the store swaps it in), else server local time "yyyy-MM-dd HH:mm:ss,S" */
  creationTime: string
  /** initStep.start() has run, so the session is closed to newcomers (unless late joining is allowed) */
  hasStarted?: boolean
  hitId: number | ''
  hits: any[]
  data: DataItem[]
  /** only on the selected session */
  events?: EventItem[]
}

export interface Experiment {
  id: number
  name: string
  uid: string
  fileMode: boolean
  steps: Step[]
  languages: Language[]
  content: Content[]
  parameters: Parameter[]
  instances: Instance[]
  images: Image[]
  style: string
  clientGraphHash: number
  clientHtmlHash: number
}

/**
 * What the shared script engine runs (ScriptBoard.engineJson), which can be another experiment than
 * the selected one, or another account's session. Every field is null when the engine has none.
 */
export interface EngineInfo {
  /** The experiment whose steps the engine loaded */
  experimentId: number | null
  experimentName: string | null
  /** Its session, while that session is running or testing */
  sessionId: number | null
  sessionName: string | null
  /** The email of the account that loaded it */
  owner: string | null
  /**
   * Whether initStep.start() has run without error since the engine was last built: false after
   * every rebuild (a resumed session's game is not running, whatever its hasStarted says) and after a
   * start that threw. Null when the server does not say (older servers leave it out).
   */
  gameStarted?: boolean | null
}

export interface User {
  email: string
  defaultLanguage: Language | null
  experiments: Experiment[]
  selectedExperiment?: string
  currentScript: string
  /** What the engine runs (sent beside the user; the store puts it here). Null when the server does not say. */
  engine: EngineInfo | null
}

export interface OutputEntry {
  id: number
  kind: 'output' | 'error' | 'info' | 'input'
  text: string
  source?: string
  /** The id of the request it answers, when the request carried one and the server echoed it (SendScript, SendStep). */
  requestId?: string
  at: number
}

/** An event as the server records it during the session ({event: ...} push). */
export interface LiveEvent {
  id?: number
  /**
   * The session it was recorded in, or null when the engine runs none (every console gets every
   * session's events). Missing from older servers.
   */
  experimentInstanceId?: number | null
  experimentId?: number | null
  name: string
  /** ISO-8601 UTC */
  datetime: string
  data: Record<string, any>
  /** When this console received it (this computer's clock); the store sets it, the server does not send it. */
  receivedAt?: number
}

export interface TieEditResult {
  ok: boolean
  op: 'add' | 'remove'
  a: string
  b: string
  reason?: string
  at?: number
}
