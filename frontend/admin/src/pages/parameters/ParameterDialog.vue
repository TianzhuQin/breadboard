<template>
  <BDialog
    :open="open"
    :title="editing ? `Edit ${editing.name}` : 'New parameter'"
    subtitle="Asked for each time a session is created. Scripts read it by its name."
    size="lg"
    :dismissable="!busy"
    @update:open="v => { if (!v) close() }"
  >
    <form :id="formId" class="pd" novalidate @submit.prevent="submit">
      <div class="pd__fields">
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

        <fieldset class="pd__types" :aria-describedby="shown('type') ? `${formId}-type-err` : undefined">
          <legend class="pd__legend">Type<span class="pd__req" aria-hidden="true">*</span></legend>
          <div class="pd__type-grid">
            <label v-for="t in PARAMETER_TYPES" :key="t.value" class="pd__type" :class="{ 'is-on': draft.type === t.value }">
              <input
                type="radio"
                class="sr-only"
                :name="`${formId}-type`"
                :value="t.value"
                :checked="draft.type === t.value"
                @change="setType(t.value)"
              >
              <span class="pd__type-icon" aria-hidden="true"><component :is="TYPE_ICONS[t.value]" :size="16" /></span>
              <span class="pd__type-text">
                <span class="pd__type-name">{{ t.label }}</span>
                <span class="pd__type-meta"><span class="mono">{{ t.value }}</span> · {{ t.example }}</span>
              </span>
            </label>
          </div>
          <p v-if="shown('type')" :id="`${formId}-type-err`" class="pd__err" role="alert">{{ shown('type') }}</p>
        </fieldset>

        <div v-if="numeric" class="pd__range">
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
          <p class="pd__range-hint">The session form only accepts values in this range{{ draft.type === 'Integer' ? ', in whole numbers' : '' }}.</p>
        </div>

        <fieldset v-else-if="draft.type === 'Boolean'" class="pd__bool">
          <legend class="pd__legend">Default<span class="pd__req" aria-hidden="true">*</span></legend>
          <div class="pd__seg">
            <label v-for="v in ['true', 'false']" :key="v" class="pd__seg-opt" :class="{ 'is-on': draft.defaultVal === v }">
              <input type="radio" class="sr-only" :name="`${formId}-bool`" :value="v" :checked="draft.defaultVal === v" @change="draft.defaultVal = v">
              <component :is="v === 'true' ? Check : X" :size="14" aria-hidden="true" />
              {{ v === 'true' ? 'Yes' : 'No' }} <span class="mono pd__seg-code">{{ v }}</span>
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

        <p v-if="saveError" class="pd__save-err" role="alert">
          <AlertCircle :size="16" aria-hidden="true" />
          <span>{{ saveError }}</span>
        </p>
      </div>

      <aside class="pd__preview" aria-label="Preview">
        <p class="pd__preview-label">When a session is created</p>
        <div class="pd__mock" aria-hidden="true">
          <span class="pd__mock-label mono">{{ draft.name.trim() || 'name' }}</span>
          <span v-if="draft.type === 'Boolean'" class="pd__mock-check">
            <span class="pd__mock-box" :class="{ 'is-on': draft.defaultVal === 'true' }"><Check v-if="draft.defaultVal === 'true'" :size="11" :stroke-width="3" /></span>
            {{ draft.defaultVal === 'true' ? 'On' : 'Off' }}
          </span>
          <span v-else class="pd__mock-input">{{ draft.type ? (draft.defaultVal || ' ') : 'Choose a type' }}</span>
          <span v-if="draft.description.trim()" class="pd__mock-hint">{{ draft.description.trim() }}</span>
          <span v-if="numeric && draft.minVal.trim() && draft.maxVal.trim()" class="pd__mock-hint">From {{ draft.minVal.trim() }} to {{ draft.maxVal.trim() }}</span>
        </div>
        <p class="pd__preview-label">In scripts</p>
        <pre class="pd__code" aria-hidden="true"><code><span class="pd__code-c">// with the default value</span>
{{ draft.name.trim() || 'name' }} <span class="pd__code-c">// {{ literal }}</span></code></pre>
        <p class="sr-only">Scripts read this parameter as {{ draft.name.trim() || 'its name' }}, which is {{ literal }} with the default value.</p>
      </aside>
    </form>

    <template #footer>
      <!-- asked here rather than with confirm(), which would open behind this dialog -->
      <template v-if="askDiscard">
        <span class="pd__foot-note pd__foot-ask" role="alert">Discard what you entered?</span>
        <BButton ref="keepBtn" variant="ghost" @click="askDiscard = false">Keep editing</BButton>
        <BButton variant="danger" @click="discard">Discard</BButton>
      </template>
      <template v-else>
        <span v-if="editing" class="pd__foot-note">Saving replaces the parameter; sessions already created keep their values.</span>
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
  /** `created` is the server's copy; `removed` the id of the one it replaced; `warning` if that failed. */
  saved: [created: Parameter, removed: number | null, warning: string]
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
    ? root?.querySelector<HTMLElement>('.pd__types input')
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
  let created: Parameter
  try {
    const res = await http.put<{ parameter?: Parameter }>(`/parameters/${props.experimentId}`, payload)
    created = res && res.parameter && typeof res.parameter === 'object' ? res.parameter : { id: null, ...payload }
  } catch (e) {
    saveError.value = describe(e)
    busy.value = false
    return
  }
  // editing = add the new version, then remove the old one (the server has no update route)
  let removed: number | null = null
  let warning = ''
  const old = props.editing
  if (old && old.id != null) {
    try {
      await http.del(`/parameters/${props.experimentId}/${old.id}`)
      removed = old.id
    } catch (e) {
      warning = `The old “${old.name}” is still in the list: ${describe(e)} Delete it there.`
    }
  }
  busy.value = false
  emit('saved', created, removed, warning)
  emit('update:open', false)
}
</script>

<style>
.pd { display: grid; grid-template-columns: minmax(0, 1fr) 236px; gap: 24px; }
.pd__fields { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.pd__legend { padding: 0; margin-bottom: 8px; font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.pd__req { color: var(--danger); margin-left: 4px; }
.pd__types, .pd__bool { border: 0; margin: 0; padding: 0; min-width: 0; }
.pd__type-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.pd__type {
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
.pd__type:hover { background: var(--surface-hover); }
.pd__type.is-on { border-color: var(--accent); background: var(--accent-soft); box-shadow: 0 0 0 1px var(--accent) inset; }
.pd__type:focus-within { outline: 2px solid var(--accent); outline-offset: 2px; }
.pd__type:has(input:focus:not(:focus-visible)) { outline: none; }
.pd__type-icon { display: grid; place-items: center; flex: none; width: 28px; height: 28px; border-radius: 7px; background: var(--bg-subtle); color: var(--muted); }
.pd__type.is-on .pd__type-icon { background: var(--surface); color: var(--accent); }
.pd__type-text { display: flex; flex-direction: column; min-width: 0; }
.pd__type-name { font-weight: 600; color: var(--text); }
.pd__type-meta { font-size: var(--text-xs); color: var(--muted); }
.pd__err { margin-top: 6px; font-size: var(--text-xs); color: var(--danger); }
.pd__range { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.pd__range-hint { grid-column: 1 / -1; margin-top: -4px; font-size: var(--text-xs); color: var(--muted); }
.pd__seg { display: inline-flex; padding: 3px; gap: 3px; border-radius: var(--radius-sm); background: var(--bg-subtle); }
.pd__seg-opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border-radius: 6px;
  color: var(--muted);
  font-weight: 550;
  cursor: pointer;
}
.pd__seg-opt.is-on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
.pd__seg-opt:focus-within { outline: 2px solid var(--accent); outline-offset: 1px; }
.pd__seg-opt:has(input:focus:not(:focus-visible)) { outline: none; }
.pd__seg-code { font-size: var(--text-xs); color: var(--faint); }
.pd__save-err {
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--danger-soft);
  color: var(--danger-soft-text);
  font-size: var(--text-sm);
}
.pd__save-err svg { flex: none; margin-top: 2px; }

.pd__preview {
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
.pd__preview-label { font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.pd__preview-label:not(:first-child) { margin-top: 8px; }
.pd__mock { display: flex; flex-direction: column; gap: 5px; padding: 12px; border-radius: var(--radius-sm); background: var(--surface); border: 1px solid var(--border); }
.pd__mock-label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); overflow-wrap: anywhere; }
.pd__mock-input {
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
.pd__mock-check { display: inline-flex; align-items: center; gap: 8px; font-size: var(--text-sm); color: var(--text-soft); }
.pd__mock-box { display: grid; place-items: center; width: 16px; height: 16px; border-radius: 4px; border: 1.5px solid var(--border-strong); color: var(--on-accent); }
.pd__mock-box.is-on { background: var(--accent); border-color: var(--accent); }
.pd__mock-hint { font-size: var(--text-xs); color: var(--muted); overflow-wrap: anywhere; }
.pd__code {
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
.pd__code-c { color: var(--faint); }
.pd__foot-note { flex: 1; font-size: var(--text-xs); color: var(--muted); }
.pd__foot-ask { font-size: var(--text-sm); font-weight: 600; color: var(--text); }
@media (max-width: 760px) {
  .pd { grid-template-columns: minmax(0, 1fr); }
  .pd__preview { position: static; }
}
@media (max-width: 480px) {
  .pd__type-grid { grid-template-columns: minmax(0, 1fr); }
  .pd__range { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .pd__range > .b-field:last-of-type { grid-column: 1 / -1; }
  .pd__foot-note:not(.pd__foot-ask) { display: none; }
}
</style>
