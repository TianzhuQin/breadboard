import { onBeforeUnmount, ref } from 'vue'

/** Whether a CSS media query matches, kept up to date (false where matchMedia is missing). */
export function useMedia (query: string) {
  const mq = typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query) : null
  const matches = ref(!!mq?.matches)
  const update = (e: MediaQueryListEvent) => { matches.value = e.matches }
  mq?.addEventListener?.('change', update)
  onBeforeUnmount(() => mq?.removeEventListener?.('change', update))
  return matches
}
