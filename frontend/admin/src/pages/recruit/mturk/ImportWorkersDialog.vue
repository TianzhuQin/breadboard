<template>
  <BDialog :open="open" title="Add past participants" :subtitle="experiment ? `For ${experiment.name}` : undefined" size="md" :dismissable="!text.trim()" @close="emit('update:open', false)">
    <form id="mt-import-form" class="rc-stack" @submit.prevent="submit">
      <p class="rc-muted">
        Record workers who already took part in this experiment, for example in an earlier run, so
        HITs set to turn away previous participants keep them out. They are marked
        <strong>Completed</strong> on the {{ api.sandbox ? 'sandbox' : 'production' }} site.
      </p>
      <BField label="Worker IDs" :error="tried && !ids.length ? 'Add at least one worker ID.' : null">
        <template #default="{ id, describedby }">
          <WorkerIdsInput :id="id" v-model="text" :describedby="describedby" :disabled="busy" />
        </template>
      </BField>
      <p class="rc-sub">Breadboard keeps them under a stopped placeholder session named “IMPORTED_…” in this experiment’s sessions. Nothing is sent to MTurk.</p>
      <ErrorNote v-if="error" title="The workers were not added" :message="error.message" :detail="error.detail" />
    </form>
    <template #footer>
      <BButton variant="ghost" @click="emit('update:open', false)">Cancel</BButton>
      <BButton type="submit" form="mt-import-form" variant="primary" :icon="UserPlus" :loading="busy">Add {{ ids.length || '' }} worker{{ ids.length === 1 ? '' : 's' }}</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { UserPlus } from 'lucide-vue-next'
import { BButton, BDialog, BField, notify } from '@/ui'
import type { Experiment } from '@/store/types'
import ErrorNote from '../ErrorNote.vue'
import WorkerIdsInput from './WorkerIdsInput.vue'
import type { MturkApi } from './api'
import { describeError, parseWorkerIds, type ErrorText } from './lib'

const props = defineProps<{ open: boolean, api: MturkApi, experiment: Experiment | null }>()
const emit = defineEmits<{ 'update:open': [value: boolean], done: [] }>()

const text = ref('')
const tried = ref(false)
const busy = ref(false)
const error = ref<ErrorText | null>(null)
const ids = computed(() => parseWorkerIds(text.value).ids)

watch(() => props.open, (o) => { if (o) { tried.value = false; error.value = null } })

async function submit () {
  tried.value = true
  if (!ids.value.length || !props.experiment) return
  busy.value = true
  error.value = null
  try {
    await props.api.importWorkers(props.experiment.id, ids.value)
    notify.success(`Added ${ids.value.length} past participant${ids.value.length === 1 ? '' : 's'}`, props.experiment.name)
    text.value = ''
    emit('done')
    emit('update:open', false)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    busy.value = false
  }
}
</script>
