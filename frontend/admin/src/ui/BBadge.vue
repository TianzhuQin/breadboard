<template>
  <span class="b-badge" :class="[`b-badge--${tone}`, { 'has-dot': dot, 'is-pulse': pulse }]">
    <span v-if="dot" class="b-badge__dot" aria-hidden="true" />
    <slot />
  </span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ tone?: 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info', dot?: boolean, pulse?: boolean }>(), { tone: 'neutral' })
</script>

<style>
.b-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: var(--text-xs);
  font-weight: 600;
  white-space: nowrap;
  background: var(--bg-subtle);
  color: var(--text-soft);
}
.b-badge--accent { background: var(--accent-soft); color: var(--accent-soft-text); }
.b-badge--success { background: var(--success-soft); color: var(--success-soft-text); }
.b-badge--warning { background: var(--warning-soft); color: var(--warning-soft-text); }
.b-badge--danger { background: var(--danger-soft); color: var(--danger-soft-text); }
.b-badge--info { background: var(--info-soft); color: var(--info-soft-text); }
.b-badge__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.b-badge.is-pulse .b-badge__dot { animation: b-pulse 1.8s ease-in-out infinite; }
@keyframes b-pulse { 0%, 100% { box-shadow: 0 0 0 0 currentColor; } 50% { box-shadow: 0 0 0 3px transparent; opacity: 0.6; } }
</style>
