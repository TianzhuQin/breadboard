/**
 * Per-browser preferences for the Recruitment page (last tab, MTurk mode, Create HIT defaults).
 * localStorage can be missing or throw (private windows, blocked site data), so every access is
 * guarded and the page works without it.
 */
export function readStore (key: string): string | null {
  try { return localStorage.getItem(key) } catch (e) { return null }
}

export function writeStore (key: string, value: string | null) {
  try {
    if (value == null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch (e) { /* not available: the preference just isn't remembered */ }
}

export function readJson<T> (key: string): T | null {
  const raw = readStore(key)
  if (!raw) return null
  try { return JSON.parse(raw) as T } catch (e) { return null }
}

export function writeJson (key: string, value: unknown) {
  writeStore(key, value == null ? null : JSON.stringify(value))
}
