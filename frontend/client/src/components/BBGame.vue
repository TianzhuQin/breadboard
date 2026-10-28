<template>
  <div class="bb-game">
    <!-- Fade in only: browsers pause animations in background tabs, and a screen waiting for its
         fade-out would stay up until the participant came back to the tab. -->
    <transition name="bb-game-fade">
      <div v-if="loading" key="loading" class="bb-game__loading" role="status">
        <span class="bb-game__spinner" />Connecting…
      </div>

      <div
        v-else
        key="grid"
        class="bb-game__grid"
        :class="{ 'is-waiting': waiting, 'is-single': !waiting && !networkShown, 'is-idle': idle }"
      >
        <!-- Only this column changes when the session starts. The panel next to it stays the same
             element, so a form or choice the participant began during the wait keeps its input. -->
        <transition name="bb-game-fade">
          <div v-if="waiting" key="waiting" class="bb-game__waiting">
            <slot name="waiting" :player="player" :info="player._system.autopilot">
              <BBWaiting :player="player" />
            </slot>
          </div>

          <section v-else-if="networkShown" key="network" class="bb-card bb-game__network" aria-label="Network">
            <header class="bb-game__head">
              <div>
                <div class="bb-game__eyebrow">{{ networkTitle }}</div>
                <div class="bb-game__meta">
                  {{ tieCount }} {{ tieCount === 1 ? 'tie' : 'ties' }}
                  <template v-if="amongCount"> · {{ amongCount }} between your neighbors</template>
                </div>
              </div>
              <div v-if="amongCount" class="bb-game__legend" aria-hidden="true">
                <span><i class="is-own" :class="{ 'is-editable': ownEditable }" />your ties</span>
                <span><i class="is-among" />between others</span>
              </div>
            </header>
            <div class="bb-game__canvas">
              <slot name="network" :player="player" :graph="graph">
                <BBNetwork
                  :player="player"
                  :graph="graph"
                  v-bind="networkProps"
                  v-on="networkListeners"
                />
              </slot>
            </div>
          </section>
        </transition>

        <!-- v-show, not v-if: hiding an empty panel must not throw away its Form and choices.
             The keys keep each one matched to its old instance when the slots appear around it. -->
        <section v-show="panelShown" class="bb-card bb-game__panel">
          <div v-if="hasTimers" key="timers" class="bb-timers">
            <PlayerTimers :player="player" />
          </div>
          <slot v-if="!waiting" name="panel-top" :player="player" />
          <!-- while waiting, BBWaiting shows the experiment's own text itself -->
          <div v-if="!waiting && player.text" class="bb-prose bb-game__text">
            <slot name="text" :text="player.text">
              <div v-html="player.text" />
            </slot>
          </div>
          <!-- Nothing to show yet (or between two screens): never a blank page. -->
          <div v-if="idle" key="idle" class="bb-game__idle" role="status">
            <span class="bb-game__idle-pulse" aria-hidden="true" />
            <h2 class="bb-game__idle-title">{{ hadContent ? 'Please wait' : 'You\'re in' }}</h2>
            <p class="bb-game__idle-text">
              {{ hadContent ? 'The next part will appear here by itself.' : 'The study will begin shortly. This page will change by itself.' }}
              Please keep it open.
            </p>
          </div>
          <PlayerChoices key="choices" :player="player" class="bb-game__choices" />
          <Form key="form" :player="player" />
          <slot v-if="!waiting" name="panel-bottom" :player="player" />
        </section>
      </div>
    </transition>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'

/**
 * A complete participant page: the network on one side, and the experiment's text, timers, choices
 * and forms on the other (stacked on phones, with the experiment's part first). While the autopilot
 * is waiting for people it shows BBWaiting in place of the network, with the experiment's timers,
 * choices and forms under it; that panel is the same one the game uses, so whatever the participant
 * entered there during the wait is still there when the session starts. Put it inside BBMain, which
 * handles the upstream waiting room and the finish screens:
 *
 *   <v-app class="bb-app">
 *     <ConnectionStatus />
 *     <BBMain :player="player"><BBGame :player="player" :graph="graph" /></BBMain>
 *   </v-app>
 *
 * Slots: network, text, panel-top, panel-bottom, and waiting ({ player, info }, replaces the
 * BBWaiting card; info is player._system.autopilot). Props for the network go in `networkProps`.
 */
export default Vue.extend({
  name: 'BBGame',
  props: {
    player: { type: Object, required: true },
    graph: { type: Object, required: true },
    /** Extra props for BBNetwork, e.g. { nodeLabel: n => n.data.name, layout: 'force' }. */
    networkProps: { type: Object, default: () => ({}) },
    /** true / false, or 'auto': show the network once the player has had a tie or may edit ties. */
    network: { type: [Boolean, String], default: 'auto' },
    networkTitle: { type: String, default: 'Your network' },
  },
  data () {
    return { hadTies: false, hadContent: false }
  },
  computed: {
    // Pass clicks on people and ties on only when the page listens for them; a tie that nobody
    // listens to and that can't be edited shouldn't look clickable.
    networkListeners (): Record<string, Function> {
      const on: Record<string, Function> = {}
      if (this.$listeners.nodeClick) on.nodeClick = (n: any, e: Event) => this.$emit('nodeClick', n, e)
      if (this.$listeners.edgeClick) on.edgeClick = (ed: any, e: Event) => this.$emit('edgeClick', ed, e)
      return on
    },
    // Until the server's first update the page only has a placeholder player (DefaultView); every
    // real player has _system.
    loading (): boolean {
      return !this.player || !this.player._system
    },
    waiting (): boolean {
      const ap = this.player && this.player._system && this.player._system.autopilot
      return !!ap && ['waiting', 'starting', 'cancelled'].includes(ap.state)
    },
    edges (): any[] { return (this.graph && this.graph.edges) || [] },
    tieCount (): number {
      const me = this.player.id
      return this.edges.filter(e => e.source.id === me || e.target.id === me).length
    },
    // The participant may cut their own ties: the network draws those in their colour.
    ownEditable (): boolean {
      const e = this.player.edgeEdits
      return !!e && !!e.enabled && !!e.remove && (e.scope === 'own' || e.scope === 'visible')
    },
    amongCount (): number {
      return this.edges.length - this.tieCount
    },
    networkShown (): boolean {
      if (this.network === true || this.network === 'true') return true
      if (this.network === false || this.network === 'false') return false
      // Someone allowed to add ties needs the network before they have any.
      const edits = this.player.edgeEdits
      return this.hadTies || this.edges.length > 0 || !!(edits && edits.enabled)
    },
    hasTimers (): boolean {
      const t = this.player.timers
      return !!t && Object.keys(t).length > 0
    },
    hasChoices (): boolean {
      return !!this.player.choices && this.player.choices.length > 0
    },
    // Form picks its form from the keys of player.forms.
    hasForms (): boolean {
      const f = this.player.forms
      return !!f && Object.keys(f).length > 0
    },
    // Connected, not waiting, and the experiment shows nothing at all (yet).
    idle (): boolean {
      return !this.waiting && !this.networkShown && !this.player.text && !this.hasTimers &&
        !this.hasChoices && !this.hasForms && !this.$scopedSlots['panel-top'] && !this.$scopedSlots['panel-bottom']
    },
    // While waiting the panel holds only the experiment's timers, choices and forms.
    panelShown (): boolean {
      return !this.waiting || this.hasTimers || this.hasChoices || this.hasForms
    },
  },
  watch: {
    'edges.length': {
      immediate: true,
      handler (n: number) { if (n > 0) this.hadTies = true },
    },
    idle: {
      immediate: true,
      handler (idle: boolean) { if (!idle && !this.loading && !this.waiting) this.hadContent = true },
    },
  },
})
</script>

<style>
.bb-game {
  min-height: 100vh;
  padding: 24px;
  box-sizing: border-box;
}
.bb-game__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(340px, 1fr);
  gap: 24px;
  max-width: 1440px;
  margin: 0 auto;
  min-height: calc(100vh - 48px);
}
.bb-game__grid.is-single {
  grid-template-columns: minmax(0, 760px);
  justify-content: center;
  min-height: 0;
  margin-top: 40px;
}
.bb-game__network {
  display: flex;
  flex-direction: column;
  min-height: 520px;
  overflow: hidden;
}
.bb-game__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px 0;
}
.bb-game__eyebrow {
  font-size: 13px;
  font-weight: 650;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #4f46e5;
}
.bb-game__meta {
  font-size: 14px;
  color: #64748b;
  margin-top: 4px;
}
.bb-game__legend {
  display: flex;
  gap: 14px;
  font-size: 13px;
  color: #64748b;
  white-space: nowrap;
}
.bb-game__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.bb-game__legend i {
  display: inline-block;
  width: 18px;
  height: 0;
  border-top: 3px solid #cbd5e1;
  border-radius: 2px;
}
.bb-game__legend i.is-among {
  border-top-color: #64748b;
}
.bb-game__legend i.is-own.is-editable {
  border-top-color: #a5b4fc;
}
.bb-game__canvas {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.bb-game__canvas > .bb-network {
  flex: 1;
}
.bb-game__panel {
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  align-self: start;
  position: sticky;
  top: 24px;
}
.bb-game__panel:empty {
  display: none;
}
.bb-game__grid.is-idle {
  grid-template-columns: minmax(0, 520px);
  margin-top: 12vh;
}
.bb-game__idle {
  text-align: center;
  padding: 28px 8px 20px;
}
.bb-game__idle-pulse {
  display: block;
  width: 14px;
  height: 14px;
  margin: 0 auto 18px;
  border-radius: 50%;
  background: #6366f1;
  box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.45);
  animation: bb-game-pulse 1.8s ease-out infinite;
}
@keyframes bb-game-pulse {
  0% { box-shadow: 0 0 0 0 rgba(99, 102, 241, 0.45); }
  70% { box-shadow: 0 0 0 14px rgba(99, 102, 241, 0); }
  100% { box-shadow: 0 0 0 0 rgba(99, 102, 241, 0); }
}
@media (prefers-reduced-motion: reduce) {
  .bb-game__idle-pulse { animation: none; }
}
.bb-game__idle-title {
  margin: 0 0 8px;
  font-size: 21px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: #0f172a;
}
.bb-game__idle-text {
  margin: 0;
  font-size: 15px;
  line-height: 1.55;
  color: #64748b;
}
/* While waiting: one calm column, the waiting card with the panel under it. */
.bb-game__grid.is-waiting {
  grid-template-columns: minmax(0, 560px);
  justify-content: center;
  min-height: 0;
}
.bb-game__waiting .bb-waiting {
  /* the grid gap spaces it from the panel */
  margin-bottom: 0;
}
.bb-game__grid.is-waiting .bb-game__panel {
  position: static;
  box-shadow: none;
}
.bb-game__loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding-top: 30vh;
  /* on the page background (not a white card), so a shade darker for contrast */
  color: #475569;
  font-size: 15px;
}
.bb-game__spinner {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 3px solid #e0e7ff;
  border-top-color: #4f46e5;
  animation: bb-game-spin 0.9s linear infinite;
}
@keyframes bb-game-spin {
  to { transform: rotate(360deg); }
}
.bb-game-fade-enter-active {
  transition: opacity 250ms ease;
}
.bb-game-fade-enter {
  opacity: 0;
}
.bb-game-fade-leave-active {
  display: none;
}
@media (prefers-reduced-motion: reduce) {
  /* the ring stays, without turning */
  .bb-game__spinner { animation: none; }
}
@media (max-width: 960px) {
  .bb-game { padding: 12px; }
  .bb-game__grid { grid-template-columns: minmax(0, 1fr); gap: 12px; min-height: 0; }
  .bb-game__grid.is-single { margin-top: 12px; }
  .bb-game__network { min-height: 0; }
  .bb-game__canvas .bb-network__stage { min-height: min(68vh, 400px); }
  /* the instructions and choices come before the network */
  .bb-game__panel { position: static; padding: 20px; order: -1; }
  /* but not before the waiting card */
  .bb-game__grid.is-waiting .bb-game__panel { order: 0; }
  .bb-game__legend { display: none; }
}
@media (max-width: 600px) {
  /* lines up with the BBWaiting card */
  .bb-game__grid.is-waiting .bb-game__panel { margin: 0 16px; }
}
</style>
