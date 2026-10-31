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

/** A preview's window in the console's frame: what it posts to its parent */
function frameWindow (origin = 'http://lab.example') {
  const posted: [any, string][] = []
  return {
    posted,
    win: {
      parent: { postMessage: (message: any, target: string) => { posted.push([message, target]) } },
      location: { origin },
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
    const tab: any = { location: { origin: 'http://lab.example' }, postMessage: vi.fn() }
    tab.parent = tab
    startPreviewBridge(preview, tab)
    preview.become('open')
    expect(tab.postMessage).not.toHaveBeenCalled()
    expect(preview.on).not.toHaveBeenCalled()
  })
})
