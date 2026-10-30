<template>
  <BPanel title="From Prolific in every session" subtitle="Who joined and who finished, with their codes and bonuses, newest session first.">
    <template #actions>
      <BButton size="sm" variant="ghost" :icon="RotateCw" :loading="loading" aria-label="Refresh the list" title="Refresh the list" @click="load" />
    </template>

    <div class="rc-stack">
      <div v-if="loading && !loaded" class="pl-hist__state" role="status">
        <BSpinner :size="16" /> <span>Loading the sessions' Prolific participants…</span>
      </div>
      <ErrorNote v-else-if="error && !loaded" title="Couldn't load the Prolific participants" :message="error" :on-retry="load" />
      <p v-else-if="!records.length" class="rc-muted pl-hist__empty">
        Nobody has joined this experiment from Prolific yet. Each session's participants are listed here as they arrive,
        and stay listed after the session ends.
      </p>

      <template v-else>
        <div class="pl-hist__top">
          <p class="pl-players__sum" aria-live="polite">
            <strong>{{ records.length }}</strong> joined · <strong>{{ finished }}</strong> finished<template v-if="noCode">
              ({{ noCode }} without a code)</template> · in <strong>{{ groups.length }}</strong> {{ groups.length === 1 ? 'session' : 'sessions' }}
          </p>
          <div v-if="bonusLines" class="pl-players__bonus">
            <CopyButton :text="bonusLines" size="sm" label="Copy bonus list" />
            <span class="rc-sub">Every session's bonuses, for Prolific’s bulk bonus: one “ID,amount” per line.</span>
          </div>
        </div>

        <div class="rc-table-wrap pl-hist__wrap">
          <table class="rc-table rc-table--compact pl-hist">
            <thead>
              <tr>
                <th scope="col">Prolific ID</th>
                <th scope="col">Joined</th>
                <th scope="col">Finished</th>
                <th scope="col">Code</th>
                <th scope="col" class="num">Bonus</th>
              </tr>
            </thead>
            <tbody v-for="g in groups" :key="g.sessionId">
              <tr class="pl-hist__session">
                <!-- (spaces between the parts, for screen readers and copied text) -->
                <th colspan="5" scope="colgroup">
                  <span class="pl-hist__name">{{ g.sessionName }}</span>{{ ' ' }}
                  <BBadge :tone="statusMeta(g.sessionStatus).tone">{{ statusMeta(g.sessionStatus).label }}</BBadge>{{ ' ' }}
                  <span class="pl-hist__count">{{ g.joined }} joined · {{ g.finished }} finished</span>
                </th>
              </tr>
              <tr v-for="r in g.records" :key="r.player">
                <td>
                  <span class="rc-mono pl-hist__pid" :title="r.prolificPid || undefined">{{ r.prolificPid || '—' }}</span>{{ ' ' }}
                  <span class="rc-sub pl-hist__player">Player {{ r.player }}</span>
                </td>
                <td class="pl-hist__when">{{ when(r.joinedAt) }}</td>
                <td>
                  <BBadge v-if="r.completed" tone="success">Finished</BBadge>
                  <BBadge v-else-if="r.finishedWithoutCode" tone="warning" title="The game ended for them, but no completion code was set">No code</BBadge>
                  <span v-else class="rc-faint">{{ g.sessionStatus === 'RUNNING' ? 'Not yet' : 'No' }}</span>
                </td>
                <td class="rc-mono">{{ r.completionCode }}</td>
                <td class="num">{{ r.bonus != null ? money(r.bonus) : '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </BPanel>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RotateCw } from 'lucide-vue-next'
import { BBadge, BButton, BPanel, BSpinner } from '@/ui'
import { admin } from '@/store/admin'
import CopyButton from '../CopyButton.vue'
import ErrorNote from '../ErrorNote.vue'
import { formatMoney } from '../mturk/lib'
import { formatDateTime, parseServerTime, statusMeta } from '../../sessions/sessionData'
import { groupBySession, loadProlificRecords, prolificBonusLines, type ProlificRecord } from './prolific'

/**
 * Every session's Prolific participants, from the sessions' data (GET /experiment/:id/prolific): the
 * live list beside it only shows the session the engine runs, which the autopilot replaces seconds
 * after a game ends.
 */
const props = defineProps<{ experimentId: number }>()

const records = ref<ProlificRecord[]>([])
const loading = ref(false)
const loaded = ref(false)
const error = ref('')
let seq = 0

async function load () {
  const mine = ++seq
  const id = props.experimentId
  loading.value = true
  try {
    const list = await loadProlificRecords(id)
    if (mine !== seq) return
    records.value = list
    loaded.value = true
    error.value = ''
  } catch (e: any) {
    if (mine !== seq) return
    error.value = e?.message || 'The server did not answer.'
  } finally {
    if (mine === seq) loading.value = false
  }
}

watch(() => props.experimentId, () => {
  records.value = []
  loaded.value = false
  void load()
}, { immediate: true })

// Look again when a session opens or ends, and as Prolific participants arrive or finish in the one
// shown (a little later: such pushes come in bursts).
let soon: ReturnType<typeof setTimeout> | undefined
function loadSoon () {
  clearTimeout(soon)
  soon = setTimeout(() => { void load() }, 1500)
}
const PROLIFIC_EVENTS = new Set(['prolificEntry', 'prolificComplete', 'noCompletionCode'])
const prolificNews = computed(() => (admin.events || []).filter(e => PROLIFIC_EVENTS.has(e.name)).length)
watch(() => [admin.instance?.id, admin.instance?.status, prolificNews.value], loadSoon)
onBeforeUnmount(() => clearTimeout(soon))

const groups = computed(() => groupBySession(records.value))
const finished = computed(() => records.value.filter(r => r.completed || r.finishedWithoutCode).length)
const noCode = computed(() => records.value.filter(r => r.finishedWithoutCode && !r.completed).length)
const bonusLines = computed(() => prolificBonusLines(records.value))
// Prolific pays in the study's currency, so no currency sign here
const money = (n: number) => formatMoney(n).replace(/^\$/, '')
const when = (iso: string | null) => (iso ? formatDateTime(parseServerTime(iso)) : '—')
</script>

<style>
.pl-hist__state { display: flex; align-items: center; gap: 8px; color: var(--muted); font-size: var(--text-sm); }
.pl-hist__empty { font-size: var(--text-sm); }
.pl-hist__top { display: flex; align-items: center; justify-content: space-between; gap: 10px 16px; flex-wrap: wrap; }
.pl-hist__wrap { max-height: 420px; overflow: auto; }
.pl-hist__session > th {
  padding: 8px 12px;
  text-align: left;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
  background: var(--surface-sunken);
  border-top: 1px solid var(--border);
  white-space: normal;
}
.pl-hist tbody:first-of-type .pl-hist__session > th { border-top: 0; }
.pl-hist__name { margin-right: 8px; overflow-wrap: anywhere; }
.pl-hist__count { margin-left: 8px; font-weight: 400; color: var(--muted); font-size: var(--text-xs); }
.pl-hist__pid { display: block; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); }
.pl-hist__player { display: block; }
.pl-hist__when { white-space: nowrap; color: var(--text-soft); }
</style>
