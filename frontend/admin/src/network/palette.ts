/**
 * Colours for people in the network.
 *
 * Categories use eight hues in a fixed order, stepped separately for the light and the dark
 * canvas (validated for colour-blind separation and contrast against --graph-bg in both modes).
 * Numbers with many distinct values use one blue ramp, light to dark on the light canvas and
 * dark to light on the dark one, so "more" always stands out from the background.
 */
export const CATEGORICAL_LIGHT = ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948']
export const CATEGORICAL_DARK = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767']
/** Kept for callers that only know one palette. */
export const CATEGORICAL = CATEGORICAL_LIGHT
/** "No value" and "other": a grey that reads on both canvases. */
export const NEUTRAL = '#94a3b8'

// blue steps 250 → 700 (light canvas) and 600 → 100 (dark canvas): the ends stay ≥ 2:1 on the canvas
const RAMP_LIGHT = ['#86b6ef', '#6da7ec', '#5598e7', '#3987e5', '#2a78d6', '#256abf', '#1c5cab', '#184f95', '#104281', '#0d366b']
const RAMP_DARK = ['#184f95', '#1c5cab', '#256abf', '#2a78d6', '#3987e5', '#5598e7', '#6da7ec', '#86b6ef', '#9ec5f4', '#cde2fb']

/** Mix two hex colours, t in [0, 1]. */
export function mix (a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16)
  const pb = parseInt(b.slice(1), 16)
  const ch = (shift: number) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t)
  return '#' + ((1 << 24) + (ch(16) << 16) + (ch(8) << 8) + ch(0)).toString(16).slice(1)
}

/** A colour along the ramp, t in [0, 1]. */
export function rampColor (t: number, dark = false): string {
  const ramp = dark ? RAMP_DARK : RAMP_LIGHT
  const x = Math.min(1, Math.max(0, isFinite(t) ? t : 0.5)) * (ramp.length - 1)
  const i = Math.min(ramp.length - 2, Math.floor(x))
  return mix(ramp[i], ramp[i + 1], x - i)
}

export interface LegendEntry { label: string, color: string, count?: number }

export interface ColorScale {
  kind: 'none' | 'categorical' | 'numeric'
  color: (value: any) => string
  /** One row per category (sorted), or three stops (min, middle, max) for numbers. */
  legend: LegendEntry[]
  /** How many people have no value for the property. */
  missing: number
  /** For numbers: the gradient to draw in a legend bar. */
  gradient?: string[]
}

/**
 * d3's category20, which the old console used for a player's `color` property ("1".."20"),
 * so experiments that set it look the way their authors expect.
 */
export const CATEGORY20 = [
  '#1f77b4', '#aec7e8', '#ff7f0e', '#ffbb78', '#2ca02c', '#98df8a', '#d62728', '#ff9896', '#9467bd', '#c5b0d5',
  '#8c564b', '#c49c94', '#e377c2', '#f7b6d2', '#7f7f7f', '#c7c7c7', '#bcbd22', '#dbdb8d', '#17becf', '#9edae5',
]

const HEX = /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i

export interface ScaleOptions {
  /** Colours stepped for the dark canvas. */
  dark?: boolean
  /** The property is the old `color` hook: numbers 1–20 pick category20 colours, hex codes are used as they are. */
  colorHook?: boolean
  /**
   * Which slot each category got before. Pass the same map every time (per property) so a
   * person's colour doesn't change when a new category appears: colour follows the value,
   * never its rank.
   */
  memory?: Map<string, number>
}

function isMissing (v: any) {
  return v === undefined || v === null || v === ''
}
function keyOf (v: any): string {
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}
function isNumberLike (v: any) {
  return typeof v === 'number' ? isFinite(v) : (typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v)))
}
const fmt = (n: number) => Number.isInteger(n) ? String(n) : (Math.abs(n) >= 100 ? n.toFixed(0) : n.toFixed(2))

/** A colour for each value of a property: categories get distinct colours, many numbers a gradient. */
export function scaleFor (values: any[], opts: ScaleOptions = {}): ColorScale {
  const dark = !!opts.dark
  const present = values.filter(v => !isMissing(v))
  const missing = values.length - present.length
  if (!present.length) return { kind: 'none', color: () => NEUTRAL, legend: [], missing }
  const palette = dark ? CATEGORICAL_DARK : CATEGORICAL_LIGHT

  const counts = new Map<string, number>()
  for (const v of present) {
    const k = keyOf(v)
    counts.set(k, (counts.get(k) || 0) + 1)
  }
  const distinct = Array.from(counts.keys())
  const numeric = present.every(isNumberLike)

  // the experiment chose the colours itself
  const own = opts.colorHook && (
    distinct.every(k => HEX.test(k)) ||
    (numeric && distinct.every(k => Number.isInteger(Number(k)) && Number(k) >= 1 && Number(k) <= CATEGORY20.length))
  )
  if (own) {
    const color = (v: any) => {
      if (isMissing(v)) return NEUTRAL
      const k = keyOf(v)
      return HEX.test(k) ? k : (CATEGORY20[Number(k) - 1] || NEUTRAL)
    }
    const sorted = distinct.slice().sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    return { kind: 'categorical', color, missing, legend: sorted.map(k => ({ label: k, color: color(k), count: counts.get(k) })) }
  }

  if (numeric && distinct.length > palette.length) {
    const nums = present.map(Number)
    let min = Infinity
    let max = -Infinity
    for (const n of nums) { if (n < min) min = n; if (n > max) max = n }
    const at = (n: number) => max === min ? 0.5 : (n - min) / (max - min)
    const color = (v: any) => isMissing(v) || !isNumberLike(v) ? NEUTRAL : rampColor(at(Number(v)), dark)
    const mid = (min + max) / 2
    return {
      kind: 'numeric',
      color,
      missing,
      legend: [
        { label: fmt(min), color: rampColor(0, dark) },
        { label: fmt(mid), color: rampColor(0.5, dark) },
        { label: fmt(max), color: rampColor(1, dark) },
      ],
      gradient: [0, 0.25, 0.5, 0.75, 1].map(t => rampColor(t, dark)),
    }
  }

  const sorted = distinct.slice().sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
  const slots = opts.memory || new Map<string, number>()
  const used = new Set(slots.values())
  for (const v of sorted) {
    if (slots.has(v)) continue
    let free = -1
    for (let i = 0; i < palette.length; i++) if (!used.has(i)) { free = i; break }
    if (free < 0) break
    slots.set(v, free)
    used.add(free)
  }
  const slotOf = (k: string) => {
    const i = slots.get(k)
    return i === undefined || i >= palette.length ? -1 : i
  }
  const color = (v: any) => {
    if (isMissing(v)) return NEUTRAL
    const i = slotOf(keyOf(v))
    return i < 0 ? NEUTRAL : palette[i]
  }
  const shown = sorted.filter(k => slotOf(k) >= 0)
  const rest = sorted.filter(k => slotOf(k) < 0)
  const legend: LegendEntry[] = shown.map(k => ({ label: k, color: palette[slotOf(k)], count: counts.get(k) }))
  if (rest.length) {
    legend.push({ label: `${rest.length} more`, color: NEUTRAL, count: rest.reduce((s, k) => s + (counts.get(k) || 0), 0) })
  }
  return { kind: 'categorical', color, legend, missing }
}
