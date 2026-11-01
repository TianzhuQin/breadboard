<template>
  <section class="pl-side-card" aria-labelledby="pl-players-title">
    <div class="pl-side-card__head">
      <div class="pl-side-card__icon" aria-hidden="true"><Users :size="18" /></div>
      <h3 id="pl-players-title" class="pl-side-card__title">From Prolific in this session</h3>
    </div>

    <p v-if="!hasSession" class="rc-muted pl-side-card__text">Start a session to see who joins from Prolific.</p>
    <!-- (the network this console has is the engine's) -->
    <p v-else-if="elsewhere" class="rc-muted pl-side-card__text">
      The engine now runs {{ elsewhere }}, so this list can’t show who came to this session:
      From Prolific in every session lists them.
    </p>
    <p v-else-if="!players.length && !live" class="rc-muted pl-side-card__text">This session has ended. From Prolific in every session lists who came to it.</p>
    <p v-else-if="!players.length" class="rc-muted pl-side-card__text">Nobody has joined from Prolific yet. They appear here as they arrive.</p>

    <template v-else>
      <p class="pl-players__sum" aria-live="polite">
        <strong>{{ players.length }}</strong> joined · <strong>{{ finished }}</strong> finished<template v-if="noCode">
          ({{ noCode }} without a code)</template>
      </p>
      <ul class="pl-players">
        <li v-for="p in players" :key="p.playerId" class="pl-players__row">
          <div class="pl-players__main">
            <span class="rc-mono pl-players__pid" :title="p.prolificPid">{{ p.prolificPid }}</span>
            <span class="rc-sub">Player {{ p.playerId }}<template v-if="p.bonus != null"> · bonus {{ money(p.bonus) }}</template></span>
          </div>
          <BBadge v-if="p.completed" tone="success" :title="p.completionCode ? `Code ${p.completionCode}` : undefined">Finished</BBadge>
          <BBadge v-else-if="p.finishedWithoutCode" tone="warning" title="The game ended for them, but no completion code was set">No code</BBadge>
          <!-- (the session has ended: nobody plays any more) -->
          <BBadge v-else-if="!live" tone="neutral">Not finished</BBadge>
          <BBadge v-else-if="p.connected === false" tone="warning">Away</BBadge>
          <BBadge v-else tone="info">Playing</BBadge>
        </li>
      </ul>
      <div v-if="bonusLines" class="pl-players__bonus">
        <CopyButton :text="bonusLines" size="sm" label="Copy bonus list" />
        <span class="rc-sub">For Prolific’s bulk bonus: one “ID,amount” per line.</span>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Users } from 'lucide-vue-next'
import { BBadge } from '@/ui'
import { admin, graph } from '@/store/admin'
import CopyButton from '../CopyButton.vue'
import { formatMoney } from '../mturk/lib'
import { playersWithoutCode, prolificBonusLines, prolificFinished, prolificPlayers } from './prolific'

defineProps<{ hasSession: boolean }>()

// Who the session recorded as finished without a completion code (noCompletionCode), as the list of
// every session beside this one reads its data: they finished, as it says, rather than play on.
const withoutCode = computed(() => playersWithoutCode(admin.instance?.events, admin.events))
/**
 * What the engine runs instead of this session, when it runs another (another experiment's session, or
 * one of this experiment's that the console doesn't show): the players this console has are that
 * one's, not this session's, so they aren't listed. Null when they are this session's.
 */
const elsewhere = computed(() => {
  const e = admin.user?.engine
  const inst = admin.instance
  if (!e || !inst || !admin.engineInfoCurrent || e.experimentId === null) return null
  const other = e.experimentId !== admin.experiment?.id
  if (!other && (e.sessionId === null || e.sessionId === inst.id)) return null
  if (e.sessionId === null) return e.experimentName ? `“${e.experimentName}”` : 'another experiment'
  const session = e.sessionName ? `“${e.sessionName}”` : 'another session'
  return other && e.experimentName ? `${session} of “${e.experimentName}”` : session
})
const live = computed(() => admin.instance?.status === 'RUNNING' || admin.instance?.status === 'TESTING')
const players = computed(() => {
  void graph.version.value
  return prolificPlayers(graph.nodes.values(), withoutCode.value)
})
const finished = computed(() => players.value.filter(prolificFinished).length)
const noCode = computed(() => players.value.filter(p => p.finishedWithoutCode).length)
const bonusLines = computed(() => prolificBonusLines(players.value))
// Prolific pays in the study's currency, so no currency sign here
const money = (n: number) => formatMoney(n).replace(/^\$/, '')
</script>

<style>
.pl-players__sum { font-size: var(--text-sm); color: var(--muted); }
.pl-players__sum strong { color: var(--text); }
.pl-players { list-style: none; margin: 0; padding: 0; width: 100%; max-height: 360px; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); }
.pl-players__row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; }
.pl-players__row + .pl-players__row { border-top: 1px solid var(--border); }
.pl-players__main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.pl-players__pid { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text); }
.pl-players__bonus { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
