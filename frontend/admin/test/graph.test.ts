import { describe, it, expect, afterEach, vi } from 'vitest'
import { LiveGraph } from '../src/lib/graph'

describe('LiveGraph', () => {
  it('builds nodes and ties from the server\'s messages', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: 'a' })
    g.apply({ action: 'addNode', id: 'b' })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'score', value: 5 })
    g.apply({ action: 'addLink', id: '7', source: 'a', target: 'b', value: 'connected' })
    expect(g.nodes.size).toBe(2)
    expect(g.nodes.get('a')!.data.score).toBe(5)
    expect(g.linkBetween('b', 'a')!.id).toBe('7')
    expect(g.neighbors('a')).toEqual(['b'])
    expect(g.degree('b')).toBe(1)
  })

  it('ignores messages that are not about the graph', () => {
    const g = new LiveGraph()
    expect(g.apply({ output: 'hi' })).toBe(false)
    expect(g.apply({ action: 'addNode', id: 'x' })).toBe(true)
  })

  it('removes the right person and their ties (the old store removed the first one)', () => {
    const g = new LiveGraph()
    for (const id of ['a', 'b', 'c']) g.apply({ action: 'addNode', id })
    g.apply({ action: 'addLink', id: '1', source: 'a', target: 'b' })
    g.apply({ action: 'addLink', id: '2', source: 'b', target: 'c' })
    g.apply({ action: 'removeNode', id: 'b' })
    expect([...g.nodes.keys()]).toEqual(['a', 'c'])
    expect(g.links.size).toBe(0)
  })

  it('does nothing for an unknown tie (the old graph deleted the last one)', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: 'a' })
    g.apply({ action: 'addNode', id: 'b' })
    g.apply({ action: 'addLink', id: '1', source: 'a', target: 'b' })
    g.apply({ action: 'removeLink', id: '99' })
    expect(g.links.size).toBe(1)
  })

  it('removes a property from one tie only', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addLink', id: '1', source: 'a', target: 'b' })
    g.apply({ action: 'addLink', id: '2', source: 'b', target: 'c' })
    g.apply({ action: 'linkPropertyChanged', id: '1', key: 'w', value: '3' })
    g.apply({ action: 'linkPropertyChanged', id: '2', key: 'w', value: '4' })
    g.apply({ action: 'linkPropertyRemoved', id: '1', key: 'w' })
    expect(g.links.get('1')!.data.w).toBeUndefined()
    expect(g.links.get('2')!.data.w).toBe(4)
  })

  it('does not duplicate a person added twice (a replay)', () => {
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: 'a' })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'k', value: 1 })
    g.apply({ action: 'addNode', id: 'a' })
    expect(g.nodes.size).toBe(1)
    expect(g.nodes.get('a')!.data.k).toBe(1)
  })

  it('keeps strings that only look like JSON when they are typed values', () => {
    const g = new LiveGraph()
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'n', value: 7 })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'o', value: { x: 1 } })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 's', value: 'plain' })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'num', value: '5' })
    g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'yes', value: 'true' })
    expect(g.nodes.get('a')!.data).toEqual({ n: 7, o: { x: 1 }, s: 'plain', num: '5', yes: 'true' })
  })

  // (the Preview page's first look waits only for a network that is still coming in)
  it('says when it last changed, and when it was last emptied, after which the network comes in again', () => {
    const now = vi.spyOn(Date, 'now')
    try {
      const g = new LiveGraph()
      expect([g.changedAt, g.clearedAt]).toEqual([0, 0])
      now.mockReturnValue(1000)
      g.clear()
      expect([g.changedAt, g.clearedAt]).toEqual([1000, 1000])
      now.mockReturnValue(1500)
      g.apply({ action: 'addNode', id: 'a' })
      now.mockReturnValue(1700)
      g.apply({ action: 'nodePropertyChanged', id: 'a', key: 'testmode', value: true })
      expect([g.changedAt, g.clearedAt]).toEqual([1700, 1000])
      // not about the graph: nothing changed
      now.mockReturnValue(2000)
      g.apply({ output: 'hi' })
      expect(g.changedAt).toBe(1700)
    } finally {
      now.mockRestore()
    }
  })
})

describe('LiveGraph redraw signal', () => {
  /** Frames that only come when the test says so (or never: a window behind others). */
  let frames: FrameRequestCallback[] = []
  function visibleWithFrames () {
    frames = []
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', (id: number) => { frames[id - 1] = () => {} })
  }
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    delete (document as any).hidden
  })

  it('still redraws when the browser sends no frames', () => {
    visibleWithFrames()
    const g = new LiveGraph()
    const v0 = g.version.value
    g.apply({ action: 'addNode', id: 'a' })
    g.apply({ action: 'addNode', id: 'b' })
    expect(frames.length).toBe(1)
    vi.advanceTimersByTime(119)
    expect(g.version.value).toBe(v0)
    vi.advanceTimersByTime(1)
    expect(g.version.value).toBe(v0 + 1)
    // the frame that comes late does not bump it again
    frames[0](0)
    vi.advanceTimersByTime(1000)
    expect(g.version.value).toBe(v0 + 1)
  })

  it('redraws once on the next frame when frames come', () => {
    visibleWithFrames()
    const g = new LiveGraph()
    const v0 = g.version.value
    g.apply({ action: 'addNode', id: 'a' })
    frames[0](0)
    expect(g.version.value).toBe(v0 + 1)
    vi.advanceTimersByTime(1000)
    expect(g.version.value).toBe(v0 + 1)
    // the next change is signalled again
    g.apply({ action: 'addNode', id: 'b' })
    expect(frames.length).toBe(2)
    frames[1](0)
    expect(g.version.value).toBe(v0 + 2)
  })
})
