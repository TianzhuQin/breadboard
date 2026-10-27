<template>
  <div class="ct-langs">
    <span :id="labelId" class="ct-langs__label">Language</span>
    <div
      v-if="all.length"
      ref="list"
      class="ct-langs__tabs"
      role="tablist"
      :aria-labelledby="labelId"
      @keydown="onKey"
    >
      <button
        v-for="l in all"
        :key="langKey(l)"
        type="button"
        role="tab"
        class="ct-lang"
        :class="{ 'is-active': isActive(l), 'is-extra': isExtra(l) }"
        :aria-selected="isActive(l) ? 'true' : 'false'"
        :tabindex="isActive(l) ? 0 : -1"
        :title="tip(l)"
        :aria-label="`${l.name} (${l.code})${srStatus(l)}`"
        @click="s.setActiveLanguage(l)"
      >
        <span class="ct-lang__name">{{ l.name }}</span>
        <span class="ct-lang__code">{{ l.code }}</span>
        <span v-if="changed(l)" class="ct-lang__changed" title="Changed, not saved" />
        <span v-if="missing(l)" class="ct-lang__miss">{{ missing(l) }}</span>
        <Check v-else-if="s.items.value.length" :size="13" class="ct-lang__ok" aria-hidden="true" />
      </button>
    </div>
    <span v-else class="ct-langs__none">No languages yet</span>
    <BButton
      size="sm"
      :variant="all.length ? 'ghost' : 'primary'"
      :icon="all.length ? Languages : Plus"
      @click="emit('manage')"
    >
      {{ all.length ? 'Languages' : 'Add a language' }}
    </BButton>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Check, Languages, Plus } from 'lucide-vue-next'
import { BButton } from '@/ui'
import { uid } from '@/ui/uid'
import type { Language } from '@/store/types'
import { langKey } from './contentModel'
import { useContentState } from './useContent'

const emit = defineEmits<{ manage: [] }>()
const s = useContentState()
const labelId = uid('ct-langs')
const list = ref<HTMLElement>()

const all = computed(() => s.allLanguages.value)
const isActive = (l: Language) => !!s.activeLanguage.value && langKey(s.activeLanguage.value) === langKey(l)
const isExtra = (l: Language) => s.extraLanguages.value.some(x => langKey(x) === langKey(l))
const missing = (l: Language) => (s.lackingByLang.value[langKey(l)] || []).length
/** The selected text has unsaved edits in this language. */
const changed = (l: Language) => !!s.selected.value?.translations[langKey(l)]?.modified

function names (l: Language) {
  const lack = s.lackingByLang.value[langKey(l)] || []
  const shown = lack.slice(0, 5).map(i => `“${i.name}”`).join(', ')
  return lack.length > 5 ? `${shown} and ${lack.length - 5} more` : shown
}
function tip (l: Language) {
  const n = missing(l)
  const head = isExtra(l) ? `${l.name} is not one of this experiment's languages, but some texts have a ${l.name} version. ` : ''
  if (!s.items.value.length) return `${head}${l.name} (${l.code})`
  if (!n) return `${head}Every text has a ${l.name} version.`
  return `${head}${n} ${n === 1 ? 'text has' : 'texts have'} no ${l.name} version: ${names(l)}.`
}
function srStatus (l: Language) {
  const n = missing(l)
  const parts = [n ? `${n} ${n === 1 ? 'text' : 'texts'} missing` : (s.items.value.length ? 'complete' : '')]
  if (changed(l)) parts.push('unsaved changes in this text')
  if (isExtra(l)) parts.push('not one of this experiment’s languages')
  const out = parts.filter(Boolean).join(', ')
  return out ? `, ${out}` : ''
}

function onKey (e: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return
  const langs = all.value
  if (!langs.length) return
  let i = langs.findIndex(isActive)
  if (e.key === 'ArrowLeft') i = (i - 1 + langs.length) % langs.length
  if (e.key === 'ArrowRight') i = (i + 1) % langs.length
  if (e.key === 'Home') i = 0
  if (e.key === 'End') i = langs.length - 1
  e.preventDefault()
  s.setActiveLanguage(langs[i])
  nextTick(() => (list.value?.querySelector('[aria-selected="true"]') as HTMLElement | null)?.focus())
}
</script>

<style>
.ct-langs { display: flex; align-items: center; gap: 10px; min-width: 0; flex: 1; }
.ct-langs__label { font-size: var(--text-xs); font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); flex: none; }
.ct-langs__tabs {
  display: flex;
  gap: 4px;
  min-width: 0;
  overflow-x: auto;
  padding: 3px;
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
  scrollbar-width: none;
}
.ct-langs__none { font-size: var(--text-sm); color: var(--muted); }
.ct-lang {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: var(--text-sm);
  font-weight: 560;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease);
}
.ct-lang:hover { color: var(--text); }
.ct-lang.is-active { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
.ct-lang.is-extra .ct-lang__name { font-style: italic; }
.ct-lang.is-extra:not(.is-active) { opacity: 0.75; }
.ct-lang__code { font-family: var(--font-mono); font-size: 11px; color: var(--faint); }
.ct-lang__miss {
  min-width: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--warning-soft);
  color: var(--warning-soft-text);
  font-size: 11px;
  font-weight: 650;
  line-height: 17px;
  text-align: center;
}
.ct-lang__ok { color: var(--success); }
.ct-lang__changed { width: 6px; height: 6px; border-radius: 50%; background: var(--warning); }
@media (max-width: 640px) {
  .ct-langs__label { display: none; }
}
</style>
