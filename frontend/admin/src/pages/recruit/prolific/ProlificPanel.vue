<template>
  <NeedsExperiment v-if="!admin.experiment" />

  <div v-else class="pl">
    <div class="pl__main">
      <!-- A study whose participants never get a code can't be submitted on Prolific: say so first. -->
      <div v-if="!givesCode" class="pl-nocode" role="note">
        <TriangleAlert :size="17" aria-hidden="true" />
        <div>
          <p class="pl-nocode__title">“{{ admin.experiment.name }}” never gives Prolific participants a completion code</p>
          <p class="pl-nocode__text">
            None of its steps calls <code>recruitment.completeProlific</code>, so people who come from Prolific
            finish without a code and can't submit the study. Add it to your last step, as shown under
            “When a participant finishes”.
          </p>
        </div>
      </div>

      <StudyLinkCard
        :experiment="admin.experiment"
        :target="target.open"
        :started="target.started"
        :autopilot="opensNextSoon"
        :held-up="heldUp"
      />

      <BPanel title="When a participant arrives" subtitle="What Breadboard does with each person Prolific sends.">
        <ol class="pl-steps">
          <li>
            <span class="pl-steps__n" aria-hidden="true">1</span>
            <div>
              <strong>They join the open session.</strong> Breadboard forwards them to the session the engine is running, when it is this experiment’s and its game hasn’t started.
              <template v-if="opensNextSoon">Otherwise they wait on a page that takes them into the autopilot’s next session as soon as it opens.</template>
              <template v-else>Otherwise they see “No session is open right now”.</template>
            </div>
          </li>
          <li>
            <span class="pl-steps__n" aria-hidden="true">2</span>
            <div><strong>Their Prolific ID stays private.</strong> It is swapped for a player id, so other participants never see it. The same browser coming back returns to its seat; the link can’t be reused in another browser (“Already joined”).</div>
          </li>
          <li>
            <span class="pl-steps__n" aria-hidden="true">3</span>
            <div v-if="opensNextSoon"><strong>Late arrivals wait for the next session.</strong> Once the game has started, newcomers are told a session is in progress and can keep the page open until the autopilot opens the next one, or return the study on Prolific.</div>
            <div v-else><strong>Late arrivals are turned away politely.</strong> Once the game has started, newcomers are asked to return the study on Prolific.</div>
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

      <CompletionCard :experiment-id="admin.experiment.id" :parameters="admin.experiment.parameters" />
      <ProlificHistory :experiment-id="admin.experiment.id" />
    </div>

    <aside class="pl__side">
      <AutopilotCard :experiment-id="admin.experiment.id" />
      <ProlificPlayers :has-session="!!admin.instance" />
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { TriangleAlert } from 'lucide-vue-next'
import { BPanel } from '@/ui'
import { admin, instances } from '@/store/admin'
import { autopilotHold, autopilotOpensNextSoon, useAutopilotStatus } from '../../session/autopilot'
import NeedsExperiment from '@/shell/NeedsExperiment.vue'
import StudyLinkCard from './StudyLinkCard.vue'
import CompletionCard from './CompletionCard.vue'
import AutopilotCard from './AutopilotCard.vue'
import ProlificPlayers from './ProlificPlayers.vue'
import ProlificHistory from './ProlificHistory.vue'
import { prolificTarget } from './prolific'

// (a call that is commented out doesn't count)
const givesCode = computed(() => (admin.experiment?.steps || []).some(st =>
  (st.source || '').split('\n').some(line => {
    const at = line.indexOf('completeProlific')
    return at >= 0 && !line.slice(0, at).includes('//')
  })))

// The autopilot opens this experiment's next session by itself (on, continuous, neither paused nor
// done), and nothing keeps it from doing so: until then, the study link holds newcomers on a page that
// waits for that session. While something does (another experiment's session on the engine, say), the
// link says no session is open, as the server decides with the same status (Autopilot.opensNextSoon).
// (asked again every 10 s: no console hears of every such problem)
const autopilot = useAutopilotStatus({ everyMs: 10000 })
const opensNextSoon = computed(() => autopilotOpensNextSoon(autopilot.value, admin.experiment?.id))
const heldUp = computed(() => autopilotHold(autopilot.value, admin.experiment?.id))

/** Where /play/<e>/prolific leads: the engine's session, while its game hasn't started. */
const target = computed(() => {
  const engine = admin.user?.engine
  return prolificTarget(instances.value, engine ? engine.sessionId : undefined)
})
</script>

<style>
.pl-nocode { display: flex; gap: 12px; align-items: flex-start; padding: 14px 16px; border-radius: var(--radius); background: var(--warning-soft); color: var(--warning-soft-text); border: 1px solid color-mix(in srgb, var(--warning) 25%, transparent); }
.pl-nocode > svg { flex: none; margin-top: 2px; }
.pl-nocode__title { font-weight: 650; }
.pl-nocode__text { margin-top: 3px; font-size: var(--text-sm); line-height: 1.5; }
.pl-nocode code { color: inherit; }
.pl { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 20px; align-items: start; }
.pl__main { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.pl__side { display: flex; flex-direction: column; gap: 16px; min-width: 0; position: sticky; top: 0; }
@media (max-width: 1100px) {
  .pl { grid-template-columns: minmax(0, 1fr); }
  .pl__side { position: static; }
}
</style>
