/** Colours for people in the network, readable on light and dark backgrounds. */
export const CATEGORICAL = [
  '#6366f1', // indigo
  '#10b981', // emerald
  '#f59e0b', // amber
  '#f43f5e', // rose
  '#0ea5e9', // sky
  '#a855f7', // purple
  '#14b8a6', // teal
  '#f97316', // orange
  '#ec4899', // pink
  '#84cc16', // lime
]
export const NEUTRAL = '#94a3b8'

/** Mix two hex colours, t in [0, 1]. */
export function mix (a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16)
  const pb = parseInt(b.slice(1), 16)
  const ch = (shift: number) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t)
  return '#' + ((1 << 24) + (ch(16) << 16) + (ch(8) << 8) + ch(0)).toString(16).slice(1)
}

export interface ColorScale {
  kind: 'none' | 'categorical' | 'numeric'
  color: (value: any) => string
  legend: { label: string, color: string }[]
}

/** A colour for each value of a property: categories get distinct colours, numbers a gradient. */
export function scaleFor (values: any[]): ColorScale {
  const present = values.filter(v => v !== undefined && v !== null && v !== '')
  if (!present.length) return { kind: 'none', color: () => NEUTRAL, legend: [] }
  const numeric = present.every(v => typeof v === 'number' || (typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v))))
  const distinct = Array.from(new Set(present.map(v => typeof v === 'object' ? JSON.stringify(v) : String(v))))
  if (numeric && distinct.length > CATEGORICAL.length) {
    const nums = present.map(Number)
    const min = Math.min(...nums)
    const max = Math.max(...nums)
    const lo = '#c7d2fe'
    const hi = '#3730a3'
    const color = (v: any) => {
      if (v === undefined || v === null || v === '' || isNaN(Number(v))) return NEUTRAL
      return max === min ? mix(lo, hi, 0.5) : mix(lo, hi, (Number(v) - min) / (max - min))
    }
    const fmt = (n: number) => Number.isInteger(n) ? String(n) : n.toFixed(2)
    return {
      kind: 'numeric',
      color,
      legend: [{ label: fmt(min), color: lo }, { label: fmt((min + max) / 2), color: mix(lo, hi, 0.5) }, { label: fmt(max), color: hi }],
    }
  }
  distinct.sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
  const index = new Map(distinct.map((v, i) => [v, i]))
  const color = (v: any) => {
    if (v === undefined || v === null || v === '') return NEUTRAL
    const i = index.get(typeof v === 'object' ? JSON.stringify(v) : String(v))
    return i === undefined || i >= CATEGORICAL.length ? NEUTRAL : CATEGORICAL[i]
  }
  return {
    kind: 'categorical',
    color,
    legend: distinct.slice(0, CATEGORICAL.length).map((v, i) => ({ label: v, color: CATEGORICAL[i] }))
      .concat(distinct.length > CATEGORICAL.length ? [{ label: `${distinct.length - CATEGORICAL.length} more`, color: NEUTRAL }] : []),
  }
}
