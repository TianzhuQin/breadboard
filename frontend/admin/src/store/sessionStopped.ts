/** What a sessionStopped event says about the account's action, in words. */
export function sessionStoppedText (by: string, reason: string, me: string): string {
  const who = me && by.toLowerCase() === me.toLowerCase() ? 'You' : by
  return (HOW[reason] || HOW.stopSession)(who)
}

const HOW: Record<string, (who: string) => string> = {
  stopSession: who => `${who} stopped this session.`,
  reloadEngine: who => `${who} reloaded the engine, which stopped this session.`,
  newSession: who => `${who} created a new session, which stopped this one.`,
  otherSession: who => `${who} resumed or selected another session, which stopped this one.`,
  autopilot: who => `${who} let the autopilot take over the engine for its session, which stopped this one.`,
}
