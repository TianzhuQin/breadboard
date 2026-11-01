<template>
  <div v-if="notice" class="session-end-notice" role="status">
    <CircleStop :size="17" class="session-end-notice__icon" aria-hidden="true" />
    <p class="session-end-notice__text">
      <strong>“{{ notice.sessionName }}” ended.</strong> {{ text }} Its recorded data is kept.
    </p>
    <button type="button" class="session-end-notice__close" aria-label="Dismiss session end notice" @click="dismissSessionEndNotice">
      <X :size="17" aria-hidden="true" />
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleStop, X } from 'lucide-vue-next'
import { admin, dismissSessionEndNotice } from '@/store/admin'
import { sessionStoppedText } from '@/store/sessionStopped'

const notice = computed(() => admin.sessionEndNotice)
const text = computed(() => notice.value ? sessionStoppedText(notice.value.by, notice.value.reason, admin.email) : '')
</script>

<style>
.session-end-notice {
  display: flex;
  align-items: flex-start;
  flex: none;
  gap: 10px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: var(--text-sm);
}
.session-end-notice__icon { flex: none; margin-top: 2px; }
.session-end-notice__text { flex: 1; min-width: 0; margin: 0; overflow-wrap: anywhere; }
.session-end-notice__text strong { font-weight: 650; }
.session-end-notice__close { display: grid; place-items: center; flex: none; min-width: 28px; min-height: 28px; margin: -4px -4px -4px 0; padding: 4px; border: 0; border-radius: 4px; background: transparent; color: inherit; cursor: pointer; }
.session-end-notice__close:hover { background: var(--surface-hover); }
.session-end-notice__close:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
@media (max-width: 900px) {
  .session-end-notice { padding: 10px 12px; }
}
</style>
