<template>
  <!-- Shown by the choices and forms while they can't send: they wait for the connection, or the page
       only watches (a researcher's preview of a player whose own page is connected) -->
  <p v-if="text" class="bb-link-note" :class="`bb-link-note--${link}`" role="status">{{ text }}</p>
</template>

<script lang="ts">
  import Vue from 'vue'

  const TEXTS: Record<string, string> = {
    reconnecting: 'Reconnecting… you can answer once the connection is back.',
    // (only a researcher's preview watches)
    watching: 'Watching only: their own page is connected, so you can’t answer for them.',
  }

  /**
   * A short note next to controls that can't send now, saying why. Nothing for the other link
   * states: 'open' needs none, and the banner speaks for 'stopped'.
   */
  export default Vue.extend({
    name: 'LinkNote',
    props: {
      /** The controls' LinkState (lib/connection.ts) */
      link: { type: String, required: true },
    },
    computed: {
      text (): string {
        return TEXTS[this.link] || ''
      }
    }
  })
</script>

<style>
  .bb-link-note {
    margin: 0 0 12px;
    font-size: 14px;
    line-height: 1.45;
    color: #64748b;
  }
  .bb-link-note::before {
    content: '';
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 8px;
    border-radius: 50%;
    background: #f59e0b;
    vertical-align: 1px;
  }
  .bb-link-note--watching::before {
    background: #0ea5e9;
  }
</style>
