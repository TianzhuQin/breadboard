<template>
  <BPanel title="Study URL" subtitle="Paste this into your study on Prolific.">
    <div class="rc-stack pl-link">
      <div class="pl-link__box">
        <code class="pl-link__url" aria-label="Study URL">
          <template v-for="(part, i) in parts" :key="i"><span :class="{ 'pl-link__ph': part.ph }">{{ part.text }}</span></template>
        </code>
        <CopyButton :text="url" variant="primary" label="Copy URL" />
      </div>
      <LocalOnlyNote what="This URL uses" internet />
      <p class="rc-sub">
        In Prolific, choose to record Prolific IDs with URL parameters and keep the parts like
        <code>{{ PID_PLACEHOLDER }}</code> exactly as they are: Prolific fills them in for each participant.
        The link always leads to this experiment’s open session, so the same URL works for every session.
      </p>

      <div v-if="target" class="rc-note rc-note--success">
        <CircleCheck :size="16" aria-hidden="true" />
        <span>
          Right now it leads to <strong>{{ target.name }}</strong>, the session the engine is running, whose game hasn’t started.
          <template v-if="autopilot">
            Once its game has started, newcomers wait on a page that takes them into the autopilot’s next session as soon as it opens.
          </template>
          <template v-else>Once that session’s game has started, newcomers are turned away until another session opens.</template>
        </span>
      </div>
      <!-- The autopilot opens the next session by itself: the link holds newcomers until then. -->
      <div v-else-if="autopilot" class="rc-note rc-note--info">
        <Hourglass :size="16" aria-hidden="true" />
        <span v-if="started">
          The game in <strong>{{ started.name }}</strong> has started. The autopilot opens the next session when it ends;
          until then, the link shows a page that waits for that session and takes newcomers in as soon as it opens.
        </span>
        <span v-else>
          The autopilot opens the next session in a moment; until then, the link shows a page that waits for it and
          takes newcomers in as soon as it opens.
        </span>
      </div>
      <div v-else-if="started" class="rc-note rc-note--warning">
        <TriangleAlert :size="16" aria-hidden="true" />
        <span>
          The game in <strong>{{ started.name }}</strong> has already started, so right now the link shows
          “No session is open right now”. Start a new session on <RouterLink to="/session">Live session</RouterLink>, or let
          <RouterLink to="/autopilot">Autopilot</RouterLink> keep one open.
        </span>
      </div>
      <div v-else class="rc-note rc-note--warning">
        <TriangleAlert :size="16" aria-hidden="true" />
        <span>
          No session is running, so participants would see “No session is open right now”.
          Start one on <RouterLink to="/session">Live session</RouterLink>, or let
          <RouterLink to="/autopilot">Autopilot</RouterLink> keep one open.
        </span>
      </div>

      <div class="pl-link__try">
        <BButton :icon="ExternalLink" @click="tryIt">Try it as a test participant</BButton>
        <span class="rc-sub">Opens a new tab with a made-up Prolific ID. It takes a seat in the session like a real participant.</span>
      </div>
    </div>
  </BPanel>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, ExternalLink, Hourglass, TriangleAlert } from 'lucide-vue-next'
import { BButton, BPanel } from '@/ui'
import type { Experiment, Instance } from '@/store/types'
import CopyButton from '../CopyButton.vue'
import LocalOnlyNote from '../../session/LocalOnlyNote.vue'
import { prolificStudyUrl, prolificTestUrl, randomToken } from './prolific'

const props = defineProps<{
  experiment: Experiment
  /** the session the link leads to now */
  target: Instance | null
  /** a running session whose game has started (why there is no target) */
  started?: Instance | null
  /**
   * The autopilot runs this experiment and opens its next session by itself (on, continuous, neither
   * paused nor done): until then, the link holds newcomers on a page that waits for that session.
   */
  autopilot?: boolean
}>()

// written as data so the template doesn't read the braces as its own syntax
const PID_PLACEHOLDER = '{{%PROLIFIC_PID%}}'

const url = computed(() => prolificStudyUrl(window.location.origin, props.experiment.id))
const parts = computed(() => url.value.split(/(\{\{%[A-Z_]+%\}\})/).filter(Boolean).map(text => ({ text, ph: /^\{\{%/.test(text) })))

function tryIt () {
  window.open(prolificTestUrl(window.location.origin, props.experiment.id, randomToken()), '_blank', 'noopener')
}
</script>

<style>
.pl-link__box {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 10px 10px 14px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
}
.pl-link__url { flex: 1; min-width: 0; font-size: 13px; line-height: 1.55; color: var(--text); overflow-wrap: anywhere; user-select: all; }
.pl-link__ph { color: var(--accent); font-weight: 600; }
.pl-link__try { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
@media (max-width: 560px) { .pl-link__box { flex-direction: column; align-items: stretch; } }
</style>
