<template>
  <BPage v-if="exp" intro="Every session of this experiment. Resume or stop one, see what happened in it, and download its data.">
    <template #actions>
      <BButton
        :icon="FileSpreadsheet"
        :loading="sessionActions.isBusy('list', exp.id)"
        :disabled="!all.length"
        title="One row per session, with its status, start time and launch parameters"
        @click="sessionActions.downloadList(exp)"
      >
        Download list
      </BButton>
      <BButton variant="primary" :icon="Plus" @click="router.push('/session?new=1')">New session</BButton>
    </template>

    <BPanel v-if="!all.length" class="ss__empty">
      <BEmpty
        :icon="Database"
        title="No sessions yet"
        text="A session is one run of this experiment, with its own participants and recorded data. Start one to open the experiment to participants."
      >
        <template #actions>
          <BButton variant="primary" :icon="Plus" @click="router.push('/session?new=1')">New session</BButton>
        </template>
      </BEmpty>
    </BPanel>

    <BPanel v-else flush class="ss">
      <div class="ss__bar">
        <BTabs v-model="statusFilter" variant="pill" :tabs="statusTabs" aria-label="Show sessions by status" />
        <div class="ss__search">
          <BInput v-model="query" size="sm" :icon="Search" placeholder="Search name, ID or parameter" aria-label="Search sessions">
            <template #suffix>
              <button v-if="query" type="button" class="ss__clear" aria-label="Clear search" @click="query = ''"><X :size="14" /></button>
            </template>
          </BInput>
        </div>
      </div>

      <div class="ss__wrap">
        <table class="ss__table">
          <caption class="sr-only">Sessions of {{ exp.name }}. {{ sortCaption }}</caption>
          <thead>
            <tr>
              <th scope="col" class="ss__c-expand"><span class="sr-only">Details</span></th>
              <th v-for="c in columns" :key="c.key" scope="col" :class="c.cls" :aria-sort="ariaSort(c.key)">
                <button type="button" class="ss__sort" :class="{ 'is-on': sortKey === c.key }" @click="sortBy(c.key)">
                  {{ c.label }}
                  <component :is="sortIcon(c.key)" :size="13" aria-hidden="true" />
                </button>
              </th>
              <th scope="col" class="ss__c-actions"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            <template v-for="s in shown" :key="s.id">
              <tr
                class="ss__row"
                :class="{ 'is-current': isCurrent(s), 'is-open': expanded.has(s.id) }"
                @click="onRowClick($event, s.id)"
              >
                <td class="ss__c-expand">
                  <button
                    type="button"
                    class="ss__chev"
                    :aria-expanded="expanded.has(s.id) ? 'true' : 'false'"
                    :aria-controls="`ss-details-${s.id}`"
                    :aria-label="`Details of ${s.name}`"
                    @click="toggle(s.id)"
                  >
                    <ChevronRight :size="16" aria-hidden="true" />
                  </button>
                </td>
                <th scope="row" class="ss__name">
                  <div class="ss__name-line">
                    <span class="ss__name-text" :title="s.name">{{ s.name || 'Untitled' }}</span>
                    <BBadge v-if="isCurrent(s)" tone="accent" class="ss__current">Current</BBadge>
                  </div>
                  <div class="ss__sub">
                    <span class="mono">#{{ s.id }}</span>
                    <span class="ss__sub-narrow">{{ createdText(s) }}</span>
                    <BBadge class="ss__sub-narrow" :tone="statusMeta(s.status).tone" dot :pulse="statusMeta(s.status).pulse">{{ statusMeta(s.status).label }}</BBadge>
                  </div>
                </th>
                <td class="ss__c-status">
                  <BBadge :tone="statusMeta(s.status).tone" dot :pulse="statusMeta(s.status).pulse">{{ statusMeta(s.status).label }}</BBadge>
                </td>
                <td class="ss__c-created tabular" :title="`As the server recorded it: ${s.creationTime}`">{{ createdText(s) }}</td>
                <td class="ss__c-params tabular" :title="paramsTitle(s)">
                  <span v-if="s.data?.length">{{ s.data.length }}</span>
                  <span v-else class="ss__none">none</span>
                </td>
                <td class="ss__c-actions">
                  <div class="ss__quick">
                    <BButton
                      v-if="canStop(s)"
                      size="sm"
                      variant="ghost"
                      :icon="Square"
                      :loading="sessionActions.isBusy('stop', s.id)"
                      :disabled="sessionActions.lifecycleBusy.value"
                      :aria-label="`Stop ${s.name}`"
                      title="Stop"
                      @click="sessionActions.stop(s)"
                    />
                    <BButton
                      v-else-if="canResume(s)"
                      size="sm"
                      variant="ghost"
                      :icon="Play"
                      :loading="sessionActions.isBusy('resume', s.id)"
                      :disabled="sessionActions.lifecycleBusy.value"
                      :aria-label="`${s.status === 'STOPPED' ? 'Resume' : 'Make current'} ${s.name}`"
                      :title="s.status === 'STOPPED' ? 'Resume' : 'Make current'"
                      @click="sessionActions.resume(s)"
                    />
                    <span v-else class="ss__quick-gap" aria-hidden="true" />
                    <BButton size="sm" variant="ghost" :icon="ScrollText" :aria-label="`View the events of ${s.name}`" title="View events" @click="openEvents(s)" />
                    <BButton
                      size="sm"
                      variant="ghost"
                      :icon="Download"
                      :loading="sessionActions.isBusy('data', s.id)"
                      :aria-label="`Download the data of ${s.name} (CSV)`"
                      title="Download data (CSV)"
                      @click="sessionActions.downloadData(s)"
                    />
                  </div>
                </td>
              </tr>
              <tr v-if="expanded.has(s.id)" :id="`ss-details-${s.id}`" class="ss__details">
                <td :colspan="colspan">
                  <SessionDetails
                    :session="s"
                    :experiment-id="exp.id"
                    :current="isCurrent(s)"
                    :locked="sessionActions.lifecycleBusy.value"
                    :busy="{
                      resume: sessionActions.isBusy('resume', s.id),
                      stop: sessionActions.isBusy('stop', s.id),
                      delete: sessionActions.isBusy('delete', s.id),
                      data: sessionActions.isBusy('data', s.id),
                    }"
                    @resume="sessionActions.resume(s)"
                    @stop="sessionActions.stop(s)"
                    @delete="onDelete(s)"
                    @events="openEvents(s)"
                    @download="sessionActions.downloadData(s)"
                  />
                </td>
              </tr>
            </template>
          </tbody>
        </table>

        <BEmpty v-if="!filtered.length" compact :icon="SearchX" title="No sessions match" :text="noMatchText">
          <template #actions><BButton size="sm" @click="clearFilters">Show all sessions</BButton></template>
        </BEmpty>
      </div>

      <div class="ss__foot">
        <span>{{ footText }}</span>
        <BButton v-if="filtered.length > limit" size="sm" @click="limit += PAGE">Show {{ Math.min(PAGE, filtered.length - limit) }} more</BButton>
        <span class="ss__foot-note">Times are the server’s local time; the CSV files use UTC.</span>
      </div>
    </BPanel>

    <SessionEventsDrawer
      v-model:open="eventsOpen"
      :session="eventsSession"
      @download="(s) => sessionActions.downloadData(s)"
    />
  </BPage>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown, ArrowUp, ArrowUpDown, ChevronRight, Database, Download, FileSpreadsheet, Play, Plus, ScrollText, Search,
  SearchX, Square, X,
} from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BInput, BPage, BPanel, BTabs, type TabItem } from '@/ui'
import { admin, instances } from '@/store/admin'
import type { Instance } from '@/store/types'
import {
  canResume, canStop, formatDateTime, matchesSession, parseServerTime, sortSessions, statusMeta,
  type SessionSortKey, type SortDir,
} from './sessionData'
import { useSessionActions } from './useSessionActions'
import { useMedia } from './useMedia'
import SessionDetails from './SessionDetails.vue'
import SessionEventsDrawer from './SessionEventsDrawer.vue'

const router = useRouter()
const route = useRoute()
const sessionActions = useSessionActions()

const PAGE = 100
const exp = computed(() => admin.experiment)
const all = computed(() => instances.value)
const isCurrent = (s: Instance) => admin.instance?.id === s.id

// ---- filter, search, sort -----------------------------------------------------------------------

const statusFilter = ref<string>('all')
const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const sortKey = ref<SessionSortKey>('created')
const sortDir = ref<SortDir>('desc')
const limit = ref(PAGE)

const counts = computed(() => {
  const c: Record<string, number> = {}
  for (const s of all.value) c[s.status] = (c[s.status] || 0) + 1
  return c
})
const statusTabs = computed<TabItem[]>(() => {
  const tabs: TabItem[] = [{ value: 'all', label: 'All', count: all.value.length }]
  for (const st of ['RUNNING', 'TESTING', 'STOPPED', 'FINISHED', 'ARCHIVED']) {
    // the three everyday ones always show; the rare ones only when present
    if (counts.value[st] || ['RUNNING', 'STOPPED', 'FINISHED'].includes(st)) {
      tabs.push({ value: st, label: statusMeta(st).label, count: counts.value[st] || 0 })
    }
  }
  return tabs
})

const filtered = computed(() => {
  const list = all.value.filter(s => (statusFilter.value === 'all' || s.status === statusFilter.value) && matchesSession(s, query.value))
  return sortSessions(list, sortKey.value, sortDir.value)
})
const shown = computed(() => filtered.value.slice(0, limit.value))
watch([statusFilter, query, sortKey, sortDir], () => { limit.value = PAGE })
// a status tab that empties (e.g. the last running session stopped) falls back to all
watch(statusTabs, (tabs) => { if (!tabs.some(t => t.value === statusFilter.value)) statusFilter.value = 'all' })

const columns: { key: SessionSortKey, label: string, cls: string, first: SortDir }[] = [
  { key: 'name', label: 'Session', cls: 'ss__c-name', first: 'asc' },
  { key: 'status', label: 'Status', cls: 'ss__c-status', first: 'asc' },
  { key: 'created', label: 'Created', cls: 'ss__c-created', first: 'desc' },
  { key: 'params', label: 'Parameters', cls: 'ss__c-params', first: 'desc' },
]
function sortBy (key: SessionSortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = columns.find(c => c.key === key)!.first
  }
}
function ariaSort (key: SessionSortKey) {
  if (sortKey.value !== key) return undefined
  return sortDir.value === 'asc' ? 'ascending' : 'descending'
}
function sortIcon (key: SessionSortKey) {
  if (sortKey.value !== key) return ArrowUpDown
  return sortDir.value === 'asc' ? ArrowUp : ArrowDown
}
const sortCaption = computed(() => {
  const c = columns.find(x => x.key === sortKey.value)!
  if (c.key === 'created') return sortDir.value === 'desc' ? 'Newest first.' : 'Oldest first.'
  return `Sorted by ${c.label.toLowerCase()}, ${sortDir.value === 'asc' ? 'ascending' : 'descending'}.`
})

function clearFilters () {
  query.value = ''
  statusFilter.value = 'all'
}
const noMatchText = computed(() => {
  const st = statusFilter.value !== 'all' ? `${statusMeta(statusFilter.value).label.toLowerCase()} ` : ''
  return query.value.trim()
    ? `No ${st}session matches “${query.value.trim()}”.`
    : `There are no ${st}sessions.`
})
const footText = computed(() => {
  const n = all.value.length
  const f = filtered.value.length
  const total = `${n} ${n === 1 ? 'session' : 'sessions'}`
  if (f === n) return shown.value.length < f ? `Showing ${shown.value.length} of ${total}` : total
  return `Showing ${Math.min(shown.value.length, f)} of ${f} matching · ${total} in all`
})

// ---- rows ---------------------------------------------------------------------------------------

// The details row spans the visible columns; the CSS below hides some on narrow screens, and a
// span over hidden columns would add empty ones.
const phone = useMedia('(max-width: 640px)')
const tablet = useMedia('(max-width: 860px)')
const colspan = computed(() => phone.value ? 3 : tablet.value ? 5 : 6)

const expanded = reactive(new Set<number>())
// The current session opens with its details, as in the old console.
if (admin.instance) expanded.add(admin.instance.id)
function toggle (id: number) {
  if (expanded.has(id)) expanded.delete(id)
  else expanded.add(id)
}
/** A click anywhere on the row (except its buttons and links) opens or closes the details. */
function onRowClick (e: MouseEvent, id: number) {
  const t = e.target as HTMLElement
  // the actions cell too: a busy (disabled) button lets clicks through to it
  if (t.closest('button, a, input, select, textarea, label, .ss__c-actions')) return
  if (window.getSelection()?.toString()) return // selecting text, not clicking
  toggle(id)
}

const createdText = (s: Instance) => formatDateTime(parseServerTime(s.creationTime))
function paramsTitle (s: Instance) {
  const d = s.data || []
  if (!d.length) return 'Started without launch parameters'
  const list = d.slice(0, 8).map(x => `${x.name} = ${x.value}`).join('\n')
  return d.length > 8 ? `${list}\n…and ${d.length - 8} more` : list
}

async function onDelete (s: Instance) {
  const deleted = await sessionActions.remove(s)
  if (deleted) expanded.delete(s.id)
}

// ---- events drawer ------------------------------------------------------------------------------

const eventsOpen = ref(false)
const eventsSession = ref<Instance | null>(null)
function openEvents (s: Instance) {
  eventsSession.value = s
  eventsOpen.value = true
}
// /sessions?events=<id> opens a session's events directly (other pages link here)
function fromQuery () {
  const id = Number(route.query.events)
  if (!route.query.events || isNaN(id)) return
  const s = all.value.find(x => x.id === id)
  if (s) openEvents(s)
}
fromQuery()
watch(() => route.query.events, fromQuery)
watch(eventsOpen, (open) => {
  if (!open && route.query.events) {
    const { events: _drop, ...rest } = route.query
    router.replace({ query: rest })
  }
})
</script>

<style>
.ss { overflow: hidden; }
.ss__empty { padding: 24px 0; }
.ss__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.ss__bar .b-tabs { max-width: 100%; }
.ss__search { flex: 0 1 300px; }
.ss__clear { display: grid; place-items: center; border: 0; background: transparent; color: var(--faint); cursor: pointer; padding: 2px; border-radius: 4px; }
.ss__clear:hover { color: var(--text); background: var(--surface-hover); }

.ss__wrap { overflow-x: auto; }
.ss__table { width: 100%; border-collapse: collapse; }
.ss__table thead th {
  padding: 9px 12px;
  text-align: left;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--muted);
  background: var(--surface-sunken);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.ss__sort {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin: -3px -5px;
  padding: 3px 5px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.ss__sort svg { opacity: 0.45; }
.ss__sort:hover { color: var(--text); background: var(--surface-hover); }
.ss__sort.is-on { color: var(--text); }
.ss__sort.is-on svg { opacity: 1; color: var(--accent); }

.ss__table tbody td, .ss__table tbody th { padding: 10px 12px; border-bottom: 1px solid var(--border); vertical-align: middle; text-align: left; font-weight: 400; }
.ss__row { cursor: pointer; transition: background var(--duration) var(--ease); }
.ss__row:hover { background: var(--surface-hover); }
.ss__row.is-open { background: var(--surface-sunken); }
.ss__row.is-open > td, .ss__row.is-open > th { border-bottom-color: transparent; }
.ss__row.is-current > td:first-child { box-shadow: inset 3px 0 0 var(--accent); }
.ss__c-expand { width: 44px; padding-right: 0 !important; padding-left: 12px !important; }
.ss__chev {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.ss__chev:hover { background: var(--surface-hover); color: var(--text); }
.ss__chev svg { transition: transform var(--duration) var(--ease); }
.ss__row.is-open .ss__chev svg { transform: rotate(90deg); }
.ss__name { min-width: 160px; max-width: 360px; }
.ss__name-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ss__name-text { font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.ss__current { flex: none; }
.ss__sub { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; margin-top: 1px; font-size: var(--text-xs); color: var(--muted); white-space: nowrap; }
.ss__sub .mono { font-size: 11px; }
.ss__sub-narrow { display: none !important; }
.ss__c-status { width: 1%; white-space: nowrap; }
.ss__c-created { white-space: nowrap; color: var(--text-soft); font-size: var(--text-sm); }
.ss__c-params { width: 1%; white-space: nowrap; text-align: right !important; color: var(--text-soft); font-size: var(--text-sm); padding-right: 24px !important; }
.ss__none { color: var(--faint); }
.ss__c-actions { width: 1%; white-space: nowrap; padding-right: 14px !important; }
.ss__quick { display: flex; align-items: center; justify-content: flex-end; gap: 2px; }
.ss__quick-gap { display: inline-block; width: 28px; }
.ss__details > td { padding: 0 !important; background: var(--surface-sunken); border-bottom: 1px solid var(--border); }
.ss__row.is-current + .ss__details > td { box-shadow: inset 3px 0 0 var(--accent); }

.ss__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  padding: 10px 16px;
  font-size: var(--text-xs);
  color: var(--muted);
}
.ss__foot-note { margin-left: auto; color: var(--faint); }

@media (max-width: 860px) {
  .ss__c-params { display: none; }
}
/* Phones: name, details and quick actions only; the date and status move under the name, and a
   fixed layout keeps long names and links from widening the table past the screen. */
@media (max-width: 640px) {
  .ss__table { table-layout: fixed; }
  .ss__c-created, .ss__c-status { display: none; }
  .ss__sub-narrow { display: inline-flex !important; }
  .ss__name { min-width: 0; max-width: none; }
  .ss__table .ss__c-expand { width: 40px; padding-left: 8px !important; }
  .ss__table .ss__c-actions { width: 100px; padding-right: 8px !important; }
  .ss__search { flex: 1 1 100%; }
  .ss__foot-note { margin-left: 0; }
}
</style>
