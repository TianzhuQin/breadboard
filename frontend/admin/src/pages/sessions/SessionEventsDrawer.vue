<template>
  <BDialog
    :open="open"
    placement="right"
    size="xl"
    flush
    :title="session ? `Events of “${session.name}”` : 'Events'"
    :subtitle="subtitle"
    @update:open="(v: boolean) => emit('update:open', v)"
  >
    <div class="sev">
      <div class="sev__bar" role="toolbar" aria-label="Filter events">
        <div class="sev__search">
          <BInput
            v-model="query"
            size="sm"
            :icon="Search"
            placeholder="Search names and values"
            aria-label="Search events"
            data-autofocus
          />
        </div>
        <div class="sev__select">
          <BSelect v-model="nameFilter" size="sm" :options="nameOptions" aria-label="Show one kind of event" />
        </div>
        <BTabs v-model="view" variant="pill" :tabs="viewTabs" aria-label="How to list the events" />
        <div class="sev__bar-end">
          <BButton
            v-if="view === 'timeline'"
            size="sm"
            variant="ghost"
            :icon="ArrowDownUp"
            :aria-label="newestFirst ? 'Newest first (switch to oldest first)' : 'Oldest first (switch to newest first)'"
            :title="newestFirst ? 'Newest first' : 'Oldest first'"
            @click="newestFirst = !newestFirst"
          />
          <BButton size="sm" variant="ghost" :icon="RotateCw" :loading="loading" aria-label="Load again" title="Load again" @click="load" />
          <BButton size="sm" :icon="Download" :disabled="!filtered.length" title="Save the events listed here as a CSV file" @click="exportView">
            Export {{ isFiltered ? 'filtered' : 'all' }}
          </BButton>
        </div>
      </div>

      <!-- announced only when the researcher filters, not on every live event -->
      <p class="sr-only" role="status">{{ announcement }}</p>
      <div v-if="events.length || isLive" class="sev__meta">
        <template v-if="events.length">
          <span v-if="isFiltered">{{ filtered.length.toLocaleString() }} of {{ events.length.toLocaleString() }} events match</span>
          <span v-else>{{ events.length.toLocaleString() }} {{ events.length === 1 ? 'event' : 'events' }}</span>
          <button v-if="isFiltered" type="button" class="sev__link" @click="clearFilters">Clear filters</button>
        </template>
        <BBadge v-if="isLive" tone="success" dot pulse class="sev__live">Live</BBadge>
      </div>

      <div class="sev__scroll">
        <div v-if="loading && !events.length" class="sev__state" role="status">
          <BSpinner :size="20" />
          <span>Loading the events of this session…</span>
        </div>

        <BEmpty
          v-else-if="failed && !events.length"
          compact
          :icon="CircleAlert"
          title="Couldn’t load the events"
          text="The server did not send them. It may be busy restarting, or the session may have been deleted. Try again, or download the server’s CSV instead."
        >
          <template #actions>
            <BButton size="sm" variant="primary" :icon="RotateCw" @click="load">Try again</BButton>
            <BButton v-if="session" size="sm" :icon="Download" @click="emit('download', session)">Download CSV</BButton>
          </template>
        </BEmpty>

        <BEmpty
          v-else-if="!events.length"
          compact
          :icon="ScrollText"
          :title="isLive ? 'No events recorded yet' : 'No events were recorded'"
          :text="isLive
            ? 'Events appear here as they happen: a player logging in, a choice, anything the steps record with a.addEvent(…).'
            : 'Nothing was recorded in this session. Events are recorded while a session runs.'"
        />

        <BEmpty
          v-else-if="!filtered.length"
          compact
          :icon="SearchX"
          title="No events match"
          text="Try other words, or show every kind of event."
        >
          <template #actions><BButton size="sm" @click="clearFilters">Clear filters</BButton></template>
        </BEmpty>

        <!-- Timeline: every event in time order -->
        <template v-else-if="view === 'timeline'">
          <table class="sev__table">
            <caption class="sr-only">Events of {{ session?.name }}, {{ newestFirst ? 'newest' : 'oldest' }} first</caption>
            <thead>
              <tr>
                <th scope="col" class="sev__col-time">Time</th>
                <th scope="col" class="sev__col-name">Event</th>
                <th scope="col">Data</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(e, i) in shownTimeline" :key="e.id ?? `n${i}`">
                <td class="sev__time tabular" :title="timeTitle(e)">
                  <span v-if="multiDay" class="sev__day">{{ formatDay(e.time) }}</span>{{ formatClock(e.time) }}
                </td>
                <td>
                  <button
                    type="button"
                    class="sev__name"
                    :title="`Show only “${e.name}” events`"
                    @click="nameFilter = e.name"
                  >
                    {{ e.name }}
                  </button>
                </td>
                <td class="sev__data">
                  <span v-for="(d, j) in e.data" :key="j" class="sev__kv">
                    <span class="sev__k">{{ d.name }}</span>
                    <span class="sev__v" :title="d.value.length > 80 ? d.value : undefined">{{ d.value === '' ? '“”' : d.value }}</span>
                  </span>
                  <span v-if="!e.data.length" class="sev__none">no data</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="filtered.length > limit" class="sev__more">
            <span class="muted">Showing {{ limit.toLocaleString() }} of {{ filtered.length.toLocaleString() }}</span>
            <BButton size="sm" @click="limit += PAGE">Show {{ Math.min(PAGE, filtered.length - limit).toLocaleString() }} more</BButton>
          </div>
        </template>

        <!-- By event: one section per kind of event, its data as columns -->
        <ul v-else class="sev__groups" aria-label="Events by name">
          <li v-for="g in groups" :key="g.name" class="sev__group" :class="{ 'is-open': openGroups.has(g.name) }">
            <button
              type="button"
              class="sev__ghead"
              :aria-expanded="openGroups.has(g.name) ? 'true' : 'false'"
              :aria-controls="groupId(g.name)"
              @click="toggleGroup(g.name)"
            >
              <ChevronRight class="sev__chev" :size="16" aria-hidden="true" />
              <span class="sev__gname">{{ g.name }}</span>
              <BBadge>{{ g.events.length.toLocaleString() }}</BBadge>
              <span class="sev__gtime">{{ groupTime(g) }}</span>
              <span v-if="g.keys.length" class="sev__gkeys">{{ g.keys.join(' · ') }}</span>
            </button>
            <div v-if="openGroups.has(g.name)" :id="groupId(g.name)" class="sev__gbody">
              <div class="sev__gtools">
                <BButton size="sm" variant="ghost" :icon="ListFilter" @click="showInTimeline(g.name)">Show in timeline</BButton>
                <BButton size="sm" :icon="Sheet" title="One row per event, one column per data name" @click="exportGroup(g)">Export as table</BButton>
              </div>
              <div class="sev__gscroll">
                <table class="sev__table is-wide">
                  <caption class="sr-only">“{{ g.name }}” events, one column per data name</caption>
                  <thead>
                    <tr>
                      <th scope="col" class="sev__col-time">Time</th>
                      <th v-for="k in g.keys" :key="k" scope="col">{{ k }}</th>
                      <th v-if="!g.keys.length" scope="col">Data</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(e, i) in g.events.slice(0, groupLimit(g.name))" :key="e.id ?? `n${i}`">
                      <td class="sev__time tabular" :title="timeTitle(e)">
                        <span v-if="multiDay" class="sev__day">{{ formatDay(e.time) }}</span>{{ formatClock(e.time) }}
                      </td>
                      <td v-for="k in g.keys" :key="k" class="sev__cell">{{ valueOf(e, k) }}</td>
                      <td v-if="!g.keys.length" class="sev__none">no data</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="g.events.length > groupLimit(g.name)" class="sev__more">
                <span class="muted">Showing {{ groupLimit(g.name).toLocaleString() }} of {{ g.events.length.toLocaleString() }}</span>
                <BButton size="sm" @click="moreInGroup(g.name)">Show more</BButton>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  ArrowDownUp, ChevronRight, CircleAlert, Download, ListFilter, RotateCw, ScrollText, Search, SearchX, Sheet,
} from 'lucide-vue-next'
import { BBadge, BButton, BDialog, BEmpty, BInput, BSelect, BSpinner, BTabs, notify, type TabItem } from '@/ui'
import { saveText } from '@/lib/http'
import { admin } from '@/store/admin'
import type { Instance } from '@/store/types'
import {
  eventNameCounts, eventsToCsv, eventsToWideCsv, filterEvents, formatClock, formatDateTime, formatDay, formatSpan,
  groupEvents, mergeEvents, normalizeEvent, safeFileName, sameDay, sortEvents, type EventGroup, type SessionEvent,
} from './sessionData'
import { loadSessionEvents } from './eventsLoader'

const props = defineProps<{ open: boolean, session: Instance | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean], download: [session: Instance] }>()

const PAGE = 300
const loaded = ref<SessionEvent[]>([])
const loading = ref(false)
const failed = ref(false)
const query = ref('')
const nameFilter = ref<string | null>(null)
const view = ref<'timeline' | 'groups'>('timeline')
const newestFirst = ref(false)
const limit = ref(PAGE)
const openGroups = reactive(new Set<string>())
const groupLimits = reactive<Record<string, number>>({})
let requestNo = 0

/** The current session keeps recording while the drawer is open: its pushed events join in. */
const isLive = computed(() => !!props.session && admin.instance?.id === props.session.id &&
  (admin.instance.status === 'RUNNING' || admin.instance.status === 'TESTING'))
const live = computed(() => props.session && admin.instance?.id === props.session.id ? admin.events.map(normalizeEvent) : [])
const events = computed(() => sortEvents(mergeEvents(loaded.value, live.value)))

const isFiltered = computed(() => !!query.value.trim() || !!nameFilter.value)
const filtered = computed(() => filterEvents(events.value, { query: query.value, name: nameFilter.value }))
const shownTimeline = computed(() => {
  const list = newestFirst.value ? [...filtered.value].reverse() : filtered.value
  return list.slice(0, limit.value)
})
const groups = computed(() => groupEvents(filtered.value))
const multiDay = computed(() => {
  const list = events.value
  return list.length > 1 && !sameDay(list[0].time, list[list.length - 1].time)
})

const nameOptions = computed(() => [
  { value: null, label: `All events (${events.value.length.toLocaleString()})` },
  ...eventNameCounts(events.value).map(n => ({ value: n.name, label: `${n.name} (${n.count.toLocaleString()})` })),
])
const viewTabs = computed<TabItem[]>(() => [
  { value: 'timeline', label: 'Timeline' },
  { value: 'groups', label: 'By event', count: groups.value.length || null },
])

const subtitle = computed(() => {
  const list = events.value
  if (!list.length) return props.session ? `Session #${props.session.id}` : ''
  const first = list[0].time
  const last = list[list.length - 1].time
  const span = formatSpan(first, last)
  return `${formatDateTime(first)}${span ? ` · over ${span}` : ''}`
})

async function load () {
  const s = props.session
  if (!s) return
  const mine = ++requestNo
  loading.value = true
  failed.value = false
  const raw = await loadSessionEvents(s.id)
  if (mine !== requestNo) return // another session was opened meanwhile
  loading.value = false
  if (raw) loaded.value = raw.map(normalizeEvent)
  else failed.value = true
}

function reset () {
  loaded.value = []
  failed.value = false
  query.value = ''
  nameFilter.value = null
  limit.value = PAGE
  openGroups.clear()
  for (const k of Object.keys(groupLimits)) delete groupLimits[k]
}

watch(() => [props.open, props.session?.id] as const, ([open, id], old) => {
  if (!open || id == null) return
  const sameSession = old && old[1] === id && old[0]
  if (sameSession) return
  reset()
  newestFirst.value = isLive.value
  view.value = 'timeline'
  load()
}, { immediate: true })

// a narrower search starts from the top again
const announcement = ref('')
let announceTimer: ReturnType<typeof setTimeout> | undefined
watch([query, nameFilter], () => {
  limit.value = PAGE
  clearTimeout(announceTimer)
  announceTimer = setTimeout(() => {
    const n = filtered.value.length
    announcement.value = isFiltered.value ? `${n} ${n === 1 ? 'event matches' : 'events match'}` : `Showing all ${n} events`
  }, 500)
})
onBeforeUnmount(() => clearTimeout(announceTimer))

function clearFilters () {
  query.value = ''
  nameFilter.value = null
}
function showInTimeline (name: string) {
  nameFilter.value = name
  view.value = 'timeline'
}
function toggleGroup (name: string) {
  if (openGroups.has(name)) openGroups.delete(name)
  else openGroups.add(name)
}
const groupLimit = (name: string) => groupLimits[name] || 100
function moreInGroup (name: string) { groupLimits[name] = groupLimit(name) + PAGE }
const groupId = (name: string) => `sev-group-${encodeURIComponent(name).replace(/[^a-zA-Z0-9_-]/g, '_')}`

function groupTime (g: EventGroup) {
  if (!g.first) return ''
  if (!g.last || g.last.getTime() === g.first.getTime()) return formatClock(g.first)
  return `${formatClock(g.first)} – ${formatClock(g.last)}`
}
function timeTitle (e: SessionEvent) {
  return e.time ? formatDateTime(e.time, { seconds: true }) : `Unreadable time: ${e.raw || 'none'}`
}
function valueOf (e: SessionEvent, key: string) {
  const d = e.data.find(x => x.name === key)
  return d ? d.value : ''
}

function baseName () {
  const s = props.session
  return s ? `${safeFileName(s.name)}_${s.id}` : 'session'
}
function exportView () {
  const list = filtered.value
  if (!list.length) return
  const file = `${baseName()}_events${isFiltered.value ? '_filtered' : ''}.csv`
  saveText(file, eventsToCsv(list))
  notify.success(`Saved ${file}`, `${list.length.toLocaleString()} ${list.length === 1 ? 'event' : 'events'}, one row per data item. Times are in UTC.`)
}
function exportGroup (g: EventGroup) {
  const file = `${baseName()}_${safeFileName(g.name, 'event')}.csv`
  saveText(file, eventsToWideCsv(g.events, g.keys))
  notify.success(`Saved ${file}`, `${g.events.length.toLocaleString()} “${g.name}” events, one column per data name.`)
}
</script>

<style>
.sev { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.sev__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
}
.sev__search { flex: 1 1 220px; }
.sev__select { flex: 0 1 220px; }
.sev__bar-end { display: flex; align-items: center; gap: 4px; margin-left: auto; }
.sev__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 6px 20px;
  font-size: var(--text-sm);
  color: var(--muted);
  border-bottom: 1px solid var(--border);
  background: var(--surface-sunken);
}
.sev__live { margin-left: auto; }
.sev__link { border: 0; background: none; padding: 0; color: var(--accent); font-weight: 550; cursor: pointer; font-size: inherit; }
.sev__link:hover { text-decoration: underline; }
.sev__scroll { flex: 1; min-height: 0; overflow: auto; }
.sev__state { display: flex; align-items: center; justify-content: center; gap: 10px; padding: 64px 20px; color: var(--muted); }

.sev__table { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.sev__table th {
  position: sticky;
  top: 0;
  z-index: 1;
  padding: 8px 12px;
  text-align: left;
  font-size: var(--text-xs);
  font-weight: 600;
  color: var(--muted);
  background: var(--surface-raised);
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.sev__table td { padding: 8px 12px; border-bottom: 1px solid var(--border); vertical-align: top; }
.sev__table th:first-child, .sev__table td:first-child { padding-left: 20px; }
.sev__table th:last-child, .sev__table td:last-child { padding-right: 20px; }
.sev__table tbody tr:hover { background: var(--surface-hover); }
.sev__col-time { width: 1%; }
.sev__col-name { width: 1%; }
.sev__time { white-space: nowrap; font-family: var(--font-mono); font-size: 12px; color: var(--text-soft); }
.sev__day { color: var(--muted); margin-right: 6px; font-family: var(--font); }
.sev__name {
  max-width: 220px;
  padding: 1px 7px;
  border: 1px solid var(--border);
  border-radius: var(--radius-xs);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 550;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
}
.sev__name:hover { border-color: var(--accent); color: var(--accent); }
.sev__data { min-width: 180px; }
.sev__kv {
  display: inline-flex;
  align-items: baseline;
  max-width: 100%;
  gap: 6px;
  margin: 0 14px 2px 0;
}
.sev__k { color: var(--muted); white-space: nowrap; }
.sev__v {
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12px;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.sev__none { color: var(--faint); font-style: italic; }
.sev__cell { font-family: var(--font-mono); font-size: 12px; max-width: 280px; overflow-wrap: anywhere; }
.sev__more { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 14px 20px 20px; font-size: var(--text-sm); }

.sev__groups { list-style: none; margin: 0; padding: 8px 0 24px; }
.sev__group { border-bottom: 1px solid var(--border); }
.sev__ghead {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 10px;
  width: 100%;
  padding: 11px 20px;
  border: 0;
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.sev__ghead:hover { background: var(--surface-hover); }
.sev__chev { color: var(--muted); transition: transform var(--duration) var(--ease); flex: none; }
.sev__group.is-open .sev__chev { transform: rotate(90deg); }
.sev__gname { font-family: var(--font-mono); font-size: var(--text-sm); font-weight: 600; }
.sev__gtime { font-size: var(--text-xs); color: var(--muted); font-variant-numeric: tabular-nums; }
.sev__gkeys {
  flex-basis: 100%;
  padding-left: 26px;
  font-size: var(--text-xs);
  color: var(--faint);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sev__gbody { padding: 0 0 8px; background: var(--surface-sunken); border-top: 1px solid var(--border); }
.sev__gtools { display: flex; gap: 6px; justify-content: flex-end; padding: 8px 20px; }
.sev__gscroll { overflow-x: auto; }
.sev__table.is-wide th { position: static; background: var(--surface-sunken); }

@media (max-width: 640px) {
  .sev__bar, .sev__meta { padding-left: 16px; padding-right: 16px; }
  .sev__bar-end { margin-left: 0; }
  .sev__select { flex: 1 1 160px; }
  .sev__table th:first-child, .sev__table td:first-child { padding-left: 16px; }
}
</style>
