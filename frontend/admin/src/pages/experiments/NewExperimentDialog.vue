<template>
  <BDialog
    :open="open"
    title="New experiment"
    subtitle="Start from a template, a ready-to-run example, or a copy of one of yours."
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
            <span class="xnew__choice-hint">Three empty starter steps (OnJoinStep, OnLeaveStep, InitStep) and the standard participant page.</span>
          </span>
        </label>
        <label class="xnew__choice" :class="{ 'is-on': from === 'example' }">
          <input v-model="from" type="radio" name="xnew-from" value="example" class="xnew__radio">
          <span class="xnew__choice-icon" aria-hidden="true"><Share2 :size="17" /></span>
          <span class="xnew__choice-text">
            <span class="xnew__choice-title">Example: network game <span class="xnew__tag">Ready to run</span></span>
            <span class="xnew__choice-hint">A complete networked game to try out or build on: a waiting room, ties among neighbors on the network, cutting ties by clicking them, timed rounds and a score. It runs from start to finish by itself.</span>
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

      <BCheckbox v-model="switchTo">
        Work on it right away
      </BCheckbox>
    </form>

    <template #footer>
      <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
      <BButton variant="primary" type="submit" :form="formId" :loading="busy">Create experiment</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Copy, Share2, Sparkles } from 'lucide-vue-next'
import { BButton, BCheckbox, BDialog, BField, BInput, BSelect, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { http } from '@/lib/http'
import { admin, actions, experiments } from '@/store/admin'
import type { Experiment } from '@/store/types'
import { describeError, validateExperimentName } from './experiments'
import { useRouter } from 'vue-router'
import { switchToNew } from './switchTo'

const props = defineProps<{ open: boolean, copyFrom?: number | null }>()
const emit = defineEmits<{ 'update:open': [v: boolean], created: [exp: Experiment] }>()

const formId = uid('xnew')
const router = useRouter()
const from = ref<'template' | 'example' | 'copy'>('template')
const EXAMPLE_NAME = 'Network game'
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
  // (A first experiment: the ready-to-run example is the quickest way to see Breadboard work.)
  from.value = source ? 'copy' : experiments.value.length ? 'template' : 'example'
  copyId.value = source ? source.id : (admin.experiment?.id ?? experiments.value[0]?.id ?? null)
  // (named here: the watch below sees no change when the example was chosen last time too, nor at
  // all when the dialog opens with the page, from the switcher's New)
  name.value = source ? suggestCopyName(source.name) : from.value === 'example' ? suggestCopyName(EXAMPLE_NAME, '') : ''
  touched.value = false
  tried.value = false
  switchTo.value = true
  error.value = ''
  busy.value = false
}, { immediate: true })

// Picking the example names it, unless the researcher has typed a name of their own.
watch(from, (now, before) => {
  const suggested = before === 'example' ? suggestCopyName(EXAMPLE_NAME, '') : ''
  if (now === 'example' && (!name.value.trim() || !touched.value)) {
    name.value = suggestCopyName(EXAMPLE_NAME, '')
  } else if (before === 'example' && name.value === suggested) {
    name.value = ''
  }
})

function suggestCopyName (base: string, suffix = ' copy') {
  const taken = new Set(experiments.value.map(e => e.name.trim().toLowerCase()))
  let candidate = `${base}${suffix}`
  for (let n = 2; taken.has(candidate.toLowerCase()); n++) candidate = `${base}${suffix} ${n}`
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
      ...(from.value === 'example' ? { example: 'network-game' } : {}),
    })
    // PUT /experiment doesn't push the researcher's state, so ask for it
    await actions.refresh()
    if (switchTo.value && created && typeof created.id === 'number') {
      // (the example runs as it is: the next step is a session to try it)
      await switchToNew(created.id, finalName, `Created “${finalName}”`, from.value === 'example'
        ? { label: 'Open a session', detail: 'It runs as it is: open a session, then join it from two tabs with its sign-in link.', run: () => { void router.push('/session?new=1') } }
        : undefined)
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
.xnew__tag { display: inline-block; margin-left: 6px; padding: 1px 7px; border-radius: 999px; background: var(--success-soft); color: var(--success-soft-text); font-size: var(--text-xs); font-weight: 600; vertical-align: 1px; }
</style>
