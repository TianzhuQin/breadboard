<template>
  <BDialog
    :open="open"
    :title="editing ? `Edit ${editing.name}` : 'New parameter'"
    subtitle="Asked for each time a session is created. Scripts read it by its name."
    size="lg"
    :dismissable="!busy"
    @update:open="v => { if (!v) close() }"
  >
    <form :id="formId" class="prmd" novalidate @submit.prevent="submit">
      <div class="prmd__fields">
        <BField label="Name" required :error="shown('name')" :hint="nameHint">
          <template #default="{ id, describedby }">
            <BInput
              :id="id"
              v-model="draft.name"
              data-field="name"
              class="mono"
              placeholder="e.g. nRounds"
              autocomplete="off"
              spellcheck="false"
              maxlength="100"
              data-autofocus
              :aria-describedby="describedby"
              :aria-invalid="!!shown('name')"
              @blur="touch('name')"
            />
          </template>
        </BField>

        <fieldset class="prmd__types" :aria-describedby="shown('type') ? `${formId}-type-err` : undefined">
          <legend class="prmd__legend">Type<span class="prmd__req" aria-hidden="true">*</span></legend>
          <div class="prmd__type-grid">
            <label v-for="t in PARAMETER_TYPES" :key="t.value" class="prmd__type" :class="{ 'is-on': draft.type === t.value }">
              <input
                type="radio"
                class="sr-only"
                :name="`${formId}-type`"
                :value="t.value"
                :checked="draft.type === t.value"
                @change="setType(t.value)"
              >
              <span class="prmd__type-icon" aria-hidden="true"><component :is="TYPE_ICONS[t.value]" :size="16" /></span>
              <span class="prmd__type-text">
                <span class="prmd__type-name">{{ t.label }}</span>
                <span class="prmd__type-meta"><span class="mono">{{ t.value }}</span> · {{ t.example }}</span>
              </span>
            </label>
          </div>
          <p v-if="shown('type')" :id="`${formId}-type-err`" class="prmd__err" role="alert">{{ shown('type') }}</p>
        </fieldset>

        <div v-if="numeric" class="prmd__range">
          <BField label="Smallest allowed" required :error="shown('minVal')">
            <template #default="{ id, describedby }">
              <BInput :id="id" v-model="draft.minVal" data-field="minVal" :inputmode="inputMode" autocomplete="off" placeholder="e.g. 1" :aria-describedby="describedby" :aria-invalid="!!shown('minVal')" @blur="touch('minVal')" />
            </template>
          </BField>
          <BField label="Largest allowed" required :error="shown('maxVal')">
            <template #default="{ id, describedby }">
              <BInput :id="id" v-model="draft.maxVal" data-field="maxVal" :inputmode="inputMode" autocomplete="off" placeholder="e.g. 20" :aria-describedby="describedby" :aria-invalid="!!shown('maxVal')" @blur="touch('maxVal')" />
            </template>
          </BField>
          <BField label="Default" required :error="shown('defaultVal')">
            <template #default="{ id, describedby }">
              <BInput :id="id" v-model="draft.defaultVal" data-field="defaultVal" :inputmode="inputMode" autocomplete="off" placeholder="e.g. 10" :aria-describedby="describedby" :aria-invalid="!!shown('defaultVal')" @blur="touch('defaultVal')" />
            </template>
          </BField>
          <p class="prmd__range-hint">The session form only accepts values in this range{{ draft.type === 'Integer' ? ', in whole numbers' : '' }}.</p>
        </div>

        <fieldset v-else-if="draft.type === 'Boolean'" class="prmd__bool">
          <legend class="prmd__legend">Default<span class="prmd__req" aria-hidden="true">*</span></legend>
          <div class="prmd__seg">
            <label v-for="v in ['true', 'false']" :key="v" class="prmd__seg-opt" :class="{ 'is-on': draft.defaultVal === v }">
              <input type="radio" class="sr-only" :name="`${formId}-bool`" :value="v" :checked="draft.defaultVal === v" @change="draft.defaultVal = v">
              <component :is="v === 'true' ? Check : X" :size="14" aria-hidden="true" />
              {{ v === 'true' ? 'Yes' : 'No' }} <span class="mono prmd__seg-code">{{ v }}</span>
            </label>
          </div>
        </fieldset>

        <BField v-else-if="draft.type === 'Text'" label="Default" optional :error="shown('defaultVal')" hint="Pre-filled when a session is created. Leave empty to start blank.">
          <template #default="{ id, describedby }">
            <BInput :id="id" v-model="draft.defaultVal" data-field="defaultVal" autocomplete="off" placeholder="e.g. control" :aria-describedby="describedby" :aria-invalid="!!shown('defaultVal')" @blur="touch('defaultVal')" />
          </template>
        </BField>

        <BField label="Short description" optional :error="shown('description')" hint="Shown under the field when a session is created, so whoever starts it knows what to enter.">
          <template #default="{ id, describedby }">
            <BInput :id="id" v-model="draft.description" data-field="description" autocomplete="off" placeholder="e.g. Number of rounds to play" :aria-describedby="describedby" :aria-invalid="!!shown('description')" @blur="touch('description')" />
          </template>
        </BField>

        <p v-if="saveError" class="prmd__save-err" role="alert">
          <AlertCircle :size="16" aria-hidden="true" />
          <span>{{ saveError }}</span>
        </p>
      </div>

      <aside class="prmd__preview" aria-label="Preview">
        <p class="prmd__preview-label">When a session is created</p>
        <div class="prmd__mock" aria-hidden="true">
          <span class="prmd__mock-label mono">{{ draft.name.trim() || 'name' }}</span>
          <span v-if="draft.type === 'Boolean'" class="prmd__mock-check">
            <span class="prmd__mock-box" :class="{ 'is-on': draft.defaultVal === 'true' }"><Check v-if="draft.defaultVal === 'true'" :size="11" :stroke-width="3" /></span>
            {{ draft.defaultVal === 'true' ? 'On' : 'Off' }}
          </span>
          <span v-else class="prmd__mock-input">{{ draft.type ? (draft.defaultVal || ' ') : 'Choose a type' }}</span>
          <span v-if="draft.description.trim()" class="prmd__mock-hint">{{ draft.description.trim() }}</span>
          <span v-if="numeric && draft.minVal.trim() && draft.maxVal.trim()" class="prmd__mock-hint">From {{ draft.minVal.trim() }} to {{ draft.maxVal.trim() }}</span>
        </div>
        <p class="prmd__preview-label">In scripts</p>
        <pre class="prmd__code" aria-hidden="true"><code><span class="prmd__code-c">// with the default value</span>
{{ draft.name.trim() || 'name' }} <span class="prmd__code-c">// {{ literal }}</span></code></pre>
        <p class="sr-only">Scripts read this parameter as {{ draft.name.trim() || 'its name' }}, which is {{ literal }} with the default value.</p>
      </aside>
    </form>

    <template #footer>
      <!-- asked here rather than with confirm(), which would open behind this dialog -->
      <template v-if="askDiscard">
        <span class="prmd__foot-note prmd__foot-ask" role="alert">Discard what you entered?</span>
        <BButton ref="keepBtn" variant="ghost" @click="askDiscard = false">Keep editing</BButton>
        <BButton variant="danger" @click="discard">Discard</BButton>
      </template>
      <template v-else>
        <span v-if="editing" class="prmd__foot-note">Sessions already created keep the values they were given.</span>
        <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
        <BButton variant="primary" type="submit" :form="formId" :loading="busy">{{ editing ? 'Save changes' : 'Create parameter' }}</BButton>
      </template>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { AlertCircle, Binary, Check, Hash, ToggleLeft, Type, X } from 'lucide-vue-next'
import { BButton, BDialog, BField, BInput } from '@/ui'
import { uid } from '@/ui/uid'
import { http, HttpError } from '@/lib/http'
import type { Parameter, ParameterType } from '@/store/types'
import {
  PARAMETER_TYPES, changeType, draftFrom, emptyDraft, hasErrors, isNumericType, scriptLiteral,
  toPayload, validateParameter, type DraftField, type ParameterDraft,
} from './parameters'

const props = defineProps<{
  open: boolean
  experimentId: number
  existing: Parameter[]
  /** The parameter to change; null for a new one. */
  editing: Parameter | null
}>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  /** The server's copy of the new or changed parameter (an edit keeps its id). */
  saved: [parameter: Parameter]
}>()

const TYPE_ICONS: Record<ParameterType, any> = { Integer: Hash, Decimal: Binary, Boolean: ToggleLeft, Text: Type }
const formId = uid('param-form')

const draft = reactive<ParameterDraft>(emptyDraft())
let initial = JSON.stringify(emptyDraft())
const touched = reactive<Partial<Record<DraftField, boolean>>>({})
const submitted = ref(false)
const busy = ref(false)
const saveError = ref('')
const askDiscard = ref(false)
const keepBtn = ref<{ $el: HTMLElement } | null>(null)

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(draft, props.editing ? draftFrom(props.editing) : emptyDraft())
  initial = JSON.stringify(draft)
  for (const k of Object.keys(touched)) delete touched[k as DraftField]
  submitted.value = false
  saveError.value = ''
  askDiscard.value = false
}, { immediate: true })

const changed = computed(() => JSON.stringify(draft) !== initial)
const errors = computed(() => validateParameter(draft, props.existing, props.editing))
const numeric = computed(() => isNumericType(draft.type))
const inputMode = computed(() => draft.type === 'Integer' ? 'numeric' : 'decimal')
const literal = computed(() => scriptLiteral(draft.type, draft.defaultVal))

function touch (f: DraftField) { touched[f] = true }
function shown (f: DraftField): string | undefined {
  // a name clash or bad name shows as soon as there is something typed
  const live = f === 'name' && draft.name.trim().length > 0
  return (submitted.value || touched[f] || live) ? errors.value[f] : undefined
}
const nameHint = computed(() => draft.name.trim() === 'password'
  ? 'Special name: participants are asked for this password when they sign in.'
  : 'Letters, digits and _, starting with a letter. Scripts use it exactly as written.')

function setType (t: ParameterType) {
  Object.assign(draft, changeType(draft, t))
  touched.type = true
}

async function close () {
  if (busy.value) return
  if (changed.value && !askDiscard.value) {
    askDiscard.value = true
    await nextTick()
    keepBtn.value?.$el?.focus()
    return
  }
  discard()
}
function discard () {
  askDiscard.value = false
  emit('update:open', false)
}

function describe (e: unknown): string {
  if (e instanceof HttpError) {
    if (e.status >= 500) return `The server couldn’t save it (${e.status}: ${e.message}). Try again in a moment.`
    return e.message || `The server said no (${e.status}).`
  }
  return e instanceof Error ? `Couldn’t reach the server: ${e.message}` : 'Something went wrong.'
}

const FIELD_ORDER: DraftField[] = ['name', 'type', 'minVal', 'maxVal', 'defaultVal', 'description']
async function focusFirstError () {
  await nextTick()
  const f = FIELD_ORDER.find(k => errors.value[k])
  if (!f) return
  const root = document.getElementById(formId)
  const el = f === 'type'
    ? root?.querySelector<HTMLElement>('.prmd__types input')
    : root?.querySelector<HTMLElement>(`[data-field="${f}"]`)
  el?.focus()
}

async function submit () {
  if (busy.value) return
  submitted.value = true
  saveError.value = ''
  if (hasErrors(errors.value)) {
    focusFirstError()
    return
  }
  busy.value = true
  const payload = toPayload(draft)
  const old = props.editing
  let saved: Parameter
  try {
    if (old && old.id != null) {
      // changed in place (POST /parameters/:experimentId/:id): it keeps its id and its place
      const res = await http.post<{ parameter?: Parameter }>(`/parameters/${props.experimentId}/${old.id}`, payload)
      saved = res && res.parameter && typeof res.parameter === 'object' ? res.parameter : { ...payload, id: old.id }
    } else {
      const res = await http.put<{ parameter?: Parameter }>(`/parameters/${props.experimentId}`, payload)
      saved = res && res.parameter && typeof res.parameter === 'object' ? res.parameter : { id: null, ...payload }
    }
  } catch (e) {
    saveError.value = describe(e)
    busy.value = false
    return
  }
  busy.value = false
  emit('saved', saved)
  emit('update:open', false)
}
</script>

<style>
.prmd { display: grid; grid-template-columns: minmax(0, 1fr) 236px; gap: 24px; }
.prmd__fields { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.prmd__legend { padding: 0; margin-bottom: 8px; font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.prmd__req { color: var(--danger); margin-left: 4px; }
.prmd__types, .prmd__bool { border: 0; margin: 0; padding: 0; min-width: 0; }
.prmd__type-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.prmd__type {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  cursor: pointer;
  transition: border-color var(--duration) var(--ease), background var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.prmd__type:hover { background: var(--surface-hover); }
.prmd__type.is-on { border-color: var(--accent); background: var(--accent-soft); box-shadow: 0 0 0 1px var(--accent) inset; }
.prmd__type:focus-within { outline: 2px solid var(--accent); outline-offset: 2px; }
.prmd__type:has(input:focus:not(:focus-visible)) { outline: none; }
.prmd__type-icon { display: grid; place-items: center; flex: none; width: 28px; height: 28px; border-radius: 7px; background: var(--bg-subtle); color: var(--muted); }
.prmd__type.is-on .prmd__type-icon { background: var(--surface); color: var(--accent); }
.prmd__type-text { display: flex; flex-direction: column; min-width: 0; }
.prmd__type-name { font-weight: 600; color: var(--text); }
.prmd__type-meta { font-size: var(--text-xs); color: var(--muted); }
.prmd__err { margin-top: 6px; font-size: var(--text-xs); color: var(--danger); }
.prmd__range { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.prmd__range-hint { grid-column: 1 / -1; margin-top: -4px; font-size: var(--text-xs); color: var(--muted); }
.prmd__seg { display: inline-flex; padding: 3px; gap: 3px; border-radius: var(--radius-sm); background: var(--bg-subtle); }
.prmd__seg-opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 6px;
  color: var(--muted);
  font-weight: 550;
  cursor: pointer;
}
.prmd__seg-opt.is-on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
.prmd__seg-opt:focus-within { outline: 2px solid var(--accent); outline-offset: 1px; }
.prmd__seg-opt:has(input:focus:not(:focus-visible)) { outline: none; }
.prmd__seg-code { font-size: var(--text-xs); color: var(--faint); }
.prmd__save-err {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
  color: var(--danger-soft-text);
  font-size: var(--text-sm);
}
.prmd__save-err svg { flex: none; margin-top: 2px; }

.prmd__preview {
  align-self: start;
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-sunken);
}
.prmd__preview-label { font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.prmd__preview-label:not(:first-child) { margin-top: 8px; }
.prmd__mock { display: flex; flex-direction: column; gap: 5px; padding: 12px; border-radius: var(--radius-sm); background: var(--surface); border: 1px solid var(--border); }
.prmd__mock-label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); overflow-wrap: anywhere; }
.prmd__mock-input {
  display: block;
  min-height: 30px;
  padding: 5px 9px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  font-size: var(--text-sm);
  color: var(--text);
  white-space: pre;
  overflow: hidden;
  text-overflow: ellipsis;
}
.prmd__mock-check { display: inline-flex; align-items: center; gap: 8px; font-size: var(--text-sm); color: var(--text-soft); }
.prmd__mock-box { display: grid; place-items: center; width: 16px; height: 16px; border-radius: 4px; border: 1.5px solid var(--border-strong); color: var(--on-accent); }
.prmd__mock-box.is-on { background: var(--accent); border-color: var(--accent); }
.prmd__mock-hint { font-size: var(--text-xs); color: var(--muted); overflow-wrap: anywhere; }
.prmd__code {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  border: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.6;
  color: var(--text);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.prmd__code-c { color: var(--faint); }
.prmd__foot-note { flex: 1; font-size: var(--text-xs); color: var(--muted); }
.prmd__foot-ask { font-size: var(--text-sm); font-weight: 600; color: var(--text); }
@media (max-width: 760px) {
  .prmd { grid-template-columns: minmax(0, 1fr); }
  .prmd__preview { position: static; }
}
@media (max-width: 480px) {
  .prmd__type-grid { grid-template-columns: minmax(0, 1fr); }
  .prmd__range { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .prmd__range > .b-field:last-of-type { grid-column: 1 / -1; }
  .prmd__foot-note:not(.prmd__foot-ask) { display: none; }
}
</style>
