<template>
  <Teleport to="body">
    <Transition name="b-dialog">
      <div v-if="open" class="b-dialog" :class="`b-dialog--${placement}`" :style="{ zIndex: layer }" @mousedown.self="onBackdrop">
        <div
          ref="panel"
          class="b-dialog__panel"
          :class="`b-dialog__panel--${size}`"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="title ? titleId : undefined"
          :aria-label="title ? undefined : ariaLabel"
          tabindex="-1"
          @keydown="onKeydown"
        >
          <header v-if="title || $slots.header" class="b-dialog__head">
            <div class="b-dialog__titles">
              <slot name="header">
                <h2 :id="titleId" class="b-dialog__title">{{ title }}</h2>
                <p v-if="subtitle" class="b-dialog__subtitle">{{ subtitle }}</p>
              </slot>
            </div>
            <button v-if="closable" type="button" class="b-dialog__close" aria-label="Close" @click="close">
              <X :size="18" />
            </button>
          </header>
          <div class="b-dialog__body" :class="{ 'is-flush': flush }"><slot /></div>
          <footer v-if="$slots.footer" class="b-dialog__foot"><slot name="footer" /></footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import { uid } from './uid'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  subtitle?: string
  ariaLabel?: string
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full'
  placement?: 'center' | 'right'
  closable?: boolean
  /** Clicking outside or pressing Esc closes it (unless a form inside would lose input). */
  dismissable?: boolean
  flush?: boolean
}>(), { size: 'md', placement: 'center', closable: true, dismissable: true })
const emit = defineEmits<{ 'update:open': [value: boolean], close: [] }>()

const titleId = uid('dialog-title')
const panel = ref<HTMLElement>()
let opener: HTMLElement | null = null

// Each dialog opened sits above the ones already open (e.g. a confirm asked from inside a dialog).
const layer = ref(100)
let isOpen = false

function close () {
  emit('update:open', false)
  emit('close')
}
function onBackdrop () {
  if (props.dismissable) close()
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), [contenteditable="true"]'
function focusables () {
  if (!panel.value) return []
  return Array.from(panel.value.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el => el.offsetParent !== null)
}
function onKeydown (e: KeyboardEvent) {
  if (e.key === 'Escape' && props.dismissable) {
    e.stopPropagation()
    close()
    return
  }
  if (e.key !== 'Tab' || !panel.value) return
  const items = focusables()
  if (!items.length) { e.preventDefault(); return }
  const first = items[0]
  const last = items[items.length - 1]
  // Tab stays inside, also from the panel itself (focused when nothing asks for focus) or from
  // anywhere outside it
  const active = document.activeElement
  const inside = !!active && active !== panel.value && panel.value.contains(active)
  if (e.shiftKey && (!inside || active === first)) { e.preventDefault(); last.focus() }
  else if (!e.shiftKey && (!inside || active === last)) { e.preventDefault(); first.focus() }
}

// The dialog is modal: while it is the top one, focus that gets out (e.g. by a click on a toast)
// comes back to it.
const self = Symbol('dialog')
function onFocusIn (e: FocusEvent) {
  if (openStack[openStack.length - 1] !== self || !panel.value) return
  if (e.target instanceof Node && panel.value.contains(e.target)) return
  ;(focusables()[0] || panel.value).focus()
}

watch(() => props.open, async (open) => {
  if (open) {
    if (!isOpen) {
      isOpen = true
      openCount++
      openStack.push(self)
      document.addEventListener('focusin', onFocusIn)
    }
    layer.value = 100 + openCount
    opener = document.activeElement as HTMLElement | null
    document.body.classList.add('b-no-scroll')
    await nextTick()
    const target = panel.value?.querySelector<HTMLElement>('[autofocus], [data-autofocus]') || panel.value
    target?.focus()
  } else {
    release()
    if (opener && document.contains(opener)) opener.focus()
    opener = null
  }
}, { immediate: true })
function release () {
  if (isOpen) {
    isOpen = false
    openCount = Math.max(0, openCount - 1)
    const i = openStack.indexOf(self)
    if (i >= 0) openStack.splice(i, 1)
    document.removeEventListener('focusin', onFocusIn)
  }
  if (!openCount) document.body.classList.remove('b-no-scroll')
}
onBeforeUnmount(release)
</script>

<script lang="ts">
let openCount = 0
/** The open dialogs, newest (top) last. */
const openStack: symbol[] = []
</script>

<style>
.b-no-scroll { overflow: hidden; }
.b-dialog {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 16px 16px;
  background: var(--overlay);
  backdrop-filter: blur(2px);
  overflow-y: auto;
}
.b-dialog--right { padding: 0; justify-content: flex-end; align-items: stretch; }
.b-dialog__panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-height: 84vh;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  outline: 0;
}
.b-dialog__panel--sm { max-width: 420px; }
.b-dialog__panel--md { max-width: 560px; }
.b-dialog__panel--lg { max-width: 760px; }
.b-dialog__panel--xl { max-width: 1040px; }
.b-dialog__panel--full { max-width: none; height: 100%; max-height: none; }
.b-dialog--right .b-dialog__panel { max-height: none; height: 100%; border-radius: 0; border-width: 0 0 0 1px; }
.b-dialog__head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border);
}
.b-dialog__titles { flex: 1; min-width: 0; }
.b-dialog__title { font-size: 17px; font-weight: 650; }
.b-dialog__subtitle { margin-top: 3px; font-size: var(--text-sm); color: var(--muted); }
.b-dialog__close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin: -4px -6px 0 0;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.b-dialog__close:hover { background: var(--surface-hover); color: var(--text); }
.b-dialog__body { padding: 18px 20px; overflow: auto; flex: 1; min-height: 0; }
.b-dialog__body.is-flush { padding: 0; }
.b-dialog__foot {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--border);
  background: var(--surface-sunken);
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
}
.b-dialog-enter-active, .b-dialog-leave-active { transition: opacity 160ms var(--ease); }
.b-dialog-enter-active .b-dialog__panel, .b-dialog-leave-active .b-dialog__panel { transition: transform 200ms var(--ease); }
.b-dialog-enter-from, .b-dialog-leave-to { opacity: 0; }
.b-dialog-enter-from .b-dialog__panel { transform: translateY(8px) scale(0.98); }
.b-dialog--right.b-dialog-enter-from .b-dialog__panel { transform: translateX(24px); }
@media (max-width: 640px) {
  .b-dialog { padding: 0; align-items: flex-end; }
  .b-dialog__panel { max-height: 92vh; border-radius: var(--radius-lg) var(--radius-lg) 0 0; }
}
</style>
