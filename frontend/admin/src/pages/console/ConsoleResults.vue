<template>
  <section class="cres" aria-labelledby="cres-title">
    <header class="cres__head">
      <h2 id="cres-title" class="cres__title">Results</h2>
      <span v-if="runs.length" class="cres__count">{{ runs.length }}</span>
      <span class="cres__spacer" />
      <BButton size="sm" variant="ghost" :icon="PanelBottom" title="Everything the engine printed, including other pages' runs (Ctrl+J)" @click="emit('output')">All output</BButton>
      <BButton size="sm" variant="ghost" :icon="Eraser" :disabled="!runs.length" @click="emit('clear')">Clear</BButton>
    </header>

    <div ref="scroller" class="cres__body" @scroll="onScroll">
      <BEmpty
        v-if="!runs.length"
        compact
        :icon="SquareTerminal"
        title="Run Groovy in the live experiment"
        :text="`What you run here, and what the engine answers, appears in this panel. Type a command and press ${modKey}+Enter, or pick one from the library.`"
      >
        <template #actions>
          <BButton size="sm" :icon="Play" @click="emit('try', 'g.V.count()')">Try g.V.count()</BButton>
        </template>
      </BEmpty>

      <ol v-else class="cres__list">
        <li v-for="r in runs" :key="r.id">
          <article class="run" :class="`is-${r.status}`" :aria-label="`${r.label}, ${statusText(r)}`">
            <header class="run__head">
              <span class="run__status" :title="statusText(r)">
                <BSpinner v-if="r.status === 'pending'" :size="13" label="Running" />
                <CircleCheck v-else-if="r.status === 'ok'" :size="15" aria-hidden="true" />
                <CircleAlert v-else-if="r.status === 'error'" :size="15" aria-hidden="true" />
                <Hourglass v-else :size="15" aria-hidden="true" />
              </span>
              <span class="run__label">{{ r.label }}</span>
              <span class="run__meta">
                <time :datetime="new Date(r.at).toISOString()">{{ clock(r.at) }}</time>
                <template v-if="r.doneAt && r.status !== 'noreply'"> · {{ duration(r) }}</template>
              </span>
              <span class="run__spacer" />
              <span class="run__actions">
                <BButton size="sm" variant="ghost" :icon="Pencil" title="Put this script in the editor" :aria-label="`Edit ${r.label} in the editor`" @click="emit('restore', r)" />
                <BButton size="sm" variant="ghost" :icon="RotateCw" title="Run it again" :aria-label="`Run ${r.label} again`" @click="emit('rerun', r)" />
                <BButton size="sm" variant="ghost" :icon="Copy" title="Copy the script and its result" :aria-label="`Copy ${r.label} and its result`" @click="copy(r)" />
              </span>
            </header>

            <div class="run__code-wrap">
              <pre class="run__code" :class="{ 'is-clamped': lineCount(r.script) > 4 && !expanded.has(r.id) }">{{ r.script.trim() }}</pre>
              <button v-if="lineCount(r.script) > 4" type="button" class="run__more" @click="toggle(r.id)">
                {{ expanded.has(r.id) ? 'Show less' : `Show all ${lineCount(r.script)} lines` }}
              </button>
            </div>

            <div class="run__out">
              <p v-if="r.status === 'pending'" class="run__note">
                {{ now - r.at > 10000 ? 'Still waiting for the engine… It answers one script at a time, so a long one ahead of this may be running.' : 'Running…' }}
              </p>
              <template v-else-if="r.status === 'ok'">
                <p v-if="!r.values.length" class="run__note">Done. Nothing was returned.</p>
                <div v-else-if="r.values.length === 1" class="run__single">
                  <CornerDownRight :size="14" aria-hidden="true" class="run__arrow" />
                  <pre class="run__value">{{ r.values[0] }}</pre>
                </div>
                <template v-else>
                  <p class="run__note">{{ r.values.length }} results</p>
                  <ol class="run__values">
                    <li v-for="(v, i) in shownValues(r)" :key="i"><pre>{{ v }}</pre></li>
                  </ol>
                  <button v-if="r.values.length > VALUE_LIMIT && !expanded.has(-r.id)" type="button" class="run__more" @click="toggle(-r.id)">Show all {{ r.values.length }}</button>
                </template>
              </template>
              <EngineError
                v-else-if="r.status === 'error' && r.error"
                :error="r.error"
                :from-line="r.fromLine"
                :own-name="r.fromLine != null ? 'your script' : 'the script'"
                @goto="(line: number, col?: number) => emit('goto', line, col)"
              />
              <p v-else class="run__note is-warn">
                No answer from the engine. The connection may have dropped or the engine may be stuck; check the output panel, or reload the engine.
              </p>
            </div>
          </article>
        </li>
      </ol>
    </div>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { CircleAlert, CircleCheck, Copy, CornerDownRight, Eraser, Hourglass, PanelBottom, Pencil, Play, RotateCw, SquareTerminal } from 'lucide-vue-next'
import { BButton, BEmpty, BSpinner, notify } from '@/ui'
import EngineError from './EngineError.vue'
import { parseGroovyError } from './groovyError'
import type { ConsoleRun } from './consoleState'

const props = defineProps<{ runs: ConsoleRun[], modKey: string }>()
const emit = defineEmits<{
  restore: [run: ConsoleRun]
  rerun: [run: ConsoleRun]
  goto: [line: number, column?: number]
  clear: []
  output: []
  try: [code: string]
}>()

const VALUE_LIMIT = 50
const expanded = reactive(new Set<number>())
function toggle (id: number) {
  if (expanded.has(id)) expanded.delete(id)
  else expanded.add(id)
}
function shownValues (r: ConsoleRun) {
  return expanded.has(-r.id) ? r.values : r.values.slice(0, VALUE_LIMIT)
}
function lineCount (s: string) {
  return s.trim().split('\n').length
}

// a clock for "still waiting", ticking only while something runs
const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null
watch(() => props.runs.some(r => r.status === 'pending'), (busy) => {
  if (busy && !ticker) ticker = setInterval(() => { now.value = Date.now() }, 1000)
  if (!busy && ticker) { clearInterval(ticker); ticker = null }
}, { immediate: true })
onBeforeUnmount(() => { if (ticker) clearInterval(ticker) })

function statusText (r: ConsoleRun) {
  if (r.status === 'pending') return 'running'
  if (r.status === 'ok') return 'done'
  if (r.status === 'error') return 'failed'
  return 'no answer'
}
function clock (t: number) {
  return new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
function duration (r: ConsoleRun) {
  const ms = (r.doneAt || r.at) - r.at
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

async function copy (r: ConsoleRun) {
  const result = r.status === 'ok'
    ? (r.values.length ? r.values.map(v => '==> ' + v).join('\n') : '==> (nothing)')
    : r.status === 'error' && r.error ? parseGroovyError(r.error).raw : ''
  try {
    await navigator.clipboard.writeText(`${r.script.trim()}\n\n${result}`.trim())
    notify.success('Copied the script and its result')
  } catch (e) {
    notify.error('Could not copy', 'Your browser blocked the clipboard.')
  }
}

// keep the newest run in view unless the researcher scrolled up to read an older one
const scroller = ref<HTMLElement>()
let stick = true
function onScroll () {
  const el = scroller.value
  if (el) stick = el.scrollHeight - el.scrollTop - el.clientHeight < 48
}
watch(() => [props.runs.length, props.runs.length ? props.runs[props.runs.length - 1].status : ''], async () => {
  await nextTick()
  if (stick && scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
}, { immediate: true })
</script>

<style>
.cres { display: flex; flex-direction: column; min-height: 0; background: var(--surface); }
.cres__head { display: flex; align-items: center; gap: 6px; padding: 8px 12px 8px 16px; border-bottom: 1px solid var(--border); }
.cres__title { font-size: var(--text-sm); font-weight: 650; letter-spacing: 0; }
.cres__count { padding: 0 7px; border-radius: 999px; background: var(--bg-subtle); color: var(--muted); font-size: 11px; font-weight: 600; line-height: 18px; }
.cres__spacer { flex: 1; }
.cres__body { flex: 1; min-height: 0; overflow: auto; background: var(--surface-sunken); }
.cres__list { list-style: none; margin: 0; padding: 12px 16px 16px; display: flex; flex-direction: column; gap: 10px; }

.run {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
  border-left: 3px solid var(--border-strong);
}
.run.is-ok { border-left-color: var(--success); }
.run.is-error { border-left-color: var(--danger); }
.run.is-pending { border-left-color: var(--accent); }
.run.is-noreply { border-left-color: var(--warning); }
.run__head { display: flex; align-items: center; gap: 8px; padding: 6px 6px 6px 12px; min-height: 38px; }
.run__status { display: inline-grid; place-items: center; width: 16px; flex: none; color: var(--faint); }
.run.is-ok .run__status { color: var(--success); }
.run.is-error .run__status { color: var(--danger); }
.run.is-noreply .run__status { color: var(--warning); }
.run__label { font-size: var(--text-sm); font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.run__meta { font-size: var(--text-xs); color: var(--faint); white-space: nowrap; font-variant-numeric: tabular-nums; }
.run__spacer { flex: 1; }
.run__actions { display: flex; gap: 0; opacity: 0.55; transition: opacity var(--duration) var(--ease); }
.run:hover .run__actions, .run:focus-within .run__actions { opacity: 1; }
.run__code-wrap { padding: 0 12px; }
.run__code {
  margin: 0;
  padding: 7px 10px;
  border-radius: var(--radius-xs);
  background: var(--code-bg);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.run__code.is-clamped { display: -webkit-box; -webkit-line-clamp: 4; line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; }
.run__more { margin-top: 4px; padding: 0; border: 0; background: none; color: var(--accent); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.run__more:hover { text-decoration: underline; }
.run__out { padding: 9px 12px 11px; }
.run__note { color: var(--muted); font-size: var(--text-sm); }
.run__note.is-warn { color: var(--warning-soft-text); }
.run__single { display: flex; align-items: flex-start; gap: 8px; }
.run__arrow { flex: none; margin-top: 3px; color: var(--success); }
.run__value, .run__values pre {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}
.run__values { margin: 6px 0 0; padding: 0 0 0 0; list-style: none; counter-reset: v; max-height: 360px; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-xs); }
.run__values li { display: flex; gap: 10px; padding: 4px 10px; counter-increment: v; }
.run__values li + li { border-top: 1px solid var(--border); }
.run__values li::before { content: counter(v); flex: none; min-width: 22px; color: var(--faint); font-size: 11px; font-family: var(--font-mono); text-align: right; padding-top: 2px; }
@media (max-width: 640px) {
  .cres__list { padding: 10px; }
  .run__actions { opacity: 1; }
}
</style>
