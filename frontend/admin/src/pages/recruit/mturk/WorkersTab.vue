<template>
  <div class="rc-stack">
    <p class="rc-muted wk__intro">
      Everyone who has submitted one of your HITs on the {{ api.sandbox ? 'sandbox' : 'production' }} site, across all
      experiments. <strong class="rc-strong">Completed</strong> marks a worker as having finished an experiment; HITs set to
      turn away previous participants keep them out.
    </p>

    <div class="rc-toolbar">
      <div class="rc-toolbar__grow wk__search">
        <label for="wk-search" class="sr-only">Search by worker ID</label>
        <BInput id="wk-search" v-model="search" :icon="Search" placeholder="Worker ID starts with…" autocomplete="off" spellcheck="false" />
      </div>
      <div class="wk__size">
        <label for="wk-size" class="rc-sub">Per page</label>
        <BSelect id="wk-size" v-model="limit" :options="[25, 50, 100]" size="sm" />
      </div>
      <BButton :icon="RefreshCw" :loading="loading" @click="load()">Refresh</BButton>
      <BButton :icon="UserPlus" :disabled="!admin.experiment" :title="admin.experiment ? undefined : 'Choose an experiment first'" @click="importOpen = true">Add past participants…</BButton>
    </div>

    <ErrorNote v-if="error" title="Could not load the workers" :message="error.message" :detail="error.detail" :on-retry="() => load()" />

    <div v-if="loading && !workers.length" class="rc-table-wrap" role="status" aria-label="Loading workers">
      <div class="rc-skeleton"><div v-for="i in 4" :key="i" class="rc-skeleton__bar" :style="{ width: `${80 - i * 12}%` }" /></div>
    </div>

    <BEmpty
      v-else-if="!error && !workers.length"
      :icon="Users"
      :title="query ? 'No matching workers' : 'No workers yet'"
      :text="query ? `No worker ID starts with “${query}”.` : 'Workers appear here once they submit one of your HITs, or after you add past participants.'"
    />

    <template v-else-if="workers.length">
      <div ref="wrap" class="rc-table-wrap" :style="{ '--rc-box-w': boxWidth ? `${boxWidth}px` : '100%' }">
        <table class="rc-table">
          <caption class="sr-only">Workers {{ rangeLabel }}</caption>
          <thead>
            <tr>
              <th scope="col" class="shrink"><span class="sr-only">Open</span></th>
              <th scope="col">Worker ID</th>
              <th scope="col" class="num">Submissions</th>
              <th scope="col" class="num">Completed</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="w in workers" :key="w.id">
              <tr class="is-clickable" :class="{ 'is-open': open === w.id }" @click="toggle(w.id)">
                <td class="shrink">
                  <button
                    type="button"
                    class="rc-expand"
                    :aria-expanded="open === w.id ? 'true' : 'false'"
                    :aria-controls="`wk-${w.id}`"
                    :aria-label="`${open === w.id ? 'Hide' : 'Show'} submissions of ${w.id}`"
                    @click.stop="toggle(w.id)"
                  >
                    <ChevronRight :size="16" aria-hidden="true" />
                  </button>
                </td>
                <td class="rc-mono rc-strong">{{ w.id }}</td>
                <td class="num">{{ w.nAssignments }}</td>
                <td class="num">{{ w.assignmentsCompleted }}</td>
              </tr>
              <tr v-if="open === w.id">
                <td :id="`wk-${w.id}`" colspan="4" class="rc-detail-cell">
                  <div class="wk__detail rc-pinned">
                    <div class="rc-table-wrap">
                      <table class="rc-table rc-table--compact">
                        <caption class="sr-only">Submissions of {{ w.id }}</caption>
                        <thead>
                          <tr>
                            <th scope="col">Experiment</th>
                            <th scope="col">Assignment</th>
                            <th scope="col">Status</th>
                            <th scope="col">Submitted</th>
                            <th scope="col">Completed</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="a in w.assignments" :key="a.assignmentId">
                            <td>{{ a.experimentName || '—' }}</td>
                            <td class="rc-mono rc-faint">{{ a.assignmentId }}</td>
                            <td>{{ statusLabel(a.assignmentStatus) }}<div v-if="a.reason && a.reason !== 'IMPORTED'" class="rc-sub">{{ a.reason }}</div></td>
                            <td class="nowrap">{{ a.submitTime ? formatTime(a.submitTime) : '—' }}</td>
                            <td>
                              <BSwitch :model-value="a.assignmentCompleted" :disabled="busy.has(a.assignmentId)" @update:model-value="(v: boolean) => setCompleted(w, a, v)">
                                <span class="sr-only">{{ w.id }} completed {{ a.experimentName || 'this experiment' }}</span>
                              </BSwitch>
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <Pager :label="rangeLabel" :has-prev="offset > 0" :has-next="offset + workers.length < total" :loading="loading" aria-label="Worker pages" @prev="go(offset - limit)" @next="go(offset + limit)" />
    </template>

    <ImportWorkersDialog v-model:open="importOpen" :api="api" :experiment="admin.experiment" @done="load()" />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ChevronRight, RefreshCw, Search, UserPlus, Users } from 'lucide-vue-next'
import { BButton, BEmpty, BInput, BSelect, BSwitch, notify } from '@/ui'
import { admin } from '@/store/admin'
import ErrorNote from '../ErrorNote.vue'
import Pager from './Pager.vue'
import ImportWorkersDialog from './ImportWorkersDialog.vue'
import type { MturkApi, WorkerAssignment, WorkerRow } from './api'
import { describeError, errorLine, formatTime, type ErrorText } from './lib'
import { useBoxWidth } from '../useBoxWidth'

const props = defineProps<{ api: MturkApi }>()

const workers = ref<WorkerRow[]>([])
const total = ref(0)
const offset = ref(0)
const limit = ref(25)
const search = ref('')
const loading = ref(false)
const error = ref<ErrorText | null>(null)
const open = ref<string | null>(null)
const importOpen = ref(false)
const busy = reactive(new Set<string>())
const wrap = ref<HTMLElement>()
const boxWidth = useBoxWidth(wrap)

/** Worker ids are upper-case letters and digits; anything else would act as a database wildcard. */
const query = computed(() => search.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))
const rangeLabel = computed(() => workers.value.length ? `${offset.value + 1}–${offset.value + workers.value.length} of ${total.value}` : '')

let seq = 0
async function load (at = offset.value) {
  const mine = ++seq
  loading.value = true
  error.value = null
  try {
    // the server lists workers of all experiments; the id in the address is required but unused
    const res = await props.api.workers(admin.experiment?.id ?? 0, { limit: limit.value, offset: Math.max(0, at), search: query.value })
    if (mine !== seq) return
    workers.value = [...(res.amtWorkers || [])].sort((a, b) => a.id.localeCompare(b.id))
    total.value = res.total || 0
    offset.value = Math.max(0, at)
    if (!workers.value.some(w => w.id === open.value)) open.value = null
  } catch (e) {
    if (mine !== seq) return
    error.value = describeError(e)
  } finally {
    if (mine === seq) loading.value = false
  }
}

function go (at: number) { load(Math.max(0, at)) }
function toggle (id: string) { open.value = open.value === id ? null : id }

let debounce: ReturnType<typeof setTimeout> | null = null
watch(query, () => {
  if (debounce) clearTimeout(debounce)
  debounce = setTimeout(() => load(0), 400)
})
watch(limit, () => load(0))
onBeforeUnmount(() => { if (debounce) clearTimeout(debounce) })

const STATUS: Record<string, string> = { Submitted: 'To review', Approved: 'Approved', Rejected: 'Rejected', IMPORTED: 'Added by hand' }
function statusLabel (s: string | null) {
  return s ? STATUS[s] || s : '—'
}

async function setCompleted (w: WorkerRow, a: WorkerAssignment, value: boolean) {
  busy.add(a.assignmentId)
  const before = a.assignmentCompleted
  a.assignmentCompleted = value
  try {
    await props.api.setCompleted(a.assignmentId, value)
    w.assignmentsCompleted = w.assignments.filter(x => x.assignmentCompleted).length
  } catch (e) {
    a.assignmentCompleted = before
    notify.error('Could not save “completed”', errorLine(e))
  } finally {
    busy.delete(a.assignmentId)
  }
}

onMounted(() => load(0))
</script>

<style>
.wk__intro { font-size: var(--text-sm); }
.wk__search { max-width: 360px; }
.wk__size { display: flex; align-items: center; gap: 8px; }
.wk__detail { padding: 12px 16px 14px 48px; }
@media (max-width: 640px) { .wk__detail { padding: 10px; } }
</style>
