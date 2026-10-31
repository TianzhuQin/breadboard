import { onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { http } from '@/lib/http'
import { admin } from '@/store/admin'
import type { EngineInfo } from '@/store/types'
import { normalizeState, problemKind, type EngineSession } from '../autopilot/autopilot'

/** What this page needs from GET /autopilot (see the Autopilot page for the full settings). */
export interface AutopilotStatus {
  /** who is in charge of its settings: the console ('admin'), the server's settings file ('config'), or nobody ('off') */
  source: 'admin' | 'config' | 'off'
  on: boolean
  experimentId: number | null
  experimentName: string | null
  instance: { id: number, name: string, status: string } | null
  continuous: boolean
  nextSessionDelaySeconds: number
  /**
   * Why it opens no new session by itself until someone presses Resume on the Autopilot page:
   * 'paused' (a researcher stopped or replaced its session), 'done' (its one session finished with
   * "continuous" off); null when it carries on.
   */
  halted: 'paused' | 'done' | null
  /**
   * What the autopilot waits for before it can open its next session, by design (the server's
   * problemKind "waiting"): another experiment's session that runs on the shared engine to end
   * (`session`), or the engine itself, which another researcher has loaded with another experiment
   * (`session` null). Null when it waits for neither.
   */
  waiting: { session: EngineSession | null } | null
  /**
   * Why the autopilot opens no session by itself now, in the server's words (status.problem), of
   * whatever kind: paused or done (see `halted`), waiting for the shared engine (see `waiting`), or a
   * problem the Autopilot page shows (the last session it opened reported an error, the disk is almost
   * full, there is no admin account to open sessions as, …). Null when nothing keeps it from opening one.
   */
  problem: string | null
}

/** The status's reason to open no new session until Resume (newer servers say problemKind too). */
function haltedOf (st: any): AutopilotStatus['halted'] {
  if (st.paused === true || st.problemKind === 'paused') return 'paused'
  if (st.done === true || st.problemKind === 'done') return 'done'
  return null
}

/**
 * Whether Breadboard runs this experiment's sessions by itself. Only a hint on this page (the
 * Autopilot page owns the settings), so a server without the route, or a failed request,
 * leaves it null and the page shows its plain link to Autopilot instead. `everyMs` also asks again
 * that often while the page is in view, for what no console hears about (a session of the autopilot
 * that reported an error, say).
 */
export function useAutopilotStatus (opts: { everyMs?: number } = {}): Ref<AutopilotStatus | null> {
  const status = ref<AutopilotStatus | null>(null)
  let seq = 0
  async function load () {
    const mine = ++seq
    try {
      const s = await http.get<any>('/autopilot')
      if (mine !== seq) return
      const read = autopilotStatusOf(s)
      if (read) status.value = read
    } catch (e) {
      if (mine === seq) status.value = null
    }
  }
  let timer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    void load()
    if (opts.everyMs) timer = setInterval(() => { if (document.visibilityState !== 'hidden') void load() }, opts.everyMs)
  })
  onBeforeUnmount(() => clearInterval(timer))
  // A session opening or finishing may be the autopilot's doing; every console hears when the
  // autopilot is switched off, paused, resumed or saved while a session is live (engine.autopilotSession),
  // and when the engine starts or ends a session or loads another experiment, which the autopilot
  // may wait for (engine.sessionId, engine.experimentId).
  watch(() => [
    admin.experiment?.id, admin.instance?.id, admin.instance?.status,
    admin.user?.engine?.autopilotSession, admin.user?.engine?.sessionId, admin.user?.engine?.experimentId,
  ], () => { void load() })
  return status
}

/** What GET /autopilot answered, as this page needs it; null when it isn't the autopilot's state. */
export function autopilotStatusOf (s: any): AutopilotStatus | null {
  if (!s || typeof s !== 'object' || !s.status || typeof s.status !== 'object') return null
  // (read as the Autopilot page reads it)
  const state = normalizeState(s)
  // The server's "waiting" note while a session of the experiment that a researcher opened by hand is
  // open (status.handSession) only says that the autopilot opens its own once that one has ended: it
  // keeps nothing from opening it then, so the study's links still wait for it during that session's
  // game (the server leaves it out of Autopilot.nextSessionHeldUp). Paused or done, the reason stays.
  const handNote = !!state.status.handSession && problemKind(state) === 'waiting'
  return {
    source: state.source,
    on: !!s.status.on,
    experimentId: s.status.experimentId ?? null,
    experimentName: s.status.experimentName ?? null,
    instance: s.status.instance ?? null,
    continuous: !!(s.settings && s.settings.continuous),
    nextSessionDelaySeconds: Number(s.settings?.nextSessionDelaySeconds) || 0,
    halted: haltedOf(s.status),
    waiting: problemKind(state) === 'waiting' && !handNote ? { session: state.status.engineSession } : null,
    problem: handNote ? null : state.status.problem,
  }
}

/** True when the autopilot is on for this experiment. */
export function autopilotRuns (s: AutopilotStatus | null, experimentId: number | null | undefined): boolean {
  return !!s && s.on && s.experimentId != null && s.experimentId === experimentId
}

/**
 * True when the autopilot opens this experiment's next session by itself: it is on for it, opens the
 * next one when one finishes (continuous), and is neither paused nor done. Something may still keep it
 * from opening one for now (autopilotHold).
 */
export function autopilotOpensNext (s: AutopilotStatus | null, experimentId: number | null | undefined): boolean {
  return autopilotRuns(s, experimentId) && !!s && s.continuous && !s.halted
}

/**
 * What keeps the autopilot, which opens this experiment's sessions by itself (autopilotOpensNext),
 * from opening the next one now: another experiment's session that runs on the shared engine
 * (`session`), a researcher who has another experiment loaded there (`engine`), or a problem the
 * Autopilot page shows (`problem`). Null when nothing does, or when it doesn't open them by itself.
 */
export type AutopilotHold = { by: 'session', session: EngineSession } | { by: 'engine' } | { by: 'problem' }
export function autopilotHold (s: AutopilotStatus | null, experimentId: number | null | undefined): AutopilotHold | null {
  if (!s || !autopilotOpensNext(s, experimentId) || !s.problem) return null
  if (s.waiting?.session) return { by: 'session', session: s.waiting.session }
  if (s.waiting) return { by: 'engine' }
  return { by: 'problem' }
}

/**
 * True when the autopilot opens this experiment's next session by itself, and nothing keeps it from
 * doing so now (no status.problem of any kind). The server decides the same way what the study's
 * links show while no session takes newcomers (Autopilot.opensNextSoon): a page that holds them until
 * the autopilot's next session opens; otherwise "No session is open right now", which asks Prolific
 * participants to return the study.
 */
export function autopilotOpensNextSoon (s: AutopilotStatus | null, experimentId: number | null | undefined): boolean {
  return autopilotOpensNext(s, experimentId) && !!s && !s.problem
}

/**
 * True when `session`, running or testing, is the autopilot's own, open now, as the server says with
 * what the engine runs (engine.autopilotSession): it opened that session (or was handed it on Resume
 * or a save), and is on, neither paused nor done. Steps saved meanwhile go into the next session it
 * opens, so there is nothing to reload for. Every console hears when that changes, also when the
 * autopilot is switched off and leaves its session running (a status asked for once would keep
 * saying it is the autopilot's).
 */
export function autopilotRunsSession (engine: EngineInfo | null | undefined, session: { id: number, status: string } | null | undefined): boolean {
  if (!engine || engine.autopilotSession !== true || !session) return false
  if (session.status !== 'RUNNING' && session.status !== 'TESTING') return false
  return engine.sessionId === session.id
}
