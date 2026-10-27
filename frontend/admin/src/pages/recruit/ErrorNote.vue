<template>
  <div class="rc-note rc-note--danger rc-error-note" role="alert">
    <CircleAlert :size="16" aria-hidden="true" />
    <div class="rc-error-note__text">
      <p class="rc-error-note__title">{{ title || message }}</p>
      <p v-if="title && message" class="rc-error-note__msg">{{ message }}</p>
      <p v-if="detail" class="rc-error-note__detail">{{ detail }}</p>
    </div>
    <BButton v-if="onRetry" size="sm" variant="secondary" :icon="RotateCw" :loading="retrying" @click="retry">Try again</BButton>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { CircleAlert, RotateCw } from 'lucide-vue-next'
import { BButton } from '@/ui'

const props = defineProps<{
  /** what failed, e.g. "Could not load the HITs" */
  title?: string
  message: string
  detail?: string
  onRetry?: () => unknown
}>()

const retrying = ref(false)
async function retry () {
  retrying.value = true
  try { await props.onRetry?.() } finally { retrying.value = false }
}
</script>

<style>
.rc-error-note { align-items: center; }
.rc-error-note__text { flex: 1; min-width: 0; }
.rc-error-note__title { font-weight: 600; }
.rc-error-note__msg { margin-top: 2px; overflow-wrap: anywhere; }
.rc-error-note__detail { margin-top: 2px; font-size: var(--text-xs); opacity: 0.85; overflow-wrap: anywhere; }
</style>
