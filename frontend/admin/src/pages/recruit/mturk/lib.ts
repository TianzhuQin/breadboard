/**
 * Pure helpers for the Mechanical Turk tab: money, answers, CSV, qualification requirements,
 * worker-id lists and error wording. Kept free of Vue and of the network so they can be tested
 * on their own (test/recruit.test.ts).
 */
import { HttpError } from '@/lib/http'

// ---- money --------------------------------------------------------------------------------------

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })

/** Whole cents, immune to float noise (1.255 * 100 is 125.49999…, which would round down). */
export function toCents (dollars: number): number {
  return Math.round(parseFloat((dollars * 100).toPrecision(12)))
}

/** "$1.50"; an em dash for nothing. */
export function formatMoney (dollars: number | null | undefined): string {
  if (dollars == null || !Number.isFinite(dollars)) return '—'
  return usd.format(dollars)
}

/**
 * A dollar amount typed by a researcher or found in an answer ("1.5", "$1.50"), rounded to cents.
 * Null when it isn't a positive amount of at least one cent.
 */
export function parseMoney (value: string | number | null | undefined): number | null {
  if (value == null) return null
  const text = typeof value === 'number' ? String(value) : value.trim().replace(/^\$\s*/, '')
  if (!/^\d*\.?\d+$/.test(text) && !/^\d+\.$/.test(text)) return null
  const cents = toCents(parseFloat(text))
  return Number.isFinite(cents) && cents >= 1 ? cents / 100 : null
}

/** The bonus a participant's submit form reported (answer field `bonus`), or null. */
export function bonusFromAnswers (answers: Record<string, string>): number | null {
  return parseMoney(answers.bonus)
}

/** Sum of amounts in dollars, added up in cents so 0.1 + 0.2 is 0.30. Invalid amounts count as 0. */
export function bonusTotal (amounts: Array<number | null | undefined>): number {
  let cents = 0
  for (const a of amounts) {
    if (a != null && Number.isFinite(a) && a > 0) cents += toCents(a)
  }
  return cents / 100
}

/** Amount as MTurk wants it on the wire: "1.50". */
export function moneyString (dollars: number): string {
  return (toCents(dollars) / 100).toFixed(2)
}

/**
 * What MTurk adds on top of rewards and bonuses: 20%, plus another 20% for HITs with 10 or more
 * assignments, plus 5% for Masters (MTurk's published pricing; shown as an estimate).
 */
export function mturkFeeRate (maxAssignments: number, masters = false): number {
  return 0.2 + (maxAssignments >= 10 ? 0.2 : 0) + (masters ? 0.05 : 0)
}

// ---- answers ------------------------------------------------------------------------------------

export interface ParsedAnswers {
  /** answer value by question identifier (form field name) */
  values: Record<string, string>
  /** identifiers in the order they appear */
  keys: string[]
  /** set when the XML could not be read; the values are then empty */
  error: string | null
}

function childrenByName (el: Element, name: string): Element[] {
  return Array.from(el.children).filter(c => c.localName === name)
}

/**
 * Read MTurk's QuestionFormAnswers XML. Unlike the old console this decodes entities (`&amp;` shows
 * as `&`), understands every answer kind (free text, selections, uploaded files) and never throws:
 * a broken answer is reported on its own row instead of hiding the whole page of assignments.
 * A field submitted twice keeps both values, joined by ", ".
 */
export function parseAnswers (xml: string | null | undefined): ParsedAnswers {
  const out: ParsedAnswers = { values: {}, keys: [], error: null }
  if (!xml || !xml.trim()) return out
  let doc: Document
  try {
    doc = new DOMParser().parseFromString(xml, 'text/xml')
  } catch (e) {
    return { ...out, error: 'The answer could not be read.' }
  }
  if (doc.getElementsByTagName('parsererror').length || !doc.documentElement) {
    return { ...out, error: 'The answer is not valid XML.' }
  }
  const answers = Array.from(doc.getElementsByTagNameNS('*', 'Answer'))
  for (const a of answers) {
    const id = childrenByName(a, 'QuestionIdentifier')[0]?.textContent?.trim()
    if (!id) continue
    const parts: string[] = []
    for (const c of Array.from(a.children)) {
      if (c.localName === 'QuestionIdentifier') continue
      if (c.localName === 'UploadedFileSizeInBytes') continue
      const text = c.textContent ?? ''
      if (c.localName === 'UploadedFileKey') parts.push(`file: ${text}`)
      else parts.push(text)
    }
    const value = parts.join(', ')
    if (id in out.values) {
      out.values[id] = out.values[id] ? `${out.values[id]}, ${value}` : value
    } else {
      out.values[id] = value
      out.keys.push(id)
    }
  }
  return out
}

// ---- CSV ----------------------------------------------------------------------------------------

const NUMBER = /^[-+]?(\d+\.?\d*|\.\d+)(e[-+]?\d+)?$/i

/**
 * One CSV cell: always quoted, quotes doubled. Text a spreadsheet would run as a formula
 * (starting with = + - @) gets a leading apostrophe, because answers are typed by workers;
 * plain numbers like -0.5 are left alone.
 */
export function csvCell (value: unknown): string {
  if (value == null) return '""'
  let s = typeof value === 'string' ? value : String(value)
  if (/^[=+\-@\t\r]/.test(s) && !NUMBER.test(s)) s = `'${s}`
  return `"${s.replace(/"/g, '""')}"`
}

export function toCsv (rows: unknown[][]): string {
  return rows.map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

export interface CsvHit { hitId: string, title: string, creationTime?: number | string | null }
export interface CsvAssignment {
  assignmentId: string
  workerId: string
  status: string
  approvalTime: string | null
  rejectionTime: string | null
  /** total bonus paid, or null when not known yet */
  bonusPaid: number | null
  completed: boolean
  answers: Record<string, string>
  answerKeys: string[]
}

const FIXED_COLUMNS = ['hitId', 'hitTitle', 'creationTime', 'assignmentId', 'workerId', 'approvalTime', 'rejectionTime', 'bonusGranted', 'status', 'completed']

export function isoTime (t: number | string | null | undefined): string {
  if (t == null || t === '') return ''
  const d = new Date(t)
  return Number.isNaN(d.getTime()) ? String(t) : d.toISOString()
}

/**
 * The assignments CSV: the old console's columns first (so existing analysis scripts keep working),
 * then status and completed, then one column per answer field in first-seen order. An answer field
 * named like a fixed column is written as `answer_<name>`.
 */
export function assignmentsCsv (hit: CsvHit, rows: CsvAssignment[]): string {
  const answerKeys: string[] = []
  for (const r of rows) for (const k of r.answerKeys) if (!answerKeys.includes(k)) answerKeys.push(k)
  const header = [...FIXED_COLUMNS, ...answerKeys.map(k => FIXED_COLUMNS.includes(k) ? `answer_${k}` : k)]
  const body = rows.map(r => [
    hit.hitId,
    hit.title,
    isoTime(hit.creationTime),
    r.assignmentId,
    r.workerId,
    r.approvalTime || '',
    r.rejectionTime || '',
    r.bonusPaid == null ? '' : moneyString(r.bonusPaid),
    r.status,
    r.completed ? 'true' : 'false',
    ...answerKeys.map(k => r.answers[k] ?? ''),
  ])
  return toCsv([header, ...body])
}

// ---- HITs ---------------------------------------------------------------------------------------

export interface Annotation { breadboard: boolean, experimentUid: string | null, experimentName: string | null }

/**
 * Breadboard writes `{"experimentType":"breadboard","experimentUid":…,"experimentName":…}` into a
 * HIT's RequesterAnnotation, without escaping the name. A quote in the name breaks the JSON, so
 * this falls back to picking the uid out of the text.
 */
export function parseAnnotation (raw: string | null | undefined): Annotation {
  const none: Annotation = { breadboard: false, experimentUid: null, experimentName: null }
  if (!raw) return none
  try {
    const j = JSON.parse(raw)
    if (j && typeof j === 'object') {
      return {
        breadboard: j.experimentType === 'breadboard' || !!j.experimentUid,
        experimentUid: typeof j.experimentUid === 'string' && j.experimentUid ? j.experimentUid : null,
        experimentName: typeof j.experimentName === 'string' ? j.experimentName : null,
      }
    }
  } catch (e) { /* fall through */ }
  const uid = raw.match(/"experimentUid"\s*:\s*"([^"]+)"/)
  const name = raw.match(/"experimentName"\s*:\s*"(.*)"\s*}\s*$/)
  if (!uid) return none
  return { breadboard: true, experimentUid: uid[1], experimentName: name ? name[1] : null }
}

export interface HitCounts { requested: number, inProgress: number, toReview: number, reviewed: number, remaining: number }

function n (v: unknown): number {
  const x = typeof v === 'number' ? v : parseInt(String(v ?? ''), 10)
  return Number.isFinite(x) ? x : 0
}

/** Assignment counts. "To review" excludes workers still doing the task (the old console mixed them in). */
export function hitCounts (hit: Record<string, any>): HitCounts {
  const requested = n(hit.maxAssignments)
  const remaining = n(hit.numberOfAssignmentsAvailable)
  const inProgress = n(hit.numberOfAssignmentsPending)
  const reviewed = n(hit.numberOfAssignmentsCompleted)
  return { requested, inProgress, reviewed, remaining, toReview: Math.max(0, requested - remaining - inProgress - reviewed) }
}

// ---- qualification requirements -----------------------------------------------------------------

export type Comparator =
  | 'LessThan' | 'LessThanOrEqualTo' | 'GreaterThan' | 'GreaterThanOrEqualTo'
  | 'EqualTo' | 'NotEqualTo' | 'Exists' | 'DoesNotExist' | 'In' | 'NotIn'
export type ActionsGuarded = 'Accept' | 'PreviewAndAccept' | 'DiscoverPreviewAndAccept'
export type RequirementKind = 'masters' | 'approvedCount' | 'approvedPercent' | 'locale' | 'adult' | 'experiment' | 'custom'

export interface LocaleValue { country: string, subdivision: string }

/** A requirement as the researcher edits it (and as it is remembered between visits). */
export interface RequirementDraft {
  kind: RequirementKind
  comparator: Comparator
  /** "1000" or "1, 2, 3" */
  integerValues: string
  locales: LocaleValue[]
  actionsGuarded: ActionsGuarded
  /** kind "experiment": whose participants */
  experimentUid: string
  experimentName: string
  /** kind "custom": any MTurk qualification type id */
  customTypeId: string
}

/** Exactly the shape AMTAdmin.createHIT reads (BBQualificationRequirement). Extra keys make it fail. */
export interface WireRequirement {
  selectedQualificationType: { label: string, qualificationTypeId: string }
  otherExperiment: string
  comparator: Comparator
  integerValues: string
  locales: LocaleValue[]
  actionsGuarded: ActionsGuarded
}

export const COMPARATOR_LABELS: Record<Comparator, string> = {
  GreaterThanOrEqualTo: 'at least',
  GreaterThan: 'more than',
  LessThanOrEqualTo: 'at most',
  LessThan: 'less than',
  EqualTo: 'exactly',
  NotEqualTo: 'not',
  Exists: 'has it',
  DoesNotExist: 'does not have it',
  In: 'one of',
  NotIn: 'none of',
}

export const ACTIONS_GUARDED: { value: ActionsGuarded, label: string, hint: string }[] = [
  { value: 'Accept', label: 'Can see it, can’t accept it', hint: 'Workers who don’t qualify can find and preview the HIT but not accept it.' },
  { value: 'PreviewAndAccept', label: 'Can find it, can’t preview or accept', hint: 'Workers who don’t qualify see it in search results only.' },
  { value: 'DiscoverPreviewAndAccept', label: 'Hidden from them', hint: 'Workers who don’t qualify don’t see the HIT at all.' },
]

const NUMERIC: Comparator[] = ['GreaterThanOrEqualTo', 'GreaterThan', 'LessThanOrEqualTo', 'LessThan', 'EqualTo', 'NotEqualTo']

interface KindInfo {
  label: string
  hint: string
  comparators: Comparator[]
  values: 'none' | 'integer' | 'locale'
  defaults: Partial<RequirementDraft>
  min?: number
  max?: number
}

export const MASTERS_ID = { sandbox: '2ARFPLSP75KLA8M8DH1HTEQVJT3SY6', production: '2F1QJWKUDD8XADTFD2Q0G6UTO95ALH' }
const SYSTEM_IDS: Partial<Record<RequirementKind, string>> = {
  approvedCount: '00000000000000000040',
  locale: '00000000000000000071',
  adult: '00000000000000000060',
  approvedPercent: '000000000000000000L0',
}

export const REQUIREMENT_KINDS: Record<RequirementKind, KindInfo> = {
  approvedPercent: {
    label: 'Approval rate',
    hint: 'The share of a worker’s past submissions that requesters approved, in percent.',
    comparators: NUMERIC,
    values: 'integer',
    min: 0,
    max: 100,
    defaults: { comparator: 'GreaterThanOrEqualTo', integerValues: '95' },
  },
  approvedCount: {
    label: 'Number of approved HITs',
    hint: 'How many HITs the worker has had approved, ever.',
    comparators: NUMERIC,
    values: 'integer',
    min: 0,
    defaults: { comparator: 'GreaterThanOrEqualTo', integerValues: '1000' },
  },
  locale: {
    label: 'Location',
    hint: 'The country (and optionally state or region) in the worker’s MTurk account.',
    comparators: ['In', 'NotIn', 'EqualTo', 'NotEqualTo'],
    values: 'locale',
    defaults: { comparator: 'In', locales: [{ country: 'US', subdivision: '' }] },
  },
  masters: {
    label: 'Masters',
    hint: 'Workers MTurk has marked as consistently good. MTurk charges 5% extra.',
    comparators: ['Exists', 'DoesNotExist'],
    values: 'none',
    defaults: { comparator: 'Exists' },
  },
  adult: {
    label: 'Adult content',
    hint: 'Workers who agreed to see adult content.',
    comparators: ['EqualTo'],
    values: 'none',
    defaults: { comparator: 'EqualTo', integerValues: '1' },
  },
  experiment: {
    label: 'Took part in an experiment',
    hint: 'Workers who hold a Breadboard experiment’s participant qualification. Use “does not have it” to keep earlier participants out.',
    comparators: ['DoesNotExist', 'Exists'],
    values: 'none',
    defaults: { comparator: 'DoesNotExist', actionsGuarded: 'DiscoverPreviewAndAccept' },
  },
  custom: {
    label: 'Another qualification',
    hint: 'Any MTurk qualification type, by its id.',
    comparators: ['Exists', 'DoesNotExist', ...NUMERIC, 'In', 'NotIn'],
    values: 'integer',
    defaults: { comparator: 'Exists' },
  },
}

export function newRequirement (kind: RequirementKind): RequirementDraft {
  const base: RequirementDraft = {
    kind,
    comparator: 'Exists',
    integerValues: '',
    locales: [],
    actionsGuarded: 'Accept',
    experimentUid: '',
    experimentName: '',
    customTypeId: '',
  }
  const d = REQUIREMENT_KINDS[kind].defaults
  return { ...base, ...d, locales: (d.locales || []).map(l => ({ ...l })) }
}

/** Whether this comparator compares against values (Exists / DoesNotExist don't). */
export function comparatorTakesValues (c: Comparator): boolean {
  return c !== 'Exists' && c !== 'DoesNotExist'
}

/**
 * Integers separated by commas or spaces. The old console's pattern (`/^[0-9,]/` wrapped by
 * AngularJS into a regex that matches nothing) rejected every value, so "≥ 1000 approved" and
 * "≥ 95%" could never be added.
 */
export function parseIntegerList (text: string): { values: number[], error: string | null } {
  const parts = text.split(/[\s,]+/).filter(Boolean)
  if (!parts.length) return { values: [], error: 'Enter a number.' }
  const values: number[] = []
  for (const p of parts) {
    if (!/^-?\d+$/.test(p)) return { values: [], error: `“${p}” is not a whole number.` }
    values.push(parseInt(p, 10))
  }
  return { values, error: null }
}

export type RequirementErrors = Partial<Record<'comparator' | 'integerValues' | 'locales' | 'experimentUid' | 'customTypeId', string>>

export function validateRequirement (d: RequirementDraft): RequirementErrors {
  const info = REQUIREMENT_KINDS[d.kind]
  const errors: RequirementErrors = {}
  if (!info.comparators.includes(d.comparator)) errors.comparator = 'Choose how to compare.'
  const multi = d.comparator === 'In' || d.comparator === 'NotIn'
  if (info.values === 'integer' && comparatorTakesValues(d.comparator)) {
    const { values, error } = parseIntegerList(d.integerValues)
    if (error) errors.integerValues = error
    else if (!multi && values.length > 1) errors.integerValues = 'Enter one number (several are only allowed with “one of” / “none of”).'
    else if (values.length > 15) errors.integerValues = 'MTurk allows at most 15 values.'
    else if (info.min != null && values.some(v => v < info.min!)) errors.integerValues = `Use ${info.min} or more.`
    else if (info.max != null && values.some(v => v > info.max!)) errors.integerValues = `Use ${info.max} or less.`
  }
  if (info.values === 'locale') {
    const filled = d.locales.filter(l => l.country.trim() || l.subdivision.trim())
    if (!filled.length) errors.locales = 'Add at least one country.'
    else if (!multi && filled.length > 1) errors.locales = 'Add one place (several are only allowed with “one of” / “none of”).'
    else if (filled.some(l => !/^[A-Za-z]{2}$/.test(l.country.trim()))) errors.locales = 'Countries are two-letter codes, e.g. US, GB, IN.'
    else if (filled.some(l => l.subdivision.trim() && !/^[A-Za-z0-9]{1,3}$/.test(l.subdivision.trim()))) errors.locales = 'Regions are short codes, e.g. CA or NY.'
  }
  if (d.kind === 'experiment' && !d.experimentUid.trim()) errors.experimentUid = 'Choose an experiment.'
  if (d.kind === 'custom' && !/^[A-Za-z0-9]{6,}$/.test(d.customTypeId.trim())) errors.customTypeId = 'Paste the qualification type id from MTurk.'
  return errors
}

/** The MTurk qualification type a requirement points at; for experiments the caller looks it up. */
export function requirementTypeId (d: RequirementDraft, sandbox: boolean, experimentTypeId?: string | null): string | null {
  switch (d.kind) {
    case 'masters': return sandbox ? MASTERS_ID.sandbox : MASTERS_ID.production
    case 'experiment': return experimentTypeId || null
    case 'custom': return d.customTypeId.trim() || null
    default: return SYSTEM_IDS[d.kind] || null
  }
}

function placeList (locales: LocaleValue[]): string {
  return locales
    .filter(l => l.country.trim())
    .map(l => l.subdivision.trim() ? `${l.country.trim().toUpperCase()}-${l.subdivision.trim().toUpperCase()}` : l.country.trim().toUpperCase())
    .join(', ')
}

/** One plain sentence, e.g. "Approval rate at least 95%". */
export function describeRequirement (d: RequirementDraft): string {
  const cmp = COMPARATOR_LABELS[d.comparator] || d.comparator
  const vals = d.integerValues.split(/[\s,]+/).filter(Boolean).join(', ')
  switch (d.kind) {
    case 'masters': return d.comparator === 'DoesNotExist' ? 'Not a Masters worker' : 'Masters workers only'
    case 'adult': return 'Agreed to see adult content'
    case 'approvedPercent': return `Approval rate ${cmp} ${vals}%`
    case 'approvedCount': return `Approved HITs: ${cmp} ${vals}`
    case 'locale': {
      const places = placeList(d.locales) || '…'
      return d.comparator === 'NotIn' || d.comparator === 'NotEqualTo' ? `Not located in ${places}` : `Located in ${places}`
    }
    case 'experiment': {
      const name = d.experimentName || d.experimentUid || '…'
      return d.comparator === 'Exists' ? `Took part in “${name}”` : `Never took part in “${name}”`
    }
    case 'custom': {
      const id = d.customTypeId.trim() || '…'
      return comparatorTakesValues(d.comparator) ? `Qualification ${id}: ${cmp} ${vals}` : `Qualification ${id}: ${cmp}`
    }
  }
}

function requirementLabel (d: RequirementDraft): string {
  if (d.kind === 'experiment') return `${d.experimentName || d.experimentUid} participant`
  if (d.kind === 'custom') return d.customTypeId.trim()
  return REQUIREMENT_KINDS[d.kind].label
}

/**
 * The wire form for AMTAdmin.createHIT. Throws an Error with a readable message when the draft
 * is incomplete. Every field is always present as a string or list: the server calls
 * `integerValues.split`, `subdivision.trim()` and loops over `locales`, so a missing one would
 * crash the request with a server error.
 */
export function buildRequirement (d: RequirementDraft, opts: { sandbox: boolean, experimentTypeId?: string | null }): WireRequirement {
  const errors = validateRequirement(d)
  const first = Object.values(errors)[0]
  if (first) throw new Error(`${describeRequirement(d)}: ${first}`)
  const typeId = requirementTypeId(d, opts.sandbox, opts.experimentTypeId)
  if (!typeId) throw new Error(`${describeRequirement(d)}: the qualification type could not be found.`)
  const info = REQUIREMENT_KINDS[d.kind]
  let integerValues = ''
  if (d.kind === 'adult') integerValues = '1'
  else if (info.values === 'integer' && comparatorTakesValues(d.comparator)) integerValues = parseIntegerList(d.integerValues).values.join(',')
  const locales = info.values === 'locale'
    ? d.locales.filter(l => l.country.trim()).map(l => ({ country: l.country.trim().toUpperCase(), subdivision: l.subdivision.trim().toUpperCase() }))
    : []
  return {
    selectedQualificationType: { label: requirementLabel(d), qualificationTypeId: typeId },
    otherExperiment: '',
    comparator: d.comparator,
    integerValues,
    locales,
    actionsGuarded: d.actionsGuarded,
  }
}

// ---- worker ids ---------------------------------------------------------------------------------

const WORKER_ID = /^[A-Z0-9]{10,64}$/

/**
 * Worker ids from pasted text or a file: one per line, or separated by commas, spaces or tabs
 * (so a CSV column works too). Upper-cased, de-duplicated; anything that doesn't look like an id
 * (headers, empty cells) is reported rather than sent to MTurk.
 */
export function parseWorkerIds (text: string): { ids: string[], duplicates: string[], invalid: string[] } {
  const ids: string[] = []
  const duplicates: string[] = []
  const invalid: string[] = []
  for (const raw of text.split(/[\s,;]+/)) {
    const token = raw.replace(/^["']+|["']+$/g, '').trim()
    if (!token) continue
    const id = token.toUpperCase()
    if (!WORKER_ID.test(id)) { if (!invalid.includes(token)) invalid.push(token); continue }
    if (ids.includes(id)) { if (!duplicates.includes(id)) duplicates.push(id); continue }
    ids.push(id)
  }
  return { ids, duplicates, invalid }
}

// ---- misc ---------------------------------------------------------------------------------------

/** "5 min", "1 h 30 min", "2 days 4 h". */
export function humanDuration (seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return '—'
  const s = Math.max(0, Math.round(seconds))
  if (s < 60) return `${s} s`
  if (s < 3600) {
    const m = Math.floor(s / 60)
    const r = s % 60
    return r ? `${m} min ${r} s` : `${m} min`
  }
  if (s < 86400) {
    const h = Math.floor(s / 3600)
    const m = Math.round((s % 3600) / 60)
    return m ? `${h} h ${m} min` : `${h} h`
  }
  const d = Math.floor(s / 86400)
  const h = Math.round((s % 86400) / 3600)
  return `${d} day${d === 1 ? '' : 's'}${h ? ` ${h} h` : ''}`
}

/**
 * The server schedules the automatic start with `seconds * 1000` in a 32-bit integer, so anything
 * past about 24.8 days overflows and the timer is never set.
 */
export const MAX_AUTOSTART_SECONDS = Math.floor(2147483647 / 1000)

/** Run `fn` over items with at most `limit` at a time (MTurk throttles bursts). */
export async function mapPool<T, R> (items: T[], limit: number, fn: (item: T, index: number) => Promise<R>): Promise<PromiseSettledResult<R>[]> {
  const results: PromiseSettledResult<R>[] = new Array(items.length)
  let next = 0
  async function worker () {
    while (next < items.length) {
      const i = next++
      try {
        results[i] = { status: 'fulfilled', value: await fn(items[i], i) }
      } catch (reason) {
        results[i] = { status: 'rejected', reason }
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(Math.max(1, limit), items.length) }, worker))
  return results
}

export const NO_KEYS_MESSAGE = 'No AWS keys provided'

export interface ErrorText {
  message: string
  detail?: string
  /** the server has no AWS keys configured */
  noKeys?: boolean
}

/**
 * Words for a failed call. /amtadmin errors are plain text: AWS messages carry a technical tail
 * "(Service: AmazonMTurk; Status Code: 400; Error Code: …; Request ID: …)" which becomes the
 * detail; a server crash page (HTML) is never shown as text.
 */
export function describeError (e: unknown): ErrorText {
  if (e instanceof HttpError) {
    const raw = (typeof e.body === 'string' ? e.body : e.message || '').trim()
    if (raw === NO_KEYS_MESSAGE) {
      return { noKeys: true, message: 'Mechanical Turk is not set up on this server yet.', detail: 'The server has no AWS keys.' }
    }
    if (/^<(!doctype|html)/i.test(raw) || /<\/html>/i.test(raw)) {
      return { message: `The server hit an unexpected error (HTTP ${e.status}).`, detail: 'The server log has the details.' }
    }
    const aws = raw.match(/^([\s\S]*?)\s*\((Service: [\s\S]*)\)\s*$/)
    if (aws) return { message: aws[1] || 'Mechanical Turk refused the request.', detail: aws[2] }
    return { message: raw || `The request failed (HTTP ${e.status}).` }
  }
  if (e instanceof TypeError) return { message: 'Could not reach the server.', detail: 'Check your connection and try again.' }
  const msg = (e as any)?.message
  return { message: typeof msg === 'string' && msg ? msg : String(e) }
}

/** One line for toasts and table cells. */
export function errorLine (e: unknown): string {
  const d = describeError(e)
  return d.message
}

/** A readable local date and time for epoch ms or an ISO string. */
export function formatTime (t: number | string | null | undefined, withTime = true): string {
  if (t == null || t === '') return '—'
  // MTurk minute-precision strings ("2026-10-27T14:05Z") parse fine in modern browsers
  const d = new Date(typeof t === 'string' && /^\d{4}-\d\d-\d\d \d/.test(t) ? t.replace(' ', 'T').replace(',', '.') : t)
  if (Number.isNaN(d.getTime())) return String(t)
  return withTime
    ? d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
    : d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}
