import { ref } from 'vue'

/**
 * Money and other bulk MTurk operations in flight. The mode switch waits for them, and the page
 * asks before being left while one runs (the requests would go on, but nobody would see the outcome).
 */
export const activeJobs = ref(0)

export async function track<T> (work: () => Promise<T>): Promise<T> {
  activeJobs.value++
  try { return await work() } finally { activeJobs.value-- }
}
