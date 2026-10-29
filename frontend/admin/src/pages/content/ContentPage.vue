<template>
  <div class="ct" :class="{ 'is-detail': detail }">
    <div v-if="s.readOnly.value" class="ct-banner ct-banner--warn" role="note">
      <FileLock2 :size="17" class="ct-banner__icon" aria-hidden="true" />
      <p class="ct-banner__text">
        <strong>File mode is on, so these texts are read-only here.</strong>
        They are read from the experiment’s Content folder; edit the .html files there and this page follows.
        To edit here instead, turn file mode off on the Experiments page.
      </p>
      <BButton size="sm" :icon-right="ArrowRight" @click="router.push('/experiments')">Experiments</BButton>
    </div>
    <div v-for="o in s.otherDirty.value" :key="o.id" class="ct-banner" role="note">
      <Info :size="17" class="ct-banner__icon" aria-hidden="true" />
      <p class="ct-banner__text">
        {{ o.count === 1 ? 'A text' : `${o.count} texts` }} in “{{ o.name }}” {{ o.count === 1 ? 'has' : 'have' }} unsaved changes.
        They are kept while this page stays open.
      </p>
      <BButton size="sm" :loading="switching === o.id" @click="switchTo(o.id, o.name)">Switch to “{{ o.name }}”</BButton>
    </div>
    <div v-if="s.loadError.value" class="ct-banner ct-banner--danger" role="alert">
      <CircleAlert :size="17" class="ct-banner__icon" aria-hidden="true" />
      <p class="ct-banner__text">
        <strong>Couldn’t load the latest texts.</strong> {{ s.loadError.value }} The page shows the last copy the console received.
      </p>
      <BButton size="sm" :icon="RotateCw" :loading="s.loading.value" @click="s.fetchContent()">Try again</BButton>
    </div>

    <div class="ct-bar">
      <LanguageBar @manage="languagesOpen = true" />
      <div class="ct-bar__save" :class="{ 'is-idle': !dirtyCount && !savingAll }">
        <span class="sr-only" aria-live="polite">{{ liveStatus }}</span>
        <BButton
          v-if="!s.readOnly.value"
          :variant="dirtyCount ? 'primary' : 'ghost'"
          :icon="dirtyCount ? Save : Check"
          :loading="savingAll"
          :disabled="!dirtyCount"
          :title="dirtyCount ? `Save (${saveKeys})` : 'Everything is saved'"
          @click="saveAll"
        >
          {{ saveLabel }}
        </BButton>
      </div>
    </div>

    <div class="ct-body">
      <ContentList @create="createOpen = true" @rename="renameFromList" @open="detail = true" />
      <ContentEditor
        ref="editor"
        @back="detail = false"
        @save="saveAll"
        @create="createOpen = true"
        @languages="languagesOpen = true"
      />
    </div>

    <NewContentDialog v-model:open="createOpen" @created="onCreated" />
    <LanguagesDialog v-model:open="languagesOpen" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ArrowRight, Check, CircleAlert, FileLock2, Info, RotateCw, Save } from 'lucide-vue-next'
import { BButton, confirm, notify } from '@/ui'
import { actions, admin, ensureConnected } from '@/store/admin'
import { confirmSwitch } from '@/store/engineGuard'
import { CONTENT, dropKeptDrafts, useContent } from './useContent'
import LanguageBar from './LanguageBar.vue'
import ContentList from './ContentList.vue'
import ContentEditor from './ContentEditor.vue'
import NewContentDialog from './NewContentDialog.vue'
import LanguagesDialog from './LanguagesDialog.vue'

/**
 * Content: the texts participants see (c.get("Name") in scripts), one version per language.
 * List on the left, editor on the right; on a phone the two take turns (`detail`).
 */
const router = useRouter()
const s = useContent()
provide(CONTENT, s)

const editor = ref<InstanceType<typeof ContentEditor> | null>(null)
const createOpen = ref(false)
const languagesOpen = ref(false)
const detail = ref(false)
const switching = ref<number | null>(null)

const dirtyCount = computed(() => s.dirty.value.length)
const savingAll = computed(() => s.saving.size > 0)
const saveLabel = computed(() => {
  if (!dirtyCount.value) return 'All saved'
  return dirtyCount.value === 1 ? 'Save' : `Save all (${dirtyCount.value})`
})
const saveKeys = /Mac|iPhone|iPad/i.test(typeof navigator !== 'undefined' ? navigator.platform || navigator.userAgent : '') ? '⌘S' : 'Ctrl+S'
const liveStatus = computed(() => {
  if (savingAll.value) return 'Saving…'
  if (!dirtyCount.value) return 'All texts saved.'
  return `${dirtyCount.value} ${dirtyCount.value === 1 ? 'text has' : 'texts have'} unsaved changes.`
})

function saveAll () {
  editor.value?.flush()
  return s.save()
}

function renameFromList (key: string) {
  s.select(key)
  detail.value = true
  nextTick(() => editor.value?.startRename())
}

function onCreated () {
  detail.value = true
  // let the editor appear (TinyMCE needs a moment) before putting the cursor in it
  setTimeout(() => editor.value?.focus(), 350)
}

/**
 * Switching rebuilds the shared engine when no session is live there, so ask first while anyone is
 * using it.
 */
async function switchTo (id: number, name: string) {
  if (switching.value !== null || !ensureConnected()) return
  if (!await confirmSwitch({ title: `Switch to “${name}”?`, action: 'Switching experiments', confirmLabel: 'Switch anyway' })) return
  switching.value = id
  const asked = Date.now()
  try {
    const res = await actions.selectExperiment(id)
    if (!res) {
      // the server's reason when it refused (a refusal resolves null at once)
      const refusal = admin.requestError
      const why = refusal && refusal.request === 'SelectExperiment' && refusal.at >= asked ? refusal.text : 'Check the experiment switcher at the top left.'
      notify.warning('The server didn’t confirm the switch', why)
    }
  } finally {
    switching.value = null
  }
}

// ---- keyboard and leaving with unsaved edits ----
function onKey (e: KeyboardEvent) {
  if ((e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === 's') {
    e.preventDefault()
    if (!s.readOnly.value) saveAll()
  }
}
function onBeforeUnload (e: BeforeUnloadEvent) {
  if (!s.totalDirty.value) return
  e.preventDefault()
  e.returnValue = ''
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  window.addEventListener('beforeunload', onBeforeUnload)
})
// Unsaved edits outlive the page (see useContent) only when it is taken down without leaving it,
// as when signing out swaps the console for the sign-in form; leaving the page drops them.
let leftPage = false
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('beforeunload', onBeforeUnload)
  if (leftPage) dropKeptDrafts()
})

onBeforeRouteLeave(async () => {
  editor.value?.flush()
  const n = s.totalDirty.value
  leftPage = !n || await confirm({
    title: 'Leave without saving?',
    message: n === 1
      ? 'One text has unsaved changes. If you leave this page, they are lost.'
      : `${n} texts have unsaved changes. If you leave this page, they are lost.`,
    confirmLabel: 'Leave without saving',
    cancelLabel: 'Stay',
    danger: true,
  })
  return leftPage
})
</script>

<style>
.ct { display: flex; flex-direction: column; height: 100%; min-height: 0; min-width: 0; background: var(--surface); }
.ct-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 9px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--info-soft);
  color: var(--info-soft-text);
  font-size: var(--text-sm);
}
.ct-banner__icon { flex: none; }
.ct-banner__text { flex: 1 1 320px; min-width: 0; }
.ct-banner--warn { background: var(--warning-soft); color: var(--warning-soft-text); }
.ct-banner--danger { background: var(--danger-soft); color: var(--danger-soft-text); }
.ct-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}
.ct-bar__save { flex: none; display: flex; align-items: center; gap: 8px; }
.ct-body { flex: 1; min-height: 0; display: grid; grid-template-columns: 300px minmax(0, 1fr); }
@media (max-width: 1100px) {
  .ct-body { grid-template-columns: 250px minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .ct-bar { padding: 8px 12px; gap: 8px; }
  /* "All saved" needs no room on a phone; the list shows unsaved texts */
  .ct-bar__save.is-idle { display: none; }
  .ct-body { grid-template-columns: minmax(0, 1fr); }
  .ct-list { border-right: 0; }
  .ct:not(.is-detail) .ct-ed { display: none; }
  .ct.is-detail .ct-list { display: none; }
  .ct-ed { padding: 12px 12px 14px; }
  .ct-ed__back { display: inline-flex; margin-left: -6px; }
  .ct-ed__langs { margin-left: 0; }
}
</style>
