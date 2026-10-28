<template>
  <!-- Links built from the console's own address work on this computer only when that address is
       localhost. Says so wherever participant links are shown, with the way to better ones. -->
  <p v-if="local" class="local-note" :class="{ 'is-compact': compact }" role="note">
    <TriangleAlert :size="15" aria-hidden="true" class="local-note__icon" />
    <span>
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
import { isLocalOrigin } from './live'

withDefaults(defineProps<{
  /** How the sentence starts, e.g. "These links use" or "This URL uses". */
  what?: string
  compact?: boolean
}>(), { what: 'These links use', compact: false })

const DOCS = 'https://github.com/TianzhuQin/breadboard/blob/main/docs/install.md#putting-it-on-a-server'
const origin = typeof window !== 'undefined' ? window.location.origin : ''
const local = isLocalOrigin(origin)
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
