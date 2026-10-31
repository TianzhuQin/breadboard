import { http } from '@/lib/http'
import { confirm } from '@/ui/confirm'
import { admin, engineElsewhere, graph, running } from './admin'
import type { EngineInfo } from './types'

/**
 * The script engine is one for the whole server: every account's console and the autopilot share
 * it (app/models/Breadboard.java). Reloading the engine, and launching or resuming a session, all
 * rebuild it, which empties the network and disconnects everyone in it, whoever's session they are
 * in; so does switching experiments, but only while no session is live there (otherwise switching
 * changes just what this console works on). Ask before doing that while anyone is using it.
 */

type Named = { id: number, name: string }
type EngineSession = Named & { experimentName: string | null, owner: string | null }

/**
 * What GET /autopilot says the engine runs right now: `auto`, its session of the autopilot's
 * experiment (status.instance), and `other`, its live session of any other experiment
 * (status.engineSession, with the account that runs it). Both null when it can't be asked; never fails.
 * `autopilotLive`: the autopilot is on, not paused, and `auto` is its session, so a researcher's
 * rebuild that ends it pauses the autopilot (the server's Autopilot.sessionStopped).
 *
 * The server works these out when it is asked, so they also name a session started since this
 * console last heard what the engine runs (admin.user.engine is asked for once the engine says it
 * rebuilt, which can be before the session that rebuild is for has started).
 */
async function engineSessions (): Promise<{ auto: Named | null, other: EngineSession | null, autopilotOn: boolean, autopilotLive: boolean, known: boolean }> {
  try {
    const answer = http.get<any>('/autopilot', { quiet401: true })
    const timeout = new Promise<null>(resolve => setTimeout(() => resolve(null), 4000))
    const s = await Promise.race([answer, timeout])
    const status = s && s.status && typeof s.status === 'object' ? s.status : null
    const auto = autopilotSession(status)
    const on = !!status && status.on === true
    return { auto, other: otherSession(status), autopilotOn: on, autopilotLive: !!auto && on && status.paused !== true, known: !!status }
  } catch (e) {
    return { auto: null, other: null, autopilotOn: false, autopilotLive: false, known: false }
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

/** People (not computer players) whose page is connected to the engine's network right now. */
function peopleConnected (): number {
  let n = 0
  for (const node of graph.nodes.values()) {
    if (String(node.id).startsWith('_')) continue
    const sys = (node.data as any)?._system
    if (sys && typeof sys === 'object' && sys.connected === true) n++
  }
  return n
}

/**
 * What a rebuild that carries session `keeping` on means for it, in words, when the engine runs it
 * now ('' otherwise, or when its game hasn't started and nobody is connected): the session goes on in
 * the new engine, but its game starts again from the beginning, and the people in it are disconnected
 * and rejoin by themselves. Also the autopilot's own session (opened as this account, so it is
 * "yours" to the server): a reload carries it on too, without pausing the autopilot.
 */
function carriedOn (keeping: number | undefined, auto: Named | null): string {
  const e = admin.user?.engine
  if (keeping == null || !e || e.sessionId !== keeping) return ''
  const started = e.gameStarted === true
  const here = peopleConnected()
  if (!started && !here) return ''
  const name = e.sessionName || (admin.instance && admin.instance.id === keeping ? admin.instance.name : String(keeping))
  const whose = auto && auto.id === keeping ? `The autopilot’s session “${name}”` : `Your session “${name}”`
  const people = here === 1 ? 'The 1 person in it is' : `The ${here} people in it are`
  const rejoin = here ? ` ${people} disconnected for a moment and ${here === 1 ? 'rejoins' : 'rejoin'} by themselves.` : ''
  return started
    ? `${whose} carries on, but its game is in progress: reloading starts the game again from the beginning.${rejoin}`
    : `${whose} carries on.${rejoin}`
}

/** Said when a rebuild would end the autopilot's live session (see engineUse). */
export const REBUILD_PAUSES_AUTOPILOT = 'This also pauses the autopilot: it opens no new session by itself until someone presses Resume on the Autopilot page, so its participant links only lead somewhere while a session of its experiment is open.'

/** Who a rebuild would disconnect right now, in words; '' when nobody is using the engine. */
export async function engineUsers (): Promise<string> {
  return (await engineUse()).who
}

/**
 * Who a rebuild would disconnect right now (`who`, in words; '' when nobody is using the engine),
 * whether it ends a running session (`endsSession`), and whether it pauses the autopilot: the
 * engine runs the autopilot's session, and the autopilot is on and not paused (say
 * REBUILD_PAUSES_AUTOPILOT then). A resume or reload that carries a session on passes its id as
 * `keeping`: that session isn't ended (nor the autopilot paused, when it is the autopilot's), though
 * the people in it are disconnected too, and its game starts again: said when its game is in progress
 * or anyone is connected to it (see carriedOn).
 */
export async function engineUse (keeping?: number): Promise<{ who: string, pausesAutopilot: boolean, endsSession: boolean }> {
  const lines: string[] = []
  const own = running.value && admin.instance ? admin.instance : null
  const named = new Set<number>(keeping != null ? [keeping] : [])
  const { auto, other, autopilotLive, known } = await engineSessions()
  if (own && !named.has(own.id)) {
    // (the running session this console shows needn't be this account's own: it follows the
    // autopilot's, and a colleague's, of the experiment it works on)
    const e = admin.user?.engine
    const owner = e && e.sessionId === own.id ? e.owner : null
    if (auto && auto.id === own.id) lines.push(`The autopilot has session “${own.name}” open.`)
    else if (owner && owner.toLowerCase() !== admin.email.toLowerCase()) lines.push(`${owner} has session “${own.name}” running.`)
    else lines.push(`Your session “${own.name}” is running.`)
    named.add(own.id)
  }
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
  const endsSession = lines.length > 0
  const carried = carriedOn(keeping, auto)
  if (carried) lines.unshift(carried)
  const n = graph.nodes.size
  // When the server says no session is running, people left in the network are what a finished or
  // stopped session left behind: clearing them disturbs no one, so it isn't worth a warning.
  if (n && (endsSession || !known)) {
    lines.push(`${n} ${n === 1 ? 'player is' : 'players are'} in the network right now` +
      (own ? '.' : ', perhaps in another researcher\'s session.'))
  }
  return {
    who: lines.join(' '),
    pausesAutopilot: autopilotLive && !(keeping != null && auto?.id === keeping),
    endsSession,
  }
}

/**
 * The session a reload of the engine carries on (the server's ReloadEngine): this account's own
 * running session, when the engine runs it for this account and the experiment selected here.
 */
export function reloadKeeps (): number | undefined {
  const i = admin.instance
  const e = admin.user?.engine
  if (!i || i.status !== 'RUNNING' || !e || e.sessionId !== i.id) return undefined
  if (admin.experiment && e.experimentId !== admin.experiment.id) return undefined
  if (!e.owner || e.owner.toLowerCase() !== admin.email.toLowerCase()) return undefined
  return i.id
}

/**
 * Ask before something that rebuilds the engine, saying who would be disconnected. Resolves true
 * at once when nobody is using it.
 * `action` names what is about to happen, as the start of a sentence ("Switching experiments");
 * `detail`, when given, is said after it (e.g. that a named session is stopped).
 */
export async function confirmEngineRebuild (opts: { title: string, action: string, confirmLabel?: string, detail?: string, keeping?: number }): Promise<boolean> {
  const use = await engineUse(opts.keeping)
  if (!use.who) return true
  return askRebuild(opts, use)
}

function askRebuild (opts: { title: string, action: string, confirmLabel?: string, detail?: string, ask?: string }, use: { who: string, pausesAutopilot: boolean }): Promise<boolean> {
  return confirm({
    title: opts.title,
    message: (opts.ask ? `${opts.ask}\n\n` : '') +
      `${use.who}\n\n${opts.action} restarts the script engine, which every researcher on this server shares. Everyone in the network is disconnected and the network is emptied.` +
      (opts.detail ? ` ${opts.detail}` : '') +
      (use.pausesAutopilot ? `\n\n${REBUILD_PAUSES_AUTOPILOT}` : ''),
    confirmLabel: opts.confirmLabel || 'Continue anyway',
    danger: true,
  })
}

/**
 * Before switching experiments: ask only when the switch rebuilds the engine under someone. While a
 * session is live in the engine (the autopilot's, or any researcher's), selecting changes only what
 * this console works on, and the server leaves the engine and that session alone
 * (ScriptBoard.sessionKeptBySelection): nothing to ask. Otherwise the engine loads the selected
 * experiment's steps, and this asks as confirmEngineRebuild does (at once true when nobody uses it).
 * With `ask` (why to switch, for a switch the researcher didn't choose in the switcher, e.g. to open
 * a step of another experiment), it always asks, saying that first.
 */
export async function confirmSwitch (opts: { title: string, action: string, confirmLabel?: string, ask?: string }): Promise<boolean> {
  const use = await engineUse()
  if (use.endsSession || !use.who) {
    return opts.ask ? confirm({ title: opts.title, message: opts.ask, confirmLabel: opts.confirmLabel || 'Switch' }) : true
  }
  return askRebuild(opts, use)
}

/**
 * What to say after switching experiments while the engine runs another one's session, which the
 * switch left running; '' when the engine runs the selected experiment or has no live session.
 */
export function switchedNote (): string {
  const e = engineElsewhere.value
  if (!e || e.sessionId === null) return ''
  const what = e.experimentName ? `“${e.experimentName}”` : 'another experiment'
  return `The engine keeps running ${what}${e.sessionName ? ` (session “${e.sessionName}”)` : ''}, untouched. Edit this experiment and see its data here; to run it, end that session first.`
}

/**
 * Whose live session the engine runs, in words: "the autopilot’s session “S”" (when `autopilot`),
 * "your session “S”", "x@lab’s session “S”", or "session “S”" when nobody is named.
 */
export function sessionWords (e: Pick<EngineInfo, 'sessionId' | 'sessionName' | 'owner'>, autopilot = false): string {
  const name = e.sessionName ? `session “${e.sessionName}”` : 'session'
  if (autopilot) return `the autopilot’s ${name}`
  if (e.owner && e.owner.toLowerCase() === admin.email.toLowerCase()) return `your ${name}`
  return e.owner ? `${e.owner}’s ${name}` : name
}

/**
 * Whether the engine's live session `id` is the autopilot's, as GET /autopilot says: it is on, and
 * that is its session, so ending it pauses the autopilot. False when the server can't be asked.
 */
export async function isAutopilotSession (id: number | null): Promise<boolean> {
  if (id === null) return false
  const { auto, autopilotOn } = await engineSessions()
  return autopilotOn && !!auto && auto.id === id
}
