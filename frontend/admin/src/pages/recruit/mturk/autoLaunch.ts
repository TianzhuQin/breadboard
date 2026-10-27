import { connection } from '@/store/admin'
import { rememberSchedule } from './hitForm'

/**
 * Ask the server to close the session to newcomers after the HIT's lifetime and to run
 * initStep.start() after lifetime + tutorial time (SubmitAMTTask → HitCreated in ScriptBoard).
 * The store has no action for this, so it is sent here. The server parses both values as whole
 * numbers and only answers with a line in the output panel.
 */
export function scheduleAutoStart (hitId: string, lifetimeSeconds: number, tutorialSeconds: number) {
  const lifetimeInSeconds = Math.max(0, Math.round(lifetimeSeconds))
  const tutorialTime = Math.max(0, Math.round(tutorialSeconds))
  connection.send('SubmitAMTTask', { lifetimeInSeconds, tutorialTime })
  const now = Date.now()
  rememberSchedule({ hitId, closesAt: now + lifetimeInSeconds * 1000, startsAt: now + (lifetimeInSeconds + tutorialTime) * 1000 })
}
