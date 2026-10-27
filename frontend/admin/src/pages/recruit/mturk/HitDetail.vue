<template>
  <div class="hd">
    <dl class="rc-kv hd__facts">
      <dt>Description</dt>
      <dd>{{ hit.description || '—' }}</dd>
      <dt>HIT ID</dt>
      <dd class="hd__id">
        <span class="rc-mono">{{ hitId }}</span>
        <CopyButton :text="hitId" icon-only size="sm" variant="ghost" aria-label="Copy the HIT ID" />
      </dd>
      <dt>Experiment</dt>
      <dd>
        <template v-if="annotation.breadboard">{{ annotation.experimentName || annotation.experimentUid }}</template>
        <span v-else class="rc-muted">Not posted from Breadboard (qualification tools are off for it)</span>
      </dd>
      <template v-if="hit.keywords">
        <dt>Keywords</dt>
        <dd>{{ hit.keywords }}</dd>
      </template>
    </dl>

    <section class="hd__section" :aria-label="`Submissions for ${hit.title}`">
      <div class="hd__bar" role="toolbar" aria-label="Actions for the selected submissions">
        <span class="hd__count" aria-live="polite">{{ selected.size ? `${selected.size} selected` : plural(rows.length, 'submission') + (nextToken ? ' loaded' : '') }}</span>
        <BButton size="sm" variant="primary" :icon="Check" :disabled="!approvable.length" @click="approve">
          Approve{{ approvable.length ? ` ${approvable.length}` : '' }}
        </BButton>
        <BButton size="sm" :icon="X" :disabled="!rejectable.length" @click="rejectOpen = true">
          Reject{{ rejectable.length ? ` ${rejectable.length}` : '' }}…
        </BButton>
        <BButton size="sm" :icon="HandCoins" :disabled="!bonusable.length" :title="bonusBlockedReason" @click="bonusOpen = true">
          Bonus{{ bonusPreview ? ` · ${formatMoney(bonusPreview)}` : '' }}…
        </BButton>
        <BMenu :items="moreItems" :width="250">
          <template #trigger="{ toggle, attrs }">
            <BButton size="sm" variant="ghost" :icon="Ellipsis" v-bind="attrs" :disabled="!selected.size" @click="toggle">More</BButton>
          </template>
        </BMenu>
        <span class="rc-spacer" />
        <BButton size="sm" variant="ghost" :icon="allAnswersOpen ? EyeOff : Eye" :disabled="!rows.length" @click="toggleAllAnswers">
          {{ allAnswersOpen ? 'Hide answers' : 'Show answers' }}
        </BButton>
        <BButton size="sm" variant="ghost" :icon="Download" :loading="csvBusy" :disabled="!rows.length" @click="downloadCsv">CSV</BButton>
        <BButton size="sm" variant="ghost" :icon="RefreshCw" :loading="loading" @click="load()">Refresh</BButton>
      </div>

      <ErrorNote v-if="error" title="Could not load the submissions" :message="error.message" :detail="error.detail" :on-retry="() => load()" />

      <div v-if="loading && !rows.length" class="rc-loading" role="status">
        <BSpinner :size="16" />
        <span>Loading submissions…</span>
      </div>

      <BEmpty
        v-else-if="!error && !rows.length"
        compact
        :icon="Inbox"
        title="No submissions yet"
        text="Submissions appear here once workers finish the HIT and submit it. Refresh to check again."
      />

      <template v-else-if="rows.length">
        <div class="rc-table-wrap">
          <table class="rc-table hd__table">
            <caption class="sr-only">Submissions for {{ hit.title }}</caption>
            <thead>
              <tr>
                <th scope="col" class="shrink">
                  <BCheckbox :model-value="allSelected" @update:model-value="selectAll">
                    <span class="sr-only">Select all submissions</span>
                  </BCheckbox>
                </th>
                <th scope="col">Worker</th>
                <th scope="col">Status</th>
                <th scope="col" class="num">Bonus</th>
                <th scope="col">Completed</th>
                <th scope="col">Qualification</th>
                <th scope="col">Answers</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="r in rows" :key="r.id">
                <tr :class="{ 'is-selected': selected.has(r.id) }">
                  <td class="shrink">
                    <BCheckbox :model-value="selected.has(r.id)" @update:model-value="(v: boolean) => select(r.id, v)">
                      <span class="sr-only">Select worker {{ r.workerId }}</span>
                    </BCheckbox>
                  </td>
                  <td>
                    <div class="rc-mono rc-strong">{{ r.workerId }}</div>
                    <div class="rc-sub rc-mono" :title="r.id">{{ r.id }}</div>
                  </td>
                  <td class="hd__status">
                    <span v-if="r.busy" class="hd__busy"><BSpinner :size="12" :label="BUSY[r.busy]" /> {{ BUSY[r.busy] }}</span>
                    <template v-else>
                      <BBadge :tone="STATUS[rowStatus(r)].tone">{{ STATUS[rowStatus(r)].label }}</BBadge>
                      <div v-if="r.approvalTime || r.rejectionTime" class="rc-sub">{{ formatTime(r.approvalTime || r.rejectionTime) }}</div>
                    </template>
                    <div v-if="r.error" class="rc-error-text" role="alert">{{ r.error }}</div>
                  </td>
                  <td class="num">
                    <BSpinner v-if="bonus.loading && !bonus.loaded" :size="12" label="Loading bonus payments" />
                    <span v-else-if="paid(r) > 0" class="rc-ok-text">{{ formatMoney(paid(r)) }} paid</span>
                    <span v-else-if="r.bonusAsked != null">{{ formatMoney(r.bonusAsked) }} <span class="rc-faint">earned</span></span>
                    <span v-else class="rc-faint">—</span>
                  </td>
                  <td>
                    <BSwitch :model-value="r.completed" :disabled="r.completedBusy" @update:model-value="(v: boolean) => toggleCompleted(r, v)">
                      <span class="sr-only">{{ r.workerId }} completed the experiment</span>
                    </BSwitch>
                  </td>
                  <td>
                    <span v-if="r.qual === 'na'" class="rc-faint" title="Only HITs posted from Breadboard have an experiment qualification">—</span>
                    <BSpinner v-else-if="r.qual === 'loading' || r.qual === 'idle'" :size="12" label="Checking the qualification" />
                    <span v-else-if="r.qual === 'error'" class="rc-error-text">Could not check</span>
                    <BSwitch v-else :model-value="r.qual === 'granted'" :disabled="r.qualBusy" @update:model-value="(v: boolean) => toggleQual(r, v)">
                      <span class="sr-only">{{ r.workerId }} holds this experiment’s qualification</span>
                    </BSwitch>
                  </td>
                  <td>
                    <BButton
                      size="sm"
                      variant="ghost"
                      :aria-expanded="r.open ? 'true' : 'false'"
                      :aria-controls="`hd-ans-${r.id}`"
                      :disabled="!r.answers.keys.length && !r.answers.error"
                      @click="r.open = !r.open"
                    >
                      {{ r.answers.error ? (r.open ? 'Hide' : 'Problem') : r.open ? 'Hide' : r.answers.keys.length ? `Show ${r.answers.keys.length}` : 'None' }}
                    </BButton>
                  </td>
                </tr>
                <tr v-if="r.open">
                  <td :id="`hd-ans-${r.id}`" colspan="7" class="hd__answers">
                    <p v-if="r.answers.error" class="rc-error-text">{{ r.answers.error }}</p>
                    <dl v-else class="rc-kv">
                      <template v-for="k in r.answers.keys" :key="k">
                        <dt>{{ k }}</dt>
                        <dd>{{ r.answers.values[k] || '—' }}</dd>
                      </template>
                    </dl>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div class="hd__foot">
          <p class="rc-sub hd__legend">
            <strong>Completed</strong> marks workers who finished this experiment; HITs that block previous players turn them away next time.
            <strong>Qualification</strong> is this experiment’s participant qualification on MTurk, which new HITs can require or exclude.
          </p>
          <BButton v-if="nextToken" size="sm" :loading="loadingMore" :icon="ChevronsDown" @click="loadMore">Load more</BButton>
        </div>
      </template>
    </section>

    <section class="hd__section" aria-labelledby="hd-bonus-title">
      <h4 id="hd-bonus-title" class="hd__h">
        Bonus payments
        <span v-if="bonus.loaded" class="rc-faint hd__h-sub">{{ bonus.payments.length }} · {{ formatMoney(paidTotal) }}</span>
      </h4>
      <ErrorNote v-if="bonus.error" title="Could not load the bonus payments" :message="bonus.error.message" :detail="bonus.error.detail" :on-retry="loadBonuses" />
      <div v-else-if="bonus.loading && !bonus.loaded" class="rc-loading" role="status"><BSpinner :size="14" /> Loading bonus payments…</div>
      <p v-else-if="!bonus.payments.length" class="rc-muted hd__none">No bonuses paid for this HIT yet.</p>
      <div v-else class="rc-table-wrap">
        <table class="rc-table rc-table--compact">
          <caption class="sr-only">Bonus payments for {{ hit.title }}</caption>
          <thead>
            <tr>
              <th scope="col">Worker</th>
              <th scope="col">Assignment</th>
              <th scope="col" class="num">Amount</th>
              <th scope="col">Reason</th>
              <th scope="col">Paid</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in bonus.payments" :key="`${p.assignmentId}-${i}`">
              <td class="rc-mono">{{ p.workerId }}</td>
              <td class="rc-mono rc-faint">{{ p.assignmentId }}</td>
              <td class="num">{{ formatMoney(parseFloat(p.bonusAmount)) }}</td>
              <td>{{ p.reason }}</td>
              <td class="nowrap">{{ formatTime(p.grantTime) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <RejectDialog v-model:open="rejectOpen" :workers="rejectable.map(r => r.workerId)" @confirm="reject" />
    <BonusDialog v-model:open="bonusOpen" :rows="bonusable" @pay="payBonuses" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Award, Check, ChevronsDown, Download, Ellipsis, Eye, EyeOff, HandCoins, Inbox, RefreshCw, UserCheck, UserMinus, UserX, X,
} from 'lucide-vue-next'
import { BBadge, BButton, BCheckbox, BEmpty, BMenu, BSpinner, BSwitch, notify, type MenuItem } from '@/ui'
import { HttpError, saveText } from '@/lib/http'
import CopyButton from '../CopyButton.vue'
import ErrorNote from '../ErrorNote.vue'
import RejectDialog from './RejectDialog.vue'
import BonusDialog from './BonusDialog.vue'
import { balanceChanged, hitIdOf, track, type BonusPayment, type MturkApi, type MturkHit } from './api'
import { confirmMoney } from './moneyConfirm'
import {
  assignmentsCsv, bonusTotal, describeError, errorLine, formatMoney, formatTime, mapPool, moneyString, mturkFeeRate,
  type Annotation, type ErrorText,
} from './lib'
import { nowMinute, plural, rowStatus, toRow, type AssignmentRow } from './rows'

const props = defineProps<{ hit: MturkHit, annotation: Annotation, api: MturkApi }>()
const emit = defineEmits<{ reviewed: [count: number] }>()

const STATUS = {
  submitted: { label: 'To review', tone: 'warning' as const },
  approved: { label: 'Approved', tone: 'success' as const },
  rejected: { label: 'Rejected', tone: 'danger' as const },
}
const BUSY = { approve: 'Approving…', reject: 'Rejecting…', bonus: 'Paying…', '': '' }
const POOL = 4

const hitId = computed(() => hitIdOf(props.hit))
const uid = computed(() => props.annotation.experimentUid)

const rows = ref<AssignmentRow[]>([])
const nextToken = ref<string | null>(null)
const loading = ref(false)
const loadingMore = ref(false)
const error = ref<ErrorText | null>(null)
const selected = reactive(new Set<string>())
const rejectOpen = ref(false)
const bonusOpen = ref(false)
const csvBusy = ref(false)

const bonus = reactive({ loading: false, loaded: false, error: null as ErrorText | null, payments: [] as BonusPayment[] })

// ---- loading ----------------------------------------------------------------------------------

async function load () {
  loading.value = true
  error.value = null
  try {
    const res = await props.api.listAssignments(hitId.value, null)
    const list = (res.assignments || []).map(toRow)
    rows.value = list
    selected.clear()
    nextToken.value = list.length ? res.nextToken : null
    loadBonuses()
    loadQualifications(rows.value)
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

/** The next page of submissions; false (after saying why) when it could not be loaded. */
async function loadMore (): Promise<boolean> {
  if (!nextToken.value) return true
  loadingMore.value = true
  try {
    const res = await props.api.listAssignments(hitId.value, nextToken.value)
    const known = new Set(rows.value.map(r => r.id))
    const fresh = (res.assignments || []).filter(a => !known.has(a.assignmentId)).map(toRow)
    rows.value.push(...fresh)
    // an empty page with a token is MTurk's way of saying there is nothing more
    nextToken.value = fresh.length ? res.nextToken : null
    loadQualifications(rows.value.slice(rows.value.length - fresh.length))
    return true
  } catch (e) {
    notify.error('Could not load more submissions', errorLine(e))
    return false
  } finally {
    loadingMore.value = false
  }
}

/** Every bonus already paid for this HIT (all pages), so nobody is paid twice by mistake. */
async function loadBonuses () {
  bonus.loading = true
  bonus.error = null
  try {
    const all: BonusPayment[] = []
    let token: string | null = null
    for (let i = 0; i < 50; i++) {
      const res: { bonusPayments: BonusPayment[], nextToken: string | null } = await props.api.listBonusPayments(hitId.value, token)
      all.push(...(res.bonusPayments || []))
      if (!res.nextToken || !(res.bonusPayments || []).length) break
      token = res.nextToken
    }
    bonus.payments = all
    bonus.loaded = true
    // a payment that errored but went through anyway now shows as paid
    for (const r of rows.value) if (r.error && paid(r) > 0 && r.busy === '') r.error = null
  } catch (e) {
    bonus.error = describeError(e)
    bonus.loaded = false
  } finally {
    bonus.loading = false
  }
}

const paidBy = computed(() => {
  const m = new Map<string, number[]>()
  for (const p of bonus.payments) {
    const list = m.get(p.assignmentId) || []
    list.push(parseFloat(p.bonusAmount))
    m.set(p.assignmentId, list)
  }
  return m
})
/** Several bonuses to one assignment add up (the old console showed only the last). */
function paid (r: AssignmentRow): number {
  return bonusTotal(paidBy.value.get(r.id) || [])
}
const paidTotal = computed(() => bonusTotal(bonus.payments.map(p => parseFloat(p.bonusAmount))))

async function qualTypeId (): Promise<string | null> {
  if (!uid.value) return null
  return props.api.experimentTypeId(uid.value)
}

async function loadQualifications (list: AssignmentRow[]) {
  if (!uid.value) {
    for (const r of list) r.qual = 'na'
    return
  }
  for (const r of list) r.qual = 'loading'
  let typeId: string | null
  try {
    typeId = await qualTypeId()
  } catch (e) {
    for (const r of list) r.qual = 'error'
    return
  }
  if (!typeId) return
  await mapPool(list, POOL, async (r) => {
    try {
      const s = await props.api.qualificationScore(r.workerId, typeId!)
      r.qual = s && s.status === 'Granted' ? 'granted' : 'none'
    } catch (e) {
      r.qual = 'error'
    }
  })
}

// ---- selection --------------------------------------------------------------------------------

const selectedRows = computed(() => rows.value.filter(r => selected.has(r.id)))
const allSelected = computed(() => rows.value.length > 0 && rows.value.every(r => selected.has(r.id)))
function select (id: string, on: boolean) {
  if (on) selected.add(id)
  else selected.delete(id)
}
function selectAll (on: boolean) {
  selected.clear()
  if (on) for (const r of rows.value) selected.add(r.id)
}

const approvable = computed(() => selectedRows.value.filter(r => !r.busy && rowStatus(r) === 'submitted'))
const rejectable = approvable
const bonusable = computed(() => bonus.loaded ? selectedRows.value.filter(r => !r.busy && paid(r) === 0) : [])
const bonusPreview = computed(() => bonusTotal(bonusable.value.map(r => r.bonusAsked)))
const bonusBlockedReason = computed(() => {
  if (!selected.size) return 'Select submissions first'
  if (!bonus.loaded) return 'The bonus payments must load first, so nobody is paid twice'
  if (!bonusable.value.length) return 'Everyone selected already has a bonus'
  return undefined
})

const moreItems = computed<MenuItem[]>(() => [
  { heading: `${plural(selected.size, 'selected submission')}` },
  { label: 'Mark completed', icon: UserCheck, run: () => bulkCompleted(true) },
  { label: 'Mark not completed', icon: UserX, run: () => bulkCompleted(false) },
  { divider: true },
  { label: 'Grant the qualification', icon: Award, disabled: !uid.value, run: () => bulkQual(true) },
  { label: 'Remove the qualification', icon: UserMinus, disabled: !uid.value, run: () => bulkQual(false) },
  { divider: true },
  { label: 'Clear the selection', run: () => selected.clear() },
])

function summarize (verb: string, results: PromiseSettledResult<unknown>[]) {
  const failed = results.filter(r => r.status === 'rejected') as PromiseRejectedResult[]
  const ok = results.length - failed.length
  if (!failed.length) notify.success(`${verb} ${plural(ok, 'submission')}`)
  else if (!ok) notify.error(`Nothing was ${verb.toLowerCase()}`, errorLine(failed[0].reason))
  else notify.warning(`${verb} ${ok}, ${failed.length} failed`, errorLine(failed[0].reason))
  return ok
}

// ---- money and review actions ------------------------------------------------------------------

async function approve () {
  const list = approvable.value.slice()
  if (!list.length) return
  const reward = parseFloat(props.hit.reward || '0') || 0
  const total = bonusTotal(list.map(() => reward))
  const fee = Math.round(mturkFeeRate(props.hit.maxAssignments || 0) * 100)
  const ok = await confirmMoney({
    title: `Approve ${plural(list.length, 'submission')}?`,
    message: `Each worker is paid this HIT’s reward of ${formatMoney(reward)}. Approved work can’t be rejected later.`,
    lines: list.map(r => ({ label: r.workerId, amount: reward })),
    total,
    totalLabel: 'Rewards paid',
    footnote: `MTurk adds its fee on top (${fee}% for this HIT). Bonuses are paid separately.`,
    confirmLabel: `Approve and pay ${formatMoney(total)}`,
  })
  if (!ok) return
  const results = await track(() => mapPool(list, POOL, async (r) => {
    r.busy = 'approve'
    r.error = null
    try {
      await props.api.approve(r.id)
      r.approvalTime = nowMinute()
      selected.delete(r.id)
    } catch (e) {
      r.error = errorLine(e)
      throw e
    } finally {
      r.busy = ''
    }
  }))
  const done = summarize('Approved', results)
  if (done) { emit('reviewed', done); balanceChanged() }
}

async function reject (reason: string) {
  rejectOpen.value = false
  const list = rejectable.value.slice()
  const results = await track(() => mapPool(list, POOL, async (r) => {
    r.busy = 'reject'
    r.error = null
    try {
      await props.api.reject(r.id, reason)
      r.rejectionTime = nowMinute()
      selected.delete(r.id)
    } catch (e) {
      r.error = errorLine(e)
      throw e
    } finally {
      r.busy = ''
    }
  }))
  const done = summarize('Rejected', results)
  if (done) emit('reviewed', done)
}

async function payBonuses (items: { row: AssignmentRow, amount: number }[], reason: string) {
  bonusOpen.value = false
  // two at a time, never retried automatically: MTurk's SendBonus has no idempotency token here
  const results = await track(() => mapPool(items, 2, async ({ row, amount }) => {
    row.busy = 'bonus'
    row.error = null
    try {
      await props.api.sendBonus(row.id, row.workerId, moneyString(amount), reason)
      selected.delete(row.id)
    } catch (e) {
      row.error = e instanceof HttpError
        ? errorLine(e)
        : 'No answer from the server, so this may or may not have been paid. Check the bonus payments below before paying again.'
      throw e
    } finally {
      row.busy = ''
    }
  }))
  const failed = results.filter(r => r.status === 'rejected').length
  const paidOk = items.filter((_, i) => results[i].status === 'fulfilled')
  const sum = bonusTotal(paidOk.map(x => x.amount))
  if (!failed) notify.success(`Paid ${formatMoney(sum)} in bonuses`, plural(paidOk.length, 'worker'))
  else notify.warning(`Paid ${plural(paidOk.length, 'bonus', 'bonuses')}, ${failed} failed`, 'See the rows marked with an error.')
  balanceChanged()
  await loadBonuses()
}

// ---- completed and qualification --------------------------------------------------------------

async function setCompleted (r: AssignmentRow, value: boolean) {
  const before = r.completed
  r.completedBusy = true
  r.completed = value
  try {
    await props.api.setCompleted(r.id, value)
  } catch (e) {
    r.completed = before
    r.error = errorLine(e)
    throw e
  } finally {
    r.completedBusy = false
  }
}

async function toggleCompleted (r: AssignmentRow, value: boolean) {
  try { await setCompleted(r, value) } catch (e) { notify.error('Could not save “completed”', errorLine(e)) }
}

async function bulkCompleted (value: boolean) {
  const list = selectedRows.value.filter(r => r.completed !== value && !r.completedBusy)
  if (!list.length) return notify.info(value ? 'Already marked completed' : 'Already marked not completed')
  const results = await mapPool(list, POOL, r => setCompleted(r, value))
  summarize(value ? 'Marked completed:' : 'Marked not completed:', results)
}

async function setQual (r: AssignmentRow, grant: boolean) {
  const typeId = await qualTypeId()
  if (!typeId) throw new Error('This HIT has no experiment qualification.')
  r.qualBusy = true
  try {
    if (grant) await props.api.grantQualification(r.workerId, typeId)
    else await props.api.revokeQualification(r.workerId, typeId)
    r.qual = grant ? 'granted' : 'none'
  } finally {
    r.qualBusy = false
  }
}

async function toggleQual (r: AssignmentRow, grant: boolean) {
  try { await setQual(r, grant) } catch (e) { notify.error(grant ? 'Could not grant the qualification' : 'Could not remove the qualification', errorLine(e)) }
}

async function bulkQual (grant: boolean) {
  const list = selectedRows.value.filter(r => !r.qualBusy && r.qual !== 'na' && r.qual !== (grant ? 'granted' : 'none'))
  if (!list.length) return notify.info(grant ? 'Everyone selected already has it' : 'Nobody selected has it')
  const results = await mapPool(list, POOL, r => setQual(r, grant))
  summarize(grant ? 'Qualification granted:' : 'Qualification removed:', results)
}

// ---- answers and CSV ----------------------------------------------------------------------------

const allAnswersOpen = computed(() => rows.value.length > 0 && rows.value.every(r => r.open || (!r.answers.keys.length && !r.answers.error)))
function toggleAllAnswers () {
  const open = !allAnswersOpen.value
  for (const r of rows.value) r.open = open && (r.answers.keys.length > 0 || !!r.answers.error)
}

/** Loads any remaining pages first, so the file has every submission, not just those on screen. */
async function downloadCsv () {
  csvBusy.value = true
  try {
    for (let i = 0; nextToken.value && i < 100; i++) {
      if (!(await loadMore())) return
    }
    if (!bonus.loaded) await loadBonuses()
    const csv = assignmentsCsv(
      { hitId: hitId.value, title: props.hit.title, creationTime: props.hit.creationTime },
      rows.value.map(r => ({
        assignmentId: r.id,
        workerId: r.workerId,
        status: rowStatus(r),
        approvalTime: r.approvalTime,
        rejectionTime: r.rejectionTime,
        bonusPaid: bonus.loaded ? paid(r) : null,
        completed: r.completed,
        answers: r.answers.values,
        answerKeys: r.answers.keys,
      })),
    )
    saveText(`${hitId.value}.csv`, csv, 'text/csv;charset=utf-8')
    notify.success('CSV downloaded', plural(rows.value.length, 'submission'))
  } catch (e) {
    notify.error('Could not make the CSV', errorLine(e))
  } finally {
    csvBusy.value = false
  }
}

onMounted(load)
</script>

<style>
.hd { display: flex; flex-direction: column; gap: 18px; padding: 16px 18px 20px; border-top: 1px solid var(--border); }
.hd__facts { max-width: 900px; }
.hd__id { display: flex; align-items: center; gap: 4px; }
.hd__section { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.hd__bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.hd__count { margin-right: 6px; font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.hd__table td { background: var(--surface); }
.hd__table tr.is-selected > td { background: var(--accent-soft); }
.hd__status { min-width: 120px; }
.hd__busy { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); font-size: var(--text-xs); }
.hd__answers { background: var(--surface-sunken) !important; padding: 12px 16px 14px 52px !important; }
.hd__foot { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.hd__legend { max-width: 760px; }
.hd__legend strong { color: var(--text-soft); font-weight: 600; }
.hd__h { font-size: var(--text-md); display: flex; align-items: baseline; gap: 8px; }
.hd__h-sub { font-size: var(--text-sm); font-weight: 500; }
.hd__none { font-size: var(--text-sm); }
@media (max-width: 640px) { .hd { padding: 12px; } .hd__answers { padding-left: 16px !important; } }
</style>
