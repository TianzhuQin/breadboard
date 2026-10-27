<template>
  <div class="qw rc-stack">
    <div class="rc-toolbar">
      <h4 class="qw__title rc-toolbar__grow">Workers who hold it</h4>
      <label :for="`qw-size-${typeId}`" class="rc-sub">Per page</label>
      <BSelect :id="`qw-size-${typeId}`" v-model="maxResults" :options="[25, 50, 100]" size="sm" />
      <BButton size="sm" variant="ghost" :icon="RefreshCw" :loading="loading" @click="load(page)">Refresh</BButton>
      <BButton size="sm" variant="primary" :icon="UserPlus" @click="addOpen = true">Grant to workers…</BButton>
    </div>

    <ErrorNote v-if="error" title="Could not load the workers" :message="error.message" :detail="error.detail" :on-retry="() => load(page)" />
    <div v-if="loading && !list.length" class="rc-loading" role="status"><BSpinner :size="14" /> Loading…</div>
    <p v-else-if="!error && !list.length" class="rc-muted qw__none">Nobody holds this qualification yet. Grant it on the HITs tab after a session, or add workers here.</p>
    <template v-else-if="list.length">
      <div class="rc-table-wrap">
        <table class="rc-table rc-table--compact">
          <caption class="sr-only">Workers holding the qualification</caption>
          <thead>
            <tr>
              <th scope="col">Worker</th>
              <th scope="col">Status</th>
              <th scope="col" class="num">Value</th>
              <th scope="col">Granted</th>
              <th scope="col" class="shrink"><span class="sr-only">Remove</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="q in list" :key="q.workerId">
              <td class="rc-mono">{{ q.workerId }}</td>
              <td>{{ q.status }}</td>
              <td class="num">{{ q.integerValue }}</td>
              <td class="nowrap">{{ formatTime(q.grantTime) }}</td>
              <td class="shrink">
                <BButton size="sm" variant="danger-ghost" :icon="UserMinus" :loading="removing.has(q.workerId)" :aria-label="`Remove the qualification from ${q.workerId}`" title="Remove" @click="remove(q.workerId)" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <Pager :label="`Page ${page + 1}`" :has-prev="page > 0" :has-next="!!tokens[page + 1]" :loading="loading" aria-label="Pages of workers" @prev="load(page - 1)" @next="load(page + 1)" />
    </template>

    <AddParticipantsDialog v-model:open="addOpen" :api="api" :type-id="typeId" :experiment-name="experimentName" @done="load(0)" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { RefreshCw, UserMinus, UserPlus } from 'lucide-vue-next'
import { BButton, BSelect, BSpinner, confirm, notify } from '@/ui'
import ErrorNote from '../ErrorNote.vue'
import Pager from './Pager.vue'
import AddParticipantsDialog from './AddParticipantsDialog.vue'
import type { MturkApi, QualifiedWorker } from './api'
import { describeError, errorLine, formatTime, type ErrorText } from './lib'

const props = defineProps<{ api: MturkApi, typeId: string, experimentName: string }>()

const list = ref<QualifiedWorker[]>([])
const tokens = ref<(string | null)[]>([null])
const page = ref(0)
const maxResults = ref(25)
const loading = ref(false)
const error = ref<ErrorText | null>(null)
const addOpen = ref(false)
const removing = reactive(new Set<string>())

async function load (p: number) {
  if (p < 0) return
  if (p === 0) tokens.value = [null]
  loading.value = true
  error.value = null
  try {
    const res = await props.api.workersWithQualification(props.typeId, tokens.value[p] ?? null, maxResults.value)
    const rows = res.qualifications || []
    if (!rows.length && p > 0) {
      tokens.value = tokens.value.slice(0, p)
      notify.info('No more workers', 'That was the last page.')
      return
    }
    list.value = [...rows].sort((a, b) => a.workerId.localeCompare(b.workerId))
    page.value = p
    tokens.value = tokens.value.slice(0, p + 1)
    if (res.nextToken && rows.length) tokens.value.push(res.nextToken)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function remove (workerId: string) {
  const ok = await confirm({
    title: `Remove the qualification from ${workerId}?`,
    message: `They will be able to take HITs that exclude earlier participants of ${props.experimentName} again. MTurk tells the worker: “Allowing re-participation in this experiment type.”`,
    confirmLabel: 'Remove',
    danger: true,
  })
  if (!ok) return
  removing.add(workerId)
  try {
    await props.api.removeWorkerQualification(props.typeId, workerId)
    list.value = list.value.filter(q => q.workerId !== workerId)
    notify.success('Qualification removed', workerId)
  } catch (e) {
    notify.error('Could not remove the qualification', errorLine(e))
  } finally {
    removing.delete(workerId)
  }
}

watch(maxResults, () => load(0))
onMounted(() => load(0))
</script>

<style>
.qw__title { font-size: var(--text-md); }
.qw__none { font-size: var(--text-sm); }
</style>
