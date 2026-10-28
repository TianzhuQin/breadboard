<template>
  <div ref="root" class="con" :class="{ 'has-lib': wide && libOpen }" @keydown="onKey">
    <section class="con__main" aria-label="Script editor and results">
      <div class="con__bar">
        <BButton variant="primary" :icon="Play" :disabled="!hasText" :title="`${runHint} (${modKey}+Enter)`" @click="runFromEditor">{{ runLabel }}</BButton>
        <span class="con__keys" aria-hidden="true"><kbd>{{ modSymbol }}</kbd><kbd>↵</kbd></span>
        <p class="con__ctx" :title="ctxTitle">
          <span class="con__ctx-dot" :class="`is-${ctxTone}`" aria-hidden="true" />
          <span class="con__ctx-text">{{ ctxText }}</span>
        </p>
        <span class="con__spacer" />
        <span v-if="consoleState.draftSavedAt && consoleState.buffer" class="con__draft" title="Your script is kept in this browser, so reloading the page or coming back later doesn't lose it.">
          <Check :size="13" aria-hidden="true" /> Draft kept
        </span>
        <BButton size="sm" variant="ghost" :icon="BookOpen" :aria-pressed="libOpen ? 'true' : 'false'" title="Ready-made commands and your history" @click="toggleLib">Library</BButton>
        <BMenu :items="moreItems" align="right" :width="290">
          <template #trigger="{ toggle, attrs }">
            <BButton size="sm" variant="ghost" :icon="Ellipsis" aria-label="More actions" v-bind="attrs" @click="toggle" />
          </template>
        </BMenu>
      </div>

      <div v-if="notice" class="con__notice" :class="`is-${notice.tone}`" role="status">
        <component :is="notice.icon" :size="15" aria-hidden="true" />
        <span>{{ notice.text }}</span>
      </div>

      <div ref="splitEl" class="con__split">
        <div class="con__editor" :style="{ flexGrow: split }">
          <BCodeEditor
            ref="editor"
            v-model="consoleState.buffer"
            language="groovy"
            fill
            aria-label="Groovy script"
            :placeholder="`Type Groovy here, e.g. g.V.count(), then press ${modKey}+Enter to run it.`"
            :on-run="runFromEditor"
            :on-save="keepDraft"
          />
        </div>
        <div
          class="con__grip"
          role="separator"
          tabindex="0"
          aria-orientation="horizontal"
          aria-label="Resize the editor and the results"
          aria-valuemin="20"
          aria-valuemax="80"
          :aria-valuenow="Math.round(split * 100)"
          @pointerdown="startResize"
          @keydown.up.prevent="setSplit(split - 0.05)"
          @keydown.down.prevent="setSplit(split + 0.05)"
        />
        <ConsoleResults
          class="con__results"
          :style="{ flexGrow: 1 - split }"
          :runs="consoleState.runs"
          :mod-key="modKey"
          @restore="r => replaceBuffer(r.script, 'Put the script back in the editor')"
          @rerun="rerun"
          @goto="goto"
          @clear="clearRuns"
          @output="openOutput = true"
          @try="code => start(code, 'Command', null)"
        />
      </div>
    </section>

    <aside v-if="wide && libOpen" class="con__lib" aria-label="Library">
      <ConsoleLibrary
        v-model:tab="libTab"
        :history="consoleState.history"
        :ids="ids"
        :mod-key="modKey"
        @insert="insertSnippet"
        @run="(code, title) => start(code, title, null)"
        @restore="s => replaceBuffer(s, 'Put a script from your history in the editor')"
        @run-history="s => start(s, 'From history', null)"
        @remove="removeFromHistory"
        @clear-history="askClearHistory"
      />
    </aside>
    <BDialog v-if="!wide" v-model:open="libOpen" title="Library" subtitle="Ready-made commands and the scripts you ran" placement="right" size="md" flush>
      <ConsoleLibrary
        v-model:tab="libTab"
        :history="consoleState.history"
        :ids="ids"
        :mod-key="modKey"
        @insert="insertSnippet"
        @run="(code, title) => { libOpen = false; start(code, title, null) }"
        @restore="s => { libOpen = false; replaceBuffer(s, 'Put a script from your history in the editor') }"
        @run-history="s => { libOpen = false; start(s, 'From history', null) }"
        @remove="removeFromHistory"
        @clear-history="askClearHistory"
      />
    </BDialog>

    <p class="sr-only" aria-live="polite">{{ announce }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BookOpen, Check, Copy, Ellipsis, Eraser, History, Info, PanelBottom, Play, WifiOff } from 'lucide-vue-next'
import { BButton, BDialog, BMenu, confirm, notify, toast, type MenuItem } from '@/ui'
import BCodeEditor from '@/ui/BCodeEditor.vue'
import { admin, connectionState, ensureConnected, graph, running } from '@/store/admin'
import { openOutput } from '@/shell/output'
import ConsoleResults from './ConsoleResults.vue'
import ConsoleLibrary from './ConsoleLibrary.vue'
import {
  clearHistory, clearRuns, consoleState, removeFromHistory, runConsoleScript, saveDraftNow, seedConsole,
  type ConsoleRun,
} from './consoleState'
import { goToLine, insertBlock, onViewUpdate, selectionOf, viewIn, type SelectionInfo } from './editorView'
import { parseGroovyError } from './groovyError'
import { pickIds } from './snippets'

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)
const modKey = isMac ? 'Cmd' : 'Ctrl'
const modSymbol = isMac ? '⌘' : 'Ctrl'

// The buffer is ours from now on: seeded once from the server's copy, never replaced by pushes.
seedConsole(admin.email || 'admin', admin.user?.currentScript)

const root = ref<HTMLElement>()
const editor = ref<InstanceType<typeof BCodeEditor>>()
const sel = ref<SelectionInfo | null>(null)
function view () {
  return viewIn(editor.value?.$el as Element | undefined)
}

onMounted(() => {
  const v = view()
  if (v) {
    onViewUpdate(v, u => { if (u.selectionSet || u.docChanged) sel.value = selectionOf(u.view) })
    // start where the researcher left off: at the end of the script
    v.dispatch({ selection: { anchor: v.state.doc.length } })
    v.focus()
  }
})

const hasText = computed(() => !!consoleState.buffer.trim())
const hasSelection = computed(() => !!sel.value && !sel.value.empty && !!sel.value.text.trim())
const runLabel = computed(() => hasSelection.value ? 'Run selection' : 'Run script')
const runHint = computed(() => {
  if (!hasSelection.value || !sel.value) return 'Runs the whole script in the live engine'
  const { fromLine, toLine } = sel.value
  return fromLine === toLine ? `Runs line ${fromLine} only` : `Runs lines ${fromLine}–${toLine} only`
})

// ---- where scripts run ----------------------------------------------------------------------

const ctxTone = computed(() => admin.engine === 'loading' ? 'busy' : running.value ? 'live' : 'idle')
const ctxText = computed(() => {
  if (admin.engine === 'loading') return 'The engine is reloading'
  const i = admin.instance
  if (running.value && i) return `Runs in the live session “${i.name}”: changes apply at once`
  return 'No session is running: scripts run in the experiment\'s engine'
})
const ctxTitle = computed(() => `${runHint.value}. Experiment: ${admin.experiment?.name || 'none'}.`)

const notice = computed(() => {
  if (connectionState.value !== 'open') {
    return { tone: 'warn', icon: WifiOff, text: 'Not connected to the server, so scripts can\'t run right now. Your script stays in the editor: run it again once the connection is back.' }
  }
  if (admin.engine === 'stale') {
    return { tone: 'info', icon: Info, text: 'Steps changed since the engine last loaded them, so names they define may be out of date here. Reload the engine (top right) to load the saved steps.' }
  }
  return null
})

// ---- running ----------------------------------------------------------------------------------

const announce = ref('')

function runFromEditor () {
  const v = view()
  let text = consoleState.buffer
  let label = 'Script'
  let fromLine: number | null = 1
  const s = v ? selectionOf(v) : null
  if (s && !s.empty && s.text.trim()) {
    text = s.text
    fromLine = s.fromLine
    label = s.fromLine === s.toLine ? `Line ${s.fromLine}` : `Lines ${s.fromLine}–${s.toLine}`
  } else if (!v) {
    const t = editor.value?.selection() || ''
    if (t.trim()) { text = t; fromLine = null; label = 'Selection' }
  }
  if (!text.trim()) {
    notify.info('Nothing to run yet', 'Type a Groovy command, or insert one from the library.')
    return
  }
  start(text, label, fromLine)
}

function start (text: string, label: string, fromLine: number | null) {
  const run = runConsoleScript(text, { label, fromLine })
  if (!run) {
    // nothing was sent: say so (the toast says why)
    ensureConnected()
    announce.value = `${label} did not run: not connected to the server.`
    return
  }
  announce.value = `Running ${label}`
  const stop = watch(() => run.status, (s) => {
    if (s === 'pending') return
    stop()
    if (s === 'ok') announce.value = run.values.length ? `${label} done: ${run.values.slice(0, 3).join(', ')}` : `${label} done.`
    else if (s === 'error' && run.error) announce.value = `${label} failed: ${parseGroovyError(run.error).title}`
    else announce.value = `${label}: no answer from the engine.`
  })
}

/** Run an earlier run again, keeping its line mapping only if the editor still has it there. */
function rerun (r: ConsoleRun) {
  let fromLine = r.fromLine
  if (fromLine != null) {
    const lines = consoleState.buffer.split('\n')
    const n = r.script.split('\n').length
    if (lines.slice(fromLine - 1, fromLine - 1 + n).join('\n') !== r.script) fromLine = null
  }
  start(r.script, r.label, fromLine)
}

function goto (line: number, column?: number) {
  const v = view()
  if (v) goToLine(v, line, column)
}

// ---- the buffer -------------------------------------------------------------------------------

function replaceBuffer (text: string, what: string) {
  const before = consoleState.buffer
  if (before === text) {
    view()?.focus()
    return
  }
  consoleState.buffer = text
  nextTick(() => {
    const v = view()
    if (v) { v.dispatch({ selection: { anchor: v.state.doc.length } }); v.focus() }
  })
  if (before.trim()) {
    toast('info', what, 'What was in the editor before was replaced.', { action: { label: 'Undo', run: () => { consoleState.buffer = before } } })
  }
}

function insertSnippet (code: string) {
  if (!wide.value) libOpen.value = false
  nextTick(() => {
    const v = view()
    if (v) insertBlock(v, code)
    else editor.value?.insert(code)
  })
}

function keepDraft () {
  if (!saveDraftNow()) notify.warning('Could not keep a draft', 'This browser blocks local storage, so the script lives only as long as this tab.')
}

async function copyScript () {
  try {
    await navigator.clipboard.writeText(consoleState.buffer)
    notify.success('Copied the script')
  } catch (e) {
    notify.error('Could not copy', 'Your browser blocked the clipboard.')
  }
}

async function askClearHistory () {
  const n = consoleState.history.length
  const ok = await confirm({
    title: 'Clear your script history?',
    message: `This removes the ${n} script${n === 1 ? '' : 's'} kept in this browser. The editor and the results stay as they are.`,
    confirmLabel: 'Clear history',
    danger: true,
  })
  if (ok) clearHistory()
}

const serverScript = computed(() => admin.user?.currentScript || '')
const moreItems = computed<MenuItem[]>(() => [
  {
    label: 'Use the last script saved on the server',
    icon: History,
    disabled: !serverScript.value.trim() || serverScript.value === consoleState.buffer,
    run: () => replaceBuffer(serverScript.value, 'Loaded the last script you ran, from the server'),
  },
  { label: 'Copy the script', icon: Copy, disabled: !consoleState.buffer, run: copyScript },
  { label: 'Clear the editor', icon: Eraser, disabled: !consoleState.buffer, run: () => replaceBuffer('', 'Cleared the editor') },
  { divider: true },
  { label: 'Show all engine output', icon: PanelBottom, hint: 'Ctrl+J', run: () => { openOutput.value = true } },
])

function onKey (e: KeyboardEvent) {
  if (e.defaultPrevented || !(e.metaKey || e.ctrlKey)) return
  // the editor handles these itself when it has focus
  if (e.key === 'Enter' && !e.shiftKey && !e.altKey) {
    const t = e.target as HTMLElement
    if (t.closest('input, textarea, [role="dialog"]')) return
    e.preventDefault()
    runFromEditor()
  } else if (e.key.toLowerCase() === 's' && !e.shiftKey) {
    e.preventDefault()
    keepDraft()
  }
}

// ---- layout: library column (wide) or drawer (narrow), resizable split -----------------------

const WIDE = '(min-width: 1180px)'
const LIB_KEY = 'bb-console-library'
const SPLIT_KEY = 'bb-console-split'
const mq = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(WIDE) : null
const wide = ref(mq ? mq.matches : true)
const libTab = ref('snippets')
function libPref () {
  try { return localStorage.getItem(LIB_KEY) !== '0' } catch (e) { return true }
}
const libOpen = ref(wide.value ? libPref() : false)
function toggleLib () {
  libOpen.value = !libOpen.value
  if (wide.value) {
    try { localStorage.setItem(LIB_KEY, libOpen.value ? '1' : '0') } catch (e) { /* ignore */ }
  }
}
function onMedia () {
  wide.value = !!mq?.matches
  libOpen.value = wide.value ? libPref() : false
}
mq?.addEventListener?.('change', onMedia)
onBeforeUnmount(() => mq?.removeEventListener?.('change', onMedia))

const splitEl = ref<HTMLElement>()
const split = ref(readSplit())
function readSplit () {
  try {
    const v = Number(localStorage.getItem(SPLIT_KEY))
    return v >= 0.2 && v <= 0.8 ? v : 0.55
  } catch (e) { return 0.55 }
}
function setSplit (v: number) {
  split.value = Math.min(0.8, Math.max(0.2, Math.round(v * 100) / 100))
  try { localStorage.setItem(SPLIT_KEY, String(split.value)) } catch (e) { /* ignore */ }
}
function startResize (e: PointerEvent) {
  const box = splitEl.value?.getBoundingClientRect()
  if (!box || !box.height) return
  e.preventDefault()
  const move = (ev: PointerEvent) => setSplit((ev.clientY - box.top) / box.height)
  const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

// player ids for the commands, from the live network
const ids = computed<[string, string]>(() => {
  void graph.version.value
  return pickIds([...graph.nodes.keys()].map(String))
})
</script>

<style>
.con {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  height: 100%;
  min-height: 0;
  background: var(--bg);
}
.con.has-lib { grid-template-columns: minmax(0, 1fr) 340px; }
.con__main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
.con__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
  min-width: 0;
}
.con__keys { display: inline-flex; gap: 3px; }
.con__ctx { display: flex; align-items: center; gap: 7px; min-width: 0; color: var(--muted); font-size: var(--text-sm); }
.con__ctx-text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.con__ctx-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--faint); flex: none; }
.con__ctx-dot.is-live { background: var(--success); box-shadow: 0 0 0 3px color-mix(in srgb, var(--success) 22%, transparent); }
.con__ctx-dot.is-busy { background: var(--warning); }
.con__spacer { flex: 1; }
.con__draft { display: inline-flex; align-items: center; gap: 4px; color: var(--faint); font-size: var(--text-xs); white-space: nowrap; }
.con__notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 16px;
  font-size: var(--text-sm);
  border-bottom: 1px solid var(--border);
}
.con__notice svg { flex: none; margin-top: 2px; }
.con__notice.is-warn { background: var(--warning-soft); color: var(--warning-soft-text); }
.con__notice.is-info { background: var(--info-soft); color: var(--info-soft-text); }
.con__split { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.con__editor { flex-basis: 0; min-height: 110px; padding: 12px 16px 6px; display: flex; flex-direction: column; }
.con__editor > .b-code { flex: 1; }
.con__grip { flex: none; height: 9px; margin: 0 16px; cursor: ns-resize; position: relative; outline: none; }
.con__grip::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 3px;
  width: 36px;
  height: 3px;
  margin-left: -18px;
  border-radius: 3px;
  background: var(--border-strong);
  transition: background var(--duration) var(--ease);
}
.con__grip:hover::after, .con__grip:focus-visible::after { background: var(--accent); }
.con__results { flex-basis: 0; min-height: 140px; border-top: 1px solid var(--border); }
.con__lib { border-left: 1px solid var(--border); min-height: 0; display: flex; flex-direction: column; }
.con__lib > .clib { flex: 1; }
@media (max-width: 1400px) {
  .con__keys { display: none; }
}
@media (max-width: 760px) {
  .con__bar { flex-wrap: wrap; padding: 8px 12px; gap: 8px; }
  .con__ctx { order: 10; flex-basis: 100%; }
  .con__draft { display: none; }
  .con__editor { padding: 10px 12px 4px; }
  .con__grip { margin: 0 12px; }
}
</style>
