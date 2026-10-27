<template>
  <BPanel flush title="Launch parameters" subtitle="Set when the session was created. Your scripts read them as variables.">
    <p v-if="!rows.length && !missing.length" class="live-params__none">
      {{ defined ? 'This session was created without parameters.' : 'This experiment has no launch parameters.' }}
    </p>
    <div v-if="rows.length" class="live-params__scroll">
      <table class="live-params">
        <caption class="sr-only">Launch parameters of this session</caption>
        <thead>
          <tr>
            <th scope="col">Parameter</th>
            <th scope="col">Value</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.name">
            <th scope="row">
              <span class="live-params__name">{{ r.name }}</span>
              <span v-if="r.description" class="live-params__desc">{{ r.description }}</span>
            </th>
            <td>
              <span class="live-params__value" :class="{ 'is-empty': r.value === '' }">{{ r.value === '' ? 'empty' : r.value }}</span>
              <span v-if="r.differs" class="live-params__tag" :title="`The experiment's default is ${r.defaultVal || 'empty'}`">changed</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="missing.length" class="live-params__missing">
      <Info :size="14" aria-hidden="true" />
      <span>Not set for this session: <span class="mono">{{ missing.join(', ') }}</span>. Scripts that read {{ missing.length === 1 ? 'it' : 'them' }} get nothing.</span>
    </p>
  </BPanel>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Info } from 'lucide-vue-next'
import { BPanel } from '@/ui'
import type { DataItem, Parameter } from '@/store/types'

const props = defineProps<{ data: DataItem[] | null | undefined, parameters: Parameter[] | null | undefined }>()

/** "0.50" and ".5" are the same number; "TRUE" and "true" the same switch. */
function sameValue (type: string, a: string, b: string) {
  const x = a.trim()
  const y = b.trim()
  if (x === y) return true
  if (type === 'Boolean') return (x.toLowerCase() === 'true') === (y.toLowerCase() === 'true')
  if ((type === 'Integer' || type === 'Decimal') && x && y) return Number(x) === Number(y) && Number.isFinite(Number(x))
  return false
}

const defined = computed(() => (props.parameters || []).length > 0)
const rows = computed(() => {
  const defs = new Map((props.parameters || []).map(p => [p.name, p]))
  return (props.data || [])
    .filter(d => d && d.name != null)
    .map((d) => {
      const def = defs.get(d.name)
      const value = d.value == null ? '' : String(d.value)
      const defaultVal = def?.defaultVal == null ? '' : String(def.defaultVal)
      return {
        name: d.name,
        value,
        description: def?.description || '',
        defaultVal,
        differs: !!def && !sameValue(def.type, value, defaultVal),
      }
    })
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
})
// parameters the experiment defines that this session has no value for (e.g. added later)
const missing = computed(() => {
  const have = new Set((props.data || []).map(d => d && d.name))
  return (props.parameters || []).map(p => p.name).filter(n => n && !have.has(n))
})
</script>

<style>
.live-params__none { padding: 16px 18px; color: var(--muted); font-size: var(--text-sm); }
.live-params__scroll { overflow-x: auto; }
.live-params { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.live-params th,
.live-params td { padding: 9px 18px; text-align: left; vertical-align: top; border-bottom: 1px solid var(--border); }
.live-params thead th { padding-top: 10px; padding-bottom: 8px; font-size: 11px; font-weight: 650; letter-spacing: 0.05em; text-transform: uppercase; color: var(--faint); background: var(--surface-sunken); }
.live-params tbody tr:last-child th,
.live-params tbody tr:last-child td { border-bottom: 0; }
.live-params tbody th { font-weight: 400; }
.live-params__name { display: block; font-family: var(--font-mono); font-size: var(--text-xs); font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
.live-params__desc { display: block; margin-top: 2px; font-size: var(--text-xs); color: var(--muted); }
.live-params__value { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text); overflow-wrap: anywhere; }
.live-params__value.is-empty { color: var(--faint); font-style: italic; font-family: var(--font); }
.live-params__tag { margin-left: 6px; padding: 0 6px; border-radius: var(--radius-pill); background: var(--accent-soft); color: var(--accent-soft-text); font-size: 10.5px; font-weight: 600; line-height: 17px; display: inline-block; vertical-align: 1px; }
.live-params__missing { display: flex; gap: 8px; align-items: flex-start; padding: 10px 18px 12px; border-top: 1px solid var(--border); font-size: var(--text-xs); color: var(--muted); }
.live-params__missing svg { flex: none; margin-top: 2px; }
</style>
