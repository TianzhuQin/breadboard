<template>
  <div class="ntb" role="toolbar" aria-label="Network tools" aria-orientation="horizontal">
    <!-- what a click does -->
    <div class="ntb__modes" role="radiogroup" aria-label="What a click does" @keydown="onModeKey">
      <button
        v-for="m in MODES"
        :key="m.value"
        ref="modeButtons"
        type="button"
        role="radio"
        class="ntb__mode"
        :class="[`is-${m.value}`, { 'is-on': mode === m.value }]"
        :aria-checked="mode === m.value ? 'true' : 'false'"
        :tabindex="mode === m.value ? 0 : -1"
        :disabled="m.value !== 'view' && !canEdit"
        :title="m.value !== 'view' && !canEdit ? 'Start the session to change ties' : `${m.title} (${m.key})`"
        :aria-keyshortcuts="m.key"
        @click="emit('update:mode', m.value)"
      >
        <component :is="m.icon" :size="15" aria-hidden="true" />
        <span class="ntb__mode-label">{{ m.label }}</span>
        <kbd class="ntb__kbd" aria-hidden="true">{{ m.key }}</kbd>
      </button>
    </div>

    <!-- find someone -->
    <div class="ntb__search" :class="{ 'has-query': !!query, 'is-miss': !!query && !matchCount && !searchError, 'has-error': !!query && !!searchError }" :title="query && searchError ? searchError : undefined">
      <Search :size="15" class="ntb__search-icon" aria-hidden="true" />
      <input
        ref="searchInput"
        :value="query"
        type="search"
        class="ntb__search-input"
        placeholder="Find a player"
        aria-label="Find players: type an id, or a filter such as score>5"
        :aria-describedby="searchStatusId"
        aria-keyshortcuts="/"
        spellcheck="false"
        autocomplete="off"
        @input="emit('update:query', ($event.target as HTMLInputElement).value)"
        @keydown="onSearchKey"
      >
      <span :id="searchStatusId" class="ntb__search-status" aria-live="polite">{{ searchStatus }}</span>
      <template v-if="query && matchCount > 1">
        <button type="button" class="ntb__icon-btn" aria-label="Previous match" title="Previous (Shift+Enter)" @click="emit('step', -1)"><ChevronUp :size="15" /></button>
        <button type="button" class="ntb__icon-btn" aria-label="Next match" title="Next (Enter)" @click="emit('step', 1)"><ChevronDown :size="15" /></button>
      </template>
      <button v-if="query" type="button" class="ntb__icon-btn" aria-label="Clear the search" title="Clear (Esc)" @click="emit('update:query', '')"><X :size="14" /></button>
    </div>

    <!-- how people look -->
    <div class="ntb__display">
      <label class="ntb__field" :title="'Colour people by a property'">
        <Palette :size="15" class="ntb__field-icon" aria-hidden="true" />
        <span class="ntb__field-label">Colour</span>
        <BSelect
          size="sm"
          class="ntb__select"
          :model-value="colorBy"
          :options="colorOptions"
          aria-label="Colour people by"
          @update:model-value="emit('update:colorBy', $event)"
        />
      </label>
      <label class="ntb__field" :title="'Name people by a property'">
        <Tag :size="15" class="ntb__field-icon" aria-hidden="true" />
        <span class="ntb__field-label">Name</span>
        <BSelect
          size="sm"
          class="ntb__select"
          :model-value="labelBy"
          :options="labelOptions"
          aria-label="Name people by"
          @update:model-value="emit('update:labelBy', $event)"
        />
      </label>
      <button
        type="button"
        class="ntb__toggle"
        :class="{ 'is-on': showAllLabels }"
        :aria-pressed="showAllLabels ? 'true' : 'false'"
        title="Show every name, even where they crowd (L)"
        aria-keyshortcuts="L"
        @click="emit('update:showAllLabels', !showAllLabels)"
      >
        <Captions :size="15" aria-hidden="true" />
        <span class="ntb__toggle-label">All names</span>
      </button>
    </div>

    <div class="ntb__end">
      <button
        type="button"
        class="ntb__toggle"
        :class="{ 'is-on': frozen }"
        :aria-pressed="frozen ? 'true' : 'false'"
        :title="frozen ? 'Let the layout move again (F)' : 'Freeze the layout: people stay where they are (F)'"
        aria-keyshortcuts="F"
        @click="emit('update:frozen', !frozen)"
      >
        <component :is="frozen ? Lock : LockOpen" :size="15" aria-hidden="true" />
        <span class="ntb__toggle-label">{{ frozen ? 'Frozen' : 'Freeze' }}</span>
      </button>
      <BMenu :items="menuItems" align="right" :width="290">
        <template #trigger="{ toggle, attrs }">
          <button type="button" class="ntb__icon-btn ntb__more" v-bind="attrs" aria-label="More: layout and export" title="Layout and export" @click="toggle"><Ellipsis :size="17" /></button>
        </template>
      </BMenu>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  MousePointer2, Spline, Scissors, Search, ChevronUp, ChevronDown, X, Palette, Tag, Captions, Lock, LockOpen,
  Ellipsis, RefreshCw, Maximize, CircleDot, Image as ImageIcon, PenTool, Braces, Share2, Keyboard,
} from 'lucide-vue-next'
import { BMenu, BSelect, type MenuItem, type SelectOption } from '@/ui'
import { uid } from '@/ui/uid'
import type { NetworkMode } from '@/network/AdminNetwork.vue'

const props = defineProps<{
  mode: NetworkMode
  canEdit: boolean
  query: string
  matchCount: number
  matchIndex: number
  searchError?: string
  colorBy: string | null
  labelBy: string | null
  colorOptions: SelectOption[]
  labelOptions: SelectOption[]
  showAllLabels: boolean
  sizeByDegree: boolean
  frozen: boolean
  canExport: boolean
}>()

const emit = defineEmits<{
  'update:mode': [mode: NetworkMode]
  'update:query': [q: string]
  'update:colorBy': [key: string | null]
  'update:labelBy': [key: string | null]
  'update:showAllLabels': [on: boolean]
  'update:sizeByDegree': [on: boolean]
  'update:frozen': [on: boolean]
  step: [dir: 1 | -1]
  fit: []
  relayout: []
  export: [kind: 'png' | 'svg' | 'json' | 'graphml']
  shortcuts: []
}>()

const MODES: { value: NetworkMode, label: string, title: string, key: string, icon: any }[] = [
  { value: 'view', label: 'Select', title: 'Select and move people', key: 'V', icon: MousePointer2 },
  { value: 'connect', label: 'Tie', title: 'Add ties: click one person, then another', key: 'C', icon: Spline },
  { value: 'cut', label: 'Cut', title: 'Cut ties: click a tie', key: 'X', icon: Scissors },
]

const searchStatusId = uid('ntb-status')
const searchInput = ref<HTMLInputElement>()
const modeButtons = ref<HTMLButtonElement[]>([])

const searchStatus = computed(() => {
  if (!props.query) return ''
  if (props.searchError) return props.searchError
  if (!props.matchCount) return 'No match'
  if (props.matchCount === 1) return '1 match'
  return props.matchIndex >= 0 ? `${props.matchIndex + 1} of ${props.matchCount}` : `${props.matchCount} matches`
})

function onSearchKey (e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    emit('step', e.shiftKey ? -1 : 1)
  } else if (e.key === 'Escape') {
    if (props.query) {
      e.preventDefault()
      e.stopPropagation()
      emit('update:query', '')
    } else {
      searchInput.value?.blur()
    }
  }
}

function onModeKey (e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return
  e.preventDefault()
  const enabled = MODES.filter(m => m.value === 'view' || props.canEdit)
  let i = enabled.findIndex(m => m.value === props.mode)
  i = (i + (e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 1) + enabled.length) % enabled.length
  emit('update:mode', enabled[i].value)
  const idx = MODES.indexOf(enabled[i])
  requestAnimationFrame(() => modeButtons.value[idx]?.focus())
}

const menuItems = computed<MenuItem[]>(() => [
  { heading: 'Layout' },
  { label: 'Fit everyone in view', icon: Maximize, hint: '0', run: () => emit('fit') },
  { label: 'Lay out again', icon: RefreshCw, hint: 'Frees moved people', run: () => emit('relayout') },
  { label: 'Bigger dots for more ties', icon: CircleDot, active: props.sizeByDegree, run: () => emit('update:sizeByDegree', !props.sizeByDegree) },
  { divider: true },
  { heading: 'Download the network' },
  { label: 'Picture (PNG)', icon: ImageIcon, disabled: !props.canExport, run: () => emit('export', 'png') },
  { label: 'Vector drawing (SVG)', icon: PenTool, disabled: !props.canExport, run: () => emit('export', 'svg') },
  { label: 'Data (JSON)', icon: Braces, disabled: !props.canExport, run: () => emit('export', 'json') },
  { label: 'GraphML', icon: Share2, hint: 'Gephi, igraph, NetworkX', disabled: !props.canExport, run: () => emit('export', 'graphml') },
  { divider: true },
  { label: 'Keyboard shortcuts', icon: Keyboard, hint: '?', run: () => emit('shortcuts') },
])

defineExpose({ focusSearch: () => { searchInput.value?.focus(); searchInput.value?.select() } })
</script>

<style>
.ntb {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  padding: 10px 16px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.ntb__modes {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  background: var(--bg-subtle);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}
.ntb__mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  font-size: var(--text-sm);
  font-weight: 550;
  cursor: pointer;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.ntb__mode:hover:not(:disabled) { color: var(--text); }
.ntb__mode:disabled { opacity: 0.45; cursor: not-allowed; }
.ntb__mode.is-on { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm), 0 0 0 1px var(--border); }
.ntb__mode.is-connect.is-on { color: var(--success-soft-text); }
.ntb__mode.is-cut.is-on { color: var(--danger-soft-text); }
.ntb__kbd {
  display: inline-grid;
  place-items: center;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border: 1px solid var(--border);
  border-radius: 4px;
  font-family: var(--font);
  font-size: 10px;
  font-weight: 600;
  color: var(--faint);
  background: transparent;
  line-height: 1;
}

.ntb__search {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1 1 220px;
  max-width: 340px;
  min-width: 180px;
  height: 34px;
  padding: 0 4px 0 10px;
  background: var(--surface);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.ntb__search:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.ntb__search-icon { flex: none; color: var(--faint); }
.ntb__search-input {
  flex: 1;
  min-width: 40px;
  height: 100%;
  margin-left: 4px;
  border: 0;
  outline: 0;
  background: transparent;
  padding: 0;
  font-size: var(--text-sm);
}
.ntb__search-input::placeholder { color: var(--faint); }
.ntb__search-input::-webkit-search-cancel-button { display: none; }
.ntb__search-status { flex: none; font-size: var(--text-xs); color: var(--muted); white-space: nowrap; font-variant-numeric: tabular-nums; padding: 0 2px; }
.ntb__search.is-miss .ntb__search-status { color: var(--danger); }
.ntb__icon-btn {
  display: grid;
  place-items: center;
  flex: none;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.ntb__icon-btn:hover { background: var(--surface-hover); color: var(--text); }
.ntb__more { width: 32px; height: 32px; border: 1px solid var(--border-strong); background: var(--surface); }

.ntb__display { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ntb__field { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); font-size: var(--text-sm); font-weight: 550; }
.ntb__field-icon { display: none; color: var(--faint); }
.ntb__select { width: 150px; }
.ntb__toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-xs);
  background: var(--surface);
  color: var(--text-soft);
  font-size: var(--text-sm);
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease), border-color var(--duration) var(--ease);
}
.ntb__toggle:hover { background: var(--surface-hover); color: var(--text); }
.ntb__toggle.is-on { background: var(--accent-soft); color: var(--accent-soft-text); border-color: transparent; }

.ntb__end { display: flex; align-items: center; gap: 8px; margin-left: auto; }

@media (max-width: 1480px) {
  .ntb__field-label, .ntb__toggle-label { display: none; }
  .ntb__field-icon { display: block; }
  .ntb__toggle { padding: 0 8px; }
  .ntb__select { width: 136px; }
  .ntb__search { flex-basis: 200px; max-width: 280px; }
}
@media (max-width: 1100px) {
  .ntb__kbd { display: none; }
  .ntb__select { width: 124px; }
}
@media (max-width: 760px) {
  /* two rows: modes, search, freeze, more / colour, name, all names */
  .ntb { padding: 8px 12px; gap: 8px; }
  .ntb__mode-label, .ntb__kbd, .ntb__toggle-label { display: none; }
  /* no room for the match count, but screen readers still hear it (it is the box's description) */
  .ntb__search-status {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    padding: 0;
    overflow: hidden;
    clip: rect(0 0 0 0);
    clip-path: inset(50%);
    white-space: nowrap;
  }
  /* a filter that can't be read still shows: the box turns red */
  .ntb__search.has-error { border-color: var(--danger); }
  .ntb__mode { padding: 0 8px; }
  .ntb__search { flex: 1 1 100px; min-width: 0; max-width: none; padding-left: 8px; }
  .ntb__end { order: 2; margin-left: 0; gap: 6px; }
  .ntb__display { order: 3; flex: 1 1 100%; min-width: 0; flex-wrap: nowrap; }
  .ntb__field { flex: 1; min-width: 0; }
  .ntb__select { width: auto; flex: 1; min-width: 0; }
  .ntb__toggle { padding: 0 8px; }
}
</style>
