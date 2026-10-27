<template>
  <BDialog :open="open" title="Pay bonuses" :subtitle="`${plural(rows.length, 'worker')} selected`" size="lg" :dismissable="false" @close="emit('update:open', false)">
    <form id="mt-bonus-form" class="rc-stack" @submit.prevent="submit">
      <ModeBanner />
      <p class="rc-muted">
        Each amount comes from the bonus the game reported when the worker submitted. Change any
        amount, or clear it to leave that worker out. Bonuses are paid on top of the HIT reward and
        can’t be taken back.
      </p>

      <div class="rc-table-wrap">
        <table class="rc-table rc-table--compact">
          <thead>
            <tr>
              <th scope="col">Worker</th>
              <th scope="col" class="num">Earned in the game</th>
              <th scope="col" class="num">Bonus to pay ($)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td class="rc-mono">{{ r.workerId }}</td>
              <td class="num">{{ r.bonusAsked != null ? formatMoney(r.bonusAsked) : '—' }}</td>
              <td class="num mt-bonus__cell">
                <div class="mt-bonus__input">
                  <BInput
                    v-model="r.bonusInput"
                    size="sm"
                    inputmode="decimal"
                    placeholder="skip"
                    :aria-label="`Bonus for ${r.workerId}`"
                    :aria-invalid="invalid(r) ? 'true' : undefined"
                  />
                </div>
                <div v-if="invalid(r)" class="rc-error-text">Enter an amount like 0.50, or leave it empty.</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <BField label="Reason" hint="Workers see this in the email MTurk sends with the bonus." :error="showError && !reason.trim() ? 'Write a short reason. MTurk requires one.' : null" required>
        <template #default="{ id, describedby }">
          <BInput :id="id" v-model="reason" :aria-describedby="describedby" />
        </template>
      </BField>

      <div class="mt-bonus__total" aria-live="polite">
        <div>
          <div class="rc-strong">{{ paying.length ? `Paying ${plural(paying.length, 'worker')}` : 'Nobody to pay' }}</div>
          <div class="rc-sub">MTurk adds a 20% fee: about {{ formatMoney(fee) }}. Skipped: {{ rows.length - paying.length }}.</div>
        </div>
        <div class="mt-bonus__sum">{{ formatMoney(total) }}</div>
      </div>
    </form>
    <template #footer>
      <BButton variant="ghost" @click="emit('update:open', false)">Cancel</BButton>
      <BButton
        type="submit"
        form="mt-bonus-form"
        :variant="isProduction ? 'danger' : 'primary'"
        :icon="HandCoins"
        :disabled="!paying.length || anyInvalid"
      >
        Pay {{ formatMoney(total) }}{{ isProduction ? ' now' : ' (sandbox)' }}
      </BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { HandCoins } from 'lucide-vue-next'
import { BButton, BDialog, BField, BInput } from '@/ui'
import ModeBanner from './ModeBanner.vue'
import { isProduction } from './mode'
import { bonusTotal, formatMoney, parseMoney, toCents } from './lib'
import { plural, type AssignmentRow } from './rows'
import { readStore, writeStore } from '../storage'

const KEY = 'bb.recruit.mturk.bonusReason'

const props = defineProps<{ open: boolean, rows: AssignmentRow[] }>()
const emit = defineEmits<{ 'update:open': [value: boolean], pay: [items: { row: AssignmentRow, amount: number }[], reason: string] }>()

const reason = ref(readStore(KEY) || 'Final game score.')
const showError = ref(false)
watch(() => props.open, (o) => { if (o) showError.value = false })

function invalid (r: AssignmentRow) {
  return r.bonusInput.trim() !== '' && parseMoney(r.bonusInput) == null
}
const anyInvalid = computed(() => props.rows.some(invalid))
const paying = computed(() => props.rows
  .map(row => ({ row, amount: parseMoney(row.bonusInput) }))
  .filter((x): x is { row: AssignmentRow, amount: number } => x.amount != null))
const total = computed(() => bonusTotal(paying.value.map(p => p.amount)))
const fee = computed(() => toCents(total.value * 0.2) / 100)

function submit () {
  if (!reason.value.trim()) { showError.value = true; return }
  if (!paying.value.length || anyInvalid.value) return
  writeStore(KEY, reason.value.trim())
  emit('pay', paying.value, reason.value.trim())
}
</script>

<style>
.mt-bonus__cell { width: 160px; }
.mt-bonus__input { margin-left: auto; max-width: 120px; }
.mt-bonus__input .b-input__el { text-align: right; font-variant-numeric: tabular-nums; }
.mt-bonus__total {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
  border: 1px solid var(--border);
}
.mt-bonus__sum { font-size: var(--text-2xl); font-weight: 700; font-variant-numeric: tabular-nums; }
</style>
