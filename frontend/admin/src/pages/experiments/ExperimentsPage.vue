<template>
  <BPage intro="Everything you've built. Choose the experiment to work on, start a new one, or move one between servers as a .zip file.">
    <template #actions>
      <BButton :icon="Upload" @click="openImport()">Import</BButton>
      <BButton variant="primary" :icon="Plus" @click="openNew()">New experiment</BButton>
    </template>

    <!-- The selected experiment, and where it is edited (file mode lives here; other pages point to it). -->
    <section v-if="current" class="xcur" aria-labelledby="xcur-title">
      <div class="xcur__top">
        <div class="xcur__titles">
          <p class="xcur__eyebrow">You're working on</p>
          <h2 id="xcur-title" class="xcur__name">{{ current.name }}</h2>
          <p class="xcur__meta">
            <span>{{ plural(current.instances?.length || 0, 'session') }}</span>
            <span>{{ plural(current.steps?.length || 0, 'step') }}</span>
            <span>{{ plural(current.content?.length || 0, 'content page') }}</span>
            <span>{{ plural(current.parameters?.length || 0, 'parameter') }}</span>
          </p>
        </div>
        <div class="xcur__actions">
          <BButton size="sm" :icon="Download" :loading="exportingId === current.id" @click="exportExp(current)">Export .zip</BButton>
          <BButton size="sm" variant="ghost" :icon="Copy" @click="duplicate(current)">Duplicate</BButton>
        </div>
      </div>

      <div class="xcur__storage" :class="{ 'is-files': current.fileMode }">
        <span class="xcur__storage-icon" aria-hidden="true">
          <HardDrive v-if="current.fileMode" :size="18" />
          <Database v-else :size="18" />
        </span>
        <div class="xcur__storage-text">
          <h3 class="xcur__storage-title">
            {{ current.fileMode ? 'File mode: edited as files on the server' : 'Edited here, in the console' }}
          </h3>
          <p v-if="current.fileMode" class="xcur__storage-body">
            Breadboard reads this experiment from <code>dev/{{ devDirName(current) }}/</code> in its server folder.
            Steps, Content, Parameters, Images and the participant page are read-only in the console, and a step file saved
            on the server runs at once.
          </p>
          <p v-else class="xcur__storage-body">
            Its steps, content and settings are stored in Breadboard's database. Prefer your own code editor?
            File mode writes the experiment to <code>dev/{{ devDirName(current) }}/</code> on the server and reads it from there.
          </p>
        </div>
        <BButton
          class="xcur__storage-btn"
          size="sm"
          :variant="current.fileMode ? 'secondary' : 'ghost'"
          :icon="current.fileMode ? Database : HardDrive"
          @click="fileModeFor(current)"
        >
          {{ current.fileMode ? 'Edit in the console again…' : 'Use file mode…' }}
        </BButton>
      </div>
    </section>

    <section v-if="experiments.length" class="xall" aria-labelledby="xall-title">
      <div class="xall__bar">
        <h2 id="xall-title" class="xall__title">All experiments <span class="xall__count">{{ experiments.length }}</span></h2>
        <div class="xall__tools">
          <BInput
            v-if="experiments.length > 6"
            v-model="query"
            size="sm"
            :icon="Search"
            type="search"
            placeholder="Find an experiment"
            aria-label="Find an experiment"
            class="xall__find"
          />
          <BSelect v-model="sort" size="sm" :options="sortOptions" aria-label="Sort experiments" class="xall__sort" />
        </div>
      </div>

      <div v-if="shown.length" class="xall__grid">
        <ExperimentCard
          v-for="e in shown"
          :id="`xcard-${e.id}`"
          :key="e.id"
          :exp="e"
          :current="!!current && current.id === e.id"
          :selecting="selectingId === e.id"
          :exporting="exportingId === e.id"
          :flash="flashId === e.id"
          @select="select(e)"
          @export="exportExp(e)"
          @duplicate="duplicate(e)"
          @file-mode="fileModeFor(e)"
          @delete="remove(e)"
        />
      </div>
      <BEmpty v-else compact :icon="Search" title="No experiment matches" :text="`Nothing is called “${query.trim()}”.`">
        <template #actions><BButton size="sm" @click="query = ''">Clear the search</BButton></template>
      </BEmpty>
    </section>

    <div v-else class="xempty">
      <BEmpty
        :icon="FlaskConical"
        title="Create your first experiment"
        text="An experiment holds the steps, content and settings that run a study. Start from Breadboard's template, or import a .zip exported from another server."
      >
        <template #actions>
          <BButton variant="primary" :icon="Plus" @click="openNew()">New experiment</BButton>
          <BButton :icon="Upload" @click="openImport()">Import a .zip</BButton>
        </template>
      </BEmpty>
    </div>

    <div v-if="dropping" class="xdrop" aria-hidden="true">
      <div class="xdrop__card">
        <FileArchive :size="26" />
        <span>Drop the .zip to import it</span>
      </div>
    </div>

    <NewExperimentDialog v-model:open="newOpen" :copy-from="copyFrom" @created="e => flash(e.id)" />
    <ImportExperimentDialog v-model:open="importOpen" :initial-file="importFile" @imported="e => flash(e.id)" />
    <FileModeDialog v-model:open="fileModeOpen" />
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Copy, Database, Download, FileArchive, FlaskConical, HardDrive, Plus, Search, Upload,
} from 'lucide-vue-next'
import { BButton, BEmpty, BInput, BPage, BSelect, confirm, notify, toast } from '@/ui'
import { admin, actions, experiments, running } from '@/store/admin'
import type { Experiment } from '@/store/types'
import ExperimentCard from './ExperimentCard.vue'
import NewExperimentDialog from './NewExperimentDialog.vue'
import ImportExperimentDialog from './ImportExperimentDialog.vue'
import FileModeDialog from './FileModeDialog.vue'
import {
  describeError, devDirName, downloadExperiment, lastSession, plural, runningSession, zipFileName,
} from './experiments'

const route = useRoute()
const router = useRouter()

const current = computed(() => admin.experiment)

// ---- list -----------------------------------------------------------------------------------

type Sort = 'name' | 'recent'
const SORT_KEY = 'bb-admin-experiments-sort'
const sortOptions = [
  { value: 'name', label: 'By name' },
  { value: 'recent', label: 'Most recent session' },
]
const sort = ref<Sort>(readSort())
const query = ref('')
function readSort (): Sort {
  try { return localStorage.getItem(SORT_KEY) === 'recent' ? 'recent' : 'name' } catch (e) { return 'name' }
}
watch(sort, (v) => { try { localStorage.setItem(SORT_KEY, v) } catch (e) { /* private mode */ } })

const shown = computed(() => {
  const q = query.value.trim().toLowerCase()
  let list = q ? experiments.value.filter(e => e.name.toLowerCase().includes(q)) : experiments.value
  if (sort.value === 'recent') {
    list = [...list].sort((a, b) => (lastSession(b)?.id || 0) - (lastSession(a)?.id || 0))
  }
  return list
})

const flashId = ref<number | null>(null)
let flashTimer: ReturnType<typeof setTimeout> | undefined
async function flash (id: number) {
  query.value = ''
  flashId.value = id
  await nextTick()
  document.getElementById(`xcard-${id}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  clearTimeout(flashTimer)
  flashTimer = setTimeout(() => { flashId.value = null }, 1800)
}

// ---- dialogs (the experiment switcher links to ?action=new / ?action=import) -----------------

const newOpen = ref(false)
const copyFrom = ref<number | null>(null)
const importOpen = ref(false)
const importFile = ref<File | null>(null)
const fileModeOpen = ref(false)

// one dialog at a time (a link can ask for another while one is open)
function openNew (from: number | null = null) {
  importOpen.value = false
  fileModeOpen.value = false
  copyFrom.value = from
  newOpen.value = true
}
function openImport (file: File | null = null) {
  newOpen.value = false
  fileModeOpen.value = false
  importFile.value = file
  importOpen.value = true
}
watch(() => route.query.action, (a) => {
  if (a !== 'new' && a !== 'import') return
  if (a === 'new') openNew()
  else openImport()
  // drop the query so the same link works again next time
  router.replace({ query: { ...route.query, action: undefined } })
}, { immediate: true })

// ---- actions --------------------------------------------------------------------------------

const selectingId = ref<number | null>(null)
const exportingId = ref<number | null>(null)

async function select (e: Experiment) {
  if (running.value) {
    notify.warning('Stop the running session first',
      'Switching experiments restarts the script engine, which disconnects everyone in the session.')
    return
  }
  selectingId.value = e.id
  try {
    await actions.selectExperiment(e.id)
    if (admin.experiment?.id === e.id) notify.success(`Now working on “${e.name}”`)
    else notify.error(`Couldn't switch to “${e.name}”`, 'The server didn\'t confirm the change. Try again, or reload the page.')
  } finally {
    selectingId.value = null
  }
}

async function exportExp (e: Experiment) {
  if (exportingId.value != null) return
  exportingId.value = e.id
  try {
    await downloadExperiment(e)
    notify.success(`Exported “${e.name}”`, `Saved as ${zipFileName(e)} in your downloads.`)
  } catch (err) {
    notify.error(`Couldn't export “${e.name}”`, describeError(err))
  } finally {
    exportingId.value = null
  }
}

function duplicate (e: Experiment) {
  openNew(e.id)
}

function fileModeFor (e: Experiment) {
  if (!current.value || current.value.id !== e.id) {
    // ToggleFileMode works on the experiment you're working on, so offer to switch to it first
    toast('info', `Work on “${e.name}” first`,
      'File mode is changed for the experiment you are working on.',
      running.value ? {} : { action: { label: 'Work on it', run: () => select(e) } })
    return
  }
  fileModeOpen.value = true
}

async function remove (e: Experiment) {
  const live = runningSession(e)
  if (live) {
    notify.warning(`“${e.name}” has a session running`,
      `Stop “${live.name}” on the Live session page before deleting the experiment.`)
    return
  }
  if (running.value) {
    // DeleteExperiment empties the engine's network, whichever experiment it deletes
    notify.warning('Stop the running session first',
      'Deleting an experiment resets the script engine, which would empty the running session\'s network.')
    return
  }
  const n = e.instances?.length || 0
  const twin = experiments.value.some(x => x.id !== e.id && x.name.trim().toLowerCase() === e.name.trim().toLowerCase())
  const ok = await confirm({
    title: `Delete “${e.name}”?`,
    message: [
      `This permanently deletes the experiment: its steps, content, parameters and images${n ? `, and ${plural(n, 'session')} with all their data` : ''}.`,
      'It cannot be undone. Export it first if you might need it again.',
      twin ? '\nAnother experiment has the same name. Check that you are deleting the right one.' : '',
    ].filter(Boolean).join(' '),
    confirmLabel: 'Delete experiment',
    danger: true,
    typeToConfirm: e.name.trim() || 'delete',
  })
  if (!ok) return
  // by id, so a twin with the same name is never deleted by mistake (§3.2.1)
  await actions.deleteExperiment(e)
  if (experiments.value.some(x => x.id === e.id)) {
    notify.error(`“${e.name}” was not deleted`,
      'The server didn\'t remove it. If a session is open, stop it and try again; the server log has details.')
  } else {
    notify.success(`Deleted “${e.name}”`)
  }
}

// ---- drop a zip anywhere on the page to import it ----------------------------------------------

const dropping = ref(false)
let depth = 0
const hasFiles = (ev: DragEvent) => Array.from(ev.dataTransfer?.types || []).includes('Files')
function onDragEnter (ev: DragEvent) {
  if (importOpen.value || newOpen.value || fileModeOpen.value || !hasFiles(ev)) return
  depth++
  dropping.value = true
}
function onDragOver (ev: DragEvent) {
  if (!hasFiles(ev)) return
  // without this the browser opens the zip instead of letting the page have it
  ev.preventDefault()
}
function onDragLeave () {
  if (!dropping.value) return
  depth = Math.max(0, depth - 1)
  if (!depth) dropping.value = false
}
function onDrop (ev: DragEvent) {
  if (!hasFiles(ev)) return
  ev.preventDefault()
  const wasDropping = dropping.value
  depth = 0
  dropping.value = false
  if (!wasDropping || importOpen.value) return
  const f = ev.dataTransfer?.files?.[0]
  if (f) openImport(f)
}
onMounted(() => {
  window.addEventListener('dragenter', onDragEnter)
  window.addEventListener('dragover', onDragOver)
  window.addEventListener('dragleave', onDragLeave)
  window.addEventListener('drop', onDrop)
})
onBeforeUnmount(() => {
  window.removeEventListener('dragenter', onDragEnter)
  window.removeEventListener('dragover', onDragOver)
  window.removeEventListener('dragleave', onDragLeave)
  window.removeEventListener('drop', onDrop)
  clearTimeout(flashTimer)
})
</script>

<style>
.xcur {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.xcur__top { display: flex; align-items: flex-start; gap: 16px; padding: 18px 20px; flex-wrap: wrap; }
.xcur__titles { flex: 1; min-width: 220px; }
.xcur__eyebrow { font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.xcur__name { margin-top: 3px; font-size: var(--text-xl); overflow-wrap: anywhere; }
.xcur__meta { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 6px; font-size: var(--text-sm); color: var(--muted); }
.xcur__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.xcur__storage {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background: var(--surface-sunken);
}
.xcur__storage.is-files { background: var(--info-soft); }
.xcur__storage-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--surface); color: var(--muted); border: 1px solid var(--border); flex: none; }
.xcur__storage.is-files .xcur__storage-icon { color: var(--info); }
.xcur__storage-text { flex: 1; min-width: 0; }
.xcur__storage-title { font-size: var(--text-md); font-weight: 650; }
.xcur__storage.is-files .xcur__storage-title { color: var(--info-soft-text); }
.xcur__storage-body { margin-top: 3px; font-size: var(--text-sm); color: var(--muted); max-width: 70ch; }
.xcur__storage-body code { color: var(--text-soft); overflow-wrap: anywhere; }
.xcur__storage-btn { flex: none; align-self: center; }
@media (max-width: 640px) {
  .xcur__storage { flex-wrap: wrap; }
  .xcur__storage-btn { margin-left: 48px; }
}

.xall { display: flex; flex-direction: column; gap: 14px; }
.xall__bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.xall__title { flex: 1; min-width: 160px; font-size: var(--text-md); font-weight: 650; display: flex; align-items: center; gap: 8px; }
.xall__count { padding: 0 7px; border-radius: 999px; background: var(--bg-subtle); color: var(--muted); font-size: var(--text-xs); font-weight: 600; line-height: 20px; }
.xall__tools { display: flex; gap: 8px; flex-wrap: wrap; }
.xall__find { width: 220px; max-width: 100%; }
.xall__sort { width: 190px; }
.xall__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 290px), 1fr)); gap: 14px; }
@media (max-width: 520px) {
  .xall__tools, .xall__find, .xall__sort { width: 100%; }
  .xall__tools > * { flex: 1; }
}

.xempty {
  background: var(--surface);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-lg);
}

.xdrop {
  position: fixed;
  inset: 0;
  z-index: 95;
  display: grid;
  place-items: center;
  background: var(--overlay);
  pointer-events: none;
}
.xdrop__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 28px 36px;
  border: 2px dashed var(--accent);
  border-radius: var(--radius-lg);
  background: var(--surface-raised);
  color: var(--accent);
  font-weight: 650;
  box-shadow: var(--shadow-lg);
}
</style>
