<template>
  <component
    :is="href ? 'a' : 'button'"
    class="b-btn"
    :class="[`b-btn--${variant}`, `b-btn--${size}`, { 'is-block': block, 'is-icon': iconOnly, 'is-loading': loading }]"
    :type="href ? undefined : type"
    :href="href"
    :disabled="href ? undefined : (disabled || loading)"
    :aria-disabled="href && disabled ? 'true' : undefined"
    :aria-busy="loading ? 'true' : undefined"
    @click="onClick"
  >
    <span v-if="loading" class="b-btn__spinner" aria-hidden="true" />
    <component :is="icon" v-else-if="icon" class="b-btn__icon" :size="size === 'sm' ? 15 : 16" aria-hidden="true" />
    <span v-if="$slots.default" class="b-btn__label"><slot /></span>
    <component :is="iconRight" v-if="iconRight" class="b-btn__icon" :size="size === 'sm' ? 15 : 16" aria-hidden="true" />
  </component>
</template>

<script setup lang="ts">
import { computed, useSlots, type Component } from 'vue'

const props = withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'danger-ghost'
  size?: 'sm' | 'md' | 'lg'
  type?: 'button' | 'submit' | 'reset'
  icon?: Component
  iconRight?: Component
  href?: string
  disabled?: boolean
  loading?: boolean
  block?: boolean
}>(), { variant: 'secondary', size: 'md', type: 'button' })

const slots = useSlots()
// A disabled link still receives clicks; stop them here so they don't reach whatever is underneath.
function onClick (e: MouseEvent) {
  if (props.href && (props.disabled || props.loading)) { e.preventDefault(); e.stopImmediatePropagation() }
}
const iconOnly = computed(() => !!props.icon && !slots.default)
</script>

<style>
.b-btn {
  --b-btn-h: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: var(--b-btn-h);
  padding: 0 13px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--text-md);
  font-weight: 550;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  text-decoration: none !important;
  transition: background var(--duration) var(--ease), border-color var(--duration) var(--ease),
    color var(--duration) var(--ease), box-shadow var(--duration) var(--ease), transform 80ms var(--ease);
}
.b-btn:active:not(:disabled) { transform: translateY(1px); }
.b-btn:disabled,
.b-btn[aria-disabled='true'] { opacity: 0.5; cursor: not-allowed; }
.b-btn.is-loading { cursor: progress; }
.b-btn--sm { --b-btn-h: 28px; padding: 0 10px; font-size: var(--text-sm); gap: 6px; border-radius: var(--radius-xs); }
.b-btn--lg { --b-btn-h: 42px; padding: 0 18px; font-size: var(--text-lg); }
.b-btn.is-block { display: flex; width: 100%; }
.b-btn.is-icon { width: var(--b-btn-h); padding: 0; }

.b-btn--primary { background: var(--accent); color: var(--on-accent); box-shadow: var(--shadow-sm); }
.b-btn--primary:hover { background: var(--accent-hover); }
.b-btn--secondary { background: var(--surface); color: var(--text); border-color: var(--border-strong); box-shadow: var(--shadow-sm); }
.b-btn--secondary:hover { background: var(--surface-hover); }
.b-btn--ghost { background: transparent; color: var(--text-soft); }
.b-btn--ghost:hover { background: var(--surface-hover); color: var(--text); }
.b-btn--danger { background: var(--danger); color: var(--on-danger); box-shadow: var(--shadow-sm); }
.b-btn--danger:hover { background: var(--danger-hover); }
.b-btn--danger-ghost { background: transparent; color: var(--danger); }
.b-btn--danger-ghost:hover { background: var(--danger-soft); }

.b-btn__icon { flex: none; }
.b-btn__label { overflow: hidden; text-overflow: ellipsis; }
.b-btn__spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid currentColor;
  border-right-color: transparent;
  animation: b-spin 0.7s linear infinite;
}
@keyframes b-spin { to { transform: rotate(360deg); } }
</style>
