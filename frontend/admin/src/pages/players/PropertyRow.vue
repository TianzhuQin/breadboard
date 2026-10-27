<template>
  <li class="prow" :class="{ 'is-flash': flash }">
    <div class="prow__line" :style="{ '--depth': depth }">
      <button
        v-if="isNested"
        type="button"
        class="prow__key prow__toggle"
        :aria-expanded="open ? 'true' : 'false'"
        @click="open = !open"
      >
        <ChevronRight :size="13" class="prow__chev" :class="{ 'is-open': open }" aria-hidden="true" />
        <span class="prow__name">{{ name }}</span>
      </button>
      <span v-else class="prow__key"><span class="prow__name">{{ name }}</span></span>

      <span class="prow__value" :class="`is-${kind}`">
        <template v-if="isNested">
          <span v-if="!open" class="prow__preview">{{ preview }}</span>
          <span v-else class="prow__count">{{ countText }}</span>
        </template>
        <template v-else>
          <span class="prow__text">{{ shownText }}</span>
          <button v-if="long" type="button" class="prow__more" :aria-expanded="expandedText ? 'true' : 'false'" @click="expandedText = !expandedText">
            {{ expandedText ? 'Show less' : `Show all (${text.length.toLocaleString()} characters)` }}
          </button>
        </template>
      </span>

      <button type="button" class="prow__copy" :aria-label="`Copy ${path}`" title="Copy value" @click="copy"><Copy :size="13" /></button>
    </div>
    <ul v-if="isNested && open" class="prow__children" role="list">
      <PropertyRow
        v-for="([k, v]) in entries"
        :key="k"
        :name="k"
        :value="v"
        :path="`${path}.${k}`"
        :depth="depth + 1"
      />
    </ul>
  </li>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChevronRight, Copy } from 'lucide-vue-next'
import { copyText } from './clipboard'

defineOptions({ name: 'PropertyRow' })
const props = withDefaults(defineProps<{ name: string, value: any, path: string, depth?: number }>(), { depth: 0 })

const LIMIT = 160
const MAX_CHILDREN = 500

const isNested = computed(() => props.value !== null && typeof props.value === 'object' && Object.keys(props.value).length > 0)
const kind = computed(() => {
  const v = props.value
  if (v === null || v === undefined) return 'null'
  if (Array.isArray(v)) return 'array'
  return typeof v
})
const entries = computed<[string, any][]>(() => {
  if (!isNested.value) return []
  const list = Array.isArray(props.value)
    ? props.value.map((v: any, i: number) => [String(i), v] as [string, any])
    : Object.entries(props.value)
  return list.slice(0, MAX_CHILDREN)
})
const countText = computed(() => {
  const n = Array.isArray(props.value) ? props.value.length : Object.keys(props.value || {}).length
  return Array.isArray(props.value) ? `${n} ${n === 1 ? 'item' : 'items'}` : `${n} ${n === 1 ? 'key' : 'keys'}`
})
const preview = computed(() => {
  const s = JSON.stringify(props.value)
  return s.length > 90 ? s.slice(0, 89) + '…' : s
})
const text = computed(() => {
  const v = props.value
  if (v === undefined) return ''
  if (v === null) return 'null'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
})
const long = computed(() => text.value.length > LIMIT)
const expandedText = ref(false)
const shownText = computed(() => long.value && !expandedText.value ? text.value.slice(0, LIMIT) + '…' : (text.value === '' ? '""' : text.value))
// small maps open by themselves at the top; the rest start folded
const open = ref(props.depth === 0 && isNested.value && Object.keys(props.value).length <= 4 && !Array.isArray(props.value))

// a short highlight when the value changes while you watch
const flash = ref(false)
let timer: any = null
watch(() => (typeof props.value === 'object' ? JSON.stringify(props.value) : props.value), (now, before) => {
  if (now === before) return
  flash.value = false
  clearTimeout(timer)
  requestAnimationFrame(() => {
    flash.value = true
    timer = setTimeout(() => { flash.value = false }, 1300)
  })
})

function copy () {
  const v = props.value
  void copyText(typeof v === 'object' && v !== null ? JSON.stringify(v, null, 2) : text.value, `Copied ${props.path}`)
}
</script>

<style>
.prow { list-style: none; border-radius: var(--radius-xs); }
.prow.is-flash > .prow__line { animation: prow-flash 1.3s var(--ease); }
@keyframes prow-flash { 0%, 30% { background: var(--accent-soft); } 100% { background: transparent; } }
.prow__line {
  display: grid;
  grid-template-columns: minmax(96px, 38%) minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 4px 6px 4px calc(22px + var(--depth) * 14px);
  border-radius: var(--radius-xs);
  font-size: var(--text-sm);
  line-height: 1.45;
}
.prow__line:hover { background: var(--surface-hover); }
.prow__key {
  display: flex;
  align-items: center;
  gap: 3px;
  min-width: 0;
  color: var(--muted);
  font-family: var(--font-mono);
  font-size: 12px;
  padding-top: 1px;
}
.prow__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prow__toggle { border: 0; background: transparent; padding: 0; margin-left: -16px; cursor: pointer; text-align: left; color: var(--text-soft); }
.prow__toggle:hover { color: var(--text); }
.prow__chev { flex: none; transition: transform var(--duration) var(--ease); color: var(--faint); }
.prow__chev.is-open { transform: rotate(90deg); }
.prow__value { min-width: 0; color: var(--text); overflow-wrap: anywhere; }
.prow__value.is-number { color: var(--info-soft-text); font-variant-numeric: tabular-nums; }
.prow__value.is-boolean { color: var(--accent-soft-text); }
.prow__value.is-null { color: var(--faint); font-style: italic; }
.prow__preview, .prow__count { color: var(--faint); font-family: var(--font-mono); font-size: 12px; overflow-wrap: anywhere; }
.prow__text { white-space: pre-wrap; }
.prow__more {
  display: inline;
  margin-left: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--accent);
  font-size: var(--text-xs);
  font-weight: 550;
  cursor: pointer;
}
.prow__more:hover { text-decoration: underline; }
.prow__copy {
  display: grid;
  place-items: center;
  width: 24px;
  height: 22px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--faint);
  cursor: pointer;
  opacity: 0;
}
.prow__line:hover .prow__copy, .prow__copy:focus-visible { opacity: 1; }
.prow__copy:hover { background: var(--surface); color: var(--text); }
@media (hover: none) { .prow__copy { opacity: 0.7; } }
.prow__children { margin: 0; padding: 0; }
</style>
