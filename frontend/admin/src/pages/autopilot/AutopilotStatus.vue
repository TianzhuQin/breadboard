<template>
  <section class="apst" :class="{ 'is-on': status.on }" aria-labelledby="apst-title">
    <header class="apst__head">
      <span class="apst__icon" aria-hidden="true"><Bot :size="20" /></span>
      <div class="apst__titles" aria-live="polite">
        <h2 id="apst-title" class="apst__title">
          {{ halt ? `The autopilot is ${halt}` : status.on ? 'The autopilot is on' : stalled ? 'The autopilot can’t run' : 'The autopilot is off' }}
        </h2>
        <!-- The way out of a pause is the same button whoever is in charge (POST /autopilot/resume),
             so this never asks for a save: application.conf autopilots have nothing to save. -->
        <p class="apst__sub">
          <template v-if="halt === 'paused'">{{ pausedLine(status.problem) }}</template>
          <template v-else-if="halt === 'done'">
            Its one session{{ expName ? ` of “${expName}”` : '' }} has finished, so it opens no new one for now.
          </template>
          <template v-else-if="status.on && expName">Running “{{ expName }}” unattended.</template>
          <template v-else-if="status.on">Running an experiment unattended.</template>
          <template v-else-if="stalled && status.problem">It is switched on below, but no session opens by itself until the problem below is fixed.</template>
          <template v-else-if="stalled">It is switched on below, but the server isn’t running it. Refresh in a moment, or check the server log.</template>
          <template v-else>Nothing opens sessions by itself. Turn it on below to run an experiment unattended.</template>
        </p>
      </div>
      <!-- the way out of a pause, right where it is described -->
      <BButton v-if="halt && resumable" class="apst__resume" variant="primary" size="sm" :icon="Play" :loading="resuming" @click="emit('resume')">
        {{ halt === 'paused' ? 'Resume' : 'Open another session' }}
      </BButton>
      <div class="apst__badges">
        <BBadge v-if="source === 'config'" tone="info"><Lock :size="12" aria-hidden="true" /> settings file</BBadge>
        <BBadge :tone="badge.tone" :dot="true" :pulse="badge.pulse">{{ badge.label }}</BBadge>
      </div>
    </header>

    <div v-if="status.problem && kind === 'error'" class="apst__problem" role="alert">
      <TriangleAlert :size="16" aria-hidden="true" />
      <div>
        <p class="apst__problem-title">{{ status.on ? 'The autopilot has a problem' : 'Why it isn’t running' }}</p>
        <template v-if="problemError">
          <p v-if="problemError.lead" class="apst__problem-text">{{ problemError.lead }}</p>
          <EngineError class="apst__problem-err" :error="problemError.error" own-name="a step" />
        </template>
        <p v-else class="apst__problem-text">{{ status.problem }}</p>
      </div>
    </div>
    <div v-else-if="status.problem" class="apst__problem" :class="{ 'is-info': !failure }" role="status">
      <component :is="kind === 'waiting' ? Hourglass : kind === 'paused' ? CirclePause : Info" :size="16" aria-hidden="true" />
      <div>
        <p class="apst__problem-title">{{ infoTitle }}</p>
        <p class="apst__problem-text">{{ failure ? failure.text : infoText }}</p>
        <!-- paused by a mistake in a step: what went wrong, explained as on the Steps page, and the way there -->
        <template v-if="failure">
          <EngineError class="apst__problem-err" :error="failure.message" :own-name="failure.step" />
          <!-- (a step of the autopilot's experiment, which needn't be the one this console works on: named then,
               and the Steps page asks to switch to it) -->
          <RouterLink class="apst__open-step" :to="stepAt(failure.step, failure.line, status.experimentId)">
            Open {{ failure.step }}{{ elsewhere && expName ? ` of “${expName}”` : '' }}{{ failure.line ? ` at line ${failure.line}` : '' }}
            <ArrowRight :size="14" aria-hidden="true" />
          </RouterLink>
        </template>
      </div>
    </div>

    <!-- a session it ended for running too long: kept until the settings are saved or Resume is pressed -->
    <div v-if="status.notice" class="apst__problem apst__notice" role="status">
      <TimerOff :size="16" aria-hidden="true" />
      <div>
        <p class="apst__problem-title">A session ran out of time{{ noticeTime ? ` (at ${noticeTime})` : '' }}</p>
        <p class="apst__problem-text">{{ status.notice }}</p>
      </div>
    </div>

    <div v-if="status.on || status.instance" class="apst__block" aria-live="polite">
      <h3 class="apst__label">Open session</h3>
      <div v-if="status.instance" class="apst__session">
        <span class="apst__session-name" :title="`Session #${status.instance.id}`">{{ status.instance.name || `Session #${status.instance.id}` }}</span>
        <BBadge :tone="sessionTone" :dot="sessionTone === 'success'">{{ sessionLabel }}</BBadge>
        <RouterLink v-if="isCurrent" to="/session" class="apst__watch">Watch it live <ArrowRight :size="14" aria-hidden="true" /></RouterLink>
      </div>
      <p v-else class="apst__muted">
        No session is open right now. {{ status.on && !status.problem && !nextOpen ? 'The autopilot opens one shortly.' : '' }}
      </p>
      <p v-if="nextOpen" class="apst__muted">
        The next session opens at <time class="tabular" :datetime="status.nextOpenAt!">{{ nextOpen }}</time>.
      </p>
      <p v-if="liveText" class="apst__live">
        <component :is="live?.state === 'cancelled' ? CircleX : (live?.state === 'starting' ? Timer : Hourglass)" :size="15" aria-hidden="true" />
        {{ liveText }}
      </p>
    </div>

    <div v-if="links && (status.on || status.instance)" class="apst__block">
      <h3 class="apst__label">Participant links</h3>
      <p class="apst__muted">They always lead to the session that is open at the moment, so the same links keep working from one session to the next.</p>
      <LocalOnlyNote class="apst__local" what="The sign-in link uses" />
      <div class="apst__links">
        <CopyLink label="Sign-in link" :url="links.login" hint="Participants enter an ID, then join." />
        <div v-if="links.prolific" class="apst__link">
          <CopyLink
            label="Prolific link"
            :url="links.prolific"
            hint="Paste it into Prolific as the study URL; Prolific fills in the {{%…%}} parts."
          />
          <!-- Prolific's people come from the internet: no lab-network tip for this one -->
          <LocalOnlyNote what="This link uses" internet compact />
        </div>
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
import { ArrowRight, Bot, CirclePause, CircleX, Hourglass, Info, Lock, Play, RefreshCw, Timer, TimerOff, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton } from '@/ui'
import CopyLink from './CopyLink.vue'
import LocalOnlyNote from '../session/LocalOnlyNote.vue'
import EngineError from '../console/EngineError.vue'
import { describeEngineSession, haltOf, pauseFailure, pausedLine, problemKind, type AutopilotState, type WaitingRoomLive } from './autopilot'
import { stepAt } from '../steps/stepsModel'
import { admin } from '@/store/admin'

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
  /** Resume can be pressed now (no unsaved edits in the form below) */
  resumable?: boolean
  resuming?: boolean
}>()
const emit = defineEmits<{ refresh: [], resume: [] }>()

const status = computed(() => props.state.status)
const source = computed(() => props.state.source)
const expName = computed(() => status.value.experimentName || props.experimentName || null)
/** switched on in the settings, yet the server says it isn't running */
const stalled = computed(() => props.state.settings.enabled && !status.value.on)
/** A problem quoting an engine error ("... reported an error: Caught error: ..."), split for EngineError. */
const problemError = computed(() => {
  const p = props.state.status.problem || ''
  const i = p.indexOf('Caught error: ')
  return i < 0 ? null : { lead: p.slice(0, i).trim(), error: p.slice(i + 'Caught error: '.length) }
})

/** what status.problem is about: a fault, or the autopilot waiting by design */
const kind = computed(() => problemKind(props.state))
/** paused or done: it opens nothing until Resume */
const halt = computed(() => haltOf(props.state))
/** paused by a mistake in a step: which one, where, and what went wrong */
const failure = computed(() => kind.value === 'paused' ? pauseFailure(status.value.problem) : null)
/** the autopilot runs another experiment than the one this console works on */
const elsewhere = computed(() => status.value.experimentId != null && status.value.experimentId !== admin.experiment?.id)

const badge = computed<{ tone: 'success' | 'warning' | 'neutral', label: string, pulse: boolean }>(() => {
  if (halt.value === 'paused') return { tone: 'warning', label: 'Paused', pulse: false }
  if (halt.value === 'done') return { tone: 'neutral', label: 'Done', pulse: false }
  if (status.value.on) return { tone: 'success', label: 'On', pulse: true }
  return stalled.value ? { tone: 'warning', label: 'Not running', pulse: false } : { tone: 'neutral', label: 'Off', pulse: false }
})

const infoTitle = computed(() => {
  if (kind.value === 'waiting') {
    // without a live session to wait for, another researcher has their experiment loaded in the
    // engine; the server's message says who and what
    return status.value.engineSession ? 'Waiting for another session to end' : 'Waiting for the script engine'
  }
  return ({
    paused: 'Paused until you resume it',
    done: 'Its one session has finished',
  } as Record<string, string>)[kind.value || ''] || 'Note'
})
const infoText = computed(() => {
  const other = status.value.engineSession
  if (kind.value === 'waiting' && other) {
    return `${describeEngineSession(other)} is running on the script engine, which every researcher shares. The autopilot opens its own session when that one ends.`
  }
  return status.value.problem || ''
})

/**
 * When the next session opens, as a time of day; '' when none is due. Paused or done, it opens none
 * whatever the server's timer says (another experiment's finished session can still set one).
 */
const nextOpen = computed(() => {
  if (halt.value || kind.value === 'paused' || kind.value === 'done') return ''
  const at = status.value.nextOpenAt ? new Date(status.value.nextOpenAt) : null
  if (!at || isNaN(at.getTime())) return ''
  return at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
})

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

/** When the session in the notice was ended: a time today, else a date and time. */
const noticeTime = computed(() => {
  const at = status.value.noticeAt ? new Date(status.value.noticeAt) : null
  if (!at || isNaN(at.getTime())) return ''
  const today = at.toDateString() === new Date().toDateString()
  return today
    ? at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : at.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
})
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
.apst__resume { align-self: center; }
.apst__badges .b-badge svg { margin-left: -2px; }
.apst__block { display: flex; flex-direction: column; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--border); }
.apst__label { font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.apst__muted { font-size: var(--text-sm); color: var(--muted); }
.apst__session { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.apst__session-name { font-weight: 600; overflow-wrap: anywhere; }
.apst__watch { display: inline-flex; align-items: center; gap: 4px; margin-left: auto; font-size: var(--text-sm); font-weight: 600; }
.apst__problem {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin: 0 20px 16px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: var(--text-sm);
}
.apst__problem.is-info { background: var(--info-soft); color: var(--info-soft-text); }
/* (after a problem box, one gap between them) */
.apst__problem + .apst__notice { margin-top: -6px; }
.apst__problem svg { flex: none; margin-top: 2px; }
.apst__problem-title { font-weight: 650; }
.apst__problem-text { margin-top: 2px; overflow-wrap: anywhere; white-space: pre-wrap; }
.apst__problem-err { margin-top: 6px; }
.apst__open-step { display: inline-flex; align-items: center; gap: 4px; margin-top: 8px; font-weight: 600; color: inherit; }
.apst__live { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--accent-soft-text); font-size: var(--text-sm); }
.apst__local { margin: 6px 0 8px; }
.apst__link { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.apst__links { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 4px; }
@media (max-width: 760px) { .apst__links { grid-template-columns: minmax(0, 1fr); } }
.apst__activity { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.apst__activity li { display: flex; align-items: center; gap: 10px; font-size: var(--text-sm); }
.apst__time { color: var(--faint); font-size: var(--text-xs); min-width: 64px; }
.apst__dot { width: 7px; height: 7px; border-radius: 50%; background: var(--faint); flex: none; }
.apst__dot.is-success { background: var(--success); }
.apst__dot.is-warning { background: var(--warning); }
.apst__dot.is-info { background: var(--info); }
.apst__dot.is-danger { background: var(--danger); }
.apst__foot { display: flex; align-items: center; gap: 10px; padding: 8px 12px 8px 20px; border-top: 1px solid var(--border); background: var(--surface-sunken); }
.apst__foot > :first-child { flex: 1; min-width: 0; }
.apst__err { display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-sm); color: var(--warning-soft-text); }
</style>
