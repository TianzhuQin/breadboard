import { http } from '@/lib/http'
import { confirm } from '@/ui/confirm'
import { admin, graph, running } from './admin'

/**
 * The script engine is one for the whole server: every account's console and the autopilot share
 * it (app/models/Breadboard.java). Switching experiment, reloading the engine, and launching or
 * resuming a session all rebuild it, which empties the network and disconnects everyone in it,
 * whoever's session they are in. Ask before doing that while anyone is using it.
 */

type Named = { id: number, name: string }
type EngineSession = Named & { experimentName: string | null, owner: string | null }

/**
 * What GET /autopilot says the engine runs right now: `auto`, its session of the autopilot's
 * experiment (status.instance), and `other`, its live session of any other experiment
 * (status.engineSession, with the account that runs it). Both null when it can't be asked; never fails.
 *
 * The server works these out when it is asked, so they also name a session started since this
 * console last heard what the engine runs (admin.user.engine is asked for once the engine says it
 * rebuilt, which can be before the session that rebuild is for has started).
 */
async function engineSessions (): Promise<{ auto: Named | null, other: EngineSession | null }> {
  try {
    const answer = http.get<any>('/autopilot', { quiet401: true })
    const timeout = new Promise<null>(resolve => setTimeout(() => resolve(null), 4000))
    const s = await Promise.race([answer, timeout])
    const status = s && s.status && typeof s.status === 'object' ? s.status : null
    return { auto: autopilotSession(status), other: otherSession(status) }
  } catch (e) {
    return { auto: null, other: null }
  }
}

const idOf = (v: any): number | null => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() ? Number(v) : NaN
  return Number.isFinite(n) ? n : null
}
const textOf = (v: any): string | null => (typeof v === 'string' && v ? v : null)

/** The engine's session of the autopilot's experiment, while it has not ended. */
function autopilotSession (status: any): Named | null {
  // (turning the autopilot off leaves its running session open, so "on" does not matter here)
  const inst = status ? status.instance : null
  const id = inst && typeof inst === 'object' ? idOf(inst.id) : null
  if (id === null) return null
  if (['STOPPED', 'FINISHED', 'ARCHIVED'].includes(String(inst.status))) return null
  return { id, name: String(inst.name || id) }
}

/** The engine's live session of another experiment than the autopilot's (it is only sent while live). */
function otherSession (status: any): EngineSession | null {
  const e = status ? status.engineSession : null
  const id = e && typeof e === 'object' ? idOf(e.id) : null
  if (id === null) return null
  return { id, name: String(e.name || id), experimentName: textOf(e.experimentName), owner: textOf(e.owner) }
}

/** "other@lab has session “S” of “E” running." (or "The engine is running …" when it is no one else's) */
function runningLine (s: { name: string, experimentName: string | null, owner: string | null }): string {
  const session = `session “${s.name}”${s.experimentName ? ` of “${s.experimentName}”` : ''}`
  return s.owner && s.owner.toLowerCase() !== admin.email.toLowerCase()
    ? `${s.owner} has ${session} running.`
    : `The engine is running ${session}.`
}

/** Who a rebuild would disconnect right now, in words; '' when nobody is using the engine. */
export async function engineUsers (): Promise<string> {
  const lines: string[] = []
  const own = running.value && admin.instance ? admin.instance : null
  if (own) lines.push(`Your session “${own.name}” is running.`)
  const { auto, other } = await engineSessions()
  const named = new Set<number>(own ? [own.id] : [])
  if (auto && !named.has(auto.id)) {
    lines.push(`The autopilot has session “${auto.name}” open.`)
    named.add(auto.id)
  }
  if (other && !named.has(other.id)) {
    lines.push(runningLine(other))
    named.add(other.id)
  }
  // what the engine said it runs when this console last asked (the server may not have been reachable just now)
  const e = admin.user?.engine
  if (e && e.sessionId !== null && !named.has(e.sessionId)) {
    lines.push(runningLine({ name: e.sessionName || String(e.sessionId), experimentName: e.experimentName, owner: e.owner }))
  }
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
 * `action` names what is about to happen, as the start of a sentence ("Switching experiments");
 * `detail`, when given, is said after it (e.g. that a named session is stopped).
 */
export async function confirmEngineRebuild (opts: { title: string, action: string, confirmLabel?: string, detail?: string }): Promise<boolean> {
  const who = await engineUsers()
  if (!who) return true
  return confirm({
    title: opts.title,
    message: `${who}\n\n${opts.action} restarts the script engine, which every researcher on this server shares. Everyone in the network is disconnected and the network is emptied.` +
      (opts.detail ? ` ${opts.detail}` : ''),
    confirmLabel: opts.confirmLabel || 'Continue anyway',
    danger: true,
  })
}
