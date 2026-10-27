import { shallowReactive } from 'vue'
import { confirm } from '@/ui/confirm'

/**
 * Work that is not saved yet, wherever a page keeps it (step drafts, content, participant pages),
 * so signing out can ask first. Each kind of work registers a counter once; the counters are read
 * when needed, so they stay the pages' own.
 *
 * Signing out replaces the whole console without leaving the page, so a page's own leave-page
 * question (onBeforeRouteLeave) is never asked then: work that only that question protects has to
 * be registered here too, while the page is open.
 */

interface Entry { count: () => number, label: string, running: boolean }
const entries = shallowReactive(new Map<string, Entry>())

/**
 * Count `key`'s unsaved work with `count` (a later call with the same key replaces it). `label`
 * names it for the researcher ("Steps"); the key when left out. With `running`, what it counts are
 * requests still under way rather than unsaved changes ("Mechanical Turk payments"), and signing
 * out says so. Returns the call that removes it.
 */
export function registerUnsaved (key: string, count: () => number, label?: string, opts: { running?: boolean } = {}): () => void {
  const entry: Entry = { count, label: label || key, running: !!opts.running }
  entries.set(key, entry)
  return () => { if (entries.get(key) === entry) entries.delete(key) }
}

type Kind = { label: string, n: number, running: boolean }

/** Each kind with unsaved work (or requests under way), and how much. */
function unsavedKinds (): Kind[] {
  const out: Kind[] = []
  for (const e of entries.values()) {
    let n = 0
    // (a page's counter that fails must not stop the researcher from signing out)
    try { n = Number(e.count()) || 0 } catch (err) { n = 0 }
    if (n > 0) out.push({ label: e.label, n, running: e.running })
  }
  return out
}

/** How many unsaved changes there are, all kinds together (requests under way are not changes). */
export function unsavedTotal (): number {
  return unsavedKinds().filter(k => !k.running).reduce((sum, k) => sum + k.n, 0)
}

/**
 * Ask before signing out while there is unsaved work, or requests still running. Resolves true
 * when it is fine to sign out.
 */
export async function confirmSignOut (): Promise<boolean> {
  const kinds = unsavedKinds()
  if (!kinds.length) return true
  const unsaved = kinds.filter(k => !k.running)
  const running = kinds.filter(k => k.running)
  const list = (ks: Kind[]) => ks.map(k => `${k.label} (${k.n})`).join(', ')
  const total = unsaved.reduce((sum, k) => sum + k.n, 0)
  const said: string[] = []
  const advice: string[] = []
  if (unsaved.length) {
    said.push(`Not saved yet: ${list(unsaved)}.`)
    advice.push('If you sign out now, these changes may be lost. Cancel to go back and save them first.')
  }
  if (running.length) {
    said.push(`Still running: ${list(running)}.`)
    advice.push('If you sign out now, the ones not sent yet fail, and you won’t see which ones went through. Cancel to let them finish first.')
  }
  return confirm({
    title: !unsaved.length
      ? 'Sign out while requests are still running?'
      : total === 1 ? 'Sign out with an unsaved change?' : `Sign out with ${total} unsaved changes?`,
    message: `${said.join(' ')}\n\n${advice.join(' ')}`,
    confirmLabel: 'Sign out anyway',
    danger: true,
  })
}
