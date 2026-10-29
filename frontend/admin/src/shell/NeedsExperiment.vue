<template>
  <div class="needs-exp">
    <BEmpty
      :icon="FlaskConical"
      :title="experiments.length ? 'Choose an experiment' : 'Create your first experiment'"
      :text="experiments.length ? 'This page works on one experiment. Pick it in the switcher at the top left, or here.' : 'An experiment holds the steps, content and settings that run a study.'"
    >
      <template #actions>
        <template v-if="experiments.length">
          <BButton v-for="e in experiments.slice(0, 6)" :key="e.id" :loading="picking === e.id" :disabled="picking !== null && picking !== e.id" @click="pick(e)">{{ e.name }}</BButton>
        </template>
        <BButton variant="primary" :icon="Plus" @click="router.push('/experiments?action=new')">New experiment</BButton>
        <BButton :icon="Upload" @click="router.push('/experiments?action=import')">Import</BButton>
      </template>
    </BEmpty>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { FlaskConical, Plus, Upload } from 'lucide-vue-next'
import { BButton, BEmpty, notify } from '@/ui'
import { admin, actions, ensureConnected, experiments } from '@/store/admin'
import { confirmSwitch } from '@/store/engineGuard'
import type { Experiment } from '@/store/types'
const router = useRouter()

const picking = ref<number | null>(null)
async function pick (e: Experiment) {
  if (picking.value !== null || !ensureConnected()) return
  // the engine is shared: a live session there is left running; otherwise the engine loads this one
  if (!await confirmSwitch({ title: `Open “${e.name}”?`, action: 'Opening an experiment', confirmLabel: 'Open anyway' })) return
  picking.value = e.id
  try {
    await actions.selectExperiment(e.id)
  } finally {
    picking.value = null
  }
  if (admin.experiment?.id !== e.id) {
    notify.error(`Could not open ${e.name}`, admin.requestError?.request === 'SelectExperiment' ? admin.requestError.text : 'The server did not confirm the switch. The output panel may say why.')
  }
}
</script>

<style>
.needs-exp { padding: 8vh 24px; }
</style>
