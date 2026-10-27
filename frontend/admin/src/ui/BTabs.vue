<template>
  <div class="b-tabs" :class="`b-tabs--${variant}`" role="tablist" :aria-label="ariaLabel" @keydown="onKey">
    <button
      v-for="t in tabs"
      :id="`${base}-${t.value}`"
      :key="t.value"
      ref="buttons"
      type="button"
      role="tab"
      class="b-tabs__tab"
      :class="{ 'is-active': t.value === modelValue }"
      :aria-selected="t.value === modelValue ? 'true' : 'false'"
      :tabindex="t.value === modelValue ? 0 : -1"
      :disabled="t.disabled"
      @click="emit('update:modelValue', t.value)"
    >
      <component :is="t.icon" v-if="t.icon" :size="15" aria-hidden="true" />
      {{ t.label }}
      <span v-if="t.count != null" class="b-tabs__count">{{ t.count }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref, type Component } from 'vue'
import { uid } from './uid'

export interface TabItem { value: string, label: string, icon?: Component, count?: number | null, disabled?: boolean }
const props = withDefaults(defineProps<{ tabs: TabItem[], modelValue: string, ariaLabel?: string, variant?: 'line' | 'pill' }>(), { variant: 'line' })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const base = uid('tab')
const buttons = ref<HTMLButtonElement[]>([])

function onKey (e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
  const enabled = props.tabs.filter(t => !t.disabled)
  let i = enabled.findIndex(t => t.value === props.modelValue)
  if (e.key === 'ArrowLeft') i = (i - 1 + enabled.length) % enabled.length
  if (e.key === 'ArrowRight') i = (i + 1) % enabled.length
  if (e.key === 'Home') i = 0
  if (e.key === 'End') i = enabled.length - 1
  e.preventDefault()
  emit('update:modelValue', enabled[i].value)
  const idx = props.tabs.indexOf(enabled[i])
  buttons.value[idx]?.focus()
}
</script>

<style>
.b-tabs { display: flex; gap: 2px; overflow-x: auto; scrollbar-width: none; }
.b-tabs--line { border-bottom: 1px solid var(--border); gap: 18px; }
.b-tabs__tab {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-weight: 550;
  white-space: nowrap;
  cursor: pointer;
  transition: color var(--duration) var(--ease), background var(--duration) var(--ease);
}
.b-tabs--line .b-tabs__tab { padding: 10px 1px 9px; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.b-tabs--line .b-tabs__tab:hover { color: var(--text); }
.b-tabs--line .b-tabs__tab.is-active { color: var(--text); border-bottom-color: var(--accent); }
.b-tabs--pill { padding: 3px; background: var(--bg-subtle); border-radius: var(--radius-sm); display: inline-flex; }
.b-tabs--pill .b-tabs__tab { padding: 5px 11px; border-radius: 6px; font-size: var(--text-sm); }
.b-tabs--pill .b-tabs__tab.is-active { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
.b-tabs__count { padding: 0 6px; border-radius: 999px; background: var(--bg-subtle); color: var(--muted); font-size: 11px; font-weight: 600; line-height: 17px; }
.b-tabs__tab.is-active .b-tabs__count { background: var(--accent-soft); color: var(--accent-soft-text); }
.b-tabs__tab:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
