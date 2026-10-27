<template>
  <aside class="ppanel" :class="`ppanel--${variant}`" :style="{ '--ppanel-w': `${width}px`, '--ppanel-sheet': sheetHeight }" :aria-label="label">
    <div
      class="ppanel__grip"
      role="separator"
      aria-orientation="vertical"
      tabindex="0"
      :aria-valuenow="width"
      :aria-valuemin="MIN"
      :aria-valuemax="max"
      aria-label="Resize the panel (arrow keys)"
      title="Drag to resize"
      @pointerdown="startResize"
      @keydown="onGripKey"
    />
    <div class="ppanel__handle" aria-hidden="true" />
    <div class="ppanel__scroll">
      <slot />
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

/**
 * The right-hand panel that holds a player's details, next to the network or the list. It doesn't
 * block the page (you can keep working on the network). Drag its edge to resize; on a phone it is
 * a sheet at the bottom of the screen.
 */
const props = withDefaults(defineProps<{
  width?: number
  label?: string
  /** dock: fills the height beside a full-page view; sticky: stays in view beside a scrolling page */
  variant?: 'dock' | 'sticky'
  /** Height as a bottom sheet on a phone (lower beside the network, so it stays in view). */
  sheetHeight?: string
}>(), { width: 380, label: 'Player details', variant: 'dock', sheetHeight: 'min(72dvh, 640px)' })
const emit = defineEmits<{ 'update:width': [w: number] }>()

const MIN = 320
const vw = ref(typeof window !== 'undefined' ? window.innerWidth : 1280)
const max = computed(() => Math.max(MIN, Math.min(760, vw.value - 420)))

function clamp (w: number) {
  return Math.round(Math.min(max.value, Math.max(MIN, w)))
}

let start: { x: number, w: number, id: number } | null = null
function startResize (e: PointerEvent) {
  if (e.button !== 0) return
  e.preventDefault()
  vw.value = window.innerWidth
  start = { x: e.clientX, w: props.width, id: e.pointerId }
  ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  document.body.classList.add('ppanel-resizing')
}
function onMove (e: PointerEvent) {
  if (!start || e.pointerId !== start.id) return
  emit('update:width', clamp(start.w + (start.x - e.clientX)))
}
function onUp () {
  start = null
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  document.body.classList.remove('ppanel-resizing')
}
function onGripKey (e: KeyboardEvent) {
  vw.value = window.innerWidth
  if (e.key === 'ArrowLeft') { e.preventDefault(); emit('update:width', clamp(props.width + 24)) }
  else if (e.key === 'ArrowRight') { e.preventDefault(); emit('update:width', clamp(props.width - 24)) }
  else if (e.key === 'Home') { e.preventDefault(); emit('update:width', MIN) }
  else if (e.key === 'End') { e.preventDefault(); emit('update:width', max.value) }
}
onBeforeUnmount(onUp)
</script>

<style>
.ppanel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: var(--ppanel-w);
  min-width: 0;
  background: var(--surface);
}
.ppanel--dock { flex: none; height: 100%; border-left: 1px solid var(--border); }
.ppanel--sticky {
  position: sticky;
  top: 16px;
  max-height: calc(100dvh - var(--topbar-height) - 32px);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.ppanel__scroll { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; }
.ppanel__grip {
  position: absolute;
  left: -4px;
  top: 0;
  bottom: 0;
  width: 8px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
}
.ppanel__grip::after {
  content: '';
  position: absolute;
  left: 3px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: transparent;
  transition: background var(--duration) var(--ease);
}
.ppanel__grip:hover::after, .ppanel__grip:focus-visible::after { background: var(--accent); }
.ppanel__grip:focus-visible { outline: none; }
.ppanel--sticky .ppanel__grip { left: -6px; }
.ppanel__handle { display: none; }
.ppanel-resizing { cursor: col-resize !important; user-select: none; }
.ppanel-resizing * { cursor: col-resize !important; }

@media (max-width: 720px) {
  .ppanel, .ppanel--sticky, .ppanel--dock {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    top: auto;
    z-index: 60;
    width: auto;
    height: var(--ppanel-sheet);
    max-height: none;
    border: 0;
    border-top: 1px solid var(--border);
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    box-shadow: var(--shadow-lg);
    animation: ppanel-up 220ms var(--ease);
  }
  .ppanel__grip { display: none; }
  .ppanel__handle {
    display: block;
    flex: none;
    width: 36px;
    height: 4px;
    margin: 8px auto 0;
    border-radius: 2px;
    background: var(--border-strong);
  }
}
@keyframes ppanel-up { from { transform: translateY(24px); opacity: 0; } to { transform: none; opacity: 1; } }
</style>
