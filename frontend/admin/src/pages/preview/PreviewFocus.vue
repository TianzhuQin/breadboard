<template>
  <BDialog
    :open="!!playerId"
    size="full"
    flush
    :title="playerId ? `What ${playerId} sees` : ''"
    subtitle="Live: clicks here are real choices for this player, and are recorded. Read-only while their own page is connected."
    @update:open="(v: boolean) => { if (!v) emit('close') }"
  >
    <div v-if="playerId" class="pfx">
      <div class="pfx__bar">
        <div class="pfx__nav">
          <BButton size="sm" variant="ghost" :icon="ChevronLeft" :disabled="index <= 0" aria-label="Previous player" title="Previous player" @click="emit('step', -1)" />
          <span class="pfx__pos tabular" aria-live="polite">{{ index + 1 }} of {{ count }}</span>
          <BButton size="sm" variant="ghost" :icon="ChevronRight" :disabled="index >= count - 1" aria-label="Next player" title="Next player" @click="emit('step', 1)" />
        </div>
        <BTabs v-model="device" variant="pill" :tabs="deviceTabs" aria-label="Screen" />
        <BTabs v-model="zoom" variant="pill" :tabs="zoomTabs" aria-label="Zoom" />
        <span class="pfx__scale tabular" :title="`The page is ${DEVICES[device].width}×${DEVICES[device].height} pixels`">{{ Math.round(scale * 100) }}%</span>
        <div class="pfx__end">
          <BButton size="sm" variant="ghost" :icon="RotateCw" @click="nonce++">Reload</BButton>
          <BButton size="sm" :icon="ExternalLink" @click="emit('open-tab')">Open in a new tab</BButton>
        </div>
      </div>
      <div ref="stage" class="pfx__stage" :class="{ 'is-actual': zoom === 'actual' }">
        <PreviewFrame
          :key="`${playerId}-${nonce}`"
          :target="target"
          :title="`Participant page of ${playerId}`"
          :device="device"
          :width="frameWidth"
          interactive
        />
      </div>
    </div>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, ExternalLink, Monitor, RotateCw, Smartphone } from 'lucide-vue-next'
import { BButton, BDialog, BTabs, type TabItem } from '@/ui'
import PreviewFrame from './PreviewFrame.vue'
import { DEVICES, frameGeometry, naturalWidth, widthToFit, type DeviceKind, type PreviewTarget } from './preview'

const props = defineProps<{
  playerId: string | null
  target: PreviewTarget | null
  index: number
  count: number
  initialDevice: DeviceKind
}>()
const emit = defineEmits<{ close: [], step: [delta: number], 'open-tab': [] }>()

const device = ref<DeviceKind>(props.initialDevice)
const zoom = ref<'fit' | 'actual'>('fit')
const nonce = ref(0)
const deviceTabs: TabItem[] = [
  { value: 'desktop', label: 'Desktop', icon: Monitor },
  { value: 'phone', label: 'Phone', icon: Smartphone },
]
const zoomTabs: TabItem[] = [
  { value: 'fit', label: 'Fit' },
  { value: 'actual', label: '100%' },
]
watch(() => props.playerId, (id, old) => {
  if (id && !old) {
    device.value = props.initialDevice
    zoom.value = 'fit'
  }
})

// The stage's size decides how large "Fit" can show the page.
const stage = ref<HTMLElement>()
const box = ref({ w: 0, h: 0 })
let ro: ResizeObserver | null = null
watch(stage, (el) => {
  ro?.disconnect()
  ro = null
  if (!el) return
  const measure = () => { box.value = { w: el.clientWidth, h: el.clientHeight } }
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(measure)
    ro.observe(el)
  }
})
onBeforeUnmount(() => ro?.disconnect())

const frameWidth = computed(() => zoom.value === 'actual'
  ? naturalWidth(device.value)
  : widthToFit(device.value, box.value.w - 48, box.value.h - 48))
const scale = computed(() => frameGeometry(device.value, frameWidth.value).scale)
</script>

<style>
.pfx { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.pfx__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--border);
}
.pfx__nav { display: inline-flex; align-items: center; gap: 2px; }
.pfx__pos { min-width: 56px; text-align: center; font-size: var(--text-sm); color: var(--muted); }
.pfx__scale { font-size: var(--text-xs); color: var(--muted); }
.pfx__end { margin-left: auto; display: inline-flex; gap: 6px; flex-wrap: wrap; }
.pfx__stage {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background: var(--bg-subtle);
}
.pfx__stage.is-actual { overflow: auto; align-items: flex-start; justify-content: flex-start; }
.pfx__stage.is-actual > .pf { margin: auto; }
@media (max-width: 640px) {
  .pfx__bar { padding: 10px 16px; }
  .pfx__end { margin-left: 0; }
  .pfx__stage { padding: 12px; }
}
</style>
