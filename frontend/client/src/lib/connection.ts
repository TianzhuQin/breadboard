import Vue from 'vue'
import { CONNECTION_EVENT } from '@human-nature-lab/breadboard-core'

/**
 * What the page's live connection lets the controls that send answers (choices, forms, tie
 * changes) do:
 * - 'open': send
 * - 'reconnecting': wait, and say so: an answer given now would wait for the connection, and arrive
 *   late or not at all
 * - 'stopped': nothing more on this page (the session is over, or the study is open in another tab)
 */
export type LinkState = 'open' | 'reconnecting' | 'stopped'

/** The page's Breadboard, when it says how its connection is (not a stand-in, e.g. in a test) */
function breadboard (): any {
  const bb = typeof window !== 'undefined' ? (window as any).Breadboard : null
  return bb && typeof bb.connection === 'string' ? bb : null
}

function stateOf (bb: any): LinkState {
  if (!bb) return 'open'
  switch (bb.connection) {
    case 'open': return 'open'
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
