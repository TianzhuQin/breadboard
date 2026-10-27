import type { Parameter, ParameterType } from '@/store/types'

/**
 * Launch parameters for a new session: turning the experiment's parameter definitions into form
 * fields, checking what the researcher typed, and building the LaunchGame payload.
 *
 * The server stores each value with `value.toString()` and binds it in the engine by type
 * (ScriptBoard.initAllParam / ScriptBoardSupport.coerceParam). The old console could send `null`
 * (NaN from an empty default, or a cleared number field), which made the launch fail silently
 * after the engine had already been reset (INVENTORY §3.3.1). Everything built here is a finite
 * number, a boolean or a string, or the form refuses to submit.
 */

export type ParamValue = string | number | boolean
/** What a field holds while editing: a switch's boolean, or the text typed into a box. */
export type FieldValue = string | boolean

/** Integer parameters are bound with Integer.parseInt on the server (Java int). */
export const INT_MIN = -2147483648
export const INT_MAX = 2147483647
/** data.value and experiment_instances.name are varchar(255). */
export const MAX_TEXT = 255

export interface ParamField {
  name: string
  type: ParameterType
  description: string
  min: number | null
  max: number | null
  /** The default as the experiment stores it (a string, possibly empty or invalid). */
  defaultRaw: string
  /** The form's starting value. */
  initial: FieldValue
  /** Why the stored default can't be used as is (shown right away), or null. */
  defaultProblem: string | null
}

const TYPES: ParameterType[] = ['Integer', 'Decimal', 'Boolean', 'Text']

/** The server only knows these four spellings; anything else is treated as text. */
export function normalizeType (t: unknown): ParameterType {
  const s = String(t ?? '').trim().toLowerCase()
  return TYPES.find(x => x.toLowerCase() === s) || 'Text'
}

export const TYPE_LABEL: Record<ParameterType, string> = {
  Integer: 'Whole number',
  Decimal: 'Number',
  Boolean: 'Yes / no',
  Text: 'Text',
}

/** A min/max bound from the parameter definition; blank or unreadable means no bound. */
export function parseBound (raw: unknown): number | null {
  if (raw == null) return null
  const s = String(raw).trim()
  if (!s) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

const INT_RE = /^[+-]?\d+$/
const DEC_RE = /^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$/

function fmt (n: number) {
  return String(n)
}

/** "1 to 10", "1 or more", "up to 10", or '' when unbounded. */
export function rangeText (min: number | null, max: number | null): string {
  if (min != null && max != null) return `${fmt(min)} to ${fmt(max)}`
  if (min != null) return `${fmt(min)} or more`
  if (max != null) return `Up to ${fmt(max)}`
  return ''
}

function rangeError (n: number, min: number | null, max: number | null): string | null {
  if ((min != null && n < min) || (max != null && n > max)) {
    if (min != null && max != null) return `Must be between ${fmt(min)} and ${fmt(max)}.`
    if (min != null) return `Must be at least ${fmt(min)}.`
    return `Must be at most ${fmt(max!)}.`
  }
  return null
}

export interface ParamCheck { value?: ParamValue, error?: string }

/** Check one field. Returns the value to send, or an error in plain words. */
export function checkParam (field: Pick<ParamField, 'type' | 'min' | 'max'>, input: FieldValue | null | undefined): ParamCheck {
  if (field.type === 'Boolean') {
    if (typeof input === 'boolean') return { value: input }
    return { value: String(input ?? '').trim().toLowerCase() === 'true' }
  }
  const s = typeof input === 'string' ? input : input == null ? '' : String(input)
  if (field.type === 'Text') {
    if (s.length > MAX_TEXT) return { error: `Keep it to ${MAX_TEXT} characters or fewer (now ${s.length}).` }
    return { value: s }
  }

  const t = s.trim()
  const whole = field.type === 'Integer'
  if (!t) return { error: whole ? 'Enter a whole number.' : 'Enter a number.' }
  if (t.includes(',')) {
    return { error: whole ? 'Use digits only, without commas.' : 'Use a dot for decimals, e.g. 0.5 (no commas).' }
  }
  if (whole) {
    if (!INT_RE.test(t)) {
      return { error: DEC_RE.test(t) ? 'Must be a whole number (no decimals).' : 'Enter a whole number, e.g. 10.' }
    }
    const n = Number(t)
    if (!Number.isSafeInteger(n) || n < INT_MIN || n > INT_MAX) {
      return { error: `Too large: whole-number parameters go from ${INT_MIN} to ${INT_MAX}.` }
    }
    const r = rangeError(n, field.min, field.max)
    return r ? { error: r } : { value: n }
  }
  if (!DEC_RE.test(t)) return { error: 'Enter a number, e.g. 0.5.' }
  const n = Number(t)
  if (!Number.isFinite(n)) return { error: 'This number is too large.' }
  const r = rangeError(n, field.min, field.max)
  return r ? { error: r } : { value: n }
}

/** Form fields for an experiment's parameters, pre-filled with their defaults. */
export function buildFields (params: Parameter[] | null | undefined): ParamField[] {
  return (params || []).filter(p => p && p.name).map((p) => {
    const type = normalizeType(p.type)
    const defaultRaw = p.defaultVal == null ? '' : String(p.defaultVal)
    const min = type === 'Integer' || type === 'Decimal' ? parseBound(p.minVal) : null
    const max = type === 'Integer' || type === 'Decimal' ? parseBound(p.maxVal) : null
    let initial: FieldValue = defaultRaw
    let defaultProblem: string | null = null
    if (type === 'Boolean') {
      // Boolean.parseBoolean on the server; a default saved as null (old bug §3.3.3) means false
      initial = defaultRaw.trim().toLowerCase() === 'true'
    } else if (type === 'Integer' || type === 'Decimal') {
      initial = defaultRaw.trim()
      if (!initial) {
        defaultProblem = 'This parameter has no default. Enter a value.'
      } else {
        const c = checkParam({ type, min, max }, initial)
        if (c.error) defaultProblem = `The saved default “${initial}” can't be used. ${c.error}`
      }
    }
    return { name: p.name, type, description: p.description || '', min, max, defaultRaw, initial, defaultProblem }
  })
}

/** Every field's error, by parameter name (only fields with a problem). */
export function fieldErrors (fields: ParamField[], values: Record<string, FieldValue>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const f of fields) {
    const c = checkParam(f, values[f.name])
    if (c.error) out[f.name] = c.error
  }
  return out
}

/** The LaunchGame `parameters` object, or the errors that stop it from being sent. */
export function buildLaunchParams (fields: ParamField[], values: Record<string, FieldValue>):
  { ok: true, params: Record<string, ParamValue> } | { ok: false, errors: Record<string, string> } {
  const params: Record<string, ParamValue> = {}
  const errors: Record<string, string> = {}
  for (const f of fields) {
    const c = checkParam(f, values[f.name])
    if (c.error || c.value === undefined) errors[f.name] = c.error || 'Enter a value.'
    else params[f.name] = c.value
  }
  return Object.keys(errors).length ? { ok: false, errors } : { ok: true, params }
}

/** Plain-words problem with a session name, or null. */
export function checkSessionName (name: string, existing: string[]): string | null {
  const t = name.trim()
  if (!t) return 'Give the session a name.'
  if (t.length > MAX_TEXT) return `Keep the name to ${MAX_TEXT} characters or fewer.`
  const lower = t.toLowerCase()
  if (existing.some(n => (n || '').trim().toLowerCase() === lower)) {
    return 'Another session of this experiment already has this name. Pick another, so you can tell them apart in your data.'
  }
  return null
}

/** "Session N", the first such name not taken yet. */
export function suggestName (existing: string[]): string {
  const taken = new Set(existing.map(n => (n || '').trim().toLowerCase()))
  let n = existing.length + 1
  while (taken.has(`session ${n}`)) n++
  return `Session ${n}`
}
