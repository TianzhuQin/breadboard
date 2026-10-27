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

export interface User {
  email: string
  defaultLanguage: Language | null
  experiments: Experiment[]
  selectedExperiment?: string
  currentScript: string
}

export interface OutputEntry {
  id: number
  kind: 'output' | 'error' | 'info' | 'input'
  text: string
  source?: string
  at: number
}

/** An event as the server records it during the session ({event: ...} push). */
export interface LiveEvent {
  id?: number
  name: string
  /** ISO-8601 UTC */
  datetime: string
  data: Record<string, any>
}

export interface TieEditResult {
  ok: boolean
  op: 'add' | 'remove'
  a: string
  b: string
  reason?: string
  at?: number
}
