import type { FeedEvent } from './events'
import { sessionStoppedText } from '@/store/sessionStopped'

/**
 * Who stopped a session, and how, in words for the Live session page, from what its data says last
 * about how it ended: its newest sessionStopped, with `by` (the account) and `reason` (what they did:
 * the server's ScriptBoard.STOPPED_BY_…). The researcher whose session another account's action
 * ended (a Reload engine, a new session, another session resumed) sees who did it and how; the page
 * only said that it was stopped. `me` is this console's account ("You …"). '' when the data doesn't
 * say: no such event, one without `by` (from before 3.1), or one that a later end or a Resume
 * (sessionResumed, sessionInterrupted, …) has made old.
 */
export function stoppedByText (events: FeedEvent[], me: string): string {
  // (newest first, as the feed is)
  const last = events.find(e => ENDS.has(e.name))
  if (!last || last.name !== 'sessionStopped') return ''
  const by = typeof last.data.by === 'string' ? last.data.by.trim() : ''
  if (!by) return ''
  const reason = typeof last.data.reason === 'string' ? last.data.reason : ''
  return sessionStoppedText(by, reason, me)
}

// The events after which a session's earlier sessionStopped no longer says how it ended now.
const ENDS = new Set([
  'sessionStopped', 'sessionResumed', 'sessionInterrupted', 'serverStopping', 'engineReloaded',
  'sessionFailed', 'sessionStalled', 'sessionTooLong', 'stepsFailedToLoad',
])
