<template>
  <div class="b-field" :class="{ 'has-error': !!error, 'is-inline': inline }">
    <label v-if="label" class="b-field__label" :for="forId">
      {{ label }}<span v-if="required" class="b-field__req" aria-hidden="true">*</span>
      <span v-if="optional" class="b-field__opt">optional</span>
    </label>
    <div class="b-field__control">
      <slot :id="forId" :describedby="describedby" />
    </div>
    <p v-if="error" :id="`${forId}-err`" class="b-field__error" role="alert">{{ error }}</p>
    <p v-else-if="hint || $slots.hint" :id="`${forId}-hint`" class="b-field__hint"><slot name="hint">{{ hint }}</slot></p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { uid } from './uid'

const props = defineProps<{
  label?: string
  hint?: string
  error?: string | null
  required?: boolean
  optional?: boolean
  inline?: boolean
  id?: string
}>()

const generated = uid('field')
const forId = computed(() => props.id || generated)
const describedby = computed(() => props.error ? `${forId.value}-err` : (props.hint ? `${forId.value}-hint` : undefined))
</script>

<style>
.b-field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.b-field.is-inline { flex-direction: row; align-items: center; gap: 10px; }
.b-field__label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); display: flex; align-items: baseline; gap: 4px; }
.b-field__req { color: var(--danger); }
.b-field__opt { font-weight: 400; color: var(--faint); font-size: var(--text-xs); margin-left: 4px; }
.b-field__hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.45; }
.b-field__error { font-size: var(--text-xs); color: var(--danger); line-height: 1.45; }
.b-field.has-error .b-input,
.b-field.has-error .b-select__el,
.b-field.has-error textarea { border-color: var(--danger); }
</style>
