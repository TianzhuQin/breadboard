<template>
  <div class="bb-waiting bb-card">
    <svg class="bb-waiting__art" viewBox="-110 -110 220 220" aria-hidden="true">
      <circle class="bb-waiting__ring" r="78" />
      <g>
        <!-- turns around the centre (a CSS rotation would turn around the wrong point in SVG) -->
        <animateTransform v-if="!reducedMotion" attributeName="transform" type="rotate" from="0 0 0" to="360 0 0" dur="24s" repeatCount="indefinite" />
        <circle
          v-for="(d, i) in dots"
          :key="i"
          class="bb-waiting__dot"
          :class="{ 'is-here': d.here }"
          :cx="d.x" :cy="d.y" :r="d.here ? 10 : 7"
        />
      </g>
      <circle class="bb-waiting__core" r="26" />
      <text v-if="state === 'starting'" class="bb-waiting__core-text" text-anchor="middle" dominant-baseline="central">{{ remaining }}</text>
    </svg>

    <h1 class="bb-waiting__title">{{ title }}</h1>

    <template v-if="state === 'waiting'">
      <!-- how many are here (the number in bold), then when the session starts -->
      <p class="bb-waiting__count"><template v-for="(part, i) in countParts"><strong v-if="part.param" :key="i">{{ part.text }}</strong><template v-else>{{ part.text }}</template></template></p>
      <div class="bb-waiting__bar" :aria-label="progressLabel">
        <span :style="{ width: `${progress}%` }" />
      </div>
    </template>

    <div v-if="showText" class="bb-prose bb-waiting__text" v-html="player.text" />

    <p v-if="state !== 'cancelled'" class="bb-waiting__note">{{ keepOpen }}</p>

    <!-- What screen readers hear: only the changes that matter, not every second of the countdown. -->
    <p class="bb-waiting__sr" aria-live="polite" aria-atomic="true">{{ announcement }}</p>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import { t, tParts, sentenceGap } from '../lib/texts'

/**
 * The waiting screen for a session run by the autopilot (groovy/autopilot.groovy), which publishes
 * player._system.autopilot = { state: 'waiting' | 'starting' | 'cancelled', here, min, max, seconds }.
 * The countdown starts again from `seconds` whenever the server sends a new value, as it does for
 * someone who reconnects during the countdown. Screen readers hear the title when the state changes
 * and the last three seconds of the countdown, not every second.
 * BBGame shows it on its own; it can also be used directly in a client HTML. Its words are in the
 * language of the session's texts (lib/texts).
 */
export default Vue.extend({
  name: 'BBWaiting',
  props: {
    player: { type: Object, required: true },
  },
  data () {
    return {
      countdownStart: 0,
      waitStart: Date.now(),
      startsTotal: 0,
      now: Date.now(),
      ticker: 0 as any,
      originalTitle: '',
      announcement: '',
      reducedMotion: typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    }
  },
  computed: {
    info (): any {
      return (this.player._system && this.player._system.autopilot) || {}
    },
    state (): string { return this.info.state || 'waiting' },
    here (): number { return this.info.here || 0 },
    min (): number { return this.info.min || 0 },
    max (): number | null { return this.info.max || null },
    target (): string {
      return this.max && this.max !== this.min ? `${this.min}–${this.max}` : String(this.min)
    },
    maxIsOne (): boolean { return (this.max || this.min) === 1 },
    // "2 people are here. The session starts when 3–8 people are here.", with the 2 in bold
    countParts (): { text: string, param?: string }[] {
      let then: string
      if (!this.enough) {
        then = t('waiting.startsWhen', { target: this.target, n: this.maxIsOne ? 1 : 2 })
      } else {
        const orMax = !!this.max && this.here < this.max
        const timed = this.startsIn != null && this.startsIn > 0
        then = t((timed ? 'waiting.startsIn' : 'waiting.startsShortly') + (orMax ? 'OrMax' : ''), { s: this.startsIn, max: this.max })
      }
      return [...tParts('waiting.here', { n: this.here }), { text: sentenceGap() + then }]
    },
    progressLabel (): string {
      return t('waiting.progress', { here: this.here, target: this.barTarget })
    },
    keepOpen (): string {
      return t('waiting.keepOpen')
    },
    // The minimum is here: the autopilot waits a little longer for more, then starts.
    enough (): boolean { return this.min > 0 && this.here >= this.min },
    // Towards the minimum, then (when there is a maximum) towards the maximum.
    barTarget (): number {
      return this.enough && this.max ? this.max : this.min
    },
    progress (): number {
      // once enough are here and the start is timed, the bar runs to the start
      if (this.enough && this.startsIn != null && this.startsTotal > 0) {
        return Math.min(100, Math.max(0, Math.round(100 * (1 - this.startsIn / this.startsTotal))))
      }
      return this.barTarget ? Math.min(100, Math.round((100 * this.here) / this.barTarget)) : 0
    },
    // Seconds until the game starts unless more people come (sent once enough are here).
    startsIn (): number | null {
      if (typeof this.info.startsIn !== 'number') return null
      return Math.max(0, Math.ceil(this.info.startsIn - (this.now - this.waitStart) / 1000))
    },
    remaining (): number {
      const seconds = this.info.seconds || 0
      return Math.max(0, Math.ceil(seconds - (this.now - this.countdownStart) / 1000))
    },
    title (): string {
      switch (this.state) {
        case 'starting': return this.remaining > 0 ? t('waiting.startingIn', { n: this.remaining }) : t('waiting.starting')
        case 'cancelled': return t('waiting.cancelledTitle')
        default: return this.enough ? t('waiting.soonTitle') : t('waiting.title')
      }
    },
    showText (): boolean {
      return !!this.player.text && (this.state === 'cancelled' || !!this.info.customText)
    },
    dots (): { x: number, y: number, here: boolean }[] {
      const total = Math.min(Math.max(this.max || this.min || 1, this.here), 24)
      const list = []
      for (let i = 0; i < total; i++) {
        const angle = -Math.PI / 2 + (2 * Math.PI * i) / total
        list.push({ x: 78 * Math.cos(angle), y: 78 * Math.sin(angle), here: i < this.here })
      }
      return list
    },
  },
  watch: {
    state: {
      immediate: true,
      handler (state: string) {
        if (state === 'starting') {
          this.restartCountdown()
          // A participant who switched tabs sees it in the tab title.
          if (!this.originalTitle) this.originalTitle = document.title
          document.title = t('waiting.tabStarting', { title: this.originalTitle })
          const n = this.remaining
          this.announcement = n > 0 ? t('waiting.announceStarting', { n }) : this.title
        } else {
          this.announcement = this.title
        }
      },
    },
    'info.startsIn': {
      immediate: true,
      handler (n: any) {
        // same instant for both, or a clock tick in between shows one second more
        this.waitStart = Date.now()
        this.now = this.waitStart
        // the longest wait seen, so the bar fills over the whole of it
        if (typeof n === 'number' && n > this.startsTotal) this.startsTotal = n
        if (n == null) this.startsTotal = 0
      },
    },
    // the heading changed without the state changing ("Starting soon" once enough are here)
    title (heading: string) { if (this.state !== 'starting') this.announcement = heading },
    // Someone who reconnects during the countdown is sent the seconds that are left.
    'info.seconds' () {
      if (this.state === 'starting') this.restartCountdown()
    },
    remaining (n: number, old: number) {
      // Only while counting down: the jump up from 0 when the countdown begins is announced above.
      if (this.state === 'starting' && n < old && n >= 1 && n <= 3) this.announcement = String(n)
    },
  },
  methods: {
    restartCountdown () {
      this.countdownStart = Date.now()
      this.now = this.countdownStart
    },
  },
  created () {
    this.ticker = setInterval(() => { this.now = Date.now() }, 250)
  },
  beforeDestroy () {
    clearInterval(this.ticker)
    if (this.originalTitle) document.title = this.originalTitle
  },
})
</script>

<style>
.bb-waiting {
  max-width: 560px;
  margin: 56px auto;
  padding: 40px 36px 36px;
  text-align: center;
}
.bb-waiting__art {
  width: 180px;
  height: 180px;
  display: block;
  margin: 0 auto 18px;
}
.bb-waiting__ring {
  fill: none;
  stroke: #e2e8f0;
  stroke-width: 2;
  stroke-dasharray: 3 8;
}
/* an empty seat: a light outline; someone who is here: a full dot */
.bb-waiting__dot {
  fill: #ffffff;
  stroke: #cbd5e1;
  stroke-width: 1.5;
  stroke-dasharray: 2.5 2.5;
  transition: fill 400ms ease;
}
.bb-waiting__dot.is-here {
  fill: #6366f1;
  stroke: #ffffff;
  stroke-width: 3;
  stroke-dasharray: none;
}
.bb-waiting__core {
  fill: #4f46e5;
  animation: bb-waiting-pulse 2.4s ease-in-out infinite;
  transform-box: fill-box;
  transform-origin: center;
}
.bb-waiting__core-text {
  fill: #fff;
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.bb-waiting__title {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #0f172a;
  margin: 0 0 10px;
}
.bb-waiting__count {
  font-size: 16px;
  color: #475569;
  margin: 0 0 18px;
}
.bb-waiting__count strong {
  color: #0f172a;
}
.bb-waiting__bar {
  height: 8px;
  border-radius: 999px;
  background: #eef2ff;
  overflow: hidden;
  margin: 0 auto 20px;
  max-width: 360px;
}
.bb-waiting__bar span {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #6366f1, #4f46e5);
  transition: width 500ms ease;
}
.bb-waiting__text {
  text-align: left;
  margin: 8px 0 18px;
}
.bb-waiting__note {
  font-size: 14px;
  color: #64748b;
  margin: 0;
}
.bb-waiting__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}
@keyframes bb-waiting-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}
@media (prefers-reduced-motion: reduce) {
  .bb-waiting__core { animation: none; }
}
@media (max-width: 600px) {
  .bb-waiting { margin: 16px; padding: 28px 20px; }
}
</style>
