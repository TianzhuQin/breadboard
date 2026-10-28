<template>
  <BDialog
    :open="open"
    title="New session"
    subtitle="A session is one run of the experiment. It opens at once, and people can join with its links."
    size="lg"
    :dismissable="!busy && !checking && !dirty"
    :closable="!busy && !checking"
    @update:open="(v) => !v && close()"
  >
    <form :id="formId" class="nsd" novalidate @submit.prevent="submit">
      <div v-if="replacing" class="nsd-warn" role="note">
        <TriangleAlert :size="16" aria-hidden="true" />
        <p>
          <b>“{{ replacing.name }}” is running.</b>
          Creating a new session stops it, restarts the experiment's scripts and disconnects everyone in it.
        </p>
      </div>

      <BField label="Session name" :id="`${formId}-name`" :error="showNameError ? nameError : null" required hint="Shown in your session list and in the data file's name.">
        <template #default="{ id, describedby }">
          <BInput
            :id="id"
            v-model="name"
            :aria-describedby="describedby"
            :aria-invalid="showNameError && nameError ? 'true' : undefined"
            autocomplete="off"
            maxlength="255"
            data-autofocus
            @blur="touched.add('__name')"
          />
        </template>
      </BField>

      <section class="nsd-params" :aria-labelledby="`${formId}-ph`">
        <header class="nsd-params__head">
          <div>
            <h3 :id="`${formId}-ph`" class="nsd-params__title">Launch parameters</h3>
            <p class="nsd-params__sub">
              {{ fields.length ? 'Your scripts read these as variables. They are saved with the session and can\'t change while it runs.' : 'This experiment has no launch parameters.' }}
              <template v-if="fields.length && filledFrom"> Filled in as in “{{ filledFrom }}”, the last session.</template>
            </p>
          </div>
          <BButton v-if="fields.length && changedFromDefaults" size="sm" variant="ghost" :icon="RotateCcw" @click="resetAll">Reset to defaults</BButton>
        </header>

        <p v-if="!fields.length" class="nsd-none">
          Parameters let you vary conditions between sessions, e.g. the number of rounds.
          <RouterLink to="/parameters" @click="close">Add them on the Parameters page</RouterLink>.
        </p>

        <div v-else class="nsd-grid">
          <div v-for="(f, i) in fields" :key="f.name" class="nsd-param" :class="{ 'has-error': !!shownError(f) }">
            <template v-if="f.type === 'Boolean'">
              <div class="nsd-param__head">
                <span class="nsd-param__name">{{ f.name }}</span>
                <span class="nsd-param__type">{{ TYPE_LABEL[f.type] }}</span>
              </div>
              <p v-if="f.description" :id="`${formId}-p${i}-desc`" class="nsd-param__desc">{{ f.description }}</p>
              <BSwitch :model-value="values[f.name] === true" @update:model-value="(v: boolean) => values[f.name] = v">
                <span class="sr-only">{{ f.name }}</span>
                <span aria-hidden="true">{{ values[f.name] === true ? 'Yes (true)' : 'No (false)' }}</span>
              </BSwitch>
              <p class="nsd-param__hint">Default: {{ f.initial === true ? 'yes' : 'no' }}</p>
            </template>

            <template v-else>
              <div class="nsd-param__head">
                <label :for="`${formId}-p${i}`" class="nsd-param__name">{{ f.name }}</label>
                <span class="nsd-param__type">{{ TYPE_LABEL[f.type] }}</span>
              </div>
              <p v-if="f.description" :id="`${formId}-p${i}-desc`" class="nsd-param__desc">{{ f.description }}</p>
              <BInput
                :id="`${formId}-p${i}`"
                :model-value="String(values[f.name] ?? '')"
                :inputmode="f.type === 'Integer' ? 'numeric' : f.type === 'Decimal' ? 'decimal' : undefined"
                :aria-describedby="describedBy(f, i)"
                :aria-invalid="shownError(f) ? 'true' : undefined"
                :class="{ mono: f.type !== 'Text' }"
                autocomplete="off"
                spellcheck="false"
                @update:model-value="(v: string) => values[f.name] = v"
                @blur="touched.add(f.name)"
              />
              <p v-if="shownError(f)" :id="`${formId}-p${i}-err`" class="nsd-param__error">{{ shownError(f) }}</p>
              <p :id="`${formId}-p${i}-hint`" class="nsd-param__hint">
                <span v-if="rangeText(f.min, f.max)">{{ rangeText(f.min, f.max) }} · </span>
                <span>Default: {{ f.defaultRaw.trim() ? f.defaultRaw : 'none' }}</span>
                <button
                  v-if="isChanged(f) && !f.defaultProblem"
                  type="button"
                  class="nsd-param__reset"
                  @click="values[f.name] = f.initial"
                >Use default</button>
              </p>
            </template>
          </div>
        </div>
      </section>

      <div v-if="failure" class="nsd-fail" role="alert">
        <CircleAlert :size="16" aria-hidden="true" />
        <div>
          <p class="nsd-fail__title">{{ failure }}</p>
          <p class="nsd-fail__text">The experiment's scripts may have failed to load. The output panel shows what the engine said.</p>
        </div>
        <BButton size="sm" :icon="PanelBottom" @click="openOutput = true">Open output</BButton>
      </div>
      <p class="sr-only" role="status" aria-live="polite">{{ announce }}</p>
    </form>

    <template #footer>
      <p class="nsd-foot-note">Starts right away.</p>
      <BButton variant="ghost" :disabled="busy || checking" @click="close">Cancel</BButton>
      <BButton type="submit" :form="formId" :variant="replacing ? 'danger' : 'primary'" :loading="busy || checking">
        {{ busy ? 'Creating…' : replacing ? 'Stop it and create' : 'Create session' }}
      </BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { RotateCcw, TriangleAlert, CircleAlert, PanelBottom } from 'lucide-vue-next'
import { BButton, BDialog, BField, BInput, BSwitch, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { admin, actions, ensureConnected, instances } from '@/store/admin'
import { confirmEngineRebuild } from '@/store/engineGuard'
import { openOutput } from '@/shell/output'
import { stepsCall } from '../autopilot/autopilot'
import {
  TYPE_LABEL, buildFields, buildLaunchParams, checkParam, checkSessionName, fieldErrors, rangeText, suggestName,
  type FieldValue, type ParamField,
} from './params'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean], created: [name: string] }>()

const formId = uid('nsd')
const name = ref('')
const initialName = ref('')
/** The session whose values the fields started with ('' for the defaults). */
const filledFrom = ref('')
const startValues: Record<string, any> = reactive({})
const fields = ref<ParamField[]>([])
const values = reactive<Record<string, FieldValue>>({})
const touched = reactive(new Set<string>())
const submitted = ref(false)
const busy = ref(false)
/**
 * Checking who uses the engine (the server can take a few seconds to say), then asking whether to
 * rebuild it. Shown on the Create button; a second submit meanwhile must not launch twice.
 */
const checking = ref(false)
const failure = ref('')
const announce = ref('')
/** The experiment whose parameters the form shows: the one selected when the dialog opened. */
let openedFor: number | undefined

const existingNames = computed(() => instances.value.map(i => i.name))
const replacing = computed(() => {
  const i = admin.instance
  return i && (i.status === 'RUNNING' || i.status === 'TESTING') ? i : null
})

function reset () {
  openedFor = admin.experiment?.id
  fields.value = buildFields(admin.experiment?.parameters)
  for (const k of Object.keys(values)) delete values[k]
  for (const f of fields.value) values[f.name] = f.initial
  // back-to-back runs usually keep the same values: start from the last session's
  filledFrom.value = fillFromLast()
  for (const k of Object.keys(startValues)) delete startValues[k]
  for (const f of fields.value) startValues[f.name] = values[f.name]
  touched.clear()
  // a stored default that can't be used is pointed out at once, not after the first submit
  for (const f of fields.value) if (f.defaultProblem) touched.add(f.name)
  name.value = suggestName(existingNames.value)
  initialName.value = name.value
  submitted.value = false
  failure.value = ''
  announce.value = ''
}
watch(() => props.open, (v) => { if (v) reset() }, { immediate: true })
// the experiment changed under the dialog: its parameters are someone else's now
watch(() => admin.experiment?.id, () => { if (props.open && !busy.value) close() })

const nameError = computed(() => checkSessionName(name.value, existingNames.value))
const showNameError = computed(() => submitted.value || touched.has('__name'))
const errors = computed(() => fieldErrors(fields.value, values))

function shownError (f: ParamField): string | null {
  const e = errors.value[f.name]
  if (!e) return null
  if (f.defaultProblem && values[f.name] === f.initial) return f.defaultProblem
  return submitted.value || touched.has(f.name) ? e : null
}
function describedBy (f: ParamField, i: number) {
  const ids = []
  if (shownError(f)) ids.push(`${formId}-p${i}-err`)
  if (f.description) ids.push(`${formId}-p${i}-desc`)
  ids.push(`${formId}-p${i}-hint`)
  return ids.join(' ')
}
function isChanged (f: ParamField) {
  const v = values[f.name]
  return typeof v === 'string' && typeof f.initial === 'string' ? v.trim() !== f.initial.trim() : v !== f.initial
}
const changedFromDefaults = computed(() => fields.value.some(isChanged))
// (changed from what the dialog opened with, which may be the last session's values)
const dirty = computed(() => name.value !== initialName.value || fields.value.some(f => values[f.name] !== startValues[f.name]))

/** Put the last session's values in the fields (those still valid); its name, or '' when none. */
function fillFromLast (): string {
  const last = [...instances.value].filter(i => Array.isArray(i.data) && i.data.length)
    .sort((a, b) => b.id - a.id)[0]
  if (!last) return ''
  let used = false
  for (const f of fields.value) {
    const d = (last.data || []).find(x => x.name === f.name)
    if (!d) continue
    const c = checkParam(f, f.type === 'Boolean' ? String(d.value).trim().toLowerCase() === 'true' : String(d.value ?? ''))
    if (c.error) continue
    const v: FieldValue = f.type === 'Boolean' ? c.value === true : String(d.value ?? '')
    if (v !== values[f.name]) used = true
    values[f.name] = v
  }
  return used ? last.name : ''
}

function resetAll () {
  for (const f of fields.value) values[f.name] = f.initial
}

function close () {
  if (busy.value) return
  emit('update:open', false)
}

async function focusFirstProblem () {
  await nextTick()
  if (nameError.value) {
    document.getElementById(`${formId}-name`)?.focus()
    return
  }
  const i = fields.value.findIndex(f => !!errors.value[f.name])
  if (i >= 0) document.getElementById(`${formId}-p${i}`)?.focus()
}

async function submit () {
  if (busy.value || checking.value) return
  submitted.value = true
  failure.value = ''
  const built = buildLaunchParams(fields.value, values)
  const problems = (nameError.value ? 1 : 0) + (built.ok ? 0 : Object.keys(built.errors).length)
  if (problems || !built.ok) {
    announce.value = problems === 1 ? 'One field needs your attention.' : `${problems} fields need your attention.`
    await focusFirstProblem()
    return
  }
  const sessionName = name.value.trim()
  if (!ensureConnected()) return
  // the form holds the parameters of the experiment it opened for: never launch them for another one
  const experimentId = openedFor
  if (admin.experiment?.id !== experimentId) { close(); return }
  // a launch rebuilds the engine every account and the autopilot share: say whose session and
  // players it disconnects (no question when nobody is using it)
  checking.value = true
  let go = false
  try {
    go = await confirmEngineRebuild({
      title: `Create “${sessionName}” now?`,
      action: 'Creating a session',
      confirmLabel: replacing.value ? 'Stop it and create' : 'Create anyway',
    })
  } finally {
    checking.value = false
  }
  // closed meanwhile (another experiment was selected), or the connection dropped while the question was open
  if (!go || !props.open || admin.experiment?.id !== experimentId || !ensureConnected()) return
  busy.value = true
  announce.value = 'Creating the session…'
  try {
    const askedAt = Date.now()
    // (the server refuses it when the account has selected another experiment since, e.g. in another tab)
    const m = await actions.launch(sessionName, built.params, experimentId)
    const ok = !!m || (admin.instance?.name === sessionName && admin.instance.status === 'RUNNING')
    if (!ok) {
      const refused = admin.requestError?.request === 'LaunchGame' && admin.requestError.at >= askedAt ? admin.requestError.text : ''
      failure.value = refused ? `The server refused: ${refused}` : 'The server did not confirm the new session.'
      announce.value = failure.value
      return
    }
    busy.value = false
    emit('update:open', false)
    emit('created', sessionName)
    notify.success(`“${sessionName}” is open`, stepsCall(admin.experiment?.steps, 'waitingRoom')
      ? 'People can join with its links now. Its waiting room starts the game once enough are here.'
      : 'People can join with its links now. Start the game when everyone is here.')
  } finally {
    busy.value = false
  }
}
</script>

<style>
.nsd { display: flex; flex-direction: column; gap: 22px; }
.nsd-warn,
.nsd-fail {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 13px;
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.nsd-warn { background: var(--warning-soft); color: var(--warning-soft-text); }
.nsd-warn svg, .nsd-fail svg { flex: none; margin-top: 2px; }
.nsd-warn b { font-weight: 650; }
.nsd-fail { background: var(--danger-soft); color: var(--danger-soft-text); align-items: center; flex-wrap: wrap; }
.nsd-fail > div { flex: 1; min-width: 200px; }
.nsd-fail__title { font-weight: 650; }
.nsd-fail__text { color: var(--text-soft); }

.nsd-params { display: flex; flex-direction: column; gap: 14px; }
.nsd-params__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-top: 18px; border-top: 1px solid var(--border); }
.nsd-params__title { font-size: var(--text-md); font-weight: 650; }
.nsd-params__sub { margin-top: 2px; font-size: var(--text-sm); color: var(--muted); }
.nsd-none { font-size: var(--text-sm); color: var(--muted); padding: 14px 16px; background: var(--surface-sunken); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); }

.nsd-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
.nsd-param {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px 13px;
  background: var(--surface-sunken);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.nsd-param.has-error { border-color: var(--danger); }
.nsd-param.has-error .b-input { border-color: var(--danger); }
.nsd-param__head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; min-width: 0; }
.nsd-param__name {
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.nsd-param__type { flex: none; font-size: 11px; font-weight: 600; letter-spacing: 0.02em; color: var(--faint); text-transform: uppercase; }
.nsd-param__desc { font-size: var(--text-xs); color: var(--muted); line-height: 1.45; }
.nsd-param__hint { font-size: var(--text-xs); color: var(--muted); display: flex; flex-wrap: wrap; align-items: baseline; gap: 0 4px; }
.nsd-param__error { font-size: var(--text-xs); color: var(--danger); line-height: 1.45; }
.nsd-param__reset {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: none;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 600;
  cursor: pointer;
}
.nsd-param__reset:hover { text-decoration: underline; }
.nsd-foot-note { margin-right: auto; font-size: var(--text-xs); color: var(--muted); }
@media (max-width: 520px) {
  .nsd-foot-note { display: none; }
}
</style>
