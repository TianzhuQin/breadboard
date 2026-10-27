import { shallowReactive } from 'vue'
import { confirm } from '@/ui/confirm'

/**
 * Work that is not saved yet, wherever a page keeps it (step drafts, content, participant pages),
 * so signing out can ask first. Each kind of work registers a counter once; the counters are read
 * when needed, so they stay the pages' own.
 */

interface Entry { count: () => number, label: string }
const entries = shallowReactive(new Map<string, Entry>())

/**
 * Count `key`'s unsaved work with `count` (a later call with the same key replaces it). `label`
 * names it for the researcher ("Steps"); the key when left out. Returns the call that removes it.
 */
export function registerUnsaved (key: string, count: () => number, label?: string): () => void {
  const entry: Entry = { count, label: label || key }
  entries.set(key, entry)
  return () => { if (entries.get(key) === entry) entries.delete(key) }
}

/** Each kind with unsaved work, and how much. */
function unsavedKinds (): { label: string, n: number }[] {
  const out: { label: string, n: number }[] = []
  for (const e of entries.values()) {
    let n = 0
    // (a page's counter that fails must not stop the researcher from signing out)
    try { n = Number(e.count()) || 0 } catch (err) { n = 0 }
    if (n > 0) out.push({ label: e.label, n })
  }
  return out
}

/** How many unsaved changes there are, all kinds together. */
export function unsavedTotal (): number {
  return unsavedKinds().reduce((sum, k) => sum + k.n, 0)
}

/** Ask before signing out while there is unsaved work. Resolves true when it is fine to sign out. */
export async function confirmSignOut (): Promise<boolean> {
  const kinds = unsavedKinds()
  if (!kinds.length) return true
  const total = kinds.reduce((sum, k) => sum + k.n, 0)
  return confirm({
    title: total === 1 ? 'Sign out with an unsaved change?' : `Sign out with ${total} unsaved changes?`,
    message: `Not saved yet: ${kinds.map(k => `${k.label} (${k.n})`).join(', ')}.\n\nIf you sign out now, these changes may be lost. Cancel to go back and save them first.`,
    confirmLabel: 'Sign out anyway',
    danger: true,
  })
}
