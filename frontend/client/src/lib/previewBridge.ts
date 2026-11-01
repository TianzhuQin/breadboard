import { CONNECTION_EVENT } from '@human-nature-lab/breadboard-core'

/**
 * What a researcher's preview tells the console it is shown in: the Preview page runs it in a
 * sandboxed frame (frontend/admin, pages/preview/PreviewFrame.vue), which keeps everything else of
 * the page from the console. It posts to window.parent, for the server's own origin only:
 * - {bbPreview: 'connection', connection, watchingBy}: at once, and whenever the page's connection may
 *   have changed (CONNECTION_EVENT): what the server told this preview, which the session's data
 *   doesn't say. The data says that a preview stands in for someone away, not which: one that only
 *   watches while another preview stands in for them (perhaps in another console) had its tile say
 *   "Away: you are standing in".
 * - {bbPreview: 'key', key: 'Escape'} when Escape is pressed on the page and nothing on it took the
 *   key (BBNetwork's confirmation does: its Escape cancels it). The page has the keyboard once the
 *   researcher has clicked in it, so the console's full-size view, which closes on Escape, never
 *   heard the key, and stayed open.
 *
 * Nothing on a participant's page, nor on a preview in a tab of its own.
 */
export const PREVIEW_MESSAGE = 'bbPreview'

/** What the bridge needs of a window: its frame's parent, its address, its key presses */
export interface BridgeWindow {
  parent: { postMessage (message: any, targetOrigin: string): void } | null
  location: { origin?: string }
  addEventListener (type: string, listener: (ev: any) => void): void
  removeEventListener (type: string, listener: (ev: any) => void): void
}

/**
 * Start telling the console, when `bb` (Breadboard) is a preview in a frame of the console's.
 * Returns what stops it.
 */
export function startPreviewBridge (bb: any, win: BridgeWindow = window as any): () => void {
  const parent = win.parent
  if (!bb || !bb.preview || !parent || (parent as any) === win) return () => {}
  // (the console is at the server's address, as this page is: the frame's own origin is opaque, but
  // its address still says where it came from)
  const origin = win.location && win.location.origin && win.location.origin !== 'null' ? win.location.origin : '*'
  const post = (message: any) => {
    try {
      parent.postMessage(message, origin)
    } catch (e) {
      // (no console to tell)
    }
  }
  const tellConnection = () => post({
    [PREVIEW_MESSAGE]: 'connection',
    connection: bb.connection,
    watchingBy: bb.watchingBy || null,
  })
  bb.on(CONNECTION_EVENT, tellConnection)
  tellConnection()
  // (after the page's own handlers: bubbling up to the window, unless one took the key; not while an
  // input method composes, where Escape cancels what is being typed)
  const onKey = (ev: KeyboardEvent) => {
    if ((ev.key === 'Escape' || ev.key === 'Esc') && !ev.defaultPrevented && !ev.isComposing) {
      post({ [PREVIEW_MESSAGE]: 'key', key: 'Escape' })
    }
  }
  win.addEventListener('keydown', onKey)
  return () => {
    bb.off(CONNECTION_EVENT, tellConnection)
    win.removeEventListener('keydown', onKey)
  }
}
