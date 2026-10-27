import { shallowRef, triggerRef } from 'vue'

/**
 * The live network of the selected session, built from the engine's graph messages
 * (addNode, removeNode, nodePropertyChanged, nodePropertyRemoved, addLink, removeLink,
 * linkPropertyChanged, linkPropertyRemoved; see IteratedBreadboardGraphChangedListener).
 *
 * Nodes and links are plain objects kept in maps; views read `version` to know when to redraw,
 * so a burst of hundreds of property changes costs one render, not hundreds.
 */
export interface GraphNode {
  id: string
  data: Record<string, any>
  // positions, written by the layout
  x?: number
  y?: number
  vx?: number
  vy?: number
  fx?: number | null
  fy?: number | null
}
export interface GraphLink {
  id: string
  source: string
  target: string
  /** The tie's kind (the edge label); researcher scripts use "connected" for the network itself. */
  label?: string
  data: Record<string, any>
}

export const GRAPH_ACTIONS = new Set([
  'addNode', 'removeNode', 'nodePropertyChanged', 'nodePropertyRemoved',
  'addLink', 'removeLink', 'linkPropertyChanged', 'linkPropertyRemoved',
])

/** Tie properties arrive as strings (Admin.edgePropertyChanged uses toString). */
function parseValue (v: any) {
  if (typeof v !== 'string') return v
  try { return JSON.parse(v) } catch (e) { return v }
}

export class LiveGraph {
  nodes = new Map<string, GraphNode>()
  links = new Map<string, GraphLink>()
  /** Bumped (once per animation frame at most) whenever anything changed. */
  version = shallowRef(0)
  private pending = false

  private changed () {
    if (this.pending) return
    this.pending = true
    const flush = () => {
      this.pending = false
      this.version.value++
      triggerRef(this.version)
    }
    if (typeof requestAnimationFrame === 'function' && typeof document !== 'undefined' && !document.hidden) {
      requestAnimationFrame(flush)
    } else {
      setTimeout(flush, 16)
    }
  }

  clear () {
    this.nodes.clear()
    this.links.clear()
    this.changed()
  }

  /** Apply one message from the server. Returns false when it wasn't a graph message. */
  apply (m: any): boolean {
    if (!m || !GRAPH_ACTIONS.has(m.action)) return false
    const id = m.id == null ? '' : String(m.id)
    switch (m.action) {
      case 'addNode':
        if (!this.nodes.has(id)) this.nodes.set(id, { id, data: {} })
        break
      case 'removeNode':
        this.nodes.delete(id)
        for (const [lid, l] of this.links) if (l.source === id || l.target === id) this.links.delete(lid)
        break
      case 'nodePropertyChanged': {
        let n = this.nodes.get(id)
        if (!n) { n = { id, data: {} }; this.nodes.set(id, n) }
        // node values arrive typed (models/Admin.java); parsing them again turned "5" into 5
        n.data = { ...n.data, [m.key]: m.value }
        break
      }
      case 'nodePropertyRemoved': {
        const n = this.nodes.get(id)
        if (n && m.key in n.data) {
          const { [m.key]: _gone, ...rest } = n.data
          n.data = rest
        }
        break
      }
      case 'addLink': {
        const source = String(m.source)
        const target = String(m.target)
        // Admin.java sends the edge label as `value`
        const label = typeof m.value === 'string' && m.value ? m.value : undefined
        this.links.set(id, { id, source, target, label, data: (m.value && typeof m.value === 'object') ? { ...m.value } : {} })
        break
      }
      case 'removeLink':
        if (this.links.has(id)) {
          this.links.delete(id)
        } else if (m.source != null && m.target != null) {
          // some engines only name the ends
          for (const [lid, l] of this.links) {
            if ((l.source === String(m.source) && l.target === String(m.target)) || (l.source === String(m.target) && l.target === String(m.source))) {
              this.links.delete(lid)
              break
            }
          }
        }
        break
      case 'linkPropertyChanged': {
        const l = this.links.get(id)
        if (l) l.data = { ...l.data, [m.key]: parseValue(m.value) }
        break
      }
      case 'linkPropertyRemoved': {
        const l = this.links.get(id)
        if (l && m.key in l.data) {
          const { [m.key]: _gone, ...rest } = l.data
          l.data = rest
        }
        break
      }
    }
    this.changed()
    return true
  }

  /** The tie between two people, either way round. */
  linkBetween (a: string, b: string): GraphLink | undefined {
    for (const l of this.links.values()) {
      if ((l.source === a && l.target === b) || (l.source === b && l.target === a)) return l
    }
    return undefined
  }

  degree (id: string): number {
    let d = 0
    for (const l of this.links.values()) if (l.source === id || l.target === id) d++
    return d
  }

  neighbors (id: string): string[] {
    const out: string[] = []
    for (const l of this.links.values()) {
      if (l.source === id) out.push(l.target)
      else if (l.target === id) out.push(l.source)
    }
    return out
  }
}
