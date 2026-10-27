<template>
  <div class="pg">
    <header class="pg-head">
      <p class="pg-head__intro">
        What participants see in their browser. Changes reach a participant the next time their page loads.
      </p>
      <div class="pg-head__actions">
        <BButton :icon="MonitorSmartphone" href="#/preview">Preview</BButton>
        <template v-if="!readOnly">
          <BButton
            v-if="dirtyParts.length > 1 || (dirtyParts.length === 1 && !currentDirty)"
            :icon="SaveAll"
            :loading="savingAll"
            :disabled="anySaving && !savingAll"
            @click="saveAll"
          >
            Save all ({{ dirtyParts.length }})
          </BButton>
          <BButton
            variant="primary"
            :icon="Save"
            :loading="!!current?.saving && !savingAll"
            :disabled="!currentDirty || anySaving"
            :title="`Save ${info.label} (${mod}S saves every changed tab)`"
            @click="saveCurrent"
          >
            Save {{ info.label }}
          </BButton>
        </template>
      </div>
    </header>

    <div v-if="readOnly" class="pg-notice"><FileModeNotice what="The participant page’s HTML, JavaScript and CSS" /></div>

    <!-- drafts left in another experiment after switching (kept so nothing is lost) -->
    <div v-for="d in otherDirty" :key="d.experimentId" class="pg-notice">
      <div class="pg-other" role="status">
        <AlertTriangle :size="16" aria-hidden="true" />
        <p>Unsaved changes to the participant page of <strong>{{ d.experimentName }}</strong> ({{ dirtyLabels(d) }}).</p>
        <BButton size="sm" :loading="d.busy" @click="saveDoc(d)">Save them</BButton>
        <BButton size="sm" variant="ghost" :disabled="d.busy" @click="discardDoc(d)">Discard</BButton>
      </div>
    </div>

    <div class="pg-tabs">
      <BTabs v-model="active" :tabs="tabs" aria-label="Parts of the participant page" />
    </div>

    <div v-if="doc && current" class="pg-body">
      <div class="pg-info">
        <div class="pg-info__text">
          <p class="pg-info__what">{{ info.what }}</p>
          <p class="pg-info__meta">
            <span class="mono">{{ info.file }}</span>
            <span aria-hidden="true">·</span>
            <span :class="`pg-state pg-state--${stateTone}`">{{ stateText }}</span>
          </p>
        </div>
        <div v-if="current.status === 'ready'" class="pg-info__actions">
          <BButton v-if="active === 'clientHtml' && !readOnly" size="sm" variant="ghost" :icon="Sparkles" :disabled="isDefaultHtml || current.saving" @click="insertNewPage">
            {{ isDefaultHtml ? 'Using the new participant page' : 'Insert the new participant page' }}
          </BButton>
          <BButton v-if="active === 'clientGraph' && !readOnly" size="sm" variant="ghost" :icon="Sparkles" :disabled="isDefaultGraph || current.saving" @click="insertStandardGraph">
            {{ isDefaultGraph ? 'Using the standard script' : 'Insert the standard script' }}
          </BButton>
          <BButton v-if="currentDirty" size="sm" variant="ghost" :icon="Undo2" :disabled="current.saving" @click="revert(active)">Discard changes</BButton>
        </div>
      </div>

      <div v-if="current.status === 'ready' && current.remoteChanged" class="pg-callout pg-callout--warning" role="alert">
        <AlertTriangle :size="16" aria-hidden="true" />
        <p>The {{ info.label }} was changed somewhere else (another tab, or another researcher) since you opened it. Saving will replace that version with yours.</p>
        <BButton size="sm" @click="reloadPart(active)">Load theirs (drop my edits)</BButton>
      </div>
      <div v-else-if="current.status === 'ready' && current.saveError" class="pg-callout pg-callout--danger" role="alert">
        <AlertCircle :size="16" aria-hidden="true" />
        <p>{{ current.saveError }}</p>
        <BButton v-if="!readOnly" size="sm" :disabled="current.saving" @click="saveCurrent">Try again</BButton>
      </div>
      <div v-else-if="active === 'clientHtml' && current.status === 'ready' && legacy && !readOnly" class="pg-callout pg-callout--info">
        <Sparkles :size="16" aria-hidden="true" />
        <p>This experiment uses the previous page layout. The new participant page adds a waiting screen, works on phones, and lets participants add and cut ties when the experiment allows it.</p>
        <BButton size="sm" @click="insertNewPage">Try the new page</BButton>
      </div>

      <div class="pg-editor">
        <template v-for="p in PARTS" :key="`${doc.experimentId}-${p.key}`">
          <div v-show="active === p.key" class="pg-pane" role="tabpanel" :aria-label="p.label">
            <div v-if="doc.parts[p.key].status === 'loading' || doc.parts[p.key].status === 'idle'" class="pg-skeleton" role="status" aria-label="Loading">
              <span v-for="w in [62, 44, 78, 30, 55, 70, 38]" :key="w" :style="{ width: `${w}%` }" />
            </div>
            <div v-else-if="doc.parts[p.key].status === 'error'" class="pg-error">
              <BEmpty :icon="CloudOff" :title="`Couldn’t load the ${p.label}`">
                {{ doc.parts[p.key].error }}<template v-if="!readOnly"> Saving this tab is off until it loads, so the stored version can’t be overwritten by mistake.</template>
                <template #actions><BButton variant="primary" :icon="RotateCw" @click="load(doc, p.key)">Try again</BButton></template>
              </BEmpty>
            </div>
            <BCodeEditor
              v-else
              v-model="doc.parts[p.key].value"
              :language="p.language"
              :readonly="readOnly"
              fill
              :aria-label="`${p.label} (${p.tech})`"
              :placeholder="p.key === 'style' ? '/* CSS for the participant page */' : ''"
              :on-save="saveAll"
              :on-run="saveCurrent"
            />
          </div>
        </template>
      </div>
    </div>

    <footer class="pg-foot">
      <span class="pg-foot__keys">
        <template v-if="!readOnly"><kbd>{{ mod }}</kbd><kbd>S</kbd> saves every changed tab · <kbd>{{ mod }}</kbd><kbd>↵</kbd> saves this one · </template>
        <kbd>{{ mod }}</kbd><kbd>F</kbd> finds
      </span>
      <span class="sr-only" role="status" aria-live="polite">{{ announce }}</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import {
  AlertCircle, AlertTriangle, Braces, CloudOff, FileCode2, MonitorSmartphone, Palette, RotateCw, Save, SaveAll,
  Sparkles, Undo2,
} from 'lucide-vue-next'
import { BButton, BCodeEditor, BEmpty, BTabs, confirm, notify, toast, type TabItem } from '@/ui'
import { http, HttpError } from '@/lib/http'
import { admin, actions } from '@/store/admin'
import FileModeNotice from '../parameters/FileModeNotice.vue'
import {
  PARTS, canSave, isCustomClientGraph, isDirty, isLegacyPage, isPartKey, newPart, normalize, partInfo, readPart,
  serverDiffers, type PartKey, type PartState,
} from './participantPage'
import { DEFAULT_CLIENT_GRAPH, DEFAULT_CLIENT_HTML } from './templates'

const route = useRoute()
const router = useRouter()
const mod = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? '⌘' : 'Ctrl'
const readOnly = computed(() => !!admin.experiment?.fileMode)

// ---- tabs ----

const initialTab = isPartKey(route.query.tab) ? route.query.tab : 'clientHtml'
const active = ref<PartKey>(initialTab)
watch(active, (k) => {
  ensureLoaded(k)
  if (route.query.tab !== k) router.replace({ query: { ...route.query, tab: k } })
})
watch(() => route.query.tab, (t) => { if (isPartKey(t) && t !== active.value) active.value = t })

// ---- one draft per experiment, so switching experiments never throws edits away ----

interface PageDoc {
  experimentId: number
  experimentName: string
  parts: Record<PartKey, PartState>
  busy: boolean
}
const docs = reactive(new Map<number, PageDoc>())
const doc = computed(() => admin.experiment ? docs.get(admin.experiment.id) || null : null)
const docDirty = (d: PageDoc) => PARTS.some(p => isDirty(d.parts[p.key]))
const dirtyLabels = (d: PageDoc) => PARTS.filter(p => isDirty(d.parts[p.key])).map(p => p.label).join(', ')
const otherDirty = computed(() => [...docs.values()].filter(d => d.experimentId !== admin.experiment?.id && docDirty(d)))

watch(() => admin.experiment?.id, (id, old) => {
  if (old != null) {
    const prev = docs.get(old)
    if (prev && !docDirty(prev)) docs.delete(old)
    else if (prev) notify.warning('Your unsaved changes are kept', `The participant page of ${prev.experimentName} has unsaved changes; save or discard them from the note at the top.`)
  }
  if (id == null) return
  if (!docs.has(id)) {
    docs.set(id, {
      experimentId: id,
      experimentName: admin.experiment!.name,
      parts: { clientHtml: newPart(), clientGraph: newPart(), style: newPart() },
      busy: false,
    })
  }
  ensureLoaded(active.value)
}, { immediate: true })

const info = computed(() => partInfo(active.value))
const current = computed(() => doc.value?.parts[active.value] || null)
const currentDirty = computed(() => !!current.value && isDirty(current.value))
const dirtyParts = computed(() => doc.value ? PARTS.filter(p => isDirty(doc.value!.parts[p.key])) : [])
const anySaving = computed(() => !!doc.value && PARTS.some(p => doc.value!.parts[p.key].saving))

// a dot instead of the icon marks unsaved changes
const DirtyDot = defineComponent({ name: 'DirtyDot', setup: () => () => h('span', { class: 'pg-dot', 'aria-hidden': 'true' }) })
const ICONS: Record<PartKey, any> = { clientHtml: FileCode2, clientGraph: Braces, style: Palette }
const tabs = computed<TabItem[]>(() => PARTS.map(p => ({
  value: p.key,
  label: p.key === 'style' ? 'Style (CSS)' : p.key === 'clientGraph' ? 'Client graph (JavaScript)' : 'Client HTML',
  icon: doc.value && isDirty(doc.value.parts[p.key]) ? DirtyDot : ICONS[p.key],
})))

const stateText = computed(() => {
  const p = current.value
  if (!p) return ''
  if (p.status === 'idle' || p.status === 'loading') return 'Loading…'
  if (p.status === 'error') return 'Couldn’t load'
  if (p.saving) return 'Saving…'
  if (readOnly.value) return 'Read only (file mode)'
  if (isDirty(p)) return 'Unsaved changes'
  if (p.savedAt) return `Saved at ${new Date(p.savedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
  return 'No unsaved changes'
})
const stateTone = computed(() => {
  const p = current.value
  if (!p) return 'muted'
  if (p.status === 'error') return 'danger'
  if (isDirty(p)) return 'warning'
  if (p.savedAt) return 'success'
  return 'muted'
})
const announce = ref('')

// ---- loading ----

function describe (e: unknown): string {
  if (e instanceof HttpError) {
    if (e.status === 413) return 'The server refused it as too large (its limit for a request body is about 100 KB unless parsers.text.maxLength is raised).'
    if (e.status >= 500) return `The server had a problem (${e.status}: ${e.message}).`
    return e.message ? sentence(e.message) : `The server said no (${e.status}).`
  }
  return e instanceof Error ? `Couldn’t reach the server: ${sentence(e.message)}` : 'Something went wrong.'
}
const sentence = (s: string) => /[.!?]$/.test(s.trim()) ? s.trim() : `${s.trim()}.`

function ensureLoaded (k: PartKey) {
  const d = doc.value
  if (d && d.parts[k].status === 'idle') load(d, k)
}

async function load (d: PageDoc, k: PartKey) {
  const p = d.parts[k]
  p.status = 'loading'
  p.error = ''
  try {
    const body = await http.get(`/customize/${k}/${d.experimentId}`)
    const text = readPart(k, body)
    if (text === null) {
      p.status = 'error'
      p.error = `The server’s answer had no ${partInfo(k).label} in it.`
      return
    }
    p.original = text
    p.value = text
    p.status = 'ready'
    p.remoteChanged = false
    p.saveError = ''
  } catch (e) {
    p.status = 'error'
    p.error = describe(e)
  }
}

async function reloadPart (k: PartKey) {
  const d = doc.value
  if (d) await load(d, k)
}

// The server copy changed (another tab, another researcher, or a file in file mode, DL-20):
// follow it when there are no edits here, otherwise say so.
watch(
  () => admin.experiment && [admin.experiment.id, admin.experiment.clientHtmlHash, admin.experiment.clientGraphHash, admin.experiment.style ?? ''] as const,
  (now) => {
    const d = doc.value
    if (!now || !d || d.experimentId !== now[0]) return
    const server: Record<PartKey, number | string> = { clientHtml: now[1], clientGraph: now[2], style: now[3] }
    for (const { key } of PARTS) {
      const p = d.parts[key]
      if (p.status !== 'ready' || !serverDiffers(key, p, server[key])) continue
      if (isDirty(p)) p.remoteChanged = true
      else load(d, key)
    }
  },
)

// ---- saving ----

async function savePart (d: PageDoc, k: PartKey): Promise<boolean> {
  const p = d.parts[k]
  if (!canSave(p) || !isDirty(p)) return false
  if (admin.experiment?.id === d.experimentId && admin.experiment.fileMode) return false
  const text = p.value
  p.saving = true
  p.pending = text
  p.saveError = ''
  try {
    await http.post(`/customize/${k}/${d.experimentId}`, { [k]: text })
    p.original = text
    p.remoteChanged = false
    p.savedAt = Date.now()
    return true
  } catch (e) {
    p.saveError = `Couldn’t save the ${partInfo(k).label}: ${describe(e)} Your edits are still here.`
    return false
  } finally {
    p.saving = false
    p.pending = null
  }
}

async function saveKeys (d: PageDoc, keys: PartKey[]) {
  const saved: string[] = []
  const failed: string[] = []
  for (const k of keys) {
    if (await savePart(d, k)) saved.push(partInfo(k).label)
    else if (d.parts[k].saveError) failed.push(partInfo(k).label)
  }
  if (saved.length) {
    const what = saved.join(' and ') + (d.experimentId !== admin.experiment?.id ? ` of ${d.experimentName}` : '')
    notify.success(`Saved ${what}`, 'Participants see it the next time their page loads.')
    announce.value = `Saved ${what}.`
    // the server doesn't push after these saves; ask for the new hashes and style
    actions.refresh()
  }
  if (failed.length) notify.error(`Couldn’t save ${failed.join(' and ')}${d.experimentId !== admin.experiment?.id ? ` of ${d.experimentName}` : ''}`, 'Your edits are still in the editor. The reason is shown above it.')
  return { saved, failed }
}

const savingAll = ref(false)
async function saveAll () {
  const d = doc.value
  if (!d) return
  if (readOnly.value) { notify.info('Read only', 'This experiment is in file mode; edit its files instead.'); return }
  const keys = PARTS.filter(p => isDirty(d.parts[p.key])).map(p => p.key)
  if (!keys.length) { notify.info('Nothing to save', 'There are no unsaved changes.'); return }
  savingAll.value = true
  try { await saveKeys(d, keys) } finally { savingAll.value = false }
}
async function saveCurrent () {
  const d = doc.value
  if (!d) return
  if (readOnly.value) { notify.info('Read only', 'This experiment is in file mode; edit its files instead.'); return }
  if (!isDirty(d.parts[active.value])) return
  await saveKeys(d, [active.value])
}

async function saveDoc (d: PageDoc) {
  d.busy = true
  try {
    const { failed } = await saveKeys(d, PARTS.filter(p => isDirty(d.parts[p.key])).map(p => p.key))
    if (!failed.length && d.experimentId !== admin.experiment?.id) docs.delete(d.experimentId)
  } finally {
    d.busy = false
  }
}
async function discardDoc (d: PageDoc) {
  const ok = await confirm({ title: `Discard changes to ${d.experimentName}?`, message: `Your unsaved edits to ${dirtyLabels(d)} will be lost.`, confirmLabel: 'Discard', danger: true })
  if (ok) docs.delete(d.experimentId)
}

async function revert (k: PartKey) {
  const p = doc.value?.parts[k]
  if (!p) return
  const ok = await confirm({ title: `Discard your changes to the ${partInfo(k).label}?`, message: 'The editor goes back to the saved version.', confirmLabel: 'Discard changes', danger: true })
  if (ok) {
    p.value = p.original
    p.saveError = ''
  }
}

// ---- templates ----

const legacy = computed(() => current.value?.status === 'ready' && isLegacyPage(current.value.value))
const isDefaultHtml = computed(() => {
  const p = doc.value?.parts.clientHtml
  return !!p && normalize(p.value).trim() === normalize(DEFAULT_CLIENT_HTML).trim()
})
const isDefaultGraph = computed(() => {
  const p = doc.value?.parts.clientGraph
  return !!p && normalize(p.value).trim() === normalize(DEFAULT_CLIENT_GRAPH).trim()
})

async function insertNewPage () {
  const d = doc.value
  if (!d || readOnly.value) return
  const ok = await confirm({
    title: 'Replace the Client HTML with the new participant page?',
    message: 'The HTML in the editor is replaced by the standard layout: the network next to the experiment’s text, timers, choices and forms, a waiting screen while a session fills up, and a phone layout.\n\nNothing is saved until you press Save, and you can undo it in the editor.',
    confirmLabel: 'Replace HTML',
  })
  if (!ok) return
  active.value = 'clientHtml'
  d.parts.clientHtml.value = DEFAULT_CLIENT_HTML
  // the new page needs the standard client graph to show it
  const g = d.parts.clientGraph
  if (g.status === 'idle') await load(d, 'clientGraph')
  if (g.status === 'ready' && isCustomClientGraph(g.value)) {
    toast('warning', 'Check the client graph too', 'This experiment has an older, custom client graph, so the new page may not show. Use the standard script there as well.', {
      timeout: 14000,
      action: { label: 'Open Client graph', run: () => { active.value = 'clientGraph' } },
    })
  }
}

async function insertStandardGraph () {
  const d = doc.value
  if (!d || readOnly.value) return
  const ok = await confirm({
    title: 'Replace the client graph with the standard script?',
    message: 'The standard script loads Breadboard’s participant components and shows the Client HTML. Your own JavaScript in this tab is replaced.\n\nNothing is saved until you press Save, and you can undo it in the editor.',
    confirmLabel: 'Replace script',
  })
  if (ok) d.parts.clientGraph.value = DEFAULT_CLIENT_GRAPH
}

// ---- leaving with unsaved edits ----

const anyDirty = computed(() => [...docs.values()].some(docDirty))
onBeforeRouteLeave(async () => {
  if (!anyDirty.value) return true
  const where = [...docs.values()].filter(docDirty).map(d => `${dirtyLabels(d)}${docs.size > 1 ? ` (${d.experimentName})` : ''}`).join('; ')
  return await confirm({
    title: 'Leave without saving?',
    message: `You have unsaved changes to ${where}. They will be lost.`,
    confirmLabel: 'Leave without saving',
    cancelLabel: 'Stay',
    danger: true,
  })
})
function onBeforeUnload (e: BeforeUnloadEvent) {
  if (!anyDirty.value) return
  e.preventDefault()
  e.returnValue = ''
}
// Cmd/Ctrl+S anywhere on the page (the editor handles it itself while it has focus)
function onKey (e: KeyboardEvent) {
  if (!(e.metaKey || e.ctrlKey) || e.altKey || e.key.toLowerCase() !== 's' || e.defaultPrevented) return
  if (document.querySelector('.b-dialog')) return
  e.preventDefault()
  saveAll()
}
onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('keydown', onKey)
})
</script>

<style>
.pg { display: flex; flex-direction: column; min-height: 0; height: 100%; overflow: auto; }
.pg-head { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 18px 24px 12px; }
.pg-head__intro { flex: 1; min-width: 240px; color: var(--muted); }
.pg-head__actions { display: flex; gap: 8px; flex-wrap: wrap; }
.pg-notice { padding: 0 24px 12px; }
.pg-other {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--warning);
  border-radius: var(--radius);
  background: var(--warning-soft);
  color: var(--text-soft);
  font-size: var(--text-sm);
}
.pg-other > svg { color: var(--warning-soft-text); flex: none; }
.pg-other p { flex: 1; min-width: 200px; }
.pg-tabs { padding: 0 24px; }
.pg-body { flex: 1; min-height: 360px; display: flex; flex-direction: column; gap: 10px; padding: 12px 24px 0; }
.pg-info { display: flex; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.pg-info__text { flex: 1; min-width: 260px; }
.pg-info__what { font-size: var(--text-sm); color: var(--text-soft); max-width: 760px; }
.pg-info__meta { display: flex; align-items: center; gap: 7px; margin-top: 3px; font-size: var(--text-xs); color: var(--faint); }
.pg-info__actions { display: flex; gap: 4px; flex-wrap: wrap; }
.pg-state { font-weight: 600; }
.pg-state--muted { color: var(--muted); }
.pg-state--warning { color: var(--warning-soft-text); }
.pg-state--success { color: var(--success-soft-text); }
.pg-state--danger { color: var(--danger); }
.pg-dot { display: inline-block; width: 8px; height: 8px; margin: 0 3px; border-radius: 50%; background: var(--warning); }

.pg-callout {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 9px 12px;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
}
.pg-callout > svg { flex: none; }
.pg-callout p { flex: 1; min-width: 220px; }
.pg-callout--warning { background: var(--warning-soft); color: var(--warning-soft-text); }
.pg-callout--danger { background: var(--danger-soft); color: var(--danger-soft-text); }
.pg-callout--info { background: var(--accent-soft); color: var(--accent-soft-text); }

.pg-editor { position: relative; flex: 1; min-height: 280px; }
.pg-pane { position: absolute; inset: 0; }
.pg-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  padding: 18px 20px 18px 56px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--code-bg);
}
.pg-skeleton span { height: 10px; border-radius: 5px; background: var(--bg-subtle); animation: pg-pulse 1.4s ease-in-out infinite; }
@keyframes pg-pulse { 50% { opacity: 0.45; } }
.pg-error { height: 100%; display: grid; place-items: center; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); }

.pg-foot { display: flex; align-items: center; gap: 12px; padding: 8px 24px 10px; font-size: var(--text-xs); color: var(--faint); }
.pg-foot kbd + kbd { margin-left: 2px; }
@media (max-width: 640px) {
  .pg-head, .pg-notice, .pg-tabs, .pg-body, .pg-foot { padding-left: 16px; padding-right: 16px; }
  .pg-head__actions { width: 100%; }
  .pg-foot__keys { display: none; }
}
</style>
