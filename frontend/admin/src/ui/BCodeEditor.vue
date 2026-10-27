<template>
  <div ref="host" class="b-code" :class="{ 'is-fill': fill }" :style="fill ? undefined : { height: typeof height === 'number' ? `${height}px` : height }" />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorState, Compartment, type Extension } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, placeholder as cmPlaceholder } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search'
import { closeBrackets, closeBracketsKeymap, autocompletion, completionKeymap } from '@codemirror/autocomplete'
import { bracketMatching, indentOnInput, foldGutter, foldKeymap, syntaxHighlighting, HighlightStyle, StreamLanguage } from '@codemirror/language'
import { tags as t } from '@lezer/highlight'
import { javascript } from '@codemirror/lang-javascript'
import { html } from '@codemirror/lang-html'
import { css } from '@codemirror/lang-css'
import { groovy } from '@codemirror/legacy-modes/mode/groovy'

export type CodeLanguage = 'groovy' | 'javascript' | 'html' | 'css' | 'text'

const props = withDefaults(defineProps<{
  modelValue: string
  language?: CodeLanguage
  readonly?: boolean
  height?: number | string
  /** Grow to fill the parent (which must have a height). */
  fill?: boolean
  placeholder?: string
  ariaLabel?: string
  /** Wrap long lines (for prose such as HTML texts) instead of scrolling sideways. */
  wrap?: boolean
  /** Cmd/Ctrl+Enter */
  onRun?: () => void
  /** Cmd/Ctrl+S */
  onSave?: () => void
}>(), { language: 'text', height: 320 })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const host = ref<HTMLElement>()
let view: EditorView | null = null
const langSlot = new Compartment()
const readSlot = new Compartment()
const wrapSlot = new Compartment()

function languageExt (lang: CodeLanguage): Extension {
  switch (lang) {
    case 'groovy': return StreamLanguage.define(groovy)
    case 'javascript': return javascript()
    case 'html': return html()
    case 'css': return css()
    default: return []
  }
}

const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.controlKeyword, t.moduleKeyword, t.operatorKeyword], color: 'var(--code-keyword)' },
  { tag: [t.string, t.special(t.string), t.regexp], color: 'var(--code-string)' },
  { tag: [t.number, t.bool, t.null, t.atom], color: 'var(--code-number)' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: 'var(--code-comment)', fontStyle: 'italic' },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: 'var(--code-function)' },
  { tag: [t.typeName, t.className, t.tagName], color: 'var(--code-type)' },
  { tag: [t.propertyName, t.attributeName], color: 'var(--code-property)' },
  { tag: [t.definition(t.variableName)], color: 'var(--code-def)' },
  { tag: t.meta, color: 'var(--code-comment)' },
  { tag: t.invalid, color: 'var(--danger)' },
])

const theme = EditorView.theme({
  '&': { height: '100%', fontSize: '13px', backgroundColor: 'var(--code-bg)', color: 'var(--text)' },
  '.cm-scroller': { fontFamily: 'var(--font-mono)', lineHeight: '1.6' },
  '.cm-content': { caretColor: 'var(--accent)', padding: '10px 0' },
  '.cm-gutters': { backgroundColor: 'var(--code-bg)', color: 'var(--faint)', border: 'none', paddingRight: '4px' },
  '.cm-activeLine': { backgroundColor: 'var(--code-active)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--code-active)', color: 'var(--text-soft)' },
  '&.cm-focused .cm-cursor': { borderLeftColor: 'var(--accent)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: 'var(--code-selection) !important' },
  '.cm-matchingBracket': { backgroundColor: 'var(--code-selection)', outline: 'none' },
  '.cm-searchMatch': { backgroundColor: 'var(--warning-soft)', outline: '1px solid var(--warning)' },
  '.cm-tooltip': { backgroundColor: 'var(--surface-raised)', border: '1px solid var(--border)', borderRadius: '8px', boxShadow: 'var(--shadow-lg)' },
  '.cm-panels': { backgroundColor: 'var(--surface-sunken)', color: 'var(--text)', borderColor: 'var(--border)' },
  '.cm-panel input, .cm-panel button': { fontFamily: 'var(--font)', fontSize: '12px' },
  '.cm-foldPlaceholder': { backgroundColor: 'var(--accent-soft)', color: 'var(--accent-soft-text)', border: 'none' },
})

function shortcutKeymap () {
  const keys = []
  keys.push({ key: 'Mod-Enter', run: () => { props.onRun?.(); return !!props.onRun } })
  keys.push({ key: 'Mod-s', run: () => { props.onSave?.(); return !!props.onSave }, preventDefault: true })
  return keymap.of(keys)
}

onMounted(() => {
  view = new EditorView({
    parent: host.value!,
    state: EditorState.create({
      doc: props.modelValue || '',
      extensions: [
        shortcutKeymap(),
        lineNumbers(),
        foldGutter(),
        highlightActiveLineGutter(),
        highlightActiveLine(),
        drawSelection(),
        history(),
        indentOnInput(),
        bracketMatching(),
        closeBrackets(),
        autocompletion({ activateOnTyping: false }),
        highlightSelectionMatches(),
        syntaxHighlighting(highlight),
        EditorState.tabSize.of(2),
        keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, ...foldKeymap, ...completionKeymap, indentWithTab]),
        langSlot.of(languageExt(props.language)),
        readSlot.of([EditorState.readOnly.of(!!props.readonly), EditorView.editable.of(!props.readonly)]),
        wrapSlot.of(props.wrap ? EditorView.lineWrapping : []),
        props.placeholder ? cmPlaceholder(props.placeholder) : [],
        EditorView.contentAttributes.of({ 'aria-label': props.ariaLabel || 'Code editor' }),
        theme,
        EditorView.updateListener.of(u => {
          if (u.docChanged) emit('update:modelValue', u.state.doc.toString())
        }),
      ],
    }),
  })
})

watch(() => props.modelValue, (v) => {
  if (!view) return
  const current = view.state.doc.toString()
  if ((v || '') !== current) {
    view.dispatch({ changes: { from: 0, to: current.length, insert: v || '' } })
  }
})
watch(() => props.language, (l) => view?.dispatch({ effects: langSlot.reconfigure(languageExt(l)) }))
watch(() => props.wrap, (w) => view?.dispatch({ effects: wrapSlot.reconfigure(w ? EditorView.lineWrapping : []) }))
watch(() => props.readonly, (r) => view?.dispatch({ effects: readSlot.reconfigure([EditorState.readOnly.of(!!r), EditorView.editable.of(!r)]) }))

onBeforeUnmount(() => { view?.destroy(); view = null })

defineExpose({
  focus: () => view?.focus(),
  /** The selected text, or '' */
  selection: () => {
    if (!view) return ''
    const { from, to } = view.state.selection.main
    return view.state.sliceDoc(from, to)
  },
  insert: (text: string) => {
    if (!view) return
    const { from, to } = view.state.selection.main
    view.dispatch({ changes: { from, to, insert: text }, selection: { anchor: from + text.length } })
    view.focus()
  },
})
</script>

<style>
.b-code {
  --code-keyword: #7c3aed;
  --code-string: #047857;
  --code-number: #c2410c;
  --code-comment: #8a94a6;
  --code-function: #2563eb;
  --code-type: #b45309;
  --code-property: #0e7490;
  --code-def: #0f172a;
  --code-active: rgba(79, 70, 229, 0.05);
  --code-selection: rgba(79, 70, 229, 0.18);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--code-bg);
  min-height: 0;
}
.b-code.is-fill { height: 100%; }
.b-code:focus-within { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.b-code .cm-editor { height: 100%; }
.b-code .cm-editor.cm-focused { outline: none; }
:root[data-theme='dark'] .b-code {
  --code-keyword: #c4b5fd;
  --code-string: #86efac;
  --code-number: #fdba74;
  --code-comment: #6b7489;
  --code-function: #93c5fd;
  --code-type: #fcd34d;
  --code-property: #67e8f9;
  --code-def: #e8ebf4;
  --code-active: rgba(129, 140, 248, 0.07);
  --code-selection: rgba(129, 140, 248, 0.28);
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) .b-code {
    --code-keyword: #c4b5fd;
    --code-string: #86efac;
    --code-number: #fdba74;
    --code-comment: #6b7489;
    --code-function: #93c5fd;
    --code-type: #fcd34d;
    --code-property: #67e8f9;
    --code-def: #e8ebf4;
    --code-active: rgba(129, 140, 248, 0.07);
    --code-selection: rgba(129, 140, 248, 0.28);
  }
}
</style>
