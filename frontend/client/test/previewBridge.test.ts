import { describe, expect, it, vi } from 'vitest'
import { startPreviewBridge } from '../src/lib/previewBridge'

// A researcher's preview runs in a sandboxed frame of the console's Preview page, which sees nothing
// of it. Two consoles previewing someone away: one preview stands in, the other only watches, and
// the data says only that a preview stands in, so the second console said "you are standing in".
// The page tells the console what its connection is (and nothing else).

/** Stands in for window.Breadboard: its connection, and CONNECTION_EVENT */
function fakeBreadboard (preview = true) {
  const listeners: Record<string, Function[]> = {}
  const bb = {
    preview,
    connection: 'connecting',
    watchingBy: null as string | null,
    on: vi.fn((event: string, fn: Function) => { (listeners[event] ||= []).push(fn) }),
    off: vi.fn((event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) }),
    become (state: string, by: string | null = null) {
      bb.connection = state
      bb.watchingBy = by
      for (const fn of listeners['bb:connection'] || []) fn()
    },
  }
  return bb
}

/** A preview's window in the console's frame: what it posts to its parent, and its key presses */
function frameWindow (origin = 'http://lab.example') {
  const posted: [any, string][] = []
  const target = new EventTarget()
  return {
    posted,
    win: {
      parent: { postMessage: (message: any, to: string) => { posted.push([message, to]) } },
      location: { origin },
      addEventListener: (type: string, fn: (ev: any) => void) => target.addEventListener(type, fn),
      removeEventListener: (type: string, fn: (ev: any) => void) => target.removeEventListener(type, fn),
    },
    /** A key pressed on the page, after the page's own handlers (`taken`: one of them took it) */
    press (key: string, opts: { taken?: boolean, composing?: boolean } = {}) {
      const ev = new KeyboardEvent('keydown', { key, cancelable: true, isComposing: !!opts.composing })
      if (opts.taken) ev.preventDefault()
      target.dispatchEvent(ev)
    },
  }
}

describe('a preview in the console\'s frame', () => {
  it('tells the console what its connection is, at once and as it changes, for the server\'s own origin only', () => {
    const bb = fakeBreadboard()
    const { posted, win } = frameWindow()
    const stop = startPreviewBridge(bb, win)
    expect(posted).toEqual([[{ bbPreview: 'connection', connection: 'connecting', watchingBy: null }, 'http://lab.example']])
    bb.become('watching', 'page')
    bb.become('watching', 'preview')
    bb.become('open')
    expect(posted.slice(1).map(([m]) => m)).toEqual([
      { bbPreview: 'connection', connection: 'watching', watchingBy: 'page' },
      { bbPreview: 'connection', connection: 'watching', watchingBy: 'preview' },
      { bbPreview: 'connection', connection: 'open', watchingBy: null },
    ])
    expect(posted.every(([, target]) => target === 'http://lab.example')).toBe(true)
    stop()
    bb.become('lost')
    expect(posted).toHaveLength(4)
  })

  it('tells nothing on a participant\'s page, nor on a preview in a tab of its own', () => {
    const participant = fakeBreadboard(false)
    const framed = frameWindow()
    startPreviewBridge(participant, framed.win)
    participant.become('open')
    expect(framed.posted).toEqual([])

    const preview = fakeBreadboard()
    const tab: any = { location: { origin: 'http://lab.example' }, postMessage: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn() }
    tab.parent = tab
    startPreviewBridge(preview, tab)
    preview.become('open')
    expect(tab.postMessage).not.toHaveBeenCalled()
    expect(preview.on).not.toHaveBeenCalled()
    expect(tab.addEventListener).not.toHaveBeenCalled()
  })

  // Clicked into, the page has the keyboard: the console's full-size view never heard Escape, and
  // stayed open.
  it('tells the console when Escape is pressed on it, unless the page took the key', () => {
    const bb = fakeBreadboard()
    const frame = frameWindow()
    const stop = startPreviewBridge(bb, frame.win)
    const keys = () => frame.posted.map(([m]) => m).filter(m => m.bbPreview === 'key')
    frame.press('Escape')
    expect(keys()).toEqual([{ bbPreview: 'key', key: 'Escape' }])
    expect(frame.posted[frame.posted.length - 1][1]).toBe('http://lab.example')
    // other keys, a key the page took (cancelling a tie change), and Escape in an input method aren't
    frame.press('Enter')
    frame.press('a')
    frame.press('Escape', { taken: true })
    frame.press('Escape', { composing: true })
    expect(keys()).toHaveLength(1)
    // (an older browser's name for it)
    frame.press('Esc')
    expect(keys()).toHaveLength(2)
    stop()
    frame.press('Escape')
    expect(keys()).toHaveLength(2)
  })
})
