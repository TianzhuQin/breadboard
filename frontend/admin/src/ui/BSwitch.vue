<template>
  <label class="b-switch" :class="{ 'is-disabled': disabled }">
    <input
      type="checkbox"
      role="switch"
      class="b-switch__input"
      :checked="modelValue"
      :disabled="disabled"
      @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    >
    <span class="b-switch__track" aria-hidden="true"><span class="b-switch__thumb" /></span>
    <span v-if="label || $slots.default" class="b-switch__label"><slot>{{ label }}</slot></span>
  </label>
</template>

<script setup lang="ts">
defineProps<{ modelValue?: boolean, label?: string, disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<style>
.b-switch { display: inline-flex; align-items: center; gap: 10px; cursor: pointer; user-select: none; }
.b-switch.is-disabled { opacity: 0.55; cursor: not-allowed; }
.b-switch__input { position: absolute; opacity: 0; width: 1px; height: 1px; }
.b-switch__track {
  position: relative;
  flex: none;
  width: 34px;
  height: 20px;
  border-radius: 999px;
  background: var(--border-strong);
  transition: background var(--duration) var(--ease);
}
.b-switch__thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.25);
  transition: transform var(--duration) var(--ease);
}
.b-switch__input:checked + .b-switch__track { background: var(--accent); }
.b-switch__input:checked + .b-switch__track .b-switch__thumb { transform: translateX(14px); }
.b-switch__input:focus-visible + .b-switch__track { outline: 2px solid var(--accent); outline-offset: 2px; }
.b-switch__label { font-size: var(--text-md); color: var(--text-soft); }
</style>
