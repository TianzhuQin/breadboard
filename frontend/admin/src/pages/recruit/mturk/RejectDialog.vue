<template>
  <BDialog :open="open" :title="`Reject ${plural(workers.length, 'submission')}?`" size="md" :dismissable="!dirty" @close="emit('update:open', false)">
    <form id="mt-reject-form" class="rc-stack" @submit.prevent="submit">
      <ModeBanner production-text="Rejections affect real workers’ approval ratings and can’t be undone." sandbox-text="Sandbox workers aren’t real people; nothing is affected." />
      <p class="rc-muted">
        Rejected workers are not paid and their approval rating drops. Every worker below gets the
        same message from you.
      </p>
      <p class="mt-reject__who rc-mono">{{ workers.slice(0, 12).join(', ') }}<span v-if="workers.length > 12" class="rc-faint"> and {{ workers.length - 12 }} more</span></p>
      <BField label="Message to the workers" :error="showError && !reason.trim() ? 'Write a short reason. MTurk requires one.' : null" required>
        <template #default="{ id, describedby }">
          <textarea :id="id" v-model="reason" class="b-textarea" rows="3" :aria-describedby="describedby" data-autofocus />
        </template>
      </BField>
    </form>
    <template #footer>
      <BButton variant="ghost" @click="emit('update:open', false)">Cancel</BButton>
      <BButton type="submit" form="mt-reject-form" variant="danger" :icon="X">Reject {{ workers.length }}</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'
import { BButton, BDialog, BField } from '@/ui'
import ModeBanner from './ModeBanner.vue'
import { plural } from './rows'
import { readStore, writeStore } from '../storage'

const KEY = 'bb.recruit.mturk.rejectReason'
const DEFAULT = 'You did not complete the task correctly.'

const props = defineProps<{ open: boolean, workers: string[] }>()
const emit = defineEmits<{ 'update:open': [value: boolean], confirm: [reason: string] }>()

const reason = ref(readStore(KEY) || DEFAULT)
const showError = ref(false)
let initial = reason.value
const dirty = computed(() => reason.value !== initial)

watch(() => props.open, (o) => {
  if (o) { showError.value = false; initial = reason.value }
})

function submit () {
  if (!reason.value.trim()) { showError.value = true; return }
  writeStore(KEY, reason.value.trim())
  emit('confirm', reason.value.trim())
}
</script>

<style>
.mt-reject__who { font-size: var(--text-xs); color: var(--text-soft); overflow-wrap: anywhere; }
</style>
