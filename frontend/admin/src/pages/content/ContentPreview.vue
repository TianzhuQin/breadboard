<template>
  <section class="ct-preview" :aria-label="`Preview of ${title}`">
    <header class="ct-preview__head">
      <div class="ct-preview__titles">
        <span class="ct-preview__title"><Eye :size="14" aria-hidden="true" /> Preview</span>
        <span class="ct-preview__hint">{{ hint }}</span>
      </div>
      <BTabs v-model="width" variant="pill" :tabs="widths" aria-label="Preview width" />
    </header>
    <div class="ct-preview__stage" :class="`is-${width}`">
      <!-- sandbox="" : no scripts, no forms, no popups, no access to the console -->
      <iframe class="ct-preview__frame" sandbox="" referrerpolicy="no-referrer" :srcdoc="doc" :title="`Preview of ${title}`" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Eye, Monitor, Smartphone } from 'lucide-vue-next'
import { BTabs, type TabItem } from '@/ui'
import { previewDocument } from './contentHtml'

const props = defineProps<{
  html: string
  title: string
  styleCss?: string
  emptyText?: string
  /** Which translation is shown: switching to another one updates at once, typing is debounced. */
  docKey?: string
}>()

const widths: TabItem[] = [
  { value: 'wide', label: 'Wide', icon: Monitor },
  { value: 'phone', label: 'Phone', icon: Smartphone },
]
const width = ref('wide')

const build = () => previewDocument(props.html, { style: props.styleCss, emptyText: props.emptyText })
const doc = ref(build())
// typing shouldn't reload the frame on every key
let timer: ReturnType<typeof setTimeout> | null = null
watch(() => [props.html, props.styleCss, props.emptyText, props.docKey], (now, before) => {
  if (timer) clearTimeout(timer)
  if (now[3] !== before[3]) doc.value = build()
  else timer = setTimeout(() => { doc.value = build() }, 220)
})
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })

const hint = computed(() => {
  const parts = ['As participants see it']
  if (props.styleCss && props.styleCss.trim()) parts.push('with your experiment’s style')
  let text = parts.join(', ')
  if (/\{\d+\}/.test(props.html)) text += '. Highlighted {0} marks are filled in by your script'
  return text
})
</script>

<style>
.ct-preview { display: flex; flex-direction: column; min-height: 0; min-width: 0; height: 100%; gap: 8px; }
.ct-preview__head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; min-height: 34px; }
.ct-preview__titles { flex: 1; min-width: 160px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.ct-preview__title { display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-sm); font-weight: 650; color: var(--text-soft); }
.ct-preview__hint { font-size: var(--text-xs); color: var(--muted); }
.ct-preview__stage {
  flex: 1;
  min-height: 0;
  display: flex;
  justify-content: center;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-subtle);
  overflow: hidden;
}
.ct-preview__frame { display: block; width: 100%; height: 100%; border: 0; background: #f5f6fa; }
.ct-preview__stage.is-phone { padding: 12px 0; }
.ct-preview__stage.is-phone .ct-preview__frame { width: 390px; max-width: 100%; border-radius: 18px; box-shadow: var(--shadow); }
</style>
