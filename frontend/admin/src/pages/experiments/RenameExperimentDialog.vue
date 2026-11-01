<template>
  <BDialog
    :open="open"
    title="Rename experiment"
    :subtitle="exp ? `Its sessions and data stay with it.` : ''"
    :dismissable="!busy"
    :closable="!busy"
    @update:open="v => !v && close()"
  >
    <form :id="formId" class="xren" novalidate @submit.prevent="submit">
      <p v-if="error" class="xnew__error" role="alert">{{ error }}</p>
      <BField label="Name" required :error="nameError" hint="Links to its sessions keep working. An autopilot set up by name in the server's settings file (conf/local.conf) needs the new name there too.">
        <template #default="{ id, describedby }">
          <BInput
            :id="id"
            v-model="name"
            data-autofocus
            autocomplete="off"
            maxlength="120"
            :aria-describedby="describedby"
            :aria-invalid="nameError ? 'true' : undefined"
          />
        </template>
      </BField>
    </form>
    <template #footer>
      <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
      <BButton variant="primary" type="submit" :form="formId" :loading="busy" :disabled="!changed">Rename</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BButton, BDialog, BField, BInput, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { http } from '@/lib/http'
import { actions, experiments } from '@/store/admin'
import type { Experiment } from '@/store/types'
import { describeError, validateExperimentName } from './experiments'

const props = defineProps<{ open: boolean, exp: Experiment | null }>()
const emit = defineEmits<{ 'update:open': [v: boolean], renamed: [exp: Experiment] }>()

const formId = uid('xren')
const name = ref('')
const busy = ref(false)
const error = ref('')

watch(() => props.open, (open) => {
  if (!open) return
  name.value = props.exp?.name || ''
  error.value = ''
  busy.value = false
}, { immediate: true })

const changed = computed(() => !!props.exp && name.value.trim() !== props.exp.name)
const nameError = computed(() => {
  if (!props.exp || !changed.value) return null
  return validateExperimentName(name.value, experiments.value.filter(e => e.id !== props.exp!.id).map(e => e.name))
})

function close () {
  if (busy.value) return
  emit('update:open', false)
}

async function submit () {
  const exp = props.exp
  if (!exp || !changed.value || nameError.value) return
  busy.value = true
  error.value = ''
  const newName = name.value.trim()
  try {
    const saved = await http.post<Experiment>(`/experiment/${exp.id}/name`, { name: newName })
    await actions.refresh()
    notify.success(`Renamed to “${newName}”`)
    emit('renamed', saved)
    busy.value = false
    emit('update:open', false)
  } catch (e) {
    error.value = `Couldn't rename it. ${describeError(e)}`
    busy.value = false
  }
}
</script>

<style>
.xren { display: flex; flex-direction: column; gap: 14px; }
</style>
