/** Prolific helpers: the study URL, the test link and the participants seen in the live session. */
import { groovyLiteral } from '@/pages/console/snippets'
import { moneyString } from '../mturk/lib'

/** Prolific's placeholder syntax; Prolific swaps these for each participant's ids. */
export const PROLIFIC_QUERY = 'PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}'

/**
 * The stable study URL to paste into Prolific. /play/<experiment>/prolific always forwards to the
 * session currently accepting participants (ClientLogin.playProlific), so one link serves every
 * session of the experiment.
 */
export function prolificStudyUrl (origin: string, experimentId: number): string {
  return `${origin}/play/${experimentId}/prolific?${PROLIFIC_QUERY}`
}

/** The same link with made-up ids, to try the way in as a pretend participant. */
export function prolificTestUrl (origin: string, experimentId: number, token: string): string {
  const q = new URLSearchParams({ PROLIFIC_PID: `TEST-${token}`, STUDY_ID: 'test-study', SESSION_ID: `test-${token}` })
  return `${origin}/play/${experimentId}/prolific?${q.toString()}`
}

/**
 * Where /play/<experiment>/prolific leads right now, picked as Autopilot.openInstance does: the
 * session the engine runs (`engineSessionId`, from the engine info), when it is one of this
 * experiment's (`list`), RUNNING, and its game hasn't started. `started` is that session once its
 * game has started, which explains why there is none. A session left RUNNING that the engine no
 * longer runs (e.g. since a restart) takes no one. While the engine info is unknown (undefined),
 * the newest running session stands in for the engine's.
 */
export function prolificTarget<T extends { id: number, status: string, hasStarted?: boolean }> (list: T[], engineSessionId?: number | null): { open: T | null, started: T | null } {
  let live: T | null = null
  for (const i of list) {
    if (i.status !== 'RUNNING') continue
    if (engineSessionId === undefined ? (!live || i.id > live.id) : i.id === engineSessionId) live = i
  }
  if (!live) return { open: null, started: null }
  return live.hasStarted ? { open: null, started: live } : { open: live, started: null }
}

export function randomToken (): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let s = ''
  for (let i = 0; i < 6; i++) s += chars[Math.floor(Math.random() * chars.length)]
  return s
}

export interface ProlificPlayer {
  playerId: string
  prolificPid: string
  studyId: string
  sessionId: string
  completed: boolean
  completionCode: string
  bonus: number | null
  connected: boolean | null
}

/** Players of the live session who came from Prolific (player._system.recruitment.source). */
export function prolificPlayers (nodes: Iterable<{ id: string, data: Record<string, any> }>): ProlificPlayer[] {
  const out: ProlificPlayer[] = []
  for (const n of nodes) {
    const sys = n.data?._system
    const rec = sys && typeof sys === 'object' ? sys.recruitment : null
    if (!rec || typeof rec !== 'object' || rec.source !== 'prolific') continue
    const bonus = typeof rec.bonus === 'number' ? rec.bonus : parseFloat(rec.bonus)
    out.push({
      playerId: n.id,
      prolificPid: String(rec.prolificPid ?? ''),
      studyId: String(rec.studyId ?? ''),
      sessionId: String(rec.sessionId ?? ''),
      completed: !!rec.completed,
      completionCode: String(rec.completionCode ?? ''),
      bonus: Number.isFinite(bonus) && bonus > 0 ? bonus : null,
      connected: typeof sys.connected === 'boolean' ? sys.connected : null,
    })
  }
  return out.sort((a, b) => a.prolificPid.localeCompare(b.prolificPid))
}

/**
 * Prolific's bulk bonus box takes one "participant id,amount" per line. Amounts are rounded to
 * cents; players without a bonus are left out.
 */
export function prolificBonusLines (players: ProlificPlayer[]): string {
  return players
    .filter(p => p.prolificPid && p.bonus != null && p.bonus > 0)
    .map(p => `${p.prolificPid},${moneyString(p.bonus!)}`)
    .join('\n')
}

/**
 * A Groovy string literal for the completion snippet: single-quoted (so `${…}` is never run), with
 * backslashes, quotes and line breaks escaped, as the script console's snippets do.
 */
export function groovyString (s: string): string {
  return groovyLiteral(s)
}

/**
 * The code for the step where a participant finishes: it completes Prolific participants only
 * (completeProlific refuses anyone else, which would stop the step), and says that `p` stands for
 * whatever the step calls the participant.
 */
export function completionSnippet (code: string): string {
  return [
    '// p is the participant: use the name your step gives them (often player, or p in g.V.each { p -> ... })',
    'if (p._system?.recruitment?.source == "prolific") {',
    '  recruitment.completeProlific(p, [',
    `    completionCode: ${groovyString(code.trim() || 'YOUR-CODE')},`,
    `    message: ${groovyString('Thank you for taking part!')}`,
    '  ])',
    '}',
  ].join('\n')
}

/** The launch parameter some experiments (the network game example) give Prolific participants their code from. */
export const CODE_PARAMETER = 'prolificCode'
