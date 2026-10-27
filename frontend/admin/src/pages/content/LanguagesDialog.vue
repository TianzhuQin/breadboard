<template>
  <BDialog
    :open="open"
    title="Languages"
    subtitle="Each text is written once for every language you add here."
    size="md"
    @update:open="emit('update:open', $event)"
  >
    <div class="ct-ld">
      <section class="ct-ld__section" aria-labelledby="ct-ld-own">
        <h3 id="ct-ld-own" class="ct-ld__heading">In this experiment</h3>
        <p v-if="!s.languages.value.length" class="ct-ld__empty">None yet. Add the language your texts are written in below.</p>
        <ul v-else class="ct-ld__rows">
          <li v-for="l in s.languages.value" :key="langKey(l)" class="ct-ld__row" :class="{ 'is-confirming': confirming === langKey(l) }">
            <template v-if="confirming === langKey(l)">
              <div class="ct-ld__confirm" role="group" :aria-label="`Remove ${l.name}?`">
                <p class="ct-ld__confirm-text"><strong>Remove {{ l.name }}?</strong> {{ s.removalConsequence(l) }}</p>
                <div class="ct-ld__confirm-actions">
                  <BButton size="sm" variant="ghost" data-keep @click="confirming = null">Keep {{ l.name }}</BButton>
                  <BButton size="sm" variant="danger" :loading="s.langBusy.value === langKey(l)" @click="remove(l)">Remove {{ l.name }}</BButton>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="ct-ld__lang">
                <span class="ct-ld__name">{{ l.name }}</span>{{ ' ' }}
                <code class="ct-ld__code">{{ l.code }}</code>
              </div>
              <span class="ct-ld__state" :class="missing(l) ? 'is-warn' : 'is-ok'">{{ stateText(l) }}</span>
              <BButton
                size="sm"
                variant="danger-ghost"
                :icon="X"
                :disabled="s.readOnly.value || !!s.langBusy.value"
                :aria-label="`Remove ${l.name}`"
                @click="askRemove(l)"
              >
                Remove
              </BButton>
            </template>
          </li>
        </ul>
        <template v-if="s.extraLanguages.value.length">
          <h4 class="ct-ld__sub">Also used by some texts</h4>
          <ul class="ct-ld__rows">
            <li v-for="l in s.extraLanguages.value" :key="langKey(l)" class="ct-ld__row">
              <div class="ct-ld__lang">
                <span class="ct-ld__name">{{ l.name }}</span>{{ ' ' }}
                <code class="ct-ld__code">{{ l.code }}</code>
              </div>
              <span class="ct-ld__state">{{ writtenCount(l) }} written, not in the experiment</span>
              <BButton
                size="sm"
                :icon="Plus"
                :loading="s.langBusy.value === langKey(l)"
                :disabled="s.readOnly.value || l.id == null || (!!s.langBusy.value && s.langBusy.value !== langKey(l))"
                :aria-label="`Add ${l.name} to the experiment`"
                @click="s.addLanguage(l)"
              >
                Add
              </BButton>
            </li>
          </ul>
        </template>
      </section>

      <section class="ct-ld__section" aria-labelledby="ct-ld-add">
        <h3 id="ct-ld-add" class="ct-ld__heading">Add a language</h3>
        <p v-if="s.readOnly.value" class="ct-ld__empty">File mode is on, so languages can’t be changed here.</p>
        <template v-else>
          <BInput
            ref="search"
            v-model="query"
            size="sm"
            type="search"
            :icon="Search"
            placeholder="Search by name or code, e.g. Spanish or spa"
            aria-label="Search languages"
            autocomplete="off"
            data-autofocus
            @keydown.down.prevent="focusResult(0)"
            @keydown.enter.prevent="addFirst"
          />
          <div v-if="s.catalogLoading.value && !s.catalog.value" class="ct-ld__status" role="status">
            <BSpinner :size="16" /> Loading languages…
          </div>
          <div v-else-if="s.catalogError.value" class="ct-ld__status is-error" role="alert">
            <span>Couldn’t load the list of languages. {{ s.catalogError.value }}</span>
            <BButton size="sm" :icon="RotateCw" @click="s.loadCatalog(true)">Try again</BButton>
          </div>
          <template v-else-if="s.catalog.value">
            <ul v-if="results.length" ref="resultList" class="ct-ld__results" aria-label="Languages you can add" @keydown="onResultKey">
              <li v-for="l in results" :key="langKey(l)">
                <button
                  type="button"
                  class="ct-ld__result"
                  :disabled="!!s.langBusy.value"
                  :aria-label="`Add ${l.name} (${l.code})`"
                  @click="add(l)"
                >
                  <span class="ct-ld__name">{{ l.name }}</span>
                  <code class="ct-ld__code">{{ l.code }}</code>
                  <BSpinner v-if="s.langBusy.value === langKey(l)" :size="14" label="Adding" />
                  <Plus v-else :size="15" class="ct-ld__plus" aria-hidden="true" />
                </button>
              </li>
            </ul>
            <p v-else class="ct-ld__empty">No language matches “{{ query }}”.</p>
            <p v-if="more" class="ct-ld__more">Showing the first {{ LIMIT }} matches. Type more of the name to narrow it down.</p>
          </template>
        </template>
      </section>
      <p class="ct-ld__hint">
        Scripts choose the language with <code>c.setDefaultLanguage("spa")</code>, or ask for one text with
        <code>c.getTranslated("Name", "spa")</code>, using the codes shown here.
      </p>
    </div>
    <template #footer>
      <BButton @click="emit('update:open', false)">Done</BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Plus, RotateCw, Search, X } from 'lucide-vue-next'
import { BButton, BDialog, BInput, BSpinner } from '@/ui'
import type { Language } from '@/store/types'
import { langKey } from './contentModel'
import { useContentState } from './useContent'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()
const s = useContentState()

const LIMIT = 60
const query = ref('')
const resultList = ref<HTMLElement>()
/** The language whose removal is being confirmed (inline: a confirm dialog would open beneath this one). */
const confirming = ref<string | null>(null)

watch(() => props.open, (o) => {
  if (o) {
    query.value = ''
    confirming.value = null
    s.loadCatalog()
  }
}, { immediate: true })

function askRemove (l: Language) {
  confirming.value = langKey(l)
  nextTick(() => (document.querySelector('.ct-ld [data-keep]') as HTMLElement | null)?.focus())
}
async function remove (l: Language) {
  if (await s.removeLanguage(l, { confirmed: true })) confirming.value = null
}

const missing = (l: Language) => (s.lackingByLang.value[langKey(l)] || []).length
function stateText (l: Language) {
  const total = s.items.value.length
  if (!total) return 'No texts yet'
  const n = missing(l)
  if (!n) return total === 1 ? 'The text is translated' : `All ${total} texts translated`
  return `${n} of ${total} missing`
}
function writtenCount (l: Language) {
  return s.items.value.filter(i => (i.translations[langKey(l)]?.saved ?? null) !== null).length
}

const matches = computed(() => {
  // the experiment's languages, and the ones offered under "Also used by some texts"
  const have = new Set(s.allLanguages.value.map(langKey))
  const q = query.value.trim().toLowerCase()
  const list = (s.catalog.value || []).filter(l => !have.has(langKey(l)))
  if (!q) return list
  // exact codes first, then names that start with the query, then the rest
  const scored = list
    .map(l => {
      const name = l.name.toLowerCase()
      const code = (l.code || '').toLowerCase()
      const score = code === q ? 0 : name.startsWith(q) ? 1 : code.startsWith(q) ? 2 : name.includes(q) ? 3 : -1
      return { l, score }
    })
    .filter(x => x.score >= 0)
  return scored.sort((a, b) => a.score - b.score).map(x => x.l)
})
const results = computed(() => matches.value.slice(0, LIMIT))
const more = computed(() => matches.value.length > LIMIT)

async function add (l: Language) {
  // start the next search from the full list
  if (await s.addLanguage(l)) query.value = ''
}
function addFirst () {
  if (results.value.length && !s.langBusy.value) add(results.value[0])
}
function focusResult (i: number) {
  nextTick(() => {
    const btns = resultList.value?.querySelectorAll<HTMLButtonElement>('.ct-ld__result')
    btns?.[Math.max(0, Math.min(i, btns.length - 1))]?.focus()
  })
}
function onResultKey (e: KeyboardEvent) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const btns = Array.from(resultList.value?.querySelectorAll<HTMLButtonElement>('.ct-ld__result') || [])
  const i = btns.indexOf(document.activeElement as HTMLButtonElement)
  e.preventDefault()
  if (e.key === 'ArrowUp' && i <= 0) {
    (document.querySelector('.ct-ld input[type="search"]') as HTMLInputElement | null)?.focus()
    return
  }
  btns[e.key === 'ArrowDown' ? Math.min(i + 1, btns.length - 1) : i - 1]?.focus()
}
</script>

<style>
.ct-ld { display: flex; flex-direction: column; gap: 22px; }
.ct-ld__section { display: flex; flex-direction: column; gap: 10px; }
.ct-ld__heading { font-size: var(--text-sm); font-weight: 650; color: var(--text); }
.ct-ld__sub { margin-top: 6px; font-size: var(--text-xs); font-weight: 650; letter-spacing: 0.05em; text-transform: uppercase; color: var(--faint); }
.ct-ld__rows { list-style: none; margin: 0; padding: 0; border: 1px solid var(--border); border-radius: var(--radius-sm); }
.ct-ld__row { display: flex; align-items: center; gap: 12px; padding: 8px 8px 8px 12px; }
.ct-ld__row + .ct-ld__row { border-top: 1px solid var(--border); }
.ct-ld__row.is-confirming { background: var(--danger-soft); }
.ct-ld__confirm { display: flex; flex-direction: column; gap: 8px; width: 100%; padding: 2px 0; }
.ct-ld__confirm-text { font-size: var(--text-sm); color: var(--text-soft); }
.ct-ld__confirm-text strong { color: var(--danger-soft-text); }
.ct-ld__confirm-actions { display: flex; gap: 6px; justify-content: flex-end; flex-wrap: wrap; }
.ct-ld__lang { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 8px; }
.ct-ld__name { font-weight: 560; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ct-ld__code { font-size: 11px; color: var(--faint); }
.ct-ld__state { font-size: var(--text-xs); color: var(--muted); white-space: nowrap; }
.ct-ld__state.is-ok { color: var(--success-soft-text); }
.ct-ld__state.is-warn { color: var(--warning-soft-text); }
.ct-ld__empty { font-size: var(--text-sm); color: var(--muted); }
.ct-ld__status { display: flex; align-items: center; gap: 10px; font-size: var(--text-sm); color: var(--muted); flex-wrap: wrap; }
.ct-ld__status.is-error { color: var(--danger); }
.ct-ld__results {
  list-style: none;
  margin: 0;
  padding: 4px;
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
}
.ct-ld__result {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.ct-ld__result:hover,
.ct-ld__result:focus-visible { background: var(--surface-hover); outline: 0; }
.ct-ld__result:focus-visible { box-shadow: inset 0 0 0 2px var(--accent-ring); }
.ct-ld__result:disabled { cursor: progress; opacity: 0.7; }
.ct-ld__result .ct-ld__name { flex: 1; }
.ct-ld__plus { color: var(--muted); }
.ct-ld__more { font-size: var(--text-xs); color: var(--muted); }
.ct-ld__hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.6; }
.ct-ld__hint code { padding: 1px 5px; border-radius: 5px; background: var(--bg-subtle); color: var(--text-soft); }
@media (max-width: 520px) {
  .ct-ld__row { flex-wrap: wrap; }
  .ct-ld__state { order: 3; width: 100%; }
}
</style>
