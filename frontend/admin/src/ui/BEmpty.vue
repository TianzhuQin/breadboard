<template>
  <div class="b-empty" :class="{ 'is-compact': compact }">
    <div v-if="icon" class="b-empty__icon"><component :is="icon" :size="compact ? 20 : 26" aria-hidden="true" /></div>
    <h3 class="b-empty__title">{{ title }}</h3>
    <p v-if="text || $slots.default" class="b-empty__text"><slot>{{ text }}</slot></p>
    <div v-if="$slots.actions" class="b-empty__actions"><slot name="actions" /></div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
defineProps<{ title: string, text?: string, icon?: Component, compact?: boolean }>()
</script>

<style>
.b-empty { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 48px 24px; gap: 8px; }
.b-empty.is-compact { padding: 24px 16px; }
.b-empty__icon { display: grid; place-items: center; width: 52px; height: 52px; border-radius: 14px; background: var(--accent-soft); color: var(--accent); margin-bottom: 6px; }
.b-empty.is-compact .b-empty__icon { width: 40px; height: 40px; border-radius: 11px; }
.b-empty__title { font-size: var(--text-lg); font-weight: 650; }
.b-empty.is-compact .b-empty__title { font-size: var(--text-md); }
.b-empty__text { color: var(--muted); max-width: 440px; font-size: var(--text-md); }
.b-empty__actions { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; justify-content: center; }
</style>
