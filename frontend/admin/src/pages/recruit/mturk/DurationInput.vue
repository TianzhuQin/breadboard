<template>
  <div class="mt-dur">
    <BInput
      :id="id"
      :model-value="amount"
      type="number"
      min="0"
      step="any"
      inputmode="decimal"
      :disabled="disabled"
      :aria-describedby="describedby"
      @update:model-value="setAmount"
    />
    <BSelect :model-value="unit" :options="UNITS" :disabled="disabled" :aria-label="unitLabel" class="mt-dur__unit" @update:model-value="setUnit" />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { BInput, BSelect } from '@/ui'

/** A length of time typed in seconds, minutes, hours or days; the model is whole seconds. */
const props = defineProps<{ modelValue: number | null, id?: string, describedby?: string, disabled?: boolean, unitLabel?: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const UNITS = [
  { value: 1, label: 'seconds' },
  { value: 60, label: 'minutes' },
  { value: 3600, label: 'hours' },
  { value: 86400, label: 'days' },
]

const amount = ref<number | null>(null)
const unit = ref(60)

/** Show a stored number of seconds in the largest unit that divides it evenly. */
function fromSeconds (s: number | null) {
  if (s == null) { amount.value = null; return }
  const u = [86400, 3600, 60, 1].find(x => s % x === 0 && s >= x) || 1
  unit.value = u
  amount.value = s / u
}

function current (): number | null {
  if (amount.value == null || !Number.isFinite(amount.value)) return null
  return Math.round(amount.value * unit.value)
}

function setAmount (v: number | null) {
  amount.value = v
  emit('update:modelValue', current())
}
// Changing the unit keeps the number: "5" then "minutes" means five minutes.
function setUnit (u: number) {
  unit.value = u
  emit('update:modelValue', current())
}

watch(() => props.modelValue, (s) => { if (s !== current()) fromSeconds(s) }, { immediate: true })
</script>

<style>
.mt-dur { display: grid; grid-template-columns: minmax(0, 1fr) 120px; gap: 6px; }
</style>
