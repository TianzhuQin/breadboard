<template>
  <!-- while the server can't be reached, what every page shows may be out of date -->
  <p v-if="shown" class="conn-notice" role="status">
    <WifiOff :size="16" class="conn-notice__icon" aria-hidden="true" />
    <span class="conn-notice__text">
      <strong>Not connected to the server.</strong> The page shows what was last known, and what needs
      the server works again once the console reconnects. If Breadboard was stopped, start it again;
      the console reconnects by itself.
    </span>
  </p>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { WifiOff } from 'lucide-vue-next'
import { admin, connectionState } from '@/store/admin'

// Once the first state has arrived: before that, the shell shows its own loading state (and the
// sign-in page says when the server can't be reached at all).
const shown = computed(() => admin.ready && connectionState.value !== 'open')
</script>

<style>
.conn-notice {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 9px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: var(--text-sm);
}
.conn-notice__icon { flex: none; margin-top: 2px; }
.conn-notice__text { flex: 1; min-width: 0; }
.conn-notice__text strong { font-weight: 650; }
@media (max-width: 900px) {
  .conn-notice { padding: 9px 12px; }
}
</style>
