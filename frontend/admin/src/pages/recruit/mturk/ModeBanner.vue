<template>
  <div class="rc-note mt-mode-banner" :class="isProduction ? 'rc-note--danger is-prod' : 'rc-note--info'" role="note">
    <component :is="isProduction ? ShieldAlert : TestTube" :size="16" aria-hidden="true" />
    <p v-if="isProduction">
      <strong>Production: real workers, real money.</strong>
      {{ productionText }}
    </p>
    <p v-else>
      <strong>Sandbox: practice only.</strong>
      {{ sandboxText }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ShieldAlert, TestTube } from 'lucide-vue-next'
import { isProduction } from './mode'

withDefaults(defineProps<{ productionText?: string, sandboxText?: string }>(), {
  productionText: 'This is charged to your MTurk account and cannot be undone.',
  sandboxText: 'Nothing is charged; MTurk’s sandbox uses play money.',
})
</script>

<style>
.mt-mode-banner.is-prod { border: 1px solid var(--danger); }
</style>
