<template>
  <BPage intro="Settings you choose each time you create a session, such as the number of rounds or a treatment condition. Scripts read each one by its name, so you can change a study without editing its code.">
    <template v-if="!readOnly" #actions>
      <BButton variant="primary" :icon="Plus" @click="openNew">New parameter</BButton>
    </template>

    <FileModeNotice v-if="readOnly" what="Parameters" />

    <BPanel v-if="list.length" flush>
      <div class="pp-scroll">
        <table class="pp-table">
          <caption class="sr-only">Parameters of {{ admin.experiment?.name }}</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Type</th>
              <th scope="col" class="is-num">Min</th>
              <th scope="col" class="is-num">Max</th>
              <th scope="col">Default</th>
              <th v-if="sessionValues" scope="col">
                <span class="pp-session-head" :title="`The value session “${admin.instance?.name}” was created with`">Current session</span>
              </th>
              <th scope="col">Description</th>
              <th v-if="!readOnly" scope="col" class="is-actions"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.key"
              :ref="el => setRowEl(row.key, el)"
              :class="{ 'is-new': flashKey === row.key, 'is-busy': busyKey === row.key }"
            >
              <th scope="row" class="pp-name">
                <code>{{ row.p.name }}</code>
                <ul v-if="row.issues.length" class="pp-issues">
                  <li v-for="(t, i) in row.issues" :key="i"><AlertTriangle :size="13" aria-hidden="true" /><span>{{ t }}</span></li>
                </ul>
              </th>
              <!-- (each value cell names itself too, for the stacked layout on a phone, which hides the header) -->
              <td><BBadge :tone="TYPE_TONE[row.p.type] || 'neutral'">{{ typeLabel(row.p.type) }}</BBadge></td>
              <td class="is-num mono" :class="{ 'is-blank': !numeric(row.p) }">
                <span class="pp-lbl">Min </span>
                <template v-if="numeric(row.p)">{{ row.p.minVal }}</template>
                <span v-else class="pp-none" title="Only numbers have a range">—</span>
              </td>
              <td class="is-num mono" :class="{ 'is-blank': !numeric(row.p) }">
                <span class="pp-lbl">Max </span>
                <template v-if="numeric(row.p)">{{ row.p.maxVal }}</template>
                <span v-else class="pp-none" title="Only numbers have a range">—</span>
              </td>
              <td class="pp-default">
                <span class="pp-lbl">Default </span>
                <span v-if="row.p.defaultVal == null" class="pp-missing">not set</span>
                <span v-else-if="row.p.type === 'Text' && row.p.defaultVal === ''" class="pp-none">empty</span>
                <code v-else>{{ formatDefault(row.p) }}</code>
              </td>
              <td v-if="sessionValues" class="pp-default">
                <span class="pp-lbl">Current session </span>
                <code v-if="sessionValues.has(row.p.name)">{{ sessionValues.get(row.p.name) }}</code>
                <span v-else class="pp-none" title="This session was created before the parameter existed">—</span>
              </td>
              <td class="pp-desc" :class="{ 'is-blank': !row.p.description }">{{ row.p.description }}<span v-if="!row.p.description" class="pp-none">—</span></td>
              <td v-if="!readOnly" class="is-actions">
                <div class="pp-actions">
                  <BButton size="sm" variant="ghost" :icon="Pencil" :aria-label="`Edit ${row.p.name}`" title="Edit" :disabled="row.p.id == null || !!busyKey" @click="openEdit(row.p)" />
                  <BButton size="sm" variant="danger-ghost" :icon="Trash2" :aria-label="`Delete ${row.p.name}`" title="Delete" :loading="busyKey === row.key" :disabled="row.p.id == null || (!!busyKey && busyKey !== row.key)" @click="remove(row)" />
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </BPanel>

    <BPanel v-else>
      <BEmpty
        :icon="SlidersHorizontal"
        title="No parameters yet"
        :text="readOnly
          ? 'This experiment has no parameters. Add rows to parameters.csv in its folder to create some.'
          : 'Add one for anything you might want to vary between sessions without touching the scripts: the number of rounds, the pay per point, which condition to run.'"
      >
        <template v-if="!readOnly" #actions>
          <BButton variant="primary" :icon="Plus" @click="openNew">New parameter</BButton>
        </template>
      </BEmpty>
    </BPanel>

    <section class="pp-how" aria-labelledby="pp-how-title">
      <h2 id="pp-how-title" class="pp-how__title">How parameters work</h2>
      <ol class="pp-how__steps">
        <li>
          <span class="pp-how__n" aria-hidden="true">1</span>
          <div><strong>Define them here</strong><p>Give each a name, a type, and a default. Numbers also get the range the session form accepts.</p></div>
        </li>
        <li>
          <span class="pp-how__n" aria-hidden="true">2</span>
          <div><strong>Choose values per session</strong><p>When you create a session, you are asked for every parameter, pre-filled with its default. The values are saved with the session's data.</p></div>
        </li>
        <li>
          <span class="pp-how__n" aria-hidden="true">3</span>
          <div>
            <strong>Use them in scripts</strong>
            <p>Each one is a variable with its name, already converted to its type:</p>
            <pre class="pp-how__code"><code>{{ example }}</code></pre>
          </div>
        </li>
      </ol>
    </section>

    <ParameterDialog
      v-if="admin.experiment"
      v-model:open="dialogOpen"
      :experiment-id="admin.experiment.id"
      :existing="list"
      :editing="editing"
      @saved="onSaved"
    />
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch, type ComponentPublicInstance } from 'vue'
import { AlertTriangle, Pencil, Plus, SlidersHorizontal, Trash2 } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BPage, BPanel, confirm, notify, toast } from '@/ui'
import { http, HttpError } from '@/lib/http'
import { admin, actions } from '@/store/admin'
import type { Parameter } from '@/store/types'
import FileModeNotice from './FileModeNotice.vue'
import ParameterDialog from './ParameterDialog.vue'
import { findParameterUsage, formatDefault, isNumericType, parameterIssues, typeLabel } from './parameters'

const TYPE_TONE: Record<string, 'accent' | 'info' | 'success' | 'neutral'> = { Integer: 'accent', Decimal: 'info', Boolean: 'success', Text: 'neutral' }

const readOnly = computed(() => !!admin.experiment?.fileMode)
const numeric = (p: Parameter) => isNumericType(p.type)

// What the server last pushed, plus our own changes until the next push confirms them.
const added = ref<Parameter[]>([])
const removedIds = ref(new Set<number>())
/** Parameters changed here, by id, until the server's copy shows the change. */
const edited = ref(new Map<number, Parameter>())
const serverList = computed<Parameter[]>(() => admin.experiment?.parameters || [])
const list = computed<Parameter[]>(() => {
  const base = serverList.value
    .filter(p => p.id == null || !removedIds.value.has(p.id))
    .map(p => (p.id != null && edited.value.get(p.id)) || p)
  const extra = added.value.filter(a => a.id != null && !serverList.value.some(p => p.id === a.id))
  return [...base, ...extra]
})
const sameParameter = (a: Parameter, b: Parameter) =>
  (['name', 'type', 'minVal', 'maxVal', 'defaultVal', 'description'] as const).every(k => (a[k] ?? '') === (b[k] ?? ''))
watch(serverList, (now) => {
  added.value = added.value.filter(a => a.id != null && !now.some(p => p.id === a.id))
  removedIds.value = new Set([...removedIds.value].filter(id => now.some(p => p.id === id)))
  edited.value = new Map([...edited.value].filter(([id, e]) => {
    const p = now.find(x => x.id === id)
    return !!p && !sameParameter(p, e)
  }))
})
watch(() => admin.experiment?.id, () => {
  added.value = []
  removedIds.value = new Set()
  edited.value = new Map()
  dialogOpen.value = false
})

const keyOf = (p: Parameter, i: number) => p.id != null ? `id-${p.id}` : `row-${i}-${p.name}`
const rows = computed(() => list.value.map((p, i) => ({ p, key: keyOf(p, i), issues: parameterIssues(p, list.value) })))

/** Values the selected session was created with (DL-25), to compare with the defaults. */
const sessionValues = computed(() => {
  const data = admin.instance?.data
  if (!data || !data.length) return null
  return new Map(data.map(d => [d.name, d.value]))
})

const example = computed(() => {
  const p = list.value.find(x => !parameterIssues(x, list.value).length) || null
  if (!p) return 'if (showNetwork) {\n  // only when the session was created with showNetwork = true\n}'
  const n = p.name
  switch (p.type) {
    case 'Integer': return `${n}.times { round ->\n  // runs ${n} times\n}`
    case 'Decimal': return `bonus = points * ${n}`
    case 'Boolean': return `if (${n}) {\n  // only when ${n} is true\n}`
    default: return `if (${n} == '${(p.defaultVal || 'control').replace(/'/g, '\\\'')}') {\n  // …\n}`
  }
})

// ---- create / edit ----

const dialogOpen = ref(false)
const editing = ref<Parameter | null>(null)
function openNew () {
  editing.value = null
  dialogOpen.value = true
}
function openEdit (p: Parameter) {
  editing.value = p
  dialogOpen.value = true
}

const flashKey = ref<string | null>(null)
const rowEls = new Map<string, HTMLElement>()
function setRowEl (key: string, el: Element | ComponentPublicInstance | null) {
  if (el instanceof HTMLElement) rowEls.set(key, el)
  else rowEls.delete(key)
}
let flashTimer: ReturnType<typeof setTimeout> | undefined
async function flash (p: Parameter) {
  const i = list.value.indexOf(p)
  const key = keyOf(p, i)
  flashKey.value = key
  clearTimeout(flashTimer)
  flashTimer = setTimeout(() => { flashKey.value = null }, 2200)
  await nextTick()
  rowEls.get(key)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

function onSaved (saved: Parameter) {
  if (saved.id != null) {
    if (serverList.value.some(p => p.id === saved.id)) edited.value = new Map([...edited.value, [saved.id, saved]])
    else added.value = [...added.value, saved]
  }
  const found = list.value.find(p => p.id === saved.id) || null
  if (editing.value) notify.success(`Saved ${saved.name}`, 'Sessions you create from now on use the new settings.')
  else notify.success(`Created ${saved.name}`, 'Sessions you create from now on will ask for it.')
  if (found) flash(found)
  refresh()
}

// ---- delete ----

const busyKey = ref<string | null>(null)

function describe (e: unknown): string {
  if (e instanceof HttpError) return e.status >= 500 ? `The server had a problem (${e.status}: ${e.message}).` : (e.message || `The server said no (${e.status}).`)
  return e instanceof Error ? `Couldn’t reach the server: ${e.message}` : 'Something went wrong.'
}

async function remove (row: { p: Parameter, key: string }) {
  const p = row.p
  const exp = admin.experiment
  if (!exp || p.id == null) return
  const usedIn = findParameterUsage(p.name, exp.steps || [])
  const lines = [
    'Sessions you create from now on won’t ask for it. Sessions already created keep their values.',
  ]
  if (usedIn.length) lines.push(`It is used in ${usedIn.length === 1 ? 'the step' : 'the steps'} ${usedIn.join(', ')}, which will stop with an error in new sessions unless you change ${usedIn.length === 1 ? 'it' : 'them'}.`)
  const ok = await confirm({ title: `Delete “${p.name}”?`, message: lines.join('\n\n'), confirmLabel: 'Delete parameter', danger: true })
  if (!ok) return
  busyKey.value = row.key
  try {
    await http.del(`/parameters/${exp.id}/${p.id}`)
    removedIds.value = new Set([...removedIds.value, p.id])
    toast('success', `Deleted ${p.name}`, undefined, { timeout: 8000, action: { label: 'Undo', run: () => restore(exp.id, p) } })
    refresh()
  } catch (e) {
    notify.error(`Couldn’t delete ${p.name}`, describe(e))
  } finally {
    busyKey.value = null
  }
}

/** Undo a delete: create it again with the same values (it gets a new id). */
async function restore (experimentId: number, p: Parameter) {
  try {
    const res = await http.put<{ parameter?: Parameter }>(`/parameters/${experimentId}`, {
      name: p.name, type: p.type, minVal: p.minVal ?? '', maxVal: p.maxVal ?? '', defaultVal: p.defaultVal ?? '', description: p.description ?? '',
    })
    if (res?.parameter && admin.experiment?.id === experimentId) added.value = [...added.value, res.parameter]
    notify.success(`Restored ${p.name}`)
    refresh()
  } catch (e) {
    notify.error(`Couldn’t restore ${p.name}`, describe(e))
  }
}

/** The server doesn't push after HTTP edits; ask for the new state (the launch form reads it). */
function refresh () {
  actions.refresh().then((m) => {
    if (!m) notify.warning('The list may be out of date', 'The server didn’t send the updated experiment. Reload the page if something looks wrong.')
  })
}
</script>

<style>
.pp-scroll { overflow-x: auto; }
.pp-table { width: 100%; border-collapse: collapse; font-size: var(--text-md); }
.pp-table th, .pp-table td { padding: 11px 14px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
.pp-table thead th {
  padding-top: 10px;
  padding-bottom: 10px;
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--muted);
  background: var(--surface-sunken);
  white-space: nowrap;
}
.pp-table tbody tr:last-child > * { border-bottom: 0; }
.pp-table tbody tr { transition: background 600ms var(--ease); }
.pp-table tbody tr:hover { background: var(--surface-hover); }
.pp-table tbody tr.is-new { background: var(--accent-soft); transition: none; }
.pp-table tbody tr.is-busy { opacity: 0.6; }
.pp-table .is-num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.pp-table .is-actions { width: 1%; white-space: nowrap; padding-top: 7px; padding-bottom: 7px; }
.pp-table td > .b-badge { margin-top: -1px; }
.pp-name { font-weight: 400; min-width: 140px; max-width: 320px; }
.pp-name code { font-size: 13px; font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
.pp-default code { padding: 1px 6px; border-radius: 5px; background: var(--bg-subtle); color: var(--text-soft); font-size: 12.5px; overflow-wrap: anywhere; }
.pp-desc { color: var(--text-soft); min-width: 200px; max-width: 420px; }
.pp-none { color: var(--faint); }
.pp-missing { color: var(--warning-soft-text); font-size: var(--text-sm); font-style: italic; }
.pp-session-head { border-bottom: 1px dotted var(--faint); cursor: help; }
.pp-actions { display: flex; gap: 2px; justify-content: flex-end; }
.pp-issues { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 3px; }
.pp-issues li { display: flex; gap: 5px; font-size: var(--text-xs); line-height: 1.4; color: var(--warning-soft-text); }
.pp-issues svg { flex: none; margin-top: 1px; }

.pp-how { padding: 18px 20px; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface-sunken); }
.pp-how__title { font-size: var(--text-md); font-weight: 650; margin-bottom: 14px; }
.pp-how__steps { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.pp-how__steps li { display: flex; gap: 12px; min-width: 0; }
.pp-how__steps strong { display: block; font-weight: 600; color: var(--text); margin-bottom: 2px; }
.pp-how__steps p { font-size: var(--text-sm); color: var(--muted); }
.pp-how__n {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-soft-text);
  font-size: var(--text-xs);
  font-weight: 700;
}
.pp-how__code {
  margin: 8px 0 0;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 900px) {
  .pp-how__steps { grid-template-columns: minmax(0, 1fr); gap: 16px; }
}
.pp-lbl { display: none; }
/* On a phone each parameter is a card: its name and buttons, its type and values, then its
   description. (The table scrolled sideways, with Max cut off and each row as tall as the
   description squeezed into its column.) */
@media (max-width: 600px) {
  .pp-scroll { overflow-x: visible; }
  .pp-table, .pp-table tbody { display: block; }
  .pp-table thead { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
  .pp-table tbody tr { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 16px; padding: 12px 14px; border-bottom: 1px solid var(--border); }
  .pp-table tbody tr:last-child { border-bottom: 0; }
  .pp-table tbody th, .pp-table tbody td { display: block; padding: 0; border: 0; }
  .pp-table .is-num { text-align: left; }
  .pp-table td.is-blank { display: none; }
  .pp-name { flex: 1 1 100%; min-width: 0; max-width: none; padding-right: 72px !important; }
  .pp-table .is-actions { position: absolute; top: 7px; right: 8px; width: auto; padding: 0; }
  .pp-lbl { display: inline; margin-right: 2px; font-family: var(--font); font-size: var(--text-xs); color: var(--muted); }
  .pp-desc { flex: 1 1 100%; min-width: 0; max-width: none; font-size: var(--text-sm); }
}
</style>
