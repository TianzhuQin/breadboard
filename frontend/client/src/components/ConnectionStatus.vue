<template>
  <!-- Not once the participant is done (the experiment set player.finished): the researcher opening
       the next session restarts the engine, and the banner would cover their thank-you screen. -->
  <v-alert :value="!isOpen && !finished" :color="color" :transition="transition" class="bb-connection">
    <!-- The message to be shown when the client is disconnected -->
    <slot :time="time">
      Connection lost. Reconnecting{{ time > 0 ? ` in ${(time / 1000).toFixed(0)} s` : '' }}… Please keep this page open.
    </slot>
  </v-alert>
</template>

<script lang="ts">
  import Vue from 'vue'
  import { SocketState } from '@human-nature-lab/breadboard-core'

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
        finished: false
      }
    },
    async created () {
      const socket = await window.Breadboard.connect()
      // @ts-ignore
      this.socket = socket
      if (socket.state === SocketState.CLOSED) {
        this.isOpen = false
      }
      window.Breadboard.on('close', this.onClose)
      window.Breadboard.on('retry', this.onRetry)
      window.Breadboard.on('open', this.onOpen)
      window.Breadboard.on('player', this.onPlayer)
    },
    destroyed () {
      window.Breadboard.off('close', this.onClose)
      window.Breadboard.off('retry', this.onRetry)
      window.Breadboard.off('open', this.onOpen)
      window.Breadboard.off('player', this.onPlayer)
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
      },
      onRetry (time: number) {
        this.time = time
      } 
    }
  })
</script>

<style lang="sass">
  
</style>