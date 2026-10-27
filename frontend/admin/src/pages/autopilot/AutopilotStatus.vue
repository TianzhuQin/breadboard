<template>
  <section class="apst" :class="{ 'is-on': status.on }" aria-labelledby="apst-title">
    <header class="apst__head">
      <span class="apst__icon" aria-hidden="true"><Bot :size="20" /></span>
      <div class="apst__titles" aria-live="polite">
        <h2 id="apst-title" class="apst__title">
          {{ status.on ? 'The autopilot is on' : 'The autopilot is off' }}
        </h2>
        <p class="apst__sub">
          <template v-if="status.on && expName">Running “{{ expName }}” unattended.</template>
          <template v-else-if="status.on">Running an experiment unattended.</template>
          <template v-else>Nothing opens sessions by itself. Turn it on below to run an experiment unattended.</template>
        </p>
      </div>
      <div class="apst__badges">
        <BBadge v-if="source === 'config'" tone="info"><Lock :size="12" aria-hidden="true" /> application.conf</BBadge>
        <BBadge :tone="status.on ? 'success' : 'neutral'" :dot="true" :pulse="status.on">{{ status.on ? 'On' : 'Off' }}</BBadge>
      </div>
    </header>

    <div v-if="status.on || status.instance" class="apst__block" aria-live="polite">
      <h3 class="apst__label">Open session</h3>
      <div v-if="status.instance" class="apst__session">
        <span class="apst__session-name" :title="`Session #${status.instance.id}`">{{ status.instance.name || `Session #${status.instance.id}` }}</span>
        <BBadge :tone="sessionTone" :dot="sessionTone === 'success'">{{ sessionLabel }}</BBadge>
        <RouterLink v-if="isCurrent" to="/session" class="apst__watch">Watch it live <ArrowRight :size="14" aria-hidden="true" /></RouterLink>
      </div>
      <p v-else class="apst__muted">
        No session is open right now. {{ status.on ? 'The autopilot opens one shortly.' : '' }}
      </p>
      <p v-if="liveText" class="apst__live">
        <component :is="live?.state === 'cancelled' ? CircleX : (live?.state === 'starting' ? Timer : Hourglass)" :size="15" aria-hidden="true" />
        {{ liveText }}
      </p>
    </div>

    <div v-if="links && (status.on || status.instance)" class="apst__block">
      <h3 class="apst__label">Participant links</h3>
      <p class="apst__muted">They always lead to the session that is open at the moment, so the same links keep working from one session to the next.</p>
      <div class="apst__links">
        <CopyLink label="Sign-in link" :url="links.login" hint="Participants enter an ID, then join." />
        <CopyLink
          v-if="links.prolific"
          label="Prolific link"
          :url="links.prolific"
          hint="Paste it into Prolific as the study URL; Prolific fills in the {{%…%}} parts."
        />
      </div>
    </div>

    <div v-if="activity.length" class="apst__block">
      <h3 class="apst__label">Recent activity in this session</h3>
      <ul class="apst__activity">
        <li v-for="a in activity" :key="a.key">
          <span class="apst__dot" :class="`is-${a.tone}`" aria-hidden="true" />
          <time class="apst__time tabular" :datetime="a.at">{{ timeOf(a.at) }}</time>
          <span>{{ a.text }}</span>
        </li>
      </ul>
    </div>

    <footer class="apst__foot">
      <span v-if="error" class="apst__err" role="status"><TriangleAlert :size="14" aria-hidden="true" /> Couldn't refresh: {{ error }}</span>
      <span v-else class="apst__muted">Updates every few seconds.</span>
      <BButton size="sm" variant="ghost" :icon="RefreshCw" :loading="refreshing" @click="emit('refresh')">Refresh</BButton>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, Bot, CircleX, Hourglass, Lock, RefreshCw, Timer, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton } from '@/ui'
import CopyLink from './CopyLink.vue'
import type { AutopilotState, WaitingRoomLive } from './autopilot'

const props = defineProps<{
  state: AutopilotState
  links: { login: string, prolific: string } | null
  live: WaitingRoomLive | null
  activity: { key: string, at: string, text: string, tone: string }[]
  /** the console's own current session is the autopilot's */
  isCurrent: boolean
  refreshing?: boolean
  error?: string
  experimentName?: string | null
}>()
const emit = defineEmits<{ refresh: [] }>()

const status = computed(() => props.state.status)
const source = computed(() => props.state.source)
const expName = computed(() => status.value.experimentName || props.experimentName || null)

const sessionLabel = computed(() => {
  const s = status.value.instance?.status || ''
  return ({ RUNNING: 'Running', TESTING: 'Testing', FINISHED: 'Finished', STOPPED: 'Stopped', ARCHIVED: 'Archived' } as Record<string, string>)[s] || s || 'Unknown'
})
const sessionTone = computed(() => {
  const s = status.value.instance?.status
  if (s === 'RUNNING' || s === 'TESTING') return 'success'
  if (s === 'STOPPED') return 'warning'
  return 'neutral'
})

const liveText = computed(() => {
  const l = props.live
  if (!l) return ''
  if (l.state === 'starting') return `Starting: ${l.seconds ?? 'a few'} seconds to go with ${l.here} ${l.here === 1 ? 'person' : 'people'}.`
  if (l.state === 'cancelled') return 'Called off: not enough people joined.'
  const need = l.min != null ? `, the game starts at ${l.min}` : ''
  const cap = l.max != null ? ` (room for ${l.max})` : ''
  return `Waiting room: ${l.here} ${l.here === 1 ? 'person' : 'people'} here${need}${cap}.`
})

function timeOf (iso: string) {
  const d = new Date(iso)
  return isNaN(d.getTime()) ? '' : d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
</script>

<style>
.apst {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}
.apst__head { display: flex; align-items: flex-start; gap: 14px; padding: 18px 20px; flex-wrap: wrap; }
.apst__icon { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 12px; background: var(--bg-subtle); color: var(--muted); flex: none; }
.apst.is-on .apst__icon { background: var(--success-soft); color: var(--success); }
.apst__titles { flex: 1; min-width: 200px; }
.apst__title { font-size: var(--text-lg); font-weight: 650; }
.apst__sub { margin-top: 2px; color: var(--muted); font-size: var(--text-sm); overflow-wrap: anywhere; }
.apst__badges { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.apst__badges .b-badge svg { margin-left: -2px; }
.apst__block { display: flex; flex-direction: column; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--border); }
.apst__label { font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.apst__muted { font-size: var(--text-sm); color: var(--muted); }
.apst__session { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.apst__session-name { font-weight: 600; overflow-wrap: anywhere; }
.apst__watch { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-size: var(--text-sm); font-weight: 600; }
.apst__live { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--accent-soft-text); font-size: var(--text-sm); }
.apst__links { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 4px; }
@media (max-width: 760px) { .apst__links { grid-template-columns: minmax(0, 1fr); } }
.apst__activity { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.apst__activity li { display: flex; align-items: center; gap: 10px; font-size: var(--text-sm); }
.apst__time { color: var(--faint); font-size: var(--text-xs); min-width: 64px; }
.apst__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--faint); flex: none; }
.apst__dot.is-success { background: var(--success); }
.apst__dot.is-warning { background: var(--warning); }
.apst__dot.is-info { background: var(--info); }
.apst__foot { display: flex; align-items: center; gap: 10px; padding: 8px 12px 8px 20px; border-top: 1px solid var(--border); background: var(--surface-sunken); }
.apst__foot > :first-child { flex: 1; min-width: 0; }
.apst__err { display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-sm); color: var(--warning-soft-text); }
</style>
