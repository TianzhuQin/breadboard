import { shallowRef, watch } from 'vue'
import { actions, admin, graph } from '@/store/admin'
import { toast } from '@/ui/toast'
import type { TieEditResult } from '@/store/types'
import { tieBetween } from './players'

/**
 * Adding and cutting ties as the researcher (EditTie → edgeEdits.adminEdit on the server).
 *
 * Every edit shows as a dashed "pending" line until the server answers, then a toast says what
 * happened: on success with an Undo (the opposite edit), on refusal with the server's reason.
 * Successful edits are remembered so Ctrl/Cmd+Z can take them back one by one.
 */

export interface TieEdit { op: 'add' | 'remove', a: string, b: string }

// shallow: the edits are compared by identity, and a deep ref would hand back proxies
/** Edits sent and not yet answered (drawn as dashed lines on the network). */
export const pendingEdits = shallowRef<TieEdit[]>([])
/** Successful edits this session, newest last (for undo). */
export const editHistory = shallowRef<TieEdit[]>([])

const HISTORY_CAP = 50

/**
 * Bumped whenever the network is rebuilt (another session, an engine reload): edits made before
 * belong to a network that is gone, so they can't be undone any more.
 */
let generation = 0

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '')
/** The undo shortcut as this computer writes it. */
export const undoShortcut = isMac ? '⌘Z' : 'Ctrl+Z'

export function inverseOf (e: TieEdit): TieEdit {
  return { op: e.op === 'add' ? 'remove' : 'add', a: e.a, b: e.b }
}
function samePair (e: TieEdit, a: string, b: string) {
  return (e.a === a && e.b === b) || (e.a === b && e.b === a)
}
export function isPending (a: string, b: string) {
  return pendingEdits.value.some(e => samePair(e, a, b))
}

/** The engine's reasons, in the researcher's words where they are technical. */
export function friendlyReason (reason?: string | null): string {
  if (!reason) return 'The server refused the change without saying why.'
  if (/needs two different existing players/i.test(reason)) return 'One of them is no longer in the session.'
  if (/engine isn't loaded/i.test(reason)) return 'The experiment\'s engine isn\'t loaded yet. Start or reload the session, then try again.'
  if (/did not answer/i.test(reason)) return 'The server did not answer. Check the connection (top right) and try again.'
  return reason
}

export interface EditOptions {
  /** How to call a player in messages (their label); defaults to the id. */
  name?: (id: string) => string
  /** Offer Undo in the success toast (default true). */
  undoable?: boolean
  /** Remember it for Ctrl/Cmd+Z (default true). */
  record?: boolean
  /** This edit takes back an earlier one (changes the wording). */
  undoing?: boolean
}

function describe (e: TieEdit, name: (id: string) => string, undoing: boolean) {
  const A = name(e.a)
  const B = name(e.b)
  if (undoing) return e.op === 'add' ? `Put back the tie between ${A} and ${B}` : `Took back the tie between ${A} and ${B}`
  return e.op === 'add' ? `Tied ${A} and ${B}` : `Cut the tie between ${A} and ${B}`
}
function describeFailure (e: TieEdit, name: (id: string) => string) {
  return e.op === 'add' ? `Couldn't tie ${name(e.a)} and ${name(e.b)}` : `Couldn't cut the tie between ${name(e.a)} and ${name(e.b)}`
}

/**
 * Add or cut a tie. Resolves with the outcome (never rejects); the toast has already told the
 * researcher what happened.
 */
export async function editTie (op: 'add' | 'remove', a: string, b: string, opts: EditOptions = {}): Promise<TieEditResult> {
  const name = opts.name || ((id: string) => id)
  const edit: TieEdit = { op, a, b }
  const refuse = (reason: string, kind: 'info' | 'error' = 'info'): TieEditResult => {
    toast(kind, describeFailure(edit, name), reason)
    return { ok: false, op, a, b, reason }
  }
  if (!a || !b || a === b) return refuse('Pick two different people.')
  if (isPending(a, b)) return refuse('That tie is already being changed. Wait a moment.')
  if (!graph.nodes.has(a) || !graph.nodes.has(b)) return refuse('One of them is no longer in the session.', 'error')
  // only "connected" edges are ties; the server neither adds nor cuts edges of other kinds
  const tied = !!tieBetween(graph, a, b)
  if (op === 'add' && tied) return refuse(`${name(a)} and ${name(b)} are already tied.`)
  if (op === 'remove' && !tied) {
    const other = graph.linkBetween(a, b)
    return refuse(other?.label
      ? `${name(a)} and ${name(b)} are joined by a “${other.label}” link, not a tie. Only ties can be cut here.`
      : `${name(a)} and ${name(b)} aren't tied.`)
  }

  const gen = generation
  pendingEdits.value = [...pendingEdits.value, edit]
  let result: TieEditResult
  try {
    result = await actions.editTie(op, a, b)
  } catch (e: any) {
    result = { ok: false, op, a, b, reason: e?.message || String(e) }
  } finally {
    pendingEdits.value = pendingEdits.value.filter(x => x !== edit)
  }

  if (result.ok) {
    // an answer that arrives after the network was rebuilt can't be undone on the new one
    const current = gen === generation
    if (opts.record !== false && current) {
      editHistory.value = [...editHistory.value, edit].slice(-HISTORY_CAP)
    }
    // Undo goes through the history, so only remembered edits offer it
    const undoable = opts.undoable !== false && opts.record !== false && current
    toast('success', describe(edit, name, !!opts.undoing), undoable ? `${undoShortcut} also undoes it.` : undefined, {
      timeout: undoable ? 8000 : 3500,
      action: undoable ? { label: 'Undo', run: () => undoFromToast(edit, gen, opts.name) } : undefined,
    })
  } else {
    toast('error', describeFailure(edit, name), friendlyReason(result.reason))
  }
  return result
}

function undoFromToast (edit: TieEdit, gen: number, name?: (id: string) => string) {
  if (gen !== generation) {
    toast('info', 'Nothing to undo', 'The network was rebuilt since, so that change no longer applies.')
    return
  }
  void undoEdit(edit, name)
}

/**
 * Take back an earlier edit (the opposite edit, no further undo). Only edits still remembered can
 * be taken back: one already undone (Ctrl/Cmd+Z, or its toast) or made on a network that has since
 * been rebuilt is left alone, so a late click on an old toast can't change a newer tie.
 */
export function undoEdit (edit: TieEdit, name?: (id: string) => string): Promise<TieEditResult> {
  const i = editHistory.value.lastIndexOf(edit)
  if (i < 0) {
    const reason = 'That change was already taken back.'
    toast('info', 'Nothing to undo', reason)
    return Promise.resolve({ ok: false, op: edit.op, a: edit.a, b: edit.b, reason })
  }
  editHistory.value = editHistory.value.filter((_, j) => j !== i)
  const inv = inverseOf(edit)
  return editTie(inv.op, inv.a, inv.b, { name, undoable: false, record: false, undoing: true })
}

/** Ctrl/Cmd+Z: take back the newest edit still remembered. */
export function undoLast (name?: (id: string) => string): Promise<TieEditResult> | null {
  const last = editHistory.value[editHistory.value.length - 1]
  if (!last) {
    toast('info', 'Nothing to undo', 'Ties you add or cut in this session can be taken back here.')
    return null
  }
  return undoEdit(last, name)
}

function forgetEdits () {
  generation++
  editHistory.value = []
  pendingEdits.value = []
}

// Another session's edits can't be undone here. (Sync: an answer handled in the same turn as the
// change must already see the new generation.)
watch(() => admin.instance?.id ?? null, (now, before) => {
  if (now !== before) forgetEdits()
}, { flush: 'sync' })
// Nor can edits made before the engine reloaded: it empties the network and builds a new one
// (often with the same people), which the researcher never edited.
watch(() => admin.engine, (state) => {
  if (state === 'loading') forgetEdits()
}, { flush: 'sync' })
