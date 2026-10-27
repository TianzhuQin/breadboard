<template>
  <BDialog :open="moneyState.open" :title="req?.title" size="md" @close="settleMoney(false)">
    <div class="rc-stack mt-money">
      <ModeBanner />
      <p v-if="req?.message" class="mt-money__msg">{{ req.message }}</p>
      <ul v-if="shown.length" class="mt-money__lines">
        <li v-for="(l, i) in shown" :key="i" class="mt-money__line">
          <span class="mt-money__label">
            {{ l.label }}
            <span v-if="l.note" class="rc-sub">{{ l.note }}</span>
          </span>
          <span v-if="l.amount != null" class="mt-money__amount">{{ formatMoney(l.amount) }}</span>
        </li>
        <li v-if="hidden > 0" class="mt-money__line rc-muted">and {{ hidden }} more</li>
      </ul>
      <div v-if="req?.total != null" class="mt-money__total">
        <span>{{ req.totalLabel || 'Total' }}</span>
        <span class="mt-money__total-amount">{{ formatMoney(req.total) }}</span>
      </div>
      <p v-if="req?.footnote" class="mt-money__foot">{{ req.footnote }}</p>
    </div>
    <template #footer>
      <BButton variant="ghost" data-autofocus @click="settleMoney(false)">Cancel</BButton>
      <BButton :variant="isProduction ? 'danger' : 'primary'" @click="settleMoney(true)">{{ req?.confirmLabel || 'Confirm' }}</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BButton, BDialog } from '@/ui'
import ModeBanner from './ModeBanner.vue'
import { moneyState, settleMoney } from './moneyConfirm'
import { isProduction } from './mode'
import { formatMoney } from './lib'

const MAX_LINES = 8
const req = computed(() => moneyState.req)
const shown = computed(() => (req.value?.lines || []).slice(0, MAX_LINES))
const hidden = computed(() => Math.max(0, (req.value?.lines?.length || 0) - MAX_LINES))
</script>

<style>
.mt-money { gap: 14px; }
.mt-money__msg { color: var(--text-soft); white-space: pre-line; }
.mt-money__lines { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-sm); }
.mt-money__line { display: flex; justify-content: space-between; gap: 12px; padding: 8px 12px; font-size: var(--text-sm); }
.mt-money__line + .mt-money__line { border-top: 1px solid var(--border); }
.mt-money__label { min-width: 0; overflow-wrap: anywhere; }
.mt-money__amount { font-variant-numeric: tabular-nums; white-space: nowrap; }
.mt-money__total { display: flex; justify-content: space-between; align-items: baseline; padding: 0 2px; font-weight: 600; }
.mt-money__total-amount { font-size: var(--text-xl); font-variant-numeric: tabular-nums; }
.mt-money__foot { font-size: var(--text-xs); color: var(--muted); }
</style>
