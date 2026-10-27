<template>
  <BDialog
    :open="open"
    title="New experiment"
    subtitle="Start from Breadboard's template or from a copy of one of yours."
    :dismissable="!busy"
    :closable="!busy"
    @update:open="v => !v && close()"
  >
    <form :id="formId" class="xnew" novalidate @submit.prevent="submit">
      <p v-if="error" class="xnew__error" role="alert">{{ error }}</p>

      <fieldset class="xnew__from">
        <legend class="xnew__legend">Start from</legend>
        <label class="xnew__choice" :class="{ 'is-on': from === 'template' }">
          <input v-model="from" type="radio" name="xnew-from" value="template" class="xnew__radio">
          <span class="xnew__choice-icon" aria-hidden="true"><Sparkles :size="17" /></span>
          <span class="xnew__choice-text">
            <span class="xnew__choice-title">Breadboard's template</span>
            <span class="xnew__choice-hint">Three starter steps (OnJoinStep, OnLeaveStep, InitStep) and the standard participant page.</span>
          </span>
        </label>
        <label class="xnew__choice" :class="{ 'is-on': from === 'copy', 'is-disabled': !experiments.length }">
          <input v-model="from" type="radio" name="xnew-from" value="copy" class="xnew__radio" :disabled="!experiments.length">
          <span class="xnew__choice-icon" aria-hidden="true"><Copy :size="17" /></span>
          <span class="xnew__choice-text">
            <span class="xnew__choice-title">A copy of an experiment</span>
            <span class="xnew__choice-hint">
              {{ experiments.length
                ? 'Copies its steps, content, translations, parameters, images and participant page. Sessions and data stay with the original.'
                : 'You have no experiments to copy yet.' }}
            </span>
          </span>
        </label>
      </fieldset>

      <BField v-if="from === 'copy'" label="Experiment to copy" required>
        <template #default="{ id }">
          <BSelect :id="id" v-model="copyId" :options="copyOptions" />
        </template>
      </BField>

      <BField label="Name" required :error="nameError" hint="This is how it appears in the experiment switcher and in exported file names.">
        <template #default="{ id, describedby }">
          <BInput
            :id="id"
            v-model="name"
            data-autofocus
            autocomplete="off"
            maxlength="120"
            placeholder="e.g. Public goods, 2026 pilot"
            :aria-describedby="describedby"
            :aria-invalid="nameError ? 'true' : undefined"
            @input="touched = true"
          />
        </template>
      </BField>

      <BCheckbox v-model="switchTo" :disabled="!!running">
        Work on it right away
      </BCheckbox>
      <p v-if="running" class="xnew__note">A session is running, so you'll stay on the current experiment. Switch once the session ends.</p>
    </form>

    <template #footer>
      <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
      <BButton variant="primary" type="submit" :form="formId" :loading="busy">Create experiment</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Copy, Sparkles } from 'lucide-vue-next'
import { BButton, BCheckbox, BDialog, BField, BInput, BSelect, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { http } from '@/lib/http'
import { admin, actions, experiments, running } from '@/store/admin'
import type { Experiment } from '@/store/types'
import { describeError, validateExperimentName } from './experiments'

const props = defineProps<{ open: boolean, copyFrom?: number | null }>()
const emit = defineEmits<{ 'update:open': [v: boolean], created: [exp: Experiment] }>()

const formId = uid('xnew')
const from = ref<'template' | 'copy'>('template')
const copyId = ref<number | null>(null)
const name = ref('')
const touched = ref(false)
const tried = ref(false)
const switchTo = ref(true)
const busy = ref(false)
const error = ref('')

const copyOptions = computed(() => experiments.value.map(e => ({ value: e.id, label: e.name })))
const problem = computed(() => validateExperimentName(name.value, experiments.value.map(e => e.name)))
// say "give it a name" only after a try; other problems as soon as they're typed
const nameError = computed(() => (tried.value || (touched.value && name.value.trim())) ? problem.value : null)

// Every opening starts clean (the old dialog kept the previous name and error, §3.4.13).
watch(() => props.open, (open) => {
  if (!open) return
  const source = props.copyFrom != null ? experiments.value.find(e => e.id === props.copyFrom) : null
  from.value = source ? 'copy' : 'template'
  copyId.value = source ? source.id : (admin.experiment?.id ?? experiments.value[0]?.id ?? null)
  name.value = source ? suggestCopyName(source.name) : ''
  touched.value = false
  tried.value = false
  switchTo.value = !running.value
  error.value = ''
  busy.value = false
}, { immediate: true })

function suggestCopyName (base: string) {
  const taken = new Set(experiments.value.map(e => e.name.trim().toLowerCase()))
  let candidate = `${base} copy`
  for (let n = 2; taken.has(candidate.toLowerCase()); n++) candidate = `${base} copy ${n}`
  return candidate
}

function close () {
  if (busy.value) return
  emit('update:open', false)
}

async function submit () {
  tried.value = true
  error.value = ''
  if (problem.value) return
  if (from.value === 'copy' && copyId.value == null) {
    error.value = 'Choose the experiment to copy.'
    return
  }
  busy.value = true
  const finalName = name.value.trim()
  try {
    const created = await http.put<Experiment>('/experiment', {
      newExperimentName: finalName,
      copyExperimentId: from.value === 'copy' ? copyId.value : -1,
    })
    // PUT /experiment doesn't push the researcher's state, so ask for it
    await actions.refresh()
    if (switchTo.value && !running.value && created && typeof created.id === 'number') {
      await actions.selectExperiment(created.id)
      notify.success(`Created “${finalName}”`, 'You are now working on it.')
    } else {
      notify.success(`Created “${finalName}”`)
    }
    if (created) emit('created', created)
    busy.value = false
    emit('update:open', false)
  } catch (e) {
    error.value = `Couldn't create the experiment. ${describeError(e)}`
    busy.value = false
  }
}
</script>

<style>
.xnew { display: flex; flex-direction: column; gap: 18px; }
.xnew__error { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.xnew__from { display: flex; flex-direction: column; gap: 8px; margin: 0; padding: 0; border: 0; min-width: 0; }
.xnew__legend { padding: 0; margin-bottom: 6px; font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.xnew__choice {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--surface);
  cursor: pointer;
  transition: border-color var(--duration) var(--ease), background var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.xnew__choice:hover { background: var(--surface-hover); }
.xnew__choice.is-on { border-color: var(--accent); background: var(--accent-soft); box-shadow: 0 0 0 1px var(--accent) inset; }
.xnew__choice.is-disabled { opacity: 0.55; cursor: not-allowed; }
.xnew__choice:focus-within { box-shadow: 0 0 0 3px var(--accent-ring); }
.xnew__radio { position: absolute; opacity: 0; width: 1px; height: 1px; }
.xnew__choice-icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 9px; background: var(--bg-subtle); color: var(--muted); flex: none; }
.xnew__choice.is-on .xnew__choice-icon { background: var(--surface); color: var(--accent); }
.xnew__choice-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.xnew__choice-title { font-weight: 600; color: var(--text); }
.xnew__choice-hint { font-size: var(--text-sm); color: var(--muted); line-height: 1.45; }
.xnew__note { margin-top: -10px; font-size: var(--text-xs); color: var(--muted); }
</style>
