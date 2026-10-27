<template>
  <div class="live-none">
    <section class="live-none__card">
      <BEmpty
        :icon="first ? Rocket : CirclePause"
        :title="first ? 'Run your first session' : 'No session is open'"
        :text="first
          ? 'A session is one run of this experiment: people join with a link, you start the game when everyone is here, and everything they do is recorded.'
          : 'Open a new session to let people in, or resume one you stopped.'"
      >
        <template #actions>
          <BButton variant="primary" :icon="Plus" @click="emit('new')">New session</BButton>
        </template>
      </BEmpty>

      <ol v-if="first" class="live-none__steps" aria-label="How a session runs">
        <li><span class="live-none__num" aria-hidden="true">1</span><span><b>Open a session.</b> Pick its launch parameters.</span></li>
        <li><span class="live-none__num" aria-hidden="true">2</span><span><b>Share its link.</b> Watch people arrive here.</span></li>
        <li><span class="live-none__num" aria-hidden="true">3</span><span><b>Start the game.</b> Everything is recorded for you.</span></li>
      </ol>

      <p v-if="autoOn" class="live-none__auto is-on">
        <Bot :size="16" aria-hidden="true" />
        <span>Autopilot runs this experiment and opens a session by itself{{ autopilot?.continuous ? ', again after each one finishes' : '' }}.</span>
        <RouterLink to="/autopilot">Autopilot settings</RouterLink>
      </p>
      <p v-else class="live-none__auto">
        <RouterLink to="/autopilot" class="live-none__auto-link">
          <Bot :size="16" aria-hidden="true" />
          or let Breadboard run sessions by itself
          <ArrowRight :size="14" aria-hidden="true" />
        </RouterLink>
      </p>
    </section>

    <BPanel v-if="recent.length" flush title="Recent sessions" subtitle="Resume a stopped session, or get the data of a finished one.">
      <template #actions>
        <BButton size="sm" variant="ghost" :icon-right="ArrowRight" href="#/sessions">All sessions & data</BButton>
      </template>
      <ul class="live-recent">
        <li v-for="s in recent" :key="s.id" class="live-recent__row">
          <div class="live-recent__main">
            <span class="live-recent__name">{{ s.name }}</span>
            <span class="live-recent__meta">#{{ s.id }} · created {{ formatWhen(parseServerTime(s.creationTime)) }}</span>
          </div>
          <BBadge :tone="tone(s.status)" dot>{{ label(s.status) }}</BBadge>
          <div class="live-recent__actions">
            <BButton
              v-if="s.status === 'STOPPED' || s.status === 'RUNNING'"
              size="sm"
              :icon="Play"
              :loading="busy.resume === s.id"
              :disabled="busy.resume != null && busy.resume !== s.id"
              :aria-label="`Resume ${s.name}`"
              @click="resumeSession(s)"
            >Resume</BButton>
            <BButton
              v-else-if="s.status === 'FINISHED'"
              size="sm"
              variant="ghost"
              :icon="Download"
              :loading="busy.download === s.id"
              :aria-label="`Download the data of ${s.name}`"
              @click="downloadData(s)"
            >Data</BButton>
          </div>
        </li>
      </ul>
    </BPanel>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, Bot, CirclePause, Download, Play, Plus, Rocket } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BPanel } from '@/ui'
import { admin, instances } from '@/store/admin'
import type { InstanceStatus } from '@/store/types'
import { busy, downloadData, resumeSession } from './controls'
import { parseServerTime } from './events'
import { formatWhen } from './live'
import { autopilotRuns, type AutopilotStatus } from './autopilot'

const props = defineProps<{ autopilot: AutopilotStatus | null }>()
const emit = defineEmits<{ new: [] }>()

const first = computed(() => !instances.value.some(i => i.id > 0))
const recent = computed(() => instances.value.filter(i => i.id > 0).slice(0, 5))
const autoOn = computed(() => autopilotRuns(props.autopilot, admin.experiment?.id))

function tone (s: InstanceStatus) {
  return s === 'RUNNING' ? 'success' : s === 'STOPPED' ? 'warning' : 'neutral'
}
function label (s: InstanceStatus) {
  return { RUNNING: 'Running', TESTING: 'Testing', STOPPED: 'Stopped', FINISHED: 'Finished', ARCHIVED: 'Archived' }[s] || s
}
</script>

<style>
.live-none { display: flex; flex-direction: column; gap: 20px; }
.live-none__card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 24px 28px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.live-none__steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  width: 100%;
  max-width: 760px;
  margin: 0 0 22px;
  padding: 0;
  list-style: none;
}
.live-none__steps li {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-sunken);
  font-size: var(--text-sm);
  color: var(--muted);
}
.live-none__steps b { color: var(--text); font-weight: 600; }
.live-none__num {
  display: grid;
  place-items: center;
  flex: none;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--accent-soft);
  color: var(--accent-soft-text);
  font-size: var(--text-xs);
  font-weight: 700;
}
.live-none__auto { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 8px; font-size: var(--text-sm); color: var(--muted); text-align: center; }
.live-none__auto.is-on { color: var(--text-soft); }
.live-none__auto.is-on svg { color: var(--accent); }
.live-none__auto-link { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-weight: 550; }
.live-none__auto-link:hover { color: var(--accent); }
.live-recent { list-style: none; margin: 0; padding: 0; }
.live-recent__row { display: flex; align-items: center; gap: 14px; padding: 11px 18px; border-bottom: 1px solid var(--border); }
.live-recent__row:last-child { border-bottom: 0; }
.live-recent__main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.live-recent__name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.live-recent__meta { font-size: var(--text-xs); color: var(--muted); }
.live-recent__actions { flex: none; width: 104px; display: flex; justify-content: flex-end; }
@media (max-width: 640px) {
  .live-none__steps { grid-template-columns: minmax(0, 1fr); }
  .live-none__card { padding: 4px 16px 22px; }
  .live-recent__row { flex-wrap: wrap; padding: 11px 14px; }
  .live-recent__main { flex-basis: 100%; }
  .live-recent__actions { width: auto; margin-left: auto; }
}
</style>
