<template>
  <div ref="root" class="tpick">
    <div class="tpick__box" :class="{ 'is-disabled': disabled }">
      <Link2 :size="15" class="tpick__icon" aria-hidden="true" />
      <input
        :id="inputId"
        ref="input"
        v-model="query"
        class="tpick__input"
        type="text"
        role="combobox"
        autocomplete="off"
        spellcheck="false"
        :placeholder="placeholder"
        :disabled="disabled"
        :aria-label="label"
        aria-autocomplete="list"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-controls="listId"
        :aria-activedescendant="open && active >= 0 ? `${listId}-${active}` : undefined"
        @focus="open = true"
        @click="open = true"
        @keydown="onKey"
        @blur="onBlur"
      >
    </div>
    <ul v-show="open && !disabled" :id="listId" class="tpick__list" role="listbox" :aria-label="label">
      <li
        v-for="(c, i) in matches"
        :id="`${listId}-${i}`"
        :key="c.id"
        role="option"
        class="tpick__option"
        :class="{ 'is-active': i === active }"
        :aria-selected="i === active ? 'true' : 'false'"
        @pointerdown.prevent
        @click="pick(c.id)"
        @pointermove="active = i"
      >
        <span class="tpick__dot" :class="`is-${c.tone || 'none'}`" aria-hidden="true" />
        <span class="tpick__name">{{ c.label }}</span>
        <span v-if="c.label !== c.id" class="tpick__id">{{ c.id }}</span>
      </li>
      <li v-if="!matches.length" class="tpick__none" role="presentation">
        {{ candidates.length ? `No one matches “${query}”.` : 'Everyone is already tied to them.' }}
      </li>
      <li v-else-if="more" class="tpick__none" role="presentation">{{ more }} more — type to narrow down</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Link2 } from 'lucide-vue-next'
import { uid } from '@/ui/uid'

/**
 * "Tie to…": pick someone to tie a player to by typing their name or id. The keyboard and
 * screen-reader way to add a tie (on the network you can also draw it).
 */
export interface TieCandidate { id: string, label: string, tone?: 'ai' | 'on' | 'off' | 'none' }

const props = withDefaults(defineProps<{ candidates: TieCandidate[], label?: string, placeholder?: string, disabled?: boolean }>(), {
  label: 'Tie to someone', placeholder: 'Tie to…', disabled: false,
})
const emit = defineEmits<{ pick: [id: string] }>()

const LIMIT = 60
const inputId = uid('tpick')
const listId = uid('tpick-list')
const root = ref<HTMLElement>()
const input = ref<HTMLInputElement>()
const query = ref('')
const open = ref(false)
const active = ref(0)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.candidates
  return props.candidates.filter(c => c.label.toLowerCase().includes(q) || c.id.toLowerCase().includes(q))
})
const matches = computed(() => filtered.value.slice(0, LIMIT))
const more = computed(() => Math.max(0, filtered.value.length - LIMIT))
watch(query, () => { active.value = 0; open.value = true })
watch(matches, (m) => { if (active.value >= m.length) active.value = Math.max(0, m.length - 1) })

function pick (id: string) {
  emit('pick', id)
  query.value = ''
  open.value = false
  input.value?.focus()
}
function scrollActive () {
  requestAnimationFrame(() => document.getElementById(`${listId}-${active.value}`)?.scrollIntoView({ block: 'nearest' }))
}
function onKey (e: KeyboardEvent) {
  const n = matches.value.length
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!open.value) { open.value = true; return }
    if (n) active.value = (active.value + 1) % n
    scrollActive()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (n) active.value = (active.value - 1 + n) % n
    scrollActive()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const c = matches.value[active.value]
    if (open.value && c) pick(c.id)
  } else if (e.key === 'Escape') {
    if (open.value || query.value) {
      e.stopPropagation()
      e.preventDefault()
      if (open.value) open.value = false
      else query.value = ''
    }
  } else if (e.key === 'Tab') {
    open.value = false
  }
}
function onBlur () {
  // let a click on an option land first
  setTimeout(() => {
    if (!root.value?.contains(document.activeElement)) open.value = false
  }, 120)
}
</script>

<style>
.tpick { position: relative; }
.tpick__box {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 10px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.tpick__box:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.tpick__box.is-disabled { opacity: 0.6; background: var(--surface-sunken); }
.tpick__icon { color: var(--faint); flex: none; }
.tpick__input { flex: 1; min-width: 0; height: 100%; border: 0; outline: 0; background: transparent; padding: 0; font-size: var(--text-sm); }
.tpick__input::placeholder { color: var(--faint); }
.tpick__list {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 4px);
  z-index: 20;
  max-height: 260px;
  overflow: auto;
  margin: 0;
  padding: 4px;
  list-style: none;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-lg);
}
.tpick__option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-xs);
  font-size: var(--text-sm);
  cursor: pointer;
}
.tpick__option.is-active { background: var(--accent-soft); color: var(--accent-soft-text); }
.tpick__dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.tpick__dot.is-on { background: var(--success); }
.tpick__dot.is-off { background: transparent; box-shadow: inset 0 0 0 1.5px var(--muted); }
.tpick__dot.is-ai { background: transparent; box-shadow: inset 0 0 0 2.5px var(--accent); }
.tpick__name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tpick__id { margin-left: auto; color: var(--faint); font-family: var(--font-mono); font-size: 11px; white-space: nowrap; }
.tpick__none { padding: 8px; color: var(--muted); font-size: var(--text-sm); }
</style>
