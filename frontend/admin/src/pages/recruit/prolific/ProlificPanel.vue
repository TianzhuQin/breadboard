<template>
  <NeedsExperiment v-if="!admin.experiment" />

  <div v-else class="pl">
    <div class="pl__main">
      <StudyLinkCard :experiment="admin.experiment" :target="target.open" :started="target.started" />

      <BPanel title="When a participant arrives" subtitle="What Breadboard does with each person Prolific sends.">
        <ol class="pl-steps">
          <li>
            <span class="pl-steps__n" aria-hidden="true">1</span>
            <div><strong>They join the open session.</strong> Breadboard forwards them to the session the engine is running, when it is this experiment’s and its game hasn’t started. Otherwise they see “No session is open right now”.</div>
          </li>
          <li>
            <span class="pl-steps__n" aria-hidden="true">2</span>
            <div><strong>Their Prolific ID stays private.</strong> It is swapped for a player id, so other participants never see it. The same browser coming back returns to its seat; the link can’t be reused in another browser (“Already joined”).</div>
          </li>
          <li>
            <span class="pl-steps__n" aria-hidden="true">3</span>
            <div><strong>Late arrivals are turned away politely.</strong> Once the game has started, newcomers are asked to return the study on Prolific.</div>
          </li>
          <li>
            <span class="pl-steps__n" aria-hidden="true">4</span>
            <div>
              <strong>Everything is recorded.</strong> A <code>prolificEntry</code> event with the Prolific ID, study and session goes into the session’s data
              (<RouterLink to="/sessions">Sessions &amp; data</RouterLink>), and the player carries them as <code>player._system.recruitment</code>
              (<RouterLink to="/players">Players</RouterLink>).
            </div>
          </li>
        </ol>
      </BPanel>

      <CompletionCard :experiment-id="admin.experiment.id" />
    </div>

    <aside class="pl__side">
      <AutopilotCard :experiment-id="admin.experiment.id" />
      <ProlificPlayers :has-session="!!admin.instance" />
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BPanel } from '@/ui'
import { admin, instances } from '@/store/admin'
import NeedsExperiment from '@/shell/NeedsExperiment.vue'
import StudyLinkCard from './StudyLinkCard.vue'
import CompletionCard from './CompletionCard.vue'
import AutopilotCard from './AutopilotCard.vue'
import ProlificPlayers from './ProlificPlayers.vue'
import { prolificTarget } from './prolific'

/** Where /play/<e>/prolific leads: the engine's session, while its game hasn't started. */
const target = computed(() => {
  const engine = admin.user?.engine
  return prolificTarget(instances.value, engine ? engine.sessionId : undefined)
})
</script>

<style>
.pl { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 20px; align-items: start; }
.pl__main { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.pl__side { display: flex; flex-direction: column; gap: 16px; min-width: 0; position: sticky; top: 0; }
@media (max-width: 1100px) {
  .pl { grid-template-columns: minmax(0, 1fr); }
  .pl__side { position: static; }
}
</style>
