<template>
  <div class="rc-stack">
    <div class="rc-toolbar">
      <p class="rc-toolbar__grow rc-muted mt-hits__intro">
        Every HIT on your {{ isProduction ? 'production' : 'sandbox' }} requester account.
        <template v-if="admin.experiment">HITs posted from <strong class="rc-strong">{{ admin.experiment.name }}</strong> are marked.</template>
        Open one to review its submissions and pay workers.
      </p>
      <BButton :icon="RefreshCw" :loading="loading" @click="load(page)">Refresh</BButton>
      <BButton variant="primary" :icon="Plus" @click="emit('create')">Create HIT</BButton>
    </div>

    <ErrorNote v-if="error" title="Could not load the HITs" :message="error.message" :detail="error.detail" :on-retry="() => load(page)" />

    <div v-if="loading && !hits.length" class="rc-table-wrap" role="status" aria-label="Loading HITs">
      <div class="rc-skeleton">
        <div v-for="i in 5" :key="i" class="rc-skeleton__bar" :style="{ width: `${92 - i * 9}%` }" />
      </div>
    </div>

    <BEmpty
      v-else-if="!error && !hits.length"
      :icon="ListChecks"
      title="No HITs yet"
      :text="`Your ${isProduction ? 'production' : 'sandbox'} account has no HITs. Create one to invite workers into a running session.`"
    >
      <template #actions>
        <BButton variant="primary" :icon="Plus" @click="emit('create')">Create HIT</BButton>
      </template>
    </BEmpty>

    <template v-else-if="hits.length">
      <div ref="wrap" class="rc-table-wrap" :style="{ '--rc-box-w': boxWidth ? `${boxWidth}px` : '100%' }">
        <table class="rc-table mt-hits">
          <caption class="sr-only">HITs, page {{ page + 1 }}</caption>
          <thead>
            <tr>
              <th scope="col" class="shrink"><span class="sr-only">Open</span></th>
              <th scope="col">Title</th>
              <th scope="col">Created</th>
              <th scope="col" class="num">Reward</th>
              <th scope="col" class="num" title="Places requested">Requested</th>
              <th scope="col" class="num" title="Workers doing the task right now">In progress</th>
              <th scope="col" class="num" title="Submitted, waiting for you to approve or reject">To review</th>
              <th scope="col" class="num" title="Approved or rejected">Reviewed</th>
              <th scope="col" class="num" title="Places nobody has taken yet">Remaining</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="h in rows" :key="h.id">
              <tr class="is-clickable" :class="{ 'is-open': expanded === h.id, 'is-current': h.current }" @click="toggle(h.id)">
                <td class="shrink">
                  <button
                    type="button"
                    class="rc-expand"
                    :aria-expanded="expanded === h.id ? 'true' : 'false'"
                    :aria-controls="`mt-hit-${h.id}`"
                    :aria-label="`${expanded === h.id ? 'Hide' : 'Show'} the submissions for “${h.title}”`"
                    @click.stop="toggle(h.id)"
                  >
                    <ChevronRight :size="16" aria-hidden="true" />
                  </button>
                </td>
                <td class="mt-hits__title">
                  <div class="mt-hits__name">
                    <span class="rc-strong">{{ h.title }}</span>
                    <BBadge v-if="h.current" tone="accent">This experiment</BBadge>
                    <BBadge v-else-if="h.annotation.experimentName" tone="neutral">{{ h.annotation.experimentName }}</BBadge>
                  </div>
                  <div class="rc-sub rc-mono">{{ h.id }}</div>
                </td>
                <td class="nowrap">{{ formatTime(h.raw.creationTime) }}</td>
                <td class="num">{{ formatMoney(h.reward) }}</td>
                <td class="num">{{ h.counts.requested }}</td>
                <td class="num">{{ h.counts.inProgress }}</td>
                <td class="num"><span :class="{ 'mt-hits__attn': h.counts.toReview > 0 }">{{ h.counts.toReview }}</span></td>
                <td class="num">{{ h.counts.reviewed }}</td>
                <td class="num">{{ h.counts.remaining }}</td>
                <td class="nowrap">
                  <BBadge :tone="h.status.tone">{{ h.status.label }}</BBadge>
                  <div v-if="h.raw.expiration" class="rc-sub">{{ h.expired ? 'Closed' : 'Open until' }} {{ formatTime(h.raw.expiration) }}</div>
                </td>
              </tr>
              <tr v-if="expanded === h.id">
                <td :id="`mt-hit-${h.id}`" colspan="10" class="rc-detail-cell">
                  <div class="rc-pinned">
                    <HitDetail :hit="h.raw" :annotation="h.annotation" :api="api" @reviewed="(n) => onReviewed(h.raw, n)" />
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <Pager :label="`Page ${page + 1}`" :has-prev="page > 0" :has-next="!!tokens[page + 1]" :loading="loading" aria-label="HIT pages" @prev="load(page - 1)" @next="load(page + 1)" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChevronRight, ListChecks, Plus, RefreshCw } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, notify } from '@/ui'
import { admin } from '@/store/admin'
import ErrorNote from '../ErrorNote.vue'
import HitDetail from './HitDetail.vue'
import Pager from './Pager.vue'
import { hitIdOf, type MturkApi, type MturkHit } from './api'
import { isProduction } from './mode'
import { describeError, formatMoney, formatTime, hitCounts, parseAnnotation, type ErrorText } from './lib'
import { useBoxWidth } from '../useBoxWidth'

const props = defineProps<{ api: MturkApi }>()
const emit = defineEmits<{ create: [] }>()

const hits = ref<MturkHit[]>([])
/** the token that loads each page found so far; page 0 needs none */
const tokens = ref<(string | null)[]>([null])
const page = ref(0)
const loading = ref(false)
const error = ref<ErrorText | null>(null)
const expanded = ref<string | null>(null)
const wrap = ref<HTMLElement>()
const boxWidth = useBoxWidth(wrap)

const STATUS: Record<string, { label: string, tone: 'success' | 'info' | 'warning' | 'neutral' }> = {
  Assignable: { label: 'Accepting workers', tone: 'success' },
  Unassignable: { label: 'All places taken', tone: 'info' },
  Reviewable: { label: 'Ended', tone: 'neutral' },
  Reviewing: { label: 'Reviewing', tone: 'info' },
  Disposed: { label: 'Deleted', tone: 'neutral' },
}

const rows = computed(() => hits.value.map(raw => {
  const id = hitIdOf(raw)
  const annotation = parseAnnotation(raw.requesterAnnotation)
  const expired = !!raw.expiration && raw.expiration < Date.now()
  const status = STATUS[raw.hitstatus || ''] || { label: raw.hitstatus || 'Unknown', tone: 'neutral' as const }
  return {
    id,
    raw,
    title: raw.title || '(untitled)',
    annotation,
    current: !!admin.experiment && !!annotation.experimentUid && annotation.experimentUid === admin.experiment.uid,
    counts: hitCounts(raw),
    reward: raw.reward != null && raw.reward !== '' ? parseFloat(raw.reward) : null,
    expired,
    status,
  }
}))

async function load (p: number) {
  if (p < 0) return
  loading.value = true
  error.value = null
  try {
    const res = await props.api.listHits(tokens.value[p] ?? null)
    const list = res.hits || []
    if (!list.length && p > 0) {
      // MTurk can hand out a token for an empty last page
      tokens.value = tokens.value.slice(0, p)
      notify.info('No more HITs', 'That was the last page.')
      return
    }
    hits.value = list
    page.value = p
    tokens.value = tokens.value.slice(0, p + 1)
    if (res.nextToken && list.length) tokens.value.push(res.nextToken)
    if (!list.some(h => hitIdOf(h) === expanded.value)) expanded.value = null
  } catch (e) {
    const d = describeError(e)
    if (!d.noKeys) error.value = d
  } finally {
    loading.value = false
  }
}

function toggle (id: string) {
  expanded.value = expanded.value === id ? null : id
}

/** Approvals and rejections move assignments to "Reviewed" before MTurk reports it. */
function onReviewed (raw: MturkHit, n: number) {
  raw.numberOfAssignmentsCompleted = (raw.numberOfAssignmentsCompleted || 0) + n
}

onMounted(() => load(0))
</script>

<style>
.mt-hits__intro { font-size: var(--text-sm); }
.mt-hits__title { min-width: 220px; }
.mt-hits__name { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mt-hits tr.is-current > td:first-child { box-shadow: inset 3px 0 0 var(--accent); }
.mt-hits__attn { display: inline-block; min-width: 22px; padding: 0 6px; border-radius: 999px; background: var(--warning-soft); color: var(--warning-soft-text); font-weight: 650; text-align: center; }
</style>
