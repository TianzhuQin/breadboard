import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import Vue from 'vue'
import BBNetwork from '../src/components/BBNetwork.vue'

// me - x, me - y, me - z, and x - y: a tie among my neighbors (sent with g.setVisibility("egoTies")).
function graph () {
  const node = (id: string, name: string) => ({ id, x: 0, y: 0, data: { name } })
  const me = node('me', 'Me')
  const x = node('nx', 'Ana')
  const y = node('ny', 'Ben')
  const z = node('nz', 'Cleo')
  const edge = (id: number, a: any, b: any) => ({ id, source: a, target: b, data: {} })
  return {
    nodes: [me, x, y, z],
    edges: [edge(1, me, x), edge(2, me, y), edge(3, me, z), edge(4, x, y)],
  }
}

let handlers: Record<string, Function>
let sendEdgeEdit: ReturnType<typeof vi.fn>

beforeEach(() => {
  handlers = {}
  sendEdgeEdit = vi.fn()
  ;(window as any).Breadboard = {
    sendEdgeEdit,
    on: (event: string, h: Function) => { handlers[event] = h },
    off: vi.fn(),
  }
})

function mountNetwork (edgeEdits?: any, g: any = graph()) {
  return mount(BBNetwork, {
    propsData: { player: { id: 'me', edgeEdits }, graph: g },
  })
}

// ties that can be clicked to cut them (every tie has a hit area, for edgeClick)
function clickableHit (w: any, a: string, b: string) {
  return edgeBetween(w, a, b).find('.bb-network__edge-hit[role="button"]')
}

async function openMoveList (w: any) {
  await w.find('.bb-network__moves-toggle').trigger('click')
}

function edgeBetween (w: any, a: string, b: string) {
  const key = a < b ? `${a}|${b}` : `${b}|${a}`
  const index = (w.vm as any).drawnEdges.findIndex((e: any) => e.key === key)
  return w.findAll('.bb-network__edge').at(index)
}

function nodeWithId (w: any, id: string) {
  const index = (w.vm as any).drawnNodes.findIndex((n: any) => n.id === id)
  return w.findAll('.bb-network__node').at(index)
}

describe('BBNetwork looks', () => {
  it('writes letters in white on dark colours and in slate on light ones', () => {
    const node = (id: string, color?: string) => ({ id, x: 0, y: 0, data: color ? { color } : {} })
    const me = node('me'), blue = node('b', '#2563eb'), grey = node('g', '#e2e8f0'), plain = node('p')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph: { nodes: [me, blue, grey, plain], edges: [edge(1, me, blue), edge(2, me, grey), edge(3, me, plain)] } } })
    const ink = (id: string) => (w.vm as any).drawnNodes.find((n: any) => n.id === id).ink
    expect(ink('b')).toBe('#ffffff')
    expect(ink('g')).toBe('#334155')
    expect(ink('p')).toBe('#334155')
    expect(ink('me')).toBe('#ffffff')
  })

  it('names unnamed neighbors clockwise from the top', () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), a = node('z1'), b = node('z2'), c = node('z3'), d = node('z4')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph: { nodes: [me, d, c, b, a], edges: [edge(1, me, a), edge(2, me, b), edge(3, me, c), edge(4, me, d)] } } })
    const vm = w.vm as any
    const top = vm.ring[0]
    expect(vm.fallbackNames[top]).toBe('Person A')
    expect(vm.fallbackNames[vm.ring[1]]).toBe('Person B')
  })

  it('says "your tie" for the participant\'s own ties in the list of changes', async () => {
    const w = mountNetwork({ enabled: true, remove: true, add: false, scope: 'own' })
    await openMoveList(w)
    const texts = w.findAll('.bb-network__move').wrappers.map((x: any) => x.text())
    expect(texts).toEqual(expect.arrayContaining(['Cut your tie with Ana']))
  })
})

describe('BBNetwork layout', () => {
  it('never draws inside out when the stage is measured before it has a size', async () => {
    const w = mountNetwork()
    const vm = w.vm as any
    for (const [sw, sh] of [[1, 1], [364, 10], [364, 400]]) {
      vm.setSize(sw, sh)
      await w.vm.$nextTick()
      const [, , vw, vh] = vm.viewBox.split(' ').map(Number)
      expect(vw).toBeGreaterThan(0)
      expect(vh).toBeGreaterThan(0)
      expect(vm.fitScale).toBeGreaterThan(0)
    }
    expect(vm.tooSmallToTap).toBe(false)
  })

  it('puts the participant in the middle and their neighbors on a circle', () => {
    const w = mountNetwork()
    const targets = (w.vm as any).targets
    expect(targets.me).toEqual({ x: 0, y: 0 })
    const r = (p: any) => Math.round(Math.hypot(p.x, p.y))
    expect(r(targets.nx)).toBe(r(targets.ny))
    expect(r(targets.ny)).toBe(r(targets.nz))
    expect(r(targets.nx)).toBeGreaterThan(100)
  })

  it('seats neighbors who are tied to each other next to each other', () => {
    // Ana - Cleo and Ben - Dev are tied; sorted by id they would alternate around the circle.
    const node = (id: string) => ({ id, x: 0, y: 0, data: { name: id } })
    const me = node('me'), a = node('a'), b = node('b'), c = node('c'), d = node('d')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const w = mount(BBNetwork, {
      propsData: {
        player: { id: 'me' },
        graph: { nodes: [me, a, b, c, d], edges: [edge(1, me, a), edge(2, me, b), edge(3, me, c), edge(4, me, d), edge(5, a, c), edge(6, b, d)] },
      },
    })
    const ring: string[] = (w.vm as any).ring
    const steps = (x: string, y: string) => (w.vm as any).ringSteps(x, y)
    expect(ring.length).toBe(4)
    expect(steps('a', 'c')).toBe(1)
    expect(steps('b', 'd')).toBe(1)
  })

  it('keeps people where they were when a new tie only changes the order around the circle', async () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), a = node('a'), b = node('b'), c = node('c')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const graph = { nodes: [me, a, b, c], edges: [edge(1, me, a), edge(2, me, b), edge(3, me, c), edge(4, a, b)] }
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph } })
    const before = { ...(w.vm as any).targets }
    graph.edges.push(edge(5, b, c))
    await w.vm.$nextTick()
    expect((w.vm as any).targets).toEqual(before)
  })

  it('keeps everyone where they are when a tie between neighbors is cut', async () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), a = node('a'), b = node('b'), c = node('c'), d = node('d')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const graph = { nodes: [me, a, b, c, d], edges: [edge(1, me, a), edge(2, me, b), edge(3, me, c), edge(4, me, d), edge(5, a, c), edge(6, b, d)] }
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph } })
    const before = { ...(w.vm as any).targets }
    graph.edges.splice(4, 1)
    await w.vm.$nextTick()
    expect((w.vm as any).targets).toEqual(before)
  })

  it('closes the gap when someone leaves, keeping the order, and seats a newcomer next to whom they are tied to', async () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), a = node('a'), b = node('b'), c = node('c'), d = node('d'), e = node('e')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const graph = { nodes: [me, a, b, c, d], edges: [edge(1, me, a), edge(2, me, b), edge(3, me, c), edge(4, me, d)] }
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph } })
    const first: string[] = (w.vm as any).ring.slice()
    // the participant cuts their tie to the second person around the ring, who leaves their view
    const gone = first[1]
    graph.nodes = graph.nodes.filter(n => n.id !== gone)
    graph.edges = graph.edges.filter(x => x.target.id !== gone)
    await w.vm.$nextTick()
    expect((w.vm as any).ring).toEqual(first.filter(id => id !== gone))
    // someone new, tied to the last person around the ring
    const last = first[3]
    graph.nodes.push(e)
    graph.edges.push(edge(7, me, e), edge(8, e, graph.nodes.find(n => n.id === last)!))
    await w.vm.$nextTick()
    const ring: string[] = (w.vm as any).ring
    expect(ring.indexOf('e')).toBe(ring.indexOf(last) + 1)
  })

  it('curves a tie between neighbors who are not next to each other around the outside', () => {
    // a ring of five neighbors where a and c are tied but b sits between them
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), ns = ['a', 'b', 'c', 'd', 'e'].map(node)
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const edges = ns.map((n, i) => edge(i + 1, me, n))
    edges.push(edge(10, ns[0], ns[1]), edge(11, ns[1], ns[2]), edge(12, ns[0], ns[2]))
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph: { nodes: [me, ...ns], edges } } })
    const drawn = (w.vm as any).drawnEdges
    const shapeOf = (x: string, y: string) => drawn.find((e: any) => e.key === `${x}|${y}`).d
    expect(shapeOf('a', 'b')).toMatch(/^M[^Q]*L/)
    expect(shapeOf('a', 'c')).toContain('Q')
  })

  it('draws ties among neighbors differently from the participant\'s own ties', () => {
    const w = mountNetwork()
    expect(edgeBetween(w, 'nx', 'ny').classes()).toContain('is-among')
    expect(edgeBetween(w, 'me', 'nx').classes()).toContain('is-own')
  })

  it('labels neighbors by name and the participant as "You"', () => {
    const w = mountNetwork()
    const labels = w.findAll('.bb-network__label').wrappers.map(l => l.text())
    expect(labels).toEqual(expect.arrayContaining(['Ana', 'Ben', 'Cleo']))
    expect(w.find('.bb-network__ego-label').text()).toBe('You')
  })

  it('offers no editing unless the server allows it', () => {
    const w = mountNetwork()
    expect(w.findAll('.bb-network__edge-hit[role="button"]').length).toBe(0)
    expect(w.find('.bb-network__hint').exists()).toBe(false)
    expect(w.find('.bb-network__moves').exists()).toBe(false)
  })

  it('marks the participant\'s node with isEgo, as SVGGraph does', () => {
    const g = graph()
    mountNetwork(undefined, g)
    expect(g.nodes.find(n => n.id === 'me')!.isEgo).toBe(true)
    expect(g.nodes.find(n => n.id === 'nx')!.isEgo).toBe(false)
  })

  it('gives every tie a hit area, so edgeClick works without editing', () => {
    const w = mountNetwork()
    expect(w.findAll('.bb-network__edge-hit').length).toBe(4)
  })

  it('mounts on an empty graph with a nodeRadius function and still hears the server', () => {
    const errors: any[] = []
    const previous = Vue.config.errorHandler
    Vue.config.errorHandler = (err: any) => { errors.push(err) }
    try {
      // the radius function of the old default page, which needs a real node
      const w = mount(BBNetwork, {
        propsData: { player: {}, graph: { nodes: [], edges: [] }, nodeRadius: (node: any) => node.data.score * 2 || 30 },
      })
      expect(errors).toEqual([])
      expect(typeof handlers['bb:edge-edit-result']).toBe('function')
      w.destroy()
    } finally {
      Vue.config.errorHandler = previous
    }
  })

  it('names unnamed neighbors "Person A", "Person B", ... and keeps those names', async () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), a = node('n1'), b = node('n2')
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const g = { nodes: [me, a, b], edges: [edge(1, me, a), edge(2, me, b)] }
    const w = mountNetwork(undefined, g)
    const names = () => (w.vm as any).drawnNodes.filter((n: any) => !n.isEgo).map((n: any) => n.label).sort()
    expect(names()).toEqual(['Person A', 'Person B'])
    const before = (w.vm as any).nameOf('n2')
    g.nodes.push(node('n0'))
    g.edges.push(edge(3, me, g.nodes[3]))
    await w.vm.$nextTick()
    expect((w.vm as any).nameOf('n2')).toBe(before)
    expect((w.vm as any).nameOf('n0')).toBe('Person C')
  })

  it('makes room for the largest person when nodeRadius is a function', () => {
    const node = (id: string, score: number) => ({ id, x: 0, y: 0, data: { name: id, score } })
    const me = node('me', 0)
    const ns = Array.from({ length: 12 }, (_, i) => node('n' + String(i).padStart(2, '0'), 25))
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const w = mount(BBNetwork, {
      propsData: {
        player: { id: 'me' },
        graph: { nodes: [me, ...ns], edges: ns.map((n, i) => edge(i + 1, me, n)) },
        nodeRadius: (n: any) => n.data.score * 2 || 30,
      },
    })
    const vm = w.vm as any
    vm.size = { w: 349, h: 400 }
    vm.relayout()
    const r = 50 * vm.nodeScale
    expect(2 * Math.PI * vm.ringR / 12 - 2 * r).toBeGreaterThan(8)
  })

  it('never lets people overlap on a small screen, shrinking the picture instead', () => {
    // 12 neighbors on a phone-sized stage, with long ties among them
    const node = (id: string) => ({ id, x: 0, y: 0, data: { name: id } })
    const me = node('me')
    const ns = Array.from({ length: 12 }, (_, i) => node('n' + String(i).padStart(2, '0')))
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const edges = ns.map((n, i) => edge(i + 1, me, n))
    edges.push(edge(100, ns[0], ns[6]), edge(101, ns[3], ns[9]))
    const w = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph: { nodes: [me, ...ns], edges } } })
    const vm = w.vm as any
    vm.size = { w: 349, h: 400 }
    vm.relayout()
    const r = vm.baseRadius * vm.nodeScale
    const ring = vm.ring
    const gap = 2 * Math.PI * vm.ringR / ring.length - 2 * r
    expect(gap).toBeGreaterThan(8)
    expect(vm.ringR - r).toBeGreaterThan(vm.baseRadius * vm.nodeScale * 1.35 + 12)
    expect(vm.fitScale).toBeLessThanOrEqual(1)
  })
})

describe('BBNetwork editing', () => {
  it('cuts a tie among neighbors after confirmation', async () => {
    const w = mountNetwork({ enabled: true, remove: true, add: false, scope: 'amongNeighbors' })
    // Only the tie among neighbors may be cut in this scope.
    expect(clickableHit(w, 'me', 'nx').exists()).toBe(false)
    await clickableHit(w, 'nx', 'ny').trigger('click')
    expect(w.find('.bb-network__confirm').text()).toContain('Cut the tie between Ana and Ben?')
    expect(sendEdgeEdit).not.toHaveBeenCalled()
    await w.find('.bb-network__confirm .bb-network__btn--danger').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('remove', 'nx', 'ny')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
  })

  it('can be cancelled', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    await w.find('.bb-network__confirm .bb-network__btn--ghost').trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
    expect(sendEdgeEdit).not.toHaveBeenCalled()
  })

  it('connects two neighbors by clicking one and then the other', async () => {
    const w = mountNetwork({ enabled: true, add: true, scope: 'amongNeighbors' })
    // The participant themself isn't a choice in this scope.
    expect(nodeWithId(w, 'me').classes()).not.toContain('is-selectable')
    await nodeWithId(w, 'nx').trigger('click')
    expect(w.find('.bb-network__hint').text()).toContain('Now choose who Ana should be connected with')
    // Ana and Ben are already tied, so Ben can't be chosen; Cleo can.
    expect(nodeWithId(w, 'ny').classes()).not.toContain('is-selectable')
    await nodeWithId(w, 'nz').trigger('click')
    expect(w.find('.bb-network__confirm').text()).toContain('Connect Ana and Cleo?')
    await w.find('.bb-network__confirm .bb-network__btn--primary').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('add', 'nx', 'nz')
  })

  it('moves the keyboard focus to the confirmation and back afterwards', async () => {
    const w = mount(BBNetwork, {
      propsData: { player: { id: 'me', edgeEdits: { enabled: true, remove: true, scope: 'amongNeighbors' } }, graph: graph() },
      attachTo: document.body,
    })
    await openMoveList(w)
    const move = w.find('.bb-network__move').element as HTMLElement
    move.focus()
    move.click()
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    // Cancel, so a repeated Enter can't confirm by accident
    expect(document.activeElement && document.activeElement.textContent!.trim()).toBe('Cancel')
    await w.find('.bb-network__confirm .bb-network__btn--ghost').trigger('click')
    expect(document.activeElement).toBe(move)
    w.destroy()
  })

  it('can be cancelled with Escape', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    await w.find('.bb-network__confirm').trigger('keydown.esc')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
  })

  it('drops a selection that can no longer lead anywhere', async () => {
    const player: any = { id: 'me', edgeEdits: { enabled: true, add: true, scope: 'amongNeighbors' } }
    const w = mount(BBNetwork, { propsData: { player, graph: graph() } })
    await nodeWithId(w, 'nx').trigger('click')
    expect((w.vm as any).selectedNode).toBe('nx')
    // the server used up the last add, but editing stays on for cuts
    w.setProps({ player: { id: 'me', edgeEdits: { enabled: true, add: false, remove: true, scope: 'amongNeighbors' } } })
    await w.vm.$nextTick()
    expect((w.vm as any).selectedNode).toBe(null)
    expect(w.find('.bb-network__hint').text()).not.toContain('Now choose')
  })

  it('drops a proposal whose tie has gone', async () => {
    const g = graph()
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' }, g)
    await clickableHit(w, 'nx', 'ny').trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(true)
    g.edges.splice(3, 1)
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
  })

  it('does not send the same change twice while waiting for the answer', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors', confirm: false })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(false)
    ;(w.vm as any).propose('remove', 'nx', 'ny')
    expect(sendEdgeEdit).toHaveBeenCalledTimes(1)
    handlers['bb:edge-edit-result']({ ok: false, op: 'remove', a: 'nx', b: 'ny', reason: 'No.' })
    await w.vm.$nextTick()
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(true)
  })

  it('says a change was sent, not done, when the experiment only collects it', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' })
    handlers['bb:edge-edit-result']({ ok: true, applied: false, op: 'remove', a: 'nx', b: 'ny' })
    await w.vm.$nextTick()
    expect(w.find('.bb-network__toast').text()).toBe('Your change was sent.')
  })

  it('says why a tie cannot be cut when it is tapped', async () => {
    const w = mountNetwork({
      enabled: true,
      remove: true,
      scope: 'amongNeighbors',
      candidates: [{ op: 'remove', a: 'nx', b: 'ny', available: false, reason: 'Ben would be left with one tie.' }],
    })
    await edgeBetween(w, 'nx', 'ny').find('.bb-network__edge-hit').trigger('click')
    expect(w.find('.bb-network__toast').text()).toBe('Ben would be left with one tie.')
  })

  it('does not tell the participant to cut ties they cannot see', () => {
    // the experiment allows cutting ties among neighbors, but sends none (visibility "ego")
    const g = graph()
    g.edges.splice(3, 1)
    const w = mountNetwork({ enabled: true, remove: true, add: false, scope: 'amongNeighbors' }, g)
    expect(w.find('.bb-network__hint').text()).toBe('There is no change you can make right now.')
  })

  it('sends the turn the change was offered in', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'own', confirm: false, turn: 7 })
    await edgeBetween(w, 'me', 'nx').find('.bb-network__edge-hit').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('remove', 'me', 'nx', 7)
  })

  it('withdraws an open question when the experiment moves on to another turn', async () => {
    const edits = { enabled: true, remove: true, scope: 'own', turn: 1 }
    const w = mountNetwork(edits)
    await edgeBetween(w, 'me', 'nx').find('.bb-network__edge-hit').trigger('click')
    expect((w.vm as any).proposal).not.toBe(null)
    await w.setProps({ player: { id: 'me', edgeEdits: { ...edits, turn: 2 } } })
    expect((w.vm as any).proposal).toBe(null)
    expect(sendEdgeEdit).not.toHaveBeenCalled()
  })

  it('sends at once when the experiment turns confirmation off', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'own', confirm: false })
    await edgeBetween(w, 'me', 'nx').find('.bb-network__edge-hit').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('remove', 'me', 'nx')
  })

  it('shows unavailable candidate moves greyed out with the reason, and does not send them', async () => {
    const w = mountNetwork({
      enabled: true,
      remove: true,
      scope: 'amongNeighbors',
      candidates: [{ op: 'remove', a: 'nx', b: 'ny', available: false, reason: 'Ben would be left with one tie.' }],
    })
    const edge = edgeBetween(w, 'nx', 'ny')
    expect(edge.classes()).toContain('is-blocked')
    expect(edge.find('title').text()).toBe('Tie between Ana and Ben: Ben would be left with one tie.')
    await edge.find('.bb-network__edge-hit').trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
    await openMoveList(w)
    const move = w.find('.bb-network__move')
    expect(move.text()).toBe('Cut the tie between Ana and Ben')
    expect(move.attributes('disabled')).toBe('disabled')
    expect(w.find('.bb-network__move-reason').text()).toBe('Ben would be left with one tie.')
  })

  it('lists every possible change when the server sends no candidates, folded away at first', async () => {
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors' })
    expect(w.find('.bb-network__move').exists()).toBe(false)
    expect(w.find('.bb-network__moves-toggle').text()).toBe('Show the changes as a list (3 possible)')
    await openMoveList(w)
    const texts = w.findAll('.bb-network__move').wrappers.map(m => m.text())
    expect(texts).toEqual(expect.arrayContaining(['Cut the tie between Ana and Ben', 'Connect Ana and Cleo', 'Connect Ben and Cleo']))
    await w.findAll('.bb-network__move').at(0).trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(true)
  })

  it('greys out a listed change that the current network no longer allows', async () => {
    const w = mountNetwork({
      enabled: true,
      add: true,
      scope: 'amongNeighbors',
      // Ana and Ben are already tied, so this candidate cannot be made
      candidates: [{ op: 'add', a: 'nx', b: 'ny' }, { op: 'add', a: 'nx', b: 'nz' }],
    })
    await openMoveList(w)
    const moves = w.findAll('.bb-network__move').wrappers
    expect(moves[0].attributes('disabled')).toBe('disabled')
    expect(moves[1].attributes('disabled')).toBeUndefined()
  })

  it('tells the participant how many changes are left', () => {
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors', remainingRemoves: 1, remainingAdds: 2 })
    expect(w.find('.bb-network__hint').text()).toBe('You can cut 1 tie and make 2 new ties. Click a tie to cut it; click two people to connect them.')
  })

  it('starts the instructions with a capital letter when only adding is allowed', () => {
    const w = mountNetwork({ enabled: true, add: true, scope: 'amongNeighbors', remainingAdds: 1 })
    expect(w.find('.bb-network__hint').text()).toBe('You can make 1 new tie. Click two people to connect them.')
  })

  it('says so when every allowed change has been made', () => {
    const w = mountNetwork({ enabled: true, add: false, remove: false, scope: 'amongNeighbors', remainingAdds: 0, remainingRemoves: 0 })
    expect(w.find('.bb-network__hint').text()).toBe('You have made all the changes you can for now.')
  })

  it('shows the server\'s answer', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' })
    handlers['bb:edge-edit-result']({ ok: false, op: 'remove', a: 'nx', b: 'ny', reason: 'Too late for that.' })
    await w.vm.$nextTick()
    expect(w.find('.bb-network__toast').text()).toBe('Too late for that.')
    expect(w.find('.bb-network__toast').classes()).toContain('bb-network__toast--error')
  })

  it('keeps a change marked as sent while the network updates for another one', async () => {
    const g = graph()
    const w = mountNetwork({ enabled: true, remove: true, scope: 'visible', confirm: false }, g)
    await clickableHit(w, 'nx', 'ny').trigger('click')
    // an unrelated update arrives (the participant's other cut, say) before the answer
    g.edges.splice(0, 1)
    await w.vm.$nextTick()
    ;(w.vm as any).propose('remove', 'nx', 'ny')
    expect(sendEdgeEdit).toHaveBeenCalledTimes(1)
  })

  it('lets go of a chosen person who has no one left to connect with, and says so', async () => {
    const player: any = { id: 'me', edgeEdits: { enabled: true, add: true, scope: 'amongNeighbors' } }
    const g = graph()
    const w = mount(BBNetwork, { propsData: { player, graph: g } })
    await nodeWithId(w, 'nz').trigger('click')
    expect((w.vm as any).selectedNode).toBe('nz')
    // Cleo gets tied to both Ana and Ben elsewhere
    g.edges.push({ id: 7, source: g.nodes[3], target: g.nodes[1], data: {} }, { id: 8, source: g.nodes[3], target: g.nodes[2], data: {} })
    await w.vm.$nextTick()
    expect((w.vm as any).selectedNode).toBe(null)
    expect(w.find('.bb-network__toast').text()).toBe('Cleo has no one left to connect with.')
  })

  it('says so when a pending confirmation becomes impossible', async () => {
    const g = graph()
    const w = mountNetwork({ enabled: true, add: true, scope: 'amongNeighbors' }, g)
    await nodeWithId(w, 'nx').trigger('click')
    await nodeWithId(w, 'nz').trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(true)
    g.edges.push({ id: 9, source: g.nodes[1], target: g.nodes[3], data: {} })
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
    expect(w.find('.bb-network__toast').text()).toBe('That change is no longer possible.')
  })

  it('uses stand-in names in words when a nodeLabel function shows no labels', async () => {
    const w = mount(BBNetwork, {
      propsData: { player: { id: 'me', edgeEdits: { enabled: true, remove: true, scope: 'amongNeighbors' } }, graph: graph(), nodeLabel: () => '' },
    })
    expect(w.findAll('.bb-network__label').length).toBe(0)
    await openMoveList(w)
    expect(w.find('.bb-network__move').text()).toMatch(/^Cut the tie between Person [A-Z] and Person [A-Z]$/)
  })

  it('does not offer to connect neighbors when their ties are hidden from the participant', () => {
    const g = graph()
    g.edges.splice(3, 1) // the page doesn't get ties among neighbors
    const w = mountNetwork({ enabled: true, add: true, scope: 'amongNeighbors', tiesHidden: true }, g)
    expect(w.findAll('.bb-network__node.is-selectable').length).toBe(0)
    expect(w.find('.bb-network__hint').text()).toBe('There is no change you can make right now.')
  })

  it('keeps room for editing from the start once the server has sent edgeEdits', () => {
    const w = mountNetwork({ enabled: false })
    expect(w.find('.bb-network__bar').exists()).toBe(true)
    expect(w.find('.bb-network__moves').exists()).toBe(true)
    const none = mountNetwork()
    expect(none.find('.bb-network__bar').exists()).toBe(false)
  })

  it('opens the list by itself when people are drawn too small to tap', async () => {
    const node = (id: string) => ({ id, x: 0, y: 0, data: { name: id } })
    const me = node('me')
    const ns = Array.from({ length: 30 }, (_, i) => node('n' + String(i).padStart(2, '0')))
    const edge = (id: number, s: any, t: any) => ({ id, source: s, target: t, data: {} })
    const edges = ns.map((n, i) => edge(i + 1, me, n))
    edges.push(edge(100, ns[0], ns[15]))
    const w = mount(BBNetwork, {
      propsData: { player: { id: 'me', edgeEdits: { enabled: true, remove: true, scope: 'amongNeighbors' } }, graph: { nodes: [me, ...ns], edges } },
    })
    const vm = w.vm as any
    vm.size = { w: 349, h: 400 }
    vm.relayout()
    await w.vm.$nextTick()
    expect(vm.tooSmallToTap).toBe(true)
    expect(w.find('.bb-network__move-list').exists()).toBe(true)
  })

  it('names every tie for screen readers, also those that cannot be edited', () => {
    const w = mountNetwork()
    const hit = edgeBetween(w, 'nx', 'ny').find('.bb-network__edge-hit')
    expect(hit.attributes('role')).toBe('img')
    expect(hit.attributes('aria-label')).toBe('Tie between Ana and Ben')
  })

  it('puts the focus on the list button when the change chosen from the list is gone', async () => {
    const w = mount(BBNetwork, {
      propsData: { player: { id: 'me', edgeEdits: { enabled: true, remove: true, scope: 'visible' } }, graph: graph() },
      attachTo: document.body,
    })
    await openMoveList(w)
    const move = w.find('.bb-network__move').element as HTMLElement
    move.focus()
    move.click()
    await w.vm.$nextTick()
    await w.find('.bb-network__confirm .bb-network__btn--danger').trigger('click')
    await w.vm.$nextTick()
    // that cut is pending, so its button is gone from the list
    expect(document.activeElement).toBe(w.find('.bb-network__moves-toggle').element)
    w.destroy()
  })
})

// Changes sent while the connection was down used to wait in a queue and arrive much later, or never.
describe('BBNetwork while the connection is down', () => {
  /** The page's connection changes (Breadboard's CONNECTION_EVENT) */
  async function become (w: any, state: string) {
    ;(window as any).Breadboard.connection = state
    handlers['bb:connection']()
    await w.vm.$nextTick()
  }

  it('offers no change, says why, and lets a confirmation wait for the connection', async () => {
    ;(window as any).Breadboard.connection = 'open'
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    expect(w.find('.bb-network__confirm').text()).toContain('Cut the tie between Ana and Ben?')

    await become(w, 'lost')
    const confirm = w.find('.bb-network__confirm .bb-network__btn--danger')
    expect((confirm.element as HTMLButtonElement).disabled).toBe(true)
    ;(w.vm as any).confirmProposal()
    expect(sendEdgeEdit).not.toHaveBeenCalled()
    await w.find('.bb-network__confirm .bb-network__btn--ghost').trigger('click')
    expect(w.find('.bb-network__hint').text()).toBe('Reconnecting… you can change ties once the connection is back.')
    // nothing to click on, nor in the list
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(false)
    expect(nodeWithId(w, 'nz').classes()).not.toContain('is-selectable')
    expect((w.vm as any).moves.every((m: any) => !m.available)).toBe(true)
    ;(w.vm as any).propose('remove', 'nx', 'ny')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)

    await become(w, 'open')
    expect(w.find('.bb-network__hint').text()).not.toContain('Reconnecting')
    await clickableHit(w, 'nx', 'ny').trigger('click')
    await w.find('.bb-network__confirm .bb-network__btn--danger').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('remove', 'nx', 'ny')
  })

  it('offers no change on a researcher\'s preview that only watches the player, and says why', async () => {
    ;(window as any).Breadboard.connection = 'open'
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    expect(w.find('.bb-network__confirm').exists()).toBe(true)
    // their own page comes back: the question can't be answered here any more
    await become(w, 'watching')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
    ;(w.vm as any).confirmProposal()
    expect(sendEdgeEdit).not.toHaveBeenCalled()
    expect(w.find('.bb-network__hint').text()).toBe('Watching only: their own page is connected, so you can’t change their ties.')
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(false)
    expect((w.vm as any).moves.every((m: any) => !m.available)).toBe(true)
  })

  it('says on a preview of a computer player that the computer plays the seat', async () => {
    ;(window as any).Breadboard.connection = 'open'
    ;(window as any).Breadboard.computerPlayer = true
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors' })
    await become(w, 'watching')
    expect(w.find('.bb-network__hint').text()).toBe('Watching only: the computer plays this seat, so you can’t change its ties.')
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(false)
  })

  it('says nothing of reconnecting once the page is done with (another page has the seat)', async () => {
    ;(window as any).Breadboard.connection = 'open'
    const w = mountNetwork({ enabled: true, remove: true, add: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    await become(w, 'elsewhere')
    expect(w.find('.bb-network__confirm').exists()).toBe(false)
    expect(w.find('.bb-network__hint').exists()).toBe(false)
    expect(clickableHit(w, 'nx', 'ny').exists()).toBe(false)
  })

  it('is editable as before where no page connection says otherwise', async () => {
    const w = mountNetwork({ enabled: true, remove: true, scope: 'amongNeighbors' })
    await clickableHit(w, 'nx', 'ny').trigger('click')
    await w.find('.bb-network__confirm .bb-network__btn--danger').trigger('click')
    expect(sendEdgeEdit).toHaveBeenCalledWith('remove', 'nx', 'ny')
  })
})
