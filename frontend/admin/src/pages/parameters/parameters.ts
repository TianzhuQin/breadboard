import type { Parameter, ParameterType } from '@/store/types'

/**
 * Rules for experiment parameters (models/Parameter.java, ParametersController.java).
 *
 * A parameter is asked for when a session is created and bound in the script engine under its
 * name (ScriptBoard.initParam, ScriptBoardSupport.coerceParam): Integer → Integer.parseInt,
 * Decimal → Double.parseDouble, Boolean → Boolean.parseBoolean, Text as is. The server stores
 * every field as a string and checks nothing, so the checks live here.
 */

export interface TypeInfo { value: ParameterType, label: string, example: string }
export const PARAMETER_TYPES: TypeInfo[] = [
  { value: 'Integer', label: 'Whole number', example: 'e.g. 10 rounds' },
  { value: 'Decimal', label: 'Decimal number', example: 'e.g. 0.25 per point' },
  { value: 'Boolean', label: 'Yes or no', example: 'e.g. show the network' },
  { value: 'Text', label: 'Text', example: 'e.g. a condition name' },
]
export function typeLabel (type: string): string {
  return PARAMETER_TYPES.find(t => t.value === type)?.label || type || 'Unknown'
}
export const isNumericType = (t: string) => t === 'Integer' || t === 'Decimal'

export interface ParameterDraft {
  name: string
  type: ParameterType | ''
  minVal: string
  maxVal: string
  defaultVal: string
  description: string
}
export type DraftField = keyof ParameterDraft
export type DraftErrors = Partial<Record<DraftField, string>>

/** The body PUT /parameters/:experimentId expects: every value a string (read with textValue()). */
export interface ParameterPayload {
  name: string
  type: ParameterType
  minVal: string
  maxVal: string
  defaultVal: string
  description: string
}

/** Every column is varchar(255) (conf/evolutions/default/1.sql). */
export const MAX_LENGTH = 255
const INT_MIN = -2147483648
const INT_MAX = 2147483647

// Groovy and Java reserved words: a binding with one of these names can't be written in a script.
const KEYWORDS = new Set([
  'abstract', 'as', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
  'continue', 'def', 'default', 'do', 'double', 'else', 'enum', 'extends', 'false', 'final', 'finally',
  'float', 'for', 'goto', 'if', 'implements', 'import', 'in', 'instanceof', 'int', 'interface', 'long',
  'native', 'new', 'null', 'package', 'private', 'protected', 'public', 'return', 'short', 'static',
  'strictfp', 'super', 'switch', 'synchronized', 'this', 'threadsafe', 'throw', 'throws', 'trait',
  'transient', 'true', 'try', 'var', 'void', 'volatile', 'while', 'record', 'sealed', 'yield',
])

/**
 * Names the engine already uses (ScriptBoard bindings, groovy/*.groovy globals, the three
 * standard steps). Parameters are bound after all of these load, so one of these names would
 * replace, say, the network `g` for the whole session.
 */
export const ENGINE_NAMES: Record<string, string> = {
  g: 'the network',
  a: 'player actions',
  r: 'the random number generator',
  c: 'the content fetcher',
  d: 'shared data',
  results: 'script results',
  events: 'the event bus',
  eventTracker: 'the event log',
  gameListener: 'the game listener',
  stepFactory: 'the step factory',
  timers: 'timers',
  autopilot: 'the autopilot',
  edgeEdits: 'tie editing',
  recruitment: 'recruitment',
  RecruitmentSource: 'recruitment',
  log: 'logging',
  logPlayerEvents: 'logging',
  currency: 'currency formatting',
  randomString: 'a helper function',
  configureFrontend: 'a helper function',
  kickPlayers: 'a helper function',
  clearAllPlayerListeners: 'a helper function',
  initStep: 'the first step',
  onJoinStep: 'the join step',
  onLeaveStep: 'the leave step',
}

const IDENTIFIER = /^[A-Za-z_][A-Za-z0-9_]*$/
const INTEGER = /^[+-]?\d+$/
const DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/

/** Why a name can't be used in scripts, or null when it can. */
export function nameProblem (name: string): string | null {
  if (!name) return 'Give the parameter a name.'
  if (/\s/.test(name)) return 'Names can’t contain spaces. Try camelCase, e.g. nRounds.'
  if (/^\d/.test(name)) return 'Names can’t start with a digit.'
  if (!IDENTIFIER.test(name)) return 'Use only letters, digits and _ so scripts can use the name.'
  if (KEYWORDS.has(name)) return `“${name}” is a reserved word in scripts.`
  if (Object.prototype.hasOwnProperty.call(ENGINE_NAMES, name)) return `“${name}” is already used by Breadboard (${ENGINE_NAMES[name]}).`
  if (name.length > 100) return 'Keep names under 100 characters.'
  return null
}

/** A number as the engine will read it, or null when it can't. */
export function parseNumber (text: string, type: 'Integer' | 'Decimal'): number | null {
  const s = text.trim()
  if (type === 'Integer') {
    if (!INTEGER.test(s)) return null
    const n = Number(s)
    return n >= INT_MIN && n <= INT_MAX ? n : null
  }
  if (!DECIMAL.test(s)) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function numberProblem (text: string, type: 'Integer' | 'Decimal', what: string): string | null {
  const s = text.trim()
  if (!s) return `Enter the ${what}.`
  if (type === 'Integer') {
    if (DECIMAL.test(s) && !INTEGER.test(s)) return 'Use a whole number (no decimal point).'
    if (!INTEGER.test(s)) return 'Enter a whole number, e.g. 10.'
    if (parseNumber(s, 'Integer') === null) return 'That number is too large for a whole-number parameter.'
    return null
  }
  if (s.includes(',') && DECIMAL.test(s.replace(',', '.'))) return 'Use a point for decimals, e.g. 0.25.'
  if (parseNumber(s, 'Decimal') === null) return 'Enter a number, e.g. 0.25.'
  return null
}

const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase()

/**
 * Check a draft before it is sent. `existing` is the experiment's parameters; `editing` the one
 * being replaced (its own name doesn't count as taken).
 */
export function validateParameter (d: ParameterDraft, existing: Parameter[], editing?: Parameter | null): DraftErrors {
  const e: DraftErrors = {}
  const name = d.name.trim()
  const np = nameProblem(name)
  if (np) e.name = np
  else {
    const isSelf = (p: Parameter) => editing != null && (p === editing || (p.id != null && p.id === editing.id))
    const clash = existing.find(p => !isSelf(p) && sameName(p.name || '', name))
    if (clash) e.name = clash.name === name ? `There is already a parameter called “${name}”.` : `“${clash.name}” already exists; names that differ only in capitals are confusing in scripts.`
  }

  if (!d.type) e.type = 'Choose what kind of value this is.'

  if (d.type === 'Integer' || d.type === 'Decimal') {
    const t = d.type
    const minP = numberProblem(d.minVal, t, 'smallest allowed value')
    const maxP = numberProblem(d.maxVal, t, 'largest allowed value')
    const defP = numberProblem(d.defaultVal, t, 'default value')
    if (minP) e.minVal = minP
    if (maxP) e.maxVal = maxP
    if (defP) e.defaultVal = defP
    const min = minP ? null : parseNumber(d.minVal, t)
    const max = maxP ? null : parseNumber(d.maxVal, t)
    const def = defP ? null : parseNumber(d.defaultVal, t)
    if (min !== null && max !== null && min > max) e.maxVal = 'The largest value must not be smaller than the smallest.'
    if (def !== null) {
      if (min !== null && def < min) e.defaultVal = `The default must be at least ${min}.`
      else if (max !== null && def > max) e.defaultVal = `The default must be at most ${max}.`
    }
  } else if (d.type === 'Boolean') {
    if (d.defaultVal !== 'true' && d.defaultVal !== 'false') e.defaultVal = 'Choose true or false.'
  } else if (d.type === 'Text') {
    if (d.defaultVal.length > MAX_LENGTH) e.defaultVal = `Keep it under ${MAX_LENGTH} characters.`
  }

  if (d.description.length > MAX_LENGTH) e.description = `Keep it under ${MAX_LENGTH} characters (${d.description.length} now).`
  return e
}

export const hasErrors = (e: DraftErrors) => Object.keys(e).length > 0

/**
 * The request body. Numbers are written the way the engine parses them, min and max are blank for
 * Boolean and Text (as the server stores them), and a Boolean default is the string "true" or
 * "false": the old console sent the JSON boolean, which the server read as null.
 */
export function toPayload (d: ParameterDraft): ParameterPayload {
  if (!d.type) throw new Error('A parameter needs a type')
  const type = d.type
  const num = (s: string) => {
    const n = parseNumber(s, type as 'Integer' | 'Decimal')
    return n === null ? s.trim() : String(n)
  }
  const numeric = isNumericType(type)
  let defaultVal: string
  if (numeric) defaultVal = num(d.defaultVal)
  else if (type === 'Boolean') defaultVal = d.defaultVal === 'true' ? 'true' : 'false'
  else defaultVal = d.defaultVal
  return {
    name: d.name.trim(),
    type,
    minVal: numeric ? num(d.minVal) : '',
    maxVal: numeric ? num(d.maxVal) : '',
    defaultVal,
    description: d.description.trim(),
  }
}

export function emptyDraft (): ParameterDraft {
  return { name: '', type: '', minVal: '', maxVal: '', defaultVal: '', description: '' }
}

export function draftFrom (p: Parameter): ParameterDraft {
  const type = (PARAMETER_TYPES.some(t => t.value === p.type) ? p.type : '') as ParameterType | ''
  let defaultVal = p.defaultVal ?? ''
  if (type === 'Boolean') defaultVal = String(defaultVal).toLowerCase() === 'true' ? 'true' : 'false'
  return {
    name: p.name || '',
    type,
    minVal: p.minVal ?? '',
    maxVal: p.maxVal ?? '',
    defaultVal: String(defaultVal),
    description: p.description ?? '',
  }
}

/** Fields that make sense to keep when the type changes. */
export function changeType (d: ParameterDraft, type: ParameterType): ParameterDraft {
  const next = { ...d, type }
  if (type === d.type) return next
  const wasNumeric = isNumericType(d.type)
  if (!isNumericType(type)) { next.minVal = ''; next.maxVal = '' }
  if (type === 'Boolean') next.defaultVal = 'false'
  else if (d.type === 'Boolean') next.defaultVal = ''
  else if (isNumericType(type) && !wasNumeric) next.defaultVal = ''
  return next
}

/**
 * Problems with a parameter that already exists (the old console let all of these through), so
 * the table can point them out.
 */
export function parameterIssues (p: Parameter, all: Parameter[]): string[] {
  const out: string[] = []
  const np = nameProblem((p.name || '').trim())
  if (np) out.push(`Scripts can’t use this name. ${np}`)
  if (all.some(o => o !== p && o.name === p.name)) out.push('Another parameter has the same name; only one of them reaches the scripts.')
  if (!PARAMETER_TYPES.some(t => t.value === p.type)) out.push(`Unknown type “${p.type}”: the value reaches scripts as text.`)
  if (p.defaultVal == null) out.push('No default value (made by the old console). Edit it to set one.')
  else if (p.type === 'Integer' || p.type === 'Decimal') {
    const def = parseNumber(p.defaultVal, p.type)
    const min = parseNumber(p.minVal ?? '', p.type)
    const max = parseNumber(p.maxVal ?? '', p.type)
    if (def === null) out.push(`The default “${p.defaultVal}” is not a ${p.type === 'Integer' ? 'whole number' : 'number'}.`)
    else if ((min !== null && def < min) || (max !== null && def > max)) out.push('The default is outside the allowed range.')
    if (min !== null && max !== null && min > max) out.push('The smallest allowed value is larger than the largest.')
  }
  return out
}

/** How the default reads in the table. */
export function formatDefault (p: Pick<Parameter, 'type' | 'defaultVal'>): string {
  if (p.defaultVal == null) return ''
  if (p.type === 'Boolean') return String(p.defaultVal).toLowerCase() === 'true' ? 'true' : 'false'
  return p.defaultVal
}

/** The Groovy literal a script sees, for the "in scripts" preview. */
export function scriptLiteral (type: ParameterType | '', value: string): string {
  if (type === 'Text') return `'${value.replace(/\\/g, '\\\\').replace(/'/g, '\\\'')}'`
  if (type === 'Boolean') return value === 'true' ? 'true' : 'false'
  if (type === 'Integer' || type === 'Decimal') {
    const n = parseNumber(value, type)
    if (n === null) return '…'
    return type === 'Decimal' && Number.isInteger(n) ? n.toFixed(1) : String(n)
  }
  return '…'
}

/**
 * Steps whose source mentions the parameter as a variable (not as `x.name`), so a delete can say
 * what will break.
 */
export function findParameterUsage (name: string, steps: { name: string, source: string }[]): string[] {
  if (!name || !IDENTIFIER.test(name)) return []
  const re = new RegExp(`(?<![A-Za-z0-9_$.])${name}(?![A-Za-z0-9_$])`)
  return steps.filter(s => typeof s.source === 'string' && re.test(s.source)).map(s => s.name)
}
