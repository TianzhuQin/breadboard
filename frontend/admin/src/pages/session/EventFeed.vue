<template>
  <BPanel flush class="live-feed">
    <template #header>
      <div class="live-feed__titles">
        <h3 class="b-panel__title live-feed__title">
          Events
          <BBadge v-if="live" tone="success" dot pulse>Live</BBadge>
        </h3>
        <p class="b-panel__subtitle">
          {{ events.length ? `${events.length.toLocaleString()} recorded${events.length >= FEED_CAP ? ' (latest ' + FEED_CAP + ')' : ''} · newest first` : 'What the session records, as it happens' }}
        </p>
      </div>
    </template>
    <template v-if="names.length > 1" #actions>
      <BSelect v-model="filter" size="sm" :options="filterOptions" aria-label="Show only events named" class="live-feed__filter" />
    </template>

    <BEmpty
      v-if="!events.length"
      compact
      :icon="Activity"
      title="No events yet"
      :text="live ? 'Events appear here as the session records them: people joining, steps starting, choices being made.' : 'This session recorded no events.'"
    />
    <BEmpty v-else-if="!filtered.length" compact :icon="ListFilter" :title="`No “${filter}” events`" text="Choose another name, or show all events.">
      <template #actions><BButton size="sm" @click="filter = null">Show all events</BButton></template>
    </BEmpty>

    <ol v-else class="live-feed__list" :aria-label="filter ? `Events named ${filter}, newest first` : 'Events, newest first'">
      <li v-for="e in shown" :key="e.key" class="live-ev" :class="{ 'is-fresh': fresh.has(e.key) }">
        <details v-if="hasData(e)" class="live-ev__details">
          <summary class="live-ev__row">
            <EventRow :event="e" expandable />
          </summary>
          <dl class="live-ev__data">
            <template v-for="(v, k) in e.data" :key="k">
              <dt>{{ k }}</dt>
              <dd>{{ formatDataValue(String(k), v, 4000) }}</dd>
            </template>
          </dl>
        </details>
        <div v-else class="live-ev__row">
          <EventRow :event="e" />
        </div>
      </li>
    </ol>

    <footer v-if="filtered.length > shown.length" class="live-feed__foot">
      <span class="muted">Showing {{ shown.length }} of {{ filtered.length.toLocaleString() }}</span>
      <BButton size="sm" variant="ghost" :icon="ChevronDown" @click="limit += 100">Show more</BButton>
    </footer>
  </BPanel>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { Activity, ChevronDown, ListFilter } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BPanel, BSelect } from '@/ui'
import EventRow from './EventRow.vue'
import { FEED_CAP, eventNameCounts, filterEvents, formatDataValue, type FeedEvent } from './events'

const props = defineProps<{
  /** Newest first. */
  events: FeedEvent[]
  /** The session is running, so new events keep arriving. */
  live: boolean
  /** Changes when another session is shown, so its events don't all flash as new. */
  sessionKey: string | number | null
}>()

const filter = ref<string | null>(null)
const limit = ref(50)
const names = computed(() => eventNameCounts(props.events))
const filterOptions = computed(() => [
  { value: null, label: `All events (${props.events.length.toLocaleString()})` },
  ...names.value.map(n => ({ value: n.name, label: `${n.name} (${n.count.toLocaleString()})` })),
])
const filtered = computed(() => filterEvents(props.events, filter.value))
const shown = computed(() => filtered.value.slice(0, limit.value))

// a name that is gone (another session) would leave an empty list behind
watch(names, (list) => { if (filter.value && !list.some(n => n.name === filter.value)) filter.value = null })
watch(filter, () => { limit.value = 50 })

function hasData (e: FeedEvent) {
  return Object.keys(e.data).length > 0
}

// Briefly mark events that arrive while watching; not the ones already there when the page opened.
const known = new Set<string>()
const fresh = reactive(new Set<string>())
const timers: ReturnType<typeof setTimeout>[] = []
let primed = false
watch(() => props.sessionKey, () => { known.clear(); primed = false })
watch(() => props.events, (list) => {
  const added: string[] = []
  for (const e of list) if (!known.has(e.key)) { known.add(e.key); added.push(e.key) }
  // the first batch, or a big one (a reload), is history, not news
  if (!primed || added.length > 20) { primed = true; return }
  for (const k of added) fresh.add(k)
  if (added.length) timers.push(setTimeout(() => { for (const k of added) fresh.delete(k) }, 1600))
}, { immediate: true })
onBeforeUnmount(() => timers.forEach(clearTimeout))
</script>

<style>
.live-feed { display: flex; flex-direction: column; min-height: 0; }
.live-feed .b-panel__head { flex-wrap: wrap; }
.live-feed__titles { flex: 1; min-width: 0; }
.live-feed__title { display: flex; align-items: center; gap: 10px; }
.live-feed__filter { width: 220px; max-width: 100%; }
.live-feed__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  max-height: 640px;
  overflow: auto;
}
.live-feed__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px 8px 18px;
  border-top: 1px solid var(--border);
  font-size: var(--text-xs);
}
.live-ev { border-bottom: 1px solid var(--border); }
.live-ev:last-child { border-bottom: 0; }
.live-ev.is-fresh { animation: live-ev-in 1.6s var(--ease); }
@keyframes live-ev-in { from { background: var(--accent-soft); } to { background: transparent; } }
.live-ev__row { display: block; padding: 9px 18px; list-style: none; }
.live-ev__details > summary { cursor: pointer; }
.live-ev__details > summary::-webkit-details-marker { display: none; }
.live-ev__details > summary:hover { background: var(--surface-hover); }
.live-ev__details > summary:focus-visible { outline-offset: -2px; }
.live-ev__data {
  display: grid;
  grid-template-columns: minmax(90px, max-content) minmax(0, 1fr);
  gap: 4px 14px;
  margin: 0 18px 12px 134px;
  padding: 10px 12px;
  background: var(--surface-sunken);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
}
.live-ev__data dt { color: var(--muted); font-family: var(--font-mono); overflow-wrap: anywhere; }
.live-ev__data dd { margin: 0; color: var(--text); font-family: var(--font-mono); white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 640px) {
  .live-ev__row { padding: 9px 14px; }
  .live-ev__data { margin: 0 14px 12px; }
  .live-feed__filter { width: 100%; }
  /* one scroll area on a phone: the page's */
  .live-feed__list { max-height: none; }
}
</style>
