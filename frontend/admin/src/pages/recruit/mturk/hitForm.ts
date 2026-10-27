/**
 * The Create HIT form: defaults, what this browser remembers, validation and the request body.
 * Pure (apart from localStorage) so it can be tested.
 */
import type { CreateHitBody } from './api'
import { MAX_AUTOSTART_SECONDS, humanDuration, moneyString, parseMoney, type RequirementDraft, type WireRequirement } from './lib'
import { readJson, readStore, writeJson } from '../storage'

export type RepeatPlay = 'type' | 'any' | 'none'

export interface HitForm {
  title: string
  description: string
  keywords: string
  /** dollars, as typed */
  reward: string
  maxAssignments: number | null
  /** seconds workers can accept the HIT; the session closes to newcomers after it */
  lifetime: number | null
  /** extra seconds before the game starts by itself */
  tutorialTime: number | null
  /** seconds each worker has to finish and submit */
  assignmentDuration: number | null
  disallowPrevious: RepeatPlay
  /** start the game (initStep.start()) after lifetime + tutorial time */
  autoLaunch: boolean
  requirements: RequirementDraft[]
}

export const HIT_FORM_DEFAULTS: Readonly<HitForm> = Object.freeze({
  title: '',
  description: '',
  keywords: '',
  reward: '1.00',
  maxAssignments: 20,
  lifetime: 300,
  tutorialTime: 300,
  assignmentDuration: 5400,
  disallowPrevious: 'type',
  autoLaunch: true,
  requirements: [],
})

export const FORM_KEY = 'bb.recruit.mturk.hitForm'

export function defaultHitForm (): HitForm {
  return { ...HIT_FORM_DEFAULTS, requirements: [] }
}

function int (v: unknown): number | null {
  const x = typeof v === 'number' ? v : parseInt(String(v ?? ''), 10)
  return Number.isFinite(x) ? x : null
}

/**
 * What this browser remembers. Falls back to the old console's keys (same site, so the same
 * storage) the first time, so researchers keep their usual settings.
 */
export function loadHitForm (): HitForm {
  const saved = readJson<Partial<HitForm>>(FORM_KEY)
  const base = defaultHitForm()
  if (saved && typeof saved === 'object') {
    return {
      ...base,
      ...saved,
      requirements: Array.isArray(saved.requirements) ? saved.requirements.filter(r => r && typeof r === 'object' && r.kind) : [],
    }
  }
  const legacy = (k: string) => readStore(k)
  const title = legacy('createHitTitle')
  if (title == null) return base
  const repeat = legacy('createHitDisallowPrevious')
  return {
    ...base,
    title: title || '',
    description: legacy('createHitDescription') || '',
    keywords: legacy('createHitKeywords') || '',
    reward: legacy('createHitReward') || base.reward,
    maxAssignments: int(legacy('createHitMaxAssignments')) ?? base.maxAssignments,
    lifetime: int(legacy('createHitLifetime')) ?? base.lifetime,
    tutorialTime: int(legacy('createHitTutorialTime')) ?? base.tutorialTime,
    assignmentDuration: int(legacy('createHitAssignmentDuration')) ?? base.assignmentDuration,
    disallowPrevious: repeat === 'any' || repeat === 'none' || repeat === 'type' ? repeat : base.disallowPrevious,
    autoLaunch: legacy('createHitAutoLaunch') !== 'false',
  }
}

export function saveHitForm (f: HitForm) {
  writeJson(FORM_KEY, f)
}

export function clearHitForm () {
  writeJson(FORM_KEY, null)
}

export type HitFormErrors = Partial<Record<keyof HitForm, string>>

const YEAR = 31536000

function wholeIn (v: number | null, min: number, max: number, what: string): string | undefined {
  if (v == null || !Number.isFinite(v)) return `Enter ${what}.`
  if (!Number.isInteger(v)) return 'Use whole seconds.'
  if (v < min) return `MTurk needs at least ${humanDuration(min)}.`
  if (v > max) return `MTurk allows at most ${humanDuration(max)}.`
  return undefined
}

/** Field → problem, in the order the fields appear. Empty when the form can be sent. */
export function validateHitForm (f: HitForm): HitFormErrors {
  const e: HitFormErrors = {}
  if (!f.title.trim()) e.title = 'Give the HIT a title workers will see in the list.'
  else if (f.title.trim().length > 128) e.title = 'MTurk allows 128 characters.'
  if (!f.description.trim()) e.description = 'Describe the task in a sentence or two.'
  else if (f.description.trim().length > 2000) e.description = 'MTurk allows 2000 characters.'
  if (!f.keywords.trim()) e.keywords = 'Add a few words workers might search for.'
  else if (f.keywords.trim().length > 1000) e.keywords = 'MTurk allows 1000 characters.'
  if (parseMoney(f.reward) == null) e.reward = 'Enter a reward of at least $0.01, like 1.50.'
  if (f.maxAssignments == null || !Number.isInteger(f.maxAssignments) || f.maxAssignments < 1) e.maxAssignments = 'Enter how many workers you need (1 or more).'
  else if (f.maxAssignments > 1000000) e.maxAssignments = 'That is more than MTurk allows.'
  const life = wholeIn(f.lifetime, 30, YEAR, 'how long the HIT stays open')
  if (life) e.lifetime = life
  if (f.tutorialTime == null || !Number.isInteger(f.tutorialTime) || f.tutorialTime < 0) e.tutorialTime = 'Enter a time (0 or more).'
  const dur = wholeIn(f.assignmentDuration, 30, YEAR, 'how long each worker has')
  if (dur) e.assignmentDuration = dur
  if (f.autoLaunch && !e.lifetime && !e.tutorialTime && (f.lifetime! + f.tutorialTime!) > MAX_AUTOSTART_SECONDS) {
    e.autoLaunch = `Starting by itself works up to ${humanDuration(MAX_AUTOSTART_SECONDS)} after posting. Shorten the times or start the game yourself.`
  }
  return e
}

/** The createHIT request. `qualificationRequirements` goes last on purpose (see CreateHitBody). */
export function hitFormBody (f: HitForm, ctx: { experimentId: number, instanceId: number, requirements: WireRequirement[] }): CreateHitBody {
  return {
    title: f.title.trim(),
    description: f.description.trim(),
    reward: moneyString(parseMoney(f.reward) || 0),
    maxAssignments: f.maxAssignments || 1,
    hitLifetime: f.lifetime || 0,
    assignmentDuration: f.assignmentDuration || 0,
    keywords: f.keywords.trim(),
    tutorialTime: f.tutorialTime || 0,
    disallowPrevious: f.disallowPrevious,
    experimentId: String(ctx.experimentId),
    experimentInstanceId: String(ctx.instanceId),
    qualificationRequirements: ctx.requirements,
  }
}

// ---- automatic start bookkeeping ----------------------------------------------------------------

const SCHEDULE_KEY = 'bb.recruit.mturk.autostart'

export interface AutoStart { hitId: string, closesAt: number, startsAt: number }

/** When the server was asked to close entry and start the game, by HIT id (this browser only). */
export function readSchedules (): Record<string, AutoStart> {
  return readJson<Record<string, AutoStart>>(SCHEDULE_KEY) || {}
}

export function rememberSchedule (s: AutoStart) {
  const all = readSchedules()
  all[s.hitId] = s
  // keep the list short
  const recent = Object.values(all).sort((a, b) => b.startsAt - a.startsAt).slice(0, 20)
  writeJson(SCHEDULE_KEY, Object.fromEntries(recent.map(x => [x.hitId, x])))
}
