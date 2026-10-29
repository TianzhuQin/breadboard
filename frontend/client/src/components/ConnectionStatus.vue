<template>
  <!-- Not once the participant is done (the experiment set player.finished): the researcher opening
       the next session restarts the engine, and the banner would cover their thank-you screen. Nor
       once the session is over for this page: it no longer reconnects. -->
  <v-alert :value="shown" :color="color" :transition="transition" class="bb-connection">
    <!-- The message to be shown when the client is disconnected. `time` is how long until the next
         attempt (ms), counting down, and 0 while one is under way. -->
    <slot :time="time">
      Connection lost. Trying to reconnect… Please keep this page open.
    </slot>
  </v-alert>
</template>

<script lang="ts">
  import Vue from 'vue'
  import { SocketState } from '@human-nature-lab/breadboard-core'

  // On the page while the banner shows: the timers dim, since they only keep time while connected.
  const DISCONNECTED = 'bb-disconnected'

  /**
   * This component will show players a message if they are disconnected from
   * the game due to a network interruption. Breadboard will automatically
   * reconnect if possible.
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
        isOpen: true,
        time: 0,
        finished: false,
        ended: false,
        // when the next attempt starts (Date.now()), and the timer counting down to it
        retryAt: 0,
        countdown: null as ReturnType<typeof setInterval> | null,
        listening: false
      }
    },
    computed: {
      shown (): boolean {
        return !this.isOpen && !this.finished && !this.ended
      }
    },
    watch: {
      shown (shown: boolean) {
        document.documentElement.classList.toggle(DISCONNECTED, shown)
      }
    },
    async created () {
      const socket = await window.Breadboard.connect()
      // @ts-ignore
      this.socket = socket
      if (socket.state === SocketState.CLOSED) {
        this.isOpen = false
      }
      this.ended = !!window.Breadboard.ended
      window.Breadboard.on('close', this.onClose)
      window.Breadboard.on('retry', this.onRetry)
      window.Breadboard.on('open', this.onOpen)
      window.Breadboard.on('player', this.onPlayer)
      window.Breadboard.on('ended', this.onEnded)
      this.listening = true
    },
    destroyed () {
      // (only what created() added: removing a listener that isn't there removes another one)
      if (this.listening) {
        window.Breadboard.off('close', this.onClose)
        window.Breadboard.off('retry', this.onRetry)
        window.Breadboard.off('open', this.onOpen)
        window.Breadboard.off('player', this.onPlayer)
        window.Breadboard.off('ended', this.onEnded)
      }
      this.stopCountdown()
      document.documentElement.classList.remove(DISCONNECTED)
    },
    methods: {
      onPlayer (p: any) {
        if (p && typeof p === 'object' && 'finished' in p) this.finished = !!p.finished
      },
      onClose () {
        this.isOpen = false
      },
      onOpen () {
        this.isOpen = true
        this.stopCountdown()
      },
      onEnded () {
        this.ended = true
        this.stopCountdown()
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
  /* The timers keep time with the server: while the page can't hear it, their times stop being true. */
  .bb-disconnected .bb-timer {
    opacity: 0.4;
    transition: opacity 0.2s;
  }
</style>
