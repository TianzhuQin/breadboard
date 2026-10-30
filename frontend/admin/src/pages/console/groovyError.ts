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
 */

export type PlaceKind = 'own' | 'library' | 'other'
export interface ErrorPlace {
  file: string
  line: number
  column?: number
  /** own: the script that was run; library: one of Breadboard's files; other: an earlier script or a step. */
  kind: PlaceKind
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
const FRAME = /^\s*at\s+(\S+\.groovy):(\d+)\s*$/
const COMPILE_AT = /(\S+?\.groovy): (\d+): (.+?)(?: @ line (\d+), column (\d+)\.)?\s*$/m
const SCRIPT_NAME = /^Script(\d+)(?:\.groovy)?$/

function scriptNumber (file: string): number | null {
  const m = SCRIPT_NAME.exec(file)
  return m ? Number(m[1]) : null
}

export function parseGroovyError (raw: string): GroovyError {
  const text = String(raw || '').replace(/^\s*Caught error:\s*/, '').trim()
  const lines = text.split('\n')
  const frames: { file: string, line: number }[] = []
  const body: string[] = []
  for (const l of lines) {
    const f = FRAME.exec(l)
    if (f) frames.push({ file: f[1], line: Number(f[2]) })
    else body.push(l)
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
  for (const f of frames) {
    if (!places.some(p => p.file === f.file && p.line === f.line)) places.push(f)
  }

  // The newest Script<N> is the script that was run (see above).
  const mentioned = [...places.map(p => p.file), ...(message.match(/\bScript\d+\b/g) || [])]
  const numbers = mentioned.map(scriptNumber).filter((n): n is number => n !== null)
  const newest = numbers.length ? Math.max(...numbers) : null
  const kinded: ErrorPlace[] = places.map(p => {
    const n = scriptNumber(p.file)
    return { ...p, kind: n === null ? 'library' : n === newest ? 'own' : 'other' }
  })
  // the run's own line first, then Breadboard's files, then the rest
  const order: Record<PlaceKind, number> = { own: 0, library: 1, other: 2 }
  kinded.sort((a, b) => order[a.kind] - order[b.kind])

  const { title, hint, cleaned } = explain(type, message, compileMessage)
  return { type, message: cleaned, title, hint, places: kinded, raw: String(raw || '').trim() }
}

function explain (type: string, message: string, compileMessage: string | null): { title: string, hint: string | null, cleaned: string } {
  const msg = message.trim()
  if (/initStep started already/i.test(msg)) {
    return {
      title: 'The game has already started',
      hint: 'initStep.start() runs once per engine load. To run it again, reload the engine (which forgets the start) or start a new session.',
      cleaned: msg,
    }
  }
  if (compileMessage !== null) {
    return {
      title: 'Syntax error',
      hint: 'Groovy could not read the script. Check the brackets, quotes and commas around the line shown.',
      cleaned: compileMessage,
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

/** A short label for where an error happened. */
export function placeLabel (p: ErrorPlace) {
  if (p.kind === 'own') return p.column ? `Line ${p.line}, column ${p.column}` : `Line ${p.line}`
  if (p.kind === 'library') return `${p.file}, line ${p.line}`
  return `${p.file}, line ${p.line}`
}
