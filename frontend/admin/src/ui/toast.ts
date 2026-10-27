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

/** A short message in the corner. Errors stay longer, so there is time to read them. */
export function toast (kind: ToastKind, title: string, detail?: string, opts: { timeout?: number, action?: Toast['action'] } = {}) {
  const id = ++next
  const timeout = opts.timeout ?? (kind === 'error' ? 9000 : 3500)
  toasts.push({ id, kind, title, detail, timeout, action: opts.action })
  if (timeout > 0) setTimeout(() => dismiss(id), timeout)
  return id
}
export function dismiss (id: number) {
  const i = toasts.findIndex(t => t.id === id)
  if (i >= 0) toasts.splice(i, 1)
}
export const notify = {
  success: (title: string, detail?: string) => toast('success', title, detail),
  error: (title: string, detail?: string) => toast('error', title, detail),
  info: (title: string, detail?: string) => toast('info', title, detail),
  warning: (title: string, detail?: string) => toast('warning', title, detail),
}
