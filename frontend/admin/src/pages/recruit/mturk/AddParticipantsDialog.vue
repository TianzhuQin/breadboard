<template>
  <BDialog :open="open" title="Grant the qualification" :subtitle="experimentName" size="md" :dismissable="!running && !text.trim()" @close="close">
    <form v-if="!items.length" id="mt-addq-form" class="rc-stack" @submit.prevent="start">
      <p class="rc-muted">
        Give this experiment’s participant qualification to workers who already took part, so HITs
        that exclude previous participants keep them out. MTurk doesn’t notify them.
      </p>
      <BField label="Worker IDs" :error="tried && !ids.length ? 'Add at least one worker ID.' : null">
        <template #default="{ id, describedby }">
          <WorkerIdsInput :id="id" v-model="text" :describedby="describedby" />
        </template>
      </BField>
    </form>

    <div v-else class="rc-stack">
      <div class="rc-progress" role="progressbar" :aria-valuenow="finished" aria-valuemin="0" :aria-valuemax="items.length" :aria-label="`${finished} of ${items.length} done`">
        <div class="rc-progress__bar" :style="{ width: `${(finished / items.length) * 100}%` }" />
      </div>
      <p :class="failed ? 'rc-error-text' : 'rc-sub'" aria-live="polite">
        {{ running ? `Granting… ${finished} of ${items.length}` : `${done} granted${failed ? `, ${failed} failed` : ''}.` }}
      </p>
      <div class="rc-table-wrap addq__list">
        <table class="rc-table rc-table--compact">
          <caption class="sr-only">Progress by worker</caption>
          <thead><tr><th scope="col">Worker</th><th scope="col">Status</th></tr></thead>
          <tbody>
            <tr v-for="it in items" :key="it.workerId">
              <td class="rc-mono">{{ it.workerId }}</td>
              <td>
                <span v-if="it.state === 'waiting'" class="rc-faint">Waiting</span>
                <span v-else-if="it.state === 'sending'" class="rc-busy-text"><BSpinner :size="12" label="Granting" /> Granting…</span>
                <BBadge v-else-if="it.state === 'done'" tone="success">Granted</BBadge>
                <span v-else class="rc-error-text">{{ it.error }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <template #footer>
      <template v-if="!items.length">
        <BButton variant="ghost" @click="close">Cancel</BButton>
        <BButton type="submit" form="mt-addq-form" variant="primary" :icon="Award">Grant to {{ ids.length || '' }} worker{{ ids.length === 1 ? '' : 's' }}</BButton>
      </template>
      <template v-else>
        <BButton v-if="!running && failed" :icon="RotateCw" @click="send(items.filter(i => i.state === 'failed'))">Try the failed again</BButton>
        <BButton variant="primary" :disabled="running" @click="close">Done</BButton>
      </template>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Award, RotateCw } from 'lucide-vue-next'
import { BBadge, BButton, BDialog, BField, BSpinner, notify } from '@/ui'
import WorkerIdsInput from './WorkerIdsInput.vue'
import type { MturkApi } from './api'
import { errorLine, mapPool, parseWorkerIds } from './lib'

const props = defineProps<{ open: boolean, api: MturkApi, typeId: string, experimentName: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean], done: [] }>()

interface Item { workerId: string, state: 'waiting' | 'sending' | 'done' | 'failed', error: string }

const text = ref('')
const tried = ref(false)
const items = ref<Item[]>([])
const running = ref(false)
const ids = computed(() => parseWorkerIds(text.value).ids)
const finished = computed(() => items.value.filter(i => i.state === 'done' || i.state === 'failed').length)
const done = computed(() => items.value.filter(i => i.state === 'done').length)
const failed = computed(() => items.value.filter(i => i.state === 'failed').length)

// Every opening starts fresh (the old dialog's button stayed disabled after one use).
watch(() => props.open, (o) => { if (o) { tried.value = false; items.value = [] } })

async function start () {
  tried.value = true
  if (!ids.value.length) return
  items.value = ids.value.map(workerId => ({ workerId, state: 'waiting', error: '' }))
  await send(items.value)
}

async function send (list: Item[]) {
  running.value = true
  try {
    await mapPool(list, 4, async (it) => {
      it.state = 'sending'
      it.error = ''
      try {
        await props.api.grantQualification(it.workerId, props.typeId)
        it.state = 'done'
      } catch (e) {
        it.state = 'failed'
        it.error = errorLine(e)
      }
    })
  } finally {
    running.value = false
  }
  if (done.value) emit('done')
  if (!failed.value) {
    notify.success(`Granted to ${done.value} worker${done.value === 1 ? '' : 's'}`)
    text.value = ''
  }
}

function close () {
  if (running.value) return
  emit('update:open', false)
}
</script>

<style>
.addq__list { max-height: 320px; overflow: auto; }
</style>
