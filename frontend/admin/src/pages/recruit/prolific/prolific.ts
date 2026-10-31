/** Prolific helpers: the study URL, the test link and the participants seen in the live session. */
import { groovyLiteral } from '@/pages/console/snippets'
import { http } from '@/lib/http'
import type { EventItem, LiveEvent } from '@/store/types'
import { bonusTotal, moneyString } from '../mturk/lib'

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
  /** the game ended for them without a completion code (a noCompletionCode event; see playersWithoutCode) */
  finishedWithoutCode: boolean
}

/**
 * Players of the live session who came from Prolific (player._system.recruitment.source).
 * `withoutCode` are the players the session recorded as finished without a completion code
 * (playersWithoutCode).
 */
export function prolificPlayers (nodes: Iterable<{ id: string, data: Record<string, any> }>, withoutCode: ReadonlySet<string> = new Set()): ProlificPlayer[] {
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
      finishedWithoutCode: !rec.completed && withoutCode.has(String(n.id)),
    })
  }
  return out.sort((a, b) => a.prolificPid.localeCompare(b.prolificPid))
}

/**
 * The players a session recorded as finished without a completion code: its noCompletionCode events
 * (`player`), which the network game example records while its prolificCode parameter is empty. From
 * what the console has of the session's events: those its User JSON carries (`recorded`, eventData)
 * and those pushed since (`live`, data).
 */
export function playersWithoutCode (
  recorded: ReadonlyArray<EventItem | null | undefined> | null | undefined,
  live: ReadonlyArray<LiveEvent | null | undefined> | null | undefined,
): Set<string> {
  const ids = new Set<string>()
  const add = (player: unknown) => { if (player != null && player !== '') ids.add(String(player)) }
  for (const e of recorded || []) {
    if (e && e.name === 'noCompletionCode') add((e.eventData || []).find(d => d && d.name === 'player')?.value)
  }
  for (const e of live || []) {
    if (e && e.name === 'noCompletionCode') add(e.data?.player)
  }
  return ids
}

/**
 * Whether a Prolific participant counts as finished: they were given a completion code, or the game
 * ended for them without one. Both of the Recruitment page's lists count them so.
 */
export function prolificFinished (p: { completed: boolean, finishedWithoutCode: boolean }): boolean {
  return p.completed || p.finishedWithoutCode
}

/**
 * Prolific's bulk bonus box takes one "participant id,amount" per line. Amounts are rounded to
 * cents; players without a bonus (or without a Prolific ID) are left out, and someone listed twice
 * (paid in two sessions) gets one line with the amounts added up.
 */
export function prolificBonusLines (players: Array<Pick<ProlificPlayer, 'prolificPid' | 'bonus'>>): string {
  const byPid = new Map<string, number[]>()
  for (const p of players) {
    if (!p.prolificPid || p.bonus == null || !(p.bonus > 0)) continue
    const amounts = byPid.get(p.prolificPid) || []
    amounts.push(p.bonus)
    byPid.set(p.prolificPid, amounts)
  }
  return [...byPid].map(([pid, amounts]) => `${pid},${moneyString(bonusTotal(amounts))}`).join('\n')
}

// ---- every session's Prolific participants ------------------------------------------------------

/**
 * A Prolific participant of one of the experiment's sessions, as its data records them (GET
 * /experiment/:id/prolific, models.ProlificParticipants): their entry (prolificEntry), their
 * completion (prolificComplete: code and bonus), or an end without a code (noCompletionCode, which
 * the network game example records while its prolificCode is empty). Times are ISO-8601 UTC.
 */
export interface ProlificRecord {
  sessionId: number
  sessionName: string
  sessionStatus: string
  player: string
  prolificPid: string
  studyId: string
  prolificSessionId: string
  joinedAt: string | null
  completed: boolean
  completedAt: string | null
  completionCode: string
  bonus: number | null
  finishedWithoutCode: boolean
}

/** The server's answer, read defensively: anything that isn't a participant is left out. */
export function prolificRecordsOf (json: any): ProlificRecord[] {
  const list = json && Array.isArray(json.participants) ? json.participants : []
  const text = (v: any) => (typeof v === 'string' ? v : v == null ? '' : String(v))
  const out: ProlificRecord[] = []
  for (const p of list) {
    if (!p || typeof p !== 'object' || !p.player) continue
    const bonus = typeof p.bonus === 'number' ? p.bonus : parseFloat(p.bonus)
    out.push({
      sessionId: Number(p.sessionId),
      sessionName: text(p.sessionName) || `Session ${p.sessionId}`,
      sessionStatus: text(p.sessionStatus),
      player: text(p.player),
      prolificPid: text(p.prolificPid),
      studyId: text(p.studyId),
      prolificSessionId: text(p.prolificSessionId),
      joinedAt: typeof p.joinedAt === 'string' ? p.joinedAt : null,
      completed: p.completed === true,
      completedAt: typeof p.completedAt === 'string' ? p.completedAt : null,
      completionCode: text(p.completionCode),
      bonus: Number.isFinite(bonus) && bonus > 0 ? bonus : null,
      finishedWithoutCode: p.finishedWithoutCode === true,
    })
  }
  return out
}

/** Every session's Prolific participants, newest session first. */
export async function loadProlificRecords (experimentId: number): Promise<ProlificRecord[]> {
  return prolificRecordsOf(await http.get(`/experiment/${experimentId}/prolific`))
}

export interface ProlificSessionGroup {
  sessionId: number
  sessionName: string
  sessionStatus: string
  records: ProlificRecord[]
  joined: number
  finished: number
}

/** The records by session, in the order they come (newest session first). */
export function groupBySession (records: ProlificRecord[]): ProlificSessionGroup[] {
  const groups = new Map<number, ProlificSessionGroup>()
  for (const r of records) {
    let g = groups.get(r.sessionId)
    if (!g) {
      g = { sessionId: r.sessionId, sessionName: r.sessionName, sessionStatus: r.sessionStatus, records: [], joined: 0, finished: 0 }
      groups.set(r.sessionId, g)
    }
    g.records.push(r)
    g.joined++
    if (prolificFinished(r)) g.finished++
  }
  return [...groups.values()]
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
 * (completeProlific does nothing for anyone else, and the server's log says why; it used to throw,
 * which stopped the step), and says that `p` stands for whatever the step calls the participant.
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
