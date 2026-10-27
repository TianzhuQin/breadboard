<template>
  <BDialog :open="open" title="Save your changes before leaving?" size="md" :dismissable="!saving" :closable="!saving" @update:open="v => { if (!v) emit('choose', 'stay') }">
    <p class="unsaved__lead">
      {{ names.length === 1 ? 'One step has' : `${names.length} steps have` }} unsaved changes:
      <strong>{{ shown.join(', ') }}</strong><template v-if="names.length > shown.length"> and {{ names.length - shown.length }} more</template>.
    </p>
    <p class="unsaved__note">
      If you leave without saving, the edits stay in this tab for when you come back, but the experiment and the live engine keep the saved versions, and closing the tab loses them.
    </p>
    <template #footer>
      <div class="unsaved__actions">
        <BButton variant="ghost" :disabled="saving" data-autofocus @click="emit('choose', 'stay')">Stay</BButton>
        <BButton :disabled="saving" @click="emit('choose', 'leave')">Leave without saving</BButton>
        <BButton variant="primary" :icon="Save" :loading="saving" @click="emit('choose', 'save')">Save all and leave</BButton>
      </div>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Save } from 'lucide-vue-next'
import { BButton, BDialog } from '@/ui'
import type { LeaveChoice } from './stepsModel'

const props = defineProps<{ open: boolean, names: string[], saving: boolean }>()
const emit = defineEmits<{ choose: [choice: LeaveChoice] }>()
const shown = computed(() => props.names.slice(0, 4))
</script>

<style>
.unsaved__lead { color: var(--text-soft); line-height: 1.5; }
.unsaved__lead strong { color: var(--text); font-weight: 600; }
.unsaved__actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; width: 100%; }
.unsaved__note { margin-top: 10px; color: var(--muted); font-size: var(--text-sm); line-height: 1.5; }
</style>
