import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/**
 * The visible width of a scrolling box, kept current. Expanded rows inside a wide table use it to
 * stay pinned to what's on screen (position: sticky; left: 0) instead of scrolling sideways with
 * the table's columns.
 */
export function useBoxWidth (el: Ref<HTMLElement | undefined | null>) {
  const width = ref(0)
  let ro: ResizeObserver | null = null
  watch(el, (node) => {
    ro?.disconnect()
    ro = null
    if (!node) return
    width.value = node.clientWidth
    if (typeof ResizeObserver === 'function') {
      ro = new ResizeObserver(() => { width.value = node.clientWidth })
      ro.observe(node)
    }
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(() => ro?.disconnect())
  return width
}
