import { computed, reactive, watch } from 'vue'
import { admin } from '@/store/admin'
import { registerUnsaved } from '@/shell/unsaved'
import { PARTS, isDirty, type PartKey, type PartState } from './participantPage'

/** One experiment's participant page as edited here. */
export interface PageDoc {
  experimentId: number
  experimentName: string
  parts: Record<PartKey, PartState>
  busy: boolean
}

/**
 * The participant page as edited here, one per experiment. Kept here, not in the page, so unsaved
 * edits survive the page being taken down without a route change: a 401 or Sign out swaps the whole
 * console for the sign-in form, and after signing in again the page comes back with them. They
 * belong to one account: signing in as someone else drops them.
 */
export const docs = reactive(new Map<number, PageDoc>())
let docsFor: string | undefined
export function claimDocs () {
  if (admin.email === docsFor) return
  if (docsFor !== undefined) docs.clear()
  docsFor = admin.email
}
watch(() => admin.email, claimDocs)

export const docDirty = (d: PageDoc) => PARTS.some(p => isDirty(d.parts[p.key]))

/** How many experiments have unsaved participant-page edits, whether or not the page is open. */
export const unsavedPageCount = computed(() => [...docs.values()].filter(docDirty).length)
// signing out asks first while there are any
registerUnsaved('participant-page', () => unsavedPageCount.value, 'Participant page')
