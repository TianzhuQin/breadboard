<template>
  <BDialog
    :open="open"
    :title="editing ? 'Edit requirement' : 'Add a requirement'"
    subtitle="Only workers who meet it can take the HIT."
    size="md"
    :dismissable="false"
    @close="emit('update:open', false)"
  >
    <form v-if="draft" id="mt-req-form" class="rc-stack" novalidate @submit.prevent="save">
      <BField label="Requirement" :hint="info.hint">
        <template #default="{ id, describedby }">
          <BSelect :id="id" :model-value="draft.kind" :options="KIND_OPTIONS" :aria-describedby="describedby" data-autofocus @update:model-value="changeKind" />
        </template>
      </BField>

      <BField v-if="draft.kind === 'experiment'" label="Experiment" :error="shown.experimentUid">
        <template #default="{ id }">
          <BSelect :id="id" :model-value="experimentChoice" :options="experimentOptions" placeholder="Choose an experiment" @update:model-value="pickExperiment" />
        </template>
      </BField>
      <BField v-if="draft.kind === 'experiment' && experimentChoice === OTHER" label="Experiment UID" hint="Find it on the Experiments page of the server that ran it.">
        <template #default="{ id, describedby }">
          <BInput :id="id" v-model="draft.experimentUid" class="rc-mono" :aria-describedby="describedby" autocomplete="off" />
        </template>
      </BField>

      <BField v-if="draft.kind === 'custom'" label="Qualification type ID" :error="shown.customTypeId" hint="From MTurk, e.g. your own qualification types on the requester site.">
        <template #default="{ id, describedby }">
          <BInput :id="id" v-model="draft.customTypeId" class="rc-mono" :aria-describedby="describedby" autocomplete="off" />
        </template>
      </BField>

      <div class="mt-req__row">
        <BField v-if="info.comparators.length > 1" label="Workers must have" :error="shown.comparator">
          <template #default="{ id }">
            <BSelect :id="id" v-model="draft.comparator" :options="comparatorOptions" />
          </template>
        </BField>
        <BField v-if="needsIntegers" :label="draft.kind === 'approvedPercent' ? 'Percent' : multi ? 'Values' : 'Value'" :error="shown.integerValues" :hint="multi ? 'Several whole numbers, separated by commas.' : undefined">
          <template #default="{ id, describedby }">
            <BInput :id="id" v-model="draft.integerValues" inputmode="numeric" :aria-describedby="describedby" autocomplete="off" />
          </template>
        </BField>
      </div>

      <fieldset v-if="info.values === 'locale'" class="mt-req__places">
        <legend class="mt-req__legend">Places</legend>
        <p class="rc-sub">Two-letter country codes (US, GB, IN), optionally with a region (US + CA for California).</p>
        <div v-for="(l, i) in draft.locales" :key="i" class="mt-req__place">
          <BInput v-model="l.country" size="sm" placeholder="Country, e.g. US" :aria-label="`Country ${i + 1}`" maxlength="2" autocomplete="off" />
          <BInput v-model="l.subdivision" size="sm" placeholder="Region (optional)" :aria-label="`Region ${i + 1}`" maxlength="3" autocomplete="off" />
          <BButton size="sm" variant="ghost" :icon="X" :aria-label="`Remove place ${i + 1}`" :disabled="draft.locales.length === 1" @click="draft.locales.splice(i, 1)" />
        </div>
        <p v-if="shown.locales" class="rc-error-text" role="alert">{{ shown.locales }}</p>
        <BButton size="sm" variant="ghost" :icon="Plus" @click="draft.locales.push({ country: '', subdivision: '' })">Add a place</BButton>
      </fieldset>

      <BField label="Workers who don’t meet it" :hint="guardHint">
        <template #default="{ id, describedby }">
          <BSelect :id="id" v-model="draft.actionsGuarded" :options="GUARD_OPTIONS" :aria-describedby="describedby" />
        </template>
      </BField>

      <div class="rc-note rc-note--info" aria-live="polite">
        <ListFilter :size="16" aria-hidden="true" />
        <span>{{ describeRequirement(draft) }}</span>
      </div>
    </form>
    <template #footer>
      <BButton variant="ghost" @click="emit('update:open', false)">Cancel</BButton>
      <BButton type="submit" form="mt-req-form" variant="primary">{{ editing ? 'Save' : 'Add requirement' }}</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ListFilter, Plus, X } from 'lucide-vue-next'
import { BButton, BDialog, BField, BInput, BSelect } from '@/ui'
import { experiments } from '@/store/admin'
import {
  ACTIONS_GUARDED, COMPARATOR_LABELS, REQUIREMENT_KINDS, comparatorTakesValues, describeRequirement, newRequirement, validateRequirement,
  type RequirementDraft, type RequirementErrors, type RequirementKind,
} from './lib'

const props = defineProps<{ open: boolean, initial: RequirementDraft | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean], save: [draft: RequirementDraft] }>()

const OTHER = '__other__'
const KIND_ORDER: RequirementKind[] = ['approvedPercent', 'approvedCount', 'locale', 'masters', 'experiment', 'adult', 'custom']
const KIND_OPTIONS = KIND_ORDER.map(k => ({ value: k, label: REQUIREMENT_KINDS[k].label }))
const GUARD_OPTIONS = ACTIONS_GUARDED.map(g => ({ value: g.value, label: g.label }))

const draft = ref<RequirementDraft | null>(null)
const editing = ref(false)
const tried = ref(false)

watch(() => props.open, (o) => {
  if (!o) return
  editing.value = !!props.initial
  tried.value = false
  draft.value = props.initial
    ? { ...props.initial, locales: props.initial.locales.map(l => ({ ...l })) }
    : newRequirement('approvedPercent')
}, { immediate: true })

const info = computed(() => REQUIREMENT_KINDS[draft.value?.kind || 'approvedPercent'])
const multi = computed(() => draft.value?.comparator === 'In' || draft.value?.comparator === 'NotIn')
const needsIntegers = computed(() => !!draft.value && info.value.values === 'integer' && comparatorTakesValues(draft.value.comparator))
const comparatorOptions = computed(() => info.value.comparators.map(c => ({ value: c, label: COMPARATOR_LABELS[c] })))
const guardHint = computed(() => ACTIONS_GUARDED.find(g => g.value === draft.value?.actionsGuarded)?.hint)

const errors = computed<RequirementErrors>(() => (draft.value ? validateRequirement(draft.value) : {}))
const shown = computed<RequirementErrors>(() => (tried.value ? errors.value : {}))

const experimentOptions = computed(() => [
  ...experiments.value.map(e => ({ value: e.uid, label: e.name })),
  { value: OTHER, label: 'Another experiment (enter its UID)…' },
])
const experimentChoice = ref<string>('')
watch(() => [props.open, draft.value?.kind], () => {
  const d = draft.value
  if (!d || d.kind !== 'experiment') return
  experimentChoice.value = !d.experimentUid ? '' : experiments.value.some(e => e.uid === d.experimentUid) ? d.experimentUid : OTHER
}, { immediate: true })

function pickExperiment (v: string) {
  if (!draft.value) return
  experimentChoice.value = v
  if (v === OTHER) {
    draft.value.experimentUid = ''
    draft.value.experimentName = ''
  } else {
    draft.value.experimentUid = v
    draft.value.experimentName = experiments.value.find(e => e.uid === v)?.name || ''
  }
}

function changeKind (k: RequirementKind) {
  draft.value = newRequirement(k)
  tried.value = false
}

function save () {
  if (!draft.value) return
  tried.value = true
  if (Object.keys(errors.value).length) return
  const d = draft.value
  if (d.kind === 'experiment' && experimentChoice.value === OTHER) d.experimentName = ''
  emit('save', { ...d, locales: d.locales.map(l => ({ ...l })) })
}
</script>

<style>
.mt-req__row { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 14px; }
.mt-req__places { margin: 0; padding: 12px 14px; border: 1px solid var(--border); border-radius: var(--radius-sm); display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.mt-req__legend { padding: 0 4px; font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.mt-req__places > .b-btn { align-self: flex-start; }
.mt-req__place { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 6px; }
</style>
