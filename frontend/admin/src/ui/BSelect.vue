<template>
  <div class="b-select" :class="[`b-select--${size}`, { 'is-disabled': disabled }]">
    <select
      v-bind="$attrs"
      class="b-select__el"
      :value="valueKey"
      :disabled="disabled"
      @change="onChange"
    >
      <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
      <option v-for="o in normalized" :key="o.key" :value="o.key" :disabled="o.disabled">{{ o.label }}</option>
    </select>
    <ChevronDown class="b-select__chev" :size="15" aria-hidden="true" />
  </div>
</template>

<script setup lang="ts">
import { computed, toRaw } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

export interface SelectOption { value: any, label: string, disabled?: boolean }

defineOptions({ inheritAttrs: false })
const props = withDefaults(defineProps<{
  modelValue?: any
  options: (SelectOption | string | number)[]
  placeholder?: string
  size?: 'sm' | 'md'
  disabled?: boolean
}>(), { size: 'md' })
const emit = defineEmits<{ 'update:modelValue': [value: any] }>()

// <select> only holds strings, so options are matched by position.
const normalized = computed(() => props.options.map((o, i) => {
  const opt = typeof o === 'object' ? o : { value: o, label: String(o) }
  return { ...opt, key: String(i) }
}))
const valueKey = computed(() => {
  const current = toRaw(props.modelValue)
  const hit = normalized.value.find(o => toRaw(o.value) === current)
  return hit ? hit.key : ''
})
function onChange (e: Event) {
  const key = (e.target as HTMLSelectElement).value
  const hit = normalized.value.find(o => o.key === key)
  emit('update:modelValue', hit ? hit.value : null)
}
</script>

<style>
.b-select { position: relative; display: flex; min-width: 0; }
.b-select__el {
  appearance: none;
  width: 100%;
  height: 36px;
  padding: 0 32px 0 11px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
  text-overflow: ellipsis;
}
.b-select--sm .b-select__el { height: 30px; font-size: var(--text-sm); border-radius: var(--radius-xs); padding-left: 9px; }
.b-select__el:focus { outline: 0; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.b-select.is-disabled { opacity: 0.6; }
.b-select__chev { position: absolute; right: 10px; top: 50%; transform: translateY(-50%); color: var(--muted); pointer-events: none; }
</style>
