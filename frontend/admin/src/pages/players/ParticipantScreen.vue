<template>
  <div class="pscreen">
    <div v-if="html" class="pscreen__frame-wrap" :style="{ height: `${height}px` }">
      <iframe
        class="pscreen__frame"
        sandbox=""
        referrerpolicy="no-referrer"
        :title="title"
        :srcdoc="doc"
      />
    </div>
    <p v-else class="pscreen__empty">Their screen is empty right now.</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * A participant's current screen (their `text`), shown in a sandboxed frame: nothing in it can
 * run or reach the console, and the experiment's style applies inside the frame only (the old
 * console put participant HTML and CSS straight into its own page).
 */
const props = withDefaults(defineProps<{ html?: string | null, css?: string | null, title?: string, height?: number }>(), {
  html: '', css: '', title: 'The participant\'s screen', height: 240,
})

function safeCss (css: string) {
  return css.replace(/<\/style/gi, '<\\/style')
}

const doc = computed(() => {
  const origin = typeof window !== 'undefined' ? window.location.origin : ''
  return `<!doctype html><html><head><meta charset="utf-8">
<base href="${origin}/" target="_blank">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src * data: blob:; media-src *; style-src 'unsafe-inline' *; font-src * data:">
<style>
  html { color-scheme: light; }
  body { margin: 0; padding: 14px 16px; font: 14px/1.55 Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; color: #0f172a; background: #fff; overflow-wrap: anywhere; }
  img, video { max-width: 100%; height: auto; }
  table { border-collapse: collapse; }
  td, th { padding: 4px 8px; border: 1px solid #e5e8f0; }
  a { color: #4f46e5; }
</style>
<style>${safeCss(props.css || '')}</style>
</head><body>${props.html || ''}</body></html>`
})
</script>

<style>
.pscreen__frame-wrap {
  position: relative;
  min-height: 120px;
  max-height: 75vh;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
  resize: vertical;
  background: var(--surface);
}
.pscreen__frame { display: block; width: 100%; height: 100%; border: 0; }
.pscreen__empty {
  padding: 18px 14px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  color: var(--muted);
  font-size: var(--text-sm);
  text-align: center;
}
</style>
