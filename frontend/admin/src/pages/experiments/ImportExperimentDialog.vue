<template>
  <BDialog
    :open="open"
    title="Import an experiment"
    subtitle="Upload a .zip that Breadboard exported. It becomes a new experiment; nothing you have is changed."
    :dismissable="!busy"
    :closable="!busy"
    @update:open="v => !v && close()"
  >
    <form :id="formId" class="ximp" novalidate @submit.prevent="submit">
      <p v-if="error" class="ximp__error" role="alert">{{ error }}</p>

      <div
        class="ximp__drop"
        :class="{ 'is-over': over, 'has-file': !!file, 'is-bad': check && !check.ok }"
        @dragenter.prevent="over = true"
        @dragover.prevent="over = true"
        @dragleave.prevent="onLeave"
        @drop.prevent="onDrop"
        @click="onZoneClick"
      >
        <input
          :id="fileId"
          ref="fileEl"
          type="file"
          accept=".zip,application/zip,application/x-zip-compressed"
          class="ximp__file"
          :disabled="busy"
          :aria-describedby="`${fileId}-status`"
          @change="onPick"
        >
        <template v-if="!file">
          <span class="ximp__drop-icon" aria-hidden="true"><FileArchive :size="22" /></span>
          <span class="ximp__drop-title">Drop the .zip here</span>
          <span class="ximp__drop-hint">or <label :for="fileId" class="ximp__browse">choose a file</label> from your computer</span>
        </template>
        <div v-else class="ximp__chosen">
          <span class="ximp__drop-icon" aria-hidden="true"><FileArchive :size="20" /></span>
          <span class="ximp__chosen-text">
            <span class="ximp__chosen-name">{{ file.name }}</span>
            <span class="ximp__chosen-meta">{{ sizeText }}</span>
          </span>
          <label :for="fileId" class="ximp__change">Change</label>
        </div>
      </div>

      <div :id="`${fileId}-status`" aria-live="polite">
        <p v-if="checking" class="ximp__check"><BSpinner :size="14" /> Checking the file…</p>
        <p v-else-if="check && !check.ok" class="ximp__check is-bad"><CircleAlert :size="15" aria-hidden="true" /> {{ check.problem }}</p>
        <template v-else-if="check">
          <p class="ximp__check is-ok">
            <CircleCheck :size="15" aria-hidden="true" />
            <span v-if="check.steps || check.content">Looks like a Breadboard experiment: {{ summary }}.</span>
            <span v-else>Ready to import.</span>
          </p>
          <p v-if="check.warning" class="ximp__check is-warn"><TriangleAlert :size="15" aria-hidden="true" /> {{ check.warning }}</p>
        </template>
      </div>

      <BField label="Name" required :error="nameError" hint="Filled in from the file. Change it if you like.">
        <template #default="{ id, describedby }">
          <BInput
            :id="id"
            v-model="name"
            autocomplete="off"
            maxlength="120"
            :disabled="busy"
            :aria-describedby="describedby"
            :aria-invalid="nameError ? 'true' : undefined"
            @input="touched = true"
          />
        </template>
      </BField>

      <BCheckbox v-model="switchTo" :disabled="!!running || busy">Work on it after importing</BCheckbox>
      <p v-if="running" class="ximp__note">A session is running, so you'll stay on the current experiment.</p>
    </form>

    <template #footer>
      <span v-if="busy" class="ximp__progress" role="status"><BSpinner :size="14" /> Uploading and unpacking…</span>
      <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
      <BButton variant="primary" type="submit" :form="formId" :loading="busy" :disabled="checking || (!!check && !check.ok)" :icon="Upload">Import</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CircleAlert, CircleCheck, FileArchive, TriangleAlert, Upload } from 'lucide-vue-next'
import { BButton, BCheckbox, BDialog, BField, BInput, BSpinner, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { http } from '@/lib/http'
import { actions, experiments, running } from '@/store/admin'
import type { Experiment } from '@/store/types'
import {
  checkExperimentZip, describeError, importNameFromFile, importUrl, manifestName, plural, readBytes, validateExperimentName,
  type ZipCheck,
} from './experiments'
import { switchToNew } from './switchTo'

const props = defineProps<{ open: boolean, initialFile?: File | null }>()
const emit = defineEmits<{ 'update:open': [v: boolean], imported: [exp: Experiment] }>()

const formId = uid('ximp')
const fileId = uid('ximp-file')
const fileEl = ref<HTMLInputElement>()
const file = ref<File | null>(null)
const check = ref<ZipCheck | null>(null)
const checking = ref(false)
const over = ref(false)
const name = ref('')
const touched = ref(false)
const tried = ref(false)
const switchTo = ref(true)
const busy = ref(false)
const error = ref('')

const problem = computed(() => validateExperimentName(name.value, experiments.value.map(e => e.name)))
const nameError = computed(() => (tried.value || (touched.value && name.value.trim())) ? problem.value : null)
const sizeText = computed(() => {
  const b = file.value?.size || 0
  return b >= 1048576 ? `${(b / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`
})
const summary = computed(() => {
  const c = check.value
  if (!c) return ''
  const parts = [plural(c.steps, 'step'), plural(c.content, 'content page')]
  if (c.images) parts.push(plural(c.images, 'image'))
  return parts.join(', ')
})

watch(() => props.open, (open) => {
  if (!open) return
  reset()
  if (props.initialFile) choose(props.initialFile)
}, { immediate: true })

function reset () {
  file.value = null
  check.value = null
  checking.value = false
  name.value = ''
  touched.value = false
  tried.value = false
  switchTo.value = !running.value
  busy.value = false
  error.value = ''
  over.value = false
  if (fileEl.value) fileEl.value.value = ''
}

let seq = 0
async function choose (f: File) {
  const mine = ++seq
  file.value = f
  error.value = ''
  check.value = null
  // the name follows the file until the researcher types their own
  if (!touched.value || !name.value.trim()) {
    name.value = importNameFromFile(f.name)
    touched.value = false
  }
  checking.value = true
  try {
    const bytes = await readBytes(f)
    if (mine !== seq) return
    const result = checkExperimentZip(bytes)
    check.value = result
    if (result.ok && !touched.value) {
      const recorded = await manifestName(bytes, result)
      if (mine === seq && recorded && !touched.value) name.value = recorded
    }
  } catch (e) {
    if (mine === seq) check.value = { ok: true, root: '', steps: 0, content: 0, images: 0, warning: 'This file couldn\'t be checked before uploading. The server will check it.' }
  } finally {
    if (mine === seq) checking.value = false
  }
}

function onPick (e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  if (f) choose(f)
}
function onLeave (e: DragEvent) {
  // dragleave also fires when moving over the children
  if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) over.value = false
}
function onZoneClick (e: MouseEvent) {
  // the whole empty area opens the file picker, not only the "choose a file" link
  if (file.value || busy.value) return
  if ((e.target as HTMLElement).closest('label, input')) return
  fileEl.value?.click()
}
function onDrop (e: DragEvent) {
  over.value = false
  if (busy.value) return
  const f = e.dataTransfer?.files?.[0]
  if (f) choose(f)
}

function close () {
  if (busy.value) return
  emit('update:open', false)
}

async function submit () {
  tried.value = true
  error.value = ''
  if (!file.value) {
    error.value = 'Choose the .zip file to import.'
    return
  }
  if (checking.value) return
  // the reason is already shown under the file
  if (check.value && !check.value.ok) return
  if (problem.value) return
  const finalName = name.value.trim()
  const body = new FormData()
  body.append('file', file.value, file.value.name)
  busy.value = true
  try {
    const imported = await http.post<Experiment>(importUrl(finalName), body)
    const id = imported && typeof imported.id === 'number' ? imported.id : null
    if (switchTo.value && !running.value && id != null) {
      // (switching sends the new list too)
      if (!await switchToNew(id, finalName, `Imported “${finalName}”`)) await actions.refresh()
    } else {
      // nothing else pushes the new list (the old console showed it only after a reload)
      await actions.refresh()
      notify.success(`Imported “${finalName}”`)
    }
    if (imported) emit('imported', imported)
    busy.value = false
    emit('update:open', false)
  } catch (e) {
    // keep the file and name so a retry is one click (the old dialog cleared both)
    error.value = `The server didn't import it. ${describeError(e)}`
    busy.value = false
  }
}
</script>

<style>
.ximp { display: flex; flex-direction: column; gap: 16px; }
.ximp__error { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.ximp__drop {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 26px 16px;
  border: 1.5px dashed var(--border-strong);
  border-radius: var(--radius);
  background: var(--surface-sunken);
  text-align: center;
  transition: border-color var(--duration) var(--ease), background var(--duration) var(--ease);
}
.ximp__drop:not(.has-file) { cursor: pointer; }
.ximp__drop.is-over { border-color: var(--accent); background: var(--accent-soft); }
.ximp__drop.has-file { padding: 12px 14px; border-style: solid; align-items: stretch; text-align: left; }
.ximp__drop.is-bad { border-color: var(--danger); }
.ximp__drop:focus-within { box-shadow: 0 0 0 3px var(--accent-ring); border-color: var(--accent); }
.ximp__file { position: absolute; opacity: 0; width: 1px; height: 1px; overflow: hidden; }
.ximp__drop-icon { display: grid; place-items: center; width: 42px; height: 42px; border-radius: 12px; background: var(--accent-soft); color: var(--accent); flex: none; margin-bottom: 6px; }
.ximp__drop.has-file .ximp__drop-icon { width: 36px; height: 36px; border-radius: 10px; margin: 0; }
.ximp__drop-title { font-weight: 600; }
.ximp__drop-hint { font-size: var(--text-sm); color: var(--muted); }
.ximp__browse, .ximp__change { color: var(--accent); font-weight: 600; cursor: pointer; }
.ximp__browse:hover, .ximp__change:hover { text-decoration: underline; }
.ximp__chosen { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ximp__chosen-text { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.ximp__chosen-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ximp__chosen-meta { font-size: var(--text-xs); color: var(--muted); }
.ximp__change { font-size: var(--text-sm); flex: none; }
.ximp__check { display: flex; align-items: flex-start; gap: 8px; font-size: var(--text-sm); color: var(--muted); line-height: 1.45; }
.ximp__check + .ximp__check { margin-top: 6px; }
.ximp__check svg { flex: none; margin-top: 2px; }
.ximp__check.is-ok svg { color: var(--success); }
.ximp__check.is-bad { color: var(--danger-soft-text); }
.ximp__check.is-warn { color: var(--warning-soft-text); }
.ximp__note { margin-top: -8px; font-size: var(--text-xs); color: var(--muted); }
.ximp__progress { display: inline-flex; align-items: center; gap: 8px; margin-right: auto; font-size: var(--text-sm); color: var(--muted); }
</style>
