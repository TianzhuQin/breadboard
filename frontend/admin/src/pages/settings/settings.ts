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

// ---- researcher accounts (controllers.ResearchersController) ------------------------------------

export interface Researcher {
  email: string
  name: string
  /** the account signed in here */
  you: boolean
}

function researcherList (res: any): Researcher[] {
  const rows = res && Array.isArray(res.researchers) ? res.researchers : []
  return rows
    .filter((r: any) => r && typeof r.email === 'string')
    .map((r: any) => ({ email: r.email, name: typeof r.name === 'string' && r.name ? r.name : r.email, you: !!r.you }))
}

/** Looks like an email address: something@something.something, no spaces (as User.looksLikeEmail). */
export function looksLikeEmail (email: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((email || '').trim())
}

export const researchers = {
  async list (): Promise<Researcher[]> {
    return researcherList(await http.get('/researchers', { quiet401: true }))
  },
  /** Adds an account; resolves to the list and the account's first password (shown once). */
  async add (email: string): Promise<{ list: Researcher[], email: string, password: string }> {
    const res = await http.post<any>('/researchers', { email: email.trim() })
    return { list: researcherList(res), email: res.email, password: res.password }
  },
  async remove (email: string): Promise<Researcher[]> {
    return researcherList(await http.post('/researchers/remove', { email }))
  },
  /** Gives another account a new password; resolves to it (shown once). */
  async resetPassword (email: string): Promise<{ email: string, password: string }> {
    const res = await http.post<any>('/researchers/password', { email })
    return { email: res.email, password: res.password }
  },
}

// ---- database backups (controllers.BackupsController) -------------------------------------------

export interface Backup {
  name: string
  size: number
  createdAt: string
}
export interface BackupStatus {
  enabled: boolean
  folder: string
  keep: number
  everyHours: number
  problem: string | null
  backups: Backup[]
}

function backupStatus (res: any): BackupStatus {
  const rows = res && Array.isArray(res.backups) ? res.backups : []
  return {
    enabled: !!res?.enabled,
    folder: typeof res?.folder === 'string' ? res.folder : '',
    keep: Number(res?.keep) || 0,
    everyHours: Number(res?.everyHours) || 0,
    problem: typeof res?.problem === 'string' && res.problem ? res.problem : null,
    backups: rows.filter((b: any) => b && typeof b.name === 'string')
      .map((b: any) => ({ name: b.name, size: Number(b.size) || 0, createdAt: String(b.createdAt || '') })),
  }
}

export const backups = {
  async status (): Promise<BackupStatus> {
    return backupStatus(await http.get('/backups', { quiet401: true }))
  },
  async backUpNow (): Promise<BackupStatus> {
    return backupStatus(await http.post('/backups'))
  },
  url: (name: string) => `/backups/${encodeURIComponent(name)}`,
}

/** 1536 → "1.5 KB", 5_400_000 → "5.1 MB" */
export function fileSize (bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
