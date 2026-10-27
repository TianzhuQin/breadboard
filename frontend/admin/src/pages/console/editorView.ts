import { EditorView, type ViewUpdate } from '@codemirror/view'
import { StateEffect } from '@codemirror/state'

/**
 * Small reach-ins to the CodeMirror view inside a BCodeEditor, for what its exposed API doesn't
 * cover yet: where the selection is (to map error lines back to the editor), jumping to a line and
 * listening to selection changes. Everything degrades to doing nothing when no view is found.
 */

/** The view inside an element that contains a BCodeEditor (or is one). */
export function viewIn (el: Element | null | undefined): EditorView | null {
  if (!el) return null
  const editor = el.classList?.contains('cm-editor') ? el : el.querySelector('.cm-editor')
  return editor ? EditorView.findFromDOM(editor as HTMLElement) : null
}

export interface SelectionInfo {
  text: string
  /** 1-based lines the selection starts and ends on */
  fromLine: number
  toLine: number
  empty: boolean
}

export function selectionOf (view: EditorView): SelectionInfo {
  const { from, to } = view.state.selection.main
  const fromLine = view.state.doc.lineAt(from).number
  // a selection ending at the very start of a line doesn't really include that line
  const endLine = view.state.doc.lineAt(to)
  const toLine = to > from && to === endLine.from ? Math.max(fromLine, endLine.number - 1) : endLine.number
  return { text: view.state.sliceDoc(from, to), fromLine, toLine, empty: from === to }
}

/** Put the cursor on a line (1-based), select it and scroll it into view. */
export function goToLine (view: EditorView, line: number, column?: number) {
  const doc = view.state.doc
  const l = doc.line(Math.min(Math.max(1, Math.round(line)), doc.lines))
  const anchor = column ? Math.min(l.to, l.from + Math.max(0, column - 1)) : l.from
  view.dispatch({
    selection: column ? { anchor } : { anchor: l.from, head: l.to },
    effects: EditorView.scrollIntoView(anchor, { y: 'center' }),
  })
  view.focus()
}

/**
 * Insert code on its own line, after the cursor's line (or the selection's last line), and select
 * it, so Cmd/Ctrl+Enter runs just that. Nothing already written is replaced.
 */
export function insertBlock (view: EditorView, code: string) {
  const line = view.state.doc.lineAt(view.state.selection.main.to)
  const blank = !line.text.trim()
  // an empty line is used as it is; otherwise a new line starts after the current one
  const from = blank ? line.from : line.to
  const to = line.to
  const insert = blank ? code : '\n' + code
  const start = blank ? line.from : line.to + 1
  view.dispatch({
    changes: { from, to, insert },
    selection: { anchor: start, head: start + code.length },
    effects: EditorView.scrollIntoView(start, { y: 'nearest' }),
  })
  view.focus()
}

/** Call `fn` after every change of the document or the selection. */
export function onViewUpdate (view: EditorView, fn: (u: ViewUpdate) => void) {
  view.dispatch({ effects: StateEffect.appendConfig.of(EditorView.updateListener.of(fn)) })
}
