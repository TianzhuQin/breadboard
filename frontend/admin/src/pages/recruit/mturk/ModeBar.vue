<template>
  <div class="mt-bar" :class="{ 'is-prod': isProduction }">
    <div class="mt-bar__mode">
      <div class="mt-seg" role="radiogroup" aria-label="Which Mechanical Turk site to use" @keydown="onKey">
        <button
          v-for="o in OPTIONS"
          :key="o.value"
          ref="buttons"
          type="button"
          role="radio"
          class="mt-seg__opt"
          :class="[`is-${o.value}`, { 'is-on': mode === o.value }]"
          :aria-checked="mode === o.value ? 'true' : 'false'"
          :tabindex="mode === o.value ? 0 : -1"
          :disabled="locked && mode !== o.value"
          @click="pick(o.value)"
        >
          <component :is="o.icon" :size="15" aria-hidden="true" />
          {{ o.label }}
        </button>
      </div>
      <p class="mt-bar__desc">
        <template v-if="isProduction">
          <strong>Real workers, real money.</strong> Approvals, bonuses and payment HITs are charged to your MTurk account.
        </template>
        <template v-else>
          MTurk’s free practice site. Try your HIT here first; workers test it at workersandbox.mturk.com.
        </template>
        <span v-if="locked" class="mt-bar__lock"> Wait for the running payments to finish before switching.</span>
      </p>
    </div>

    <div class="mt-bar__side">
      <div class="mt-bar__balance" aria-live="polite">
        <Wallet :size="16" class="rc-faint" aria-hidden="true" />
        <span class="mt-bar__label">Balance</span>
        <BSpinner v-if="loading" :size="14" label="Loading the balance" />
        <span v-else-if="error" class="rc-error-text" :title="error">Could not load it</span>
        <span v-else-if="noKeys" class="rc-faint">Not connected</span>
        <span v-else-if="balance" class="mt-bar__amount">{{ formatMoney(balance.available) }}</span>
        <span v-else class="rc-faint">—</span>
        <span v-if="balance && balance.onHold" class="rc-sub">· {{ formatMoney(balance.onHold) }} on hold</span>
        <BButton size="sm" variant="ghost" :icon="RefreshCw" aria-label="Refresh the balance" title="Refresh the balance" :disabled="loading" @click="emit('refresh')" />
      </div>
      <BButton size="sm" variant="ghost" :icon-right="ExternalLink" :href="sites.requester" target="_blank" rel="noopener noreferrer">
        {{ isProduction ? 'Requester site' : 'Sandbox requester site' }}
      </BButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ExternalLink, RefreshCw, ShieldAlert, TestTube, Wallet } from 'lucide-vue-next'
import { BButton, BSpinner, confirm } from '@/ui'
import { isProduction, mode, sites, type MturkMode } from './mode'
import { activeJobs, noKeys } from './api'
import { formatMoney } from './lib'

defineProps<{
  balance: { available: number | null, onHold: number | null } | null
  loading: boolean
  error: string
}>()
const emit = defineEmits<{ refresh: [] }>()

const OPTIONS = [
  { value: 'sandbox' as MturkMode, label: 'Sandbox', icon: TestTube },
  { value: 'production' as MturkMode, label: 'Production', icon: ShieldAlert },
]
const buttons = ref<HTMLButtonElement[]>([])
const locked = computed(() => activeJobs.value > 0)

async function pick (m: MturkMode) {
  if (m === mode.value || locked.value) return
  if (m === 'production') {
    const ok = await confirm({
      title: 'Switch to production?',
      message: 'This tab will then use the real Mechanical Turk: real workers see the HITs you post, and approving work, paying bonuses and creating payment HITs spend real money from your account.\n\nThis browser remembers the choice until you switch back.',
      confirmLabel: 'Use production',
      danger: true,
    })
    if (!ok) return
  }
  mode.value = m
  await nextTick()
  buttons.value[OPTIONS.findIndex(o => o.value === m)]?.focus()
}

function onKey (e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return
  e.preventDefault()
  pick(mode.value === 'sandbox' ? 'production' : 'sandbox')
}
</script>

<style>
.mt-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px 24px;
  flex-wrap: wrap;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.mt-bar.is-prod {
  border-color: var(--danger);
  background: var(--danger-soft);
  box-shadow: inset 4px 0 0 var(--danger), var(--shadow-sm);
}
.mt-bar__mode { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; min-width: 0; flex: 1 1 420px; }
.mt-bar__desc { flex: 1 1 240px; min-width: 0; font-size: var(--text-sm); color: var(--muted); }
.mt-bar.is-prod .mt-bar__desc { color: var(--danger-soft-text); }
.mt-bar__desc strong { color: inherit; }
.mt-bar__lock { font-weight: 600; }
.mt-bar__side { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mt-bar__balance { display: flex; align-items: center; gap: 7px; font-size: var(--text-sm); }
.mt-bar__label { color: var(--muted); }
.mt-bar__amount { font-weight: 650; font-size: var(--text-lg); font-variant-numeric: tabular-nums; color: var(--text); }

.mt-seg { display: inline-flex; padding: 3px; gap: 2px; border-radius: var(--radius-sm); background: var(--bg-subtle); flex: none; }
.mt-seg__opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease);
}
.mt-seg__opt:hover:not(:disabled) { color: var(--text); }
.mt-seg__opt:disabled { opacity: 0.5; cursor: not-allowed; }
.mt-seg__opt.is-on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
.mt-seg__opt.is-production.is-on { background: var(--danger); color: var(--on-accent); }
</style>
