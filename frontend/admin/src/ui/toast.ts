import { reactive } from 'vue'

export type ToastKind = 'success' | 'error' | 'info' | 'warning'
export interface Toast {
  id: number
  kind: ToastKind
  title: string
  detail?: string
  timeout: number
  action?: { label: string, run: () => void }
}

let next = 0
export const toasts = reactive<Toast[]>([])

/** A running dismissal: what is left of it, and why it is paused (pointer over it, focus in it). */
interface Timer { remaining: number, startedAt: number, handle: ReturnType<typeof setTimeout> | null, holds: Set<string> }
const timers = new Map<number, Timer>()

/** Toasts with a button (Undo, Resume…) stay at least this long, so there is time to reach it. */
const ACTION_MIN_MS = 10000

/**
 * A short message in the corner. Errors stay longer, so there is time to read them. A toast does
 * not go away while the pointer is over it or focus is in it (see holdToast).
 */
export function toast (kind: ToastKind, title: string, detail?: string, opts: { timeout?: number, action?: Toast['action'] } = {}) {
  const id = ++next
  let timeout = opts.timeout ?? (kind === 'error' ? 9000 : 3500)
  if (opts.action && timeout > 0) timeout = Math.max(timeout, ACTION_MIN_MS)
  toasts.push({ id, kind, title, detail, timeout, action: opts.action })
  if (timeout > 0) {
    const t: Timer = { remaining: timeout, startedAt: Date.now(), handle: null, holds: new Set() }
    timers.set(id, t)
    t.handle = setTimeout(() => dismiss(id), timeout)
  }
  return id
}

/** Pause a toast's dismissal for `reason` until releaseToast(id, reason). */
export function holdToast (id: number, reason: string) {
  const t = timers.get(id)
  if (!t) return
  if (!t.holds.size && t.handle) {
    clearTimeout(t.handle)
    t.handle = null
    t.remaining = Math.max(0, t.remaining - (Date.now() - t.startedAt))
  }
  t.holds.add(reason)
}
/** Let the dismissal go on once nothing holds it; at least a moment is left to move away. */
export function releaseToast (id: number, reason: string) {
  const t = timers.get(id)
  if (!t || !t.holds.delete(reason) || t.holds.size) return
  t.remaining = Math.max(t.remaining, 1500)
  t.startedAt = Date.now()
  t.handle = setTimeout(() => dismiss(id), t.remaining)
}

export function dismiss (id: number) {
  const t = timers.get(id)
  if (t && t.handle) clearTimeout(t.handle)
  timers.delete(id)
  const i = toasts.findIndex(t => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}
export const notify = {
  success: (title: string, detail?: string) => toast('success', title, detail),
  error: (title: string, detail?: string) => toast('error', title, detail),
  info: (title: string, detail?: string) => toast('info', title, detail),
  warning: (title: string, detail?: string) => toast('warning', title, detail),
}
