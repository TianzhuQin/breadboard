/**
 * Drawing the network: one scene (everything in screen pixels) drawn on a canvas for the live
 * view and the PNG export, or written out as SVG for the SVG export. Keeping both in one place
 * means the exports look exactly like the screen.
 */

export interface RenderTheme {
  dark: boolean
  bg: string
  grid: string
  text: string
  textSoft: string
  muted: string
  faint: string
  link: string
  accent: string
  accentRing: string
  danger: string
  success: string
  warning: string
  surface: string
  font: string
}

/**
 * The light theme as fixed colours, for exported files (a picture in a paper or a slide can't
 * follow the console's theme). Same values as the light tokens in styles/tokens.css.
 */
export const PAPER_THEME: RenderTheme = {
  dark: false,
  bg: '#ffffff',
  grid: 'rgba(15, 23, 42, 0)',
  text: '#0f172a',
  textSoft: '#334155',
  muted: '#5b6678',
  faint: '#8a94a6',
  link: '#b6bdcb',
  accent: '#4f46e5',
  accentRing: 'rgba(79, 70, 229, 0.25)',
  danger: '#e11d48',
  success: '#059669',
  warning: '#d97706',
  surface: '#ffffff',
  font: 'Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
}

function luminance (color: string): number | null {
  const m = /^#([0-9a-f]{6})$/i.exec(color.trim())
  if (m) {
    const v = parseInt(m[1], 16)
    return (0.2126 * ((v >> 16) & 255) + 0.7152 * ((v >> 8) & 255) + 0.0722 * (v & 255)) / 255
  }
  const r = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/i.exec(color)
  if (r) return (0.2126 * +r[1] + 0.7152 * +r[2] + 0.0722 * +r[3]) / 255
  return null
}

/** The console's current colours (light or dark), read from the design tokens. */
export function readTheme (el: Element | null): RenderTheme {
  const cs = el && typeof getComputedStyle === 'function' ? getComputedStyle(el) : null
  const v = (name: string, fallback: string) => (cs && cs.getPropertyValue(name).trim()) || fallback
  const bg = v('--graph-bg', PAPER_THEME.bg)
  const lum = luminance(bg)
  const dark = lum === null
    ? (typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches)
    : lum < 0.5
  return {
    dark,
    bg,
    grid: v('--graph-grid', 'rgba(15, 23, 42, 0.05)'),
    text: v('--text', PAPER_THEME.text),
    textSoft: v('--text-soft', PAPER_THEME.textSoft),
    muted: v('--muted', PAPER_THEME.muted),
    faint: v('--faint', PAPER_THEME.faint),
    // ties: a quiet line that still reads on either canvas
    link: dark ? '#3d4760' : '#c3c9d6',
    accent: v('--accent', PAPER_THEME.accent),
    accentRing: v('--accent-ring', PAPER_THEME.accentRing),
    danger: v('--danger', PAPER_THEME.danger),
    success: v('--success', PAPER_THEME.success),
    warning: v('--warning', PAPER_THEME.warning),
    surface: v('--surface', PAPER_THEME.surface),
    font: v('--font', PAPER_THEME.font),
  }
}

export interface SceneNode {
  id: string
  x: number
  y: number
  r: number
  fill: string
  /** An AI player: drawn as a ring. */
  ai: boolean
  /** Disconnected: faded with a dashed outline. */
  away: boolean
  selected: boolean
  /** Keyboard focus. */
  focused: boolean
  hover: boolean
  /** The person a new tie starts from (connect mode). */
  from: boolean
  /** The person a new tie would go to (connect mode). */
  target: boolean
  dim: boolean
  /** A search match. */
  match: boolean
  /** The label to draw, or null. */
  label: string | null
  /** 0 → 1 while appearing. */
  pop: number
}
export type LinkKind = 'normal' | 'focus' | 'dim' | 'cut'
export interface SceneLink { id: string, a: string, b: string, x1: number, y1: number, x2: number, y2: number, kind: LinkKind, pop: number }
export interface SceneLine { op: 'add' | 'remove', x1: number, y1: number, x2: number, y2: number }
export interface Scene {
  w: number
  h: number
  nodes: SceneNode[]
  links: SceneLink[]
  pending: SceneLine[]
  rubber: { x1: number, y1: number, x2: number, y2: number, ok: boolean } | null
  /** Animation clock (ms) for the moving dashes. */
  time: number
  /** Dot grid that moves with the view. */
  grid: { k: number, x: number, y: number } | null
  /** Thinner ties for big networks. */
  linkWidth: number
}

export interface LegendBlock {
  title: string
  entries: { label: string, color: string, count?: number }[]
  gradient?: string[]
  missing?: number
}

const LABEL_SIZE = 11.5
const LABEL_GAP = 4

function easeOutBack (t: number) {
  const c1 = 1.4
  const c3 = c1 + 1
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)
}
export function popScale (pop: number) {
  return pop >= 1 ? 1 : Math.max(0, easeOutBack(Math.max(0, pop)))
}

// ---- labels -------------------------------------------------------------------------------------

export interface LabelCandidate { id: string, x: number, y: number, r: number, text: string, priority: number }

/**
 * Which labels to show without overlapping: most important first (selected, hovered, matches,
 * then the best connected), skipping any that would cover one already placed or fall off screen.
 */
export function placeLabels (cands: LabelCandidate[], measure: (text: string) => number, w: number, h: number, all = false): Set<string> {
  const placed: [number, number, number, number][] = []
  const out = new Set<string>()
  const sorted = cands.slice().sort((a, b) => b.priority - a.priority)
  for (const c of sorted) {
    const tw = measure(c.text)
    const x0 = c.x - tw / 2 - 3
    const y0 = c.y + c.r + LABEL_GAP - 1
    const x1 = x0 + tw + 6
    const y1 = y0 + LABEL_SIZE + 5
    if (x1 < 0 || x0 > w || y1 < 0 || y0 > h) continue
    if (!all && c.priority < 100) {
      let hit = false
      for (const p of placed) {
        if (x0 < p[2] && x1 > p[0] && y0 < p[3] && y1 > p[1]) { hit = true; break }
      }
      if (hit) continue
    }
    placed.push([x0, y0, x1, y1])
    out.add(c.id)
  }
  return out
}

// ---- canvas -------------------------------------------------------------------------------------

function circle (ctx: CanvasRenderingContext2D, x: number, y: number, r: number) {
  ctx.beginPath()
  ctx.arc(x, y, Math.max(0.1, r), 0, Math.PI * 2)
}

export function drawScene (ctx: CanvasRenderingContext2D, s: Scene, t: RenderTheme) {
  ctx.save()
  ctx.fillStyle = t.bg
  ctx.fillRect(0, 0, s.w, s.h)

  // dot grid
  if (s.grid) {
    let step = 26 * s.grid.k
    while (step < 14) step *= 2
    while (step > 52) step /= 2
    const ox = ((s.grid.x % step) + step) % step
    const oy = ((s.grid.y % step) + step) % step
    ctx.fillStyle = t.grid
    for (let x = ox; x < s.w; x += step) {
      for (let y = oy; y < s.h; y += step) ctx.fillRect(x - 0.75, y - 0.75, 1.5, 1.5)
    }
  }

  // ties: quiet ones in one stroke, the interesting ones on top
  ctx.lineCap = 'round'
  const strokeGroup = (kind: LinkKind, color: string, width: number, alpha: number) => {
    let any = false
    ctx.beginPath()
    for (const l of s.links) {
      if (l.kind !== kind || l.pop < 1) continue
      ctx.moveTo(l.x1, l.y1)
      ctx.lineTo(l.x2, l.y2)
      any = true
    }
    if (!any) return
    ctx.globalAlpha = alpha
    ctx.strokeStyle = color
    ctx.lineWidth = width
    ctx.stroke()
    ctx.globalAlpha = 1
  }
  strokeGroup('dim', t.link, s.linkWidth, 0.35)
  strokeGroup('normal', t.link, s.linkWidth, 1)
  for (const l of s.links) {
    if (l.pop >= 1) continue
    // a new tie fades in
    ctx.globalAlpha = Math.max(0, l.pop)
    ctx.strokeStyle = l.kind === 'focus' ? t.accent : t.link
    ctx.lineWidth = l.kind === 'focus' ? 2 : s.linkWidth
    ctx.beginPath()
    ctx.moveTo(l.x1, l.y1)
    ctx.lineTo(l.x2, l.y2)
    ctx.stroke()
    ctx.globalAlpha = 1
  }
  strokeGroup('focus', t.accent, Math.max(1.8, s.linkWidth + 0.6), 0.9)
  for (const l of s.links) {
    if (l.kind !== 'cut') continue
    ctx.strokeStyle = t.danger
    ctx.lineWidth = 3
    ctx.setLineDash([7, 5])
    ctx.lineDashOffset = -s.time / 40
    ctx.beginPath()
    ctx.moveTo(l.x1, l.y1)
    ctx.lineTo(l.x2, l.y2)
    ctx.stroke()
    ctx.setLineDash([])
  }

  // edits waiting for the server
  for (const p of s.pending) {
    ctx.strokeStyle = p.op === 'add' ? t.success : t.danger
    ctx.lineWidth = 2.5
    ctx.setLineDash([6, 6])
    ctx.lineDashOffset = -s.time / 30
    ctx.beginPath()
    ctx.moveTo(p.x1, p.y1)
    ctx.lineTo(p.x2, p.y2)
    ctx.stroke()
    ctx.setLineDash([])
  }
  if (s.rubber) {
    ctx.strokeStyle = s.rubber.ok ? t.success : t.accent
    ctx.lineWidth = 2
    ctx.setLineDash([6, 5])
    ctx.beginPath()
    ctx.moveTo(s.rubber.x1, s.rubber.y1)
    ctx.lineTo(s.rubber.x2, s.rubber.y2)
    ctx.stroke()
    ctx.setLineDash([])
  }

  // people: dimmed ones first, highlighted ones last (on top)
  const order = (n: SceneNode) => (n.dim ? 0 : 1) + (n.match || n.hover ? 1 : 0) + (n.selected || n.from || n.target || n.focused ? 2 : 0)
  const nodes = s.nodes.slice().sort((a, b) => order(a) - order(b))
  for (const n of nodes) drawNode(ctx, n, t, s.time)

  // labels on top of everything
  ctx.textAlign = 'center'
  ctx.textBaseline = 'top'
  ctx.lineJoin = 'round'
  for (const n of nodes) {
    if (!n.label) continue
    const strong = n.selected || n.hover || n.from || n.target || n.focused
    ctx.font = `${strong ? 650 : 520} ${LABEL_SIZE}px ${t.font}`
    ctx.globalAlpha = n.dim ? 0.4 : 1
    const y = n.y + n.r * popScale(n.pop) + LABEL_GAP
    ctx.strokeStyle = t.bg
    ctx.lineWidth = 3.5
    ctx.strokeText(n.label, n.x, y)
    ctx.fillStyle = strong ? t.text : t.textSoft
    ctx.fillText(n.label, n.x, y)
    ctx.globalAlpha = 1
  }
  ctx.restore()
}

function drawNode (ctx: CanvasRenderingContext2D, n: SceneNode, t: RenderTheme, time: number) {
  const r = n.r * popScale(n.pop)
  if (r <= 0.2) return
  const alpha = n.dim ? 0.22 : (n.away ? 0.5 : 1)
  ctx.globalAlpha = alpha

  if (n.selected || n.from) {
    // a soft halo and a crisp ring
    circle(ctx, n.x, n.y, r + 7)
    ctx.fillStyle = n.from ? t.success : t.accentRing
    if (n.from) ctx.globalAlpha = alpha * 0.22
    ctx.fill()
    ctx.globalAlpha = alpha
    circle(ctx, n.x, n.y, r + 3.5)
    ctx.strokeStyle = n.from ? t.success : t.accent
    ctx.lineWidth = 2
    ctx.stroke()
  } else if (n.target) {
    circle(ctx, n.x, n.y, r + 3.5)
    ctx.strokeStyle = t.success
    ctx.lineWidth = 2.5
    ctx.stroke()
  } else if (n.match) {
    circle(ctx, n.x, n.y, r + 3.5)
    ctx.strokeStyle = t.warning
    ctx.lineWidth = 2
    ctx.stroke()
  }

  const rr = n.hover ? r * 1.12 : r
  circle(ctx, n.x, n.y, rr)
  if (n.ai) {
    // AI players are rings in their colour
    ctx.fillStyle = t.bg
    ctx.fill()
    ctx.strokeStyle = n.fill
    ctx.lineWidth = Math.max(2, rr * 0.42)
    circle(ctx, n.x, n.y, rr - ctx.lineWidth / 2)
    ctx.stroke()
  } else {
    ctx.fillStyle = n.fill
    ctx.fill()
    // a thin ring in the canvas colour keeps touching dots apart
    ctx.strokeStyle = t.bg
    ctx.lineWidth = 1.5
    ctx.stroke()
  }
  if (n.away) {
    circle(ctx, n.x, n.y, rr + 2.5)
    ctx.setLineDash([2.5, 2.5])
    ctx.strokeStyle = t.muted
    ctx.lineWidth = 1.2
    ctx.stroke()
    ctx.setLineDash([])
  }
  if (n.focused) {
    circle(ctx, n.x, n.y, rr + 6)
    ctx.setLineDash([4, 3])
    ctx.lineDashOffset = -time / 60
    ctx.strokeStyle = t.accent
    ctx.lineWidth = 2
    ctx.stroke()
    ctx.setLineDash([])
  }
  ctx.globalAlpha = 1
}

/** A legend box in the bottom-left corner of an exported picture. */
export function drawLegend (ctx: CanvasRenderingContext2D, legend: LegendBlock, t: RenderTheme, h: number) {
  const rows = legendRows(legend)
  const x = 24
  const lineH = 20
  const boxH = 30 + rows.length * lineH
  const y = h - 24 - boxH
  ctx.save()
  ctx.font = `600 12px ${t.font}`
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'left'
  ctx.fillStyle = t.text
  ctx.fillText(legend.title, x, y + 10)
  ctx.font = `500 12px ${t.font}`
  rows.forEach((row, i) => {
    const cy = y + 32 + i * lineH
    if (row.gradient) {
      const g = ctx.createLinearGradient(x, 0, x + 120, 0)
      row.gradient.forEach((c, j) => g.addColorStop(j / (row.gradient!.length - 1), c))
      ctx.fillStyle = g
      ctx.fillRect(x, cy - 5, 120, 10)
      ctx.fillStyle = t.textSoft
      ctx.fillText(row.label, x + 130, cy)
    } else {
      circle(ctx, x + 6, cy, 5.5)
      ctx.fillStyle = row.color!
      ctx.fill()
      ctx.fillStyle = t.textSoft
      ctx.fillText(row.label, x + 18, cy)
    }
  })
  ctx.restore()
}

function legendRows (legend: LegendBlock): { label: string, color?: string, gradient?: string[] }[] {
  const rows: { label: string, color?: string, gradient?: string[] }[] = []
  if (legend.gradient && legend.entries.length >= 2) {
    rows.push({ label: `${legend.entries[0].label} – ${legend.entries[legend.entries.length - 1].label}`, gradient: legend.gradient })
  } else {
    for (const e of legend.entries) rows.push({ label: e.count != null ? `${e.label} (${e.count})` : e.label, color: e.color })
  }
  if (legend.missing) rows.push({ label: `Not set (${legend.missing})`, color: '#94a3b8' })
  return rows
}

// ---- SVG ----------------------------------------------------------------------------------------

/** Characters XML 1.0 doesn't allow at all (control characters, U+FFFE/U+FFFF, lone surrogates). */
const XML_ILLEGAL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g

/** Text made safe for XML: special characters escaped, characters XML can't hold dropped. */
export function escapeXml (s: string): string {
  return s.replace(XML_ILLEGAL, '').replace(/[<>&"']/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', '\'': '&apos;' }[c]!))
}
const f1 = (n: number) => (Math.round(n * 10) / 10).toString()

/** The scene as a standalone SVG document. */
export function sceneToSvg (s: Scene, t: RenderTheme, opts: { title?: string, legend?: LegendBlock | null } = {}): string {
  const out: string[] = []
  out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${s.w}" height="${s.h}" viewBox="0 0 ${s.w} ${s.h}" font-family="${escapeXml(t.font)}">`)
  if (opts.title) out.push(`<title>${escapeXml(opts.title)}</title>`)
  out.push(`<rect width="100%" height="100%" fill="${t.bg}"/>`)
  out.push(`<g stroke="${t.link}" stroke-width="${s.linkWidth}" stroke-linecap="round">`)
  for (const l of s.links) {
    const color = l.kind === 'focus' ? ` stroke="${t.accent}"` : ''
    out.push(`<line x1="${f1(l.x1)}" y1="${f1(l.y1)}" x2="${f1(l.x2)}" y2="${f1(l.y2)}"${color}/>`)
  }
  out.push('</g>')
  out.push('<g>')
  for (const n of s.nodes) {
    const id = escapeXml(n.id)
    const op = n.away ? ' opacity="0.5"' : ''
    if (n.selected) out.push(`<circle cx="${f1(n.x)}" cy="${f1(n.y)}" r="${f1(n.r + 3.5)}" fill="none" stroke="${t.accent}" stroke-width="2"/>`)
    if (n.ai) {
      const w = Math.max(2, n.r * 0.42)
      out.push(`<circle data-id="${id}" cx="${f1(n.x)}" cy="${f1(n.y)}" r="${f1(n.r - w / 2)}" fill="${t.bg}" stroke="${n.fill}" stroke-width="${f1(w)}"${op}><title>${id} (AI)</title></circle>`)
    } else {
      out.push(`<circle data-id="${id}" cx="${f1(n.x)}" cy="${f1(n.y)}" r="${f1(n.r)}" fill="${n.fill}" stroke="${t.bg}" stroke-width="1.5"${op}><title>${id}</title></circle>`)
    }
  }
  out.push('</g>')
  out.push(`<g font-size="${LABEL_SIZE}" font-weight="520" text-anchor="middle" fill="${t.textSoft}" stroke="${t.bg}" stroke-width="3.5" stroke-linejoin="round" paint-order="stroke">`)
  for (const n of s.nodes) {
    if (!n.label) continue
    out.push(`<text x="${f1(n.x)}" y="${f1(n.y + n.r + LABEL_GAP + LABEL_SIZE * 0.85)}">${escapeXml(n.label)}</text>`)
  }
  out.push('</g>')
  if (opts.legend) {
    const rows = legendRows(opts.legend)
    const lineH = 20
    const y = s.h - 24 - (30 + rows.length * lineH)
    out.push(`<g font-size="12" fill="${t.textSoft}">`)
    out.push(`<text x="24" y="${y + 14}" font-weight="600" fill="${t.text}">${escapeXml(opts.legend.title)}</text>`)
    rows.forEach((row, i) => {
      const cy = y + 32 + i * lineH
      if (row.gradient) {
        out.push(`<defs><linearGradient id="bb-legend-ramp">${row.gradient.map((c, j) => `<stop offset="${j / (row.gradient!.length - 1)}" stop-color="${c}"/>`).join('')}</linearGradient></defs>`)
        out.push(`<rect x="24" y="${cy - 5}" width="120" height="10" fill="url(#bb-legend-ramp)"/>`)
        out.push(`<text x="154" y="${cy + 4}">${escapeXml(row.label)}</text>`)
      } else {
        out.push(`<circle cx="30" cy="${cy}" r="5.5" fill="${row.color}"/>`)
        out.push(`<text x="42" y="${cy + 4}">${escapeXml(row.label)}</text>`)
      }
    })
    out.push('</g>')
  }
  out.push('</svg>')
  return out.join('\n')
}
