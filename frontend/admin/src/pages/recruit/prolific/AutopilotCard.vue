<template>
  <section class="pl-side-card" aria-labelledby="pl-ap-title">
    <div class="pl-side-card__head">
      <div class="pl-side-card__icon" aria-hidden="true"><Bot :size="18" /></div>
      <h3 id="pl-ap-title" class="pl-side-card__title">Run it unattended</h3>
    </div>
    <p class="rc-muted pl-side-card__text">
      Autopilot opens a new session whenever none is open, so people arriving from Prolific always
      find one. It can also hold a waiting room until enough people are there and let an AI take
      over for anyone who drops out.
    </p>
    <div v-if="state !== 'unknown'" class="pl-ap__status" aria-live="polite">
      <BSpinner v-if="state === 'loading'" :size="13" label="Checking Autopilot" />
      <template v-else-if="state === 'ready' && ap">
        <BBadge :tone="tone" dot>{{ label }}</BBadge>
        <span v-if="ap.source === 'config'" class="rc-sub">Set in the server’s configuration</span>
      </template>
    </div>
    <BButton :icon-right="ArrowRight" @click="router.push('/autopilot')">Open Autopilot</BButton>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Bot } from 'lucide-vue-next'
import { BBadge, BButton, BSpinner } from '@/ui'
import { http } from '@/lib/http'

interface AutopilotStatus {
  source: 'admin' | 'config' | 'off'
  /**
   * paused: a researcher stopped its session; done: its one session finished ("continuous" off).
   * Either way it opens none, and the Prolific link leads nowhere, until Resume on the Autopilot page.
   */
  status: { on: boolean, experimentId: number | null, experimentName: string | null, paused?: boolean, done?: boolean }
}

const props = defineProps<{ experimentId: number }>()
const router = useRouter()

const state = ref<'loading' | 'ready' | 'unknown'>('loading')
const ap = ref<AutopilotStatus | null>(null)

const forThis = computed(() => !!ap.value?.status?.on && ap.value.status.experimentId === props.experimentId)
const paused = computed(() => forThis.value && ap.value?.status?.paused === true)
const done = computed(() => forThis.value && !paused.value && ap.value?.status?.done === true)
const tone = computed(() => (paused.value || done.value ? 'warning' : forThis.value ? 'success' : ap.value?.status?.on ? 'warning' : 'neutral'))
const label = computed(() => {
  const s = ap.value?.status
  if (!s?.on) return 'Off'
  if (paused.value) return 'Paused for this experiment'
  if (done.value) return 'Done: it opens no more sessions'
  if (forThis.value) return 'On for this experiment'
  return `On for ${s.experimentName || 'another experiment'}`
})

// Only a hint: when the server can't say, the card still leads to the Autopilot page.
onMounted(async () => {
  try {
    const res = await http.get<AutopilotStatus>('/autopilot')
    if (res && typeof res === 'object' && res.status) {
      ap.value = res
      state.value = 'ready'
    } else {
      state.value = 'unknown'
    }
  } catch (e) {
    state.value = 'unknown'
  }
})
</script>

<style>
.pl-side-card { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; padding: 16px 18px; border: 1px solid var(--border); border-radius: var(--radius-lg); background: var(--surface); box-shadow: var(--shadow-sm); min-width: 0; }
.pl-side-card__head { display: flex; align-items: center; gap: 10px; }
.pl-side-card__icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 9px; background: var(--accent-soft); color: var(--accent); }
.pl-side-card__title { font-size: var(--text-md); }
.pl-side-card__text { font-size: var(--text-sm); }
.pl-ap__status { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-height: 22px; }
</style>
