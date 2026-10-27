<template>
  <section class="outd" :style="{ height: `${height}px` }" aria-label="Output">
    <div class="outd__grip" role="separator" aria-orientation="horizontal" aria-label="Resize the output panel" tabindex="0" @pointerdown="startResize" @keydown.up.prevent="nudge(24)" @keydown.down.prevent="nudge(-24)" />
    <header class="outd__head">
      <BTabs v-model="filter" :tabs="tabs" variant="pill" aria-label="Show" />
      <span class="outd__spacer" />
      <BButton size="sm" variant="ghost" :icon="Copy" :disabled="!shown.length" @click="copy">Copy</BButton>
      <BButton size="sm" variant="ghost" :icon="Trash2" :disabled="!admin.output.length" @click="actions.clearOutput()">Clear</BButton>
      <BButton size="sm" variant="ghost" :icon="X" aria-label="Close the output panel" @click="emit('close')" />
    </header>
    <div ref="scroller" class="outd__body" role="log" aria-live="polite" @scroll="onScroll">
      <p v-if="!shown.length" class="outd__empty">{{ filter === 'errors' ? 'No errors.' : 'What the experiment\'s scripts print and return appears here.' }}</p>
      <div v-for="e in shown" :key="e.id" class="outd__entry" :class="`is-${e.kind}`">
        <span class="outd__time">{{ time(e.at) }}</span>
        <component :is="icon(e.kind)" :size="14" class="outd__icon" aria-hidden="true" />
        <pre class="outd__text">{{ e.text }}</pre>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Copy, Trash2, X, ChevronRight, CircleAlert, Info, CornerDownRight } from 'lucide-vue-next'
import { BButton, BTabs, notify } from '@/ui'
import { admin, actions } from '@/store/admin'

const emit = defineEmits<{ close: [] }>()
const filter = ref<'all' | 'errors'>('all')
const errorCount = computed(() => admin.output.filter(e => e.kind === 'error').length)
const tabs = computed(() => [
  { value: 'all', label: 'All' },
  { value: 'errors', label: 'Errors', count: errorCount.value || null },
])
const shown = computed(() => filter.value === 'errors' ? admin.output.filter(e => e.kind === 'error') : admin.output)

const KEY = 'bb-admin-output-height'
const height = ref(readHeight())
function readHeight () {
  try { return Math.min(700, Math.max(140, Number(localStorage.getItem(KEY)) || 260)) } catch (e) { return 260 }
}
function setHeight (h: number) {
  height.value = Math.min(Math.round(window.innerHeight * 0.8), Math.max(120, h))
  try { localStorage.setItem(KEY, String(height.value)) } catch (e) { /* ignore */ }
}
function nudge (d: number) { setHeight(height.value + d) }
function startResize (e: PointerEvent) {
  const startY = e.clientY
  const startH = height.value
  const move = (ev: PointerEvent) => setHeight(startH + (startY - ev.clientY))
  const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

const scroller = ref<HTMLElement>()
let stick = true
function onScroll () {
  const el = scroller.value
  if (el) stick = el.scrollHeight - el.scrollTop - el.clientHeight < 40
}
watch(() => shown.value.length, async () => {
  await nextTick()
  if (stick && scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}, { immediate: true })

function time (t: number) {
  return new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
function icon (kind: string) {
  return kind === 'error' ? CircleAlert : kind === 'info' ? Info : kind === 'input' ? ChevronRight : CornerDownRight
}
async function copy () {
  const text = shown.value.map(e => `[${time(e.at)}] ${e.kind === 'input' ? '> ' : ''}${e.text}`).join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    notify.success('Copied the output')
  } catch (e) {
    notify.error('Could not copy', 'Your browser blocked the clipboard.')
  }
}
</script>

<style>
.outd {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: none;
  border-top: 1px solid var(--border);
  background: var(--surface);
  min-height: 0;
}
.outd__grip { position: absolute; top: -4px; left: 0; right: 0; height: 8px; cursor: ns-resize; z-index: 2; }
.outd__grip:focus-visible { outline: 2px solid var(--accent); outline-offset: -3px; }
.outd__head { display: flex; align-items: center; gap: 4px; padding: 8px 12px; border-bottom: 1px solid var(--border); }
.outd__spacer { flex: 1; }
.outd__body { flex: 1; overflow: auto; padding: 6px 0; background: var(--code-bg); font-family: var(--font-mono); font-size: 12.5px; }
.outd__empty { padding: 18px; color: var(--muted); font-family: var(--font); font-size: var(--text-sm); }
.outd__entry { display: flex; align-items: flex-start; gap: 9px; padding: 5px 14px; border-left: 3px solid transparent; }
.outd__entry + .outd__entry { border-top: 1px solid var(--border); }
.outd__entry.is-error { background: var(--danger-soft); border-left-color: var(--danger); }
.outd__entry.is-error .outd__text { color: var(--danger-soft-text); }
.outd__entry.is-info .outd__text { color: var(--info-soft-text); }
.outd__entry.is-input .outd__text { color: var(--muted); }
.outd__time { color: var(--faint); white-space: nowrap; font-size: 11.5px; padding-top: 1px; }
.outd__icon { flex: none; margin-top: 2px; color: var(--faint); }
.outd__entry.is-error .outd__icon { color: var(--danger); }
.outd__text { margin: 0; flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-word; color: var(--text); font-size: inherit; }
</style>
