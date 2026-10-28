<template>
  <label class="b-check" :class="{ 'is-disabled': disabled }">
    <input
      type="checkbox"
      class="b-check__input"
      :checked="modelValue"
      :disabled="disabled"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    >
    <span class="b-check__box" aria-hidden="true"><Check :size="12" :stroke-width="3" /></span>
    <span v-if="label || $slots.default" class="b-check__label"><slot>{{ label }}</slot></span>
  </label>
</template>

<script setup lang="ts">
import { Check } from 'lucide-vue-next'
defineProps<{ modelValue?: boolean, label?: string, disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<style>
.b-check { position: relative; display: inline-flex; align-items: center; gap: 9px; cursor: pointer; user-select: none; }
.b-check.is-disabled { opacity: 0.55; cursor: not-allowed; }
.b-check__input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.b-check__box {
  display: grid;
  place-items: center;
  flex: none;
  width: 17px;
  height: 17px;
  border: 1.5px solid var(--border-strong);
  border-radius: 5px;
  background: var(--surface);
  color: transparent;
  transition: all var(--duration) var(--ease);
}
.b-check__input:checked + .b-check__box { background: var(--accent); border-color: var(--accent); color: var(--on-accent); }
.b-check__input:focus-visible + .b-check__box { outline: 2px solid var(--accent); outline-offset: 2px; }
.b-check__label { color: var(--text-soft); }
</style>
