/**
 * Reads the engine's error text (ScriptBoardSupport.describeError) into something a researcher can
 * act on: what went wrong in plain words, and where.
 *
 * The server sends e.g.
 *   Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: foo for class: Script12
 *     at Script12.groovy:3
 *     at graph.groovy:120
 * Breadboard's own files keep their names (graph.groovy …); a script typed in the console or a
 * step is compiled as "Script<N>.groovy", numbered in the order the engine compiled them. So the
 * highest number is the script that was just run.
 *
 * Not so for an error in a step's own code, which the server names ("step"): the steps were compiled
 * as they loaded, in name order, and what started the step during the game can be newer: RoundStep,
 * whose finishStep.start() runs FinishStep, an OnJoinStep that starts InitStep, and the scripts the
 * server runs to start the game (initStep.start(), for the waiting room or Start) or the OnJoinStep
 * for a newcomer (onJoinStep.start("p1")), newer than every step. The step's own place is the
 * innermost script of the trace, the first one listed (ParseOptions.inStep).
 *
 * Neither is right once a step's code calls a function another step defined (the network game's
 * RoundStep calls InitStep's isHuman): the innermost line is then InitStep's. Newer servers say which
 * step's code each line is ("frames": the server records the class each step's code compiles to),
 * and which line is the failing step's own: the innermost one in its code. With them, every line is
 * named by its step, and where it went wrong in another step's code is said too ("Line 14 of
 * RoundStep, in isHuman (InitStep line 30)"). Without them (an older message), the guesses above.
 */

import type { ErrorFrame } from '@/store/types'

/**
 * own: the script that was run, or the step whose code failed; in: where it went wrong in another step's
 * code that the failing step's own line called (a function of that step's); library: one of Breadboard's
 * files; other: an earlier script or a step (a step the server names, newer servers).
 */
export type PlaceKind = 'own' | 'in' | 'library' | 'other'
export interface ErrorPlace {
  file: string
  line: number
  column?: number
  kind: PlaceKind
  /** The step whose code it is ("RoundStep"), as the server names it (ErrorFrame). */
  step?: string
  /** The failing step's own line (the server's), when the server names it. */
  primary?: boolean
  /** For `in`: the function of that step the failing step called there (e.g. isHuman), when the server knows it. */
  name?: string
}

export type { ErrorFrame }

export interface ParseOptions {
  /** The error happened in a step's own code, which the server named: its place is the innermost script, not the newest (see above). */
  inStep?: boolean
  /** The server's names for the lines (newer servers): see above. */
  frames?: ErrorFrame[] | null
}
export interface GroovyError {
  /** The Java/Groovy exception name, e.g. MissingPropertyException ('' when there is none). */
  type: string
  /** The message without exception names. */
  message: string
  /** What happened, in plain words. */
  title: string
  /** What to try, or null. */
  hint: string | null
  places: ErrorPlace[]
  raw: string
}

const TYPE_PREFIX = /^(?:[a-z_$][\w$]*\.)*([A-Z][\w$]*(?:Exception|Error)):\s*/
const TYPE_ONLY = /^(?:[a-z_$][\w$]*\.)*([A-Z][\w$]*(?:Exception|Error))\s*$/
// (a closure's frame can have no line: "at step.groovy:-1")
const FRAME = /^\s*at\s+(\S+\.groovy):(-?\d+)\s*$/
const COMPILE_AT = /(\S+?\.groovy): (\d+): (.+?)(?: @ line (\d+), column (\d+)\.)?\s*$/m
const SCRIPT_NAME = /^Script(\d+)(?:\.groovy)?$/

function scriptNumber (file: string): number | null {
  const m = SCRIPT_NAME.exec(file)
  return m ? Number(m[1]) : null
}

export function parseGroovyError (raw: string, opts: ParseOptions = {}): GroovyError {
  const text = String(raw || '').replace(/^\s*Caught error:\s*/, '').trim()
  const lines = text.split('\n')
  const frames: { file: string, line: number }[] = []
  const body: string[] = []
  for (const l of lines) {
    const f = FRAME.exec(l)
    // (a frame without a line says nothing that can be opened: it is left to the full error)
    if (f) { if (Number(f[2]) > 0) frames.push({ file: f[1], line: Number(f[2]) }) } else body.push(l)
  }

  // Unwrap "ScriptException: groovy.lang.MissingPropertyException: …" down to the innermost type.
  let message = body.join('\n').trim()
  let type = ''
  for (let guard = 0; guard < 6; guard++) {
    const only = TYPE_ONLY.exec(message)
    if (only) { type = only[1]; message = ''; break }
    const m = TYPE_PREFIX.exec(message)
    if (!m) break
    type = m[1]
    message = message.slice(m[0].length)
  }

  const places: { file: string, line: number, column?: number }[] = []
  let compileMessage: string | null = null
  const compile = /startup failed|CompilationFailed|CompilationErrors/i.test(type + ' ' + message) ? COMPILE_AT.exec(message) : null
  if (compile) {
    compileMessage = compile[3].trim()
    const line = compile[4] ? Number(compile[4]) : Number(compile[2])
    places.push({ file: compile[1], line, column: compile[5] ? Number(compile[5]) : undefined })
  }
  // A SyntaxException's brief message, as the autopilot's pause keeps it, has no exception type
  // or "startup failed" prefix. Its own location still identifies a compiler failure.
  const briefCompile = /^(.*?)\s+at line:\s*(\d+)\s+column:\s*(\d+)\.\s+File:\s*(Script\d+\.groovy)\s*$/s.exec(message)
  if (!compile && briefCompile) {
    compileMessage = briefCompile[1].trim()
    places.push({ file: briefCompile[4], line: Number(briefCompile[2]), column: Number(briefCompile[3]) })
  }
  for (const f of frames) {
    if (!places.some(p => p.file === f.file && p.line === f.line)) places.push(f)
  }

  // The server's names (see above): the step each script is the code of, the failing step's own line,
  // and where it went wrong in another step's code, the innermost line, when that isn't the own line.
  const named = opts.frames && opts.frames.length ? opts.frames : null
  const stepOf = new Map<string, string>()
  for (const f of named || []) if (f.step) stepOf.set(f.file, f.step)
  const serverOwn = named?.find(f => f.own) ?? null
  const serverIn = serverOwn && named![0] !== serverOwn && named![0].step ? named![0] : null
  for (const f of serverOwn ? [serverOwn] : []) {
    // (a line only the server names, e.g. a syntax error's in a step that didn't compile)
    if (!places.some(p => p.file === f.file && p.line === f.line)) places.unshift({ file: f.file, line: f.line })
  }

  // The script the error is about (see above): for a step's own code, the server's own line when it
  // names one, else the innermost script, the first listed; else the newest Script<N>, the script that
  // was run.
  let own: number | null
  if (opts.inStep && serverOwn) {
    own = scriptNumber(serverOwn.file)
  } else if (opts.inStep) {
    own = places.map(p => scriptNumber(p.file)).find((n): n is number => n !== null) ?? null
  } else {
    const mentioned = [...places.map(p => p.file), ...(message.match(/\bScript\d+\b/g) || [])]
    const numbers = mentioned.map(scriptNumber).filter((n): n is number => n !== null)
    own = numbers.length ? Math.max(...numbers) : null
  }
  // the code the error is about: that script's, or all of the failing step's code the server names
  const ownStep = serverOwn && own === scriptNumber(serverOwn.file) ? serverOwn.step ?? null : null
  const kinded: ErrorPlace[] = places.map(p => {
    const n = scriptNumber(p.file)
    const step = stepOf.get(p.file)
    const place: ErrorPlace = { ...p, kind: n === null ? 'library' : n === own || (!!ownStep && step === ownStep) ? 'own' : 'other' }
    if (step) place.step = step
    if (serverOwn && ownStep !== null && p.file === serverOwn.file && p.line === serverOwn.line) place.primary = true
    // where it went wrong, in another step's code the own line called
    if (serverIn && own === scriptNumber(serverOwn!.file) && p.file === serverIn.file && p.line === serverIn.line && place.kind === 'other') {
      place.kind = 'in'
      if (serverIn.name) place.name = serverIn.name
    }
    return place
  })
  // the run's own line first (the failing step's, then where it went wrong in another step's code), then
  // Breadboard's files, then the rest
  const order: Record<PlaceKind, number> = { own: 2, in: 1, library: 3, other: 4 }
  const rank = (p: ErrorPlace) => p.primary ? 0 : order[p.kind]
  kinded.sort((a, b) => rank(a) - rank(b))

  const { title, hint, cleaned } = explain(type, message, compileMessage)
  return { type, message: cleaned, title, hint, places: kinded, raw: String(raw || '').trim() }
}

function explain (type: string, message: string, compileMessage: string | null): { title: string, hint: string | null, cleaned: string } {
  const msg = message.trim().replace(/\s+for class: Script\d+(?:\$[\w$]+)?(?![\w$])/g, '')
  if (/initStep started already/i.test(msg)) {
    return {
      title: 'The game has already started',
      hint: 'initStep.start() runs once per engine load. To run it again, reload the engine (which forgets the start) or start a new session.',
      cleaned: msg,
    }
  }
  if (compileMessage !== null || /^(?:unexpected (?:token|char(?:acter)?):|expecting\s|unable to resolve class\s|Constructor call must be the first statement)/i.test(msg)) {
    return {
      title: 'Syntax error',
      hint: 'Groovy could not read the script. Check the brackets, quotes and commas around the line shown.',
      cleaned: compileMessage ?? msg,
    }
  }
  // (the server's brief words for an error leave the type out: "No such property: five")
  if (type === 'MissingPropertyException' || (!type && /^No such property: /.test(msg))) {
    const name = /No such property: (\S+)/.exec(msg)?.[1]
    return {
      title: name ? `Unknown name “${name}”` : 'Unknown name',
      hint: 'Groovy doesn\'t know this name. Check its spelling and capitals, or define it first. Names defined in steps exist only once the steps have run.',
      cleaned: msg,
    }
  }
  if (type === 'MissingMethodException' || (!type && /^No signature of method: /.test(msg))) {
    const name = /No signature of method: (?:[\w.$]+\.)?([\w$]+)\(\)/.exec(msg)?.[1]
    return {
      title: name ? `“${name}” can't be used like this` : 'No such function',
      hint: 'There is no function with this name that takes these values. Check the name, and the number and kind of values you pass.',
      cleaned: msg,
    }
  }
  if (type === 'NullPointerException' || /on null object/.test(msg)) {
    return {
      title: 'Something was empty (null)',
      hint: 'Often a player id that does not exist: g.getVertex("id") gives null for an unknown id.',
      cleaned: msg,
    }
  }
  if (type === 'IllegalStateException' || type === 'IllegalArgumentException') {
    // Breadboard's own refusals are already in plain words ("They are already connected.")
    return { title: msg || 'Not possible right now', hint: null, cleaned: msg }
  }
  return { title: msg ? firstLine(msg) : (type ? humanType(type) : 'The script failed'), hint: null, cleaned: msg }
}

function firstLine (s: string) {
  const l = s.split('\n')[0].trim()
  return l.length > 140 ? l.slice(0, 137) + '…' : l
}

/** "NumberFormatException" → "Number format exception". */
export function humanType (type: string) {
  const words = type.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * A short label for where an error happened: "Line 14", "in isHuman (InitStep line 30)", "RoundStep
 * line 80" for a line of a step the server names, else the file and line.
 */
export function placeLabel (p: ErrorPlace) {
  if (p.kind === 'own') return p.column ? `Line ${p.line}, column ${p.column}` : `Line ${p.line}`
  if (p.kind === 'in') return stepLineLabel({ step: p.step ?? p.file, line: p.line, name: p.name ?? null })
  if (p.kind === 'other' && p.step) return `${p.step} line ${p.line}`
  return `${p.file}, line ${p.line}`
}

/**
 * Where the server's brief words for a step's failure say it happened (ScriptBoardSupport.briefError, in
 * the data's stepFailed and the autopilot's pause): "line 71: …", the failing step's own line; "line 14,
 * in isHuman (InitStep line 30): …" (or "line 14, in InitStep line 30: …") when it went wrong in another
 * step's code that line called; "line 30 of InitStep: …" when the failing step's own line isn't known.
 */
export interface BriefPlace {
  /** The failing step's own line; null when the words name none. */
  line: number | null
  /** Where it went wrong in another step's code (the function it called there, when known). */
  in: StepLine | null
  /** What went wrong. */
  message: string
}
export interface StepLine { step: string, line: number, name: string | null }

const BRIEF = /^line (\d+)(?: of ([^,:\n]+?))?(?:, in ([^:\n]+?))?: ([\s\S]*)$/
const BRIEF_IN = /^(?:([\w$]+) \((.+) line (\d+)\)|(.+) line (\d+))$/

/** "isHuman (InitStep line 30)" or "InitStep line 30", as briefError says where it went wrong; null for anything else. */
export function readStepLine (text: string): StepLine | null {
  const m = BRIEF_IN.exec(text.trim())
  if (!m) return null
  return m[1] ? { step: m[2].trim(), line: Number(m[3]), name: m[1] } : { step: m[4].trim(), line: Number(m[5]), name: null }
}

/** The brief words read (see BriefPlace); no line and the words as they are when they name none. */
export function readBrief (text: string): BriefPlace {
  const m = BRIEF.exec(text || '')
  if (!m) return { line: null, in: null, message: text || '' }
  // "line 30 of InitStep": no line of the failing step's, the line where it went wrong
  if (m[2]) return { line: null, in: { step: m[2].trim(), line: Number(m[1]), name: null }, message: m[4] }
  const where = m[3] ? readStepLine(m[3]) : null
  return { line: Number(m[1]), in: where, message: m[3] && !where ? `in ${m[3]}: ${m[4]}` : m[4] }
}

/** "in isHuman (InitStep line 30)", "in InitStep line 30": where it went wrong in another step's code. */
export function stepLineLabel (p: StepLine) {
  return p.name ? `in ${p.name} (${p.step} line ${p.line})` : `in ${p.step} line ${p.line}`
}

/** "in isHuman at InitStep line 30": the same inside a sentence already in brackets (the events lists). */
export function stepLineAt (p: StepLine) {
  return `in ${p.name ? `${p.name} at ` : ''}${p.step} line ${p.line}`
}

/**
 * Where a failure event's data says it happened: stepFailed's error ("line 14, in isHuman (InitStep line
 * 30): …"), or sessionFailed's line and error ("in isHuman (InitStep line 30): …").
 */
export function failureWhere (data: Record<string, unknown>): { line: number | null, in: StepLine | null } {
  const error = String(data.error ?? '')
  const n = Number(data.line)
  if (Number.isInteger(n) && n > 0) {
    const m = /^in ([^:\n]+?): /.exec(error)
    return { line: n, in: m ? readStepLine(m[1]) : null }
  }
  const brief = readBrief(error)
  return { line: brief.line, in: brief.in }
}
