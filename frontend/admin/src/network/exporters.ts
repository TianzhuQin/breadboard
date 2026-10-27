import type { LiveGraph } from '@/lib/graph'
import { escapeXml } from './render'

/**
 * The network as files: JSON (everything, for scripts) and GraphML (for Gephi, igraph, NetworkX,
 * Cytoscape). Positions are included so a picture can be redrawn the same way, and each tie keeps
 * its kind (the edge label, e.g. "connected" or "partner") so layers can be told apart.
 */

export interface ExportContext {
  experiment?: string | null
  session?: string | null
  positions?: Map<string, { x?: number, y?: number }>
}

export function networkJson (graph: LiveGraph, ctx: ExportContext = {}): string {
  const nodes = Array.from(graph.nodes.values()).map(n => {
    const p = ctx.positions?.get(n.id)
    const pos = p && p.x !== undefined && p.y !== undefined ? { x: Math.round(p.x * 10) / 10, y: Math.round(p.y * 10) / 10 } : {}
    return { id: n.id, ...pos, properties: n.data }
  })
  const links = Array.from(graph.links.values()).map(l => ({ id: l.id, source: l.source, target: l.target, label: l.label ?? null, properties: l.data }))
  return JSON.stringify({
    experiment: ctx.experiment ?? null,
    session: ctx.session ?? null,
    exportedAt: new Date().toISOString(),
    nodes,
    links,
  }, null, 2)
}

type KeyType = 'boolean' | 'double' | 'string'
function typeOf (values: any[]): KeyType {
  const present = values.filter(v => v !== undefined && v !== null)
  if (present.length && present.every(v => typeof v === 'boolean')) return 'boolean'
  if (present.length && present.every(v => typeof v === 'number' && isFinite(v))) return 'double'
  return 'string'
}
function asText (v: any): string {
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}
/** GraphML ids must be XML names-ish; keep them readable but safe. */
function keyId (prefix: string, name: string, i: number) {
  return `${prefix}${i}_${name.replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 40)}`
}

/** Large HTML and engine plumbing are left out of GraphML; JSON has everything. */
const GRAPHML_SKIP = new Set(['text', 'choices', 'timers', 'private'])

export function networkGraphml (graph: LiveGraph, ctx: ExportContext = {}): string {
  const nodes = Array.from(graph.nodes.values())
  const links = Array.from(graph.links.values())
  const nodeKeys = new Map<string, any[]>()
  for (const n of nodes) {
    for (const [k, v] of Object.entries(n.data)) {
      if (GRAPHML_SKIP.has(k)) continue
      if (!nodeKeys.has(k)) nodeKeys.set(k, [])
      nodeKeys.get(k)!.push(v)
    }
  }
  const linkKeys = new Map<string, any[]>()
  for (const l of links) {
    for (const [k, v] of Object.entries(l.data)) {
      if (!linkKeys.has(k)) linkKeys.set(k, [])
      linkKeys.get(k)!.push(v)
    }
  }
  const out: string[] = []
  out.push('<?xml version="1.0" encoding="UTF-8"?>')
  out.push('<graphml xmlns="http://graphml.graphdrawing.org/xmlns" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://graphml.graphdrawing.org/xmlns http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd">')
  // the experiment and session as graph data (ids must be NMTOKENs: no spaces or slashes)
  const about: [string, string][] = []
  if (ctx.experiment) about.push(['experiment', ctx.experiment])
  if (ctx.session) about.push(['session', ctx.session])
  for (const [k] of about) out.push(`  <key id="g_${k}" for="graph" attr.name="${k}" attr.type="string"/>`)
  const nIds = new Map<string, { id: string, type: KeyType }>()
  let i = 0
  for (const [k, values] of nodeKeys) {
    const id = keyId('n', k, i++)
    const type = typeOf(values)
    nIds.set(k, { id, type })
    out.push(`  <key id="${escapeXml(id)}" for="node" attr.name="${escapeXml(k)}" attr.type="${type}"/>`)
  }
  // named apart from any `x` and `y` the experiment sets, which readers such as NetworkX would overwrite
  const hasPos = !!ctx.positions
  if (hasPos) {
    out.push('  <key id="layout_x" for="node" attr.name="layout_x" attr.type="double"/>')
    out.push('  <key id="layout_y" for="node" attr.name="layout_y" attr.type="double"/>')
  }
  const eIds = new Map<string, { id: string, type: KeyType }>()
  i = 0
  for (const [k, values] of linkKeys) {
    const id = keyId('e', k, i++)
    const type = typeOf(values)
    eIds.set(k, { id, type })
    out.push(`  <key id="${escapeXml(id)}" for="edge" attr.name="${escapeXml(k)}" attr.type="${type}"/>`)
  }
  // each tie's kind, under another name if a tie property is already called "label"
  const hasLabels = links.some(l => l.label)
  if (hasLabels) {
    const labelName = linkKeys.has('label') ? 'tie_label' : 'label'
    out.push(`  <key id="e_label" for="edge" attr.name="${labelName}" attr.type="string"/>`)
  }
  const name = [ctx.experiment, ctx.session].filter(Boolean).join(' / ')
  out.push('  <graph id="network" edgedefault="undirected">')
  if (name) out.push(`    <desc>${escapeXml(name)}</desc>`)
  for (const [k, v] of about) out.push(`    <data key="g_${k}">${escapeXml(v)}</data>`)
  const value = (type: KeyType, v: any) => type === 'string' ? asText(v) : String(v)
  for (const n of nodes) {
    out.push(`    <node id="${escapeXml(n.id)}">`)
    for (const [k, v] of Object.entries(n.data)) {
      const key = nIds.get(k)
      if (!key || v === undefined || v === null) continue
      out.push(`      <data key="${escapeXml(key.id)}">${escapeXml(value(key.type, v))}</data>`)
    }
    const p = ctx.positions?.get(n.id)
    if (hasPos && p && p.x !== undefined && p.y !== undefined) {
      out.push(`      <data key="layout_x">${Math.round(p.x * 10) / 10}</data>`)
      out.push(`      <data key="layout_y">${Math.round(p.y * 10) / 10}</data>`)
    }
    out.push('    </node>')
  }
  for (const l of links) {
    if (!graph.nodes.has(l.source) || !graph.nodes.has(l.target)) continue
    const data = Object.entries(l.data).filter(([k, v]) => eIds.has(k) && v !== undefined && v !== null)
    if (!data.length && !l.label) {
      out.push(`    <edge id="${escapeXml(l.id)}" source="${escapeXml(l.source)}" target="${escapeXml(l.target)}"/>`)
      continue
    }
    out.push(`    <edge id="${escapeXml(l.id)}" source="${escapeXml(l.source)}" target="${escapeXml(l.target)}">`)
    if (l.label) out.push(`      <data key="e_label">${escapeXml(l.label)}</data>`)
    for (const [k, v] of data) {
      const key = eIds.get(k)!
      out.push(`      <data key="${escapeXml(key.id)}">${escapeXml(value(key.type, v))}</data>`)
    }
    out.push('    </edge>')
  }
  out.push('  </graph>')
  out.push('</graphml>')
  return out.join('\n')
}

/** "network-<experiment>-<session>-2026-10-27-1405.<ext>", safe on every system. */
export function exportFileName (ext: string, ctx: ExportContext = {}, at = new Date()): string {
  const slug = (s?: string | null) => (s || '').normalize('NFKD').replace(/[^\w-]+/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '').slice(0, 40)
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}-${pad(at.getHours())}${pad(at.getMinutes())}`
  return ['network', slug(ctx.experiment), slug(ctx.session), stamp].filter(Boolean).join('-') + '.' + ext
}

/** Hand the researcher a file. */
export function download (data: Blob | string, fileName: string, type = 'application/octet-stream') {
  const blob = typeof data === 'string' ? new Blob([data], { type }) : data
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}
