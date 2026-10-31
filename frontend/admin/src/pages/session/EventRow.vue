<template>
  <span class="live-evr">
    <time class="live-evr__time tabular" :datetime="event.at ? new Date(event.at).toISOString() : undefined" :title="event.at ? new Date(event.at).toLocaleString() : 'Time unknown'">
      {{ clock.time }}<span v-if="clock.date" class="live-evr__date">{{ clock.date }}</span>
    </time>
    <span class="live-evr__dot" :class="`is-${tone}`" aria-hidden="true" />
    <span class="live-evr__what">
      <span class="live-evr__name">{{ event.name }}</span>
      <span v-if="plain" class="live-evr__plain" :title="plain">{{ plain }}</span>
    </span>
    <span v-if="summary.shown.length" class="live-evr__data">
      <span v-for="[k, v] in summary.shown" :key="k" class="live-evr__chip"><span class="live-evr__k">{{ k }}</span> <span class="live-evr__v">{{ v }}</span></span>
      <span v-if="summary.more" class="live-evr__more">+{{ summary.more }} more</span>
    </span>
    <ChevronRight v-if="expandable" class="live-evr__chev" :size="15" aria-hidden="true" />
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight } from 'lucide-vue-next'
import { clockParts, describeEvent, eventTone, summarizeData, type FeedEvent } from './events'

const props = defineProps<{ event: FeedEvent, expandable?: boolean }>()
const tone = computed(() => eventTone(props.event.name))
const clock = computed(() => clockParts(props.event.at))
const plain = computed(() => describeEvent(props.event.name, props.event.data))
const summary = computed(() => summarizeData(props.event.data, 3))
</script>

<style>
.live-evr {
  display: grid;
  grid-template-columns: 84px 8px minmax(120px, 0.9fr) minmax(0, 1.4fr) 15px;
  align-items: center;
  gap: 4px 12px;
  font-size: var(--text-sm);
  min-width: 0;
}
.live-evr__time { display: flex; flex-direction: column; color: var(--faint); font-size: var(--text-xs); white-space: nowrap; line-height: 1.35; }
.live-evr__date { font-size: 11px; }
.live-evr__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.live-evr__dot.is-success { background: var(--success); }
.live-evr__dot.is-warning { background: var(--warning); }
.live-evr__dot.is-danger { background: var(--danger); }
.live-evr__dot.is-accent { background: var(--accent); }
.live-evr__what { display: flex; flex-direction: column; min-width: 0; }
.live-evr__name { font-family: var(--font-mono); font-size: var(--text-xs); font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* (on up to two lines: what it means is the point of it, "A researcher’s preview signed in as AI 6") */
.live-evr__plain { font-size: var(--text-xs); color: var(--muted); overflow: hidden; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-clamp: 2; overflow-wrap: anywhere; }
.live-evr__data { display: flex; flex-wrap: wrap; gap: 4px; min-width: 0; }
.live-evr__chip {
  display: inline-flex;
  gap: 5px;
  max-width: 100%;
  padding: 1px 7px;
  border-radius: var(--radius-xs);
  background: var(--bg-subtle);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 19px;
  overflow: hidden;
}
.live-evr__k { color: var(--muted); flex: none; }
.live-evr__v { color: var(--text-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.live-evr__more { font-size: 11.5px; line-height: 21px; color: var(--faint); }
.live-evr__chev { grid-column: 5; color: var(--faint); transition: transform var(--duration) var(--ease); }
details[open] > summary .live-evr__chev { transform: rotate(90deg); }
@media (max-width: 640px) {
  .live-evr { grid-template-columns: 78px 8px minmax(0, 1fr) 15px; gap: 4px 10px; }
  .live-evr__data { grid-column: 3 / -1; grid-row: 2; }
  .live-evr__chev { grid-column: 4; grid-row: 1; }
}
</style>
