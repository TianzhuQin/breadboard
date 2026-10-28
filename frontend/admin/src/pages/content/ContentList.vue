<template>
  <aside class="ct-list" aria-label="Texts">
    <div class="ct-list__head">
      <BInput
        v-model="query"
        size="sm"
        type="search"
        :icon="Search"
        placeholder="Search texts"
        aria-label="Search texts by name or wording"
        autocomplete="off"
        class="ct-list__search"
        @keydown.down.prevent="focusRow(0)"
      />
      <BButton
        size="sm"
        variant="primary"
        :icon="Plus"
        :disabled="s.readOnly.value"
        :title="s.readOnly.value ? 'File mode is on: texts can’t be added here' : 'New text'"
        aria-label="New text"
        @click="emit('create')"
      />
    </div>

    <button
      v-if="lang && lackCount"
      type="button"
      class="ct-list__filter"
      :class="{ 'is-on': onlyMissing }"
      :aria-pressed="onlyMissing ? 'true' : 'false'"
      @click="onlyMissing = !onlyMissing"
    >
      <CircleDashed :size="14" aria-hidden="true" />
      <span>{{ onlyMissing ? 'Showing' : 'Show' }} only texts without {{ lang.name }}</span>
      <span class="ct-list__filter-n">{{ lackCount }}</span>
    </button>

    <ul v-if="shown.length" ref="listEl" class="ct-list__items" @keydown="onKey">
      <li v-for="it in shown" :key="it.key">
        <button
          type="button"
          class="ct-row"
          :class="{ 'is-active': it.key === s.selectedKey.value }"
          :aria-current="it.key === s.selectedKey.value ? 'true' : undefined"
          :data-key="it.key"
          :aria-label="rowLabel(it)"
          @click="pick(it.key)"
          @dblclick="!s.readOnly.value && emit('rename', it.key)"
        >
          <span class="ct-row__top">
            <span class="ct-row__name" :class="{ 'is-untitled': !it.name }">{{ it.name || 'Untitled' }}</span>
            <span class="ct-row__flags">
              <span v-if="s.errors[it.key]" class="ct-row__err" :title="`Last save failed: ${s.errors[it.key]}`">
                <CircleAlert :size="14" aria-hidden="true" />
              </span>
              <BSpinner v-if="s.saving.has(it.key) || s.deleting.has(it.key)" :size="12" :label="s.deleting.has(it.key) ? 'Deleting' : 'Saving'" />
              <span v-else-if="it.isNew" class="ct-row__new">New</span>
              <span v-else-if="it.modified" class="ct-row__dot" title="Unsaved changes" />
            </span>
          </span>
          <span v-if="lang && missingKeys.has(it.key)" class="ct-row__snippet is-missing">
            <CircleDashed :size="12" aria-hidden="true" /> {{ missingLabel(it) }}
          </span>
          <span v-else class="ct-row__snippet">{{ snippet(it) }}</span>
          <span v-if="dupes.has(it.name.trim())" class="ct-row__warn">
            <TriangleAlert :size="12" aria-hidden="true" /> Same name as another text
          </span>
        </button>
      </li>
    </ul>

    <!-- (the editor beside the list explains texts and has the New text button) -->
    <div v-else-if="!s.items.value.length" class="ct-list__empty">
      <p>No texts yet.</p>
    </div>
    <div v-else class="ct-list__empty">
      <p v-if="query">No text matches “{{ query }}”.</p>
      <p v-else>Every text has a {{ lang?.name }} version.</p>
      <BButton size="sm" variant="ghost" @click="clearFilters">Show all texts</BButton>
    </div>

    <footer class="ct-list__foot" aria-live="polite">
      {{ countLabel }}<template v-if="s.dirty.value.length"> · <span class="ct-list__foot-dirty">{{ s.dirty.value.length }} unsaved</span></template>
    </footer>
  </aside>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { CircleAlert, CircleDashed, Plus, Search, TriangleAlert } from 'lucide-vue-next'
import { BButton, BInput, BSpinner } from '@/ui'
import { plainText } from './contentHtml'
import { langKey, statusOf, textOf, type ContentItem } from './contentModel'
import { useContentState } from './useContent'

const emit = defineEmits<{ create: [], rename: [key: string], open: [key: string] }>()
const s = useContentState()

const query = ref('')
const onlyMissing = ref(false)
const listEl = ref<HTMLElement>()

const lang = computed(() => s.activeLanguage.value)
const missingKeys = computed(() => new Set((lang.value ? s.lackingByLang.value[langKey(lang.value)] || [] : []).map(i => i.key)))
const lackCount = computed(() => missingKeys.value.size)
const dupes = computed(() => s.duplicates.value)
watch(lackCount, (n) => { if (!n) onlyMissing.value = false })

// plain text of every translation, for searching by wording
const haystacks = computed(() => {
  const m = new Map<string, string>()
  for (const it of s.items.value) {
    m.set(it.key, `${it.name}\n${Object.values(it.translations).map(t => plainText(t.text)).join('\n')}`.toLocaleLowerCase())
  }
  return m
})

const shown = computed(() => {
  const q = query.value.trim().toLocaleLowerCase()
  return s.items.value.filter(it => {
    if (onlyMissing.value && !missingKeys.value.has(it.key)) return false
    return !q || (haystacks.value.get(it.key) || '').includes(q)
  })
})

const countLabel = computed(() => {
  const total = s.items.value.length
  if (shown.value.length !== total) return `${shown.value.length} of ${total} texts`
  return total === 1 ? '1 text' : `${total} texts`
})

function snippet (it: ContentItem) {
  const text = lang.value ? plainText(textOf(it, lang.value)) : ''
  return text ? (text.length > 90 ? `${text.slice(0, 90)}…` : text) : ' '
}
function missingLabel (it: ContentItem) {
  const l = lang.value!
  const st = statusOf(it, l)
  if (st === 'placeholder') return `${l.name}: old placeholder text`
  if (st === 'empty') return `${l.name} version is empty`
  return `No ${l.name} version`
}

function rowLabel (it: ContentItem) {
  const parts = [it.name || 'Untitled']
  if (s.saving.has(it.key)) parts.push('saving')
  else if (it.isNew) parts.push('new, not saved yet')
  else if (it.modified) parts.push('unsaved changes')
  if (s.errors[it.key]) parts.push('last save failed')
  if (lang.value && missingKeys.value.has(it.key)) parts.push(missingLabel(it))
  if (dupes.value.has(it.name.trim())) parts.push('same name as another text')
  return parts.join(', ')
}

function pick (key: string) {
  s.select(key)
  emit('open', key)
}
function clearFilters () {
  query.value = ''
  onlyMissing.value = false
}

function rows (): HTMLButtonElement[] {
  return Array.from(listEl.value?.querySelectorAll<HTMLButtonElement>('.ct-row') || [])
}
function focusRow (i: number) {
  nextTick(() => rows()[i]?.focus())
}
function onKey (e: KeyboardEvent) {
  const all = rows()
  const i = all.indexOf(document.activeElement as HTMLButtonElement)
  if (i < 0) return
  let next = -1
  if (e.key === 'ArrowDown') next = Math.min(i + 1, all.length - 1)
  else if (e.key === 'ArrowUp') next = Math.max(i - 1, 0)
  else if (e.key === 'Home') next = 0
  else if (e.key === 'End') next = all.length - 1
  else if (e.key === 'F2' && !s.readOnly.value) {
    e.preventDefault()
    const key = all[i].dataset.key
    if (key) emit('rename', key)
    return
  }
  if (next < 0) return
  e.preventDefault()
  all[next].focus()
  const key = all[next].dataset.key
  if (key) s.select(key)
}

/** Keep the selected row in view (e.g. after creating a text). */
watch(() => s.selectedKey.value, (k) => {
  nextTick(() => {
    const el = k ? rows().find(r => r.dataset.key === k) : null
    el?.scrollIntoView?.({ block: 'nearest' })
  })
})
</script>

<style>
.ct-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  border-right: 1px solid var(--border);
  background: var(--surface-sunken);
}
.ct-list__head { display: flex; gap: 8px; padding: 12px 12px 8px; }
.ct-list__search { flex: 1; }
.ct-list__filter {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 12px 6px;
  padding: 6px 9px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  font-size: var(--text-xs);
  font-weight: 550;
  text-align: left;
  cursor: pointer;
}
.ct-list__filter:hover { color: var(--text); border-color: var(--warning); }
.ct-list__filter.is-on { border-style: solid; border-color: var(--warning); background: var(--warning-soft); color: var(--warning-soft-text); }
.ct-list__filter span:nth-child(2) { flex: 1; }
.ct-list__filter-n { padding: 0 6px; border-radius: 999px; background: var(--warning-soft); color: var(--warning-soft-text); font-weight: 650; }
.ct-list__items { list-style: none; margin: 0; padding: 2px 8px 8px; overflow-y: auto; flex: 1; min-height: 0; }
.ct-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  margin-bottom: 2px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: background var(--duration) var(--ease), border-color var(--duration) var(--ease);
}
.ct-row:hover { background: var(--surface-hover); }
.ct-row.is-active { background: var(--surface); border-color: var(--border); box-shadow: var(--shadow-sm); }
.ct-row.is-active .ct-row__name { color: var(--accent-soft-text); }
.ct-row__top { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ct-row__name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 560; font-size: var(--text-md); }
.ct-row__name.is-untitled { color: var(--faint); font-style: italic; }
.ct-row__flags { display: inline-flex; align-items: center; gap: 6px; flex: none; }
.ct-row__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--warning); box-shadow: 0 0 0 3px var(--warning-soft); }
.ct-row__new { padding: 0 6px; border-radius: 999px; background: var(--accent-soft); color: var(--accent-soft-text); font-size: 10.5px; font-weight: 650; line-height: 17px; letter-spacing: 0.02em; }
.ct-row__err { display: inline-flex; color: var(--danger); }
.ct-row__snippet { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: var(--text-xs); min-height: 1.45em; }
.ct-row__snippet.is-missing { display: inline-flex; align-items: center; gap: 5px; color: var(--warning-soft-text); }
.ct-row__warn { display: inline-flex; align-items: center; gap: 5px; color: var(--danger-soft-text); font-size: var(--text-xs); }
.ct-list__empty { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; padding: 16px 20px; color: var(--muted); font-size: var(--text-sm); }
.ct-list__foot { padding: 9px 16px; border-top: 1px solid var(--border); color: var(--muted); font-size: var(--text-xs); }
.ct-list__foot-dirty { color: var(--warning-soft-text); font-weight: 600; }
</style>
