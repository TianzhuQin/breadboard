import { actions, graph, nextMessage } from '@/store/admin'
import { choicesOf } from '@/network/players'

export type ChoiceOutcome =
  | { ok: true }
  /** The choice had already left their screen; `sent` says whether MakeChoice went out at all. */
  | { ok: false, gone: true, sent: boolean }
  | { ok: false, error: string }
  /** No answer in time; `onScreen` says whether the choice is still on their screen. */
  | { ok: false, timeout: true, onScreen: boolean }
  /** Not connected to the server, so nothing was sent. */
  | { ok: false, offline: true }

/** "Caught error: java.lang.X: what happened [frames]" → "what happened". */
export function shortError (error: string): string {
  const s = error.replace(/^\s*Caught error:\s*/i, '').trim()
  const m = /^[\w.$]+(?:Exception|Error):\s*([\s\S]*)$/.exec(s)
  const msg = (m ? m[1] : s).split('\n')[0].trim()
  return msg.length > 220 ? msg.slice(0, 219) + '…' : (msg || s.split('\n')[0])
}

const hasError = (m: any) => typeof m.error === 'string' && m.error.trim() !== ''
/**
 * The server's answer to this MakeChoice: tagged {"request": "MakeChoice"} with "choice": "made" or
 * "gone", or with the error. When it names the choice ("choiceUID"), an answer about another choice
 * (one chosen from another page meanwhile) is not taken for this one's.
 */
const isReplyTo = (choiceUID: string) => (m: any) => m.request === 'MakeChoice' && (m.choiceUID === undefined || m.choiceUID === choiceUID)

/**
 * Make a choice on a player's behalf (MakeChoice) and find out whether it took.
 *
 * Nothing is sent when the choice has already left their screen (they answered, or it expired,
 * while the researcher was confirming): the engine would ignore it without a word. Nor when the
 * connection is down: that is said at once.
 *
 * Only the server's tagged answer decides. Untagged engine errors that arrive meanwhile (a timer,
 * a step file saved in file mode) are someone else's, and the choice leaving their screen proves
 * nothing either: the participant may have answered it themselves. With no answer in time, that is
 * what it says.
 */
export function submitChoice (playerId: string, choiceUID: string, ms = 6000): Promise<ChoiceOutcome> {
  const stillThere = () => choicesOf(graph.nodes.get(playerId)).some(c => c.uid === choiceUID)
  if (!stillThere()) return Promise.resolve({ ok: false, gone: true, sent: false })
  // listen before sending so the answer can't slip past
  const answer = nextMessage(isReplyTo(choiceUID), ms)
  if (!actions.makeChoice(choiceUID)) return Promise.resolve({ ok: false, offline: true })
  return answer.then((m): ChoiceOutcome => {
    if (!m) return { ok: false, timeout: true, onScreen: stillThere() }
    if (hasError(m)) return { ok: false, error: String(m.error) }
    if (m.choice === 'gone') return { ok: false, gone: true, sent: true }
    return { ok: true }
  })
}
