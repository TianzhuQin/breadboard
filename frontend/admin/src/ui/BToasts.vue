<template>
  <div class="b-toasts" aria-live="polite" aria-relevant="additions">
    <TransitionGroup name="b-toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="b-toast"
        :class="`b-toast--${t.kind}`"
        :role="t.kind === 'error' ? 'alert' : 'status'"
        @pointerenter="onPointerEnter($event, t.id)"
        @pointerleave="releaseToast(t.id, 'pointer')"
        @focusin="holdToast(t.id, 'focus')"
        @focusout="onFocusOut($event, t.id)"
      >
        <component :is="icons[t.kind]" class="b-toast__icon" :size="18" aria-hidden="true" />
        <div class="b-toast__text">
          <div class="b-toast__title">{{ t.title }}</div>
          <div v-if="t.detail" class="b-toast__detail">{{ t.detail }}</div>
        </div>
        <button v-if="t.action" type="button" class="b-toast__action" @click="t.action.run(); dismiss(t.id)">{{ t.action.label }}</button>
        <button type="button" class="b-toast__close" aria-label="Dismiss" @click="dismiss(t.id)"><X :size="15" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { CircleCheck, CircleAlert, Info, TriangleAlert, X } from 'lucide-vue-next'
import { toasts, dismiss, holdToast, releaseToast } from './toast'
const icons = { success: CircleCheck, error: CircleAlert, info: Info, warning: TriangleAlert }

// Only a mouse hovers. On a touch screen a tap also sends mouseenter, whose mouseleave comes only
// with a tap somewhere else, which would keep the toast up until then.
function onPointerEnter (ev: PointerEvent, id: number) {
  if (ev.pointerType === 'mouse') holdToast(id, 'pointer')
}

// (moving between the toast's own buttons keeps it held)
function onFocusOut (ev: FocusEvent, id: number) {
  const el = ev.currentTarget as HTMLElement
  if (!el.contains(ev.relatedTarget as Node | null)) releaseToast(id, 'focus')
}
</script>

<style>
.b-toasts {
  position: fixed;
  right: 16px;
  bottom: 16px;
  z-index: 200;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: min(380px, calc(100vw - 32px));
  pointer-events: none;
}
.b-toast {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 12px 12px 14px;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  pointer-events: auto;
}
.b-toast__icon { flex: none; margin-top: 1px; }
.b-toast--success .b-toast__icon { color: var(--success); }
.b-toast--error .b-toast__icon { color: var(--danger); }
.b-toast--info .b-toast__icon { color: var(--info); }
.b-toast--warning .b-toast__icon { color: var(--warning); }
.b-toast__text { flex: 1; min-width: 0; }
.b-toast__title { font-weight: 600; font-size: var(--text-md); }
.b-toast__detail { margin-top: 2px; color: var(--muted); font-size: var(--text-sm); white-space: pre-wrap; word-break: break-word; max-height: 160px; overflow: auto; }
.b-toast__action { border: 0; background: transparent; color: var(--accent); font-weight: 600; cursor: pointer; padding: 2px 4px; }
.b-toast__close { border: 0; background: transparent; color: var(--faint); cursor: pointer; padding: 2px; border-radius: 4px; }
.b-toast__close:hover { color: var(--text); background: var(--surface-hover); }
.b-toast-enter-active, .b-toast-leave-active { transition: all 220ms var(--ease); }
.b-toast-enter-from { opacity: 0; transform: translateY(8px); }
.b-toast-leave-to { opacity: 0; transform: translateX(16px); }
@media (max-width: 640px) {
  /* dialogs become bottom sheets here, with their buttons at the very bottom: keep off them, and
     off the top bar's menu, session and Output buttons */
  .b-toasts { top: calc(var(--topbar-height) + 8px + env(safe-area-inset-top, 0px)); bottom: auto; }
  .b-toast-enter-from { transform: translateY(-8px); }
}
</style>
