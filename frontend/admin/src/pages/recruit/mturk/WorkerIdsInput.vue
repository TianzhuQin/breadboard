<template>
  <div class="wid">
    <textarea
      :id="id"
      :value="modelValue"
      class="b-textarea rc-mono wid__text"
      rows="8"
      spellcheck="false"
      :aria-describedby="describedby"
      :disabled="disabled"
      placeholder="One worker ID per line"
      @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
    />
    <div class="wid__bar">
      <input ref="file" type="file" accept=".txt,.csv,.tsv,text/plain,text/csv" class="sr-only" tabindex="-1" aria-hidden="true" @change="onFile">
      <BButton size="sm" :icon="FileUp" :disabled="disabled" @click="file?.click()">Load from a file…</BButton>
      <span class="rc-sub" aria-live="polite">
        {{ parsed.ids.length }} worker ID{{ parsed.ids.length === 1 ? '' : 's' }}
        <template v-if="parsed.duplicates.length"> · {{ parsed.duplicates.length }} duplicate{{ parsed.duplicates.length === 1 ? '' : 's' }} left out</template>
      </span>
    </div>
    <div v-if="parsed.invalid.length" class="rc-note rc-note--warning">
      <TriangleAlert :size="16" aria-hidden="true" />
      <span>Skipped because they don’t look like worker IDs: <span class="rc-mono">{{ parsed.invalid.slice(0, 8).join(', ') }}</span><template v-if="parsed.invalid.length > 8"> and {{ parsed.invalid.length - 8 }} more</template>.</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileUp, TriangleAlert } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { parseWorkerIds } from './lib'

/** A list of worker IDs: typed, pasted, or loaded from a text/CSV file (added to what is there). */
const props = defineProps<{ modelValue: string, id?: string, describedby?: string, disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const file = ref<HTMLInputElement>()
const parsed = computed(() => parseWorkerIds(props.modelValue))

async function onFile (e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  input.value = ''
  if (!f) return
  if (f.size > 5 * 1024 * 1024) {
    notify.error('That file is too large', 'Use a plain text or CSV file under 5 MB.')
    return
  }
  try {
    const text = await f.text()
    const found = parseWorkerIds(text).ids.length
    const current = props.modelValue.trim()
    emit('update:modelValue', current ? `${current}\n${text.trim()}` : text.trim())
    notify.info(`Loaded ${f.name}`, `${found} worker ID${found === 1 ? '' : 's'} found.`)
  } catch (err) {
    notify.error('Could not read the file', (err as Error)?.message || String(err))
  }
}
</script>

<style>
.wid { display: flex; flex-direction: column; gap: 8px; }
.wid__text { min-height: 150px; }
.wid__bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
