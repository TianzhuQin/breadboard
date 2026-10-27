<template>
  <nav class="mt-pager" :aria-label="ariaLabel">
    <span class="mt-pager__label" aria-live="polite">{{ label }}</span>
    <BButton size="sm" variant="ghost" :icon="ChevronLeft" :disabled="!hasPrev || loading" @click="emit('prev')">Previous</BButton>
    <BButton size="sm" variant="ghost" :icon-right="ChevronRight" :disabled="!hasNext || loading" @click="emit('next')">Next</BButton>
  </nav>
</template>

<script setup lang="ts">
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { BButton } from '@/ui'

withDefaults(defineProps<{ label: string, hasPrev: boolean, hasNext: boolean, loading?: boolean, ariaLabel?: string }>(), { ariaLabel: 'Pages' })
const emit = defineEmits<{ prev: [], next: [] }>()
</script>

<style>
.mt-pager { display: flex; align-items: center; gap: 4px; justify-content: flex-end; flex-wrap: wrap; }
.mt-pager__label { margin-right: 6px; font-size: var(--text-sm); color: var(--muted); font-variant-numeric: tabular-nums; }
</style>
