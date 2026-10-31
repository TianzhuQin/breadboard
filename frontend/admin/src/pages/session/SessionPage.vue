<template>
  <BPage intro="What is happening in the session right now, and the controls to run it.">
    <template v-if="inst && !isDone" #actions>
      <BButton :icon="Plus" @click="newOpen = true">New session</BButton>
    </template>

    <!-- while the server can't be reached, what is shown may be out of date -->
    <p v-if="offline" class="live-offline" role="status">
      <WifiOff :size="16" aria-hidden="true" />
      <span>
        <strong>Not connected to the server.</strong> This shows what was last known; the buttons work again once
        the console reconnects, which it does by itself.
      </span>
    </p>

    <!-- the newest engine error, so a broken step doesn't go unnoticed -->
    <section v-if="latestError" class="live-err" aria-labelledby="live-err-title">
      <CircleAlert class="live-err__icon" :size="18" aria-hidden="true" />
      <div class="live-err__body">
        <p id="live-err-title" class="live-err__title">
          {{ errorExperiment.elsewhere && errorExperiment.name ? `The scripts of “${errorExperiment.name}” reported an error` : 'The experiment\'s scripts reported an error' }}
          <span class="live-err__time">at {{ clock(latestError.at) }}</span>
        </p>
        <!-- "Line 71 of RoundStep", which opens that step at that line, when the server names the step (in the
             experiment the engine runs, which the Steps page asks to switch to when it isn't the one worked on) -->
        <EngineError
          class="live-err__text"
          :error="latestError.text"
          :own-name="latestError.step || 'a step'"
          :from-line="latestError.step ? 1 : null"
          @goto="(line: number) => latestError?.step && router.push(stepAt(latestError.step, line, errorExperiment.id))"
        />
      </div>
      <div class="live-err__actions">
        <BButton size="sm" :icon="PanelBottom" @click="openOutput = true">Open output</BButton>
        <BButton size="sm" variant="ghost" @click="dismissedErrorId = latestError.id">Dismiss</BButton>
      </div>
    </section>

    <NoSession v-if="!inst" :autopilot="autopilot" @new="newOpen = true" />

    <template v-else>
      <section class="live-hero" :class="`is-${phase}`" aria-labelledby="live-hero-name">
        <div class="live-hero__top">
          <div class="live-hero__id">
            <div class="live-hero__badges">
              <BBadge :tone="statusTone" dot :pulse="isRunning">{{ statusLabel }}</BBadge>
              <BBadge v-if="isRunning && started.started" tone="accent">Game in progress</BBadge>
              <BBadge v-else-if="isRunning" tone="neutral">{{ started.forgotten ? 'Game not running' : 'Game not started' }}</BBadge>
              <BBadge v-if="openedByAutopilot" tone="info">Opened by Autopilot</BBadge>
            </div>
            <h2 id="live-hero-name" class="live-hero__name">{{ inst.name }}</h2>
            <p class="live-hero__meta">
              <span>{{ inst.id > 0 ? `ID ${inst.id} · created ${createdText}` : `Created ${createdText}` }}</span>
              <span v-if="admin.engine === 'loading'" class="live-hero__loading" role="status">
                <span aria-hidden="true"><BSpinner :size="12" /></span> Restarting the experiment's scripts…
              </span>
            </p>
          </div>

          <div class="live-hero__actions">
            <template v-if="isRunning">
              <BButton
                v-if="!started.started"
                variant="primary"
                size="lg"
                :icon="Play"
                :loading="busy.start"
                :disabled="!!startBlocked"
                :title="startBlocked || 'Lets everyone who is waiting in, and the experiment begins'"
                @click="onStart"
              >Start the game</BButton>
              <span v-else class="live-hero__started">
                <CircleCheck :size="17" aria-hidden="true" />
                Game started<template v-if="started.at"> at {{ clock(started.at) }}</template>
              </span>
              <BButton variant="danger-ghost" :icon="CircleStop" :loading="busy.stop" @click="stopSession(inst)">Stop session</BButton>
            </template>
            <template v-else-if="inst.status === 'STOPPED'">
              <BButton variant="primary" size="lg" :icon="Play" :loading="busy.resume === inst.id" @click="resumeSession(inst)">Resume session</BButton>
            </template>
            <template v-else>
              <BButton variant="primary" :icon="Plus" @click="newOpen = true">New session</BButton>
              <BButton v-if="inst.id > 0" :icon="Download" :loading="busy.download === inst.id" @click="downloadData(inst)">Download data</BButton>
            </template>
          </div>
        </div>

        <!-- what this state means and what happens next, in plain words -->
        <p class="live-hero__explain">
          {{ explanation }}
          <!-- only the session's log says so (a start that threw logs its start too): offer it again -->
          <button v-if="isRunning && started.started && !started.sure && !startBlocked" type="button" class="live-linkbtn" @click="onStart">Not started? Start it anyway</button>
        </p>

        <div v-if="isDone" class="live-done">
          <div class="live-done__item"><span class="live-done__k">Ran for</span><span class="live-done__v">{{ ranFor }}</span></div>
          <div class="live-done__item"><span class="live-done__k">People joined</span><span class="live-done__v">{{ joinedCount }}</span></div>
          <div class="live-done__item"><span class="live-done__k">Events recorded</span><span class="live-done__v">{{ allEvents.length.toLocaleString() }}</span></div>
          <RouterLink to="/sessions" class="live-done__more">All sessions & data <ArrowRight :size="14" aria-hidden="true" /></RouterLink>
        </div>

        <div v-if="room && isRunning" class="live-wait" :class="`is-${room.state}`">
          <component :is="room.state === 'starting' ? Timer : Hourglass" class="live-wait__icon" :size="18" aria-hidden="true" />
          <div class="live-wait__body">
            <p class="live-wait__title">
              <template v-if="room.state === 'starting'">
                Starting in <span class="tabular" aria-hidden="true">{{ countdown }}</span><span class="sr-only">a few</span> seconds
              </template>
              <template v-else-if="room.min != null && room.here < room.min">Waiting room: {{ room.here }} of the {{ room.min }} people needed {{ room.here === 1 ? 'is' : 'are' }} here</template>
              <template v-else>
                Waiting room: {{ room.here }} {{ room.here === 1 ? 'person is' : 'people are' }} here, enough to start<template v-if="startsInLeft">. The game starts in about {{ startsInLeft }} s<template v-if="room.max"> (at once if {{ room.max }} are here)</template>.</template>
              </template>
            </p>
            <div
              class="live-wait__bar"
              role="progressbar"
              :aria-valuemin="0"
              :aria-valuemax="roomTarget"
              :aria-valuenow="Math.min(room.here, roomTarget)"
              :aria-label="`${room.here} of ${roomTarget} people`"
            >
              <span class="live-wait__fill" :style="{ width: `${roomPct}%` }" />
              <span v-if="room.max && room.min != null && room.min < room.max" class="live-wait__tick" :style="{ left: `${(room.min / room.max) * 100}%` }" />
            </div>
            <p class="live-wait__hint">{{ roomHint }}</p>
          </div>
        </div>
        <p class="sr-only" role="status" aria-live="polite">{{ roomAnnouncement }}</p>
      </section>

      <dl v-if="!isDone" class="live-stats">
        <div class="live-stat">
          <dt class="live-stat__label"><Users :size="15" aria-hidden="true" /> In the network</dt>
          <dd class="live-stat__value tabular">{{ stats.people }}</dd>
          <dd class="live-stat__sub">not counting AI players</dd>
        </div>
        <div class="live-stat">
          <dt class="live-stat__label"><Wifi :size="15" aria-hidden="true" /> Connected now</dt>
          <dd class="live-stat__value tabular">{{ stats.connected }}</dd>
          <dd class="live-stat__sub">{{ stats.people ? `${stats.people - stats.connected} not on the page` : 'nobody has joined yet' }}</dd>
        </div>
        <div class="live-stat">
          <dt class="live-stat__label"><Bot :size="15" aria-hidden="true" /> AI seats</dt>
          <dd class="live-stat__value tabular">{{ stats.aiSeats }}</dd>
          <dd class="live-stat__sub">{{ aiText }}</dd>
        </div>
        <div class="live-stat">
          <dt class="live-stat__label"><Share2 :size="15" aria-hidden="true" /> Ties</dt>
          <dd class="live-stat__value tabular">{{ stats.ties }}</dd>
          <dd class="live-stat__sub">{{ tieText }}</dd>
        </div>
      </dl>

      <div class="live-grid">
        <div class="live-grid__main">
          <EventFeed :events="feed" :live="isRunning" :session-key="inst.id" />
        </div>
        <div class="live-grid__side">
          <JoinLinks
            v-if="!isDone && inst.status !== 'TESTING' && admin.experiment"
            :experiment-id="admin.experiment.id"
            :instance-id="inst.id"
            :joinable="isRunning"
          />
          <NetworkPreview :people="stats.people" :ties="stats.ties" :ended="!isRunning" />
          <SessionParams :data="inst.data" :parameters="admin.experiment?.parameters" />
        </div>
      </div>
    </template>

    <NewSessionDialog v-model:open="newOpen" />
  </BPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  WifiOff,
  ArrowRight, Bot, CircleAlert, CircleCheck, CircleStop, Download, Hourglass, PanelBottom, Play, Plus, Share2, Timer, Users, Wifi,
} from 'lucide-vue-next'
import { BBadge, BButton, BPage, BSpinner } from '@/ui'
import { admin, connectionState, engineElsewhere, graph, isResearcherRun } from '@/store/admin'
import { stepsCall } from '../autopilot/autopilot'
import { openOutput } from '@/shell/output'
import EngineError from '../console/EngineError.vue'
import NewSessionDialog from './NewSessionDialog.vue'
import NoSession from './NoSession.vue'
import EventFeed from './EventFeed.vue'
import JoinLinks from './JoinLinks.vue'
import NetworkPreview from './NetworkPreview.vue'
import SessionParams from './SessionParams.vue'
import { FEED_CAP, fromLive, fromSeed, mergeFeed, parseServerTime, participantsSeen } from './events'
import {
  detectGameStarted, engineElsewhereText, engineGameFlag, formatDuration, formatWhen, networkStats, waitingRoom,
} from './live'
import { busy, dismissedErrorId, downloadData, resumeSession, startGame, stopSession } from './controls'
import { autopilotRuns, useAutopilotStatus } from './autopilot'
import { stepAt } from '../steps/stepsModel'

const route = useRoute()
const router = useRouter()

const inst = computed(() => admin.instance)
const isRunning = computed(() => inst.value?.status === 'RUNNING' || inst.value?.status === 'TESTING')
const isDone = computed(() => inst.value?.status === 'FINISHED' || inst.value?.status === 'ARCHIVED')

// ---- new session (also opened by links elsewhere: /session?new=1) ---------------------------

const newOpen = ref(false)
watch(() => route.query.new, (v) => {
  if (v !== '1' && v !== 'true') return
  newOpen.value = true
  const { new: _drop, ...rest } = route.query
  void router.replace({ query: rest })
}, { immediate: true })

// ---- events -----------------------------------------------------------------------------------

// recorded ones change only with a new User JSON; converting them is kept apart from live pushes
const seedFeed = computed(() => (inst.value?.events || []).filter(Boolean).map(fromSeed))
const liveFeed = computed(() => admin.events.filter(Boolean).map(fromLive))
const allEvents = computed(() => mergeFeed(seedFeed.value, liveFeed.value))
const feed = computed(() => allEvents.value.length > FEED_CAP ? allEvents.value.slice(0, FEED_CAP) : allEvents.value)

// ---- network ----------------------------------------------------------------------------------

const stats = computed(() => {
  void graph.version.value
  return networkStats(graph.nodes.values(), graph.links.size)
})
const room = computed(() => {
  void graph.version.value
  return waitingRoom(graph.nodes.values())
})
const aiText = computed(() => {
  const s = stats.value
  if (!s.aiSeats) return 'the computer plays no one'
  const parts = []
  if (s.bots) parts.push(`${s.bots} AI ${s.bots === 1 ? 'player' : 'players'}`)
  if (s.takenOver) parts.push(`${s.takenOver} taken over`)
  return parts.join(' · ')
})
const tieText = computed(() => {
  const s = stats.value
  const n = s.people + s.bots
  if (!s.ties) return 'none yet'
  return `about ${((2 * s.ties) / n).toFixed(1)} per person`
})

// ---- game start -------------------------------------------------------------------------------

const started = computed(() => detectGameStarted({
  hasStarted: (inst.value as any)?.hasStarted,
  events: allEvents.value,
  output: admin.output,
  since: admin.engineReloadedAt,
  // what the engine itself says (newer servers), when it is about this session
  engine: engineGameFlag(admin.user?.engine, admin.engineInfoCurrent, inst.value?.id, admin.engineInfoAt),
}))

// the shared engine runs another experiment, so the server refuses a start here; '' otherwise
const startBlocked = computed(() => engineElsewhere.value ? engineElsewhereText(engineElsewhere.value) : '')

function onStart () {
  const r = room.value
  void startGame({
    connected: stats.value.connected,
    people: stats.value.people,
    waitingRoom: !!r && r.state !== 'cancelled',
    again: started.value.forgotten,
  })
}

// ---- waiting room countdown -------------------------------------------------------------------

const now = ref(Date.now())
const countdownEnd = ref(0)
const startsInEnd = ref(0)
let ticker: ReturnType<typeof setInterval> | null = null
function stopTicker () { if (ticker) { clearInterval(ticker); ticker = null } }
watch(() => room.value && room.value.state === 'starting' ? room.value.seconds : null, (secs) => {
  if (secs == null) { if (startsInEnd.value === 0) stopTicker(); return }
  now.value = Date.now()
  countdownEnd.value = now.value + secs * 1000
  if (!ticker) ticker = setInterval(() => { now.value = Date.now() }, 250)
}, { immediate: true })
// the wait before that countdown, once enough people are here: counted down here too, as on their pages
watch(() => room.value && room.value.state === 'waiting' ? room.value.startsIn ?? null : null, (secs) => {
  if (secs == null) { startsInEnd.value = 0; if (!countdownEnd.value || countdown.value === 0) stopTicker(); return }
  now.value = Date.now()
  startsInEnd.value = now.value + secs * 1000
  if (!ticker) ticker = setInterval(() => { now.value = Date.now() }, 250)
}, { immediate: true })
onBeforeUnmount(stopTicker)
const countdown = computed(() => Math.max(0, Math.ceil((countdownEnd.value - now.value) / 1000)))
const startsInLeft = computed(() => startsInEnd.value ? Math.max(0, Math.ceil((startsInEnd.value - now.value) / 1000)) : null)

const roomTarget = computed(() => {
  const r = room.value
  return Math.max(1, (r && (r.max || r.min)) || 1)
})
const roomPct = computed(() => Math.min(100, ((room.value?.here || 0) / roomTarget.value) * 100))
const roomHint = computed(() => {
  const r = room.value
  if (!r) return ''
  if (r.state === 'starting') return `${r.here} ${r.here === 1 ? 'person is' : 'people are'} in. Their pages show the same countdown.`
  const parts = []
  if (r.min != null && r.here < r.min) parts.push('The game starts by itself once enough people are here.')
  else parts.push('The game starts by itself after the waiting time.')
  if (r.max) parts.push(`It starts at once when ${r.max} are here.`)
  parts.push('You can also start it now.')
  return parts.join(' ')
})
// read out when the room changes state, not on every tick or arrival
const roomAnnouncement = computed(() => {
  const s = room.value?.state
  return s === 'starting' ? 'The waiting room is counting down to the start.' : s === 'waiting' ? 'The waiting room is open.' : ''
})

// ---- status texts -----------------------------------------------------------------------------

const phase = computed(() => !inst.value ? 'none' : isRunning.value ? (started.value.started ? 'playing' : 'open') : isDone.value ? 'done' : 'stopped')
const statusLabel = computed(() => ({ RUNNING: 'Running', TESTING: 'Testing', STOPPED: 'Stopped', FINISHED: 'Finished', ARCHIVED: 'Archived' } as Record<string, string>)[inst.value?.status || ''] || inst.value?.status || '')
const statusTone = computed(() => isRunning.value ? 'success' : inst.value?.status === 'STOPPED' ? 'warning' : 'neutral')
const createdAt = computed(() => parseServerTime(inst.value?.creationTime))
const createdText = computed(() => formatWhen(createdAt.value))

const autopilot = useAutopilotStatus()
const autoOn = computed(() => autopilotRuns(autopilot.value, admin.experiment?.id))
const openedByAutopilot = computed(() => autoOn.value && !!inst.value && autopilot.value?.instance?.id === inst.value.id)

const explanation = computed(() => {
  const i = inst.value
  if (!i) return ''
  if (i.status === 'TESTING') return 'This is the test session for trying the experiment out. What happens in it is not saved.'
  if (isRunning.value) {
    if (startBlocked.value) return startBlocked.value
    if (started.value.started && started.value.nearReload) return 'The session\'s log shows the game starting at about the time the engine last reloaded, and a reload forgets a start, so the game may not be running now.'
    if (started.value.started && openedByAutopilot.value) return 'The game is on and new arrivals are turned away. The experiment ends it, and the autopilot then opens the next session. (Stopping it pauses the autopilot.)'
    if (started.value.started) return 'The game is on and new arrivals are turned away. Stop the session when you are done, or let the experiment finish it.'
    // (the server reopens a session it carries into a rebuilt engine, so after a reload this shows
    // only until the next update; an AMT session's entry also closes before its game starts)
    if (started.value.forgotten) return 'The session is closed to newcomers, but its game isn\'t running in the engine: the engine was reloaded since it started (a reload forgets a started game; the session takes people again in a moment), or entry closed before the start, as a Mechanical Turk session\'s does when its HIT\'s time is up. Start the game to run it from the beginning with whoever is in the network now.'
    if (room.value && room.value.state !== 'cancelled') return 'People are gathering in the waiting room, which starts the game by itself.'
    if (openedByAutopilot.value && autopilot.value?.halted === 'paused') return 'The autopilot opened this session but is paused: its waiting room still starts the game, but no next session opens until you press Resume on the Autopilot page.'
    if (openedByAutopilot.value) return 'The autopilot opened this session: people join with the links below and wait in its waiting room, which starts the game by itself once enough are here. You don\'t need to do anything.'
    if (stepsCall(admin.experiment?.steps, 'waitingRoom')) return 'People can join with the links below. This experiment has a waiting room: once enough people are here, the game starts by itself (you can also start it yourself).'
    return 'People can join with the links below. When everyone you need is here, start the game: everyone waiting is let in and newcomers are turned away.'
  }
  if (i.status === 'STOPPED') return 'This session is stopped, so nobody can join. Resuming it runs its game again from the beginning: its scripts restart, which disconnects anyone still on the page. The data recorded so far is kept.'
  const gaveUp = room.value?.state === 'cancelled'
  const ap = autopilot.value
  // (a paused autopilot opens nothing until Resume, whatever its settings say)
  const next = !autoOn.value || !ap
    ? ''
    : ap.halted === 'paused'
      ? ' Autopilot is paused: it opens no new session until someone presses Resume on the Autopilot page.'
      : ap.continuous && !ap.halted
        // (restarting the experiment's scripts for it takes a second or so too)
        ? ap.nextSessionDelaySeconds
          ? ` Autopilot opens the next session about ${ap.nextSessionDelaySeconds} seconds after one finishes, plus a second or so to restart the experiment's scripts.`
          : ' Autopilot opens the next session a second or so after one finishes, once the experiment\'s scripts have restarted.'
        : ''
  if (i.status === 'ARCHIVED') return `This session is archived. Its data is kept.${next}`
  return `${gaveUp ? 'The waiting room gave up because not enough people joined. ' : 'This session has finished. '}Its data is saved: download it now, or find it later under Sessions & data.${next}`
})

const lastEventAt = computed(() => allEvents.value.length ? allEvents.value[0].at : 0)
const ranFor = computed(() => createdAt.value && lastEventAt.value >= createdAt.value ? formatDuration(lastEventAt.value - createdAt.value) : '—')
const joinedCount = computed(() => Math.max(participantsSeen(allEvents.value).size, stats.value.people))

// Once the first state has arrived: before that, the page shows its own loading state.
const offline = computed(() => admin.ready && connectionState.value !== 'open')

// ---- latest error -----------------------------------------------------------------------------

// errors from before the scripts last reloaded are about code that is no longer running
const latestError = computed(() => {
  for (let i = admin.output.length - 1; i >= 0; i--) {
    const e = admin.output[i]
    if (e.at < admin.engineReloadedAt) return null
    // a mistake in a snippet run from the Script console isn't the experiment failing
    if (e.kind === 'error' && !isResearcherRun(e.source)) return e.id > dismissedErrorId.value ? e : null
  }
  return null
})

/**
 * The experiment the error is in: the one the engine runs (errors from before it last reloaded aren't
 * shown), which can be another than the one this console works on, e.g. the autopilot's.
 */
const errorExperiment = computed(() => {
  const e = admin.user?.engine
  const id = e?.experimentId ?? null
  return { id, name: e?.experimentName ?? null, elsewhere: id !== null && id !== admin.experiment?.id }
})

function clock (at: number) {
  return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
</script>

<style>
/* ---- engine error ---- */
.live-err {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 13px 16px;
  background: var(--danger-soft);
  border: 1px solid color-mix(in srgb, var(--danger) 30%, transparent);
  border-radius: var(--radius);
}
.live-err__icon { flex: none; margin-top: 1px; color: var(--danger); }
.live-err__body { flex: 1; min-width: 0; }
.live-err__title { font-weight: 650; color: var(--danger-soft-text); }
.live-err__time { font-weight: 400; color: var(--muted); font-size: var(--text-sm); margin-left: 4px; }
/* (no height limit here: EngineError is a flex column, so a cap squeezed its message and its "Full
   error" to a few pixels; the message limits its own height) */
.live-err__text {
  margin: 6px 0 0;
  font-size: var(--text-xs);
  line-height: 1.55;
  color: var(--text-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.live-err__actions { display: flex; gap: 6px; flex: none; }

/* ---- hero ---- */
.live-hero {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px 24px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  position: relative;
  overflow: hidden;
}
/* a thin status line along the top edge */
.live-hero::before { content: ''; position: absolute; inset: 0 0 auto 0; height: 3px; background: var(--border); }
.live-hero.is-open::before { background: var(--success); }
.live-hero.is-playing::before { background: var(--accent); }
.live-hero.is-stopped::before { background: var(--warning); }
.live-hero__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px 24px; flex-wrap: wrap; }
.live-hero__id { min-width: 0; flex: 1 1 320px; }
.live-hero__badges { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.live-hero__name { font-size: var(--text-2xl); font-weight: 700; letter-spacing: -0.02em; overflow-wrap: anywhere; }
.live-hero__meta { margin-top: 4px; color: var(--muted); font-size: var(--text-sm); display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; }
.live-hero__loading { display: inline-flex; align-items: center; gap: 6px; color: var(--accent-soft-text); }
.live-hero__actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.live-hero__started {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 42px;
  padding: 0 14px;
  border-radius: var(--radius-sm);
  background: var(--accent-soft);
  color: var(--accent-soft-text);
  font-weight: 600;
}
.live-hero__explain { color: var(--text-soft); max-width: 760px; }
.live-linkbtn {
  margin-left: 6px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font-weight: 550;
  cursor: pointer;
}
.live-linkbtn:hover { text-decoration: underline; }

.live-done { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px 36px; padding-top: 14px; border-top: 1px solid var(--border); }
.live-done__item { display: flex; flex-direction: column; gap: 2px; }
.live-done__k { font-size: var(--text-xs); color: var(--muted); }
.live-done__v { font-size: var(--text-xl); font-weight: 650; font-variant-numeric: tabular-nums; }
.live-done__more { margin-left: auto; display: inline-flex; align-items: center; gap: 5px; font-size: var(--text-sm); font-weight: 550; }

.live-wait {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: var(--info-soft);
  border: 1px solid color-mix(in srgb, var(--info) 22%, transparent);
}
.live-wait.is-starting { background: var(--accent-soft); border-color: color-mix(in srgb, var(--accent) 25%, transparent); }
.live-wait__icon { flex: none; margin-top: 2px; color: var(--info); }
.live-wait.is-starting .live-wait__icon { color: var(--accent); }
.live-wait__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.live-wait__title { font-weight: 650; color: var(--text); }
.live-wait__bar { position: relative; height: 6px; border-radius: var(--radius-pill); background: color-mix(in srgb, var(--text) 10%, transparent); max-width: 520px; }
.live-wait__fill { position: absolute; inset: 0 auto 0 0; border-radius: inherit; background: var(--info); transition: width 400ms var(--ease); }
.live-wait.is-starting .live-wait__fill { background: var(--accent); }
.live-wait__tick { position: absolute; top: -3px; width: 2px; height: 12px; margin-left: -1px; border-radius: 1px; background: var(--text-soft); }
.live-wait__hint { font-size: var(--text-sm); color: var(--muted); }

/* ---- stats ---- */
.live-stats { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 0; }
.live-stat {
  display: flex;
  flex-direction: column;
  padding: 14px 16px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-sm);
  min-width: 0;
}
.live-stat__label { display: flex; align-items: center; gap: 7px; order: 0; font-size: var(--text-xs); font-weight: 600; color: var(--muted); }
.live-stat__value { order: 1; margin: 6px 0 0; font-size: 28px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.1; color: var(--text); }
.live-stat__sub { order: 2; margin: 3px 0 0; font-size: var(--text-xs); color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---- layout ---- */
.live-grid { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(0, 1fr); gap: 20px; align-items: start; }
.live-grid__main, .live-grid__side { display: flex; flex-direction: column; gap: 20px; min-width: 0; }

@media (max-width: 1080px) {
  .live-grid { grid-template-columns: minmax(0, 1fr); }
  /* links first on narrow screens: they are what a researcher reaches for */
  .live-grid__side { order: -1; }
}
@media (max-width: 760px) {
  .live-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .live-hero { padding: 18px 16px; }
  .live-hero__name { font-size: var(--text-xl); }
  .live-hero__actions { width: 100%; }
  .live-hero__actions > .b-btn--primary { flex: 1; }
  .live-err { flex-wrap: wrap; }
  .live-err__actions { width: 100%; justify-content: flex-end; }
  .live-done__more { margin-left: 0; }
}
.live-offline {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: var(--radius);
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: var(--text-sm);
}
.live-offline svg { flex: none; margin-top: 2px; }
</style>
