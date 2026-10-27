<template>
  <div class="fm-notice" role="note">
    <HardDrive class="fm-notice__icon" :size="18" aria-hidden="true" />
    <div class="fm-notice__text">
      <p class="fm-notice__title">Read only: this experiment is in file mode</p>
      <p class="fm-notice__body">
        {{ what }} come from the files in <code>{{ folder }}</code> on the server, so change them there; the console picks
        up saved files by itself. To edit here again, turn file mode off on the Experiments page.
      </p>
    </div>
    <BButton size="sm" :icon-right="ArrowRight" href="#/experiments">Experiments</BButton>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight, HardDrive } from 'lucide-vue-next'
import { BButton } from '@/ui'
import { admin } from '@/store/admin'

/** Shown above pages that can't be edited while the experiment is read from files (DL-33). */
defineProps<{ what: string }>()

// Experiment.getDirectoryName(): spaces become dashes
const folder = computed(() => {
  const e = admin.experiment
  return e ? `dev/${e.name.replace(/ /g, '-')}_${e.id}/` : 'dev/'
})
</script>

<style>
.fm-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--warning);
  border-radius: var(--radius);
  background: var(--warning-soft);
}
.fm-notice__icon { flex: none; margin-top: 2px; color: var(--warning-soft-text); }
.fm-notice__text { flex: 1 1 280px; min-width: 0; }
.fm-notice__title { font-weight: 600; color: var(--text); }
.fm-notice__body { margin-top: 2px; font-size: var(--text-sm); color: var(--text-soft); }
.fm-notice__body code { padding: 1px 5px; border-radius: 5px; background: var(--surface); word-break: break-all; }
@media (max-width: 640px) {
  .fm-notice .b-btn { margin-left: 30px; }
}
</style>
