<template>
  <div class="ch">
    <BEmpty
      v-if="!admin.experiment"
      :icon="FlaskConical"
      title="Choose an experiment first"
      text="A HIT invites workers into a running session of one experiment. Pick the experiment in the switcher at the top left."
    >
      <template #actions>
        <BButton variant="primary" @click="router.push('/experiments')">Open Experiments</BButton>
      </template>
    </BEmpty>

    <div v-else class="ch__layout">
      <div class="ch__main">
        <BEmpty
          v-if="!isRunning"
          :icon="CirclePlay"
          title="Start a session first"
          :text="`A HIT sends workers straight into a running session of ${admin.experiment.name}. Start one on the Live session page, then come back here to post the HIT.`"
        >
          <template #actions>
            <BButton variant="primary" :icon="Activity" @click="router.push('/session')">Go to Live session</BButton>
          </template>
        </BEmpty>

        <form v-else class="rc-stack" novalidate @submit.prevent="submit">
          <div v-if="sessionHits.length" class="rc-note rc-note--info">
            <Info :size="16" aria-hidden="true" />
            <span>This session already has {{ sessionHits.length === 1 ? 'a HIT' : `${sessionHits.length} HITs` }}. Posting another adds more places, for example when the first one filled up.</span>
          </div>

          <fieldset class="ch__fieldset" :disabled="busy">
            <BPanel title="What workers see" subtitle="Shown in MTurk’s list of HITs.">
              <div class="rc-form-grid">
                <BField id="ch-title" label="Title" class="is-wide" :error="err.title" required hint="Short and specific, e.g. “Group decision game (about 20 minutes)”.">
                  <template #default="{ id, describedby }">
                    <BInput :id="id" v-model="form.title" maxlength="128" :aria-describedby="describedby" :aria-invalid="err.title ? 'true' : undefined" />
                  </template>
                </BField>
                <BField id="ch-description" label="Description" class="is-wide" :error="err.description" required hint="What the task is and roughly how long it takes.">
                  <template #default="{ id, describedby }">
                    <textarea :id="id" v-model="form.description" class="b-textarea" rows="3" maxlength="2000" :aria-describedby="describedby" :aria-invalid="err.description ? 'true' : undefined" />
                  </template>
                </BField>
                <BField id="ch-keywords" label="Keywords" class="is-wide" :error="err.keywords" required hint="Words workers might search for, separated by commas.">
                  <template #default="{ id, describedby }">
                    <BInput :id="id" v-model="form.keywords" placeholder="game, study, decision making" :aria-describedby="describedby" :aria-invalid="err.keywords ? 'true' : undefined" />
                  </template>
                </BField>
              </div>
            </BPanel>

            <BPanel title="Pay and places">
              <div class="rc-form-grid">
                <BField id="ch-reward" label="Reward per worker ($)" :error="err.reward" required hint="Paid when you approve a submission. Bonuses come on top.">
                  <template #default="{ id, describedby }">
                    <BInput :id="id" v-model="form.reward" inputmode="decimal" :aria-describedby="describedby" :aria-invalid="err.reward ? 'true' : undefined" />
                  </template>
                </BField>
                <BField id="ch-places" label="Places" :error="err.maxAssignments" required hint="How many workers can take part (MTurk calls these assignments).">
                  <template #default="{ id, describedby }">
                    <BInput :id="id" v-model="form.maxAssignments" type="number" min="1" step="1" :aria-describedby="describedby" :aria-invalid="err.maxAssignments ? 'true' : undefined" />
                  </template>
                </BField>
              </div>
              <p class="ch__estimate" aria-live="polite">
                <template v-if="cost">
                  At most <strong>{{ formatMoney(cost.rewards) }}</strong> in rewards plus about
                  <strong>{{ formatMoney(cost.fee) }}</strong> in MTurk fees ({{ cost.feePct }}%{{ form.maxAssignments && form.maxAssignments >= 10 ? ', higher from 10 places' : '' }}):
                  <strong>{{ formatMoney(cost.total) }}</strong>, plus any bonuses.
                </template>
                <template v-else>Enter a reward and the number of places to see the cost.</template>
              </p>
            </BPanel>

            <BPanel title="Timing">
              <div class="rc-form-grid">
                <BField id="ch-lifetime" label="Open for" :error="err.lifetime" required hint="How long workers can find and accept the HIT.">
                  <template #default="{ id, describedby }">
                    <DurationInput :id="id" v-model="form.lifetime" :describedby="describedby" unit-label="Unit for “open for”" />
                  </template>
                </BField>
                <BField id="ch-tutorial" label="Tutorial time" :error="err.tutorialTime" required hint="Extra time to read the instructions before the game starts.">
                  <template #default="{ id, describedby }">
                    <DurationInput :id="id" v-model="form.tutorialTime" :describedby="describedby" unit-label="Unit for tutorial time" />
                  </template>
                </BField>
                <BField id="ch-duration" label="Time to finish" :error="err.assignmentDuration" required hint="How long each worker has, from accepting to submitting.">
                  <template #default="{ id, describedby }">
                    <DurationInput :id="id" v-model="form.assignmentDuration" :describedby="describedby" unit-label="Unit for time to finish" />
                  </template>
                </BField>
                <div class="ch__switch">
                  <BSwitch v-model="form.autoLaunch">Start the game by itself</BSwitch>
                  <p class="rc-sub">Turns newcomers away when the HIT closes and runs <code>initStep.start()</code> after the tutorial time.</p>
                  <p v-if="err.autoLaunch" class="rc-error-text" role="alert">{{ err.autoLaunch }}</p>
                </div>
              </div>
              <ol class="ch__timeline" aria-label="What happens after you post">
                <li><span class="ch__dot" aria-hidden="true" /><strong>Now</strong> — the HIT appears on MTurk; workers accept it and wait in the session.</li>
                <li v-if="form.autoLaunch">
                  <span class="ch__dot" aria-hidden="true" /><strong>After {{ humanDuration(form.lifetime) }}</strong> — the HIT closes; newcomers are turned away.
                </li>
                <li v-if="form.autoLaunch">
                  <span class="ch__dot is-accent" aria-hidden="true" /><strong>After {{ humanDuration((form.lifetime || 0) + (form.tutorialTime || 0)) }}</strong> — the game starts by itself.
                </li>
                <li v-else>
                  <span class="ch__dot is-accent" aria-hidden="true" /><strong>When you’re ready</strong> — start the game yourself on Live session.
                </li>
                <li><span class="ch__dot" aria-hidden="true" /><strong>Each worker</strong> has {{ humanDuration(form.assignmentDuration) }} to finish and submit.</li>
              </ol>
            </BPanel>

            <BPanel title="Who can take part">
              <div class="rc-stack">
                <BField id="ch-repeat" label="Workers who took part before" hint="Breadboard checks the workers marked “Completed” on the HITs and Workers tabs, in both sandbox and production.">
                  <template #default="{ id, describedby }">
                    <BSelect :id="id" v-model="form.disallowPrevious" :options="REPEAT_OPTIONS" :aria-describedby="describedby" />
                  </template>
                </BField>
                <div>
                  <h4 class="ch__h">Requirements</h4>
                  <p class="rc-sub ch__h-sub">MTurk only lets workers who meet every requirement take the HIT.</p>
                  <RequirementsEditor v-model="form.requirements" :disabled="busy" />
                </div>
              </div>
            </BPanel>

            <BPanel title="Where workers go">
              <p class="rc-muted ch__url-text">
                MTurk shows workers this session inside its own page, at the server’s
                <code>breadboard.rootUrl</code> followed by:
              </p>
              <div class="ch__url">
                <span class="rc-mono rc-break">{{ entryPath }}</span>
                <CopyButton :text="entryUrl" size="sm" variant="ghost" label="Copy full address" />
              </div>
              <div v-if="insecureOrigin" class="rc-note rc-note--warning ch__url-warn">
                <TriangleAlert :size="16" aria-hidden="true" />
                <span>
                  MTurk only opens public <strong>https</strong> addresses. This console is at
                  <span class="rc-mono">{{ origin }}</span>, so make sure <code>breadboard.rootUrl</code>
                  is set to the server’s public https address (in its settings file,
                  <code>conf/local.conf</code>), or MTurk will refuse the HIT.
                </span>
              </div>
            </BPanel>
          </fieldset>

          <ErrorNote v-if="formError" title="The HIT was not posted" :message="formError.message" :detail="formError.detail" />

          <div class="ch__actions">
            <BButton variant="ghost" :icon="Eraser" :disabled="busy" @click="clearForm">Clear form</BButton>
            <span class="rc-spacer" />
            <span v-if="tried && errorCount" class="rc-error-text" role="status">{{ errorCount === 1 ? 'One field needs' : `${errorCount} fields need` }} attention.</span>
            <BBadge :tone="isProduction ? 'danger' : 'info'" dot>{{ isProduction ? 'Production' : 'Sandbox' }}</BBadge>
            <BButton type="submit" :variant="isProduction ? 'danger' : 'primary'" :icon="Send" :loading="busy">
              {{ isProduction ? 'Post HIT to production' : 'Post HIT to sandbox' }}
            </BButton>
          </div>
        </form>
      </div>

      <aside class="ch__side">
        <section class="ch__session" aria-label="Session">
          <h4 class="ch__h">Session</h4>
          <p class="rc-strong">{{ admin.experiment.name }}</p>
          <p v-if="admin.instance" class="ch__session-line">
            <BBadge :tone="isRunning ? 'success' : 'neutral'" dot :pulse="isRunning">{{ statusLabel }}</BBadge>
            <span>{{ admin.instance.name }}</span>
          </p>
          <p v-else class="rc-muted">No session selected.</p>
          <p class="rc-sub">Workers from a HIT join this session. Posting HITs works in the session that is open on Live session.</p>
        </section>
        <CreatedHits v-if="sessionHits.length" ref="createdRef" :hits="sessionHits" @view-hits="emit('view-hits')" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, CirclePlay, Eraser, FlaskConical, Info, Send, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BField, BInput, BPanel, BSelect, BSwitch, notify, toast } from '@/ui'
import { actions, admin, connectionState } from '@/store/admin'
import CopyButton from '../CopyButton.vue'
import ErrorNote from '../ErrorNote.vue'
import DurationInput from './DurationInput.vue'
import RequirementsEditor from './RequirementsEditor.vue'
import CreatedHits from './CreatedHits.vue'
import { track, type CreatedHit, type MturkApi } from './api'
import { isProduction } from './mode'
import { confirmMoney } from './moneyConfirm'
import { scheduleAutoStart } from './autoLaunch'
import {
  buildRequirement, describeError, describeRequirement, formatMoney, humanDuration, mturkFeeRate, parseMoney, toCents, validateRequirement,
  type ErrorText, type WireRequirement,
} from './lib'
import { clearHitForm, defaultHitForm, hitFormBody, loadHitForm, saveHitForm, validateHitForm, type HitForm, type HitFormErrors } from './hitForm'

const props = defineProps<{ api: MturkApi }>()
const emit = defineEmits<{ 'view-hits': [] }>()
const router = useRouter()

const REPEAT_OPTIONS = [
  { value: 'type', label: 'Turn away anyone who completed this experiment' },
  { value: 'any', label: 'Turn away anyone who completed any Breadboard experiment' },
  { value: 'none', label: 'Let everyone take part again' },
]
const FIELD_IDS: Partial<Record<keyof HitForm, string>> = {
  title: 'ch-title', description: 'ch-description', keywords: 'ch-keywords', reward: 'ch-reward', maxAssignments: 'ch-places',
  lifetime: 'ch-lifetime', tutorialTime: 'ch-tutorial', assignmentDuration: 'ch-duration',
}

const form = reactive<HitForm>(loadHitForm())
const busy = ref(false)
const tried = ref(false)
const formError = ref<ErrorText | null>(null)
const justCreated = ref<CreatedHit | null>(null)
const createdRef = ref<InstanceType<typeof CreatedHits> | null>(null)

const isRunning = computed(() => admin.instance?.status === 'RUNNING')
const statusLabel = computed(() => {
  const s = admin.instance?.status
  return s ? s.charAt(0) + s.slice(1).toLowerCase() : ''
})

const allErrors = computed<HitFormErrors>(() => validateHitForm(form))
const err = computed<HitFormErrors>(() => (tried.value ? allErrors.value : {}))
const errorCount = computed(() => Object.keys(allErrors.value).length)

const cost = computed(() => {
  const reward = parseMoney(form.reward)
  const places = form.maxAssignments
  if (reward == null || !places || places < 1) return null
  const masters = form.requirements.some(r => r.kind === 'masters' && r.comparator === 'Exists')
  const rate = mturkFeeRate(places, masters)
  const rewardsCents = toCents(reward) * places
  const feeCents = Math.round(rewardsCents * rate)
  return { rewards: rewardsCents / 100, fee: feeCents / 100, total: (rewardsCents + feeCents) / 100, feePct: Math.round(rate * 100) }
})

const origin = window.location.origin
const entryPath = computed(() => `/game/${admin.experiment?.id}/${admin.instance?.id}/amt`)
const entryUrl = computed(() => origin + entryPath.value)
const insecureOrigin = /^http:/i.test(origin) || /\/\/(localhost|127\.0\.0\.1|\[::1\])(:|$)/i.test(origin)

/** The HITs posted for the selected session (from the server, plus one just posted). */
const sessionHits = computed<CreatedHit[]>(() => {
  const list = ((admin.instance?.hits || []) as CreatedHit[]).filter(h => h && h.hitId)
  const extra = justCreated.value
  return extra && !list.some(h => h.hitId === extra.hitId) ? [...list, extra] : list
})
watch(() => admin.instance?.id, () => { justCreated.value = null })

// Remember the form while it is edited, so leaving the page loses nothing.
let saveTimer: ReturnType<typeof setTimeout> | null = null
watch(form, () => {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => saveHitForm(form), 400)
}, { deep: true })

function assign (f: HitForm) {
  Object.assign(form, { ...f, requirements: f.requirements.map(r => ({ ...r, locales: r.locales.map(l => ({ ...l })) })) })
}

function clearForm () {
  const before: HitForm = JSON.parse(JSON.stringify(form))
  assign(defaultHitForm())
  clearHitForm()
  tried.value = false
  formError.value = null
  toast('info', 'Form cleared', 'Back to the standard settings.', { action: { label: 'Undo', run: () => assign(before) } })
}

async function focusFirstError (errors: HitFormErrors) {
  await nextTick()
  const first = (Object.keys(FIELD_IDS) as (keyof HitForm)[]).find(k => errors[k])
  const el = first ? document.getElementById(FIELD_IDS[first]!) : null
  if (!el) return
  el.focus()
  const still = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center', behavior: still ? 'auto' : 'smooth' })
}

async function submit () {
  tried.value = true
  formError.value = null
  const errors = allErrors.value
  if (Object.keys(errors).length) { focusFirstError(errors); return }
  for (const d of form.requirements) {
    const problem = Object.values(validateRequirement(d))[0]
    if (problem) { formError.value = { message: `Fix the requirement “${describeRequirement(d)}”: ${problem}` }; return }
  }
  const exp = admin.experiment
  const inst = admin.instance
  if (!exp || !inst || inst.status !== 'RUNNING') {
    formError.value = { message: 'The session stopped. Start a session on Live session and try again.' }
    return
  }
  if (form.autoLaunch && connectionState.value !== 'open') {
    // the automatic start is asked for over the connection, right after posting
    formError.value = { message: 'Not connected to the server, so the game couldn’t be set to start by itself. Wait until the connection is back (see the top bar), or turn off “Start the game by itself”.' }
    return
  }
  if (!props.api.sandbox && cost.value) {
    const ok = await confirmMoney({
      title: 'Post this HIT to production?',
      message: `Real workers will see “${form.title.trim()}” and can start at once. You pay each worker when you approve their submission.`,
      lines: [
        { label: `${form.maxAssignments} places × ${formatMoney(parseMoney(form.reward))} reward`, amount: cost.value.rewards },
        { label: `MTurk fee (about ${cost.value.feePct}%)`, amount: cost.value.fee },
      ],
      total: cost.value.total,
      totalLabel: 'Most you pay, before bonuses',
      footnote: 'Bonuses are paid separately, on the HITs tab.',
      confirmLabel: 'Post HIT',
    })
    if (!ok) return
  }

  busy.value = true
  try {
    const wire: WireRequirement[] = []
    for (const d of form.requirements) {
      const typeId = d.kind === 'experiment' ? await props.api.experimentTypeId(d.experimentUid.trim()) : null
      wire.push(buildRequirement(d, { sandbox: props.api.sandbox, experimentTypeId: typeId }))
    }
    const body = hitFormBody(form, { experimentId: exp.id, instanceId: inst.id, requirements: wire })
    const created = await track(() => props.api.createHit(body))
    saveHitForm(form)
    tried.value = false
    if (created && created.hitId) {
      justCreated.value = created
      notify.success('HIT posted', `“${body.title}” is on the ${props.api.sandbox ? 'sandbox' : 'production'} site.`)
    } else {
      notify.warning('HIT posted', 'The server didn’t send its details. Open the HITs tab to see it.')
    }
    // the HIT exists once the server says OK, so the automatic start is asked for either way
    if (form.autoLaunch) {
      const started = await scheduleAutoStart(created?.hitId || '', body.hitLifetime, body.tutorialTime)
      if (!started.ok) {
        toast('warning', 'The game won’t start by itself',
          `${started.reason} The HIT is posted, so start the game yourself on Live session once enough workers have joined.`,
          { timeout: 0 })
      }
    }
    await nextTick()
    createdRef.value?.reload()
    // the session's list of HITs arrives with the next User JSON
    actions.refresh()
  } catch (e) {
    formError.value = describeError(e)
  } finally {
    busy.value = false
  }
}
</script>

<style>
.ch__layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 20px; align-items: start; }
.ch__main { min-width: 0; }
.ch__side { display: flex; flex-direction: column; gap: 16px; position: sticky; top: 0; min-width: 0; }
@media (max-width: 1100px) {
  .ch__layout { grid-template-columns: minmax(0, 1fr); }
  .ch__side { position: static; }
}
.ch__fieldset { border: 0; margin: 0; padding: 0; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.ch__estimate { margin-top: 14px; font-size: var(--text-sm); color: var(--muted); }
.ch__estimate strong { color: var(--text); font-variant-numeric: tabular-nums; }
.ch__switch { display: flex; flex-direction: column; gap: 6px; justify-content: center; }
.ch__timeline { list-style: none; margin: 16px 0 0; padding: 12px 14px; border-radius: var(--radius-sm); background: var(--surface-sunken); display: flex; flex-direction: column; gap: 6px; font-size: var(--text-sm); color: var(--text-soft); }
.ch__timeline li { display: flex; align-items: baseline; gap: 10px; }
.ch__dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); transform: translateY(-1px); }
.ch__dot.is-accent { background: var(--accent); }
.ch__h { font-size: var(--text-md); }
.ch__h-sub { margin: 2px 0 10px; }
.ch__url-text { font-size: var(--text-sm); }
.ch__url { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; padding: 8px 8px 8px 12px; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--surface-sunken); }
.ch__url-warn { margin-top: 10px; }
.ch__actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-raised);
  box-shadow: var(--shadow);
}
.ch__session { padding: 16px 18px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); display: flex; flex-direction: column; gap: 6px; }
.ch__session-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: var(--text-sm); }
</style>
