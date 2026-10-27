import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { confirm } from '@/ui'
import { registerUnsaved } from '@/shell/unsaved'

/**
 * Warns before unsaved edits are lost: on leaving the page inside the console (a dialog) and on
 * closing or reloading the tab (the browser's own prompt). Also wires Cmd/Ctrl+S to `save`.
 * With `unsaved`, the edits also count as unsaved work while the page is open, so signing out asks
 * first (signing out replaces the whole console, so the route guard never runs).
 */
export function useUnsavedGuard (dirty: Ref<boolean>, opts: { what?: string, save?: () => void, unsaved?: { key: string, label: string } } = {}) {
  onBeforeRouteLeave(async () => {
    if (!dirty.value) return true
    return confirm({
      title: 'Leave without saving?',
      message: `Your changes to ${opts.what || 'this page'} haven't been saved and will be lost.`,
      confirmLabel: 'Leave and discard',
      cancelLabel: 'Stay',
      danger: true,
    })
  })
  function onBeforeUnload (e: BeforeUnloadEvent) {
    if (!dirty.value) return
    e.preventDefault()
    e.returnValue = ''
  }
  function onKey (e: KeyboardEvent) {
    if (!opts.save || !(e.metaKey || e.ctrlKey) || e.key.toLowerCase() !== 's') return
    e.preventDefault()
    opts.save()
  }
  let unregister: (() => void) | null = null
  onMounted(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
    window.addEventListener('keydown', onKey)
    if (opts.unsaved) unregister = registerUnsaved(opts.unsaved.key, () => (dirty.value ? 1 : 0), opts.unsaved.label)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
    window.removeEventListener('keydown', onKey)
    unregister?.()
    unregister = null
  })
}
