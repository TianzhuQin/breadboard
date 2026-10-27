<template>
  <div class="steps" :class="{ 'show-editor': mobileEditor }">
    <aside class="steps__list" aria-label="Steps">
      <StepList
        :drafts="bucket?.drafts || []"
        :selected="bucket?.selected || null"
        :file-mode="fileMode"
        :loading="!!bucket?.loading && !bucket?.loaded"
        :saving-all="savingAll"
        :mod-key="modKey"
        @select="select"
        @create="create"
        @create-builtin="addBuiltin"
        @save-all="saveAll"
      />
    </aside>

    <section class="steps__main" :aria-label="current ? `Step ${current.name}` : 'Step editor'">
      <div v-if="fileMode" class="steps__banner is-file" role="note">
        <FolderOpen :size="17" aria-hidden="true" class="steps__banner-icon" />
        <p class="steps__banner-text">
          <strong>Read from files.</strong> This experiment is in file mode: its steps come from the .groovy files in its
          folder on the server, and this page follows them as they change. Editing here is off so nothing overwrites those files.
        </p>
        <BButton size="sm" @click="router.push('/experiments')">Switch off file mode…</BButton>
      </div>
      <div v-else-if="admin.engine === 'stale'" class="steps__banner is-stale" role="status">
        <RotateCw :size="16" aria-hidden="true" class="steps__banner-icon" />
        <p class="steps__banner-text">
          <strong>The engine isn't running your latest steps.</strong> Reload it to load everything that is saved,
          or use Run on a step to load just that one.
        </p>
        <BButton size="sm" @click="reloadEngine">Reload engine</BButton>
      </div>
      <div v-if="stranded.length" class="steps__banner is-file is-stranded" role="status">
        <TriangleAlert :size="16" aria-hidden="true" class="steps__banner-icon" />
        <p class="steps__banner-text">
          <strong>Not saved: {{ stranded.map(d => d.name).join(', ') }}.</strong>
          You changed {{ stranded.length === 1 ? 'this step' : 'these steps' }} before file mode was switched on, and
          {{ stranded.length === 1 ? 'it' : 'they' }} can't be saved while the steps come from files. Switch file mode off
          to save, or copy the code and discard the changes.
        </p>
        <BButton size="sm" :icon="Copy" @click="copyStranded">Copy code</BButton>
        <BButton size="sm" variant="ghost" @click="discardStranded">Discard…</BButton>
      </div>

      <div v-if="!bucket || (bucket.loading && !bucket.loaded)" class="steps__state" role="status">
        <BSpinner :size="22" />
        <span>Loading the steps…</span>
      </div>
      <div v-else-if="bucket.loadError && !bucket.loaded" class="steps__state">
        <BEmpty :icon="CircleAlert" title="Couldn't load the steps" :text="bucket.loadError">
          <template #actions><BButton variant="primary" :icon="RotateCw" @click="reload">Try again</BButton></template>
        </BEmpty>
      </div>
      <div v-else-if="!current" class="steps__state">
        <BEmpty
          :icon="ListOrdered"
          :title="bucket.drafts.length ? 'Choose a step' : 'No steps yet'"
          :text="bucket.drafts.length
            ? 'Pick a step on the left to read or edit its code.'
            : 'Steps are the Groovy code that runs your experiment: InitStep starts the game, the others are rounds, questions and payoffs.'"
        >
          <template v-if="!fileMode && !bucket.drafts.length" #actions>
            <BButton variant="primary" :icon="Plus" @click="create('sync')">New step</BButton>
          </template>
        </BEmpty>
      </div>

      <template v-else>
        <header class="steps__head">
          <BButton class="steps__back" variant="ghost" size="sm" :icon="ArrowLeft" aria-label="Back to all steps" @click="mobileEditor = false" />
          <div class="steps__name">
            <BField v-if="editingName" :error="nameError" class="steps__name-field">
              <template #default="{ id, describedby }">
                <BInput
                  :id="id"
                  ref="nameInput"
                  :model-value="cur.name"
                  size="sm"
                  aria-label="Step name"
                  :aria-describedby="describedby"
                  :aria-invalid="nameError ? 'true' : undefined"
                  autocomplete="off"
                  spellcheck="false"
                  @update:model-value="onNameInput"
                  @keydown.enter.prevent="commitName"
                  @keydown.esc.prevent.stop="cancelName"
                  @blur="commitName"
                />
              </template>
            </BField>
            <template v-else>
              <h2 class="steps__title" :title="cur.name">{{ cur.name }}</h2>
              <BButton
                v-if="!fileMode && !builtin"
                size="sm"
                variant="ghost"
                :icon="Pencil"
                :aria-label="`Rename ${cur.name}`"
                title="Rename"
                @click="startRename"
              />
              <span v-if="builtin" class="steps__lock" title="Built-in: Breadboard calls this step by name, so it can't be renamed or deleted">
                <Lock :size="13" aria-hidden="true" /> Built in
              </span>
            </template>
          </div>
          <span class="steps__status" role="status">
            <BBadge v-if="cur.status === 'saving'" tone="info" dot pulse>Saving…</BBadge>
            <BBadge v-else-if="cur.status === 'deleting'" tone="info" dot pulse>Deleting…</BBadge>
            <BBadge v-else-if="cur.status === 'saved'" tone="success" dot>Saved</BBadge>
            <BBadge v-else-if="fileMode" tone="neutral">From file</BBadge>
            <BBadge v-else-if="isNewStep" tone="warning" dot>New · not saved</BBadge>
            <BBadge v-else-if="dirty" tone="warning" dot>Unsaved changes</BBadge>
          </span>
          <span class="steps__spacer" />
          <div v-if="!fileMode" class="steps__actions">
            <BButton
              :icon="Play"
              :loading="cur.run?.status === 'pending'"
              :disabled="cur.status === 'saving' || cur.status === 'deleting'"
              :title="dirty
                ? `Saves, then runs this step's code in the live engine so the change takes effect now. It doesn't start the step. (${modKey}+Enter)`
                : `Runs this step's code in the live engine, so the engine uses this version. It doesn't start the step. (${modKey}+Enter)`"
              @click="saveAndRunCurrent"
            >
              {{ dirty ? 'Save & run' : 'Run' }}
            </BButton>
            <BButton
              variant="primary"
              :icon="Save"
              :loading="cur.status === 'saving'"
              :disabled="!dirty || cur.status === 'deleting'"
              :title="`Save this step (${modKey}+S)`"
              @click="saveCurrent"
            >
              Save
            </BButton>
            <BMenu :items="moreItems" align="right" :width="250">
              <template #trigger="{ toggle, attrs }">
                <BButton variant="ghost" :icon="Ellipsis" :aria-label="`More actions for ${cur.name}`" v-bind="attrs" @click="toggle" />
              </template>
            </BMenu>
          </div>
        </header>

        <p v-if="builtin" class="steps__about">
          <Info :size="15" aria-hidden="true" />
          <span>{{ builtin.detail }}</span>
        </p>
        <p v-else-if="isNewStep && cur.template" class="steps__about">
          <Info :size="15" aria-hidden="true" />
          <span>A new {{ templateLabel }}. Its code follows the name until you change the code. It is added to the experiment when you save it.</span>
        </p>

        <div v-if="cur.error && cur.status === 'error'" class="steps__alert" role="alert">
          <CircleAlert :size="16" aria-hidden="true" />
          <p><strong>Not saved.</strong> {{ cur.error }}</p>
          <BButton size="sm" variant="ghost" :icon="X" aria-label="Dismiss" @click="dismissError" />
        </div>
        <div v-if="cur.orphaned" class="steps__alert is-warn" role="status">
          <TriangleAlert :size="16" aria-hidden="true" />
          <p>This step was deleted elsewhere while you were editing it. Saving adds it to the experiment again as a new step.</p>
        </div>

        <div class="steps__editor">
          <KeepAlive :max="24">
            <BCodeEditor
              :key="cur.uid"
              ref="editor"
              v-model="cur.source"
              language="groovy"
              fill
              :readonly="fileMode"
              :aria-label="`Code of the step ${cur.name}`"
              :placeholder="fileMode ? '' : '// The Groovy code of this step'"
              :on-run="saveAndRunCurrent"
              :on-save="saveCurrent"
            />
          </KeepAlive>
        </div>

        <footer class="steps__foot">
          <div v-if="cur.run" class="srun" :class="`is-${cur.run.status}`">
            <div class="srun__head">
              <BSpinner v-if="cur.run.status === 'pending'" :size="13" label="Running" />
              <CircleCheck v-else-if="cur.run.status === 'ok'" :size="15" aria-hidden="true" />
              <Hourglass v-else-if="cur.run.status === 'noreply'" :size="15" aria-hidden="true" />
              <WifiOff v-else-if="cur.run.status === 'notsent'" :size="15" aria-hidden="true" />
              <span class="srun__title">{{ runTitle }}</span>
              <span class="srun__time">{{ clock(cur.run.at) }}</span>
              <span class="steps__spacer" />
              <BButton size="sm" variant="ghost" :icon="PanelBottom" title="All engine output (Ctrl+J)" @click="openOutput = true">Output</BButton>
              <BButton size="sm" variant="ghost" :icon="X" aria-label="Hide this result" @click="cur.run = null" />
            </div>
            <pre v-if="cur.run.status === 'ok' && cur.run.values.length" class="srun__value">{{ cur.run.values.join('\n') }}</pre>
            <EngineError
              v-if="cur.run.status === 'error' && cur.run.error"
              :error="cur.run.error"
              :from-line="1"
              own-name="this step"
              @goto="goto"
            />
          </div>
          <p v-else-if="cur.engineBehind && !fileMode" class="steps__hint is-behind">
            <Info :size="14" aria-hidden="true" />
            Saved. The live engine isn't using this version yet: <strong>Run</strong> loads it now (it doesn't start the step).
          </p>
          <p v-else class="steps__hint">
            <template v-if="fileMode">Edit the file on the server to change this step.</template>
            <template v-else>
              <kbd>{{ modSymbol }}S</kbd> save · <kbd>{{ modSymbol }}↵</kbd> save &amp; run · <kbd>⇧{{ modSymbol }}S</kbd> save all
            </template>
          </p>
        </footer>
      </template>
    </section>

    <UnsavedDialog :open="leave.open" :names="leave.names" :saving="leave.saving" @choose="onLeaveChoice" />
    <p class="sr-only" aria-live="polite">{{ announce }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import {
  ArrowLeft, CircleAlert, CircleCheck, Copy, CopyPlus, Ellipsis, FolderOpen, Hourglass, Info, ListOrdered, Lock,
  PanelBottom, Pencil, Play, Plus, RotateCw, Save, Trash2, TriangleAlert, Undo2, WifiOff, X,
} from 'lucide-vue-next'
import {
  BBadge, BButton, BCodeEditor, BEmpty, BField, BInput, BMenu, BSpinner, confirm, notify, toast, type MenuItem,
} from '@/ui'
import { actions, admin, ensureConnected } from '@/store/admin'
import { confirmEngineRebuild } from '@/store/engineGuard'
import { openOutput } from '@/shell/output'
import EngineError from '../console/EngineError.vue'
import { goToLine, viewIn } from '../console/editorView'
import { parseGroovyError } from '../console/groovyError'
import StepList from './StepList.vue'
import UnsavedDialog from './UnsavedDialog.vue'
import {
  TEMPLATES, builtinOf, isDirty, isNew, newDraft, renameDraft, sortDrafts, validateName,
  type Builtin, type LeaveChoice, type StepDraft, type TemplateKind,
} from './stepsModel'
import {
  addDraft, bucketKey, buckets, createBuiltin, createFromTemplate, deleteDraft, duplicate, ensureBucket, loadSteps,
  mergeFromServer, refreshStore, removeDraft, runDraft, saveDraft, type StepsBucket,
} from './stepsStore'

const router = useRouter()
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)
const modKey = isMac ? 'Cmd' : 'Ctrl'
const modSymbol = isMac ? '⌘' : 'Ctrl+'

// ---- which experiment, which steps ------------------------------------------------------------

const editingName = ref(false)
const nameError = ref<string | null>(null)
const nameInput = ref<InstanceType<typeof BInput>>()
let nameBefore = ''

const fileMode = computed(() => !!admin.experiment?.fileMode)
const key = computed(() => admin.experiment ? bucketKey(admin.experiment.id, fileMode.value) : null)
const bucket = computed<StepsBucket | undefined>(() => key.value ? buckets.get(key.value) : undefined)

watch(key, () => {
  if (!admin.experiment) return
  const b = ensureBucket(admin.experiment.id, fileMode.value)
  editingName.value = false
  // the steps in the newest User JSON first (instant), then a fresh copy from the server
  if (Array.isArray(admin.experiment.steps)) mergeFromServer(b, admin.experiment.steps)
  loadSteps(b).then(() => {
    if (b.loadError && b.loaded) notify.warning('Couldn\'t refresh the steps', `${b.loadError} Showing the last copy the server sent.`)
  })
}, { immediate: true })

// pushes (and the refresh after a save) bring the server's steps; in file mode they follow the files
watch(() => admin.experiment?.steps, (steps) => {
  const b = bucket.value
  if (b && Array.isArray(steps) && admin.experiment?.id === b.experimentId) mergeFromServer(b, steps)
})

/**
 * Unsaved edits from before file mode was switched on. They are kept (and count as unsaved), but can
 * only be saved once file mode is off again: say so, and offer to copy or discard them.
 */
const stranded = computed<StepDraft[]>(() => {
  const exp = admin.experiment
  if (!exp || !fileMode.value) return []
  const b = buckets.get(bucketKey(exp.id, false))
  return b ? sortDrafts(b.drafts.filter(isDirty)) : []
})

async function copyStranded () {
  const list = stranded.value
  try {
    await navigator.clipboard.writeText(list.map(d => `// ---- ${d.name} ----\n${d.source}`).join('\n\n'))
    notify.success(list.length === 1 ? 'Copied the code' : `Copied the code of ${list.length} steps`)
  } catch (e) {
    notify.error('Could not copy', 'Your browser blocked the clipboard.')
  }
}

async function discardStranded () {
  const exp = admin.experiment
  const list = stranded.value
  if (!exp || !list.length) return
  const ok = await confirm({
    title: list.length === 1 ? `Discard your changes to “${list[0].name}”?` : `Discard your changes to ${list.length} steps?`,
    message: 'Your edits are thrown away. The step files, and the steps saved before file mode was switched on, stay as they are.',
    confirmLabel: 'Discard changes',
    danger: true,
  })
  const b = buckets.get(bucketKey(exp.id, false))
  if (!ok || !b) return
  for (const d of list) {
    if (isNew(d)) {
      removeDraft(b, d)
    } else {
      d.name = d.savedName ?? d.name
      d.source = d.savedSource ?? ''
    }
  }
}

const current = computed<StepDraft | null>(() => {
  const b = bucket.value
  return (b && b.drafts.find(d => d.uid === b.selected)) || null
})
/** The selected step, for the part of the template shown only when there is one. */
const cur = computed(() => current.value as StepDraft)
// keep something selected: InitStep first, as the old console did
watch(() => [bucket.value?.loaded, bucket.value?.drafts.length, bucket.value?.selected], () => {
  const b = bucket.value
  if (!b || current.value) return
  b.selected = sortDrafts(b.drafts)[0]?.uid ?? null
}, { immediate: true })

const builtin = computed(() => current.value ? builtinOf(current.value.savedName ?? current.value.name) : null)
const dirty = computed(() => !!current.value && !fileMode.value && isDirty(current.value))
const isNewStep = computed(() => !!current.value && isNew(current.value))
const templateLabel = computed(() => {
  const t = TEMPLATES.find(x => x.kind === current.value?.template)
  return t ? `${t.label.toLowerCase()} step` : 'step'
})

const mobileEditor = ref(false)
const announce = ref('')

function select (uid: string) {
  const b = bucket.value
  if (!b) return
  if (editingName.value) finishNameEdit()
  b.selected = uid
  mobileEditor.value = true
}

// ---- the editor ---------------------------------------------------------------------------------

const editor = ref<InstanceType<typeof BCodeEditor>>()
function editorView () {
  return viewIn(editor.value?.$el as Element | undefined)
}
function focusEditor () {
  nextTick(() => editor.value?.focus())
}
function goto (line: number, column?: number) {
  const v = editorView()
  if (v) goToLine(v, line, column)
}

// ---- naming (new steps get an inline name, and their template follows it, DL-5) ---------------

function startRename () {
  const d = current.value
  if (!d || fileMode.value || builtinOf(d.savedName ?? '')) return
  nameBefore = d.name
  nameError.value = bucket.value ? validateName(d.name, bucket.value.drafts, d) : null
  editingName.value = true
  nextTick(() => {
    nameInput.value?.focus()
    nameInput.value?.select()
  })
}
function onNameInput (v: string) {
  const d = current.value
  if (!d || !bucket.value) return
  renameDraft(d, v)
  nameError.value = validateName(v, bucket.value.drafts, d)
}
function commitName () {
  if (!editingName.value) return
  if (nameError.value) return
  editingName.value = false
  focusEditor()
}
function cancelName () {
  const d = current.value
  if (d) renameDraft(d, nameBefore)
  editingName.value = false
  nameError.value = null
  focusEditor()
}
/** Leaving the name field for another step: keep a valid name, undo an invalid one. */
function finishNameEdit () {
  if (nameError.value) {
    const d = current.value
    if (d) renameDraft(d, nameBefore)
  }
  editingName.value = false
  nameError.value = null
}

// ---- creating -----------------------------------------------------------------------------------

function create (kind: TemplateKind) {
  const b = bucket.value
  if (!b || fileMode.value) return
  if (editingName.value) finishNameEdit()
  createFromTemplate(b, kind)
  mobileEditor.value = true
  nextTick(startRename)
}
function addBuiltin (bi: Builtin) {
  const b = bucket.value
  if (!b || fileMode.value) return
  if (editingName.value) finishNameEdit()
  createBuiltin(b, bi)
  mobileEditor.value = true
  focusEditor()
}

// ---- saving and running -------------------------------------------------------------------------

/** Check the name before saving; show the problem at the name field. */
function nameOk (d: StepDraft) {
  const b = bucket.value
  if (!b) return false
  const problem = validateName(d.name, b.drafts, d)
  if (!problem) return true
  if (current.value !== d) select(d.uid)
  nextTick(() => {
    startRename()
    nameError.value = problem
  })
  notify.error(`Fix the step's name first`, problem)
  return false
}

async function saveCurrent () {
  const b = bucket.value
  const d = current.value
  if (!b || !d || fileMode.value) return
  if (editingName.value && nameError.value) return
  editingName.value = false
  if (!isDirty(d)) {
    announce.value = `${d.name} has no unsaved changes.`
    return
  }
  if (!nameOk(d)) return
  const ok = await saveDraft(b, d)
  if (ok) {
    refreshStore()
    if (d.engineBehind) actions.markEngineStale()
    announce.value = `Saved ${d.name}.`
    notify.success(`Saved “${d.name}”`)
  } else {
    notify.error(`Couldn't save “${d.name}”`, d.error || undefined)
  }
}

async function saveAndRunCurrent () {
  const b = bucket.value
  const d = current.value
  if (!b || !d || fileMode.value) return
  if (editingName.value && nameError.value) return
  editingName.value = false
  if (isDirty(d)) {
    if (!nameOk(d)) return
    const ok = await saveDraft(b, d)
    if (!ok) {
      notify.error(`Couldn't save “${d.name}”`, d.error || undefined)
      return
    }
    refreshStore()
  }
  // The server runs the code in whatever the engine runs now: when the selected experiment changed
  // while saving (here or in another tab), this step belongs to another one.
  const exp = admin.experiment
  if (!exp || exp.id !== b.experimentId || !!exp.fileMode !== b.fileMode) {
    notify.warning(`“${d.name}” was saved but not run`, 'The selected experiment changed (or went into file mode) while it was being saved, so it wasn’t loaded into the engine.')
    announce.value = `${d.name} was saved but not run: the selected experiment changed.`
    return
  }
  if (!d.source.trim()) {
    notify.info('Nothing to run', 'This step has no code yet.')
    return
  }
  if (runDraft(d)) {
    announce.value = `Running ${d.name} in the engine.`
    return
  }
  // (not sent: runDraft marks a version just saved as not in the engine yet)
  announce.value = `${d.name} did not run: not connected to the server.`
}

const runTitle = computed(() => {
  const r = current.value?.run
  if (!r) return ''
  if (r.status === 'pending') return 'Running in the engine…'
  if (r.status === 'ok') return r.values.length ? 'Loaded into the engine. It returned:' : 'Loaded into the engine'
  if (r.status === 'error') return 'The engine reported an error'
  if (r.status === 'notsent') return 'Not run: not connected to the server. Run it again once the connection is back.'
  return 'No answer from the engine. The connection may have dropped, or the engine is busy or reloading; check the output panel.'
})
watch(() => current.value?.run?.status, (s, before) => {
  const d = current.value
  if (!d || !d.run || before !== 'pending' || s === 'pending') return
  if (s === 'ok') announce.value = `${d.name} loaded into the engine.`
  else if (s === 'error' && d.run.error) announce.value = `${d.name}: ${parseGroovyError(d.run.error).title}`
  else announce.value = `${d.name}: no answer from the engine.`
})

const savingAll = ref(false)
async function saveAll (): Promise<boolean> {
  const b = bucket.value
  if (!b || fileMode.value || savingAll.value) return false
  if (editingName.value && nameError.value) return false
  editingName.value = false
  const list = sortDrafts(b.drafts.filter(isDirty))
  if (!list.length) {
    announce.value = 'Nothing to save.'
    return true
  }
  for (const d of list) if (!nameOk(d)) return false
  savingAll.value = true
  const failed: StepDraft[] = []
  let saved = 0
  let behind = false
  for (const d of list) {
    if (await saveDraft(b, d)) {
      saved++
      behind = behind || d.engineBehind
    } else {
      failed.push(d)
    }
  }
  savingAll.value = false
  if (saved) {
    refreshStore()
    if (behind) actions.markEngineStale()
    notify.success(`Saved ${saved} step${saved === 1 ? '' : 's'}`, behind ? 'Reload the engine, or Run a step, to use the new versions now.' : undefined)
  }
  if (failed.length) {
    notify.error(`Couldn't save ${failed.length} step${failed.length === 1 ? '' : 's'}`, failed.map(d => `${d.name}: ${d.error}`).join('\n'))
    select(failed[0].uid)
  }
  announce.value = failed.length ? `${failed.length} steps were not saved.` : `Saved ${saved} steps.`
  return !failed.length
}

function dismissError () {
  const d = current.value
  if (!d) return
  d.error = null
  d.status = 'idle'
}

// ---- other actions --------------------------------------------------------------------------------

async function deleteCurrent () {
  const b = bucket.value
  const d = current.value
  if (!b || !d || fileMode.value || (builtin.value && !isNew(d))) return
  const neverSaved = isNew(d)
  const ok = await confirm({
    title: `Delete the step “${d.name}”?`,
    message: neverSaved
      ? 'This step was never saved, so its code will be gone.'
      : 'It is removed from the experiment. Sessions that already ran keep their data. The live engine keeps what this step defined until you reload it.',
    confirmLabel: 'Delete step',
    danger: true,
  })
  if (!ok) return
  const order = sortDrafts(b.drafts)
  const index = order.indexOf(d)
  const snapshot = { name: d.name, savedSource: d.savedSource, source: d.source, wasSaved: !neverSaved }
  if (!(await deleteDraft(b, d))) {
    notify.error(`Couldn't delete “${d.name}”`, d.error || undefined)
    return
  }
  if (snapshot.wasSaved) {
    actions.markEngineStale()
    refreshStore()
  }
  const rest = sortDrafts(b.drafts)
  b.selected = (rest[Math.min(index, rest.length - 1)] || rest[0])?.uid ?? null
  toast('success', `Deleted “${snapshot.name}”`, undefined, { timeout: 8000, action: { label: 'Undo', run: () => { undoDelete(b, snapshot) } } })
}

/** Put a deleted step back: saved again as a new step, with any unsaved edits it had. */
async function undoDelete (b: StepsBucket, s: { name: string, savedSource: string | null, source: string, wasSaved: boolean }) {
  const d = addDraft(b, newDraft(s.name, 'empty', s.savedSource ?? s.source))
  d.template = null
  mobileEditor.value = true
  if (s.wasSaved) {
    if (await saveDraft(b, d)) {
      refreshStore()
      notify.success(`Restored “${d.name}”`)
    } else {
      notify.error(`Couldn't restore “${d.name}”`, d.error || undefined)
    }
  }
  d.source = s.source
}

function revert () {
  const d = current.value
  if (!d || isNew(d) || !isDirty(d)) return
  const before = { name: d.name, source: d.source }
  d.name = d.savedName ?? d.name
  d.source = d.savedSource ?? ''
  toast('info', `Reverted “${d.name}”`, 'Back to the saved version.', { action: { label: 'Undo', run: () => { d.name = before.name; d.source = before.source } } })
}

function duplicateCurrent () {
  const b = bucket.value
  const d = current.value
  if (!b || !d) return
  duplicate(b, d)
  nextTick(startRename)
}

async function copyCode () {
  try {
    await navigator.clipboard.writeText(current.value?.source || '')
    notify.success('Copied the code')
  } catch (e) {
    notify.error('Could not copy', 'Your browser blocked the clipboard.')
  }
}

async function reloadEngine () {
  if (!ensureConnected()) return
  // the engine is shared by every account and the autopilot: ask while anyone is using it
  if (!await confirmEngineRebuild({ title: 'Reload the script engine?', action: 'Reloading', confirmLabel: 'Reload anyway' })) return
  // (the connection may have dropped while the question was open)
  if (!actions.reloadEngine()) ensureConnected()
}

function reload () {
  if (bucket.value) loadSteps(bucket.value)
}

const moreItems = computed<MenuItem[]>(() => {
  const d = current.value
  const locked = !!builtin.value
  const keep = locked && !!d && !isNew(d)
  return [
    { label: 'Rename', icon: Pencil, disabled: locked, hint: locked ? 'built in' : undefined, run: startRename },
    { label: 'Duplicate', icon: CopyPlus, run: duplicateCurrent },
    { label: 'Revert to saved', icon: Undo2, disabled: !d || isNew(d) || !isDirty(d), run: revert },
    { label: 'Copy the code', icon: Copy, run: copyCode },
    { label: 'Show engine output', icon: PanelBottom, hint: 'Ctrl+J', run: () => { openOutput.value = true } },
    { divider: true },
    { label: 'Delete step', icon: Trash2, danger: true, disabled: keep, hint: keep ? 'built in' : undefined, run: deleteCurrent },
  ]
})

// ---- keyboard: Cmd/Ctrl+S save, +Enter save & run, +Shift+S save all ---------------------------

function onKey (e: KeyboardEvent) {
  if (e.defaultPrevented || !(e.metaKey || e.ctrlKey) || e.altKey) return
  // a dialog (confirm, unsaved changes) is open
  if (document.body.classList.contains('b-no-scroll')) return
  const k = e.key.toLowerCase()
  if (k === 's') {
    e.preventDefault()
    if (e.shiftKey) saveAll()
    else saveCurrent()
  } else if (k === 'enter' && !e.shiftKey) {
    e.preventDefault()
    saveAndRunCurrent()
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// ---- leaving with unsaved changes ---------------------------------------------------------------

const leave = reactive({ open: false, saving: false, names: [] as string[], resolve: null as null | ((c: LeaveChoice) => void) })
function onLeaveChoice (c: LeaveChoice) {
  leave.resolve?.(c)
}
onBeforeRouteLeave(async () => {
  const b = bucket.value
  if (!b || b.fileMode) return true
  const unsaved = sortDrafts(b.drafts.filter(isDirty))
  if (!unsaved.length) return true
  if (editingName.value) finishNameEdit()
  leave.names = unsaved.map(d => d.name)
  leave.open = true
  const choice = await new Promise<LeaveChoice>(resolve => { leave.resolve = resolve })
  leave.resolve = null
  if (choice === 'save') {
    leave.saving = true
    const ok = await saveAll()
    leave.saving = false
    leave.open = false
    return ok
  }
  leave.open = false
  return choice === 'leave'
})

function clock (t: number) {
  return new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
</script>

<style>
.steps {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  background: var(--bg);
}
.steps__list { border-right: 1px solid var(--border); min-height: 0; min-width: 0; }
.steps__main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.steps__banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--border);
  font-size: var(--text-sm);
  line-height: 1.45;
}
.steps__banner-icon { flex: none; }
.steps__banner-text { flex: 1; min-width: 0; }
.steps__banner-text strong { font-weight: 650; }
.steps__banner.is-file { background: var(--warning-soft); color: var(--warning-soft-text); }
.steps__banner.is-stale { background: var(--info-soft); color: var(--info-soft-text); }
.steps__state { flex: 1; display: flex; align-items: center; justify-content: center; gap: 12px; color: var(--muted); padding: 24px; }
.steps__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 20px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  min-height: 58px;
  flex-wrap: wrap;
}
.steps__back { display: none; }
.steps__name { display: flex; align-items: center; gap: 6px; min-width: 0; }
.steps__name-field { width: min(340px, 60vw); }
.steps__title { font-size: var(--text-lg); font-weight: 650; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 42vw; }
.steps__lock { display: inline-flex; align-items: center; gap: 4px; padding: 0 8px; height: 22px; border-radius: 999px; background: var(--bg-subtle); color: var(--muted); font-size: var(--text-xs); font-weight: 600; white-space: nowrap; }
.steps__status { display: inline-flex; }
.steps__spacer { flex: 1; }
.steps__actions { display: flex; align-items: center; gap: 6px; }
.steps__about {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 12px 20px 0;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.steps__about svg { flex: none; margin-top: 3px; color: var(--accent); }
.steps__alert {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin: 12px 20px 0;
  padding: 8px 8px 8px 12px;
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
  color: var(--danger-soft-text);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.steps__alert > svg { flex: none; margin-top: 3px; }
.steps__alert p { flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-word; padding-top: 1px; }
.steps__alert.is-warn { background: var(--warning-soft); color: var(--warning-soft-text); padding-right: 12px; }
.steps__editor { flex: 1; min-height: 160px; padding: 12px 20px; display: flex; flex-direction: column; }
.steps__editor > .b-code { flex: 1; }
.steps__foot { padding: 0 20px 14px; }
.steps__hint { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; color: var(--faint); font-size: var(--text-xs); }
.steps__hint.is-behind { color: var(--info-soft-text); font-size: var(--text-sm); }
.steps__hint.is-behind strong { font-weight: 650; }
.srun {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 8px 10px 12px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius-sm);
  background: var(--surface);
  max-height: 260px;
  overflow: auto;
}
.srun.is-ok { border-left-color: var(--success); }
.srun.is-error { border-left-color: var(--danger); }
.srun.is-noreply, .srun.is-notsent { border-left-color: var(--warning); }
.srun__head { display: flex; align-items: center; gap: 8px; min-height: 28px; }
.srun.is-ok .srun__head > svg { color: var(--success); }
.srun.is-noreply .srun__head > svg, .srun.is-notsent .srun__head > svg { color: var(--warning); }
.srun__title { font-size: var(--text-sm); font-weight: 600; color: var(--text); }
.srun.is-noreply .srun__title, .srun.is-notsent .srun__title { color: var(--warning-soft-text); font-weight: 500; }
.srun__time { font-size: var(--text-xs); color: var(--faint); font-variant-numeric: tabular-nums; }
.srun__value { margin: 0; padding-right: 4px; font-size: 12.5px; color: var(--text); white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow: auto; }
@media (max-width: 900px) {
  .steps { grid-template-columns: minmax(0, 1fr); }
  .steps:not(.show-editor) .steps__main { display: none; }
  .steps.show-editor .steps__list { display: none; }
  .steps__back { display: inline-flex; }
  .steps__head { padding: 10px 12px; }
  .steps__title { max-width: 60vw; }
  .steps__banner { padding: 10px 12px; flex-wrap: wrap; }
  .steps__about, .steps__alert { margin: 10px 12px 0; }
  .steps__editor { padding: 10px 12px; }
  .steps__foot { padding: 0 12px 12px; }
}
@media (max-width: 560px) {
  /* no keyboard shortcuts to show on a phone */
  .steps__hint:not(.is-behind) { display: none; }
  .steps__actions { width: 100%; }
  .steps__actions > .b-btn:first-child { margin-right: auto; }
}
</style>
