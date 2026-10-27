<template>
  <div ref="root" class="b-menu" @keydown="onKey">
    <slot name="trigger" :open="open" :toggle="toggle" :attrs="triggerAttrs" />
    <Transition name="b-menu">
      <div
        v-if="open"
        :id="menuId"
        ref="list"
        class="b-menu__list"
        :class="[`b-menu__list--${align}`, { 'is-up': up }]"
        role="menu"
        :style="{ minWidth: width ? `${width}px` : undefined }"
      >
        <template v-for="(item, i) in items" :key="i">
          <div v-if="item.divider" class="b-menu__divider" role="separator" />
          <div v-else-if="item.heading" class="b-menu__heading">{{ item.heading }}</div>
          <button
            v-else
            type="button"
            role="menuitem"
            class="b-menu__item"
            :class="{ 'is-danger': item.danger, 'is-active': item.active }"
            :disabled="item.disabled"
            @click="pick(item)"
          >
            <component :is="item.icon" v-if="item.icon" :size="15" class="b-menu__icon" aria-hidden="true" />
            <span class="b-menu__label">{{ item.label }}</span>
            <Check v-if="item.active" :size="14" class="b-menu__check" aria-hidden="true" />
            <span v-else-if="item.hint" class="b-menu__hint">{{ item.hint }}</span>
          </button>
        </template>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch, type Component } from 'vue'
import { Check } from 'lucide-vue-next'
import { uid } from './uid'

export interface MenuItem {
  label?: string
  icon?: Component
  hint?: string
  danger?: boolean
  active?: boolean
  disabled?: boolean
  divider?: boolean
  heading?: string
  run?: () => void
}
withDefaults(defineProps<{ items: MenuItem[], align?: 'left' | 'right', up?: boolean, width?: number }>(), { align: 'left' })

const open = ref(false)
const root = ref<HTMLElement>()
const list = ref<HTMLElement>()
const menuId = uid('menu')
const triggerAttrs = computed(() => ({ 'aria-haspopup': 'menu' as const, 'aria-expanded': open.value, 'aria-controls': menuId }))

function toggle () { open.value = !open.value }
function close (refocus = false) {
  open.value = false
  if (refocus) (root.value?.querySelector('[aria-haspopup]') as HTMLElement | null)?.focus()
}
function pick (item: MenuItem) {
  close(true)
  item.run?.()
}
function itemEls (): HTMLElement[] {
  return Array.from(list.value?.querySelectorAll<HTMLElement>('.b-menu__item:not(:disabled)') || [])
}
function onKey (e: KeyboardEvent) {
  if (!open.value) {
    if (e.key === 'ArrowDown' && (e.target as HTMLElement).hasAttribute('aria-haspopup')) { e.preventDefault(); open.value = true }
    return
  }
  const all = itemEls()
  const i = all.indexOf(document.activeElement as HTMLElement)
  if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(true) }
  else if (e.key === 'ArrowDown') { e.preventDefault(); all[(i + 1) % all.length]?.focus() }
  else if (e.key === 'ArrowUp') { e.preventDefault(); all[(i - 1 + all.length) % all.length]?.focus() }
  else if (e.key === 'Tab') close()
}
function onDocDown (e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) close()
}
watch(open, async (v) => {
  if (v) {
    document.addEventListener('mousedown', onDocDown)
    await nextTick()
    itemEls()[0]?.focus()
  } else {
    document.removeEventListener('mousedown', onDocDown)
  }
})
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocDown))
defineExpose({ close })
</script>

<style>
.b-menu { position: relative; display: inline-flex; }
.b-menu__list {
  position: absolute;
  top: calc(100% + 6px);
  z-index: 60;
  min-width: 200px;
  max-height: 60vh;
  overflow: auto;
  padding: 5px;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
}
.b-menu__list.is-up { top: auto; bottom: calc(100% + 6px); }
.b-menu__list--left { left: 0; }
.b-menu__list--right { right: 0; }
.b-menu__item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  font-size: var(--text-md);
}
.b-menu__item:hover, .b-menu__item:focus-visible { background: var(--surface-hover); outline: 0; }
.b-menu__item:disabled { opacity: 0.45; cursor: not-allowed; }
.b-menu__item.is-danger { color: var(--danger); }
.b-menu__icon { color: var(--muted); flex: none; }
.b-menu__item.is-danger .b-menu__icon { color: inherit; }
.b-menu__label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.b-menu__hint { color: var(--faint); font-size: var(--text-xs); }
.b-menu__check { color: var(--accent); }
.b-menu__divider { height: 1px; margin: 5px 4px; background: var(--border); }
.b-menu__heading { padding: 8px 9px 4px; font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.b-menu-enter-active, .b-menu-leave-active { transition: opacity 120ms var(--ease), transform 120ms var(--ease); }
.b-menu-enter-from, .b-menu-leave-to { opacity: 0; transform: translateY(-4px); }
</style>
