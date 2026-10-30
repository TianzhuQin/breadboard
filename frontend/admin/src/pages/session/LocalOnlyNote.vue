<template>
  <!-- Links built from the console's own address work on this computer only when that address is
       localhost. Says so wherever participant links are shown, with the way to better ones. A link
       Prolific sends people to (`internet`) needs the server's public address: a lab network's
       address won't do either, so it says so for one of those too, and gives no lab-network tip. -->
  <p v-if="internet ? local || lanOnly : local" class="local-note" :class="{ 'is-compact': compact }" role="note">
    <TriangleAlert :size="15" aria-hidden="true" class="local-note__icon" />
    <span v-if="internet">
      {{ what }} <b>{{ host }}</b>, {{ local ? 'which only works on this computer' : 'an address that only works on this network' }}.
      Prolific sends people from the internet, so they need the server’s public address: put Breadboard
      on a server (<a :href="DOCS" target="_blank" rel="noopener noreferrer">putting Breadboard on a server</a>),
      open the console at its address, and copy the URL there.
    </span>
    <span v-else>
      {{ what }} <b>{{ host }}</b>, which only works on this computer.
      <template v-if="lan.length">
        For people on the same network (say, the lab's computers), open the console at
        <template v-for="(a, i) in lan" :key="a"><a :href="a + here">{{ a }}</a>{{ i < lan.length - 1 ? ' or ' : '' }}</template>
        (you sign in again there) and copy the links from there.
      </template>
      People elsewhere need the server's public address:
      <a :href="DOCS" target="_blank" rel="noopener noreferrer">putting Breadboard on a server</a>.
    </span>
  </p>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { TriangleAlert } from 'lucide-vue-next'
import { admin } from '@/store/admin'
import { isLocalOrigin, isPrivateOrigin } from './live'

withDefaults(defineProps<{
  /** How the sentence starts, e.g. "These links use" or "This URL uses". */
  what?: string
  compact?: boolean
  /** A link Prolific sends people to: only the server's public address works for it. */
  internet?: boolean
}>(), { what: 'These links use', compact: false, internet: false })

const DOCS = 'https://github.com/TianzhuQin/breadboard/blob/main/docs/install.md#putting-it-on-a-server'
const origin = typeof window !== 'undefined' ? window.location.origin : ''
const local = isLocalOrigin(origin)
const lanOnly = isPrivateOrigin(origin)
const host = (() => { try { return new URL(origin).host } catch (e) { return origin } })()
const lan = computed(() => admin.lanAddresses || [])
// the same page at the other address
const here = typeof window !== 'undefined' ? `${window.location.pathname}${window.location.hash}` : '/'
</script>

<style>
.local-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: var(--text-sm);
  line-height: 1.45;
}
.local-note.is-compact { padding: 7px 9px; font-size: var(--text-xs); }
.local-note__icon { flex: none; margin-top: 2px; }
.local-note a { color: inherit; font-weight: 600; text-decoration: underline; overflow-wrap: anywhere; }
</style>
