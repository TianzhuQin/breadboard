<template>
  <!-- Not once the participant is done (the experiment set player.finished, or gave them a completion
       code): the researcher opening the next session restarts the engine, and the banner would cover
       their thank-you screen. -->
  <v-alert :value="shown" :color="color" :transition="transition" class="bb-connection">
    <!-- The message to be shown when the client is disconnected. `state` is 'lost' (it is
         reconnecting), 'blocked' (it has never connected: the network may not let it), 'elsewhere'
         (the study is open in another tab or window) or 'ended' (the session is over, and the page
         shows no end of its own). `time` is how long until the next attempt (ms), counting down, and 0
         while one is under way. `tryNow` and `useHere` do what the buttons do. -->
    <slot :time="time" :state="state" :tryNow="tryNow" :useHere="useHere">
      <div class="bb-connection__body">
        <span class="bb-connection__text">{{ text }}</span>
        <button v-if="state === 'elsewhere'" type="button" class="bb-connection__btn" @click="useHere">Use it here</button>
        <button v-else-if="state !== 'ended'" type="button" class="bb-connection__btn" @click="tryNow">Try now</button>
      </div>
    </slot>
  </v-alert>
</template>

<script lang="ts">
  import Vue from 'vue'
  import { CONNECTION_EVENT } from '@human-nature-lab/breadboard-core'

  // On the page while the banner shows: what the page shows may be out of date, so it is dimmed.
  const DISCONNECTED = 'bb-disconnected'

  const TEXTS: Record<string, string> = {
    lost: 'Connection lost. Trying to reconnect… Please keep this page open.',
    blocked: 'This page can’t open its live connection to the study; the network may block it. Please try another network (e.g. mobile data) or browser.',
    elsewhere: 'This study is open in another tab or window.',
    ended: 'This session is over. You can close this page.',
  }

  /**
   * This component will show players a message if they are disconnected from
   * the game due to a network interruption. Breadboard will automatically
   * reconnect if possible; the participant can also try again at once. It also
   * says when reconnecting won't help: the network blocks the connection, the
   * study is open in another tab (which the participant can take back), or the
   * session is over.
   */
  export default Vue.extend({
    name: 'ConnectionStatus',
    props: {
      color: {
        type: String,
        default: 'info'
      },
      transition: {
        type: String,
        default: 'slide-x-transition'
      }
    },
    data () {
      return {
        // Breadboard's `connection` (see ConnectionState in breadboard-core)
        state: 'connecting',
        time: 0,
        finished: false,
        // when the next attempt starts (Date.now()), and the timer counting down to it
        retryAt: 0,
        countdown: null as ReturnType<typeof setInterval> | null,
        listening: false
      }
    },
    computed: {
      shown (): boolean {
        return !this.finished && ['lost', 'blocked', 'elsewhere', 'ended'].includes(this.state)
      },
      text (): string {
        return TEXTS[this.state] || TEXTS.lost
      }
    },
    watch: {
      shown (shown: boolean) {
        document.documentElement.classList.toggle(DISCONNECTED, shown)
      }
    },
    async created () {
      await window.Breadboard.connect()
      this.sync()
      window.Breadboard.on(CONNECTION_EVENT, this.sync)
      window.Breadboard.on('retry', this.onRetry)
      window.Breadboard.on('connect', this.stopCountdown)
      window.Breadboard.on('player', this.onPlayer)
      this.listening = true
    },
    destroyed () {
      // (only what created() added: removing a listener that isn't there removes another one)
      if (this.listening) {
        window.Breadboard.off(CONNECTION_EVENT, this.sync)
        window.Breadboard.off('retry', this.onRetry)
        window.Breadboard.off('connect', this.stopCountdown)
        window.Breadboard.off('player', this.onPlayer)
      }
      this.stopCountdown()
      document.documentElement.classList.remove(DISCONNECTED)
    },
    methods: {
      sync () {
        this.state = window.Breadboard.connection
        // (no more attempts to count down to)
        if (this.state === 'open' || this.state === 'elsewhere' || this.state === 'ended') this.stopCountdown()
      },
      onPlayer (p: any) {
        if (!p || typeof p !== 'object') return
        if ('finished' in p) this.finished = !!p.finished
        // a completion code on the screen is the participant's end too
        const recruitment = p._system && p._system.recruitment
        if (recruitment && recruitment.completed) this.finished = true
      },
      tryNow () {
        window.Breadboard.reconnectNow()
      },
      useHere () {
        window.Breadboard.useHere()
      },
      onRetry (wait: number) {
        this.retryAt = Date.now() + wait
        this.tick()
        if (this.countdown === null) this.countdown = setInterval(this.tick, 1000)
      },
      tick () {
        this.time = Math.max(0, this.retryAt - Date.now())
        if (this.time === 0) this.stopCountdown()
      },
      stopCountdown () {
        if (this.countdown !== null) clearInterval(this.countdown)
        this.countdown = null
        this.time = 0
      }
    }
  })
</script>

<style>
  .bb-connection__body {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
  }
  .bb-connection__text {
    flex: 1 1 240px;
  }
  .bb-connection__btn {
    flex: none;
    padding: 5px 14px;
    border: 1px solid currentColor;
    border-radius: 10px;
    background: transparent;
    color: inherit;
    font: inherit;
    font-weight: 600;
    cursor: pointer;
  }
  .bb-connection__btn:hover {
    background: rgba(255, 255, 255, 0.14);
  }
  .bb-connection__btn:focus-visible {
    outline: 2px solid currentColor;
    outline-offset: 2px;
  }
  /* The timers keep time with the server: while the page can't hear it, their times stop being true. */
  .bb-disconnected .bb-timer {
    opacity: 0.4;
    transition: opacity 0.2s;
  }
  /* Nor is the rest of what the server last sent necessarily still so. */
  .bb-disconnected .bb-game__text,
  .bb-disconnected .bb-game__waiting,
  .bb-disconnected .bb-network__stage {
    opacity: 0.55;
    transition: opacity 0.2s;
  }
</style>
