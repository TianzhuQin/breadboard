<template>
  <section class="mt-created" aria-labelledby="mt-created-title">
    <h4 id="mt-created-title" class="mt-created__title">HITs for this session</h4>
    <article v-for="h in hits" :key="h.hitId" class="mt-created__card">
      <header class="mt-created__head">
        <div class="mt-created__name">
          <CircleCheck :size="18" class="mt-created__ok" aria-hidden="true" />
          <span class="rc-strong">{{ h.title }}</span>
        </div>
        <BBadge :tone="h.sandbox ? 'info' : 'danger'" dot>{{ h.sandbox ? 'Sandbox' : 'Production' }}</BBadge>
      </header>

      <dl class="rc-kv">
        <dt>Posted</dt>
        <dd>{{ formatTime(h.creationTime) }}</dd>
        <dt>HIT ID</dt>
        <dd class="mt-created__copy"><span class="rc-mono">{{ h.hitId }}</span><CopyButton :text="h.hitId" icon-only size="sm" variant="ghost" aria-label="Copy the HIT ID" /></dd>
        <dt>Reward</dt>
        <dd>{{ formatMoney(parseFloat(h.reward)) }} × {{ h.maxAssignments }} places</dd>
        <dt>Open for</dt>
        <dd>{{ humanDuration(num(h.lifetimeInSeconds)) }}, then {{ humanDuration(num(h.tutorialTime)) }} of tutorial time</dd>
        <dt>Repeat players</dt>
        <dd>{{ REPEAT[h.disallowPrevious] || h.disallowPrevious }}</dd>
        <dt>Workers open</dt>
        <dd class="mt-created__copy"><span class="rc-mono rc-break">{{ h.externalURL }}</span><CopyButton :text="h.externalURL" icon-only size="sm" variant="ghost" aria-label="Copy the address" /></dd>
      </dl>

      <div v-if="unreachable(h.externalURL)" class="rc-note rc-note--warning">
        <TriangleAlert :size="16" aria-hidden="true" />
        <span>
          Workers can’t open this address: MTurk needs a public <strong>https</strong> address. Set
          <code>breadboard.rootUrl</code> in the server’s configuration to this server’s public
          address (for example <code>https://study.example.edu</code>) before posting real HITs.
        </span>
      </div>

      <div v-if="schedules[h.hitId]" class="rc-note" :class="schedules[h.hitId].startsAt > now ? 'rc-note--info' : ''">
        <Timer :size="16" aria-hidden="true" />
        <span>
          <template v-if="schedules[h.hitId].startsAt > now">
            New workers are turned away at <strong>{{ clock(schedules[h.hitId].closesAt) }}</strong>; the game starts by itself at
            <strong>{{ clock(schedules[h.hitId].startsAt) }}</strong> (in {{ humanDuration((schedules[h.hitId].startsAt - now) / 1000) }}).
            Reloading the engine or restarting the session cancels this; then start the game from Live session.
          </template>
          <template v-else>
            The game was set to start by itself at {{ clock(schedules[h.hitId].startsAt) }}. If it didn’t, start it from Live session.
          </template>
        </span>
      </div>
    </article>
    <div class="rc-row">
      <BButton size="sm" :icon="ListChecks" @click="emit('view-hits')">Review submissions</BButton>
      <BButton size="sm" variant="ghost" :icon="Activity" @click="router.push('/session')">Live session</BButton>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, CircleCheck, ListChecks, Timer, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton } from '@/ui'
import CopyButton from '../CopyButton.vue'
import type { CreatedHit } from './api'
import { formatMoney, formatTime, humanDuration } from './lib'
import { readSchedules, type AutoStart } from './hitForm'

defineProps<{ hits: CreatedHit[] }>()
const emit = defineEmits<{ 'view-hits': [] }>()
const router = useRouter()

const REPEAT: Record<string, string> = {
  type: 'Turned away if they completed this experiment before',
  any: 'Turned away if they completed any Breadboard experiment',
  none: 'Welcome again',
}

const schedules = ref<Record<string, AutoStart>>(readSchedules())
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
    schedules.value = readSchedules()
  }, 15000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
defineExpose({ reload: () => { schedules.value = readSchedules(); now.value = Date.now() } })

function num (s: string) {
  const x = parseInt(s, 10)
  return Number.isFinite(x) ? x : null
}
function clock (ms: number) {
  return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}
function unreachable (url: string) {
  return !url || !/^https:\/\//i.test(url) || /\/\/(localhost|127\.0\.0\.1|\[::1\])([:/]|$)/i.test(url)
}
</script>

<style>
.mt-created { display: flex; flex-direction: column; gap: 12px; }
.mt-created__title { font-size: var(--text-md); }
.mt-created__card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow-sm);
}
.mt-created__head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.mt-created__name { display: flex; align-items: center; gap: 8px; min-width: 0; }
.mt-created__ok { color: var(--success); flex: none; }
.mt-created__copy { display: flex; align-items: center; gap: 4px; }
</style>
