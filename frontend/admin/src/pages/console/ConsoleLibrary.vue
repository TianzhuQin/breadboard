<template>
  <div class="clib">
    <div class="clib__top">
      <BTabs v-model="tab" :tabs="tabs" variant="pill" aria-label="Library" />
      <BInput v-model="query" size="sm" :icon="Search" :placeholder="tab === 'snippets' ? 'Search commands' : 'Search your scripts'" :aria-label="tab === 'snippets' ? 'Search commands' : 'Search your scripts'" class="clib__search" />
    </div>

    <div v-if="tab === 'snippets'" class="clib__body">
      <p class="clib__intro">
        Ready-made commands. <strong>Insert</strong> puts one in the editor, selected, so {{ modKey }}+Enter runs just that.
        <span v-if="ids[0] !== '1' || ids[1] !== '2'"> Player ids are taken from the live network.</span>
      </p>
      <section v-for="g in groups" :key="g.name" class="clib__group" :aria-label="g.name">
        <h3 class="clib__group-title">{{ g.name }}</h3>
        <ul class="clib__list">
          <li v-for="s in g.items" :key="s.id" class="snip">
            <div class="snip__head">
              <span class="snip__title">{{ s.title }}</span>
              <span v-if="s.changes" class="snip__flag" title="Running this changes the live session">changes</span>
            </div>
            <p class="snip__text">{{ s.text }}</p>
            <code class="snip__code">{{ fill(s.code) }}</code>
            <div class="snip__actions">
              <BButton size="sm" :icon="CornerDownLeft" :aria-label="`Insert “${s.title}” into the editor`" @click="emit('insert', fill(s.code))">Insert</BButton>
              <BButton v-if="!s.changes" size="sm" variant="ghost" :icon="Play" :aria-label="`Run “${s.title}”`" @click="emit('run', fill(s.code), s.title)">Run</BButton>
            </div>
          </li>
        </ul>
      </section>
      <BEmpty v-if="!groups.length" compact :icon="Search" title="No command matches" :text="`Nothing in the library mentions “${query.trim()}”.`" />
    </div>

    <div v-else class="clib__body">
      <BEmpty
        v-if="!history.length"
        compact
        :icon="History"
        title="No scripts yet"
        text="Every script you run is kept here, in this browser. Click one to put it back in the editor."
      />
      <BEmpty v-else-if="!shownHistory.length" compact :icon="Search" title="No script matches" :text="`None of your scripts contains “${query.trim()}”.`" />
      <ul v-else class="clib__list">
        <li v-for="h in shownHistory" :key="h.script + h.at" class="hist">
          <button type="button" class="hist__main" :title="'Put this script in the editor'" @click="emit('restore', h.script)">
            <pre class="hist__code">{{ preview(h.script) }}</pre>
            <span class="hist__meta">
              <span class="hist__dot" :class="h.ok === true ? 'is-ok' : h.ok === false ? 'is-error' : 'is-unknown'" aria-hidden="true" />
              <span class="sr-only">{{ h.ok === true ? 'Worked.' : h.ok === false ? 'Failed.' : '' }}</span>
              {{ when(h.at) }}<template v-if="h.count > 1"> · run {{ h.count }} times</template>
            </span>
          </button>
          <span class="hist__actions">
            <BButton size="sm" variant="ghost" :icon="Play" title="Run it again" :aria-label="`Run again: ${firstLine(h.script)}`" @click="emit('runHistory', h.script)" />
            <BButton size="sm" variant="ghost" :icon="X" title="Remove from history" :aria-label="`Remove from history: ${firstLine(h.script)}`" @click="emit('remove', h.script)" />
          </span>
        </li>
      </ul>
      <div v-if="history.length" class="clib__foot">
        <span class="clib__foot-note">Kept in this browser</span>
        <BButton size="sm" variant="danger-ghost" :icon="Trash2" @click="emit('clearHistory')">Clear history</BButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { CornerDownLeft, History, Play, Search, Trash2, X } from 'lucide-vue-next'
import { BButton, BEmpty, BInput, BTabs } from '@/ui'
import { SNIPPETS, SNIPPET_GROUPS, fillSnippet, searchSnippets } from './snippets'
import { searchHistory, type HistoryItem } from './consoleState'

const props = defineProps<{ history: HistoryItem[], ids: [string, string], modKey: string }>()
const emit = defineEmits<{
  insert: [code: string]
  run: [code: string, title: string]
  restore: [script: string]
  runHistory: [script: string]
  remove: [script: string]
  clearHistory: []
}>()

const tab = defineModel<string>('tab', { default: 'snippets' })
const query = ref('')
const tabs = computed(() => [
  { value: 'snippets', label: 'Commands' },
  { value: 'history', label: 'History', count: props.history.length || null },
])

const groups = computed(() => {
  const found = searchSnippets(SNIPPETS, query.value)
  return SNIPPET_GROUPS
    .map(name => ({ name, items: found.filter(s => s.group === name) }))
    .filter(g => g.items.length)
})
const shownHistory = computed(() => searchHistory(props.history, query.value))

function fill (code: string) {
  return fillSnippet(code, props.ids)
}
function firstLine (s: string) {
  const l = s.trim().split('\n')[0]
  return l.length > 60 ? l.slice(0, 57) + '…' : l
}
function preview (s: string) {
  const lines = s.trim().split('\n')
  return lines.slice(0, 3).join('\n') + (lines.length > 3 ? `\n… ${lines.length - 3} more lines` : '')
}
function when (t: number) {
  const diff = Date.now() - t
  if (diff < 60_000) return 'just now'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`
  const d = new Date(t)
  const today = new Date()
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (d.toDateString() === today.toDateString()) return `today ${time}`
  const y = new Date(today)
  y.setDate(today.getDate() - 1)
  if (d.toDateString() === y.toDateString()) return `yesterday ${time}`
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ' ' + time
}
</script>

<style>
.clib { display: flex; flex-direction: column; min-height: 0; height: 100%; background: var(--surface); }
.clib__top { display: flex; flex-direction: column; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--border); }
.clib__top .b-tabs--pill { align-self: flex-start; }
.clib__body { flex: 1; min-height: 0; overflow: auto; padding: 4px 0 12px; }
.clib__intro { padding: 10px 14px 2px; color: var(--muted); font-size: var(--text-xs); line-height: 1.5; }
.clib__intro strong { color: var(--text-soft); font-weight: 600; }
.clib__group { padding: 6px 0 2px; }
.clib__group-title { padding: 10px 14px 6px; font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.clib__list { list-style: none; margin: 0; padding: 0 8px; display: flex; flex-direction: column; gap: 2px; }

.snip { position: relative; padding: 9px 8px 9px 8px; border-radius: var(--radius-sm); }
.snip:hover, .snip:focus-within { background: var(--surface-hover); }
.snip__head { display: flex; align-items: center; gap: 8px; }
.snip__title { font-size: var(--text-sm); font-weight: 600; color: var(--text); }
.snip__flag { font-size: 10.5px; font-weight: 600; letter-spacing: 0.02em; padding: 0 6px; line-height: 17px; border-radius: 999px; background: var(--warning-soft); color: var(--warning-soft-text); }
.snip__text { margin-top: 2px; font-size: var(--text-xs); color: var(--muted); line-height: 1.45; }
.snip__code {
  display: block;
  margin-top: 6px;
  padding: 5px 8px;
  border-radius: var(--radius-xs);
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: 11.5px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.snip__actions { display: flex; gap: 4px; margin-top: 7px; }

.hist { display: flex; align-items: flex-start; gap: 2px; border-radius: var(--radius-sm); }
.hist:hover, .hist:focus-within { background: var(--surface-hover); }
.hist__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 8px 8px; border: 0; background: none; text-align: left; cursor: pointer; border-radius: var(--radius-sm); color: inherit; }
.hist__code { margin: 0; font-size: 11.5px; line-height: 1.5; color: var(--text); white-space: pre-wrap; word-break: break-word; }
.hist__meta { display: flex; align-items: center; gap: 6px; font-size: var(--text-xs); color: var(--faint); }
.hist__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--border-strong); flex: none; }
.hist__dot.is-ok { background: var(--success); }
.hist__dot.is-error { background: var(--danger); }
.hist__actions { display: flex; padding-top: 4px; padding-right: 4px; opacity: 0.5; }
.hist:hover .hist__actions, .hist:focus-within .hist__actions { opacity: 1; }
.clib__foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 10px 14px 0; padding-top: 10px; border-top: 1px solid var(--border); }
.clib__foot-note { font-size: var(--text-xs); color: var(--faint); }
@media (hover: none) { .hist__actions { opacity: 1; } }
</style>
