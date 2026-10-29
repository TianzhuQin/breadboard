<template>
  <BPage>
    <template #intro>
      Everyone in the session, live. Find people by id, or filter by any property, e.g. <code>score&gt;5</code>. Click someone to see their screen, choices, ties and properties.
    </template>
    <template #actions>
      <BButton :icon="Download" :disabled="!rows.length" title="The players shown, with every property" @click="downloadCsv">Download CSV</BButton>
    </template>

    <BEmpty
      v-if="!admin.instance"
      :icon="Users"
      title="No session is open"
      text="Players appear here while a session runs. Start one, or resume a stopped one, from Live session."
    >
      <template #actions><BButton variant="primary" href="#/session">Go to Live session</BButton></template>
    </BEmpty>

    <div v-else class="plist" :class="{ 'has-panel': !!selectedPlayer }">
      <div class="plist__main">
        <div class="plist__bar">
          <div class="plist__search" :class="{ 'has-error': !!parsed.error }">
            <BInput
              ref="searchInput"
              v-model="query"
              :icon="ListFilter"
              placeholder="Filter: an id, or score>5, group=2, name~ann"
              aria-label="Filter players"
              :aria-describedby="statusId"
              spellcheck="false"
              autocomplete="off"
              @keydown.esc.stop="query = ''"
            >
              <template #suffix>
                <button
                  type="button"
                  class="plist__help-btn"
                  :aria-expanded="helpOpen ? 'true' : 'false'"
                  :aria-controls="helpId"
                  title="How filtering works"
                  aria-label="How filtering works"
                  @click="helpOpen = !helpOpen"
                ><CircleHelp :size="15" /></button>
              </template>
            </BInput>
          </div>

          <div class="plist__chips" role="radiogroup" aria-label="Show">
            <button
              v-for="q in visibleQuick"
              :key="q.value"
              type="button"
              role="radio"
              class="plist__chip"
              :class="{ 'is-on': quick === q.value }"
              :aria-checked="quick === q.value ? 'true' : 'false'"
              @click="quick = q.value"
            >{{ q.label }} <span class="plist__chip-count">{{ quickCounts[q.value] }}</span></button>
          </div>

          <BMenu :items="columnItems" align="right" :width="260">
            <template #trigger="{ toggle, attrs }">
              <BButton size="sm" :icon="Columns3" v-bind="attrs" @click="toggle">Columns</BButton>
            </template>
          </BMenu>
        </div>

        <div v-if="helpOpen" :id="helpId" class="plist__help">
          <p><b>Plain text</b> finds players whose id contains it. <b>Expressions</b> test a property:</p>
          <ul>
            <li><code>score=5</code> equals · <code>score!=5</code> doesn't equal</li>
            <li><code>score&gt;5</code>, <code>score&lt;5</code>, <code>score&gt;=5</code>, <code>score&lt;=5</code> compare (numbers as numbers)</li>
            <li><code>name~ann</code> contains, ignoring case</li>
            <li>Join conditions with <code>&amp;</code>: <code>ai=1 &amp; score&gt;5</code>. Dots reach inside: <code>_system.connected=false</code></li>
          </ul>
          <p v-if="examples.length" class="plist__examples">
            Try:
            <button v-for="ex in examples" :key="ex" type="button" class="plist__example" @click="query = ex">{{ ex }}</button>
          </p>
        </div>

        <p :id="statusId" class="plist__status" :class="{ 'is-error': !!parsed.error }" aria-live="polite">
          <template v-if="parsed.error">{{ parsed.error }}</template>
          <template v-else>
            Showing <b>{{ rows.length }}</b> of <b>{{ total }}</b> {{ total === 1 ? 'player' : 'players' }}<template v-if="filterWords"> where {{ filterWords }}</template>
          </template>
        </p>

        <div v-if="!total && admin.engine === 'loading'" class="plist__card">
          <BEmpty compact :icon="RotateCw" title="Reloading the engine" text="The list empties while the experiment's steps load, then fills again." />
        </div>
        <div v-else-if="!total" class="plist__card">
          <BEmpty
            compact
            :icon="Users"
            :title="running ? 'No one has joined yet' : 'This session isn\'t running'"
            :text="running ? 'People appear here as they join. Share the participant link from Live session.' : 'Players are listed while the engine runs the session. Start it again from Live session.'"
          >
            <template #actions><BButton size="sm" href="#/session">Go to Live session</BButton></template>
          </BEmpty>
        </div>
        <div v-else-if="!rows.length" class="plist__card">
          <BEmpty compact :icon="SearchX" title="No one matches" text="Change the filter, or show everyone.">
            <template #actions><BButton size="sm" @click="clearFilters">Show everyone</BButton></template>
          </BEmpty>
        </div>
        <div v-else class="plist__card plist__table-wrap">
          <table class="plist__table">
            <caption class="sr-only">Players, sorted by {{ sortLabel }} ({{ sort.dir === 1 ? 'ascending' : 'descending' }})</caption>
            <thead>
              <tr>
                <th
                  v-for="c in columns"
                  :key="c.key"
                  scope="col"
                  :class="{ 'is-num': c.numeric, 'is-sorted': sort.key === c.key }"
                  :aria-sort="sort.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'"
                >
                  <button type="button" class="plist__sort" @click="sortBy(c.key)">
                    <span>{{ c.label }}</span>
                    <component :is="sort.key === c.key ? (sort.dir === 1 ? ArrowUp : ArrowDown) : ArrowUpDown" :size="13" class="plist__sort-icon" aria-hidden="true" />
                  </button>
                </th>
              </tr>
            </thead>
            <tbody @keydown="onRowKey">
              <tr
                v-for="r in pageRows"
                :key="r.id"
                class="plist__row"
                :class="{ 'is-selected': r.id === selectedPlayer }"
                @click="toggle(r.id)"
              >
                <td class="plist__id-cell">
                  <button
                    type="button"
                    class="plist__id"
                    :data-id="r.id"
                    :aria-pressed="r.id === selectedPlayer ? 'true' : 'false'"
                    @click.stop="toggle(r.id)"
                  >
                    <span class="plist__dot" :class="`is-${r.tone}`" aria-hidden="true" />
                    <span class="plist__id-text">{{ r.name }}</span>
                  </button>
                </td>
                <td><span v-if="r.status" class="plist__status-text" :class="`is-${r.tone}`">{{ r.status }}</span><span v-else class="plist__none" aria-label="unknown">—</span></td>
                <td class="is-num">{{ r.ties }}</td>
                <td><BBadge v-if="r.choices" tone="info" :title="`${r.choices} ${r.choices === 1 ? 'option' : 'options'} on their screen, not chosen yet`">Choosing</BBadge></td>
                <td v-for="c in extraColumns" :key="c.key" class="plist__value" :class="{ 'is-num': r.numeric[c.key] }" :title="r.full[c.key]"><span v-if="isColour(r.cells[c.key])" class="plist__swatch" :style="{ background: r.cells[c.key] }" aria-hidden="true" />{{ r.cells[c.key] }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <nav v-if="pages > 1" class="plist__pager" aria-label="Pages">
          <span class="plist__pager-text">{{ pageStart + 1 }}–{{ Math.min(rows.length, pageStart + PAGE) }} of {{ rows.length }}</span>
          <BButton size="sm" :icon="ChevronLeft" :disabled="page === 0" aria-label="Previous page" @click="page--" />
          <BButton size="sm" :icon="ChevronRight" :disabled="page >= pages - 1" aria-label="Next page" @click="page++" />
        </nav>
      </div>

      <PlayerPanel v-if="selectedPlayer" v-model:width="prefs.panelWidth" variant="sticky" :label="`Details of ${playerName(selectedPlayer)}`">
        <PlayerDetails
          :id="selectedPlayer"
          context="players"
          @close="closePanel"
          @select="selectPlayer"
          @locate="showOnNetwork"
        />
      </PlayerPanel>
    </div>
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Users, Download, ListFilter, CircleHelp, Columns3, ArrowUp, ArrowDown, ArrowUpDown, ChevronLeft, ChevronRight, SearchX, RotateCcw, RotateCw,
} from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BInput, BMenu, BPage, notify, type MenuItem } from '@/ui'
import { uid } from '@/ui/uid'
import { admin, graph, running } from '@/store/admin'
import { selectedPlayer, selectPlayer, networkPrefs as prefs, loadColumns, saveColumns } from '@/network/selection'
import { parseFilter, matchFilter, describeFilter } from '@/network/filter'
import { columnProperties, connectionOf, discoverProperties, formatValue, isAi, playerName, statusOf, choicesOf, valueOf } from '@/network/players'
import { undoLast } from '@/network/tieEdits'
import { download } from '@/network/exporters'
import PlayerPanel from './PlayerPanel.vue'
import PlayerDetails from './PlayerDetails.vue'
import { BASE_COLUMNS, QUICK_FILTERS, csvKeys, quickMatch, sortNodes, toCsv, type Column, type QuickFilter, type Sort } from './table'

/** A value such as #2563eb: the table shows its colour next to it. */
function isColour (v: unknown): boolean {
  return typeof v === 'string' && /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i.test(v)
}

const route = useRoute()
const router = useRouter()
const statusId = uid('plist-status')
const helpId = uid('plist-help')
const searchInput = ref<InstanceType<typeof BInput>>()

const query = ref(typeof route.query.filter === 'string' ? route.query.filter : '')
const quick = ref<QuickFilter>('all')
const helpOpen = ref(false)
const sort = ref<Sort>({ key: 'id', dir: 1 })
const page = ref(0)
const PAGE = 100

// ---- who is there -------------------------------------------------------------------------------

const nodes = computed(() => { void graph.version.value; return Array.from(graph.nodes.values()) })
const total = computed(() => nodes.value.length)
/** Ties per person, counted once per change (the store's degree() walks every tie). */
const degrees = computed(() => {
  void graph.version.value
  const d = new Map<string, Set<string>>()
  for (const l of graph.links.values()) {
    if (l.source === l.target) continue
    if (!d.has(l.source)) d.set(l.source, new Set())
    if (!d.has(l.target)) d.set(l.target, new Set())
    d.get(l.source)!.add(l.target)
    d.get(l.target)!.add(l.source)
  }
  return d
})
const degree = (id: string) => degrees.value.get(id)?.size || 0

const properties = computed(() => discoverProperties(nodes.value))
const labelable = computed(() => columnProperties(properties.value))

// ---- columns ------------------------------------------------------------------------------------

const chosen = ref<string[] | null>(loadColumns(admin.experiment?.id))
watch(() => admin.experiment?.id, (id) => { chosen.value = loadColumns(id) })
/** Until the researcher picks, show the three properties most people have. */
const extraKeys = computed<string[]>(() => {
  if (chosen.value) return chosen.value
  return labelable.value.filter(p => !p.long).sort((a, b) => b.count - a.count || a.label.localeCompare(b.label)).slice(0, 3).map(p => p.key)
})
const extraColumns = computed<Column[]>(() => extraKeys.value.map(k => {
  const p = properties.value.find(x => x.key === k)
  return { key: k, label: p ? p.label : k, numeric: p?.kind === 'number' }
}))
const columns = computed<Column[]>(() => [...BASE_COLUMNS, ...extraColumns.value])
function toggleColumn (key: string) {
  const now = extraKeys.value.slice()
  const i = now.indexOf(key)
  if (i >= 0) now.splice(i, 1)
  else now.push(key)
  chosen.value = now
  saveColumns(admin.experiment?.id, now)
  if (i >= 0 && sort.value.key === key) sort.value = { key: 'id', dir: 1 }
}
const columnItems = computed<MenuItem[]>(() => {
  const items: MenuItem[] = [{ heading: 'Property columns' }]
  if (!labelable.value.length) items.push({ label: 'No properties yet', disabled: true })
  for (const p of labelable.value) items.push({ label: p.label, active: extraKeys.value.includes(p.key), run: () => toggleColumn(p.key) })
  items.push({ divider: true }, {
    label: 'Back to the usual columns',
    icon: RotateCcw,
    run: () => { chosen.value = null; try { localStorage.removeItem(`bb-admin-player-columns:${admin.experiment?.id ?? ''}`) } catch (e) { /* ignore */ } },
  })
  return items
})

// ---- filtering and sorting ----------------------------------------------------------------------

const parsed = computed(() => parseFilter(query.value))
const filterWords = computed(() => describeFilter(parsed.value))
const quickCounts = computed<Record<QuickFilter, number>>(() => {
  const c: Record<QuickFilter, number> = { all: 0, people: 0, ai: 0, connected: 0, disconnected: 0, choice: 0 }
  for (const n of nodes.value) {
    c.all++
    if (isAi(n)) c.ai++
    else {
      c.people++
      const s = connectionOf(n)
      if (s === 'connected') c.connected++
      else if (s === 'disconnected') c.disconnected++
    }
    if (choicesOf(n).length) c.choice++
  }
  return c
})
const visibleQuick = computed(() => QUICK_FILTERS.filter(q => q.value === 'all' || q.value === quick.value || quickCounts.value[q.value] > 0))

const filtered = computed(() => nodes.value.filter(n => quickMatch(n, quick.value) && matchFilter(parsed.value, n)))
const sorted = computed(() => sortNodes(filtered.value, sort.value, degree))
const sortLabel = computed(() => columns.value.find(c => c.key === sort.value.key)?.label || 'player')

function sortBy (key: string) {
  sort.value = sort.value.key === key ? { key, dir: sort.value.dir === 1 ? -1 : 1 } : { key, dir: key === '@ties' ? -1 : 1 }
}

interface Row {
  id: string
  /** As the network and the Preview page name them: "AI 6 (_6)" for a computer player. */
  name: string
  tone: 'ai' | 'on' | 'off' | 'none'
  status: string
  ties: number
  choices: number
  cells: Record<string, string>
  full: Record<string, string>
  numeric: Record<string, boolean>
}
const rows = computed<Row[]>(() => sorted.value.map(n => {
  const cells: Record<string, string> = {}
  const full: Record<string, string> = {}
  const numeric: Record<string, boolean> = {}
  for (const c of extraColumns.value) {
    const v = valueOf(n, c.key, degree)
    cells[c.key] = formatValue(v, 48)
    full[c.key] = formatValue(v, 2000)
    numeric[c.key] = typeof v === 'number'
  }
  const conn = connectionOf(n)
  return {
    id: n.id,
    name: playerName(n.id),
    tone: isAi(n) ? 'ai' : conn === 'connected' ? 'on' : conn === 'disconnected' ? 'off' : 'none',
    status: statusOf(n),
    ties: degree(n.id),
    choices: choicesOf(n).length,
    cells,
    full,
    numeric,
  }
}))

// big sessions page by 100 so the table stays quick
const pages = computed(() => rows.value.length > 200 ? Math.ceil(rows.value.length / PAGE) : 1)
const pageStart = computed(() => pages.value > 1 ? page.value * PAGE : 0)
const pageRows = computed(() => pages.value > 1 ? rows.value.slice(pageStart.value, pageStart.value + PAGE) : rows.value)
watch([query, quick, sort], () => { page.value = 0 })
watch(pages, (p) => { if (page.value > p - 1) page.value = Math.max(0, p - 1) })
// someone picked on the network shows up on this page's list too
watch(selectedPlayer, (id) => {
  if (!id || pages.value <= 1) return
  const i = rows.value.findIndex(r => r.id === id)
  if (i >= 0) page.value = Math.floor(i / PAGE)
}, { immediate: true })

/** Examples made from this session's own properties, so they find someone. */
const examples = computed(() => {
  const out: string[] = []
  const sample = (key: string) => nodes.value.map(n => n.data[key]).find(v => v !== undefined && v !== null && v !== '')
  const num = properties.value.find(p => p.kind === 'number' && !p.key.includes('.'))
  if (num) {
    const v = sample(num.key)
    out.push(`${num.key}>=${typeof v === 'number' ? v : 0}`)
  }
  const cat = properties.value.find(p => p.kind === 'string' && p.distinct <= 12 && !p.key.includes('.'))
  if (cat) {
    const v = String(sample(cat.key) ?? '')
    if (v && !/[&=<>~]/.test(v)) out.push(`${cat.key}=${v.length > 16 ? v.slice(0, 16) : v}`)
  }
  if (quickCounts.value.ai) out.push('ai=1')
  if (nodes.value.some(n => n.data._system && typeof n.data._system.connected === 'boolean')) out.push('_system.connected=false')
  return out
})

function clearFilters () {
  query.value = ''
  quick.value = 'all'
}

// ---- selection ----------------------------------------------------------------------------------

function toggle (id: string) {
  selectPlayer(selectedPlayer.value === id ? null : id)
}
function focusRow (id: string) {
  nextTick(() => (document.querySelector(`.plist__id[data-id="${CSS.escape(id)}"]`) as HTMLElement | null)?.focus())
}
function closePanel () {
  const id = selectedPlayer.value
  selectPlayer(null)
  if (id) focusRow(id)
}
function onRowKey (e: KeyboardEvent) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const t = e.target as HTMLElement
  const id = t?.dataset?.id
  if (!id) return
  e.preventDefault()
  const list = pageRows.value
  const i = list.findIndex(r => r.id === id)
  const next = list[i + (e.key === 'ArrowDown' ? 1 : -1)]
  if (!next) return
  focusRow(next.id)
  // moving through the list follows along in the panel when it is open
  if (selectedPlayer.value) selectPlayer(next.id)
}
function showOnNetwork (id: string) {
  selectPlayer(id)
  router.push('/network')
}

// ---- download -----------------------------------------------------------------------------------

function downloadCsv () {
  try {
    // every property anyone has (not only the ones a table column can show), plus Prolific ids
    const keys = csvKeys(nodes.value)
    const name = [admin.experiment?.name, admin.instance?.name].filter(Boolean).join('-').replace(/[^\w-]+/g, '-') || 'players'
    download(toCsv(sorted.value, keys, degree), `players-${name}.csv`, 'text/csv;charset=utf-8')
    notify.success('Players downloaded', `${rows.value.length} ${rows.value.length === 1 ? 'row' : 'rows'}, with every property the experiment set as a column.`)
  } catch (e: any) {
    notify.error('Couldn\'t download the players', e?.message || String(e))
  }
}

// ---- keyboard -----------------------------------------------------------------------------------

function typing (e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  return !!t && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))
}
function onKey (e: KeyboardEvent) {
  if (e.defaultPrevented || typing(e) || document.querySelector('.b-dialog')) return
  if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    // (its notice names the players as the table does: "AI 6 (_6)", not "_6")
    void undoLast(playerName)
    return
  }
  if (e.metaKey || e.ctrlKey || e.altKey) return
  if (e.key === 'Escape' && selectedPlayer.value) { e.preventDefault(); closePanel() }
  else if (e.key === '/') { e.preventDefault(); searchInput.value?.focus() }
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  const q = route.query.player
  if (typeof q === 'string' && q) selectPlayer(q)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style>
.plist__swatch { display: inline-block; width: 10px; height: 10px; margin-right: 7px; border-radius: 50%; vertical-align: -1px; box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.12); }
.plist { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; align-items: start; }
.plist.has-panel { grid-template-columns: minmax(0, 1fr) auto; }
.plist__main { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.plist__bar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.plist__search { flex: 1 1 280px; max-width: 460px; }
.plist__search.has-error .b-input { border-color: var(--danger); }
.plist__help-btn {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  margin-right: -4px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--faint);
  cursor: pointer;
}
.plist__help-btn:hover, .plist__help-btn[aria-expanded='true'] { color: var(--accent); background: var(--accent-soft); }
.plist__chips { display: flex; flex-wrap: wrap; gap: 6px; }
.plist__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  background: var(--surface);
  color: var(--text-soft);
  font-size: var(--text-sm);
  font-weight: 550;
  cursor: pointer;
}
.plist__chip:hover { background: var(--surface-hover); }
.plist__chip.is-on { background: var(--accent-soft); border-color: transparent; color: var(--accent-soft-text); }
.plist__chip-count { color: var(--faint); font-variant-numeric: tabular-nums; font-size: var(--text-xs); }
.plist__chip.is-on .plist__chip-count { color: inherit; opacity: 0.8; }
.plist__bar > .b-menu { margin-left: auto; }
.plist__help {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-sunken);
  font-size: var(--text-sm);
  color: var(--text-soft);
}
.plist__help ul { margin: 6px 0; padding-left: 18px; display: grid; gap: 3px; }
.plist__help code { padding: 1px 5px; border-radius: 5px; background: var(--surface); border: 1px solid var(--border); font-size: 12px; }
.plist__examples { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
.plist__example {
  padding: 2px 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-pill);
  background: var(--surface);
  font-family: var(--font-mono);
  font-size: 12px;
  cursor: pointer;
}
.plist__example:hover { border-color: var(--accent); color: var(--accent); }
.plist__status { font-size: var(--text-sm); color: var(--muted); }
.plist__status b { color: var(--text); font-weight: 650; font-variant-numeric: tabular-nums; }
.plist__status.is-error { color: var(--danger); }
.plist__card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm); }
.plist__table-wrap { overflow-x: auto; }
.plist__table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: var(--text-sm); }
.plist__table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 0;
  background: var(--surface-sunken);
  border-bottom: 1px solid var(--border);
  text-align: left;
  font-weight: 600;
  color: var(--muted);
  font-size: var(--text-xs);
  white-space: nowrap;
}
.plist__table th:first-child { border-top-left-radius: var(--radius); }
.plist__table th:last-child { border-top-right-radius: var(--radius); }
.plist__sort {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 10px 14px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  cursor: pointer;
}
.plist__table th.is-num .plist__sort { justify-content: flex-end; }
.plist__sort:hover { color: var(--text); }
.plist__sort-icon { color: var(--faint); flex: none; }
.plist__table th.is-sorted { color: var(--text); }
.plist__table th.is-sorted .plist__sort-icon { color: var(--accent); }
.plist__table td {
  padding: 7px 14px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.plist__table tbody tr:last-child td { border-bottom: 0; }
.plist__table td.is-num { text-align: right; font-variant-numeric: tabular-nums; }
.plist__row { cursor: pointer; transition: background var(--duration) var(--ease); }
.plist__row:hover { background: var(--surface-hover); }
.plist__row.is-selected { background: var(--accent-soft); }
.plist__row.is-selected td:first-child { box-shadow: inset 3px 0 0 var(--accent); }
.plist__id-cell { padding: 0 !important; }
.plist__id {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 14px;
  border: 0;
  background: transparent;
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 550;
  text-align: left;
  cursor: pointer;
}
.plist__id:focus-visible { outline-offset: -2px; border-radius: var(--radius-xs); }
.plist__id-text { overflow: hidden; text-overflow: ellipsis; }
.plist__dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.plist__dot.is-on { background: var(--success); }
.plist__dot.is-off { background: transparent; box-shadow: inset 0 0 0 1.5px var(--muted); }
.plist__dot.is-ai { background: transparent; box-shadow: inset 0 0 0 2.5px var(--accent); }
.plist__status-text { color: var(--text-soft); }
.plist__status-text.is-off { color: var(--warning-soft-text); }
.plist__status-text.is-ai { color: var(--muted); }
.plist__none { color: var(--faint); }
.plist__value { color: var(--text-soft); }
.plist__pager { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.plist__pager-text { margin-right: 6px; color: var(--muted); font-size: var(--text-sm); font-variant-numeric: tabular-nums; }

@media (max-width: 1100px) and (min-width: 721px) {
  /* not enough room beside the table: the panel floats on the right */
  .plist.has-panel { grid-template-columns: minmax(0, 1fr); }
  .plist .ppanel--sticky {
    position: fixed;
    top: var(--topbar-height);
    right: 0;
    bottom: 0;
    max-height: none;
    z-index: 40;
    border-radius: 0;
    border-width: 0 0 0 1px;
    box-shadow: var(--shadow-lg);
  }
}
</style>
