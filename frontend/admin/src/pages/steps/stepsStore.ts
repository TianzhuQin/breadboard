import { computed, reactive, toRaw, watch } from 'vue'
import { http, HttpError } from '@/lib/http'
import { admin, actions, ensureConnected } from '@/store/admin'
import { confirmSwitch } from '@/store/engineGuard'
import type { Step } from '@/store/types'
import { registerUnsaved } from '@/shell/unsaved'
import { notify, toast } from '@/ui'
import { expectReply, lastOutputId } from '../console/engineReplies'
import {
  copyName, isDirty, isNew, mergeServerSteps, newDraft, renameInSource, uniqueName, validateName,
  type Builtin, type StepDraft, type TemplateKind,
} from './stepsModel'

/**
 * The Steps page's drafts, per experiment, kept for the whole visit: leaving the page or switching
 * experiment doesn't throw away unsaved edits (the old console did, silently, §3.2.6). Closing or
 * reloading the tab asks first while anything is unsaved.
 */

export interface StepsBucket {
  experimentId: number
  fileMode: boolean
  drafts: StepDraft[]
  selected: string | null
  loading: boolean
  loaded: boolean
  loadError: string | null
}

export const buckets = reactive(new Map<string, StepsBucket>())

// The drafts belong to the account that typed them: signing in as someone else starts clean.
let bucketsFor = admin.email
watch(() => admin.email, (email) => {
  if (email === bucketsFor) return
  buckets.clear()
  stepDeletedSinceReload = false
  bucketsFor = email
})

export function bucketKey (experimentId: number, fileMode: boolean) {
  return `${experimentId}:${fileMode ? 'file' : 'db'}`
}

export function ensureBucket (experimentId: number, fileMode: boolean): StepsBucket {
  const key = bucketKey(experimentId, fileMode)
  if (!buckets.has(key)) {
    buckets.set(key, { experimentId, fileMode, drafts: [], selected: null, loading: false, loaded: false, loadError: null })
  }
  return buckets.get(key)!
}

/** An error in words for the researcher. */
export function errorText (e: unknown): string {
  if (e instanceof HttpError) {
    const msg = (e.message || '').trim()
    if (e.status >= 500) return `The server had a problem (${e.status})${msg ? `: ${msg}` : '.'}`
    return msg || `The server refused (${e.status}).`
  }
  if (e instanceof TypeError) return 'Could not reach the server. Check that it is running and your connection works.'
  const m = (e as any)?.message
  return m ? String(m) : 'Something went wrong.'
}

// ---- loading ----------------------------------------------------------------------------------

export async function loadSteps (b: StepsBucket) {
  b.loading = true
  b.loadError = null
  try {
    const res = await http.get<{ steps: Step[] }>(`/steps/${b.experimentId}`)
    b.drafts = mergeServerSteps(b.drafts, Array.isArray(res?.steps) ? res.steps : [], b.fileMode)
    b.loaded = true
  } catch (e) {
    b.loadError = errorText(e)
  } finally {
    b.loading = false
  }
}

/** Follow the steps in a User JSON push (in file mode they come from the files, DL-9). */
export function mergeFromServer (b: StepsBucket, steps: Step[]) {
  b.drafts = mergeServerSteps(b.drafts, steps, b.fileMode)
  b.loaded = true
}

// ---- editing ----------------------------------------------------------------------------------

export function addDraft (b: StepsBucket, d: StepDraft): StepDraft {
  b.drafts.push(d)
  b.selected = d.uid
  return b.drafts[b.drafts.length - 1]
}

export function createFromTemplate (b: StepsBucket, kind: TemplateKind) {
  return addDraft(b, newDraft(uniqueName('NewStep', b.drafts), kind))
}

export function createBuiltin (b: StepsBucket, builtin: Builtin) {
  const d = newDraft(builtin.name, 'empty', builtin.source)
  d.template = null
  return addDraft(b, d)
}

export function duplicate (b: StepsBucket, from: StepDraft) {
  const name = copyName(from.name, b.drafts)
  const d = newDraft(name, 'empty', renameInSource(from.source, from.name, name))
  d.template = null
  return addDraft(b, d)
}

export function removeDraft (b: StepsBucket, d: StepDraft) {
  const sorted = b.drafts
  const i = sorted.indexOf(d)
  if (i < 0) return
  b.drafts.splice(i, 1)
  if (b.selected === d.uid) b.selected = null
}

// The save each draft is waiting for, so a second Save (Cmd+S then Cmd+Enter) waits for it.
const savesInFlight = new WeakMap<StepDraft, Promise<boolean>>()

/**
 * Save one step (POST /steps/:id, -1 for a new one). Resolves whether it worked; errors go on the
 * draft. While a save is on its way, another call waits for it, then saves again if more was typed.
 */
export function saveDraft (b: StepsBucket, d: StepDraft): Promise<boolean> {
  const key = toRaw(d)
  const pending = savesInFlight.get(key)
  if (pending) return pending.then(ok => ok && isDirty(d) ? saveDraft(b, d) : ok)
  const p = saveNow(b, d).finally(() => {
    if (savesInFlight.get(key) === p) savesInFlight.delete(key)
  })
  savesInFlight.set(key, p)
  return p
}

async function saveNow (b: StepsBucket, d: StepDraft): Promise<boolean> {
  if (b.fileMode) {
    d.error = 'This experiment is in file mode, so its steps can only be changed in their files.'
    return false
  }
  if (d.status === 'deleting') {
    d.error = 'This step is being deleted.'
    return false
  }
  const problem = validateName(d.name, b.drafts, d)
  if (problem) {
    d.status = 'error'
    d.error = problem
    return false
  }
  const name = d.name
  const source = d.source
  d.status = 'saving'
  d.error = null
  try {
    const res = await http.post<Step>(`/steps/${d.id ?? -1}`, { experimentId: b.experimentId, stepSource: source, name })
    const changed = d.savedSource !== source
    if (res && typeof res.id === 'number') d.id = res.id
    d.savedName = name
    d.savedSource = source
    d.savedAt = Date.now()
    d.template = null
    d.orphaned = false
    if (changed) d.engineBehind = true
    d.status = 'saved'
    setTimeout(() => { if (d.status === 'saved') d.status = 'idle' }, 1800)
    return true
  } catch (e) {
    d.status = 'error'
    d.error = errorText(e)
    return false
  }
}

/**
 * Run a step's code in the live engine (SendStep) and keep the engine's answer on the draft. False
 * when it could not be sent (the connection is down): the draft says so at once.
 */
export function runDraft (d: StepDraft): boolean {
  const source = d.source
  const afterId = lastOutputId()
  const requestId = actions.runStep({ id: d.id, name: d.name, source })
  if (!requestId) {
    d.run = { status: 'notsent', values: [], error: null, at: Date.now() }
    if (d.engineBehind) actions.markEngineStale()
    return false
  }
  d.run = { status: 'pending', values: [], error: null, at: Date.now() }
  const run = d.run
  expectReply(source, afterId, { request: 'SendStep', requestId }).then(reply => {
    if (d.run !== run) return
    // it didn't load: the engine still has the version before this save (or half of this one)
    if ((!reply || !reply.ok) && d.engineBehind) actions.markEngineStale()
    if (!reply) {
      run.status = 'noreply'
      return
    }
    run.status = reply.ok ? 'ok' : 'error'
    // (a step's code ends with an assignment of a closure, which comes back as "Script12$_run_closure2@6d06d69c":
    // not worth showing)
    run.values = reply.values.filter((v: string) => !/^Script\d+\$_[\w$]*@[0-9a-f]+$/.test(String(v).trim()))
    run.error = reply.error
    if (reply.ok && d.source === source) {
      d.engineBehind = false
      engineCaughtUp()
    }
  })
  return true
}

// ---- is the live engine running the saved steps? ----------------------------------------------

/** A saved step was deleted since the engine last loaded: only a reload drops what it defined. */
let stepDeletedSinceReload = false

/**
 * Once every saved change has been loaded with Run, the engine is current again: clear the
 * "Reload needed" mark, so nobody reloads (and disconnects a running session) for nothing.
 */
function engineCaughtUp () {
  if (admin.engine !== 'stale' || stepDeletedSinceReload) return
  const id = admin.experiment?.id
  for (const b of buckets.values()) {
    if (b.experimentId === id && b.drafts.some(d => d.engineBehind)) return
  }
  // (the store keeps the mark of a reload that didn't finish: only a reload clears that)
  actions.markEngineCurrent()
}

// A reload (or an experiment switch) loads every saved step, so nothing is behind any more.
// (Signing out sets the time back to 0: that is no reload, and the drafts outlive it.)
watch(() => admin.engineReloadedAt, (t) => {
  if (!t) return
  stepDeletedSinceReload = false
  for (const b of buckets.values()) for (const d of b.drafts) d.engineBehind = false
})

// Signing in again (after Sign out or a 401) starts the store over with the engine marked current,
// but the shared engine still has the old versions of what was saved here and never loaded.
watch(() => admin.ready, (ready) => {
  if (!ready || admin.engine !== 'ready') return
  const id = admin.experiment?.id
  const behind = [...buckets.values()].some(b => b.experimentId === id && b.drafts.some(d => d.engineBehind))
  if (stepDeletedSinceReload || behind) actions.markEngineStale()
})

/** Delete a step (DELETE /steps/:id); a step never saved is just dropped. */
export async function deleteDraft (b: StepsBucket, d: StepDraft): Promise<boolean> {
  if (isNew(d) || d.id == null) {
    removeDraft(b, d)
    return true
  }
  d.status = 'deleting'
  d.error = null
  try {
    await http.del(`/steps/${d.id}`)
    removeDraft(b, d)
    stepDeletedSinceReload = true
    return true
  } catch (e) {
    d.status = 'error'
    d.error = errorText(e)
    return false
  }
}

/** Ask the server for fresh User JSON so other pages see saved steps (the HTTP routes don't push). */
export function refreshStore () {
  actions.refresh().catch(() => { /* the next push brings it anyway */ })
}

// ---- unsaved work across the visit -------------------------------------------------------------

export const unsavedDrafts = computed(() => {
  const out: StepDraft[] = []
  for (const b of buckets.values()) {
    if (!b.fileMode) out.push(...b.drafts.filter(isDirty))
  }
  return out
})

// signing out asks first while there are any (they are kept, but only in this tab)
registerUnsaved('steps', () => unsavedDrafts.value.length, 'Steps')

/** The experiments' names as last seen, to name a deleted one (also after signing in again). */
const experimentNames = new Map<number, string>()
// A deleted experiment (from this tab or another) takes its steps with it: drafts kept for it could
// never be saved, and would keep asking before closing the tab or signing out. (Only a User JSON
// says which experiments there are: signing out empties `user`, and the drafts outlive that.)
watch(() => admin.user?.experiments, (list) => {
  if (!admin.user || !Array.isArray(list)) return
  const ids = new Set(list.map(e => e?.id))
  const selected = admin.experiment?.id
  const lost = new Map<number, number>()
  for (const [key, b] of [...buckets]) {
    if (ids.has(b.experimentId) || b.experimentId === selected) continue
    const n = b.fileMode ? 0 : b.drafts.filter(isDirty).length
    if (n) lost.set(b.experimentId, (lost.get(b.experimentId) || 0) + n)
    buckets.delete(key)
  }
  for (const [id, n] of lost) {
    const name = experimentNames.get(id)
    notify.warning(
      name ? `Unsaved steps of “${name}” were thrown away` : 'Unsaved steps were thrown away',
      `The experiment was deleted, so there is nowhere to save ${n === 1 ? 'the step' : `the ${n} steps`} you had changed in it.`,
    )
  }
  experimentNames.clear()
  for (const e of list) if (e) experimentNames.set(e.id, e.name)
}, { immediate: true })

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', (e) => {
    if (!unsavedDrafts.value.length) return
    e.preventDefault()
    e.returnValue = ''
  })
}

// Switching experiment keeps the old one's drafts; say so, and offer the way back.
// (Not when no experiment is selected any more, as on signing out: there is nothing to switch from.)
watch(() => admin.experiment?.id, (now, before) => {
  if (before == null || now == null || now === before) return
  const b = buckets.get(bucketKey(before, false))
  const n = b ? b.drafts.filter(isDirty).length : 0
  if (!n) return
  const name = admin.user?.experiments?.find(e => e.id === before)?.name || 'the previous experiment'
  toast('warning', `Unsaved steps in “${name}”`, `${n} step${n === 1 ? ' has' : 's have'} unsaved changes. They are kept in this tab; switch back to save them.`, {
    timeout: 12000,
    action: { label: 'Switch back', run: () => { switchBack(before, name) } },
  })
})

/**
 * The toast's way back. Switching rebuilds the shared engine when no session is live there, so ask
 * first while anyone is using it.
 */
async function switchBack (id: number, name: string) {
  if (!ensureConnected()) return
  if (!await confirmSwitch({ title: `Switch to “${name}”?`, action: 'Switching experiments', confirmLabel: 'Switch anyway' })) return
  const asked = Date.now()
  const res = await actions.selectExperiment(id)
  if (!res) {
    // the server's reason when it refused (a refusal resolves null at once)
    const refusal = admin.requestError
    const why = refusal && refusal.request === 'SelectExperiment' && refusal.at >= asked ? refusal.text : 'Check the experiment switcher at the top left.'
    notify.warning('The server didn’t confirm the switch', why)
  }
}
