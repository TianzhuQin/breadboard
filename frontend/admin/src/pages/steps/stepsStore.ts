import { computed, reactive, watch } from 'vue'
import { http, HttpError } from '@/lib/http'
import { admin, actions } from '@/store/admin'
import type { Step } from '@/store/types'
import { toast } from '@/ui'
import { expectReply, lastOutputId } from '../console/engineReplies'
import {
  copyName, isDirty, isNew, mergeServerSteps, newDraft, uniqueName, validateName,
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
  const d = newDraft(copyName(from.name, b.drafts), 'empty', from.source)
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

/** Save one step (POST /steps/:id, -1 for a new one). Resolves whether it worked; errors go on the draft. */
export async function saveDraft (b: StepsBucket, d: StepDraft): Promise<boolean> {
  if (b.fileMode || d.status === 'saving' || d.status === 'deleting') return false
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

/** Run a step's code in the live engine (SendStep) and keep the engine's answer on the draft. */
export function runDraft (d: StepDraft) {
  const source = d.source
  const afterId = lastOutputId()
  actions.runStep({ id: d.id, name: d.name, source })
  d.run = { status: 'pending', values: [], error: null, at: Date.now() }
  const run = d.run
  expectReply(source, afterId).then(reply => {
    if (d.run !== run) return
    if (!reply) {
      run.status = 'noreply'
      return
    }
    run.status = reply.ok ? 'ok' : 'error'
    run.values = reply.values
    run.error = reply.error
    if (reply.ok && d.source === source) d.engineBehind = false
  })
}

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

if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', (e) => {
    if (!unsavedDrafts.value.length) return
    e.preventDefault()
    e.returnValue = ''
  })
}

// Switching experiment keeps the old one's drafts; say so, and offer the way back.
watch(() => admin.experiment?.id, (now, before) => {
  if (before == null || now === before) return
  const b = buckets.get(bucketKey(before, false))
  const n = b ? b.drafts.filter(isDirty).length : 0
  if (!n) return
  const name = admin.user?.experiments?.find(e => e.id === before)?.name || 'the previous experiment'
  toast('warning', `Unsaved steps in “${name}”`, `${n} step${n === 1 ? ' has' : 's have'} unsaved changes. They are kept in this tab; switch back to save them.`, {
    timeout: 12000,
    action: { label: 'Switch back', run: () => { actions.selectExperiment(before) } },
  })
})
