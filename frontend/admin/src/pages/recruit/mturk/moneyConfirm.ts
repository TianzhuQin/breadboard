import { reactive } from 'vue'

export interface MoneyLine {
  label: string
  amount?: number | null
  note?: string
}

export interface MoneyRequest {
  title: string
  message?: string
  /** what is being paid, one line each (long lists are shortened) */
  lines?: MoneyLine[]
  total?: number | null
  totalLabel?: string
  /** small print, e.g. MTurk's fee */
  footnote?: string
  confirmLabel: string
}

export const moneyState = reactive<{ open: boolean, req: MoneyRequest | null, resolve: ((ok: boolean) => void) | null }>({
  open: false,
  req: null,
  resolve: null,
})

/**
 * Ask before an action that spends money (approve, bonus, payment HITs, posting a HIT to
 * production). The dialog (MoneyConfirmDialog) always shows whether this is the sandbox or
 * production site, the total, and that it cannot be undone.
 */
export function confirmMoney (req: MoneyRequest): Promise<boolean> {
  if (moneyState.resolve) moneyState.resolve(false)
  return new Promise(resolve => {
    moneyState.req = req
    moneyState.resolve = resolve
    moneyState.open = true
  })
}

export function settleMoney (ok: boolean) {
  const r = moneyState.resolve
  moneyState.open = false
  moneyState.resolve = null
  if (r) r(ok)
}
