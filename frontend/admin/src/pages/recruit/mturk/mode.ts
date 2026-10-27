import { computed, ref, watch } from 'vue'
import { readStore, writeStore } from '../storage'

export type MturkMode = 'sandbox' | 'production'

const KEY = 'bb.recruit.mturk.mode'

/**
 * Sandbox (MTurk's free practice site) or production (real workers, real money). Starts in
 * sandbox and remembers the choice in this browser only, so a new browser never starts on
 * production by surprise.
 */
export const mode = ref<MturkMode>(readStore(KEY) === 'production' ? 'production' : 'sandbox')
watch(mode, m => writeStore(KEY, m))

export const isProduction = computed(() => mode.value === 'production')
export const modeName = computed(() => (mode.value === 'production' ? 'Production' : 'Sandbox'))

/** MTurk's own websites for the current mode. */
export const sites = computed(() => mode.value === 'production'
  ? { requester: 'https://requester.mturk.com', worker: 'https://worker.mturk.com' }
  : { requester: 'https://requestersandbox.mturk.com', worker: 'https://workersandbox.mturk.com' })
