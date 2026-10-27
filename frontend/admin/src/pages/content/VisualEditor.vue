<template>
  <div class="ct-visual" :class="{ 'is-locked': locked, 'is-ready': ready }">
    <div v-if="ready && blocked" class="ct-note ct-note--warn" role="status">
      <ShieldAlert :size="16" class="ct-note__icon" aria-hidden="true" />
      <p class="ct-note__text">
        <strong>Not shown here, for safety.</strong>
        This text has {{ blocked }}, which would run inside the console if the visual editor showed it. Edit it as HTML instead.
      </p>
      <BButton size="sm" :icon="Code2" @click="emit('use-html')">Edit as HTML</BButton>
    </div>
    <div v-else-if="ready && unsafe && !readonly" class="ct-note ct-note--warn" role="status">
      <ShieldAlert :size="16" class="ct-note__icon" aria-hidden="true" />
      <p class="ct-note__text">
        <strong>Shown read-only so nothing is lost.</strong>
        The visual editor would move or drop {{ unsafe }} here. Edit this text as HTML instead.
      </p>
      <BButton size="sm" :icon="Code2" @click="emit('use-html')">Edit as HTML</BButton>
    </div>
    <div class="ct-visual__frame">
      <div ref="host" class="ct-visual__host ct-mce">
        <textarea :id="id" class="ct-visual__ta" tabindex="-1" aria-hidden="true" />
      </div>
      <div v-if="!ready && !error" class="ct-visual__overlay" role="status">
        <BSpinner :size="20" label="Loading the visual editor" />
        <span>Loading the visual editor…</span>
      </div>
      <div v-if="error" class="ct-visual__overlay is-error" role="alert">
        <TriangleAlert :size="22" class="ct-visual__err-icon" aria-hidden="true" />
        <p class="ct-visual__err-title">The visual editor didn’t start</p>
        <p class="ct-visual__err-text">{{ error }} You can still edit this text as HTML.</p>
        <div class="ct-visual__err-actions">
          <BButton size="sm" :icon="RotateCw" @click="retry">Try again</BButton>
          <BButton size="sm" variant="primary" :icon="Code2" @click="emit('use-html')">Edit as HTML</BButton>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Code2, RotateCw, ShieldAlert, TriangleAlert } from 'lucide-vue-next'
import { BButton, BSpinner } from '@/ui'
import { uid } from '@/ui/uid'
import { activeContent, plainText, roundTripLoss } from './contentHtml'
import { loadTinymce, tinymceSettings } from './tinymce'

/**
 * The WYSIWYG editor (TinyMCE 4, as in the old console), with two guarantees the old one lacked:
 * - Showing a text never changes it. TinyMCE tidies markup when it loads a text (and again when it
 *   is clicked), so only real edits (typing, toolbar commands, pastes) are reported, and undoing
 *   back to the start reports the original text exactly, not TinyMCE's tidied copy.
 * - Nothing is silently dropped. When TinyMCE's copy of a text is missing a tag, attribute, word,
 *   {0} placeholder or comment of the original, the text is shown read-only with a way to HTML.
 * - Nothing runs. Its editing frame shares the console's origin, so a text with event handlers or
 *   javascript: links is not loaded into it at all (the HTML editor and the sandboxed preview show it).
 */
const props = defineProps<{
  html: string
  /** Which translation is shown; reported back with every change. */
  docKey: string
  readonly?: boolean
  label: string
  imageList: { title: string, value: string }[]
}>()
const emit = defineEmits<{
  change: [value: string, docKey: string]
  save: []
  'use-html': []
}>()

const id = uid('ct-mce')
const host = ref<HTMLElement>()
const ready = ref(false)
const error = ref<string | null>(null)
const unsafe = ref<string | null>(null)
/** Markup that would run code in the editor's frame; such a text is not loaded at all. */
const blocked = ref<string | null>(null)
const locked = computed(() => !!props.readonly || !!unsafe.value || !!blocked.value)

let editor: any = null
let destroyed = false
let loadedKey = ''
let loadedOriginal = ''
let loadedTidied = ''
let lastReported = ''
let touched = false
let loadingDoc = false
let timer: ReturnType<typeof setTimeout> | null = null
let startTimer: ReturnType<typeof setTimeout> | null = null

// TinyMCE runs these itself; they are not edits
const PASSIVE = new Set(['mceFocus', 'mceAddUndoLevel', 'mceBeginUndoLevel', 'mceEndUndoLevel', 'mceRepaint', 'mceSelectNode', 'mceSelectNodeDepth', 'mceVisualAid', 'mceAutoResize', 'mceResetDesignMode', 'SelectAll', 'Copy'])

async function init () {
  error.value = null
  ready.value = false
  try {
    const tinymce = await loadTinymce()
    if (destroyed) return
    tinymce.get?.(id)?.remove?.()
    tinymce.init(tinymceSettings({
      selector: `#${id}`,
      readonly: false,
      imageList: props.imageList,
      setup (ed: any) {
        editor = ed
        ed.on('init', () => {
          if (destroyed) { removeEditor(); return }
          if (startTimer) { clearTimeout(startTimer); startTimer = null }
          error.value = null
          ready.value = true
          labelBody()
          load(props.html, props.docKey)
        })
        ed.on('input undo redo cut paste drop compositionend', markTouched)
        ed.on('ExecCommand', (e: any) => { if (!PASSIVE.has(e?.command)) markTouched() })
        // inserted by a dialog (image, link, character map)
        ed.on('SetContent', (e: any) => { if (e?.selection && !loadingDoc) markTouched() })
        ed.on('keydown', onKeydown)
        ed.on('change keyup NodeChange SetAttrib focusout', schedule)
        ed.on('blur', check)
      },
    }))
    // TinyMCE 4 reports a theme or plugin that failed to load only in the browser console
    if (startTimer) clearTimeout(startTimer)
    startTimer = setTimeout(() => {
      if (!ready.value && !destroyed) error.value = 'It took too long to start.'
    }, 20000)
  } catch (e: any) {
    if (!destroyed) error.value = e?.message || String(e)
  }
}

function retry () {
  removeEditor()
  init()
}

function labelBody () {
  try { editor?.getBody()?.setAttribute('aria-label', props.label) } catch (e) { /* not ready */ }
}

function load (html: string, key: string) {
  if (!editor || !ready.value) return
  loadingDoc = true
  try {
    blocked.value = activeContent(html || '')
    editor.setContent(blocked.value ? '' : (html || ''))
    editor.undoManager.clear()
    editor.undoManager.add()
    loadedKey = key
    loadedOriginal = html || ''
    lastReported = loadedOriginal
    loadedTidied = editor.getContent()
    touched = false
    unsafe.value = blocked.value ? null : roundTripLoss(loadedOriginal, loadedTidied)
  } finally {
    loadingDoc = false
  }
  applyLock()
}

function markTouched () {
  if (loadingDoc) return
  touched = true
  schedule()
}

function schedule () {
  if (timer) clearTimeout(timer)
  timer = setTimeout(check, 140)
}

/** Report the text if the researcher changed it. */
function check () {
  if (timer) { clearTimeout(timer); timer = null }
  if (!editor || !ready.value || loadingDoc || locked.value) return
  let now: string
  try { now = editor.getContent() } catch (e) { return }
  // tidying never changes the words, so different words mean an edit even if no key event said so
  // (some input methods and assistive tools insert text without keydown/input events)
  if (!touched && plainText(now) !== plainText(loadedTidied)) touched = true
  if (!touched) {
    // TinyMCE tidied the markup on its own (e.g. wrapped loose text in a paragraph): not an edit
    loadedTidied = now
    return
  }
  const value = now === loadedTidied ? loadedOriginal : now
  if (value !== lastReported) {
    lastReported = value
    emit('change', value, loadedKey)
  }
}

function onKeydown (e: KeyboardEvent) {
  const mod = e.metaKey || e.ctrlKey
  const key = (e.key || '').toLowerCase()
  if (mod && !e.altKey && (key === 's' || e.keyCode === 83)) {
    e.preventDefault()
    check()
    emit('save')
    return
  }
  if (!mod && (key.length === 1 || key === 'enter' || key === 'backspace' || key === 'delete' || key === 'tab')) markTouched()
  else if (mod && /^[biuxvyz]$/.test(key)) markTouched()
}

function applyLock () {
  if (!editor || !ready.value) return
  try { editor.getBody().setAttribute('contenteditable', locked.value ? 'false' : 'true') } catch (e) { /* removed */ }
}

function removeEditor () {
  if (timer) { clearTimeout(timer); timer = null }
  try {
    const ed = editor || window.tinymce?.get?.(id)
    ed?.remove?.()
  } catch (e) { /* already gone */ }
  editor = null
  ready.value = false
}

watch(() => [props.docKey, props.html] as const, ([key, html]) => {
  if (!ready.value) return
  if (key !== loadedKey) {
    // finish reporting the text being left before showing the next one
    check()
    load(html, key)
  } else if (html !== lastReported) {
    // changed from outside (copied from another language, discarded, edited as HTML)
    load(html, key)
  }
})
watch(locked, applyLock)
watch(() => props.label, labelBody)

onMounted(() => {
  document.body.classList.add('ct-mce-on')
  init()
})
onBeforeUnmount(() => {
  check()
  destroyed = true
  if (startTimer) clearTimeout(startTimer)
  removeEditor()
  document.body.classList.remove('ct-mce-on')
})

defineExpose({
  /** Report any edit still waiting (before saving). */
  flush: check,
  focus: () => { try { editor?.focus() } catch (e) { /* not ready */ } },
})
</script>

<style>
.ct-visual { display: flex; flex-direction: column; gap: 10px; height: 100%; min-height: 0; }
.ct-visual__frame {
  position: relative;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
}
.ct-visual__frame:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.ct-visual__host { position: absolute; inset: 0; }
.ct-visual__ta { display: none; }
.ct-visual__overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
  background: var(--surface);
  color: var(--muted);
  text-align: center;
}
.ct-visual__overlay:not(.is-error) { flex-direction: row; }
.ct-visual__err-icon { color: var(--warning); }
.ct-visual__err-title { color: var(--text); font-weight: 650; }
.ct-visual__err-text { max-width: 420px; font-size: var(--text-sm); }
.ct-visual__err-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; margin-top: 4px; }

/*
 * TinyMCE 4's "lightgray" skin, re-coloured with the console's tokens (so it follows dark mode)
 * and stretched to fill the pane: the editing area grows with the page instead of being measured
 * once (§3.4.16). Only colours, borders and radii change; TinyMCE measures sizes itself.
 */
.ct-mce .mce-tinymce { height: 100% !important; border: 0 !important; box-shadow: none !important; border-radius: 0 !important; background: var(--surface) !important; }
.ct-mce .mce-tinymce > .mce-container-body { display: flex !important; flex-direction: column; height: 100%; }
/* the page itself stays white in dark mode too: participants always see a light page */
.ct-mce .mce-edit-area { position: relative; flex: 1 1 auto; min-height: 0; border: 0 !important; background: #ffffff; }
.ct-mce .mce-edit-area iframe { position: absolute; inset: 0; width: 100% !important; height: 100% !important; }
.ct-mce .mce-panel { background: var(--surface) !important; background-image: none !important; border-color: var(--border) !important; filter: none !important; }
.ct-mce .mce-toolbar-grp { padding: 5px 6px !important; border-bottom: 1px solid var(--border) !important; }
.ct-visual.is-locked .mce-toolbar-grp { display: none !important; }
.ct-mce .mce-container,
.ct-mce .mce-container *,
.ct-mce .mce-widget,
.ct-mce .mce-widget * { color: var(--text-soft); text-shadow: none !important; }
.ct-mce .mce-btn {
  margin: 1px !important;
  border: 1px solid transparent !important;
  border-radius: var(--radius-xs) !important;
  background: transparent !important;
  background-image: none !important;
  box-shadow: none !important;
  filter: none !important;
}
.ct-mce .mce-btn:hover,
.ct-mce .mce-btn:focus { background: var(--surface-hover) !important; }
.ct-mce .mce-btn.mce-active,
.ct-mce .mce-btn:active { background: var(--accent-soft) !important; }
.ct-mce .mce-btn.mce-active .mce-ico,
.ct-mce .mce-btn.mce-active button { color: var(--accent) !important; }
.ct-mce .mce-btn i { text-shadow: none !important; }
.ct-mce .mce-btn-group:not(:first-child) { border-left: 1px solid var(--border) !important; padding-left: 4px !important; margin-left: 4px !important; }
.ct-mce .mce-ico { color: var(--text-soft) !important; }
.ct-mce .mce-btn.mce-disabled .mce-ico { color: var(--faint) !important; }
.ct-mce .mce-caret { border-top-color: var(--muted) !important; }
.ct-mce .mce-colorbutton:hover .mce-open { border-color: var(--border) !important; }

/* menus, colour pickers and dialogs are attached to <body> while the editor is open */
.ct-mce-on .mce-floatpanel.mce-menu,
.ct-mce-on .mce-floatpanel.mce-popover,
.ct-mce-on .mce-window,
.ct-mce-on .mce-window .mce-panel,
.ct-mce-on .mce-window .mce-foot {
  background: var(--surface-raised) !important;
  background-image: none !important;
  border-color: var(--border) !important;
  filter: none !important;
}
.ct-mce-on .mce-floatpanel.mce-menu { border: 1px solid var(--border) !important; border-radius: var(--radius-sm) !important; box-shadow: var(--shadow-lg) !important; }
.ct-mce-on .mce-floatpanel.mce-popover > .mce-arrow:after { border-bottom-color: var(--surface-raised) !important; }
.ct-mce-on .mce-floatpanel *,
.ct-mce-on .mce-window * { color: var(--text-soft); text-shadow: none !important; }
.ct-mce-on .mce-menu-item .mce-text,
.ct-mce-on .mce-menu-item .mce-ico { color: var(--text) !important; }
.ct-mce-on .mce-menu-item:hover,
.ct-mce-on .mce-menu-item:focus,
.ct-mce-on .mce-menu-item.mce-selected { background: var(--surface-hover) !important; }
.ct-mce-on .mce-menu-item:hover .mce-text,
.ct-mce-on .mce-menu-item:focus .mce-text,
.ct-mce-on .mce-menu-item.mce-selected .mce-text,
.ct-mce-on .mce-menu-item:hover .mce-ico,
.ct-mce-on .mce-menu-item.mce-selected .mce-ico { color: var(--text) !important; }
.ct-mce-on .mce-menu-item.mce-active { border-left-color: var(--accent) !important; }
.ct-mce-on .mce-window { border: 1px solid var(--border) !important; border-radius: var(--radius-lg) !important; box-shadow: var(--shadow-lg) !important; }
.ct-mce-on .mce-window-head { border-bottom-color: var(--border) !important; }
.ct-mce-on .mce-window-head .mce-title { color: var(--text) !important; }
.ct-mce-on .mce-foot { border-top-color: var(--border) !important; }
.ct-mce-on .mce-window .mce-textbox,
.ct-mce-on .mce-window .mce-combobox input,
.ct-mce-on .mce-window .mce-listbox,
.ct-mce-on .mce-window .mce-btn:not(.mce-primary) {
  background: var(--surface) !important;
  background-image: none !important;
  border: 1px solid var(--border-strong) !important;
  color: var(--text) !important;
  box-shadow: none !important;
}
.ct-mce-on .mce-window .mce-btn:not(.mce-primary) button,
.ct-mce-on .mce-window .mce-listbox button,
.ct-mce-on .mce-window .mce-listbox span { color: var(--text) !important; }
.ct-mce-on .mce-window .mce-primary { background: var(--accent) !important; background-image: none !important; border-color: transparent !important; }
.ct-mce-on .mce-window .mce-primary button,
.ct-mce-on .mce-window .mce-primary button i { color: var(--on-accent) !important; text-shadow: none !important; }
.ct-mce-on .mce-window .mce-textbox:focus,
.ct-mce-on .mce-window .mce-combobox input:focus { border-color: var(--accent) !important; }
.ct-mce-on #mce-modal-block.mce-in { background: var(--overlay) !important; opacity: 1 !important; }
</style>
