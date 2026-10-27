<template>
  <BDialog :open="!!image" size="xl" :aria-label="image ? `Preview of ${image.fileName}` : 'Preview'" @update:open="v => { if (!v) emit('close') }">
    <template #header>
      <div class="lb__head">
        <h2 class="lb__title" :title="image?.fileName">{{ image?.fileName }}</h2>
        <p class="lb__sub">
          <span v-if="dims">{{ dims }}</span>
          <span v-if="dims && ext" aria-hidden="true"> · </span>
          <span v-if="ext">{{ ext.toUpperCase() }}</span>
          <span v-if="count > 1" class="lb__count">{{ index + 1 }} of {{ count }}</span>
        </p>
      </div>
    </template>

    <div v-if="image" class="lb">
      <div class="lb__stage">
        <img
          v-if="state !== 'error'"
          :key="src"
          :src="src"
          :alt="`${image.fileName}, full size`"
          :class="{ 'is-ready': state === 'ready' }"
          @load="onLoad"
          @error="state = 'error'"
        >
        <BSpinner v-if="state === 'loading'" class="lb__spin" label="Loading the image" />
        <div v-if="state === 'error'" class="lb__broken" role="alert">
          <ImageOff :size="26" aria-hidden="true" />
          <p>This image couldn’t be loaded. The file may be damaged or not an image the browser can show.</p>
        </div>
        <template v-if="count > 1">
          <button type="button" class="lb__nav lb__nav--prev" aria-label="Previous image" @click="emit('step', -1)"><ChevronLeft :size="20" /></button>
          <button type="button" class="lb__nav lb__nav--next" aria-label="Next image" @click="emit('step', 1)"><ChevronRight :size="20" /></button>
        </template>
      </div>

      <div class="lb__side">
        <h3 class="lb__side-title">Use this image</h3>
        <p class="lb__side-text">Paste a link into a content item, a step’s text or the participant page.</p>
        <CopyField label="Link" badge="recommended" :value="links.byName" hint="By file name, and works in file mode too. An imported copy of the experiment gets a new ID, so update these links after importing it." />
        <CopyField v-if="links.byId" label="Link by ID" :value="links.byId" hint="Shorter, but only valid in this database: an imported copy of the experiment gets new IDs." />
        <CopyField label="HTML tag" :value="links.tag" hint="For HTML content. Change the alt text to describe the picture for screen readers." />
        <div class="lb__side-actions">
          <BButton size="sm" :icon="ExternalLink" :href="links.byName" target="_blank" rel="noopener">Open in a new tab</BButton>
          <BButton v-if="!readOnly && image.id != null" size="sm" variant="danger-ghost" :icon="Trash2" :loading="busy" @click="emit('remove')">Delete</BButton>
        </div>
      </div>
    </div>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, ExternalLink, ImageOff, Trash2 } from 'lucide-vue-next'
import { BButton, BDialog, BSpinner } from '@/ui'
import type { Image } from '@/store/types'
import CopyField from './CopyField.vue'
import { extensionOf, imageLinks, imageSrc } from './images'

const props = defineProps<{ image: Image | null, experimentId: number, index: number, count: number, readOnly: boolean, busy?: boolean }>()
const emit = defineEmits<{ close: [], step: [delta: number], remove: [] }>()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const dims = ref('')
const src = computed(() => props.image ? imageSrc(props.experimentId, props.image) : '')
watch(src, () => { state.value = 'loading'; dims.value = '' }, { immediate: true })
const ext = computed(() => props.image ? extensionOf(props.image.fileName) : '')
const links = computed(() => props.image
  ? imageLinks(props.experimentId, props.image)
  : { byName: '', byId: null, tag: '' })

function onLoad (e: Event) {
  const img = e.target as HTMLImageElement
  state.value = 'ready'
  if (img.naturalWidth) dims.value = `${img.naturalWidth} × ${img.naturalHeight}`
}

function onKey (e: KeyboardEvent) {
  // arrows flip through images, except while a text field has the caret or another dialog is on top
  const t = e.target as HTMLElement | null
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return
  const dialog = t?.closest('[role="dialog"]')
  if (dialog && !dialog.querySelector('.lb')) return
  if (e.key === 'ArrowLeft') { e.preventDefault(); emit('step', -1) }
  if (e.key === 'ArrowRight') { e.preventDefault(); emit('step', 1) }
}
watch(() => !!props.image, (open) => {
  if (open) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
}, { immediate: true })
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style>
.lb__head { min-width: 0; }
.lb__title { font-size: 17px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lb__sub { margin-top: 3px; font-size: var(--text-sm); color: var(--muted); font-variant-numeric: tabular-nums; }
.lb__count { margin-left: 10px; padding-left: 10px; border-left: 1px solid var(--border); }
.lb { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 20px; }
.lb__stage {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 280px;
  max-height: 64vh;
  padding: 16px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background-color: var(--surface-sunken);
  background-image:
    linear-gradient(45deg, var(--bg-subtle) 25%, transparent 25%, transparent 75%, var(--bg-subtle) 75%),
    linear-gradient(45deg, var(--bg-subtle) 25%, transparent 25%, transparent 75%, var(--bg-subtle) 75%);
  background-size: 20px 20px;
  background-position: 0 0, 10px 10px;
  overflow: hidden;
}
.lb__stage img { max-width: 100%; max-height: calc(64vh - 34px); object-fit: contain; opacity: 0; transition: opacity 200ms var(--ease); }
.lb__stage img.is-ready { opacity: 1; }
.lb__spin { position: absolute; }
.lb__broken { display: flex; flex-direction: column; align-items: center; gap: 8px; max-width: 320px; text-align: center; color: var(--muted); font-size: var(--text-sm); }
.lb__nav {
  position: absolute;
  top: 50%;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin-top: -18px;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--surface-raised);
  color: var(--text);
  box-shadow: var(--shadow);
  cursor: pointer;
  opacity: 0.85;
}
.lb__nav:hover { opacity: 1; }
.lb__nav--prev { left: 10px; }
.lb__nav--next { right: 10px; }
.lb__side { display: flex; flex-direction: column; gap: 14px; min-width: 0; }
.lb__side-title { font-size: var(--text-md); font-weight: 650; }
.lb__side-text { margin-top: -10px; font-size: var(--text-sm); color: var(--muted); }
.lb__side-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; margin-top: 4px; }
@media (max-width: 860px) {
  .lb { grid-template-columns: minmax(0, 1fr); }
  .lb__stage { min-height: 200px; max-height: 46vh; }
  .lb__stage img { max-height: calc(46vh - 34px); }
}
</style>
