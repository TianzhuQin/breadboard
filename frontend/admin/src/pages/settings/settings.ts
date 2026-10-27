import { http } from '@/lib/http'

export const PASSWORD_MIN = 8

export interface PasswordChange {
  current: string
  next: string
  confirm: string
}
export type PasswordErrors = Partial<Record<keyof PasswordChange, string>>

/** Checks a password change before it is sent (the old dialog posted even when its checks failed). */
export function validatePasswordChange (p: PasswordChange): PasswordErrors {
  const errors: PasswordErrors = {}
  if (!p.current) errors.current = 'Enter your current password.'
  if (!p.next) errors.next = 'Enter a new password.'
  else if (p.next.length < PASSWORD_MIN) errors.next = `Use at least ${PASSWORD_MIN} characters.`
  else if (p.next.trim() !== p.next) errors.next = 'Don\'t start or end the password with a space.'
  else if (p.current && p.next === p.current) errors.next = 'Choose a password that is different from your current one.'
  if (!errors.next && p.confirm !== p.next) errors.confirm = 'This doesn\'t match the new password.'
  return errors
}

/** The server's messages (models/UserSettings.java), in plainer words where it helps. */
export function friendlyPasswordError (msg: string | undefined | null): { field?: keyof PasswordChange, text: string } {
  const m = (msg || '').trim()
  if (/current password doesn'?t match/i.test(m)) return { field: 'current', text: 'That isn\'t your current password.' }
  if (/current password is missing/i.test(m)) return { field: 'current', text: 'Enter your current password.' }
  if (/new password is missing/i.test(m)) return { field: 'next', text: 'Enter a new password.' }
  if (/confirm password/i.test(m)) return { field: 'confirm', text: 'This doesn\'t match the new password.' }
  if (/user is not found|email can'?t be empty/i.test(m)) return { text: 'The server couldn\'t find your account. Sign out and in again, then retry.' }
  return { text: m || 'The password was not changed.' }
}

/**
 * POST /saveUserSettings, form-encoded, as models.UserSettings expects. Resolves to the server's
 * answer. A changed password gives the account a new uid (`uid`), which the console must use from
 * then on: the server refuses requests that carry the old one.
 */
export async function changePassword (email: string, p: PasswordChange): Promise<{ success: boolean, error?: string, uid?: string }> {
  const res = await http.post<{ success?: boolean, error?: string, uid?: string }>('/saveUserSettings', {
    email,
    currentPassword: p.current,
    newPassword: p.next,
    confirmPassword: p.confirm,
  }, { form: true })
  const uid = res && typeof res.uid === 'string' && res.uid ? res.uid : undefined
  return { success: !!(res && res.success), error: res?.error, ...(uid ? { uid } : {}) }
}
