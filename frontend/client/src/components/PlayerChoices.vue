<template>
  <v-col v-if="hasChoices" class="player-choices">
    <!-- A click while the connection is down would arrive late, or never: the choices wait for it. -->
    <ReconnectingNote v-if="link === 'reconnecting'" />
    <v-row no-gutters class="choices-container justify-space-between">
      <template v-for="(choice, index) of player.choices">
        <slot name="choice" :choice="choice" :submit="submit" :index="index" :disabled="!canSend">
          <Choice :choice="choice" :key="choice.uid" :disabled="!canSend" @click="submit(choice)" />
        </slot>
      </template>
    </v-row>
  </v-col>
</template>

<script lang="ts">
import { PlayerChoice } from '@human-nature-lab/breadboard-core'
import Choice from './Choice.vue'
import ReconnectingNote from './ReconnectingNote.vue'
import { LinkAware } from '../lib/connection'

/**
 * A container for all of the player choices. They are disabled while the connection is down.
 */
export default LinkAware.extend({
  name: 'PlayerChoices',
  components: { Choice, ReconnectingNote },
  props: {
    player: {
      type: Object,
      required: true
    },
    choiceFilter: {
      type: Function
    }
  },
  data() {
    return {
      choicesAreEnabled: true
    }
  },
  updated() {
    this.choicesAreEnabled = true
    // console.log('player choices updated')
  },
  methods: {
    submit (choice: PlayerChoice) {
      // (a page's own choices, in the slot, may not be disabled)
      if (!this.canSend) return
      if (choice._route === 'group') {
        // Group choices aren't in the platform action map; route to the group handler.
        window.Breadboard.sendGroupChoice(choice.uid, choice.params)
      } else if (choice.params) {
        window.Breadboard.sendChoice(choice.uid, choice.params)
      } else {
        window.Breadboard.sendChoice(choice.uid)
      }
      this.choicesAreEnabled = false
    },
    /**
     * This filter allows to easily replace only certain choices and keep the other choices by default
     */
    useDefaultChoice (choice: { [key: string]: any }): boolean {
      if (!this.$slots.choice && !this.$scopedSlots.choice) {
        return true
      } else if (!this.choiceFilter) {
        return false
      } else {
        return !this.choiceFilter(choice)
      }
    }
  },
  computed: {
    hasChoices (): boolean {
      return !!this.player && !!this.player.choices && !!this.player.choices.length
    }
  }
})
</script>
