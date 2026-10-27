<template>
  <section class="b-panel" :class="{ 'is-flush': flush }">
    <header v-if="title || $slots.header || $slots.actions" class="b-panel__head">
      <div class="b-panel__titles">
        <slot name="header">
          <h3 class="b-panel__title">{{ title }}</h3>
          <p v-if="subtitle" class="b-panel__subtitle">{{ subtitle }}</p>
        </slot>
      </div>
      <div v-if="$slots.actions" class="b-panel__actions"><slot name="actions" /></div>
    </header>
    <div class="b-panel__body"><slot /></div>
  </section>
</template>

<script setup lang="ts">
defineProps<{ title?: string, subtitle?: string, flush?: boolean }>()
</script>

<style>
.b-panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); box-shadow: var(--shadow-sm); min-width: 0; }
.b-panel__head { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-bottom: 1px solid var(--border); }
.b-panel__titles { flex: 1; min-width: 0; }
.b-panel__title { font-size: var(--text-md); font-weight: 650; }
.b-panel__subtitle { font-size: var(--text-sm); color: var(--muted); margin-top: 2px; }
.b-panel__actions { display: flex; gap: 6px; align-items: center; }
.b-panel__body { padding: 16px 18px; }
.b-panel.is-flush .b-panel__body { padding: 0; }
</style>
