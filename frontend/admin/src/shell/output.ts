import { ref } from 'vue'

/** Whether the output panel at the bottom is open. Pages may open it (e.g. after running a script). */
export const openOutput = ref(false)
