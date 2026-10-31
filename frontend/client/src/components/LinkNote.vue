<template>
  <!-- Shown by the choices and forms while they can't send: they wait for the connection, or the page
       only watches (a researcher's preview of a player whose own page is connected, or of a computer
       player) -->
  <p v-if="text" class="bb-link-note" :class="`bb-link-note--${link}`" role="status">{{ text }}</p>
</template>

<script lang="ts">
  import Vue from 'vue'
  import { computerPlayer } from '../lib/connection'
  import { t } from '../lib/texts'

  const TEXTS: Record<string, () => string> = {
    // (in the language of the session's texts, lib/texts)
    reconnecting: () => t('connection.reconnectingChoices'),
    // (only a researcher's preview watches: in English, as the console)
    watching: () => 'Watching only: their own page is connected, so you can’t answer for them.',
  }
  // A preview of a computer player watches what the computer does.
  const COMPUTER_WATCHING = 'Watching only: the computer plays this seat, so you can’t answer for it.'


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
        if (this.link === 'watching' && computerPlayer()) return COMPUTER_WATCHING
        return TEXTS[this.link] ? TEXTS[this.link]() : ''
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
