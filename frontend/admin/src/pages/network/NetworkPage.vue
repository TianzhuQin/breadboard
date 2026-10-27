<template>
  <div class="netpage">
    <NetworkToolbar
      ref="toolbar"
      v-model:mode="mode"
      v-model:query="query"
      v-model:color-by="colorBy"
      v-model:label-by="labelBy"
      v-model:show-all-labels="prefs.showAllLabels"
      v-model:size-by-degree="prefs.sizeByDegree"
      v-model:frozen="prefs.frozen"
      :can-edit="canEdit"
      :edit-hint="tieEditsBlocked"
      :match-count="matches.length"
      :match-index="matchIndex"
      :search-error="parsed.error"
      :color-options="colorOptions"
      :label-options="labelOptions"
      :can-export="counts.people + counts.ai > 0"
      @step="step"
      @fit="net?.fit(true)"
      @relayout="net?.relayout()"
      @export="doExport"
      @shortcuts="shortcutsOpen = true"
    />

    <div class="netpage__body">
      <div class="netpage__stage">
        <AdminNetwork
          ref="net"
          :graph="graph"
          :mode="mode"
          :selected-id="selectedPlayer"
          :color-by="colorBy"
          :label-by="labelBy"
          :size-by-degree="prefs.sizeByDegree"
          :show-all-labels="prefs.showAllLabels"
          :pending="pendingEdits"
          :highlight="highlight"
          :frozen="prefs.frozen"
          @select="selectPlayer"
          @edit="onEdit"
          @legend="onLegend"
          @notice="notify.info"
        >
          <template #empty>
            <BEmpty
              v-if="!admin.instance"
              :icon="Share2"
              title="No session yet"
              text="The network shows who is in a session and how they are tied. Start a session and it fills up as people join."
            >
              <template #actions><BButton variant="primary" href="#/session">Go to Live session</BButton></template>
            </BEmpty>
            <BEmpty
              v-else-if="admin.engine === 'loading'"
              :icon="RotateCw"
              title="Reloading the engine"
              text="The network empties while the experiment's steps load, then fills again."
            />
            <BEmpty
              v-else-if="running"
              :icon="Users"
              title="No one has joined yet"
              text="People appear here as they join, and their ties as the experiment makes them. Share the participant link from Live session."
            >
              <template #actions><BButton href="#/session">Participant links</BButton></template>
            </BEmpty>
            <BEmpty
              v-else
              :icon="Share2"
              title="This session isn't running"
              :text="`“${admin.instance.name}” is ${statusWord}. Its network is shown while the engine runs it; start it again or pick another session in Live session.`"
            >
              <template #actions><BButton href="#/session">Go to Live session</BButton></template>
            </BEmpty>
          </template>
        </AdminNetwork>

        <div v-if="counts.people + counts.ai > 0" class="netpage__stats" aria-live="polite">
          <span><b>{{ counts.people }}</b> {{ counts.people === 1 ? 'person' : 'people' }}</span>
          <span v-if="counts.ai"><b>{{ counts.ai }}</b> AI</span>
          <span><b>{{ counts.ties }}</b> {{ counts.ties === 1 ? 'tie' : 'ties' }}</span>
          <span v-if="highlight" class="netpage__stats-hl"><b>{{ matches.length }}</b> found</span>
          <span v-if="pendingEdits.length" class="netpage__saving"><BSpinner :size="12" label="Saving" /> Saving {{ pendingEdits.length === 1 ? 'a change' : `${pendingEdits.length} changes` }}…</span>
          <span v-if="prefs.frozen" class="netpage__frozen"><Lock :size="12" aria-hidden="true" /> Layout frozen</span>
        </div>

        <NetworkLegend
          v-if="counts.people + counts.ai > 0"
          :entries="legend"
          :scale="legendScale"
          :title="colorTitle"
          :symbols="symbols"
        />

        <div v-if="admin.engine === 'loading' && counts.people + counts.ai > 0" class="netpage__banner" role="status">
          <BSpinner :size="14" label="Reloading" /> Reloading the engine. The network refills in a moment.
        </div>
      </div>

      <PlayerPanel v-if="selectedPlayer" v-model:width="prefs.panelWidth" variant="dock" :sheet-height="`${SHEET_VH}dvh`" :label="`Details of ${nameFor(selectedPlayer)}`">
        <PlayerDetails
          :id="selectedPlayer"
          :label-by="labelBy"
          :color="selectedColor"
          context="network"
          @close="closePanel"
          @select="selectAndReveal"
          @locate="(id: string) => net?.focusOn(id)"
        />
      </PlayerPanel>
    </div>

    <BDialog v-model:open="shortcutsOpen" title="Keyboard shortcuts" subtitle="On the network page, when you're not typing in a box." size="sm">
      <dl class="netkeys">
        <template v-for="k in SHORTCUTS" :key="k[1]">
          <dt><kbd v-for="part in k[0]" :key="part">{{ part }}</kbd></dt>
          <dd>{{ k[1] }}</dd>
        </template>
      </dl>
    </BDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Share2, Users, RotateCw, Lock } from 'lucide-vue-next'
import { BButton, BDialog, BEmpty, BSpinner, notify, type SelectOption } from '@/ui'
import { admin, graph, running } from '@/store/admin'
import AdminNetwork, { type NetworkMode, type PendingEdit } from '@/network/AdminNetwork.vue'
import type { ColorScale, LegendEntry } from '@/network/palette'
import { selectedPlayer, selectPlayer, networkPrefs as prefs, viewPrefs, setViewPref } from '@/network/selection'
import { editTie, pendingEdits, tieEditsBlocked, undoLast, undoShortcut } from '@/network/tieEdits'
import { parseFilter, matchFilter } from '@/network/filter'
import {
  colorableProperties, compareIds, connectionOf, discoverProperties, isAi, labelOf, labelableProperties, propertyTitle, valueOf,
} from '@/network/players'
import { download, exportFileName, networkGraphml, networkJson } from '@/network/exporters'
import PlayerPanel from '@/pages/players/PlayerPanel.vue'
import PlayerDetails from '@/pages/players/PlayerDetails.vue'
import NetworkToolbar from './NetworkToolbar.vue'
import NetworkLegend from './NetworkLegend.vue'

const route = useRoute()
const net = ref<InstanceType<typeof AdminNetwork>>()
const toolbar = ref<InstanceType<typeof NetworkToolbar>>()
const mode = ref<NetworkMode>('view')
const query = ref('')
const matchIndex = ref(-1)
const legend = shallowRef<LegendEntry[]>([])
const legendScale = shallowRef<ColorScale | null>(null)
const shortcutsOpen = ref(false)

// off while the session isn't running, or while the engine runs another experiment
const canEdit = computed(() => !tieEditsBlocked.value)
watch(canEdit, (v) => { if (!v) mode.value = 'view' })
/** Say why ties can't be changed now. */
function sayEditsBlocked () {
  notify.info(tieEditsBlocked.value || 'Ties can\'t be changed right now')
}

const statusWord = computed(() => ({ STOPPED: 'stopped', FINISHED: 'finished', ARCHIVED: 'archived' } as Record<string, string>)[admin.instance?.status || ''] || 'not running')

// ---- who is there -------------------------------------------------------------------------------

const nodes = computed(() => { void graph.version.value; return Array.from(graph.nodes.values()) })
const counts = computed(() => {
  let ai = 0
  for (const n of nodes.value) if (isAi(n)) ai++
  let ties = 0
  for (const l of graph.links.values()) if (l.source !== l.target && graph.nodes.has(l.source) && graph.nodes.has(l.target)) ties++
  return { people: nodes.value.length - ai, ai, ties }
})
const symbols = computed(() => ({
  ai: counts.value.ai > 0,
  away: nodes.value.some(n => !isAi(n) && connectionOf(n) === 'disconnected'),
  sized: prefs.sizeByDegree && counts.value.ties > 0,
}))

// ---- colour and names -----------------------------------------------------------------------------

const properties = computed(() => discoverProperties(nodes.value))
/**
 * Colour and names are chosen per experiment. Until the researcher picks a colour, a `color`
 * property (the old console's styling hook) colours people the way the experiment intends.
 */
const colorBy = computed<string | null>({
  get: () => {
    const v = viewPrefs(admin.experiment?.id).colorBy
    if (v !== undefined) return v
    return properties.value.some(p => p.key === 'color') ? 'color' : null
  },
  set: (v) => setViewPref(admin.experiment?.id, 'colorBy', v),
})
const labelBy = computed<string | null>({
  get: () => viewPrefs(admin.experiment?.id).labelBy ?? null,
  set: (v) => setViewPref(admin.experiment?.id, 'labelBy', v),
})
const nameFor = (id: string) => labelOf(graph.nodes.get(id) || { id, data: {} }, labelBy.value)

const colorOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [
    { value: null, label: 'One colour' },
    { value: '@status', label: 'Status (AI, connected)' },
    { value: '@ties', label: 'Number of ties' },
    ...colorableProperties(properties.value, nodes.value.length).map(p => ({ value: p.key, label: p.label })),
  ]
  const c = colorBy.value
  if (c && !opts.some(o => o.value === c)) opts.push({ value: c, label: `${propertyTitle(c)} (no one has it yet)` })
  return opts
})
const labelOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [
    { value: null, label: 'Id' },
    ...labelableProperties(properties.value).map(p => ({ value: p.key, label: p.label })),
  ]
  const l = labelBy.value
  if (l && !opts.some(o => o.value === l)) opts.push({ value: l, label: `${propertyTitle(l)} (no one has it yet)` })
  return opts
})
const colorTitle = computed(() => colorBy.value ? propertyTitle(colorBy.value) : null)

function onLegend (entries: LegendEntry[], scale: ColorScale | null) {
  legend.value = entries
  legendScale.value = scale
}
const selectedColor = computed(() => {
  void graph.version.value
  const id = selectedPlayer.value
  const s = legendScale.value
  const key = colorBy.value
  if (!id || !key || !s) return null
  const n = graph.nodes.get(id)
  return n ? s.color(valueOf(n, key, (x: string) => graph.degree(x))) : null
})

// ---- search -------------------------------------------------------------------------------------

const parsed = computed(() => parseFilter(query.value))
const matches = computed<string[]>(() => {
  void graph.version.value
  const f = parsed.value
  if (!f.conditions.length) return []
  const text = (n: { id: string, data: Record<string, any> }, t: string) => {
    const q = t.toLowerCase()
    return n.id.toLowerCase().includes(q) || (!!labelBy.value && labelOf(n, labelBy.value).toLowerCase().includes(q))
  }
  return nodes.value.filter(n => matchFilter(f, n, { text })).map(n => n.id).sort(compareIds)
})
const highlight = computed(() => parsed.value.conditions.length ? matches.value : null)
watch(query, () => { matchIndex.value = -1 })

/** Enter in the search box: show the next match (and open their details). */
function step (dir: 1 | -1) {
  const m = matches.value
  if (!m.length) return
  if (matchIndex.value < 0 || matchIndex.value >= m.length) matchIndex.value = dir === 1 ? 0 : m.length - 1
  else matchIndex.value = (matchIndex.value + dir + m.length) % m.length
  const id = m[matchIndex.value]
  selectPlayer(id)
  net.value?.focusOn(id)
}

// ---- selection ----------------------------------------------------------------------------------

// on a phone the details are a sheet over the lower half of the network; in a narrow window
// the panel floats over its right side (see the styles below)
const SHEET_VH = 56
const media = (q: string) => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(q).matches
function revealSelected (id: string) {
  if (media('(max-width: 720px)')) net.value?.reveal(id, { bottom: window.innerHeight * SHEET_VH / 100 })
  else if (media('(max-width: 1100px)')) net.value?.reveal(id, { right: prefs.panelWidth })
  else net.value?.reveal(id)
}
function selectAndReveal (id: string) {
  selectPlayer(id)
  nextTick(() => revealSelected(id))
}
// a panel that covers the network shouldn't hide the person it is about
watch(selectedPlayer, (id) => { if (id && media('(max-width: 1100px)')) setTimeout(() => revealSelected(id), 30) })
function closePanel () {
  selectPlayer(null)
  nextTick(() => (net.value?.$el as HTMLElement | undefined)?.focus({ preventScroll: true }))
}

// ---- edits --------------------------------------------------------------------------------------

function onEdit (e: PendingEdit) {
  if (!canEdit.value) {
    sayEditsBlocked()
    return
  }
  void editTie(e.op, e.a, e.b, { name: nameFor })
}

// ---- exports ------------------------------------------------------------------------------------

async function doExport (kind: 'png' | 'svg' | 'json' | 'graphml') {
  const ctx = { experiment: admin.experiment?.name, session: admin.instance?.name }
  try {
    if (kind === 'png') {
      const blob = await net.value?.exportPng()
      if (!blob) throw new Error('This browser couldn\'t draw the picture. Try the SVG instead.')
      download(blob, exportFileName('png', ctx))
    } else if (kind === 'svg') {
      download(net.value!.exportSvg(), exportFileName('svg', ctx), 'image/svg+xml')
    } else if (kind === 'json') {
      download(networkJson(graph, { ...ctx, positions: net.value?.positions() }), exportFileName('json', ctx), 'application/json')
    } else {
      download(networkGraphml(graph, { ...ctx, positions: net.value?.positions() }), exportFileName('graphml', ctx), 'application/graphml+xml')
    }
    notify.success('Network downloaded', kind === 'graphml' ? 'Open it in Gephi, igraph, NetworkX or Cytoscape.' : undefined)
  } catch (e: any) {
    notify.error('Couldn\'t download the network', e?.message || String(e))
  }
}

// ---- keyboard -----------------------------------------------------------------------------------

const SHORTCUTS: [string[], string][] = [
  [['V'], 'Select and move people'],
  [['C'], 'Add ties: click one person, then another'],
  [['X'], 'Cut ties: click a tie'],
  [['Esc'], 'Cancel a tie you are drawing, go back to Select, or close the panel'],
  [['/'], 'Find a player (an id, or a filter like score>5)'],
  [['F'], 'Freeze or unfreeze the layout'],
  [['L'], 'Show every name'],
  [[undoShortcut], 'Undo the last tie you added or cut'],
  [['0'], 'Fit everyone in view'],
  [['+', '−'], 'Zoom in and out'],
  [['Scroll'], 'Zoom in and out around the pointer (or pinch)'],
  [['Shift', 'Scroll'], 'Pan (or drag the background, or swipe sideways on a trackpad)'],
  [['←', '→', '↑', '↓'], 'On the network: move between people (Enter selects)'],
  [['Shift', '↑'], 'On the network: pan'],
  [['?'], 'This list'],
]

function typing (e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  if (!t) return false
  return t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)
}
function onKey (e: KeyboardEvent) {
  if (e.defaultPrevented || typing(e)) return
  if (document.querySelector('.b-dialog')) return
  const k = e.key
  if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && k.toLowerCase() === 'z') {
    e.preventDefault()
    void undoLast(nameFor)
    return
  }
  if (e.metaKey || e.ctrlKey || e.altKey) return
  switch (k.toLowerCase()) {
    case 'v': mode.value = 'view'; break
    case 'c': if (canEdit.value) mode.value = 'connect'; else sayEditsBlocked(); break
    case 'x': if (canEdit.value) mode.value = 'cut'; else sayEditsBlocked(); break
    case 'f': prefs.frozen = !prefs.frozen; break
    case 'l': prefs.showAllLabels = !prefs.showAllLabels; break
    case '0': net.value?.fit(true); break
    case '/': toolbar.value?.focusSearch(); break
    case '?': shortcutsOpen.value = true; break
    case 'escape':
      if (net.value?.isConnecting()) net.value.cancelConnect()
      else if (mode.value !== 'view') mode.value = 'view'
      else if (query.value) query.value = ''
      else if (selectedPlayer.value) closePanel()
      else return
      break
    default: return
  }
  e.preventDefault()
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  const q = route.query.player
  if (typeof q === 'string' && q) selectPlayer(q)
  if (selectedPlayer.value) {
    const id = selectedPlayer.value
    // after the first picture, so the view has its size
    setTimeout(() => revealSelected(id), 60)
  }
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style>
.netpage { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--graph-bg); }
.netpage__body { position: relative; flex: 1; min-height: 0; display: flex; }
.netpage__stage { position: relative; flex: 1; min-width: 0; min-height: 0; }
.netpage__stats {
  position: absolute;
  left: 14px;
  top: 12px;
  z-index: 2;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  max-width: calc(100% - 28px);
  padding: 5px 10px;
  border-radius: var(--radius-pill);
  background: color-mix(in srgb, var(--graph-bg) 82%, transparent);
  backdrop-filter: blur(4px);
  color: var(--muted);
  font-size: var(--text-sm);
  pointer-events: none;
}
.netpage__stats b { color: var(--text); font-weight: 650; font-variant-numeric: tabular-nums; }
.netpage__stats-hl b { color: var(--warning-soft-text); }
.netpage__saving, .netpage__frozen { display: inline-flex; align-items: center; gap: 5px; color: var(--accent-soft-text); font-weight: 550; }
.netpage__banner {
  position: absolute;
  left: 50%;
  top: 56px;
  transform: translateX(-50%);
  z-index: 3;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: calc(100% - 32px);
  padding: 7px 14px;
  border-radius: var(--radius-pill);
  background: var(--surface-raised);
  border: 1px solid var(--border);
  box-shadow: var(--shadow);
  color: var(--text-soft);
  font-size: var(--text-sm);
  white-space: nowrap;
}
@media (max-width: 1100px) and (min-width: 721px) {
  /* a narrow window: the panel floats over the network instead of squeezing it */
  .netpage__body > .ppanel { position: absolute; top: 0; right: 0; bottom: 0; z-index: 4; box-shadow: var(--shadow-lg); }
}
.netkeys { display: grid; grid-template-columns: auto 1fr; gap: 9px 16px; margin: 0; align-items: baseline; }
.netkeys dt { display: flex; gap: 4px; justify-content: flex-end; white-space: nowrap; }
.netkeys dd { margin: 0; color: var(--text-soft); font-size: var(--text-sm); }
</style>
