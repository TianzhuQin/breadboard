<template>
  <BPage intro="Bring participants in from Prolific or Amazon Mechanical Turk, and pay them when they’re done.">
    <div class="recruit">
      <BTabs v-model="tab" :tabs="TABS" aria-label="Recruitment service" />

      <div class="recruit__panel" role="region" :aria-label="tab === 'prolific' ? 'Prolific' : 'Mechanical Turk'">
        <ProlificPanel v-if="tab === 'prolific'" />
        <!-- Mechanical Turk is only contacted once this tab is opened, then kept while switching back and forth -->
        <MturkPanel v-if="mturkOpened" v-show="tab === 'mturk'" @prolific="tab = 'prolific'" />
      </div>
    </div>
  </BPage>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { Megaphone, UsersRound } from 'lucide-vue-next'
import { BPage, BSpinner, BTabs, confirm, type TabItem } from '@/ui'
import ProlificPanel from './prolific/ProlificPanel.vue'
import { readStore, writeStore } from './storage'
import { activeJobs } from './mturk/jobs'
import './recruit.css'

// Loaded with the tab: researchers who only use Prolific never download it.
const MturkPanel = defineAsyncComponent({
  loader: () => import('./mturk/MturkPanel.vue'),
  loadingComponent: { render: () => h('div', { class: 'rc-loading', role: 'status' }, [h(BSpinner, { size: 16 }), 'Loading Mechanical Turk…']) },
  delay: 150,
})

type Tab = 'prolific' | 'mturk'
const TABS: TabItem[] = [
  { value: 'prolific', label: 'Prolific', icon: UsersRound },
  { value: 'mturk', label: 'Mechanical Turk', icon: Megaphone },
]
const KEY = 'bb.recruit.tab'

const route = useRoute()
const router = useRouter()

function valid (v: unknown): v is Tab {
  return v === 'prolific' || v === 'mturk'
}

// The address wins (?tab=mturk), then what this browser used last.
const tab = computed<Tab>({
  get: () => {
    if (valid(route.query.tab)) return route.query.tab
    const last = readStore(KEY)
    return valid(last) ? last : 'prolific'
  },
  set: (v) => {
    writeStore(KEY, v)
    const query: Record<string, any> = { ...route.query, tab: v }
    if (v !== 'mturk') delete query.mt
    router.replace({ query })
  },
})

const mturkOpened = ref(tab.value === 'mturk')
watch(tab, (t) => { if (t === 'mturk') mturkOpened.value = true })

// Payments in progress keep going after leaving, but their outcome would no longer be shown.
onBeforeRouteLeave(async () => {
  if (!activeJobs.value) return true
  return confirm({
    title: 'Leave while payments are being sent?',
    message: 'Mechanical Turk requests are still running. They will finish, but you won’t see which ones succeeded or failed.',
    confirmLabel: 'Leave anyway',
    cancelLabel: 'Stay',
    danger: true,
  })
})
function onUnload (e: BeforeUnloadEvent) {
  if (activeJobs.value) { e.preventDefault(); e.returnValue = '' }
}
onMounted(() => window.addEventListener('beforeunload', onUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', onUnload))
</script>

<style>
.recruit { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.recruit__panel { min-width: 0; }
</style>
