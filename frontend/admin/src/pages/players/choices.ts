import { watch } from 'vue'
import { actions, graph, nextMessage } from '@/store/admin'
import { choicesOf } from '@/network/players'

export type ChoiceOutcome =
  | { ok: true }
  | { ok: false, error: string }
  | { ok: false, timeout: true }

/** "Caught error: java.lang.X: what happened [frames]" → "what happened". */
export function shortError (error: string): string {
  const s = error.replace(/^\s*Caught error:\s*/i, '').trim()
  const m = /^[\w.$]+(?:Exception|Error):\s*([\s\S]*)$/.exec(s)
  const msg = (m ? m[1] : s).split('\n')[0].trim()
  return msg.length > 220 ? msg.slice(0, 219) + '…' : (msg || s.split('\n')[0])
}

/**
 * Make a choice on a player's behalf (MakeChoice) and find out whether it took.
 *
 * The server answers `{}` on success and also when the choice had already expired (a silent no-op
 * in the engine), so success is judged by the choice leaving the player's screen; an `{error}`
 * reply means the engine failed while running it.
 */
export function submitChoice (playerId: string, choiceUID: string, ms = 6000): Promise<ChoiceOutcome> {
  return new Promise<ChoiceOutcome>((resolve) => {
    let done = false
    const finish = (o: ChoiceOutcome) => {
      if (done) return
      done = true
      stop()
      clearTimeout(timer)
      resolve(o)
    }
    const stillThere = () => choicesOf(graph.nodes.get(playerId)).some(c => c.uid === choiceUID)
    const stop = watch(() => graph.version.value, () => { if (!stillThere()) finish({ ok: true }) })
    const timer = setTimeout(() => finish(stillThere() ? { ok: false, timeout: true } : { ok: true }), ms)
    nextMessage(m => typeof m.error === 'string' && m.error.trim() !== '', ms)
      .then(m => { if (m) finish({ ok: false, error: String(m.error) }) })
    actions.makeChoice(choiceUID)
  })
}
