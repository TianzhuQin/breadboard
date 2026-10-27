import { ref } from 'vue'
import { registerUnsaved } from '@/shell/unsaved'

/**
 * Money and other bulk MTurk operations in flight. The mode switch waits for them, and the page
 * asks before being left while one runs (the requests would go on, but nobody would see the outcome).
 */
export const activeJobs = ref(0)

// Signing out swaps the whole console for the sign-in page, so the page's own leave question never
// runs; and a run keeps going after the page is left. Counted here, for as long as the module lives,
// signing out asks first whenever one is under way.
registerUnsaved('mturk-jobs', () => activeJobs.value, 'Mechanical Turk requests', { running: true })

export async function track<T> (work: () => Promise<T>): Promise<T> {
  activeJobs.value++
  try { return await work() } finally { activeJobs.value-- }
}
