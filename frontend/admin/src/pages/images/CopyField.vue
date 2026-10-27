<template>
  <div class="cf">
    <div class="cf__head">
      <label class="cf__label" :for="id">{{ label }}</label>
      <span v-if="badge" class="cf__badge">{{ badge }}</span>
    </div>
    <div class="cf__row">
      <input
        :id="id"
        class="cf__value mono"
        :value="value"
        readonly
        spellcheck="false"
        :aria-describedby="hint ? `${id}-hint` : undefined"
        @focus="($event.target as HTMLInputElement).select()"
      >
      <BButton size="sm" :icon="copied ? Check : Copy" :aria-label="`Copy ${label.toLowerCase()}`" @click="copy">{{ copied ? 'Copied' : 'Copy' }}</BButton>
    </div>
    <p v-if="hint" :id="`${id}-hint`" class="cf__hint">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Check, Copy } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { copyText } from './images'

/** A read-only value with a copy button; the field can also be selected and copied by hand. */
const props = defineProps<{ label: string, value: string, hint?: string, badge?: string }>()
const id = uid('copy')
const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
// a new value (the preview moved to another image) hasn't been copied yet
watch(() => props.value, () => { copied.value = false })

async function copy () {
  if (await copyText(props.value)) {
    copied.value = true
    clearTimeout(timer)
    timer = setTimeout(() => { copied.value = false }, 1600)
    notify.success(`${props.label} copied`)
  } else {
    notify.error('Couldn’t copy', 'Your browser blocked the clipboard. Click the field, then press Ctrl+C (⌘C on a Mac).')
  }
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<style>
.cf { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.cf__head { display: flex; align-items: center; gap: 8px; }
.cf__label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.cf__badge { padding: 0 7px; border-radius: 999px; background: var(--accent-soft); color: var(--accent-soft-text); font-size: 11px; font-weight: 600; line-height: 18px; }
.cf__row { display: flex; gap: 6px; min-width: 0; }
.cf__value {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 9px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  background: var(--surface-sunken);
  color: var(--text-soft);
  font-size: 12px;
  text-overflow: ellipsis;
}
.cf__value:focus { outline: 0; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.cf__hint { font-size: var(--text-xs); color: var(--muted); }
</style>
