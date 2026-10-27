import { reactive } from 'vue'

export interface ConfirmRequest {
  title: string
  message?: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
  /** The researcher must type this before the button works (for irreversible deletes). */
  typeToConfirm?: string
}

export const confirmState = reactive<{ open: boolean, req: ConfirmRequest | null, resolve: ((ok: boolean) => void) | null }>({
  open: false,
  req: null,
  resolve: null,
})

/** Ask before doing something. Resolves true when the researcher confirms. */
export function confirm (req: ConfirmRequest): Promise<boolean> {
  if (confirmState.resolve) confirmState.resolve(false)
  return new Promise(resolve => {
    confirmState.req = req
    confirmState.resolve = resolve
    confirmState.open = true
  })
}
export function settleConfirm (ok: boolean) {
  const r = confirmState.resolve
  confirmState.open = false
  confirmState.resolve = null
  if (r) r(ok)
}
