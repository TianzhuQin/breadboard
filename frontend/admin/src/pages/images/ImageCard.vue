<template>
  <li class="ic" :class="{ 'is-fresh': fresh, 'is-busy': busy }">
    <button type="button" class="ic__thumb" :aria-label="`Preview ${image.fileName}`" @click="emit('preview')">
      <img
        v-if="state !== 'error'"
        :src="src"
        alt=""
        loading="lazy"
        decoding="async"
        :class="{ 'is-ready': state === 'ready' }"
        @load="onLoad"
        @error="state = 'error'"
      >
      <span v-if="state === 'loading'" class="ic__skeleton" aria-hidden="true" />
      <span v-if="state === 'error'" class="ic__broken">
        <ImageOff :size="22" aria-hidden="true" />
        <span>Can’t show this image</span>
      </span>
    </button>
    <div class="ic__body">
      <p class="ic__name" :title="image.fileName">{{ image.fileName }}</p>
      <p class="ic__meta">
        <span v-if="dims">{{ dims }}</span>
        <span v-if="dims && ext" aria-hidden="true"> · </span>
        <span v-if="ext">{{ ext.toUpperCase() }}</span>
      </p>
    </div>
    <div class="ic__actions">
      <BButton size="sm" :icon="LinkIcon" :aria-label="`Copy link to ${image.fileName}`" @click="copy(links.byName, 'Link')">Copy link</BButton>
      <BMenu :items="menu" align="right" :width="220">
        <template #trigger="{ toggle, attrs }">
          <BButton size="sm" variant="ghost" :icon="MoreHorizontal" v-bind="attrs" :aria-label="`More actions for ${image.fileName}`" @click="toggle" />
        </template>
      </BMenu>
    </div>
  </li>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Code2, ExternalLink, Hash, ImageOff, Link as LinkIcon, Maximize2, MoreHorizontal, Trash2 } from 'lucide-vue-next'
import { BButton, BMenu, notify, type MenuItem } from '@/ui'
import type { Image } from '@/store/types'
import { copyText, extensionOf, imageLinks, imageSrc } from './images'

const props = defineProps<{ image: Image, experimentId: number, readOnly: boolean, fresh?: boolean, busy?: boolean }>()
const emit = defineEmits<{ preview: [], remove: [] }>()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const dims = ref('')
const src = computed(() => imageSrc(props.experimentId, props.image))
watch(src, () => { state.value = 'loading'; dims.value = '' })
const ext = computed(() => extensionOf(props.image.fileName))
const links = computed(() => imageLinks(props.experimentId, props.image))

function onLoad (e: Event) {
  const img = e.target as HTMLImageElement
  state.value = 'ready'
  if (img.naturalWidth) dims.value = `${img.naturalWidth} × ${img.naturalHeight}`
}

async function copy (text: string, what: string) {
  if (await copyText(text)) notify.success(`${what} copied`, text.length > 90 ? `${text.slice(0, 90)}…` : text)
  else notify.error('Couldn’t copy', 'Your browser blocked the clipboard. Open the preview and copy the link from there.')
}

const menu = computed<MenuItem[]>(() => {
  const items: MenuItem[] = [
    { label: 'Copy HTML tag', icon: Code2, hint: '<img>', run: () => copy(links.value.tag, 'HTML tag') },
    {
      label: 'Copy link by ID',
      icon: Hash,
      hint: props.image.id != null ? `#${props.image.id}` : 'none',
      disabled: !links.value.byId,
      run: () => links.value.byId && copy(links.value.byId, 'Link by ID'),
    },
    { divider: true },
    { label: 'Preview', icon: Maximize2, run: () => emit('preview') },
    { label: 'Open in a new tab', icon: ExternalLink, run: () => window.open(links.value.byName, '_blank', 'noopener') },
  ]
  if (!props.readOnly) {
    items.push({ divider: true }, { label: 'Delete…', icon: Trash2, danger: true, disabled: props.image.id == null, run: () => emit('remove') })
  }
  return items
})
</script>

<style>
.ic {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  transition: box-shadow var(--duration) var(--ease), border-color var(--duration) var(--ease), opacity var(--duration) var(--ease);
}
.ic:hover { box-shadow: var(--shadow); border-color: var(--border-strong); }
.ic.is-fresh { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.ic.is-busy { opacity: 0.55; pointer-events: none; }
.ic__thumb {
  position: relative;
  display: grid;
  place-items: center;
  aspect-ratio: 4 / 3;
  width: 100%;
  padding: 10px;
  border: 0;
  border-bottom: 1px solid var(--border);
  border-radius: var(--radius) var(--radius) 0 0;
  /* a checkerboard, so transparent and white images still show their shape */
  background-color: var(--surface-sunken);
  background-image:
    linear-gradient(45deg, var(--bg-subtle) 25%, transparent 25%, transparent 75%, var(--bg-subtle) 75%),
    linear-gradient(45deg, var(--bg-subtle) 25%, transparent 25%, transparent 75%, var(--bg-subtle) 75%);
  background-size: 16px 16px;
  background-position: 0 0, 8px 8px;
  cursor: zoom-in;
  overflow: hidden;
}
.ic__thumb:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.ic__thumb img { max-width: 100%; max-height: 100%; object-fit: contain; opacity: 0; transition: opacity 200ms var(--ease); }
.ic__thumb img.is-ready { opacity: 1; }
.ic__skeleton { position: absolute; inset: 10px; border-radius: var(--radius-sm); background: var(--bg-subtle); animation: ic-pulse 1.4s ease-in-out infinite; }
@keyframes ic-pulse { 50% { opacity: 0.5; } }
.ic__broken { display: flex; flex-direction: column; align-items: center; gap: 6px; color: var(--muted); font-size: var(--text-xs); text-align: center; }
.ic__body { padding: 10px 12px 2px; min-width: 0; }
.ic__name { font-weight: 600; font-size: var(--text-sm); color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ic__meta { min-height: 18px; font-size: var(--text-xs); color: var(--muted); font-variant-numeric: tabular-nums; }
.ic__actions { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 8px 10px 10px; }
</style>
