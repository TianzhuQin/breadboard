<template>
  <section class="pl-side-card" aria-labelledby="pl-ap-title">
    <div class="pl-side-card__head">
      <div class="pl-side-card__icon" aria-hidden="true"><Bot :size="18" /></div>
      <h3 id="pl-ap-title" class="pl-side-card__title">Run it unattended</h3>
    </div>
    <!-- (it promises nothing the link may not do: the Study URL card says when it can't open a session) -->
    <p class="rc-muted pl-side-card__text">
      Autopilot opens a new session whenever none is open, and can hold a waiting room until enough
      people are there and let an AI take over for anyone who drops out. The Study URL card says when
      something keeps it from opening one.
    </p>
    <div v-if="status" class="pl-ap__status" aria-live="polite">
      <BBadge :tone="tone" dot>{{ label }}</BBadge>
      <span v-if="status.source === 'config'" class="rc-sub">Set in the server’s configuration</span>
    </div>
    <BButton :icon-right="ArrowRight" @click="router.push('/autopilot')">Open Autopilot</BButton>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Bot } from 'lucide-vue-next'
import { BBadge, BButton } from '@/ui'
import { autopilotHold, autopilotRuns, type AutopilotStatus } from '../../session/autopilot'

const props = defineProps<{
  experimentId: number
  /**
   * GET /autopilot, as the Recruitment page asks for it every 10 s and whenever the engine changes
   * (useAutopilotStatus), so the badge follows a pause, the done state and what holds it up, as the
   * Study URL card beside it does. It used to be asked for once, as the card appeared. Null while
   * unknown (an older server, a failed request): the card still leads to the Autopilot page.
   */
  status: AutopilotStatus | null
}>()
const router = useRouter()

const forThis = computed(() => autopilotRuns(props.status, props.experimentId))
/** something keeps it from opening this experiment's next session (another experiment's session, …) */
const heldUp = computed(() => autopilotHold(props.status, props.experimentId))
const tone = computed(() => {
  const s = props.status
  if (!s?.on) return 'neutral'
  return !forThis.value || s.halted || heldUp.value ? 'warning' : 'success'
})
const label = computed(() => {
  const s = props.status
  if (!s?.on) return 'Off'
  if (!forThis.value) return `On for ${s.experimentName || 'another experiment'}`
  // paused: a researcher stopped its session (or a step failed); done: its one session finished
  // ("continuous" off). Either way it opens none, and the Prolific link leads nowhere, until Resume.
  if (s.halted === 'paused') return 'Paused for this experiment'
  if (s.halted === 'done') return 'Done: it opens no more sessions'
  if (heldUp.value) return 'On, but held up for now'
  return 'On for this experiment'
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
