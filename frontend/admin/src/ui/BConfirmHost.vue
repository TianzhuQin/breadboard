<template>
  <BDialog :open="confirmState.open" :title="req?.title" size="sm" @close="settleConfirm(false)">
    <p v-if="req?.message" class="b-confirm__msg">{{ req.message }}</p>
    <BField v-if="req?.typeToConfirm" :label="`Type “${req.typeToConfirm}” to confirm`" class="b-confirm__type">
      <template #default="{ id }">
        <BInput :id="id" v-model="typed" autocomplete="off" data-autofocus @keydown.enter.prevent="ok" />
      </template>
    </BField>
    <template #footer>
      <BButton variant="ghost" :data-autofocus="req?.typeToConfirm ? undefined : ''" @click="settleConfirm(false)">{{ req?.cancelLabel || 'Cancel' }}</BButton>
      <BButton :variant="req?.danger ? 'danger' : 'primary'" :disabled="!canConfirm" @click="ok">{{ req?.confirmLabel || 'Confirm' }}</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BDialog from './BDialog.vue'
import BButton from './BButton.vue'
import BField from './BField.vue'
import BInput from './BInput.vue'
import { confirmState, settleConfirm } from './confirm'

const req = computed(() => confirmState.req)
const typed = ref('')
watch(() => confirmState.open, () => { typed.value = '' })
const canConfirm = computed(() => !req.value?.typeToConfirm || typed.value.trim() === req.value.typeToConfirm.trim())
function ok () { if (canConfirm.value) settleConfirm(true) }
</script>

<style>
.b-confirm__msg { color: var(--text-soft); white-space: pre-line; }
.b-confirm__type { margin-top: 14px; }
</style>
