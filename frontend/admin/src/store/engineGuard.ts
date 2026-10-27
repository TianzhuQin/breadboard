import { http } from '@/lib/http'
import { confirm } from '@/ui/confirm'
import { admin, graph, running } from './admin'

/**
 * The script engine is one for the whole server: every account's console and the autopilot share
 * it (app/models/Breadboard.java). Switching experiment, reloading the engine, and launching or
 * resuming a session all rebuild it, which empties the network and disconnects everyone in it,
 * whoever's session they are in. Ask before doing that while anyone is using it.
 */

/** The session the autopilot has open (GET /autopilot), or null; never fails. */
async function autopilotSession (): Promise<{ id: number, name: string } | null> {
  try {
    const answer = http.get<any>('/autopilot', { quiet401: true })
    const timeout = new Promise<null>(resolve => setTimeout(() => resolve(null), 4000))
    const s = await Promise.race([answer, timeout])
    // (turning the autopilot off leaves its running session open, so "on" does not matter here)
    const inst = s && s.status ? s.status.instance : null
    if (!inst || typeof inst.id !== 'number') return null
    if (['STOPPED', 'FINISHED', 'ARCHIVED'].includes(String(inst.status))) return null
    return { id: inst.id, name: String(inst.name || inst.id) }
  } catch (e) {
    return null
  }
}

/** Who a rebuild would disconnect right now, in words; '' when nobody is using the engine. */
export async function engineUsers (): Promise<string> {
  const lines: string[] = []
  const own = running.value && admin.instance ? admin.instance : null
  if (own) lines.push(`Your session “${own.name}” is running.`)
  const auto = await autopilotSession()
  if (auto && auto.id !== own?.id) lines.push(`The autopilot has session “${auto.name}” open.`)
  const n = graph.nodes.size
  if (n) {
    lines.push(`${n} ${n === 1 ? 'player is' : 'players are'} in the network right now` +
      (own ? '.' : ', perhaps in another researcher\'s session.'))
  }
  return lines.join(' ')
}

/**
 * Ask before something that rebuilds the engine, saying who would be disconnected. Resolves true
 * at once when nobody is using it.
 * `action` names what is about to happen, as the start of a sentence ("Switching experiments").
 */
export async function confirmEngineRebuild (opts: { title: string, action: string, confirmLabel?: string }): Promise<boolean> {
  const who = await engineUsers()
  if (!who) return true
  return confirm({
    title: opts.title,
    message: `${who}\n\n${opts.action} restarts the script engine, which every researcher on this server shares. Everyone in the network is disconnected and the network is emptied.`,
    confirmLabel: opts.confirmLabel || 'Continue anyway',
    danger: true,
  })
}
