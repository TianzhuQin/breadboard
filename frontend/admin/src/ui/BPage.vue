<template>
  <div class="b-page" :class="{ 'is-narrow': narrow }">
    <div v-if="intro || $slots.intro || $slots.actions" class="b-page__head">
      <p v-if="intro || $slots.intro" class="b-page__intro"><slot name="intro">{{ intro }}</slot></p>
      <div v-if="$slots.actions" class="b-page__actions"><slot name="actions" /></div>
    </div>
    <slot />
  </div>
</template>

<script setup lang="ts">
/** The standard frame for a page below the top bar: an optional one-line intro, actions, content. */
defineProps<{ intro?: string, narrow?: boolean }>()
</script>

<style>
.b-page { padding: 24px 28px 48px; display: flex; flex-direction: column; gap: 20px; }
.b-page.is-narrow { max-width: 860px !important; }
.b-page__head { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.b-page__intro { flex: 1; min-width: 240px; color: var(--muted); font-size: var(--text-md); }
.b-page__actions { display: flex; gap: 8px; flex-wrap: wrap; }
@media (max-width: 640px) { .b-page { padding: 16px 16px 40px; } }
</style>
