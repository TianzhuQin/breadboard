<template>
  <div class="needs-exp">
    <BEmpty
      :icon="FlaskConical"
      :title="experiments.length ? 'Choose an experiment' : 'Create your first experiment'"
      :text="experiments.length ? 'This page works on one experiment. Pick it in the switcher at the top left, or here.' : 'An experiment holds the steps, content and settings that run a study.'"
    >
      <template #actions>
        <template v-if="experiments.length">
          <BButton v-for="e in experiments.slice(0, 6)" :key="e.id" @click="actions.selectExperiment(e.id)">{{ e.name }}</BButton>
        </template>
        <BButton variant="primary" :icon="Plus" @click="router.push('/experiments?action=new')">New experiment</BButton>
        <BButton :icon="Upload" @click="router.push('/experiments?action=import')">Import</BButton>
      </template>
    </BEmpty>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { FlaskConical, Plus, Upload } from 'lucide-vue-next'
import { BButton, BEmpty } from '@/ui'
import { actions, experiments } from '@/store/admin'
const router = useRouter()
</script>

<style>
.needs-exp { padding: 8vh 24px; }
</style>
