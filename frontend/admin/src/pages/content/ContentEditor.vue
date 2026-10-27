<template>
  <section ref="root" class="ct-ed" tabindex="-1" :aria-label="item ? `Editing “${item.name || 'Untitled'}”` : 'Editor'">
    <template v-if="item && lang">
      <header class="ct-ed__head">
        <BButton class="ct-ed__back" variant="ghost" size="sm" :icon="ChevronLeft" @click="emit('back')">All texts</BButton>

        <div class="ct-ed__title" :class="{ 'is-renaming': renaming }">
          <form v-if="renaming" class="ct-ed__rename" novalidate @submit.prevent="commitRename">
            <div class="ct-ed__rename-field">
              <BInput
                ref="renameInput"
                v-model="nameDraft"
                size="sm"
                aria-label="New name"
                :aria-invalid="renameError ? 'true' : undefined"
                :aria-describedby="renameMsgId"
                autocomplete="off"
                spellcheck="false"
                @keydown.esc.stop.prevent="cancelRename"
              />
            </div>
            <BButton size="sm" variant="primary" type="submit">Rename</BButton>
            <BButton size="sm" variant="ghost" @click="cancelRename">Cancel</BButton>
            <p v-if="renameError" :id="renameMsgId" class="ct-ed__rename-msg is-error" role="alert">{{ renameError }}</p>
            <p v-else-if="!item.isNew && nameDraft.trim() !== (item.savedName ?? '')" :id="renameMsgId" class="ct-ed__rename-msg">
              Scripts that call <code>c.get("{{ item.savedName }}")</code> need the new name too.
            </p>
          </form>
          <template v-else>
            <h2 class="ct-ed__name" :class="{ 'is-untitled': !item.name }" :title="item.name">{{ item.name || 'Untitled' }}</h2>
            <BButton
              v-if="!s.readOnly.value"
              ref="renameBtn"
              variant="ghost"
              size="sm"
              :icon="Pencil"
              :aria-label="`Rename “${item.name}”`"
              title="Rename (F2 in the list)"
              @click="startRename"
            />
            <BBadge v-if="isSaving" tone="info"><BSpinner :size="11" label="Saving" /> Saving…</BBadge>
            <BBadge v-else-if="item.isNew" tone="accent" dot>New · not saved yet</BBadge>
            <BBadge v-else-if="item.modified" tone="warning" dot>Unsaved changes</BBadge>
          </template>
        </div>

        <div class="ct-ed__tools">
          <BTabs v-model="mode" variant="pill" :tabs="modes" aria-label="How to edit" />
          <BButton
            variant="ghost"
            size="sm"
            :icon="preview ? PanelRightClose : PanelRightOpen"
            :aria-pressed="preview ? 'true' : 'false'"
            title="Show how participants see it"
            @click="preview = !preview"
          >
            Preview
          </BButton>
          <BButton
            v-if="!s.readOnly.value"
            size="sm"
            :icon="Save"
            :loading="isSaving"
            :disabled="!item.modified"
            :title="item.modified ? `Save this text only` : 'No unsaved changes'"
            @click="saveThis"
          >
            Save
          </BButton>
          <BMenu :items="menu" align="right" :width="240">
            <template #trigger="{ toggle, attrs }">
              <BButton v-bind="attrs" variant="ghost" size="sm" :icon="Ellipsis" :aria-label="`More actions for “${item.name}”`" @click="toggle" />
            </template>
          </BMenu>
        </div>
      </header>

      <div class="ct-ed__meta">
        <span class="ct-ed__usage">
          Scripts show it with
          <code>{{ usage }}</code>
          <button type="button" class="ct-ed__copy" :aria-label="`Copy ${usage}`" title="Copy" @click="copyUsage"><Copy :size="13" /></button>
        </span>
        <span v-if="item.renamed" class="ct-ed__was">Was “{{ item.savedName }}” until you save</span>
        <span class="ct-ed__langs" :aria-label="`Languages of ${item.name}`">
          <span
            v-for="l in s.allLanguages.value"
            :key="langKey(l)"
            class="ct-ed__lang"
            :class="`is-${statusOf(item, l)}`"
            :title="langTitle(l)"
          >{{ l.code }}<span class="sr-only">: {{ langTitle(l) }}</span></span>
        </span>
      </div>

      <div v-if="s.errors[item.key]" class="ct-note ct-note--danger" role="alert">
        <CircleAlert :size="16" class="ct-note__icon" aria-hidden="true" />
        <p class="ct-note__text"><strong>Not saved.</strong> {{ s.errors[item.key] }}</p>
        <BButton v-if="!s.readOnly.value" size="sm" :icon="RotateCw" :loading="isSaving" @click="saveThis">Try again</BButton>
      </div>
      <div v-if="isDuplicate" class="ct-note ct-note--warn" role="note">
        <TriangleAlert :size="16" class="ct-note__icon" aria-hidden="true" />
        <p class="ct-note__text">Another text is also called “{{ item.name }}”. Scripts only ever get one of them, so give one a different name.</p>
        <BButton v-if="!s.readOnly.value" size="sm" :icon="Pencil" @click="startRename">Rename</BButton>
      </div>
      <div v-if="isExtra" class="ct-note" role="note">
        <Info :size="16" class="ct-note__icon" aria-hidden="true" />
        <p class="ct-note__text">{{ lang.name }} is not one of this experiment’s languages, but some texts have a {{ lang.name }} version.</p>
        <BButton v-if="!s.readOnly.value && lang.id != null" size="sm" :icon="Plus" :loading="s.langBusy.value === langKey(lang)" @click="s.addLanguage(lang)">Add {{ lang.name }}</BButton>
      </div>
      <div v-if="status !== 'present'" class="ct-note" :class="{ 'ct-note--warn': status === 'placeholder' }" role="note">
        <CircleDashed :size="16" class="ct-note__icon" aria-hidden="true" />
        <p class="ct-note__text">{{ statusText }}</p>
        <template v-if="!s.readOnly.value">
          <BButton v-for="src in copySources.slice(0, 2)" :key="langKey(src)" size="sm" :icon="CopyPlus" @click="s.copyFrom(item, src, lang)">
            Copy from {{ src.name }}
          </BButton>
        </template>
      </div>

      <div class="ct-ed__body">
        <div class="ct-ed__split" :class="{ 'has-preview': preview }">
          <div class="ct-ed__pane">
            <VisualEditor
              v-if="mode === 'visual'"
              ref="visual"
              :html="text"
              :doc-key="docKey"
              :readonly="s.readOnly.value"
              :label="`${item.name || 'Untitled'} in ${lang.name}`"
              :image-list="imageList"
              @change="(v: string, k: string) => s.updateDoc(k, v)"
              @save="emit('save')"
              @use-html="mode = 'html'"
            />
            <BCodeEditor
              v-else
              :key="docKey"
              ref="code"
              :model-value="text"
              language="html"
              fill
              wrap
              :readonly="s.readOnly.value"
              :aria-label="`HTML of ${item.name || 'Untitled'} in ${lang.name}`"
              :placeholder="s.readOnly.value ? '' : `Write the ${lang.name} version here, e.g. <p>Welcome!</p>`"
              @update:model-value="onCode"
            />
          </div>
          <ContentPreview
            v-if="preview"
            :html="text"
            :doc-key="docKey"
            :title="`${item.name || 'Untitled'} in ${lang.name}`"
            :style-css="admin.experiment?.style"
            :empty-text="`No ${lang.name} version yet.`"
          />
        </div>
      </div>
    </template>

    <div v-else-if="!s.items.value.length" class="ct-ed__empty">
      <BEmpty
        :icon="FileText"
        title="No texts yet"
        :text="emptyText"
      >
        <template v-if="!s.readOnly.value" #actions>
          <BButton variant="primary" :icon="Plus" @click="emit('create')">New text</BButton>
        </template>
      </BEmpty>
    </div>
    <div v-else-if="!lang" class="ct-ed__empty">
      <BEmpty
        :icon="Languages"
        title="Add a language first"
        text="Every text is written once per language. Add the language your texts are written in to start editing."
      >
        <template #actions>
          <BButton variant="primary" :icon="Plus" :disabled="s.readOnly.value" @click="emit('languages')">Add a language</BButton>
        </template>
      </BEmpty>
    </div>
    <div v-else class="ct-ed__empty">
      <BEmpty :icon="FileText" title="Pick a text" text="Choose a text in the list to edit it." compact />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ChevronLeft, CircleAlert, CircleDashed, Code2, Copy, CopyPlus, Ellipsis, FileText, Info, Languages,
  PanelRightClose, PanelRightOpen, Pencil, Plus, RotateCw, Save, Trash2, TriangleAlert, Type, Undo2,
} from 'lucide-vue-next'
import { BBadge, BButton, BCodeEditor, BEmpty, BInput, BMenu, BSpinner, BTabs, notify, type MenuItem, type TabItem } from '@/ui'
import { uid } from '@/ui/uid'
import { admin } from '@/store/admin'
import type { Language } from '@/store/types'
import { langKey, statusOf, textOf } from './contentModel'
import { useContentState } from './useContent'
import VisualEditor from './VisualEditor.vue'
import ContentPreview from './ContentPreview.vue'

const emit = defineEmits<{ back: [], save: [], create: [], languages: [] }>()
const s = useContentState()

const item = computed(() => s.selected.value)
const lang = computed(() => s.activeLanguage.value)
const text = computed(() => (item.value && lang.value ? textOf(item.value, lang.value) : ''))
const docKey = computed(() => (item.value && lang.value ? s.docKey(item.value, lang.value) : ''))
const isSaving = computed(() => !!item.value && s.saving.has(item.value.key))
const status = computed(() => (item.value && lang.value ? statusOf(item.value, lang.value) : 'present'))
const isExtra = computed(() => !!lang.value && s.extraLanguages.value.some(l => langKey(l) === langKey(lang.value!)))
const isDuplicate = computed(() => !!item.value && !item.value.isNew && !item.value.renamed && s.duplicates.value.has(item.value.name.trim()))
const usage = computed(() => `c.get("${item.value?.name || 'Name'}")`)
const emptyText = computed(() => s.readOnly.value
  ? 'This experiment’s Content folder has no texts.'
  : 'Texts are what participants read: instructions, consent forms, messages. Write each once per language; your scripts show them with c.get("Name").')

const copySources = computed(() => {
  const it = item.value
  const l = lang.value
  if (!it || !l) return []
  return s.allLanguages.value.filter(x => langKey(x) !== langKey(l) && statusOf(it, x) === 'present')
})
const statusText = computed(() => {
  const l = lang.value?.name || ''
  switch (status.value) {
    case 'missing': return s.readOnly.value ? `This text has no ${l} version.` : `No ${l} version yet. Type below to add one; nothing is stored until you do.`
    case 'empty': return `The ${l} version is empty, so participants would see nothing.`
    case 'placeholder': return `The ${l} version is only the placeholder the old editor saved by mistake. Replace it with the real text.`
    default: return ''
  }
})
function langTitle (l: Language) {
  const it = item.value
  if (!it) return ''
  const st = statusOf(it, l)
  const changed = it.translations[langKey(l)]?.modified ? ' (changed, not saved)' : ''
  if (st === 'present') return `${l.name}: written${changed}`
  if (st === 'missing') return `${l.name}: missing`
  if (st === 'empty') return `${l.name}: empty${changed}`
  return `${l.name}: old placeholder${changed}`
}

const imageList = computed(() => {
  const exp = admin.experiment
  if (!exp) return []
  return (exp.images || []).map(img => ({ title: img.fileName, value: `/images/${exp.id}/${encodeURIComponent(img.fileName)}` }))
})

// ---- editing mode (remembered on this computer) ----
function pref (key: string, fallback: string) {
  try { return localStorage.getItem(key) ?? fallback } catch (e) { return fallback }
}
function setPref (key: string, value: string) {
  try { localStorage.setItem(key, value) } catch (e) { /* private mode */ }
}
const modes: TabItem[] = [
  { value: 'visual', label: 'Visual', icon: Type },
  { value: 'html', label: 'HTML', icon: Code2 },
]
const mode = ref(pref('bb-content-mode', 'visual') === 'html' ? 'html' : 'visual')
const previewFor = ref<Record<string, boolean>>({
  visual: pref('bb-content-preview-visual', '0') === '1',
  html: pref('bb-content-preview-html', '1') === '1',
})
const preview = computed({
  get: () => previewFor.value[mode.value],
  set: (v: boolean) => {
    previewFor.value = { ...previewFor.value, [mode.value]: v }
    setPref(`bb-content-preview-${mode.value}`, v ? '1' : '0')
  },
})
watch(mode, (m) => setPref('bb-content-mode', m))

function onCode (value: string) {
  const it = item.value
  const l = lang.value
  if (it && l) s.updateText(it, l, value)
}

const visual = ref<InstanceType<typeof VisualEditor> | null>(null)
const code = ref<InstanceType<typeof BCodeEditor> | null>(null)

function saveThis () {
  visual.value?.flush()
  if (item.value) s.save([item.value.key])
}

// ---- rename ----
const renaming = ref(false)
const nameDraft = ref('')
const renameError = ref<string | null>(null)
const renameInput = ref<InstanceType<typeof BInput> | null>(null)
const renameMsgId = uid('ct-rename')

function startRename () {
  if (!item.value || s.readOnly.value) return
  nameDraft.value = item.value.name
  renameError.value = null
  renaming.value = true
  nextTick(() => { renameInput.value?.focus(); renameInput.value?.select() })
}
const renameBtn = ref<{ $el: HTMLElement } | null>(null)
function endRename () {
  renaming.value = false
  renameError.value = null
  // back to the button that started it, so keyboard users keep their place
  nextTick(() => renameBtn.value?.$el?.focus?.())
}
function commitRename () {
  if (!item.value) return
  if (nameDraft.value.trim() !== item.value.name) {
    const err = s.rename(item.value, nameDraft.value)
    if (err) { renameError.value = err; return }
  }
  endRename()
}
function cancelRename () {
  endRename()
}
watch(nameDraft, () => { if (renameError.value) renameError.value = null })
watch(() => item.value?.key, () => { renaming.value = false })

async function copyUsage () {
  try {
    await navigator.clipboard.writeText(usage.value)
    notify.success('Copied', usage.value)
  } catch (e) {
    notify.error('Couldn’t copy', 'Your browser blocked the clipboard. Select the text and copy it yourself.')
  }
}

const root = ref<HTMLElement | null>(null)
/**
 * Before a confirm with a type-to-confirm field: the confirm hands focus back to whatever had it,
 * and the Enter that confirms would then "click" the menu button again and reopen the menu.
 */
function parkFocus () {
  root.value?.focus({ preventScroll: true })
}

const menu = computed<MenuItem[]>(() => {
  const it = item.value
  if (!it) return []
  const ro = s.readOnly.value
  return [
    { label: 'Rename', icon: Pencil, hint: 'F2', disabled: ro, run: startRename },
    { label: 'Discard changes', icon: Undo2, disabled: ro || !it.modified || it.isNew, run: () => s.discard(it) },
    { label: `Copy ${usage.value}`, icon: Copy, run: copyUsage },
    { divider: true },
    { label: it.isNew ? 'Throw away this text' : 'Delete text…', icon: Trash2, danger: true, disabled: ro || s.deleting.has(it.key), run: () => { parkFocus(); s.remove(it) } },
  ]
})

defineExpose({
  startRename,
  /** Report edits the visual editor is still holding back (before saving). */
  flush: () => visual.value?.flush(),
  focus: () => (mode.value === 'visual' ? visual.value?.focus() : code.value?.focus()),
})
</script>

<style>
.ct-ed {
  outline: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 0;
  padding: 14px 18px 16px;
  background: var(--surface);
}
.ct-ed__head { display: flex; align-items: center; gap: 10px 14px; flex-wrap: wrap; }
.ct-ed__back { display: none; }
.ct-ed__title { display: flex; align-items: center; gap: 8px; flex: 1 1 260px; min-width: 0; flex-wrap: wrap; }
.ct-ed__title.is-renaming { flex-basis: 100%; }
.ct-ed__name { font-size: var(--text-lg); font-weight: 650; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.ct-ed__name.is-untitled { color: var(--faint); font-style: italic; }
.ct-ed__tools { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ct-ed__rename { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; width: 100%; }
.ct-ed__rename-field { flex: 1 1 200px; max-width: 360px; min-width: 0; }
.ct-ed__rename-msg { width: 100%; font-size: var(--text-xs); color: var(--muted); }
.ct-ed__rename-msg.is-error { color: var(--danger); }
.ct-ed__rename-msg code, .ct-ed__usage code { padding: 1px 5px; border-radius: 5px; background: var(--bg-subtle); color: var(--text-soft); font-size: 12px; }
.ct-ed__meta { display: flex; align-items: center; gap: 6px 16px; flex-wrap: wrap; font-size: var(--text-xs); color: var(--muted); }
.ct-ed__usage { display: inline-flex; align-items: center; gap: 6px; min-width: 0; }
.ct-ed__usage code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 42ch; }
.ct-ed__copy { display: inline-grid; place-items: center; width: 22px; height: 22px; border: 0; border-radius: var(--radius-xs); background: transparent; color: var(--muted); cursor: pointer; }
.ct-ed__copy:hover { background: var(--surface-hover); color: var(--text); }
.ct-ed__was { color: var(--warning-soft-text); }
.ct-ed__langs { display: inline-flex; gap: 4px; margin-left: auto; flex-wrap: wrap; }
.ct-ed__lang { padding: 0 6px; border-radius: 5px; font-family: var(--font-mono); font-size: 11px; line-height: 18px; border: 1px solid var(--border); color: var(--muted); }
.ct-ed__lang.is-present { background: var(--success-soft); border-color: transparent; color: var(--success-soft-text); }
.ct-ed__lang.is-missing { border-style: dashed; color: var(--faint); }
.ct-ed__lang.is-empty, .ct-ed__lang.is-placeholder { background: var(--warning-soft); border-color: transparent; color: var(--warning-soft-text); }
.ct-ed__body { flex: 1; min-height: 0; container-type: inline-size; }
.ct-ed__split { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; height: 100%; min-height: 0; }
.ct-ed__split.has-preview { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); }
.ct-ed__pane { min-height: 0; min-width: 0; height: 100%; }
@container (max-width: 760px) {
  .ct-ed__split.has-preview { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1.2fr) minmax(0, 1fr); }
}
.ct-ed__empty { flex: 1; display: grid; place-items: center; }

/* notes above the editor */
.ct-note {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 8px 10px 8px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
  font-size: var(--text-sm);
  color: var(--text-soft);
}
.ct-note__icon { flex: none; color: var(--muted); }
.ct-note__text { flex: 1 1 260px; min-width: 0; }
.ct-note--warn { background: var(--warning-soft); border-color: color-mix(in srgb, var(--warning) 30%, transparent); color: var(--warning-soft-text); }
.ct-note--warn .ct-note__icon { color: var(--warning); }
.ct-note--danger { background: var(--danger-soft); border-color: color-mix(in srgb, var(--danger) 30%, transparent); color: var(--danger-soft-text); }
.ct-note--danger .ct-note__icon { color: var(--danger); }
</style>
