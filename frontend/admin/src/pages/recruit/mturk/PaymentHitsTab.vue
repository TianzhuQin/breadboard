<template>
  <div class="ph rc-stack">
    <div class="rc-note">
      <HandCoins :size="16" aria-hidden="true" />
      <span>
        A payment HIT pays someone for work done outside a normal HIT, for example a worker whose
        session broke down. Breadboard creates one private HIT per worker that only they can
        accept (they find it by searching for their worker ID) and submit with one click. They are
        paid once you <strong>approve</strong> it on the HITs tab.
      </span>
    </div>

    <form v-if="!run" class="rc-stack" novalidate @submit.prevent="submit">
      <BPanel title="Who to pay">
        <div class="rc-stack">
          <BField id="ph-ids" label="Worker IDs" required :error="tried && !parsed.ids.length ? 'Paste at least one worker ID.' : null" hint="One per line, or separated by commas or spaces. Duplicates are left out.">
            <template #default="{ id, describedby }">
              <textarea :id="id" v-model="idsText" class="b-textarea rc-mono ph__ids" rows="8" spellcheck="false" :aria-describedby="describedby" placeholder="A1B2C3D4E5F6G7&#10;A7G6F5E4D3C2B1" />
            </template>
          </BField>
          <p class="rc-sub" aria-live="polite">
            {{ parsed.ids.length }} worker{{ parsed.ids.length === 1 ? '' : 's' }}
            <template v-if="parsed.duplicates.length"> · {{ parsed.duplicates.length }} duplicate{{ parsed.duplicates.length === 1 ? '' : 's' }} left out</template>
          </p>
          <div v-if="parsed.invalid.length" class="rc-note rc-note--warning">
            <TriangleAlert :size="16" aria-hidden="true" />
            <span>These don’t look like worker IDs and will be skipped: <span class="rc-mono">{{ parsed.invalid.slice(0, 8).join(', ') }}</span><template v-if="parsed.invalid.length > 8"> and {{ parsed.invalid.length - 8 }} more</template>.</span>
          </div>
        </div>
      </BPanel>

      <BPanel title="Payment">
        <div class="rc-form-grid">
          <BField id="ph-reward" label="Amount per worker ($)" required :error="tried && reward == null ? 'Enter an amount of at least $0.01, like 2.50.' : null" hint="The reward of each payment HIT.">
            <template #default="{ id, describedby }">
              <BInput :id="id" v-model="rewardText" inputmode="decimal" :aria-describedby="describedby" />
            </template>
          </BField>
          <BField id="ph-reason" label="Reason" class="is-wide" required :error="tried && !reason.trim() ? 'Say what the payment is for. The worker sees it.' : null" hint="Shown to the worker as the HIT’s description.">
            <template #default="{ id, describedby }">
              <textarea :id="id" v-model="reason" class="b-textarea" rows="2" :aria-describedby="describedby" placeholder="Payment for the session on 27 October that ended early" />
            </template>
          </BField>
        </div>
        <p class="ph__sum" aria-live="polite">
          <template v-if="parsed.ids.length && reward != null">
            {{ parsed.ids.length }} × {{ formatMoney(reward) }} = <strong>{{ formatMoney(total) }}</strong>, plus MTurk’s 20% fee (about {{ formatMoney(fee) }}), paid when you approve each one.
          </template>
          <template v-else>Add worker IDs and an amount to see the total.</template>
        </p>
      </BPanel>

      <div class="ph__actions">
        <span class="rc-spacer" />
        <BBadge :tone="isProduction ? 'danger' : 'info'" dot>{{ isProduction ? 'Production' : 'Sandbox' }}</BBadge>
        <BButton type="submit" :variant="isProduction ? 'danger' : 'primary'" :icon="Send">
          Create {{ parsed.ids.length || '' }} payment HIT{{ parsed.ids.length === 1 ? '' : 's' }}
        </BButton>
      </div>
    </form>

    <BPanel v-else :title="running ? 'Creating payment HITs…' : 'Payment HITs'" :subtitle="`${formatMoney(run.reward)} each · ${run.reason}`">
      <template #actions>
        <BBadge :tone="run.sandbox ? 'info' : 'danger'" dot>{{ run.sandbox ? 'Sandbox' : 'Production' }}</BBadge>
      </template>
      <div class="rc-stack">
        <div class="rc-progress" role="progressbar" :aria-valuenow="finished" aria-valuemin="0" :aria-valuemax="run.items.length" :aria-label="`${finished} of ${run.items.length} done`">
          <div class="rc-progress__bar" :style="{ width: `${run.items.length ? (finished / run.items.length) * 100 : 0}%` }" />
        </div>
        <p class="rc-sub" aria-live="polite">
          {{ finished }} of {{ run.items.length }} done · {{ created }} created<template v-if="failed"> · {{ failed }} failed</template>
        </p>
        <div class="rc-table-wrap">
          <table class="rc-table rc-table--compact">
            <caption class="sr-only">Payment HITs by worker</caption>
            <thead><tr><th scope="col">Worker</th><th scope="col">Status</th></tr></thead>
            <tbody>
              <tr v-for="it in run.items" :key="it.workerId">
                <td class="rc-mono">{{ it.workerId }}</td>
                <td>
                  <span v-if="it.state === 'waiting'" class="rc-faint">Waiting</span>
                  <span v-else-if="it.state === 'sending'" class="rc-busy-text"><BSpinner :size="12" label="Creating" /> Creating…</span>
                  <BBadge v-else-if="it.state === 'done'" tone="success">Created</BBadge>
                  <span v-else class="rc-error-text">{{ it.error }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!running" class="rc-row">
          <BButton v-if="failed" :icon="RotateCw" @click="retryFailed">Try the {{ failed }} failed again</BButton>
          <BButton variant="primary" :icon="ListChecks" @click="emit('view-hits')">Go to HITs to approve them</BButton>
          <BButton variant="ghost" @click="run = null">Create more</BButton>
        </div>
      </div>
    </BPanel>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { HandCoins, ListChecks, RotateCw, Send, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton, BField, BInput, BPanel, BSpinner, notify } from '@/ui'
import { track, type MturkApi } from './api'
import { isProduction } from './mode'
import { confirmMoney } from './moneyConfirm'
import { bonusTotal, errorLine, formatMoney, mapPool, moneyString, parseMoney, parseWorkerIds, toCents } from './lib'

const props = defineProps<{ api: MturkApi }>()
const emit = defineEmits<{ 'view-hits': [] }>()

interface Item { workerId: string, state: 'waiting' | 'sending' | 'done' | 'failed', error: string }

const idsText = ref('')
const rewardText = ref('1.00')
const reason = ref('')
const tried = ref(false)
const run = ref<{ items: Item[], reward: number, reason: string, sandbox: boolean } | null>(null)
const running = ref(false)

const parsed = computed(() => parseWorkerIds(idsText.value))
const reward = computed(() => parseMoney(rewardText.value))
const total = computed(() => bonusTotal(parsed.value.ids.map(() => reward.value)))
const fee = computed(() => toCents(total.value * 0.2) / 100)
const finished = computed(() => run.value?.items.filter(i => i.state === 'done' || i.state === 'failed').length || 0)
const created = computed(() => run.value?.items.filter(i => i.state === 'done').length || 0)
const failed = computed(() => run.value?.items.filter(i => i.state === 'failed').length || 0)

async function submit () {
  tried.value = true
  if (!parsed.value.ids.length || reward.value == null || !reason.value.trim()) return
  const n = parsed.value.ids.length
  const ok = await confirmMoney({
    title: `Create ${n} payment HIT${n === 1 ? '' : 's'}?`,
    message: `Each worker below gets a private HIT worth ${formatMoney(reward.value)}. You pay when you approve their submission (MTurk may also approve it automatically after a few days).`,
    lines: parsed.value.ids.map(id => ({ label: id, amount: reward.value })),
    total: total.value,
    totalLabel: 'Total to pay',
    footnote: 'MTurk adds a 20% fee. Each payment HIT also leaves a one-off qualification on your MTurk account.',
    confirmLabel: `Create ${n} payment HIT${n === 1 ? '' : 's'}`,
  })
  if (!ok) return
  run.value = {
    items: parsed.value.ids.map(workerId => ({ workerId, state: 'waiting', error: '' })),
    reward: reward.value,
    reason: reason.value.trim(),
    sandbox: props.api.sandbox,
  }
  await send(run.value.items)
}

async function send (items: Item[]) {
  if (!run.value) return
  const { reward: amount, reason: why } = run.value
  running.value = true
  try {
    await track(() => mapPool(items, 3, async (it) => {
      it.state = 'sending'
      it.error = ''
      try {
        await props.api.createPaymentHit(it.workerId, moneyString(amount), why)
        it.state = 'done'
      } catch (e) {
        it.state = 'failed'
        it.error = errorLine(e)
      }
    }))
  } finally {
    running.value = false
  }
  if (failed.value) notify.warning(`${created.value} created, ${failed.value} failed`, 'See the list for what went wrong.')
  else notify.success(`Created ${created.value} payment HIT${created.value === 1 ? '' : 's'}`, 'Approve them on the HITs tab once the workers submit.')
}

async function retryFailed () {
  if (!run.value) return
  const again = run.value.items.filter(i => i.state === 'failed')
  const ok = await confirmMoney({
    title: `Try ${again.length} payment HIT${again.length === 1 ? '' : 's'} again?`,
    message: 'If an earlier attempt reached MTurk despite the error, the worker may end up with two payment HITs; approve only one.',
    lines: again.map(i => ({ label: i.workerId, amount: run.value!.reward })),
    total: bonusTotal(again.map(() => run.value!.reward)),
    totalLabel: 'Total to pay',
    confirmLabel: 'Try again',
  })
  if (ok) send(again)
}
</script>

<style>
.ph__ids { min-height: 160px; }
.ph__sum { margin-top: 14px; font-size: var(--text-sm); color: var(--muted); }
.ph__sum strong { color: var(--text); }
.ph__actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
