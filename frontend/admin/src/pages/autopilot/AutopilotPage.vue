<template>
  <BPage intro="Let an experiment run by itself: Breadboard opens a session, lets people in through a fixed link, starts the game once enough have arrived, and opens the next session when one ends.">
    <div v-if="phase === 'loading'" class="ap-loading" role="status">
      <BSpinner :size="20" />
      <span>Loading the autopilot…</span>
    </div>

    <div v-else-if="phase === 'unavailable'" class="ap-empty">
      <BEmpty
        :icon="ServerCog"
        title="The autopilot needs a server update"
        text="This Breadboard server doesn't offer autopilot settings yet. Update Breadboard on the server and restart it, then come back. Until then the autopilot can be set in application.conf (breadboard.autopilot.*)."
      >
        <template #actions>
          <BButton :icon="RefreshCw" :loading="refreshing" @click="load()">Check again</BButton>
        </template>
      </BEmpty>
    </div>

    <div v-else-if="phase === 'error'" class="ap-empty">
      <BEmpty :icon="TriangleAlert" title="Couldn't load the autopilot" :text="loadError">
        <template #actions>
          <BButton variant="primary" :icon="RefreshCw" :loading="refreshing" @click="load()">Try again</BButton>
        </template>
      </BEmpty>
    </div>

    <template v-else-if="state">
      <AutopilotStatus
        :state="state"
        :links="links"
        :live="live"
        :activity="activity"
        :is-current="isCurrent"
        :refreshing="refreshing"
        :error="refreshError"
        :experiment-name="savedExperimentName"
        :resumable="!dirty"
        :resuming="saving"
        @refresh="refresh(true)"
        @resume="resume"
      />

      <div v-if="readOnly" class="ap-banner" role="note">
        <Lock :size="17" aria-hidden="true" />
        <div>
          <p class="ap-banner__title">These settings come from the server's configuration file</p>
          <p class="ap-banner__text">
            <code>application.conf</code> sets <code>breadboard.autopilot.experiment</code>, so the server follows that file
            and changes can't be made here. To manage the autopilot from this page, remove the
            <code>breadboard.autopilot.*</code> lines from the file and restart the server.
          </p>
        </div>
      </div>

      <form ref="formEl" class="ap-form" novalidate @submit.prevent="submit()">
        <p v-if="saveError" class="ap-error" role="alert">{{ saveError }}</p>

        <!-- On / off and which experiment -->
        <section class="ap-sec" aria-labelledby="ap-sec-run">
          <div class="ap-sec__head">
            <span class="ap-sec__icon" aria-hidden="true"><Bot :size="17" /></span>
            <div>
              <h2 id="ap-sec-run" class="ap-sec__title">Run unattended</h2>
              <p class="ap-sec__desc">
                While it's on, Breadboard keeps a session of this experiment open: right after saving, when the server
                starts, and after each session ends. While another researcher's session is running, it waits for that
                one to end. Turning it off stops new sessions; one that is open keeps running.
              </p>
            </div>
          </div>
          <div class="ap-sec__body">
            <BSwitch v-model="form.enabled" :disabled="readOnly">Run this experiment unattended</BSwitch>
            <BField label="Experiment" :error="errors.experimentId" class="ap-exp">
              <template #default="{ id, describedby }">
                <div class="ap-exp__row">
                  <BSelect
                    :id="id"
                    v-model="form.experimentId"
                    :options="experimentOptions"
                    placeholder="Choose an experiment"
                    :disabled="readOnly"
                    :aria-describedby="describedby"
                    :aria-invalid="errors.experimentId ? 'true' : undefined"
                  />
                  <BButton
                    v-if="!readOnly && admin.experiment && form.experimentId !== admin.experiment.id"
                    variant="ghost"
                    size="sm"
                    @click="form.experimentId = admin.experiment!.id"
                  >
                    Use “{{ admin.experiment.name }}”
                  </BButton>
                </div>
              </template>
            </BField>
            <!-- the autopilot can't ask for launch parameters: its sessions use the defaults -->
            <p v-if="launchDefaults.length" class="ap-preview ap-params" :class="{ 'is-warn': emptyDefaults.length }">
              <Info :size="15" aria-hidden="true" />
              <span>
                Its sessions use each launch parameter's default:
                <template v-for="(p, i) in launchDefaults" :key="p.name"><code>{{ p.name }}</code> {{ p.value === '' ? '(empty)' : p.value }}{{ i < launchDefaults.length - 1 ? ', ' : '.' }} </template>
                <template v-if="emptyDefaults.length">Check that {{ emptyDefaults.join(' and ') }} can be empty. </template>
                Change them on the <RouterLink to="/parameters">Parameters</RouterLink> page.
              </span>
            </p>
            <div v-if="holder" class="ap-preview ap-holder" role="note">
              <Hourglass :size="15" aria-hidden="true" />
              <div>
                <p>
                  {{ describeEngineSession(holder) }} is running on the script engine, which every researcher shares.
                  <template v-if="halt && !dirty">
                    The autopilot is {{ halt }}, so it opens nothing until you press
                    {{ resumeLabel(halt) }}; then it waits for that session to end and opens its own.
                  </template>
                  <template v-else>
                    {{ dirty || !state.settings.enabled ? 'When you save, the autopilot waits' : 'The autopilot waits' }}
                    for that session to end, then opens its own.
                  </template>
                </p>
                <BButton class="ap-holder__btn" size="sm" variant="danger" :disabled="saving" @click="save(true)">Save and end it now…</BButton>
              </div>
            </div>
          </div>
        </section>

        <!-- Sessions -->
        <section class="ap-sec" aria-labelledby="ap-sec-sessions">
          <div class="ap-sec__head">
            <span class="ap-sec__icon" aria-hidden="true"><Repeat :size="17" /></span>
            <div>
              <h2 id="ap-sec-sessions" class="ap-sec__title">One session after another</h2>
              <p class="ap-sec__desc">Keep recruiting without you: when a session finishes (or is called off), the next one opens.</p>
            </div>
          </div>
          <div class="ap-sec__body">
            <BSwitch v-model="form.continuous" :disabled="readOnly">Open the next session when one finishes</BSwitch>
            <div class="ap-grid">
              <BField label="Pause before the next session" :error="errors.nextSessionDelaySeconds" :hint="secondsHint(form.nextSessionDelaySeconds, 'Time for the server to save the data. 0 opens it at once.')">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.nextSessionDelaySeconds"
                    type="number"
                    min="0"
                    step="1"
                    inputmode="numeric"
                    :disabled="readOnly || !form.continuous"
                    :aria-describedby="describedby"
                    :aria-invalid="errors.nextSessionDelaySeconds ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">seconds</span></template>
                  </BInput>
                </template>
              </BField>
            </div>
          </div>
        </section>

        <!-- Waiting room -->
        <section class="ap-sec" aria-labelledby="ap-sec-room">
          <div class="ap-sec__head">
            <span class="ap-sec__icon" aria-hidden="true"><Hourglass :size="17" /></span>
            <div>
              <h2 id="ap-sec-room" class="ap-sec__title">Waiting room</h2>
              <p class="ap-sec__desc">
                People who arrive early see a waiting page until enough have joined, and the game starts by
                itself. Switched on, the settings below are used; switched off, the experiment's steps decide.
              </p>
            </div>
          </div>
          <div class="ap-sec__body">
            <p v-if="ownRoom" class="ap-own">
              <Info :size="15" aria-hidden="true" />
              <span>This experiment's steps set their own waiting room: <code>{{ ownRoom }}</code>.
                {{ form.waitingRoom.enabled ? 'The settings below replace it.' : 'It is used as long as the switch is off.' }}</span>
            </p>
            <p v-else-if="!form.waitingRoom.enabled" class="ap-own is-warn">
              <TriangleAlert :size="15" aria-hidden="true" />
              <span>This experiment's steps set no waiting room, so with the switch off its sessions wait for someone to press Start the game.</span>
            </p>
            <BSwitch v-model="form.waitingRoom.enabled" :disabled="readOnly">Use the waiting room set here</BSwitch>
            <div class="ap-grid">
              <BField label="Minimum players" :error="errors['waitingRoom.minPlayers']" hint="The game can start once this many people are here.">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.waitingRoom.minPlayers"
                    type="number"
                    min="1"
                    step="1"
                    inputmode="numeric"
                    :disabled="roomOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['waitingRoom.minPlayers'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">people</span></template>
                  </BInput>
                </template>
              </BField>
              <BField label="Maximum players" optional :error="errors['waitingRoom.maxPlayers']" hint="When this many are here, the countdown starts at once. Empty: no maximum.">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.waitingRoom.maxPlayers"
                    type="number"
                    min="1"
                    step="1"
                    inputmode="numeric"
                    placeholder="No maximum"
                    :disabled="roomOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['waitingRoom.maxPlayers'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">people</span></template>
                  </BInput>
                </template>
              </BField>
              <BField label="Wait for more people" :error="errors['waitingRoom.waitSeconds']" :hint="secondsHint(form.waitingRoom.waitSeconds, 'After the minimum is reached, how long to wait for latecomers. 0: start right away.')">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.waitingRoom.waitSeconds"
                    type="number"
                    min="0"
                    step="1"
                    inputmode="numeric"
                    :disabled="roomOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['waitingRoom.waitSeconds'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">seconds</span></template>
                  </BInput>
                </template>
              </BField>
              <BField label="Give up after" optional :error="errors['waitingRoom.maxWaitSeconds']" :hint="secondsHint(form.waitingRoom.maxWaitSeconds, 'Counted from the first arrival. If the minimum isn\'t reached by then, the session is called off and everyone is told. Empty: wait as long as it takes.')">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.waitingRoom.maxWaitSeconds"
                    type="number"
                    min="1"
                    step="1"
                    inputmode="numeric"
                    placeholder="Never"
                    :disabled="roomOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['waitingRoom.maxWaitSeconds'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">seconds</span></template>
                  </BInput>
                </template>
              </BField>
              <BField label="Countdown" :error="errors['waitingRoom.countdownSeconds']" :hint="secondsHint(form.waitingRoom.countdownSeconds, 'Participants see “Starting in … seconds” before the game begins. 0: no countdown.')">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.waitingRoom.countdownSeconds"
                    type="number"
                    min="0"
                    step="1"
                    inputmode="numeric"
                    :disabled="roomOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['waitingRoom.countdownSeconds'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">seconds</span></template>
                  </BInput>
                </template>
              </BField>
            </div>
            <p class="ap-preview" aria-live="polite">
              <Info :size="15" aria-hidden="true" />
              <span>{{ roomSummary }}</span>
            </p>
          </div>
        </section>

        <!-- People who leave -->
        <section class="ap-sec" aria-labelledby="ap-sec-leave">
          <div class="ap-sec__head">
            <span class="ap-sec__icon" aria-hidden="true"><UserRoundX :size="17" /></span>
            <div>
              <h2 id="ap-sec-leave" class="ap-sec__title">People who leave during the game</h2>
              <p class="ap-sec__desc">
                Keep the game going when someone closes their page: the computer plays their seat. Switched
                on, the settings below are used; switched off, the experiment's steps decide.
              </p>
            </div>
          </div>
          <div class="ap-sec__body">
            <p v-if="ownLeave" class="ap-own">
              <Info :size="15" aria-hidden="true" />
              <span>This experiment's steps set it themselves: <code>{{ ownLeave }}</code>.
                {{ form.replaceDisconnected.enabled ? 'The settings below replace it.' : 'It is used as long as the switch is off.' }}</span>
            </p>
            <BSwitch v-model="form.replaceDisconnected.enabled" :disabled="readOnly">Let the computer play for people who leave, as set here</BSwitch>
            <div class="ap-grid">
              <BField label="Take over after" :error="errors['replaceDisconnected.afterSeconds']" :hint="secondsHint(form.replaceDisconnected.afterSeconds, 'A grace period, so a page reload or a short network drop doesn\'t hand the seat over.')">
                <template #default="{ id, describedby }">
                  <BInput
                    :id="id"
                    v-model="form.replaceDisconnected.afterSeconds"
                    type="number"
                    min="1"
                    step="1"
                    inputmode="numeric"
                    :disabled="leaveOff"
                    :aria-describedby="describedby"
                    :aria-invalid="errors['replaceDisconnected.afterSeconds'] ? 'true' : undefined"
                  >
                    <template #suffix><span class="ap-unit">seconds</span></template>
                  </BInput>
                </template>
              </BField>
              <div class="ap-check">
                <BCheckbox v-model="form.replaceDisconnected.returnToHuman" :disabled="leaveOff">Give the seat back when they return</BCheckbox>
                <p class="ap-check__hint">If they come back, they take over from the computer and play on.</p>
              </div>
            </div>
          </div>
        </section>

        <!-- application.conf in charge: nothing to save, but a paused or done autopilot can still be resumed -->
        <div v-if="!readOnly || halt" class="ap-savebar" :class="{ 'is-dirty': dirty }">
          <span class="ap-savebar__text" role="status">
            <template v-if="dirty"><span class="ap-savebar__dot" aria-hidden="true" /> Unsaved changes</template>
            <template v-else-if="state.source === 'off'">Not set up yet: these are the suggested settings.</template>
            <template v-else-if="halt === 'paused'">Paused. Resume to let it open sessions again with these settings.</template>
            <template v-else-if="halt === 'done'">Its one session has finished. Open another with these settings whenever you like.</template>
            <template v-else>All changes saved.</template>
          </span>
          <BButton v-if="dirty" variant="ghost" :icon="Undo2" :disabled="saving" @click="discard">Discard</BButton>
          <BButton type="submit" variant="primary" :icon="halt && !dirty ? Play : Save" :loading="saving" :disabled="!canSave">
            {{ halt && !dirty ? resumeLabel(halt) : 'Save' }}
          </BButton>
        </div>
      </form>
    </template>
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bot, Hourglass, Info, Lock, RefreshCw, Repeat, Save, ServerCog, TriangleAlert, Undo2, UserRoundX, Play } from 'lucide-vue-next'
import { BButton, BCheckbox, BEmpty, BField, BInput, BPage, BSelect, BSpinner, BSwitch, confirm, notify } from '@/ui'
import { HttpError } from '@/lib/http'
import { admin, experiments, graph } from '@/store/admin'
import { describeError } from '../experiments/experiments'
import { liveEventOf } from '../sessions/sessionData'
import AutopilotStatus from './AutopilotStatus.vue'
import { useUnsavedGuard } from './useUnsavedGuard'
import {
  autopilotActivity, describeEngineSession, describeWaitingRoom, fetchAutopilot, haltOf, humanSeconds, participantLinks,
  resumeAutopilot, resumeLabel, sameForm, saveAutopilot, takeOverTarget, DEFAULTS, toForm, toSettings, validateAutopilot,
  stepsCall, waitingRoomLive, type AutopilotForm, type AutopilotState, type EngineSession,
} from './autopilot'

type Phase = 'loading' | 'ready' | 'unavailable' | 'error'
const phase = ref<Phase>('loading')
const state = ref<AutopilotState | null>(null)
// starts from the defaults so the template never deals with a missing form
const form = ref<AutopilotForm>(toForm(DEFAULTS))
const baseline = ref<AutopilotForm>(toForm(DEFAULTS))
const loadError = ref('')
const refreshError = ref('')
const refreshing = ref(false)
const saving = ref(false)
const saveError = ref('')
const formEl = ref<HTMLFormElement>()

const readOnly = computed(() => state.value?.source === 'config')
/**
 * Paused (a researcher stopped its session) or done (its one session finished, "continuous" off):
 * it opens nothing until Resume, which the button does without edits (POST /autopilot/resume),
 * also while application.conf is in charge. With edits, saving resumes it too.
 */
const halt = computed(() => haltOf(state.value))
const dirty = computed(() => phase.value === 'ready' && !readOnly.value && !sameForm(form.value, baseline.value))
/** Something to do: edits, first-time settings, or a paused or done autopilot to start again. */
const canSave = computed(() => dirty.value || state.value?.source === 'off' || !!halt.value)
const errors = computed<Record<string, string>>(() => (readOnly.value ? {} : validateAutopilot(form.value)))
const roomOff = computed(() => readOnly.value || !form.value.waitingRoom.enabled)
const leaveOff = computed(() => readOnly.value || !form.value.replaceDisconnected.enabled)
const roomSummary = computed(() => describeWaitingRoom(form.value, !!ownRoom.value))

const experimentOptions = computed(() => {
  const opts = experiments.value.map(e => ({ value: e.id as number | null, label: e.name }))
  const id = form.value.experimentId
  if (id != null && !opts.some(o => o.value === id)) {
    // run by the server for an experiment that isn't in this account's list
    const name = state.value?.status.experimentId === id ? state.value.status.experimentName : null
    opts.unshift({ value: id, label: name ? `${name} (not in your list)` : `Experiment #${id} (not in your list)` })
  }
  return opts
})
/**
 * The live session that opening the autopilot's session would end (the server's engineSession, or
 * its own open session when the form moves it to another experiment); null when there is none.
 * The console's engine info names the account that runs it when the autopilot's status doesn't.
 */
const holder = computed<EngineSession | null>(() => {
  if (!state.value || readOnly.value) return null
  const t = takeOverTarget(form.value, state.value.status)
  if (!t || t.owner) return t
  const e = admin.user?.engine
  return e && e.sessionId === t.id && e.owner ? { ...t, owner: e.owner } : t
})
const savedExperimentName = computed(() => {
  const id = state.value?.settings.experimentId
  return id == null ? null : (experiments.value.find(e => e.id === id)?.name ?? null)
})

// ---- live view of the open session --------------------------------------------------------------

const isCurrent = computed(() => {
  const inst = state.value?.status.instance
  return !!inst && !!admin.instance && admin.instance.id === inst.id
})
const links = computed(() => state.value
  ? participantLinks(state.value.status, state.value.settings.experimentId, window.location.origin)
  : null)
const live = computed(() => {
  void graph.version.value
  return isCurrent.value ? waitingRoomLive(graph.nodes.values()) : null
})
const activity = computed(() => {
  const inst = state.value?.status.instance
  if (!isCurrent.value || !inst) return []
  // only what its own session recorded: pushes from every session reach this console
  const live = inst.status === 'RUNNING' || inst.status === 'TESTING'
  return autopilotActivity(admin.events.filter(e => liveEventOf(e, inst.id, live)))
})

// ---- loading -----------------------------------------------------------------------------------

// The chosen experiment's own autopilot calls, from its steps (null: none).
const formExperiment = computed(() => experiments.value.find(e => e.id === form.value.experimentId) ||
  (admin.experiment && admin.experiment.id === form.value.experimentId ? admin.experiment : null))
const ownRoom = computed(() => stepsCall(formExperiment.value?.steps, 'waitingRoom'))
const launchDefaults = computed(() => (formExperiment.value?.parameters || []).map(p => ({ name: p.name, value: String(p.defaultVal ?? '') })))
// (an empty text default is often a completion code someone forgot to fill in)
const emptyDefaults = computed(() => launchDefaults.value.filter(p => p.value === '').map(p => p.name))
const ownLeave = computed(() => stepsCall(formExperiment.value?.steps, 'replaceDisconnected'))

/**
 * Never set up yet: suggest the experiment being worked on, and leave to its steps what they
 * already set (a saved waiting room would silently replace theirs).
 */
function suggested (s: AutopilotState): AutopilotForm {
  const f = toForm(s.settings)
  if (s.source !== 'off' || f.experimentId != null || !admin.experiment) return f
  f.experimentId = admin.experiment.id
  if (stepsCall(admin.experiment.steps, 'waitingRoom')) f.waitingRoom.enabled = false
  if (stepsCall(admin.experiment.steps, 'replaceDisconnected')) f.replaceDisconnected.enabled = false
  return f
}

function adopt (s: AutopilotState, resetForm: boolean) {
  state.value = s
  if (resetForm) {
    // (never set up: the suggestion is the starting point, not an edit; Save still saves it)
    form.value = suggested(s)
    baseline.value = suggested(s)
  } else if (!dirty.value && !sameForm(baseline.value, suggested(s))) {
    // changed elsewhere (another admin, or the config) while nothing was being edited here
    // (and, never set up, the suggestion stays the suggestion)
    form.value = suggested(s)
    baseline.value = suggested(s)
  }
}

async function load () {
  refreshing.value = true
  try {
    adopt(await fetchAutopilot(), true)
    phase.value = 'ready'
    refreshError.value = ''
  } catch (e) {
    if (e instanceof HttpError && e.status === 404) phase.value = 'unavailable'
    else if (!(e instanceof HttpError && e.status === 401)) {
      loadError.value = describeError(e)
      phase.value = 'error'
    }
  } finally {
    refreshing.value = false
  }
}

/** The status only; the form is left alone while it has unsaved edits. */
async function refresh (byHand = false) {
  if (phase.value !== 'ready' || saving.value || (refreshing.value && !byHand)) return
  refreshing.value = true
  try {
    adopt(await fetchAutopilot(), false)
    refreshError.value = ''
  } catch (e) {
    if (e instanceof HttpError && e.status === 404) phase.value = 'unavailable'
    else refreshError.value = describeError(e)
  } finally {
    refreshing.value = false
  }
}

let poll: ReturnType<typeof setInterval> | undefined
let soon: ReturnType<typeof setTimeout> | undefined
onMounted(() => {
  load()
  poll = setInterval(() => { if (document.visibilityState === 'visible') refresh() }, 10000)
})
onBeforeUnmount(() => {
  clearInterval(poll)
  clearTimeout(soon)
})
// a session opened, started or finished: look again (debounced, pushes come in bursts)
watch(() => [admin.instance?.id, admin.instance?.status], () => {
  clearTimeout(soon)
  soon = setTimeout(() => refresh(), 600)
})

// ---- saving ------------------------------------------------------------------------------------

function secondsHint (v: number | null, text: string) {
  return v != null && v >= 60 && Number.isInteger(v) ? `${text} (${humanSeconds(v)})` : text
}

function discard () {
  form.value = toForm(baseline.value)
  saveError.value = ''
}

/** The main button (and Cmd/Ctrl+S): resume a paused or done autopilot as it is, or save the edits. */
function submit () {
  if (halt.value && !dirty.value) resume()
  else save()
}

/** Resume without changing a setting (the only way while application.conf is in charge). */
async function resume () {
  const was = halt.value
  if (!state.value || !was || saving.value) return
  saveError.value = ''
  saving.value = true
  let failed = false
  try {
    const now = await resumeAutopilot()
    adopt(now, false)
    const name = now.status.experimentName || savedExperimentName.value || 'the experiment'
    const waitingFor = now.status.engineSession
    if (waitingFor && waitingFor.experimentId !== now.settings.experimentId) {
      notify.success(was === 'paused' ? 'The autopilot is running again' : 'Opening another session',
        `It opens a session of “${name}” once “${waitingFor.name || `Session #${waitingFor.id}`}” ends.`)
    } else if (was === 'done') {
      notify.success('Opening another session', `The autopilot opens one more session of “${name}”. Share the participant links on this page.`)
    } else {
      notify.success('The autopilot is running again', `“${name}” runs unattended. Share the participant links on this page.`)
    }
  } catch (e) {
    if (e instanceof HttpError && e.status === 401) return
    failed = true
    saveError.value = `Not ${was === 'paused' ? 'resumed' : 'opened'}. ${describeError(e)}`
    notify.error(was === 'paused' ? 'The autopilot was not resumed' : 'No session was opened', describeError(e))
  } finally {
    saving.value = false
  }
  // the server may see it otherwise (switched off elsewhere, say): show what it says now
  if (failed) refresh(true)
}

/**
 * Save the settings. With `takeOver` (the "Save and end it now" button) a live session that holds
 * the engine is ended so the autopilot's session opens at once; a plain save never ends one: the
 * server then waits for it to end.
 */
async function save (takeOver = false) {
  if (!state.value || readOnly.value || saving.value) return
  saveError.value = ''
  if (Object.keys(errors.value).length) {
    notify.error('Check the highlighted settings', 'Some values need fixing before they can be saved.')
    await nextTick()
    formEl.value?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    return
  }
  const next = toSettings(form.value)
  const prev = state.value.settings
  const wasPaused = halt.value === 'paused'
  const wasDone = halt.value === 'done'
  const name = experiments.value.find(e => e.id === next.experimentId)?.name
    || (state.value.status.experimentId === next.experimentId ? state.value.status.experimentName : null)
    || `experiment #${next.experimentId}`
  const turningOn = next.enabled && (!prev.enabled || prev.experimentId !== next.experimentId || state.value.source === 'off')
  const each = next.continuous ? ', and a new one each time a session ends' : ''
  const other = holder.value
  if (takeOver && other) {
    const ok = await confirm({
      title: `End “${other.name || `Session #${other.id}`}” and run “${name}”?`,
      message: `${describeEngineSession(other)} is running on the script engine, which every researcher on this server shares. ` +
        `Saving now ends that session and disconnects everyone in it, then opens a session of “${name}”${each}.` +
        '\n\nTo let it finish instead, use Save: the autopilot then waits for it to end.',
      confirmLabel: 'End it and save',
      danger: true,
    })
    if (!ok) return
  } else if (turningOn) {
    const ok = await confirm({
      title: `Run “${name}” unattended?`,
      message: [
        other
          ? `${describeEngineSession(other)} is running on the script engine, so Breadboard waits for it to end, then opens a session of “${name}”${each}.`
          : `Breadboard opens a session of “${name}” right away if none is open${each}.`,
        'Participants join through the links on this page.',
      ].join(' '),
      confirmLabel: 'Turn on and save',
    })
    if (!ok) return
  }
  const ending = takeOver && !!other
  saving.value = true
  try {
    const saved = await saveAutopilot(next, { takeOver: ending })
    adopt(saved, true)
    const waitingFor = saved.status.engineSession
    if (saved.settings.enabled && !ending && waitingFor && waitingFor.experimentId !== saved.settings.experimentId) {
      notify.success('The autopilot is on', `It opens a session of “${name}” once “${waitingFor.name || `Session #${waitingFor.id}`}” ends.`)
    } else if (saved.settings.enabled && wasDone && !saved.settings.continuous) {
      notify.success('Opening another session', `The autopilot opens one more session of “${name}”. Share the participant links on this page.`)
    } else if (saved.settings.enabled) {
      notify.success(wasPaused || wasDone ? 'The autopilot is running again' : 'The autopilot is on', `“${name}” runs unattended. Share the participant links on this page.`)
    } else if (prev.enabled) {
      notify.success('The autopilot is off', 'No new sessions will open. A session that is already open keeps running.')
    } else {
      notify.success('Autopilot settings saved')
    }
  } catch (e) {
    if (e instanceof HttpError && e.status === 404) {
      phase.value = 'unavailable'
      return
    }
    saveError.value = `Not saved. ${describeError(e)}`
    notify.error('Autopilot settings not saved', describeError(e))
  } finally {
    saving.value = false
  }
}

useUnsavedGuard(dirty, {
  what: 'the autopilot settings',
  // like the main button: also resumes a paused autopilot, or opens another session once it is done
  save: () => { if (canSave.value) submit() },
  // signing out swaps the whole console for the sign-in page, so no route guard runs: count it there too
  unsaved: { key: 'autopilot', label: 'Autopilot settings' },
})
</script>

<style>
.ap-loading { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 14vh 0; color: var(--muted); }
.ap-empty { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }

.ap-banner {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: var(--info-soft);
  color: var(--info-soft-text);
}
.ap-banner svg { flex: none; margin-top: 2px; }
.ap-banner__title { font-weight: 650; }
.ap-banner__text { margin-top: 2px; font-size: var(--text-sm); }
.ap-banner code { font-size: 0.95em; }

.ap-form { display: flex; flex-direction: column; gap: 14px; }
.ap-error { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.ap-sec {
  display: grid;
  grid-template-columns: minmax(0, 340px) minmax(0, 1fr);
  gap: 16px 32px;
  padding: 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
@media (max-width: 900px) { .ap-sec { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 640px) { .ap-sec { padding: 16px; } }
.ap-sec__head { display: flex; gap: 12px; align-items: flex-start; }
.ap-sec__icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); flex: none; }
.ap-sec__title { font-size: var(--text-md); font-weight: 650; }
.ap-sec__desc { margin-top: 4px; font-size: var(--text-sm); color: var(--muted); line-height: 1.5; }
.ap-sec__desc code { font-size: 0.92em; color: var(--text-soft); overflow-wrap: anywhere; }
.ap-sec__body { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.ap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 220px), 1fr)); gap: 16px 18px; align-items: start; }
.ap-unit { font-size: var(--text-xs); color: var(--faint); white-space: nowrap; }
.ap-exp__row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.ap-exp__row > .b-select { flex: 1; min-width: 200px; }
.ap-check { display: flex; flex-direction: column; gap: 6px; padding-top: 26px; }
.ap-check__hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.45; padding-left: 26px; }
@media (max-width: 520px) { .ap-check { padding-top: 0; } }
.ap-preview {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
  border: 1px solid var(--border);
  color: var(--text-soft);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.ap-preview svg { flex: none; margin-top: 2px; color: var(--info); }
.ap-holder__btn { margin-top: 8px; }
.ap-own {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--info-soft);
  color: var(--info-soft-text);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.ap-own code { overflow-wrap: anywhere; color: inherit; }
.ap-own svg { flex: none; margin-top: 2px; }
.ap-own.is-warn { background: var(--warning-soft); color: var(--warning-soft-text); }
.ap-params code { color: var(--text-soft); }
.ap-params.is-warn { background: var(--warning-soft); border-color: transparent; color: var(--warning-soft-text); }
.ap-params.is-warn svg { color: inherit; }

.ap-savebar {
  position: relative;
  bottom: 12px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px 10px 16px;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
}
/* stays in view only while there is something to save */
.ap-savebar.is-dirty { position: sticky; border-color: var(--accent); }
.ap-savebar:not(.is-dirty) { bottom: 0; box-shadow: none; background: transparent; border-color: transparent; padding-left: 4px; padding-right: 0; }
.ap-savebar__text { flex: 1; min-width: 0; display: inline-flex; align-items: center; gap: 8px; font-size: var(--text-sm); color: var(--muted); }
.ap-savebar.is-dirty .ap-savebar__text { color: var(--text); font-weight: 600; }
.ap-savebar__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); flex: none; }
</style>
