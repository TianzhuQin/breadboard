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

/**
 * Text as typed-to-confirm compares it: the case, runs of spaces, and kinds of dash and quote don't
 * count (an em dash or a curly quote in a name is hard to type), nor does Unicode normalisation.
 */
export function looseText (s: string): string {
  return (s || '')
    .normalize('NFKC')
    // (a dash of any kind counts as a space, so "Template-Study" can be typed "template study")
    .replace(/[-\u2010-\u2015\u2212]/g, ' ')
    .replace(/[\u2018\u2019\u201b]/g, "'")
    .replace(/[\u201c\u201d\u201f]/g, '"')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
}
