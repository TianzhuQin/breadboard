import { connection, nextMessage, NOT_CONNECTED } from '@/store/admin'
import { rememberSchedule } from './hitForm'

/** Whether the server took the automatic start; `reason` says why not, in words. */
export type AutoStartResult = { ok: true } | { ok: false, reason: string }

/**
 * Ask the server to close the session to newcomers after the HIT's lifetime and to run
 * initStep.start() after lifetime + tutorial time (SubmitAMTTask → HitCreated in ScriptBoard).
 * The store has no action for this, so it is sent here. The server parses both values as whole
 * numbers and answers with a line in the output panel, or refuses ({"error", "request":
 * "SubmitAMTTask"}, e.g. while the engine runs another researcher's experiment).
 * The schedule is remembered (for the HITs list) only when it was sent and not refused.
 */
export async function scheduleAutoStart (hitId: string, lifetimeSeconds: number, tutorialSeconds: number, ms = 10000): Promise<AutoStartResult> {
  const lifetimeInSeconds = Math.max(0, Math.round(lifetimeSeconds))
  const tutorialTime = Math.max(0, Math.round(tutorialSeconds))
  if (!connection.send('SubmitAMTTask', { lifetimeInSeconds, tutorialTime })) {
    return { ok: false, reason: `${NOT_CONNECTED}, so the server wasn't asked.` }
  }
  const sentAt = Date.now()
  const answer = await nextMessage(m => (m.request === 'SubmitAMTTask' && typeof m.error === 'string') ||
    (typeof m.output === 'string' && /^AMT HIT created/.test(m.output)), ms)
  if (answer && typeof answer.error === 'string') return { ok: false, reason: answer.error.trim() || 'The server refused it.' }
  // no answer in time: it was sent, and the output panel shows the server's line when it comes
  rememberSchedule({ hitId, closesAt: sentAt + lifetimeInSeconds * 1000, startsAt: sentAt + (lifetimeInSeconds + tutorialTime) * 1000 })
  return { ok: true }
}
