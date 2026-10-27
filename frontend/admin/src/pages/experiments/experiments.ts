import { http, HttpError } from '@/lib/http'
import type { Experiment, Instance } from '@/store/types'

/** Pure helpers for the Experiments page (names, zip files, dates), kept here so they can be tested. */

export const NAME_MAX = 100

export function isLive (i: Pick<Instance, 'status'>) {
  return i.status === 'RUNNING' || i.status === 'TESTING'
}

/** The session of this experiment that is running right now, if any. */
export function runningSession (exp: Experiment): Instance | null {
  return (exp.instances || []).find(isLive) || null
}

/** The newest session (ids only grow, so the biggest id is the newest). */
export function lastSession (exp: Experiment): Instance | null {
  let best: Instance | null = null
  for (const i of exp.instances || []) if (!best || i.id > best.id) best = i
  return best
}

/**
 * Checks a name for a new or imported experiment. The server uses the name in folder names
 * (experiments/<name>_<id>_…, dev/<name>_<id>/) and some tools still find experiments by name,
 * so slashes and duplicates are refused here. Returns the problem in words, or null.
 */
export function validateExperimentName (raw: string, existing: string[] = []): string | null {
  const name = (raw || '').trim()
  if (!name) return 'Give the experiment a name.'
  if (name.length > NAME_MAX) return `Keep the name under ${NAME_MAX} characters.`
  if (/[/\\]/.test(name)) return 'Names can\'t contain / or \\ (the server uses the name for folder names).'
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f]/.test(name)) return 'Names can\'t contain line breaks or other control characters.'
  if (/^\.+$/.test(name)) return 'Use letters or numbers in the name.'
  const lower = name.toLowerCase()
  if (existing.some(n => (n || '').trim().toLowerCase() === lower)) {
    return `You already have an experiment called “${name}”. Pick another name, because some tools find experiments by name.`
  }
  return null
}

/** A suggested experiment name for an uploaded file: the file name without its folder and ".zip". */
export function importNameFromFile (fileName: string): string {
  const base = (fileName || '').split(/[/\\]/).pop() || ''
  return base.replace(/\.zip$/i, '').replace(/[/\\]/g, '-').trim()
}

/** The import route; the name is a path segment, so it must be encoded (the old console sent it raw). */
export function importUrl (name: string): string {
  return `/experiment/import/${encodeURIComponent(name.trim())}`
}

/** "<Name-with-dashes>_<id>.zip", as the old console named exports, without characters file systems refuse. */
export function zipFileName (exp: Pick<Experiment, 'id' | 'name'>): string {
  const base = (exp.name || '').trim().replace(/\s+/g, '-').replace(/[\\/:*?"<>|\u0000-\u001f]+/g, '')
  return `${base || 'experiment'}_${exp.id}.zip`
}

/** The folder the server uses in file mode: dev/<name with spaces as dashes>_<id> (Experiment.getDirectoryName). */
export function devDirName (exp: Pick<Experiment, 'id' | 'name'>): string {
  return `${(exp.name || '').replace(/ /g, '-')}_${exp.id}`
}

/**
 * The server writes session times in its own local time as "yyyy-MM-dd HH:mm:ss,S". Read the
 * parts as they are (no time-zone guessing), so the clock time shown is the server's.
 */
export function parseServerTime (s: string | null | undefined): Date | null {
  // ISO-8601 with a zone (the server's *Utc fields): the browser converts it to local time
  if (s && /T\d{2}:\d{2}.*(Z|[+-]\d{2}:?\d{2})$/.test(s)) {
    const t = Date.parse(s)
    return isNaN(t) ? null : new Date(t)
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/.exec(s || '')
  if (!m) return null
  const d = new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], m[6] ? +m[6] : 0)
  return isNaN(d.getTime()) ? null : d
}

/** "Today, 14:03", "Yesterday, 09:10", "12 Mar" or "12 Mar 2024". */
export function formatWhen (d: Date | null, now = new Date()): string {
  if (!d) return '—'
  const time = d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const days = Math.round((startOf(now) - startOf(d)) / 86400000)
  if (days === 0) return `Today, ${time}`
  if (days === 1) return `Yesterday, ${time}`
  const opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }
  if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric'
  return d.toLocaleDateString(undefined, opts)
}

export function plural (n: number, one: string, many = `${one}s`) {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * An error in words. Play's error pages are HTML, which is no use in a toast, so those become a
 * short sentence with the status code.
 */
export function describeError (e: unknown, fallback = 'Something went wrong.'): string {
  if (e instanceof HttpError) {
    const msg = (e.message || '').trim()
    if (e.status === 413) return 'The file is too large for the server.'
    if (!msg || /<\s*(!doctype|html|head|body|div|h1|p)\b/i.test(msg)) {
      return e.status >= 500
        ? `The server ran into an error (${e.status}). Its log has the details.`
        : `The server refused the request (${e.status}).`
    }
    return msg
  }
  if (e instanceof Error && e.message) {
    return /failed to fetch|networkerror|load failed/i.test(e.message)
      ? 'Couldn\'t reach the server. Check your connection and try again.'
      : e.message
  }
  return fallback
}

// ---- zip inspection ---------------------------------------------------------------------------
//
// The server unpacks an import only after it has created the experiment, so a zip it rejects
// leaves an empty experiment behind in its database. Reading the zip's table of contents first
// lets the page refuse files the server would refuse, with a clearer reason.

export interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  offset: number
}

export interface ZipCheck {
  /** False when the file can't go to the server as it is. */
  ok: boolean
  /** Why it can't (in words). */
  problem?: string
  /** Worth knowing, but the server may accept it. */
  warning?: string
  /** "" or "<folder>/" when everything sits in one folder. */
  root: string
  steps: number
  content: number
  images: number
  /** Where .breadboard is, to read the experiment's own name from it. */
  manifest?: ZipEntry
}

const SKIP = new Set(['__MACOSX', '__MAC_OSX'])

/** A file's bytes (FileReader where Blob.arrayBuffer is missing, e.g. older Safari). */
export function readBytes (file: Blob): Promise<Uint8Array> {
  if (typeof (file as any).arrayBuffer === 'function') return file.arrayBuffer().then(b => new Uint8Array(b))
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer))
    r.onerror = () => reject(r.error || new Error('The file could not be read.'))
    r.readAsArrayBuffer(file)
  })
}

/** The entries listed in a zip's central directory, or null when it can't be read that way. */
export function readZipEntries (bytes: Uint8Array): ZipEntry[] | null {
  if (bytes.length < 22) return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  // the end-of-central-directory record sits in the last 22 + 65535 (comment) bytes
  let eocd = -1
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) return null
  const count = view.getUint16(eocd + 10, true)
  const size = view.getUint32(eocd + 12, true)
  const start = view.getUint32(eocd + 16, true)
  if (start === 0xffffffff || count === 0xffff) return null // zip64: not worth reading by hand
  if (start + size > bytes.length) return null
  const decoder = new TextDecoder()
  const entries: ZipEntry[] = []
  let p = start
  for (let n = 0; n < count; n++) {
    if (p + 46 > bytes.length || view.getUint32(p, true) !== 0x02014b50) return null
    const method = view.getUint16(p + 10, true)
    const compressedSize = view.getUint32(p + 20, true)
    const nameLen = view.getUint16(p + 28, true)
    const extraLen = view.getUint16(p + 30, true)
    const commentLen = view.getUint16(p + 32, true)
    const offset = view.getUint32(p + 42, true)
    const name = decoder.decode(bytes.subarray(p + 46, p + 46 + nameLen)).replace(/\\/g, '/')
    entries.push({ name, method, compressedSize, offset })
    p += 46 + nameLen + extraLen + commentLen
  }
  return entries
}

/** Folder names directly inside `prefix` ("" = top level), from the entries' paths. */
function foldersIn (names: string[], prefix: string): Set<string> {
  const out = new Set<string>()
  for (const n of names) {
    if (!n.startsWith(prefix)) continue
    const rest = n.slice(prefix.length).split('/')
    if (rest.length > 1 && rest[0]) out.add(rest[0])
  }
  return out
}

/**
 * Checks a zip the way ExperimentController.importExperiment does: after dropping __MACOSX, the top
 * level must hold a Steps or a Content folder; failing that, its only entry must be a folder that
 * does. (An export of an experiment without content items has no Content folder.)
 */
export function checkExperimentZip (bytes: Uint8Array): ZipCheck {
  const none: ZipCheck = { ok: false, root: '', steps: 0, content: 0, images: 0 }
  const isZip = bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && (bytes[2] === 3 || bytes[2] === 5)
  if (!isZip) {
    return { ...none, problem: 'This file isn\'t a zip archive. Export the experiment from Breadboard (it downloads a .zip) and import that file.' }
  }
  const entries = readZipEntries(bytes)
  if (!entries) {
    // Can't read it here (e.g. a very large zip64 archive); let the server decide.
    return { ok: true, root: '', steps: 0, content: 0, images: 0, warning: 'This zip couldn\'t be checked before uploading. The server will check it.' }
  }
  const kept = entries.filter(e => !SKIP.has(e.name.split('/')[0]))
  const names = kept.map(e => e.name)
  if (!names.length) return { ...none, problem: 'This zip file is empty.' }

  const top = new Set(names.map(n => n.split('/')[0]).filter(Boolean))
  const only = top.size === 1 ? [...top][0] : null
  const topFolders = foldersIn(names, '')
  const inner = only != null ? foldersIn(names, `${only}/`) : new Set<string>()
  const exact = (folders: Set<string>) => folders.has('Steps') || folders.has('Content')
  // a server on a case-insensitive disk (macOS) finds these too; one on Linux does not
  const loose = (folders: Set<string>) => [...folders].some(f => /^(steps|content)$/i.test(f))
  const caseWarning = 'Its folders are not named exactly “Steps” and “Content”. Some servers will not find them.'
  let root = ''
  let warning: string | undefined
  if (exact(topFolders)) {
    root = ''
  } else if (only != null && exact(inner)) {
    root = `${only}/`
  } else if (loose(topFolders)) {
    warning = caseWarning
  } else if (only != null && loose(inner)) {
    root = `${only}/`
    warning = caseWarning
  } else {
    return { ...none, problem: 'This zip doesn\'t look like a Breadboard experiment: it needs a Steps or Content folder.' }
  }
  const under = (dir: string, ext: RegExp) => kept.filter(e => {
    const lower = e.name.toLowerCase()
    return lower.startsWith(`${root}${dir}/`.toLowerCase()) && ext.test(lower)
  }).length
  const manifest = kept.find(e => e.name === `${root}.breadboard`)
  return {
    ok: true,
    warning,
    root,
    steps: under('steps', /\.groovy$/),
    content: under('content', /\.html?$/),
    images: under('images', /[^/]$/),
    manifest,
  }
}

/** The text of one (small) entry: stored as is, or deflated where the browser can inflate. */
export async function readZipText (bytes: Uint8Array, entry: ZipEntry): Promise<string | null> {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const p = entry.offset
  if (p + 30 > bytes.length || view.getUint32(p, true) !== 0x04034b50) return null
  const start = p + 30 + view.getUint16(p + 26, true) + view.getUint16(p + 28, true)
  const data = bytes.subarray(start, start + entry.compressedSize)
  if (entry.method === 0) return new TextDecoder().decode(data)
  if (entry.method !== 8 || typeof DecompressionStream === 'undefined') return null
  try {
    const stream = new Blob([data as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
    return await new Response(stream).text()
  } catch (e) {
    return null
  }
}

/** The experiment's name as recorded in the zip's .breadboard file, if it has one. */
export async function manifestName (bytes: Uint8Array, check: ZipCheck): Promise<string | null> {
  if (!check.manifest) return null
  const text = await readZipText(bytes, check.manifest)
  if (!text) return null
  try {
    const name = JSON.parse(text)?.experimentName
    return typeof name === 'string' && name.trim() ? name.trim() : null
  } catch (e) {
    return null
  }
}

// ---- export -----------------------------------------------------------------------------------

/**
 * Downloads the experiment as a zip. Fetched as a blob (not a plain link) so a refusal can be shown
 * in words; the old console failed silently.
 */
export async function downloadExperiment (exp: Pick<Experiment, 'id' | 'name'>): Promise<void> {
  const res = await fetch(`/experiment/export/${exp.id}`, { credentials: 'same-origin' })
  if (res.status === 401) {
    // let the app notice the session is gone and show the sign-in page
    http.get('/state').catch(() => {})
    throw new HttpError(401, 'Your session has ended. Sign in again to export.')
  }
  if (!res.ok) {
    const text = (await res.text().catch(() => '')).trim()
    throw new HttpError(res.status, text.slice(0, 300) || res.statusText)
  }
  const blob = await res.blob()
  if (!blob.size) throw new Error('The server sent an empty file.')
  const url = URL.createObjectURL(new Blob([blob], { type: 'application/zip' }))
  const a = document.createElement('a')
  a.href = url
  a.download = zipFileName(exp)
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}
