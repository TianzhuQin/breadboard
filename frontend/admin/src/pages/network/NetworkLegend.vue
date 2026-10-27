<template>
  <div class="nlg" :class="{ 'is-open': open }">
    <button type="button" class="nlg__head" :aria-expanded="open ? 'true' : 'false'" :aria-controls="bodyId" @click="open = !open">
      <span class="nlg__title">{{ title ? `Colour: ${title}` : 'Legend' }}</span>
      <ChevronDown :size="14" class="nlg__chev" aria-hidden="true" />
    </button>
    <div v-show="open" :id="bodyId" class="nlg__body">
      <template v-if="title && scale && scale.kind === 'numeric' && scale.gradient">
        <div class="nlg__ramp" :style="{ background: `linear-gradient(90deg, ${scale.gradient.join(', ')})` }" aria-hidden="true" />
        <div class="nlg__ramp-labels">
          <span>{{ entries[0]?.label }}</span><span>{{ entries[entries.length - 1]?.label }}</span>
        </div>
        <p class="sr-only">From {{ entries[0]?.label }} (lightest) to {{ entries[entries.length - 1]?.label }} (strongest).</p>
      </template>
      <ul v-else-if="title && entries.length" class="nlg__list" role="list">
        <li v-for="e in entries" :key="e.label" class="nlg__row">
          <span class="nlg__swatch" :style="{ background: e.color }" aria-hidden="true" />
          <span class="nlg__label">{{ e.label }}</span>
          <span v-if="e.count != null" class="nlg__count">{{ e.count }}</span>
        </li>
      </ul>
      <p v-else-if="title" class="nlg__empty">No one has a value for it yet.</p>
      <div v-if="title && scale && scale.missing" class="nlg__row is-missing">
        <span class="nlg__swatch is-missing" aria-hidden="true" />
        <span class="nlg__label">Not set</span>
        <span class="nlg__count">{{ scale.missing }}</span>
      </div>

      <ul class="nlg__symbols" :class="{ 'has-top': !!title, 'is-plain': !!title }" role="list">
        <li class="nlg__row"><span class="nlg__sym is-person" aria-hidden="true" /><span class="nlg__label">Person</span></li>
        <li v-if="symbols.ai" class="nlg__row"><span class="nlg__sym is-ai" aria-hidden="true" /><span class="nlg__label">AI player</span></li>
        <li v-if="symbols.away" class="nlg__row"><span class="nlg__sym is-away" aria-hidden="true" /><span class="nlg__label">Disconnected</span></li>
        <li v-if="symbols.sized" class="nlg__row"><span class="nlg__sym is-size" aria-hidden="true"><i /><i /></span><span class="nlg__label">Bigger = more ties</span></li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ChevronDown } from 'lucide-vue-next'
import { uid } from '@/ui/uid'
import type { ColorScale, LegendEntry } from '@/network/palette'

defineProps<{
  entries: LegendEntry[]
  scale: ColorScale | null
  /** What people are coloured by (null: one colour). */
  title: string | null
  symbols: { ai: boolean, away: boolean, sized: boolean }
}>()

const bodyId = uid('nlg')
// folded on small screens, where the network needs the room
const open = ref(typeof window === 'undefined' || window.innerWidth > 720)
</script>

<style>
.nlg {
  position: absolute;
  left: 14px;
  bottom: 14px;
  z-index: 2;
  width: 220px;
  max-width: calc(100% - 80px);
  max-height: calc(100% - 28px);
  display: flex;
  flex-direction: column;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  font-size: var(--text-sm);
}
.nlg:not(.is-open) { width: auto; }
.nlg__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px 8px 12px;
  border: 0;
  background: transparent;
  color: var(--text);
  font-weight: 650;
  font-size: var(--text-xs);
  letter-spacing: 0.02em;
  cursor: pointer;
  text-align: left;
  border-radius: var(--radius-sm);
}
.nlg__head:hover { background: var(--surface-hover); }
.nlg__title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nlg__chev { flex: none; color: var(--faint); transition: transform var(--duration) var(--ease); transform: rotate(180deg); }
.nlg.is-open .nlg__chev { transform: none; }
.nlg__body { padding: 0 12px 10px; overflow: auto; }
.nlg__list, .nlg__symbols { margin: 0; padding: 0; list-style: none; }
.nlg__symbols.has-top { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); }
.nlg__row { display: flex; align-items: center; gap: 8px; min-height: 22px; color: var(--text-soft); }
.nlg__label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nlg__count { color: var(--muted); font-size: var(--text-xs); font-variant-numeric: tabular-nums; }
.nlg__swatch { flex: none; width: 11px; height: 11px; border-radius: 50%; }
.nlg__swatch.is-missing { background: transparent; box-shadow: inset 0 0 0 1.5px var(--faint); }
.nlg__empty { color: var(--muted); font-size: var(--text-xs); }
.nlg__ramp { height: 10px; border-radius: 5px; margin-top: 4px; }
.nlg__ramp-labels { display: flex; justify-content: space-between; margin-top: 4px; color: var(--muted); font-size: var(--text-xs); font-variant-numeric: tabular-nums; }
.nlg__sym { flex: none; position: relative; width: 11px; height: 11px; border-radius: 50%; }
.nlg__sym.is-person { background: var(--accent); }
.nlg__sym.is-ai { box-shadow: inset 0 0 0 3px var(--accent); }
.nlg__sym.is-away { background: var(--accent); opacity: 0.5; outline: 1.2px dashed var(--muted); outline-offset: 1.5px; }
/* with a colour key above, the shapes are shown in grey: they mean the same in any colour */
.nlg__symbols.is-plain .nlg__sym.is-person, .nlg__symbols.is-plain .nlg__sym.is-away { background: var(--faint); }
.nlg__symbols.is-plain .nlg__sym.is-ai { box-shadow: inset 0 0 0 3px var(--faint); }
.nlg__sym.is-size { display: inline-flex; align-items: center; gap: 2px; width: auto; border-radius: 0; }
.nlg__sym.is-size i { display: block; border-radius: 50%; background: var(--faint); }
.nlg__sym.is-size i:first-child { width: 5px; height: 5px; }
.nlg__sym.is-size i:last-child { width: 10px; height: 10px; }
</style>
