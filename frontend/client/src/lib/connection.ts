import Vue from 'vue'
import { CONNECTION_EVENT } from '@human-nature-lab/breadboard-core'

/**
 * What the page's live connection lets the controls that send answers (choices, forms, tie
 * changes) do:
 * - 'open': send
 * - 'reconnecting': wait, and say so: an answer given now would wait for the connection, and arrive
 *   late or not at all
 * - 'watching': nothing, and say why: the page is a researcher's preview of a player whose own page
 *   is connected, or of a computer player, which only watches
 * - 'stopped': nothing more on this page (the session is over, or another page has the seat)
 */
export type LinkState = 'open' | 'reconnecting' | 'watching' | 'stopped'

/** The page's Breadboard, when it says how its connection is (not a stand-in, e.g. in a test) */
function breadboard (): any {
  const bb = typeof window !== 'undefined' ? (window as any).Breadboard : null
  return bb && typeof bb.connection === 'string' ? bb : null
}

function stateOf (bb: any): LinkState {
  if (!bb) return 'open'
  switch (bb.connection) {
    case 'open': return 'open'
    case 'watching': return 'watching'
    case 'ended':
    case 'elsewhere': return 'stopped'
    default: return 'reconnecting'
  }
}

/** The link state now: 'open' where no Breadboard says otherwise (e.g. a component shown on its own) */
export function linkState (): LinkState {
  return stateOf(breadboard())
}

/**
 * Whether the page's player is a computer player (g.addAI's _1, _2, …): a researcher's preview of
 * one only watches what the computer does, so its 'watching' says so instead of "their own page is
 * connected"
 */
export function computerPlayer (): boolean {
  const bb = breadboard()
  return !!bb && !!bb.computerPlayer
}

/**
 * The base of components whose controls send answers: `link` follows the page's connection, and
 * `canSend` says whether they may send now. Use it as `LinkAware.extend({ ... })`.
 */
export const LinkAware = Vue.extend({
  data () {
    return {
      link: linkState() as LinkState,
      // stops following the connection; set once following it (removing a listener that isn't
      // there would remove another one)
      unwatchLink: null as null | (() => void),
    }
  },
  computed: {
    canSend (): boolean {
      return this.link === 'open'
    },
  },
  created () {
    const bb = breadboard()
    if (!bb || typeof bb.on !== 'function') return
    const handler = () => { this.link = stateOf(bb) }
    bb.on(CONNECTION_EVENT, handler)
    this.unwatchLink = () => bb.off(CONNECTION_EVENT, handler)
  },
  beforeDestroy () {
    if (this.unwatchLink) this.unwatchLink()
    this.unwatchLink = null
  },
})
