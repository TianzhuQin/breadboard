import { reactive, ref, watch } from 'vue'
import { admin } from '@/store/admin'

/**
 * The player selected on the Network and Players pages. One shared ref, so picking someone on
 * one page shows them on the other (the old console synced two copies with events and drifted).
 */
export const selectedPlayer = ref<string | null>(null)

export function selectPlayer (id: string | null) {
  selectedPlayer.value = id
}

// A different session has different people.
watch(() => admin.instance?.id ?? null, (now, before) => {
  if (now !== before) selectedPlayer.value = null
})

/** Per-viewer display choices for the network, remembered in this browser. */
export interface NetworkPrefs {
  /** Colour and name choices per experiment (their properties differ). */
  byExperiment: Record<string, { colorBy?: string | null, labelBy?: string | null }>
  showAllLabels: boolean
  sizeByDegree: boolean
  frozen: boolean
  panelWidth: number
}

const KEY = 'bb-admin-network'
const DEFAULTS: NetworkPrefs = { byExperiment: {}, showAllLabels: false, sizeByDegree: true, frozen: false, panelWidth: 380 }

function load (): NetworkPrefs {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const v = JSON.parse(raw)
      if (v && typeof v === 'object') return { ...DEFAULTS, ...v, byExperiment: v.byExperiment && typeof v.byExperiment === 'object' ? v.byExperiment : {} }
    }
  } catch (e) { /* private mode or bad data: defaults */ }
  return { ...DEFAULTS }
}

export const networkPrefs = reactive<NetworkPrefs>(load())

watch(networkPrefs, (v) => {
  try { localStorage.setItem(KEY, JSON.stringify(v)) } catch (e) { /* not saved, still works */ }
}, { deep: true })

/** The experiment's colour/name choices (undefined: never chosen). */
export function viewPrefs (experimentId: number | undefined) {
  return networkPrefs.byExperiment[String(experimentId ?? '')] || {}
}
export function setViewPref (experimentId: number | undefined, key: 'colorBy' | 'labelBy', value: string | null) {
  const id = String(experimentId ?? '')
  networkPrefs.byExperiment = { ...networkPrefs.byExperiment, [id]: { ...networkPrefs.byExperiment[id], [key]: value } }
}

/** Extra columns on the Players page, per experiment, remembered in this browser (null: never chosen). */
export function loadColumns (experimentId: number | undefined): string[] | null {
  try {
    const raw = localStorage.getItem(`bb-admin-player-columns:${experimentId ?? ''}`)
    const v = raw ? JSON.parse(raw) : null
    return Array.isArray(v) ? v.filter(x => typeof x === 'string') : null
  } catch (e) {
    return null
  }
}
export function saveColumns (experimentId: number | undefined, cols: string[]) {
  try { localStorage.setItem(`bb-admin-player-columns:${experimentId ?? ''}`, JSON.stringify(cols)) } catch (e) { /* ignore */ }
}
