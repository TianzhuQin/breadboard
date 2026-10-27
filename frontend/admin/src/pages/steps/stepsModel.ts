import type { Step } from '@/store/types'

/**
 * The Steps page's rules, kept free of Vue and the server so they can be tested on their own:
 * templates for new steps, the built-in steps, sorting, naming and the unsaved-changes tracking.
 */

export type TemplateKind = 'sync' | 'async' | 'empty'
/** What to do with unsaved steps when leaving the page. */
export type LeaveChoice = 'stay' | 'leave' | 'save'

export interface Builtin {
  name: string
  /** when it runs, in a few words (shown in the list) */
  when: string
  /** a fuller explanation (shown above the editor) */
  detail: string
  /** the source a new experiment starts with (models/Experiment.java generate*Step) */
  source: string
}

/** The steps Breadboard itself calls by name (case-insensitively, models/Experiment.java). */
export const BUILTINS: Builtin[] = [
  {
    name: 'InitStep',
    when: 'Starts the game',
    detail: 'Runs when the game starts: when you press Start game on the Live session page, or when the autopilot starts the session. It runs once per session (initStep.start()).',
    source: 'initStep = stepFactory.createStep("InitStep")\n\ninitStep.run = {\n  println "initStep.run"\n}\ninitStep.done = {\n  println "initStep.done"\n}',
  },
  {
    name: 'OnJoinStep',
    when: 'Runs when someone joins',
    detail: 'Runs every time a participant joins the session, with their player id (onJoinStep.start(playerId)). Use it to set up the newcomer. What it prints appears in the Output panel.',
    source: 'onJoinStep = stepFactory.createNoUserActionStep()\n\nonJoinStep.run = { playerId->\n  println "onJoinStep.run"\n  def player = g.getVertex(playerId)\n}\nonJoinStep.done = {\n  println "onJoinStep.done"\n}',
  },
  {
    name: 'OnLeaveStep',
    when: 'Runs when someone leaves',
    detail: 'Runs every time a participant is taken out of the network, with their player id (onLeaveStep.start(playerId)). What it prints appears in the Output panel.',
    source: 'onLeaveStep = stepFactory.createNoUserActionStep()\n\nonLeaveStep.run = {\n  println "onLeaveStep.run"\n}\nonLeaveStep.done = {\n  println "onLeaveStep.done"\n}',
  },
]

export function builtinOf (name: string | null | undefined): Builtin | null {
  if (!name) return null
  const n = name.trim().toLowerCase()
  return BUILTINS.find(b => b.name.toLowerCase() === n) || null
}
export function isBuiltin (name: string | null | undefined) {
  return !!builtinOf(name)
}

export const TEMPLATES: { kind: TemplateKind, label: string, hint: string, text: string }[] = [
  { kind: 'sync', label: 'Synchronous', hint: 'waits for choices', text: 'Offers choices and ends when everyone has answered (stepFactory.createStep).' },
  { kind: 'async', label: 'Asynchronous', hint: 'no waiting', text: 'Runs without waiting for anyone\'s choices (stepFactory.createNoUserActionStep).' },
  { kind: 'empty', label: 'Empty', hint: 'no code', text: 'Starts with no code.' },
]

/**
 * The variable a step's code defines, from its name: "My-new_step" → "myNewStep".
 * The old console's formatName (frontend/design/steps/steps.controller.js), unchanged.
 */
export function formatName (name: string): string {
  return name
    .replace(/[-_]+/g, ' ')
    .replace(/[^\w\s]/g, '')
    .replace(/(?:^\w|[A-Z]|\b\w)/g, (letter: string, index: number) => index === 0 ? letter.toLowerCase() : letter.toUpperCase())
    .replace(/ /g, '')
}

/** A new step's code (the old console's getSource, unchanged). */
export function templateSource (name: string, kind: TemplateKind): string {
  if (kind === 'empty') return ''
  const v = formatName(name)
  return v + ' = stepFactory.' + (kind === 'sync' ? 'createStep' : 'createNoUserActionStep') + '("' + name + '")\n' +
    '\n' +
    v + '.run = {\n' +
    '  println "' + v + '.run"\n' +
    '}\n\n' +
    v + '.done = {\n' +
    '  println "' + v + '.done"\n' +
    '}'
}

// ---- drafts -----------------------------------------------------------------------------------

export interface StepRun {
  status: 'pending' | 'ok' | 'error' | 'noreply'
  values: string[]
  error: string | null
  at: number
}

/** A step as edited here: what is on the server and what is in the editor. */
export interface StepDraft {
  /** stable local id (list keys, selection, editor instance) */
  uid: string
  /** server id; null for a step not saved yet, and for every step in file mode */
  id: number | null
  name: string
  /** the name on the server (null: never saved) */
  savedName: string | null
  source: string
  /** the code on the server (null: never saved) */
  savedSource: string | null
  /** a new step's template: its code follows its name until the code is edited */
  template: TemplateKind | null
  status: 'idle' | 'saving' | 'saved' | 'error' | 'deleting'
  /** the last save or delete error, in words */
  error: string | null
  /** when it was last saved here (ms), to ignore server copies older than that save */
  savedAt: number
  /** saved since it was last run: the live engine still has an older version */
  engineBehind: boolean
  /** the last time it was run in the engine from this page */
  run: StepRun | null
  /** it was removed on the server while it had unsaved edits here; saving creates it again */
  orphaned: boolean
}

let uidSeq = 0
export function nextUid (prefix = 's') {
  return `${prefix}${++uidSeq}`
}

/**
 * A step's code with "\n" line endings, as the code editor gives it back. Steps written on Windows
 * (or imported from there) have "\r\n"; without this they would look edited after an undo or a revert.
 */
export function normalizeEol (source: string | null | undefined): string {
  return (source ?? '').replace(/\r\n?/g, '\n')
}

export function draftFromStep (s: Step): StepDraft {
  const source = normalizeEol(s.source)
  return {
    uid: nextUid(),
    id: s.id ?? null,
    name: s.name,
    savedName: s.name,
    source,
    savedSource: source,
    template: null,
    status: 'idle',
    error: null,
    savedAt: 0,
    engineBehind: false,
    run: null,
    orphaned: false,
  }
}

export function newDraft (name: string, kind: TemplateKind, source = templateSource(name, kind)): StepDraft {
  return {
    uid: nextUid('n'),
    id: null,
    name,
    savedName: null,
    source,
    savedSource: null,
    template: kind,
    status: 'idle',
    error: null,
    savedAt: 0,
    engineBehind: false,
    run: null,
    orphaned: false,
  }
}

export function isNew (d: StepDraft) {
  return d.savedSource === null
}

/** Unsaved: never saved, or its code or name differ from the server's. */
export function isDirty (d: StepDraft) {
  return d.savedSource === null || d.source !== d.savedSource || d.name !== d.savedName
}

/** Rename a draft. A new step's code follows its name while it is still the untouched template. */
export function renameDraft (d: StepDraft, name: string) {
  if (d.template && d.source === templateSource(d.name, d.template)) d.source = templateSource(name, d.template)
  d.name = name
}

/** Built-in steps first (InitStep, OnJoinStep, OnLeaveStep), then the rest by name. */
export function sortDrafts<T extends { name: string }> (list: T[]): T[] {
  const rank = (n: string) => {
    const b = builtinOf(n)
    return b ? BUILTINS.indexOf(b) : BUILTINS.length
  }
  return [...list].sort((a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }))
}

/** Why a name can't be used, or null when it's fine. Only names being changed are checked. */
export function validateName (name: string, all: StepDraft[], self: StepDraft): string | null {
  const n = name.trim()
  if (!n) return 'Give the step a name.'
  if (self.savedName !== null && name === self.savedName) return null
  if (n !== name) return 'The name can\'t start or end with a space.'
  const other = all.find(d => d !== self && d.name.trim().toLowerCase() === n.toLowerCase())
  if (other) return `Another step is already called “${other.name}”.`
  if (!/^[A-Za-z]/.test(n)) return 'Start the name with a letter.'
  if (!/^[A-Za-z][A-Za-z0-9 _-]*$/.test(n)) return 'Use only letters, digits, spaces, - and _.'
  if (n.length > 80) return 'Keep the name under 80 characters.'
  return null
}

/** "NewStep", or "NewStep2", "NewStep3" … when taken. */
export function uniqueName (base: string, all: { name: string }[]) {
  const taken = new Set(all.map(d => d.name.toLowerCase()))
  if (!taken.has(base.toLowerCase())) return base
  for (let i = 2; ; i++) {
    if (!taken.has(`${base}${i}`.toLowerCase())) return `${base}${i}`
  }
}

/** A copy's name: "Payoff copy", "Payoff copy 2" … */
export function copyName (name: string, all: { name: string }[]) {
  return uniqueName(`${name} copy`, all)
}

/**
 * Bring the server's steps into the drafts without losing anything typed here.
 * - A step the server has and we don't: added.
 * - Unchanged drafts follow the server's copy; drafts with unsaved edits, and drafts being saved
 *   or saved within the last few seconds (the server's copy may predate that save), are left alone.
 * - Gone on the server: dropped when unchanged; kept as a new step (to save again) when edited.
 * - New, never-saved drafts always stay.
 * In file mode steps have no ids and are matched by name.
 */
export function mergeServerSteps (drafts: StepDraft[], steps: Step[], fileMode: boolean, now = Date.now()): StepDraft[] {
  const RECENT = 5000
  const out: StepDraft[] = []
  const used = new Set<StepDraft>()
  const seenKeys = new Set<string>()
  for (const s of steps) {
    const key = fileMode ? `name:${s.name.toLowerCase()}` : `id:${s.id}`
    if (seenKeys.has(key)) continue
    seenKeys.add(key)
    const d = drafts.find(x => !used.has(x) && (fileMode
      ? x.savedName !== null && x.savedName.toLowerCase() === s.name.toLowerCase()
      : x.id !== null && x.id === s.id))
    if (!d) {
      out.push(draftFromStep(s))
      continue
    }
    used.add(d)
    const busy = d.status === 'saving' || d.status === 'deleting' || now - d.savedAt < RECENT
    if (!isDirty(d) && !busy) {
      d.name = d.savedName = s.name
      d.source = d.savedSource = normalizeEol(s.source)
    }
    out.push(d)
  }
  for (const d of drafts) {
    if (used.has(d)) continue
    if (isNew(d)) { out.push(d); continue }
    const busy = d.status === 'saving' || d.status === 'deleting' || now - d.savedAt < RECENT
    if (busy) { out.push(d); continue }
    if (isDirty(d) && !fileMode) {
      d.id = null
      d.savedName = null
      d.savedSource = null
      d.orphaned = true
      out.push(d)
    }
  }
  return out
}
