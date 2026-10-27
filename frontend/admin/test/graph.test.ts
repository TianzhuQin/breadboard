import { describe, it, expect } from 'vitest'
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
})
