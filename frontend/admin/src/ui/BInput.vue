<template>
  <div class="b-input" :class="[`b-input--${size}`, { 'is-disabled': disabled, 'has-prefix': !!icon }]">
    <component :is="icon" v-if="icon" class="b-input__icon" :size="15" aria-hidden="true" />
    <input
      ref="el"
      v-bind="$attrs"
      class="b-input__el"
      :type="type"
      :value="modelValue"
      :disabled="disabled"
      @input="onInput"
    >
    <slot name="suffix" />
  </div>
</template>

<script setup lang="ts">
import { ref, type Component } from 'vue'

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  modelValue?: string | number | null
  type?: string
  size?: 'sm' | 'md'
  icon?: Component
  disabled?: boolean
  number?: boolean
}>(), { type: 'text', size: 'md' })
const emit = defineEmits<{ 'update:modelValue': [value: any] }>()

const el = ref<HTMLInputElement>()
function onInput (e: Event) {
  const v = (e.target as HTMLInputElement).value
  if (props.number || props.type === 'number') {
    emit('update:modelValue', v === '' ? null : Number(v))
  } else {
    emit('update:modelValue', v)
  }
}
defineExpose({ focus: () => el.value?.focus(), select: () => el.value?.select(), el })
</script>

<style>
.b-input {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 11px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
  min-width: 0;
}
.b-input--sm { height: 30px; padding: 0 9px; border-radius: var(--radius-xs); font-size: var(--text-sm); }
.b-input:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.b-input.is-disabled { background: var(--surface-sunken); opacity: 0.7; }
.b-input__icon { color: var(--faint); flex: none; }
.b-input__el {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  padding: 0;
}
.b-input__el::placeholder { color: var(--faint); }
.b-input__el:focus-visible { outline: 0; }
textarea.b-textarea {
  display: block;
  width: 100%;
  min-height: 90px;
  padding: 9px 11px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  resize: vertical;
  line-height: 1.5;
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
textarea.b-textarea:focus { outline: 0; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
</style>
