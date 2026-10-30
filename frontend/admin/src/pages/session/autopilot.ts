import { onMounted, ref, watch, type Ref } from 'vue'
import { http } from '@/lib/http'
import { admin } from '@/store/admin'
import { normalizeState, problemKind, type EngineSession } from '../autopilot/autopilot'

/** What this page needs from GET /autopilot (see the Autopilot page for the full settings). */
export interface AutopilotStatus {
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
 * leaves it null and the page shows its plain link to Autopilot instead.
 */
export function useAutopilotStatus (): Ref<AutopilotStatus | null> {
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
  onMounted(load)
  // a session opening or finishing may be the autopilot's doing
  watch(() => [admin.experiment?.id, admin.instance?.id, admin.instance?.status], () => { void load() })
  return status
}

/** What GET /autopilot answered, as this page needs it; null when it isn't the autopilot's state. */
export function autopilotStatusOf (s: any): AutopilotStatus | null {
  if (!s || typeof s !== 'object' || !s.status || typeof s.status !== 'object') return null
  // (read as the Autopilot page reads it)
  const state = normalizeState(s)
  return {
    on: !!s.status.on,
    experimentId: s.status.experimentId ?? null,
    experimentName: s.status.experimentName ?? null,
    instance: s.status.instance ?? null,
    continuous: !!(s.settings && s.settings.continuous),
    nextSessionDelaySeconds: Number(s.settings?.nextSessionDelaySeconds) || 0,
    halted: haltedOf(s.status),
    waiting: problemKind(state) === 'waiting' ? { session: state.status.engineSession } : null,
  }
}

/** True when the autopilot is on for this experiment. */
export function autopilotRuns (s: AutopilotStatus | null, experimentId: number | null | undefined): boolean {
  return !!s && s.on && s.experimentId != null && s.experimentId === experimentId
}

/**
 * True when session `sessionId` is the autopilot's own, open now: it is on for this experiment, isn't
 * paused or done, and that is the session it opened. Steps saved meanwhile go into the next session
 * it opens, so there is nothing to reload for.
 */
export function autopilotRunsSession (s: AutopilotStatus | null, experimentId: number | null | undefined, sessionId: number | null | undefined): boolean {
  if (!s || !autopilotRuns(s, experimentId) || s.halted || !s.instance || sessionId == null) return false
  return s.instance.id === sessionId && (s.instance.status === 'RUNNING' || s.instance.status === 'TESTING')
}
