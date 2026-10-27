import { forceSimulation, forceLink, forceManyBody, forceCollide, forceX, forceY, type Simulation, type SimulationNodeDatum, type SimulationLinkDatum, type ForceLink, type ForceManyBody } from 'd3-force'
import type { LiveGraph } from '@/lib/graph'

/**
 * Where everyone sits on the network picture: a d3-force layout kept in step with the live graph.
 *
 * One layout per graph, shared by every view of it and kept while the researcher visits other
 * pages, so coming back shows the same picture. People who leave are remembered for a while, so a
 * full replay (reconnect, engine reload) puts them back where they were instead of reshuffling.
 */

export interface LayoutNode extends SimulationNodeDatum {
  id: string
  /** Radius in layout units (grows with the number of ties). */
  r: number
  degree: number
  /** When they appeared (ms, performance clock), for the pop-in. */
  born: number
}
export interface LayoutLink extends SimulationLinkDatum<LayoutNode> {
  id: string
  source: LayoutNode
  target: LayoutNode
  born: number
}

export interface SyncResult { added: number, removed: number, structural: boolean }

const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now())

export class NetworkLayout {
  nodes = new Map<string, LayoutNode>()
  links: LayoutLink[] = []
  adjacency = new Map<string, Set<string>>()
  sizeByDegree = true
  /** The last view (zoom and pan) a component showed, restored when it comes back. */
  view: { k: number, x: number, y: number } | null = null
  /** Whether the view should keep fitting everyone in (until the researcher pans or zooms). */
  follow = true

  private remembered = new Map<string, { x: number, y: number }>()
  readonly sim: Simulation<LayoutNode, LayoutLink>

  constructor () {
    this.sim = forceSimulation<LayoutNode, LayoutLink>([])
      .force('link', forceLink<LayoutNode, LayoutLink>([]).id(d => d.id).distance(l => 34 + l.source.r + l.target.r))
      .force('charge', forceManyBody<LayoutNode>().strength(-220).distanceMax(520).theta(0.9))
      .force('collide', forceCollide<LayoutNode>().radius(d => d.r + 7).iterations(1))
      .force('x', forceX<LayoutNode>(0).strength(0.055))
      .force('y', forceY<LayoutNode>(0).strength(0.055))
      .alphaDecay(0.028)
      .velocityDecay(0.42)
      .stop()
  }

  radiusFor (degree: number): number {
    if (!this.sizeByDegree) return 8
    return Math.min(17, 6 + Math.sqrt(degree) * 2.3)
  }

  /** Bigger dots for people with more ties, or all the same size. */
  setSizeByDegree (on: boolean) {
    if (this.sizeByDegree === on) return
    this.sizeByDegree = on
    for (const n of this.nodes.values()) n.r = this.radiusFor(n.degree)
    // re-initialises the forces, which cache radii and tie lengths
    this.sim.nodes(Array.from(this.nodes.values()))
    this.reheat(0.3)
  }

  degree (id: string): number {
    return this.adjacency.get(id)?.size || 0
  }

  /** Bring the layout in line with the graph, keeping everyone's place. */
  sync (graph: LiveGraph): SyncResult {
    const t = now()
    let added = 0
    let removed = 0
    let structural = false

    for (const [id, n] of this.nodes) {
      if (!graph.nodes.has(id)) {
        this.remember(id, n)
        this.nodes.delete(id)
        removed++
      }
    }

    // ties, and who is tied to whom (ignoring ties to unknown people and loops)
    const adjacency = new Map<string, Set<string>>()
    const linkSpecs: { id: string, source: string, target: string }[] = []
    for (const l of graph.links.values()) {
      if (l.source === l.target || !graph.nodes.has(l.source) || !graph.nodes.has(l.target)) continue
      linkSpecs.push(l)
      if (!adjacency.has(l.source)) adjacency.set(l.source, new Set())
      if (!adjacency.has(l.target)) adjacency.set(l.target, new Set())
      adjacency.get(l.source)!.add(l.target)
      adjacency.get(l.target)!.add(l.source)
    }
    this.adjacency = adjacency

    // newcomers: back where they were, next to someone they're tied to, or on a spiral round the middle
    const fresh: LayoutNode[] = []
    for (const id of graph.nodes.keys()) {
      if (this.nodes.has(id)) continue
      const n: LayoutNode = { id, r: 8, degree: 0, born: t }
      const prev = this.remembered.get(id)
      if (prev) {
        n.x = prev.x
        n.y = prev.y
        n.born = t - 10000 // no pop-in for someone coming back
        this.remembered.delete(id)
      }
      this.nodes.set(id, n)
      fresh.push(n)
      added++
    }
    if (fresh.length) {
      const c = this.centroid()
      let spiral = this.nodes.size - fresh.length
      for (const n of fresh) {
        if (n.x !== undefined) continue
        const friend = Array.from(adjacency.get(n.id) || []).map(id => this.nodes.get(id)).find(f => f && f.x !== undefined)
        if (friend) {
          const a = Math.random() * Math.PI * 2
          n.x = friend.x! + Math.cos(a) * 28
          n.y = friend.y! + Math.sin(a) * 28
        } else {
          // golden-angle spiral: evenly spread, no overlaps, no randomness to settle out
          const a = spiral * 2.39996
          const d = 22 * Math.sqrt(spiral + 0.5)
          n.x = c.x + Math.cos(a) * d
          n.y = c.y + Math.sin(a) * d
          spiral++
        }
      }
    }

    for (const n of this.nodes.values()) {
      n.degree = adjacency.get(n.id)?.size || 0
      n.r = this.radiusFor(n.degree)
    }

    const before = new Map(this.links.map(l => [l.id, l]))
    const links: LayoutLink[] = []
    for (const l of linkSpecs) {
      const s = this.nodes.get(l.source)!
      const tg = this.nodes.get(l.target)!
      const old = before.get(l.id)
      if (old && old.source === s && old.target === tg) {
        links.push(old)
        before.delete(l.id)
      } else {
        links.push({ id: l.id, source: s, target: tg, born: old ? old.born : t })
        structural = true
      }
    }
    if (before.size) structural = true
    if (added || removed) structural = true
    this.links = links

    if (structural) {
      this.sim.nodes(Array.from(this.nodes.values()))
      ;(this.sim.force('link') as ForceLink<LayoutNode, LayoutLink>).links(this.links)
      const count = this.nodes.size
      ;(this.sim.force('charge') as ForceManyBody<LayoutNode>).strength(count > 400 ? -90 : count > 150 ? -130 : count > 60 ? -170 : -220)
    }
    return { added, removed, structural }
  }

  private remember (id: string, n: LayoutNode) {
    if (n.x === undefined || n.y === undefined) return
    this.remembered.set(id, { x: n.x, y: n.y })
    if (this.remembered.size > 2000) {
      const first = this.remembered.keys().next().value
      if (first !== undefined) this.remembered.delete(first)
    }
  }

  centroid (): { x: number, y: number } {
    let x = 0
    let y = 0
    let k = 0
    for (const n of this.nodes.values()) {
      if (n.x === undefined || n.y === undefined) continue
      x += n.x
      y += n.y
      k++
    }
    return k ? { x: x / k, y: y / k } : { x: 0, y: 0 }
  }

  get alpha () { return this.sim.alpha() }
  get moving () { return this.sim.alpha() >= this.sim.alphaMin() && this.nodes.size > 0 }

  /** Make the layout move again (after a change); stronger for bigger changes. */
  reheat (alpha = 0.3) {
    this.sim.alpha(Math.max(this.sim.alpha(), alpha))
  }

  /** One step of the layout. Returns whether it is still moving. */
  step (): boolean {
    if (!this.moving) return false
    this.sim.tick()
    return this.moving
  }

  /** Run the layout without drawing, up to `budgetMs`, e.g. before the first picture. */
  settle (budgetMs = 40, maxTicks = 300) {
    const start = now()
    let i = 0
    while (this.moving && i < maxTicks && now() - start < budgetMs) {
      this.sim.tick()
      i++
    }
    return i
  }

  /** Free everyone the researcher pinned and lay the network out again. */
  unpinAll () {
    for (const n of this.nodes.values()) { n.fx = null; n.fy = null }
  }

  /** The box around everyone, in layout units (null when empty). */
  bounds (): { minX: number, minY: number, maxX: number, maxY: number } | null {
    if (!this.nodes.size) return null
    let minX = Infinity
    let minY = Infinity
    let maxX = -Infinity
    let maxY = -Infinity
    for (const n of this.nodes.values()) {
      const x = n.x || 0
      const y = n.y || 0
      if (x - n.r < minX) minX = x - n.r
      if (y - n.r < minY) minY = y - n.r
      if (x + n.r > maxX) maxX = x + n.r
      if (y + n.r > maxY) maxY = y + n.r
    }
    return { minX, minY, maxX, maxY }
  }
}

const layouts = new WeakMap<LiveGraph, NetworkLayout>()

/** The layout of a graph (one per graph, kept between page visits). */
export function layoutFor (graph: LiveGraph): NetworkLayout {
  let l = layouts.get(graph)
  if (!l) {
    l = new NetworkLayout()
    layouts.set(graph, l)
  }
  return l
}
