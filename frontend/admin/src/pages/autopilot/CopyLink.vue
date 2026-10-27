<template>
  <div class="aplink">
    <span :id="labelId" class="aplink__label">{{ label }}</span>
    <div class="aplink__row">
      <input
        class="aplink__url"
        :value="url"
        readonly
        spellcheck="false"
        :aria-labelledby="labelId"
        :aria-describedby="hint ? `${labelId}-hint` : undefined"
        @focus="($event.target as HTMLInputElement).select()"
      >
      <BButton size="sm" :icon="copied ? Check : Copy" :aria-label="`Copy the ${label.toLowerCase()}`" @click="copy">
        {{ copied ? 'Copied' : 'Copy' }}
      </BButton>
    </div>
    <span v-if="hint" :id="`${labelId}-hint`" class="aplink__hint">{{ hint }}</span>
    <span class="sr-only" aria-live="polite">{{ copied ? `${label} copied` : '' }}</span>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { Check, Copy } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { uid } from '@/ui/uid'

const props = defineProps<{ label: string, url: string, hint?: string }>()
const labelId = uid('aplink')
const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

async function copy () {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(props.url)
    } else {
      // plain-http servers have no clipboard API; fall back to a selection copy
      const ta = document.createElement('textarea')
      ta.value = props.url
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      if (!ok) throw new Error('copy refused')
    }
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { copied.value = false }, 1800)
  } catch (e) {
    notify.error('Couldn\'t copy the link', 'Select it and copy it by hand (Ctrl+C or ⌘C).')
  }
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<style>
.aplink { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.aplink__label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.aplink__hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.45; }
.aplink__row { display: flex; gap: 8px; align-items: center; min-width: 0; }
.aplink__url {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  background: var(--surface-sunken);
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12.5px;
  text-overflow: ellipsis;
}
.aplink__url:focus { outline: 0; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
</style>
