import { watch } from 'vue'
import { actions, graph, nextMessage } from '@/store/admin'
import { choicesOf } from '@/network/players'

export type ChoiceOutcome =
  | { ok: true }
  /** The choice had already left their screen; `sent` says whether MakeChoice went out at all. */
  | { ok: false, gone: true, sent: boolean }
  | { ok: false, error: string }
  | { ok: false, timeout: true }
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
/** The server's own answer to MakeChoice, tagged {"request": "MakeChoice", "choice": "made" | "gone"} or with an error. */
const isTaggedReply = (m: any) => m.request === 'MakeChoice'
/** An older server answers MakeChoice with an empty {} when it ran (or ignored) the choice. */
const isPlainAck = (m: any) => m.constructor === Object && Object.keys(m).length === 0
/**
 * An engine error that names neither a request nor where it came from (OnJoinStep, OnLeaveStep):
 * MakeChoice's failure on an older server, or anything else.
 */
const isUntaggedError = (m: any) => hasError(m) && typeof m.request !== 'string' && typeof m.source !== 'string' &&
  !(typeof m.output === 'string' && m.output.trim())

/**
 * Make a choice on a player's behalf (MakeChoice) and find out whether it took.
 *
 * Nothing is sent when the choice has already left their screen (they answered, or it expired,
 * while the researcher was confirming): the engine would ignore it without a word. Nor when the
 * connection is down: that is said at once.
 *
 * The server's answer decides. Newer servers tag it {"request": "MakeChoice"} with "choice":
 * "made" or "gone", or with the error, and the first tagged answer is final. Older servers answer
 * an empty {} when the choice ran and an untagged {error} instead when it failed; other engine
 * errors (a late joiner's OnJoinStep, a timer) can arrive meanwhile, so an untagged error only
 * counts when no {} follows it soon. After a {}, the choice leaving their screen is the sign it
 * was made.
 */
export function submitChoice (playerId: string, choiceUID: string, ms = 6000): Promise<ChoiceOutcome> {
  const stillThere = () => choicesOf(graph.nodes.get(playerId)).some(c => c.uid === choiceUID)
  if (!stillThere()) return Promise.resolve({ ok: false, gone: true, sent: false })
  const grace = Math.min(1500, ms / 4)
  return new Promise<ChoiceOutcome>((resolve) => {
    let done = false
    let acked = false
    let firstError: string | null = null
    let errorTimer: any = null
    const finish = (o: ChoiceOutcome) => {
      if (done) return
      done = true
      stop()
      clearTimeout(timer)
      if (errorTimer) clearTimeout(errorTimer)
      resolve(o)
    }
    const stop = watch(() => graph.version.value, () => { if (acked && !stillThere()) finish({ ok: true }) })
    const timer = setTimeout(() => {
      if (firstError !== null) finish({ ok: false, error: firstError })
      else finish(stillThere() ? { ok: false, timeout: true } : { ok: true })
    }, ms)

    // listen before sending so the answer can't slip past
    nextMessage(isTaggedReply, ms).then(m => {
      if (!m) return
      if (hasError(m)) finish({ ok: false, error: String(m.error) })
      else if (m.choice === 'gone') finish({ ok: false, gone: true, sent: true })
      else finish({ ok: true })
    })
    nextMessage(isPlainAck, ms).then(m => {
      if (!m || done) return
      // the server's answer came without an error: any error before it was someone else's
      acked = true
      firstError = null
      if (errorTimer) { clearTimeout(errorTimer); errorTimer = null }
      if (!stillThere()) finish({ ok: true })
    })
    nextMessage(isUntaggedError, ms).then(m => {
      if (!m || done || acked) return
      firstError = String(m.error)
      errorTimer = setTimeout(() => { if (!acked && firstError !== null) finish({ ok: false, error: firstError }) }, grace)
    })
    if (!actions.makeChoice(choiceUID)) finish({ ok: false, offline: true })
  })
}
