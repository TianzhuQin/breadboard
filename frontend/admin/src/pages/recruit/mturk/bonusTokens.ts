import { readJson, writeJson } from '../storage'

/**
 * MTurk pays a bonus only once per request token (uniqueRequestToken, for 24 hours), so a bonus
 * that got no clear answer can be sent again without paying twice. Each assignment gets one token
 * when the researcher first confirms its bonus, and every later try reuses it, even after a success:
 * the console pays one bonus per assignment, and MTurk's list of payments can lag behind, so a
 * second run on the same worker must be caught as a repeat. Tokens are kept in this browser (a
 * reload must not lose them) for those 24 hours.
 */

const KEY = 'bb.recruit.mturk.bonusTokens'
/** MTurk remembers a token for 24 hours; after that it would pay again anyway. */
const TTL_MS = 24 * 3600 * 1000
const MAX_KEPT = 500

interface Kept { token: string, at: number }
const memory = new Map<string, Kept>()

/** 1-64 letters, digits, "_" or "-": what MTurk (and the server) accept. */
export const TOKEN_RE = /^[A-Za-z0-9_-]{1,64}$/

/** A new random token. randomUUID only exists on https (and localhost), so there is a fallback. */
export function newRequestToken (): string {
  let raw = ''
  const c: Crypto | undefined = (globalThis as any).crypto
  try {
    if (c && typeof c.randomUUID === 'function') raw = c.randomUUID()
    else if (c && typeof c.getRandomValues === 'function') {
      raw = Array.from(c.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('')
    }
  } catch (e) { raw = '' }
  if (!raw) raw = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
  return raw.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64) || `t${Date.now()}`
}

function readAll (now: number): Record<string, Kept> {
  const stored = readJson<Record<string, Kept>>(KEY) || {}
  const all: Record<string, Kept> = {}
  for (const [id, k] of Object.entries(stored)) {
    if (k && typeof k.token === 'string' && TOKEN_RE.test(k.token) && typeof k.at === 'number' && now - k.at < TTL_MS) all[id] = k
  }
  for (const [id, k] of memory) if (now - k.at < TTL_MS && !all[id]) all[id] = k
  return all
}

function writeAll (all: Record<string, Kept>) {
  const recent = Object.entries(all).sort((a, b) => b[1].at - a[1].at).slice(0, MAX_KEPT)
  writeJson(KEY, Object.fromEntries(recent))
}

/** The token for this assignment's bonus: the one from an earlier try (within 24 hours), or a new one. */
export function bonusToken (assignmentId: string, now = Date.now()): string {
  const all = readAll(now)
  const kept = all[assignmentId]
  if (kept) return kept.token
  const fresh = { token: newRequestToken(), at: now }
  memory.set(assignmentId, fresh)
  all[assignmentId] = fresh
  writeAll(all)
  return fresh.token
}
