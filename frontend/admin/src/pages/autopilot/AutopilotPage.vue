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
        @refresh="refresh(true)"
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

      <form ref="formEl" class="ap-form" novalidate @submit.prevent="save">
        <p v-if="saveError" class="ap-error" role="alert">{{ saveError }}</p>

        <!-- On / off and which experiment -->
        <section class="ap-sec" aria-labelledby="ap-sec-run">
          <div class="ap-sec__head">
            <span class="ap-sec__icon" aria-hidden="true"><Bot :size="17" /></span>
            <div>
              <h2 id="ap-sec-run" class="ap-sec__title">Run unattended</h2>
              <p class="ap-sec__desc">
                While it's on, Breadboard keeps a session of this experiment open: right after saving, when the server
                starts, and after each session ends. Turning it off stops new sessions; one that is open keeps running.
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
                People who arrive early see a waiting page until enough have joined. This replaces any
                <code>autopilot.waitingRoom(…)</code> call in the experiment's steps.
              </p>
            </div>
          </div>
          <div class="ap-sec__body">
            <BSwitch v-model="form.waitingRoom.enabled" :disabled="readOnly">Start the game automatically when enough people are here</BSwitch>
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
                Keep the game going when someone closes their page: the computer plays their seat. This replaces any
                <code>autopilot.replaceDisconnected(…)</code> call in the experiment's steps.
              </p>
            </div>
          </div>
          <div class="ap-sec__body">
            <BSwitch v-model="form.replaceDisconnected.enabled" :disabled="readOnly">Let the computer play for people who leave</BSwitch>
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

        <div v-if="!readOnly" class="ap-savebar" :class="{ 'is-dirty': dirty }">
          <span class="ap-savebar__text" role="status">
            <template v-if="dirty"><span class="ap-savebar__dot" aria-hidden="true" /> Unsaved changes</template>
            <template v-else-if="state.source === 'off'">Not set up yet: these are the suggested settings.</template>
            <template v-else>All changes saved.</template>
          </span>
          <BButton v-if="dirty" variant="ghost" :icon="Undo2" :disabled="saving" @click="discard">Discard</BButton>
          <BButton type="submit" variant="primary" :icon="Save" :loading="saving" :disabled="!dirty && state.source !== 'off'">
            Save
          </BButton>
        </div>
      </form>
    </template>
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Bot, Hourglass, Info, Lock, RefreshCw, Repeat, Save, ServerCog, TriangleAlert, Undo2, UserRoundX,
} from 'lucide-vue-next'
import { BButton, BCheckbox, BEmpty, BField, BInput, BPage, BSelect, BSpinner, BSwitch, confirm, notify } from '@/ui'
import { HttpError } from '@/lib/http'
import { admin, experiments, graph, running } from '@/store/admin'
import { describeError } from '../experiments/experiments'
import { liveEventOf } from '../sessions/sessionData'
import AutopilotStatus from './AutopilotStatus.vue'
import { useUnsavedGuard } from './useUnsavedGuard'
import {
  autopilotActivity, describeWaitingRoom, fetchAutopilot, humanSeconds, participantLinks, sameForm, saveAutopilot,
  saveOpensSession, DEFAULTS, toForm, toSettings, validateAutopilot, waitingRoomLive,
  type AutopilotForm, type AutopilotState,
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
const dirty = computed(() => phase.value === 'ready' && !readOnly.value && !sameForm(form.value, baseline.value))
const errors = computed<Record<string, string>>(() => (readOnly.value ? {} : validateAutopilot(form.value)))
const roomOff = computed(() => readOnly.value || !form.value.waitingRoom.enabled)
const leaveOff = computed(() => readOnly.value || !form.value.replaceDisconnected.enabled)
const roomSummary = computed(() => describeWaitingRoom(form.value))

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
  return autopilotActivity(admin.events.filter(e => liveEventOf(e as { experimentInstanceId?: unknown }, inst.id, live)))
})

// ---- loading -----------------------------------------------------------------------------------

function adopt (s: AutopilotState, resetForm: boolean) {
  state.value = s
  if (resetForm) {
    form.value = toForm(s.settings)
    baseline.value = toForm(s.settings)
  } else if (!dirty.value && !sameForm(baseline.value, toForm(s.settings))) {
    // changed elsewhere (another admin, or the config) while nothing was being edited here
    form.value = toForm(s.settings)
    baseline.value = toForm(s.settings)
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

async function save () {
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
  const name = experiments.value.find(e => e.id === next.experimentId)?.name
    || (state.value.status.experimentId === next.experimentId ? state.value.status.experimentName : null)
    || `experiment #${next.experimentId}`
  const turningOn = next.enabled && (!prev.enabled || prev.experimentId !== next.experimentId || state.value.source === 'off')
  // Any save while it is on opens a session at once if none of its experiment is open, even when
  // the shared engine runs something else; that session then ends.
  const atRisk = saveOpensSession(next, state.value.status) && running.value && admin.instance ? admin.instance : null
  if (turningOn || atRisk) {
    const parts = turningOn
      ? [
          `Breadboard opens a session of “${name}” right away if none is open${next.continuous ? ', and a new one each time a session ends' : ''}.`,
          'Participants join through the links on this page.',
        ]
      : [`The autopilot is on and no session of “${name}” is open, so saving opens one right away.`]
    if (atRisk) {
      parts.push(`\n\n“${atRisk.name}” appears to be running right now. Opening a new session restarts the script engine, which ends that session and disconnects its participants.`)
    }
    const ok = await confirm({
      title: turningOn ? `Run “${name}” unattended?` : `Save and open a session of “${name}”?`,
      message: parts.join(' '),
      confirmLabel: turningOn ? 'Turn on and save' : 'Save and open a session',
      danger: !!atRisk,
    })
    if (!ok) return
  }
  saving.value = true
  try {
    const saved = await saveAutopilot(next)
    adopt(saved, true)
    if (saved.settings.enabled) {
      notify.success('The autopilot is on', `“${name}” runs unattended. Share the participant links on this page.`)
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

useUnsavedGuard(dirty, { what: 'the autopilot settings', save: () => { if (dirty.value || state.value?.source === 'off') save() } })
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
