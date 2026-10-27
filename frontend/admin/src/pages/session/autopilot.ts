import { onMounted, ref, watch, type Ref } from 'vue'
import { http } from '@/lib/http'
import { admin } from '@/store/admin'

/** What this page needs from GET /autopilot (see the Autopilot page for the full settings). */
export interface AutopilotStatus {
  on: boolean
  experimentId: number | null
  experimentName: string | null
  instance: { id: number, name: string, status: string } | null
  continuous: boolean
  nextSessionDelaySeconds: number
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
      if (mine !== seq || !s || typeof s !== 'object' || !s.status) return
      status.value = {
        on: !!s.status.on,
        experimentId: s.status.experimentId ?? null,
        experimentName: s.status.experimentName ?? null,
        instance: s.status.instance ?? null,
        continuous: !!(s.settings && s.settings.continuous),
        nextSessionDelaySeconds: Number(s.settings?.nextSessionDelaySeconds) || 0,
      }
    } catch (e) {
      if (mine === seq) status.value = null
    }
  }
  onMounted(load)
  // a session opening or finishing may be the autopilot's doing
  watch(() => [admin.experiment?.id, admin.instance?.id, admin.instance?.status], () => { void load() })
  return status
}

/** True when the autopilot is on for this experiment. */
export function autopilotRuns (s: AutopilotStatus | null, experimentId: number | null | undefined): boolean {
  return !!s && s.on && s.experimentId != null && s.experimentId === experimentId
}
