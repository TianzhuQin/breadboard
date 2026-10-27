<template>
  <div class="slist">
    <header class="slist__head">
      <div class="slist__titles">
        <h2 class="slist__title">Steps</h2>
        <span v-if="drafts.length" class="slist__count">{{ drafts.length }}</span>
        <BBadge v-if="fileMode" tone="warning" class="slist__ro">Read only</BBadge>
      </div>
      <BMenu v-if="!fileMode" :items="createItems" align="right" :width="248">
        <template #trigger="{ toggle, attrs }">
          <BButton size="sm" variant="primary" :icon="Plus" :icon-right="ChevronDown" v-bind="attrs" @click="toggle">New</BButton>
        </template>
      </BMenu>
    </header>

    <div class="slist__search">
      <BInput v-model="query" size="sm" :icon="Search" placeholder="Search names and code" aria-label="Search steps by name or code" />
    </div>

    <div class="slist__body" @keydown="onListKey">
      <template v-if="loading && !drafts.length">
        <div v-for="i in 5" :key="i" class="slist__skeleton" aria-hidden="true"><span :style="{ width: `${40 + (i * 17) % 45}%` }" /></div>
        <p class="sr-only" role="status">Loading the steps…</p>
      </template>

      <template v-else>
        <section v-if="pinned.length || missing.length" class="slist__group" aria-labelledby="slist-builtin">
          <h3 id="slist-builtin" class="slist__group-title">Built in</h3>
          <ul class="slist__items">
            <li v-for="d in pinned" :key="d.uid">
              <StepItem :d="d" :active="d.uid === selected" :hint="builtinOf(d.name)?.when" :code-match="codeOnly(d)" @select="emit('select', d.uid)" />
            </li>
            <li v-for="b in missing" :key="b.name" class="slist__missing">
              <span class="slist__missing-text">
                <span class="slist__missing-name">{{ b.name }}</span>
                <span class="slist__missing-hint">Not in this experiment · {{ b.when.toLowerCase() }}</span>
              </span>
              <BButton v-if="!fileMode" size="sm" variant="ghost" :icon="Plus" :aria-label="`Add ${b.name}`" @click="emit('createBuiltin', b)">Add</BButton>
            </li>
          </ul>
        </section>

        <section class="slist__group" aria-labelledby="slist-own">
          <h3 id="slist-own" class="slist__group-title">Your steps</h3>
          <ul v-if="others.length" class="slist__items">
            <li v-for="d in others" :key="d.uid">
              <StepItem :d="d" :active="d.uid === selected" :code-match="codeOnly(d)" @select="emit('select', d.uid)" />
            </li>
          </ul>
          <p v-else-if="q" class="slist__none">No step matches “{{ query.trim() }}”.</p>
          <p v-else class="slist__none">
            {{ fileMode ? 'No other steps in the files.' : 'Add steps for each part of your experiment: rounds, questions, payoffs.' }}
          </p>
        </section>
      </template>
    </div>

    <footer v-if="unsaved.length" class="slist__foot">
      <p class="slist__foot-text" role="status">
        <span class="slist__dirty-dot" aria-hidden="true" />
        {{ unsaved.length }} unsaved
      </p>
      <BButton v-if="unsaved.length > 1" size="sm" :icon="Save" :loading="savingAll" :title="`Save every changed step (${modKey}+Shift+S)`" @click="emit('saveAll')">Save all</BButton>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, FileCode, FilePlus2, Plus, Save, Search, Zap } from 'lucide-vue-next'
import { BBadge, BButton, BInput, BMenu, type MenuItem } from '@/ui'
import StepItem from './StepItem.vue'
import { BUILTINS, TEMPLATES, builtinOf, isDirty, sortDrafts, type Builtin, type StepDraft, type TemplateKind } from './stepsModel'

const props = defineProps<{
  drafts: StepDraft[]
  selected: string | null
  fileMode: boolean
  loading: boolean
  savingAll: boolean
  modKey: string
}>()
const emit = defineEmits<{
  select: [uid: string]
  create: [kind: TemplateKind]
  createBuiltin: [b: Builtin]
  saveAll: []
}>()

const query = ref('')
const q = computed(() => query.value.trim().toLowerCase())
function matches (d: StepDraft) {
  return !q.value || d.name.toLowerCase().includes(q.value) || d.source.toLowerCase().includes(q.value)
}
/** Found by its code, not its name: say so in the list. */
function codeOnly (d: StepDraft) {
  return !!q.value && !d.name.toLowerCase().includes(q.value)
}

const sorted = computed(() => sortDrafts(props.drafts))
const pinned = computed(() => sorted.value.filter(d => builtinOf(d.name) && matches(d)))
const others = computed(() => sorted.value.filter(d => !builtinOf(d.name) && matches(d)))
const missing = computed(() => q.value
  ? []
  : BUILTINS.filter(b => !props.drafts.some(d => builtinOf(d.name) === b)))
const unsaved = computed(() => props.fileMode ? [] : props.drafts.filter(isDirty))

const icons: Record<TemplateKind, any> = { sync: FileCode, async: Zap, empty: FilePlus2 }
const createItems = computed<MenuItem[]>(() => [
  { heading: 'New step' },
  ...TEMPLATES.map(t => ({ label: t.label, hint: t.hint, icon: icons[t.kind], run: () => emit('create', t.kind) })),
])

// Up/Down move between the steps in the list
function onListKey (e: KeyboardEvent) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const items = Array.from((e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('.sitem'))
  const i = items.indexOf(document.activeElement as HTMLElement)
  if (i < 0) return
  e.preventDefault()
  items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
}
</script>

<style>
.slist { display: flex; flex-direction: column; min-height: 0; height: 100%; background: var(--surface); }
.slist__head { display: flex; align-items: center; gap: 8px; padding: 12px 12px 10px 16px; }
.slist__titles { flex: 1; display: flex; align-items: center; gap: 8px; min-width: 0; }
.slist__title { font-size: var(--text-md); font-weight: 650; }
.slist__count { padding: 0 7px; border-radius: 999px; background: var(--bg-subtle); color: var(--muted); font-size: 11px; font-weight: 600; line-height: 18px; }
.slist__search { padding: 0 12px 10px; border-bottom: 1px solid var(--border); }
.slist__body { flex: 1; min-height: 0; overflow: auto; padding: 4px 0 12px; }
.slist__group { padding-top: 6px; }
.slist__group-title { padding: 10px 16px 5px; font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.slist__items { list-style: none; margin: 0; padding: 0 8px; display: flex; flex-direction: column; gap: 1px; }
.slist__none { padding: 6px 16px; color: var(--muted); font-size: var(--text-sm); line-height: 1.45; }
.slist__missing { display: flex; align-items: center; gap: 8px; padding: 6px 4px 6px 10px; border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); margin: 3px 0; }
.slist__missing-text { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.slist__missing-name { font-size: var(--text-sm); font-weight: 560; color: var(--muted); }
.slist__missing-hint { font-size: var(--text-xs); color: var(--faint); }
.slist__skeleton { padding: 9px 18px; }
.slist__skeleton span { display: block; height: 12px; border-radius: 6px; background: var(--bg-subtle); animation: slist-pulse 1.4s ease-in-out infinite; }
@keyframes slist-pulse { 50% { opacity: 0.5; } }
.slist__foot { display: flex; align-items: center; gap: 8px; padding: 10px 12px 10px 16px; border-top: 1px solid var(--border); background: var(--surface-sunken); }
.slist__foot-text { flex: 1; display: flex; align-items: center; gap: 7px; font-size: var(--text-sm); color: var(--text-soft); }
.slist__dirty-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--warning); }
</style>
