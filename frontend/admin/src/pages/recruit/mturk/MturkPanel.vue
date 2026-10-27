<template>
  <div class="mt rc-stack">
    <ModeBar :balance="balance" :loading="balanceLoading" :error="balanceError" @refresh="loadBalance" />

    <div v-if="!checked" class="rc-loading" role="status">
      <BSpinner :size="16" />
      <span>Connecting to Mechanical Turk…</span>
    </div>

    <NoKeysCard v-else-if="noKeys" :checking="balanceLoading" @retry="recheck" @prolific="emit('prolific')" />

    <template v-else>
      <BTabs v-model="sub" :tabs="SUBTABS" aria-label="Mechanical Turk sections" variant="pill" />

      <!-- rebuilt when the mode changes, so nothing from the other site lingers -->
      <div :key="mode" class="mt__body">
        <HitsTab v-if="opened.hits" v-show="sub === 'hits'" :api="api" @create="sub = 'create'" />
        <CreateHitTab v-if="opened.create" v-show="sub === 'create'" :api="api" @view-hits="sub = 'hits'" />
        <PaymentHitsTab v-if="opened.pay" v-show="sub === 'pay'" :api="api" @view-hits="sub = 'hits'" />
        <WorkersTab v-if="opened.workers" v-show="sub === 'workers'" :api="api" />
        <QualificationsTab v-if="opened.quals" v-show="sub === 'quals'" :api="api" />
      </div>
    </template>

    <MoneyConfirmDialog />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Award, HandCoins, ListChecks, Plus, Users } from 'lucide-vue-next'
import { BSpinner, BTabs, type TabItem } from '@/ui'
import ModeBar from './ModeBar.vue'
import NoKeysCard from './NoKeysCard.vue'
import MoneyConfirmDialog from './MoneyConfirmDialog.vue'
import HitsTab from './HitsTab.vue'
import CreateHitTab from './CreateHitTab.vue'
import PaymentHitsTab from './PaymentHitsTab.vue'
import WorkersTab from './WorkersTab.vue'
import QualificationsTab from './QualificationsTab.vue'
import { mode } from './mode'
import { balanceTick, mturkApi, noKeys } from './api'
import { describeError } from './lib'

const emit = defineEmits<{ prolific: [] }>()

type Sub = 'hits' | 'create' | 'pay' | 'workers' | 'quals'
const SUBTABS: TabItem[] = [
  { value: 'hits', label: 'HITs', icon: ListChecks },
  { value: 'create', label: 'Create HIT', icon: Plus },
  { value: 'pay', label: 'Payment HITs', icon: HandCoins },
  { value: 'workers', label: 'Workers', icon: Users },
  { value: 'quals', label: 'Qualifications', icon: Award },
]
const SUB_VALUES = SUBTABS.map(t => t.value)

const route = useRoute()
const router = useRouter()

// The section lives in the address (?mt=…) so a link or reload lands on it.
const sub = computed<Sub>({
  get: () => (SUB_VALUES.includes(String(route.query.mt)) ? route.query.mt : 'hits') as Sub,
  set: (v) => { router.replace({ query: { ...route.query, mt: v } }) },
})

// Sections load only when first opened, and stay loaded while switching between them.
const opened = reactive<Record<Sub, boolean>>({ hits: false, create: false, pay: false, workers: false, quals: false })
watch(sub, (s) => { opened[s] = true }, { immediate: true })

const api = computed(() => mturkApi(mode.value))

const balance = ref<{ available: number | null, onHold: number | null } | null>(null)
const balanceLoading = ref(false)
const balanceError = ref('')
/** The first balance call doubles as the check for AWS keys. */
const checked = ref(false)

function num (s: string | null | undefined): number | null {
  if (s == null || s === '') return null
  const x = parseFloat(s)
  return Number.isFinite(x) ? x : null
}

async function loadBalance () {
  const m = mode.value
  balanceLoading.value = true
  balanceError.value = ''
  try {
    const r = await mturkApi(m).balance()
    if (m !== mode.value) return
    balance.value = { available: num(r.availableBalance), onHold: num(r.onHoldBalance) }
  } catch (e) {
    if (m !== mode.value) return
    const d = describeError(e)
    balance.value = null
    balanceError.value = d.noKeys ? '' : [d.message, d.detail].filter(Boolean).join(' ')
  } finally {
    if (m === mode.value) {
      balanceLoading.value = false
      checked.value = true
    }
  }
}

function recheck () {
  noKeys.value = false
  checked.value = false
  loadBalance()
}

watch(mode, () => {
  balance.value = null
  for (const k of Object.keys(opened) as Sub[]) opened[k] = k === sub.value
  loadBalance()
})
watch(balanceTick, () => loadBalance())
onMounted(() => {
  // a previous visit may have found no keys; look again rather than trusting it
  noKeys.value = false
  loadBalance()
})
</script>

<style>
.mt__body { min-width: 0; }
</style>
