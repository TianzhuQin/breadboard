<template>
  <div
    ref="stage"
    class="an"
    :class="[`an--${mode}`, { 'is-panning': panning, 'is-empty': !nodeCount }]"
    tabindex="0"
    role="application"
    aria-roledescription="network"
    :aria-label="ariaSummary"
    :aria-describedby="helpId"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerCancel"
    @pointerleave="onPointerLeave"
    @wheel.prevent="onWheel"
    @dblclick="onDblClick"
    @keydown="onKey"
    @focus="onFocus"
    @blur="onBlur"
    @contextmenu="onContextMenu"
  >
    <canvas ref="canvas" class="an__canvas" aria-hidden="true" />

    <p :id="helpId" class="sr-only">{{ helpText }}</p>
    <div class="sr-only" aria-live="polite" aria-atomic="true">{{ announce }}</div>

    <div v-if="!nodeCount" class="an__empty" @pointerdown.stop @dblclick.stop>
      <slot name="empty">
        <p>No one is in this session yet.</p>
      </slot>
    </div>

    <Transition name="an-fade" mode="out-in">
      <div v-if="hint" :key="hint" class="an__hint" aria-hidden="true">{{ hint }}</div>
    </Transition>

    <div
      v-if="tooltip"
      class="an__tip"
      :class="{ 'is-below': tipPos.below }"
      :style="{ left: `${tipPos.x}px`, top: `${tipPos.y}px` }"
      aria-hidden="true"
    >
      <div class="an__tip-title">
        <span v-if="tooltip.color" class="an__tip-dot" :class="{ 'is-ring': tooltip.ai }" :style="{ '--dot': tooltip.color }" />
        <span class="an__tip-name">{{ tooltip.title }}</span>
      </div>
      <div v-if="tooltip.sub" class="an__tip-sub">{{ tooltip.sub }}</div>
      <div v-if="tooltip.note" class="an__tip-note">{{ tooltip.note }}</div>
      <div v-for="row in tooltip.rows" :key="row[0]" class="an__tip-row"><span>{{ row[0] }}</span><b>{{ row[1] }}</b></div>
    </div>

    <div v-if="controls" class="an__zoom" @pointerdown.stop @dblclick.stop>
      <button type="button" class="an__zoom-btn" aria-label="Zoom in" title="Zoom in (+)" @click="zoomBy(1.3)"><Plus :size="16" /></button>
      <button type="button" class="an__zoom-btn" aria-label="Zoom out" title="Zoom out (−)" @click="zoomBy(1 / 1.3)"><Minus :size="16" /></button>
      <button type="button" class="an__zoom-btn" aria-label="Fit everyone in view" title="Fit everyone in view (0)" @click="fit(true)"><Maximize :size="15" /></button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { Plus, Minus, Maximize } from 'lucide-vue-next'
import type { LiveGraph } from '@/lib/graph'
import { uid } from '@/ui/uid'
import { scaleFor, NEUTRAL, type ColorScale, type LegendEntry } from './palette'
import { layoutFor, type LayoutNode } from './layout'
import {
  drawScene, drawLegend, placeLabels, readTheme, sceneToSvg, PAPER_THEME,
  type RenderTheme, type Scene, type SceneNode, type SceneLink, type LegendBlock, type LabelCandidate,
} from './render'
import { NOISY_KEYS, formatValue, isAi, connectionOf, labelOf as labelFor, propertyTitle, statusOf, valueOf } from './players'

export type NetworkMode = 'view' | 'connect' | 'cut'
export interface PendingEdit { op: 'add' | 'remove', a: string, b: string }

/**
 * The live network of the session, drawn on a canvas so hundreds of people stay smooth.
 *
 * View mode: click to select, drag people to move them (they stay where you put them), drag the
 * background to pan, wheel or pinch to zoom, double-click to zoom (Shift: out).
 * Connect mode: click one person then another (or drag from one to the other) to propose a tie.
 * Cut mode: click a tie to propose cutting it.
 * Edits are only proposed (`edit` event); the page sends them and passes them back as `pending`.
 * Keyboard: arrows move between people, Enter selects (or picks, in connect mode), +/− zoom,
 * 0 fits, Shift+arrows pan.
 */
const props = withDefaults(defineProps<{
  graph: LiveGraph
  mode?: NetworkMode
  selectedId?: string | null
  /** A property (or '@ties' / '@status') to colour people by. */
  colorBy?: string | null
  /** A property to name people by (defaults to their id). */
  labelBy?: string | null
  sizeByDegree?: boolean
  showAllLabels?: boolean
  pending?: PendingEdit[]
  /** Ids to emphasise (e.g. search matches); everyone else fades. */
  highlight?: string[] | null
  /** Stop the layout from moving (dragging still moves the one person). */
  frozen?: boolean
  /** Zoom buttons in the corner. */
  controls?: boolean
}>(), {
  mode: 'view', selectedId: null, colorBy: null, labelBy: null, sizeByDegree: true, showAllLabels: false,
  pending: () => [], highlight: null, frozen: false, controls: true,
})

const emit = defineEmits<{
  select: [id: string | null]
  edit: [edit: PendingEdit]
  legend: [legend: LegendEntry[], scale: ColorScale | null]
  notice: [text: string]
}>()

const helpId = uid('an-help')
const stage = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
let ctx: CanvasRenderingContext2D | null = null
const size = reactive({ w: 0, h: 0 })
let dpr = 1
const view = reactive({ k: 1, x: 0, y: 0 })
let theme: RenderTheme = PAPER_THEME
const themeTick = ref(0)

const hoverNode = ref<string | null>(null)
const hoverLink = shallowRef<{ id: string, a: string, b: string } | null>(null)
const connectFrom = ref<string | null>(null)
/** The person a tie is being dragged from (connect mode). */
const dragTie = ref<string | null>(null)
const kbFocus = ref<string | null>(null)
const showKbFocus = ref(false)
const panning = ref(false)
const announce = ref('')
const tipPos = reactive({ x: 0, y: 0, below: false })
const pointer = { x: 0, y: 0, inside: false, type: 'mouse' as string }

let drag: { id: string, pointerId: number, startX: number, startY: number, moved: boolean, wasPinned: boolean } | null = null
let pan: { pointerId: number, startX: number, startY: number, vx: number, vy: number, moved: boolean } | null = null
let linkDown: { id: string, a: string, b: string, pointerId: number, startX: number, startY: number } | null = null
const pointers = new Map<number, { x: number, y: number }>()
let pinch: { dist: number, midX: number, midY: number, k: number, x: number, y: number } | null = null
let lastClick = { id: '', at: 0 }

const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
const layout = layoutFor(props.graph)

// ---- colours, labels ----------------------------------------------------------------------------

const memories = new Map<string, Map<string, number>>()
const degreeOf = (id: string) => layout.degree(id)

const nodeCount = computed(() => { void props.graph.version.value; return props.graph.nodes.size })

const scale = computed<ColorScale | null>(() => {
  void props.graph.version.value
  void themeTick.value
  const key = props.colorBy
  if (!key) return null
  let mem = memories.get(key)
  if (!mem) { mem = new Map(); memories.set(key, mem) }
  // counted here rather than read from the layout, which may not have caught up with this change yet
  let degree = degreeOf
  if (key === '@ties') {
    const seen = new Map<string, Set<string>>()
    for (const l of props.graph.links.values()) {
      if (l.source === l.target) continue
      if (!seen.has(l.source)) seen.set(l.source, new Set())
      if (!seen.has(l.target)) seen.set(l.target, new Set())
      seen.get(l.source)!.add(l.target)
      seen.get(l.target)!.add(l.source)
    }
    degree = (id: string) => seen.get(id)?.size || 0
  }
  const values = Array.from(props.graph.nodes.values()).map(n => valueOf(n, key, degree))
  return scaleFor(values, { dark: theme.dark, memory: mem, colorHook: key === 'color' })
})

let lastLegend = ''
watch(scale, (s) => {
  const entries = s ? s.legend : []
  const sig = JSON.stringify([s?.kind, entries, s?.missing])
  if (sig === lastLegend) return
  lastLegend = sig
  emit('legend', entries, s)
}, { immediate: true })

function fillOf (id: string, s: ColorScale | null, t: RenderTheme): string {
  const n = props.graph.nodes.get(id)
  if (!n) return NEUTRAL
  if (s && props.colorBy) return s.color(valueOf(n, props.colorBy, degreeOf))
  return t.accent
}
function nameOf (id: string): string {
  return labelFor(props.graph.nodes.get(id) || { id, data: {} }, props.labelBy, degreeOf)
}
const colorTitle = propertyTitle

// label widths, measured once per text
const widths = new Map<string, number>()
function measure (text: string): number {
  let w = widths.get(text)
  if (w === undefined) {
    if (ctx) {
      ctx.font = `520 11.5px ${theme.font}`
      w = ctx.measureText(text).width
    } else {
      w = text.length * 6.6
    }
    widths.set(text, w)
    if (widths.size > 5000) widths.clear()
  }
  return w
}

// ---- coordinates --------------------------------------------------------------------------------

/** Dots grow more slowly than distances when zooming, so a zoomed-out crowd stays readable. */
function radiusFactor (k: number) {
  return Math.min(2.2, Math.max(0.45, Math.sqrt(k)))
}
function toWorld (sx: number, sy: number) {
  return { x: (sx - view.x) / view.k, y: (sy - view.y) / view.k }
}
function toScreen (n: { x?: number, y?: number }, v = view) {
  return { x: (n.x || 0) * v.k + v.x, y: (n.y || 0) * v.k + v.y }
}
function localPoint (e: { clientX: number, clientY: number }) {
  const rect = stage.value!.getBoundingClientRect()
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}

function nodeAt (sx: number, sy: number, slop = 4): string | null {
  const rf = radiusFactor(view.k)
  let best: string | null = null
  let bestD = Infinity
  for (const n of layout.nodes.values()) {
    const p = toScreen(n)
    const d = Math.hypot(p.x - sx, p.y - sy)
    if (d <= n.r * rf + slop && d < bestD) { best = n.id; bestD = d }
  }
  return best
}
function linkAt (sx: number, sy: number, tol = 7): { id: string, a: string, b: string } | null {
  let best: { id: string, a: string, b: string } | null = null
  let bestD = tol
  for (const l of layout.links) {
    const p = toScreen(l.source)
    const q = toScreen(l.target)
    const dx = q.x - p.x
    const dy = q.y - p.y
    const len2 = dx * dx + dy * dy
    const t = len2 ? Math.max(0, Math.min(1, ((sx - p.x) * dx + (sy - p.y) * dy) / len2)) : 0
    const d = Math.hypot(sx - (p.x + t * dx), sy - (p.y + t * dy))
    if (d <= bestD) { bestD = d; best = { id: l.id, a: l.source.id, b: l.target.id } }
  }
  return best
}

// ---- the scene ----------------------------------------------------------------------------------

const highlightSet = computed(() => props.highlight ? new Set(props.highlight) : null)

function focusSet (): Set<string> | null {
  if (props.mode === 'connect') return null
  const id = hoverNode.value || props.selectedId
  if (!id || !layout.nodes.has(id)) return null
  return new Set([id, ...(layout.adjacency.get(id) || [])])
}

interface BuildOptions {
  v: { k: number, x: number, y: number }
  w: number
  h: number
  t: RenderTheme
  time: number
  live: boolean
  colors: ColorScale | null
  allLabels: boolean
}

function buildScene (o: BuildOptions): Scene {
  const rf = radiusFactor(o.v.k)
  const focus = o.live ? focusSet() : null
  const hl = o.live ? highlightSet.value : null
  const focusId = o.live && props.mode !== 'connect' ? (hoverNode.value || props.selectedId) : null
  const from = o.live && props.mode === 'connect' ? connectFrom.value : null
  const dragFrom = o.live && props.mode === 'connect' && drag && drag.moved ? drag.id : null
  const tieStart = from || dragFrom
  const target = tieStart && hoverNode.value && hoverNode.value !== tieStart ? hoverNode.value : null

  const nodes: SceneNode[] = []
  const pos = new Map<string, { x: number, y: number, r: number }>()
  const cands: LabelCandidate[] = []
  const many = layout.nodes.size > 80 && o.v.k < 0.9
  for (const n of layout.nodes.values()) {
    const p = toScreen(n, o.v)
    const r = n.r * rf
    pos.set(n.id, { x: p.x, y: p.y, r })
    const g = props.graph.nodes.get(n.id)
    if (!g) continue
    const offscreen = p.x < -40 || p.y < -40 || p.x > o.w + 40 || p.y > o.h + 40
    if (offscreen && o.live) continue
    const sn: SceneNode = {
      id: n.id,
      x: p.x,
      y: p.y,
      r,
      fill: fillOf(n.id, o.colors, o.t),
      ai: isAi(g),
      away: connectionOf(g) === 'disconnected' && !isAi(g),
      selected: o.live && n.id === props.selectedId,
      focused: o.live && showKbFocus.value && n.id === kbFocus.value,
      hover: o.live && n.id === hoverNode.value,
      from: !!tieStart && n.id === tieStart,
      target: !!target && n.id === target,
      // search matches stay bright next to the selected person's circle
      dim: hl ? !hl.has(n.id) && !(focus && focus.has(n.id)) : !!(focus && !focus.has(n.id)),
      match: !!(hl && hl.has(n.id)),
      label: null,
      pop: o.live && !reducedMotion ? Math.min(1, (o.time - n.born) / 420) : 1,
    }
    nodes.push(sn)
    let priority = 10 + Math.min(80, n.degree * 4)
    if (sn.selected) priority = 1000
    else if (sn.hover) priority = 950
    else if (sn.from || sn.target) priority = 900
    else if (sn.focused) priority = 850
    else if (sn.match) priority = 500
    if (o.allLabels || !many || priority >= 500) {
      cands.push({ id: n.id, x: p.x, y: p.y, r, text: nameOf(n.id), priority })
    }
  }
  const shown = placeLabels(cands, measure, o.w, o.h, o.allLabels)
  for (const sn of nodes) if (shown.has(sn.id)) sn.label = nameOf(sn.id)

  const links: SceneLink[] = []
  const cut = o.live && props.mode === 'cut' ? hoverLink.value : null
  for (const l of layout.links) {
    const a = pos.get(l.source.id)
    const b = pos.get(l.target.id)
    if (!a || !b) continue
    let kind: SceneLink['kind'] = 'normal'
    const touches = !!focusId && (l.source.id === focusId || l.target.id === focusId)
    if (cut && cut.id === l.id) kind = 'cut'
    else if (touches) kind = 'focus'
    else if (hl ? !(hl.has(l.source.id) && hl.has(l.target.id)) : (focus && !touches)) kind = 'dim'
    links.push({
      id: l.id, a: l.source.id, b: l.target.id, x1: a.x, y1: a.y, x2: b.x, y2: b.y, kind,
      pop: o.live && !reducedMotion ? Math.min(1, (o.time - l.born) / 380) : 1,
    })
  }

  const pending = o.live
    ? props.pending.map(e => {
      const a = pos.get(e.a)
      const b = pos.get(e.b)
      return a && b ? { op: e.op, x1: a.x, y1: a.y, x2: b.x, y2: b.y } : null
    }).filter((x): x is NonNullable<typeof x> => !!x)
    : []

  let rubber: Scene['rubber'] = null
  if (tieStart && (pointer.inside || drag)) {
    const a = pos.get(tieStart)
    if (a) {
      const b = target ? pos.get(target) : null
      const ok = !!target && !props.graph.linkBetween(tieStart, target)
      rubber = { x1: a.x, y1: a.y, x2: b ? b.x : pointer.x, y2: b ? b.y : pointer.y, ok }
    }
  }

  const linkCount = layout.links.length
  return {
    w: o.w,
    h: o.h,
    nodes,
    links,
    pending,
    rubber,
    time: o.time,
    grid: o.live ? { k: o.v.k, x: o.v.x, y: o.v.y } : null,
    linkWidth: linkCount > 800 ? 0.8 : linkCount > 250 ? 1.1 : 1.4,
  }
}

// ---- drawing loop -------------------------------------------------------------------------------

let raf = 0
let mounted = false
let fitWhenCalm = false
let anim: { from: { k: number, x: number, y: number }, to: { k: number, x: number, y: number }, start: number, dur: number } | null = null

function invalidate () {
  if (!raf && mounted) raf = requestAnimationFrame(frame)
}

function frame (time: number) {
  raf = 0
  let again = false
  if (!props.frozen && !reducedMotion && layout.moving) {
    layout.step()
    again = layout.moving
  }
  if (fitWhenCalm && layout.follow && (props.frozen || layout.alpha < 0.12 || !layout.moving)) {
    fitWhenCalm = false
    fit(true, true)
  }
  if (anim) {
    const t = Math.min(1, (time - anim.start) / anim.dur)
    const e = 1 - Math.pow(1 - t, 3)
    view.k = anim.from.k + (anim.to.k - anim.from.k) * e
    view.x = anim.from.x + (anim.to.x - anim.from.x) * e
    view.y = anim.from.y + (anim.to.y - anim.from.y) * e
    if (t >= 1) anim = null
    else again = true
  }
  if (!reducedMotion && (props.pending.length || (props.mode === 'cut' && hoverLink.value))) again = true
  draw(time)
  if (!reducedMotion && !again) {
    // keep going while someone is popping in
    const fresh = time - 450
    for (const n of layout.nodes.values()) if (n.born > fresh) { again = true; break }
    if (!again) for (const l of layout.links) if (l.born > fresh) { again = true; break }
  }
  if (again) invalidate()
}

function draw (time: number) {
  if (!ctx || !size.w || !size.h) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  const scene = buildScene({ v: view, w: size.w, h: size.h, t: theme, time, live: true, colors: scale.value, allLabels: props.showAllLabels })
  drawScene(ctx, scene, theme)
  // keep the tooltip on its person while the layout moves
  const id = hoverNode.value
  const n = id ? layout.nodes.get(id) : null
  if (n) {
    const p = toScreen(n)
    const r = n.r * radiusFactor(view.k)
    tipPos.below = p.y - r < 120
    tipPos.x = Math.max(110, Math.min(size.w - 110, p.x))
    tipPos.y = tipPos.below ? p.y + r + 12 : p.y - r - 12
  } else if (hoverLink.value) {
    tipPos.below = pointer.y < 90
    tipPos.x = Math.max(110, Math.min(size.w - 110, pointer.x))
    tipPos.y = tipPos.below ? pointer.y + 20 : pointer.y - 14
  }
}

// ---- view: zoom, pan, fit -----------------------------------------------------------------------

const K_MIN = 0.06
const K_MAX = 8

function setView (target: { k: number, x: number, y: number }, animate: boolean) {
  if (!animate || reducedMotion) {
    anim = null
    Object.assign(view, target)
  } else {
    anim = { from: { ...view }, to: target, start: performance.now(), dur: 380 }
  }
  invalidate()
}
function zoomAt (factor: number, sx: number, sy: number, animate = false) {
  const k = Math.min(K_MAX, Math.max(K_MIN, view.k * factor))
  const w = toWorld(sx, sy)
  layout.follow = false
  setView({ k, x: sx - w.x * k, y: sy - w.y * k }, animate)
}
function zoomBy (factor: number) {
  zoomAt(factor, size.w / 2, size.h / 2, true)
}

/** The view that shows everyone. */
function fitView (w: number, h: number, pad: { top: number, right: number, bottom: number, left: number }) {
  const b = layout.bounds()
  if (!b) return { k: 1, x: w / 2, y: h / 2 }
  const bw = Math.max(160, b.maxX - b.minX)
  const bh = Math.max(160, b.maxY - b.minY)
  const aw = Math.max(40, w - pad.left - pad.right)
  const ah = Math.max(40, h - pad.top - pad.bottom)
  const k = Math.min(1.8, Math.max(K_MIN, Math.min(aw / bw, ah / bh)))
  const cx = (b.minX + b.maxX) / 2
  const cy = (b.minY + b.maxY) / 2
  return { k, x: pad.left + aw / 2 - cx * k, y: pad.top + ah / 2 - cy * k }
}
/** Fit everyone in view; the view then follows the network as it grows, until you pan or zoom. */
function fit (animate = false, keepFollow = false) {
  if (!size.w || !size.h) return
  const pad = Math.min(64, Math.max(28, Math.min(size.w, size.h) * 0.08))
  setView(fitView(size.w, size.h, { top: pad + 8, right: pad, bottom: pad + 16, left: pad }), animate)
  if (!keepFollow) layout.follow = true
}

/** Centre the view on someone (e.g. a search result) and zoom in a little. */
function focusOn (id: string) {
  const n = layout.nodes.get(id)
  if (!n || !size.w) return
  layout.follow = false
  const k = Math.max(view.k, Math.min(1.6, Math.max(1, view.k * 1.4)))
  setView({ k, x: size.w / 2 - (n.x || 0) * k, y: size.h / 2 - (n.y || 0) * k }, true)
}
/**
 * Pan to show someone if they are off screen, or hidden under something covering part of the view
 * (`cover` pixels from the right or the bottom, e.g. a floating panel or a phone's bottom sheet).
 */
function reveal (id: string, cover: { right?: number, bottom?: number } = {}) {
  const n = layout.nodes.get(id)
  if (!n || !size.w) return
  const p = toScreen(n)
  const m = 48
  const w = Math.max(80, size.w - (cover.right || 0))
  const h = Math.max(80, size.h - (cover.bottom || 0))
  if (p.x >= m && p.y >= m && p.x <= w - m && p.y <= h - m) return
  layout.follow = false
  setView({ k: view.k, x: w / 2 - (n.x || 0) * view.k, y: h / 2 - (n.y || 0) * view.k }, true)
}
/** Free everyone you pinned and lay the network out afresh. */
function relayout () {
  layout.unpinAll()
  layout.reheat(1)
  if (reducedMotion) layout.settle(150, 600)
  layout.follow = true
  fitWhenCalm = true
  invalidate()
}

function onWheel (e: WheelEvent) {
  const p = localPoint(e)
  layout.follow = false
  anim = null
  if (e.ctrlKey || e.metaKey) {
    // a trackpad pinch arrives as a wheel event with ctrlKey
    zoomAt(Math.exp(-e.deltaY * 0.01), p.x, p.y)
  } else if (e.deltaMode === 0 && (Math.abs(e.deltaX) > 0 || Math.abs(e.deltaY) < 40)) {
    // two-finger scrolling on a trackpad moves the picture
    view.x -= e.deltaX
    view.y -= e.deltaY
    invalidate()
  } else {
    // a mouse wheel zooms around the pointer
    const lines = e.deltaMode === 1 ? 40 : 1
    zoomAt(Math.exp(-Math.sign(e.deltaY) * Math.min(120, Math.abs(e.deltaY) * lines) * 0.0022), p.x, p.y)
  }
}

// ---- pointer ------------------------------------------------------------------------------------

function setCursor (c: string) {
  if (stage.value && stage.value.style.cursor !== c) stage.value.style.cursor = c
}
function updateHover (p: { x: number, y: number }) {
  const touch = pointer.type === 'touch'
  const node = touch ? null : nodeAt(p.x, p.y)
  const link = !touch && !node && props.mode === 'cut' ? linkAt(p.x, p.y) : null
  if (node !== hoverNode.value) hoverNode.value = node
  if ((link?.id || null) !== (hoverLink.value?.id || null)) hoverLink.value = link
  if (props.mode === 'connect') setCursor(node ? 'pointer' : 'crosshair')
  else if (node) setCursor('pointer')
  else if (link) setCursor('pointer')
  else setCursor('grab')
}

function onPointerDown (e: PointerEvent) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  pointer.type = e.pointerType
  showKbFocus.value = false
  pointerFocus = true
  stage.value?.focus({ preventScroll: true })
  pointerFocus = false
  const p = localPoint(e)
  pointer.x = p.x
  pointer.y = p.y
  pointer.inside = true
  pointers.set(e.pointerId, p)
  try { stage.value?.setPointerCapture(e.pointerId) } catch (err) { /* not capturable */ }
  if (pointers.size === 2) {
    // two fingers: pinch to zoom, cancel whatever one finger started
    const [a, b] = Array.from(pointers.values())
    pinch = { dist: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), midX: (a.x + b.x) / 2, midY: (a.y + b.y) / 2, k: view.k, x: view.x, y: view.y }
    drag = null
    pan = null
    linkDown = null
    panning.value = false
    return
  }
  if (pointers.size > 2) return
  const slop = e.pointerType === 'touch' ? 10 : 4
  const hit = nodeAt(p.x, p.y, slop)
  if (hit) {
    const n = layout.nodes.get(hit)!
    drag = { id: hit, pointerId: e.pointerId, startX: p.x, startY: p.y, moved: false, wasPinned: n.fx != null }
    return
  }
  if (props.mode === 'cut') {
    const l = linkAt(p.x, p.y, e.pointerType === 'touch' ? 12 : 7)
    if (l) {
      linkDown = { ...l, pointerId: e.pointerId, startX: p.x, startY: p.y }
      return
    }
  }
  pan = { pointerId: e.pointerId, startX: p.x, startY: p.y, vx: view.x, vy: view.y, moved: false }
}

function onPointerMove (e: PointerEvent) {
  const p = localPoint(e)
  pointer.x = p.x
  pointer.y = p.y
  pointer.inside = true
  if (pointers.has(e.pointerId)) pointers.set(e.pointerId, p)

  if (pinch && pointers.size >= 2) {
    const [a, b] = Array.from(pointers.values())
    const dist = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y))
    const midX = (a.x + b.x) / 2
    const midY = (a.y + b.y) / 2
    const k = Math.min(K_MAX, Math.max(K_MIN, pinch.k * dist / pinch.dist))
    const wx = (pinch.midX - pinch.x) / pinch.k
    const wy = (pinch.midY - pinch.y) / pinch.k
    layout.follow = false
    anim = null
    view.k = k
    view.x = midX - wx * k
    view.y = midY - wy * k
    invalidate()
    return
  }

  if (drag && drag.pointerId === e.pointerId) {
    if (!drag.moved && Math.hypot(p.x - drag.startX, p.y - drag.startY) < (pointer.type === 'touch' ? 8 : 4)) return
    drag.moved = true
    if (props.mode === 'connect') {
      // dragging from a person draws a tie to whoever it ends on
      dragTie.value = drag.id
      const over = nodeAt(p.x, p.y, pointer.type === 'touch' ? 10 : 4)
      hoverNode.value = over && over !== drag.id ? over : null
      invalidate()
      return
    }
    const n = layout.nodes.get(drag.id)
    if (n) {
      const w = toWorld(p.x, p.y)
      n.fx = w.x
      n.fy = w.y
      n.x = w.x
      n.y = w.y
      if (!props.frozen) layout.reheat(0.12)
    }
    hoverNode.value = drag.id
    setCursor('grabbing')
    invalidate()
    return
  }

  if (pan && pan.pointerId === e.pointerId) {
    const dx = p.x - pan.startX
    const dy = p.y - pan.startY
    if (!pan.moved && Math.hypot(dx, dy) < 4) return
    pan.moved = true
    panning.value = true
    layout.follow = false
    anim = null
    view.x = pan.vx + dx
    view.y = pan.vy + dy
    setCursor('grabbing')
    invalidate()
    return
  }

  if (linkDown) return
  const before = hoverNode.value
  const beforeLink = hoverLink.value
  updateHover(p)
  if (before !== hoverNode.value || beforeLink !== hoverLink.value || (props.mode === 'connect' && connectFrom.value)) invalidate()
}

function onPointerUp (e: PointerEvent) {
  const p = localPoint(e)
  pointer.x = p.x
  pointer.y = p.y
  pointers.delete(e.pointerId)
  try { stage.value?.releasePointerCapture(e.pointerId) } catch (err) { /* already released */ }
  if (pinch) {
    if (pointers.size < 2) pinch = null
    return
  }

  if (drag && drag.pointerId === e.pointerId) {
    const d = drag
    drag = null
    dragTie.value = null
    if (props.mode === 'connect') {
      if (d.moved) {
        const over = nodeAt(p.x, p.y, pointer.type === 'touch' ? 10 : 4)
        if (over && over !== d.id) proposeAdd(d.id, over)
        else if (!connectFrom.value) connectFrom.value = d.id
      } else {
        clickNode(d.id)
      }
    } else if (!d.moved) {
      clickNode(d.id)
    }
    updateHover(p)
    invalidate()
    return
  }

  if (linkDown && linkDown.pointerId === e.pointerId) {
    const l = linkDown
    linkDown = null
    if (Math.hypot(p.x - l.startX, p.y - l.startY) < 8 && props.mode === 'cut') {
      emit('edit', { op: 'remove', a: l.a, b: l.b })
    }
    return
  }

  if (pan && pan.pointerId === e.pointerId) {
    const wasClick = !pan.moved
    pan = null
    panning.value = false
    updateHover(p)
    if (wasClick) {
      // a click on empty space: cancels a tie being drawn; in Select mode it also deselects
      if (props.mode === 'connect') connectFrom.value = null
      else if (props.mode === 'view' && props.selectedId) emit('select', null)
    }
    invalidate()
  }
}
function onPointerCancel (e: PointerEvent) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinch = null
  drag = null
  dragTie.value = null
  pan = null
  linkDown = null
  panning.value = false
  invalidate()
}
function onPointerLeave (e: PointerEvent) {
  if (pointers.has(e.pointerId)) return
  pointer.inside = false
  if (hoverNode.value || hoverLink.value) {
    hoverNode.value = null
    hoverLink.value = null
    invalidate()
  }
}
function onDblClick (e: MouseEvent) {
  const p = localPoint(e)
  const hit = nodeAt(p.x, p.y)
  if (hit) {
    // double-click a person you moved to let the layout place them again
    const n = layout.nodes.get(hit)
    if (n && n.fx != null) {
      n.fx = null
      n.fy = null
      layout.reheat(0.2)
      invalidate()
    }
    return
  }
  if ((e.target as Element).closest('.an__zoom')) return
  zoomAt(e.shiftKey ? 0.5 : 2, p.x, p.y, true)
}
function onContextMenu (e: MouseEvent) {
  // a right-click while drawing a tie cancels it instead of opening the menu
  if (connectFrom.value) {
    e.preventDefault()
    connectFrom.value = null
    invalidate()
  }
}

function clickNode (id: string) {
  const now = performance.now()
  const double = lastClick.id === id && now - lastClick.at < 350
  lastClick = { id: double ? '' : id, at: now }
  if (props.mode === 'connect') {
    if (double) return
    if (!connectFrom.value) connectFrom.value = id
    else if (connectFrom.value === id) connectFrom.value = null
    else proposeAdd(connectFrom.value, id)
    invalidate()
    return
  }
  // the second click of a double-click keeps the person selected (it used to deselect them)
  if (double) {
    if (props.selectedId !== id) emit('select', id)
    return
  }
  emit('select', id === props.selectedId ? null : id)
}

function proposeAdd (a: string, b: string) {
  if (props.graph.linkBetween(a, b)) {
    emit('notice', `${nameOf(a)} and ${nameOf(b)} are already tied.`)
  } else {
    emit('edit', { op: 'add', a, b })
  }
  connectFrom.value = null
}

// ---- keyboard -----------------------------------------------------------------------------------

function nearestToCentre (): string | null {
  let best: string | null = null
  let bestD = Infinity
  for (const n of layout.nodes.values()) {
    const p = toScreen(n)
    const d = Math.hypot(p.x - size.w / 2, p.y - size.h / 2)
    if (d < bestD) { bestD = d; best = n.id }
  }
  return best
}
function moveFocus (dx: number, dy: number) {
  const cur = kbFocus.value ? layout.nodes.get(kbFocus.value) : null
  if (!cur) { kbFocus.value = props.selectedId && layout.nodes.has(props.selectedId) ? props.selectedId : nearestToCentre(); return }
  const from = toScreen(cur)
  let best: string | null = null
  let bestScore = Infinity
  for (const n of layout.nodes.values()) {
    if (n.id === cur.id) continue
    const p = toScreen(n)
    const vx = p.x - from.x
    const vy = p.y - from.y
    const d = Math.hypot(vx, vy)
    if (d < 0.5) continue
    const cos = (vx * dx + vy * dy) / d
    if (cos < 0.3) continue
    const score = d * (1 + 2.2 * (1 - cos))
    if (score < bestScore) { bestScore = score; best = n.id }
  }
  if (best) kbFocus.value = best
}

function onKey (e: KeyboardEvent) {
  if (e.altKey || e.ctrlKey || e.metaKey) return
  const arrows: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
  const dir = arrows[e.key]
  if (dir) {
    e.preventDefault()
    if (e.shiftKey) {
      layout.follow = false
      view.x -= dir[0] * 80
      view.y -= dir[1] * 80
    } else {
      showKbFocus.value = true
      moveFocus(dir[0], dir[1])
      if (kbFocus.value) reveal(kbFocus.value)
    }
    invalidate()
    return
  }
  if ((e.key === 'Enter' || e.key === ' ') && kbFocus.value && showKbFocus.value) {
    e.preventDefault()
    lastClick = { id: '', at: 0 }
    clickNode(kbFocus.value)
    return
  }
  if (e.key === 'Escape' && connectFrom.value) {
    e.stopPropagation()
    connectFrom.value = null
    invalidate()
    return
  }
  if (e.key === '+' || e.key === '=') { e.preventDefault(); zoomBy(1.3) }
  else if (e.key === '-' || e.key === '_') { e.preventDefault(); zoomBy(1 / 1.3) }
  else if (e.key === '0') { e.preventDefault(); fit(true) }
}
let pointerFocus = false
function onFocus () {
  // focus that comes from a click shows no keyboard ring
  if (pointerFocus) return
  let visible = false
  try { visible = !!stage.value?.matches(':focus-visible') } catch (err) { visible = false }
  if (!visible) return
  showKbFocus.value = true
  if (!kbFocus.value || !layout.nodes.has(kbFocus.value)) {
    kbFocus.value = props.selectedId && layout.nodes.has(props.selectedId) ? props.selectedId : nearestToCentre()
  }
  invalidate()
}
function onBlur () {
  showKbFocus.value = false
  invalidate()
}

watch([kbFocus, showKbFocus, connectFrom, () => props.selectedId, () => props.mode], () => {
  const id = showKbFocus.value ? kbFocus.value : null
  if (!id) { announce.value = connectFrom.value ? `Drawing a tie from ${nameOf(connectFrom.value)}. Pick who to tie them to, or press Escape.` : ''; return }
  const g = props.graph.nodes.get(id)
  const ties = layout.degree(id)
  const status = statusOf(g)
  let action = id === props.selectedId ? 'Selected.' : 'Press Enter to select.'
  if (props.mode === 'connect') {
    action = connectFrom.value
      ? (connectFrom.value === id ? 'Press Enter to cancel this tie.' : `Press Enter to tie ${nameOf(connectFrom.value)} to them.`)
      : 'Press Enter to start a tie from them.'
  }
  announce.value = `${nameOf(id)}. ${ties} ${ties === 1 ? 'tie' : 'ties'}.${status ? ` ${status}.` : ''} ${action}`
})

// ---- words --------------------------------------------------------------------------------------

const hint = computed(() => {
  if (props.mode === 'connect') {
    const from = connectFrom.value || dragTie.value
    if (!from) return 'Click a person, then who to tie them to. Or drag from one to the other.'
    const over = hoverNode.value
    if (over && over !== from && props.graph.linkBetween(from, over)) return `${nameOf(from)} and ${nameOf(over)} are already tied`
    return `Now click who to tie ${nameOf(from)} to · Esc cancels`
  }
  if (props.mode === 'cut') return 'Click a tie to cut it'
  return ''
})

const helpText = computed(() => {
  const base = 'Arrow keys move between people, Enter selects, plus and minus zoom, 0 fits everyone in view, Shift and arrows pan.'
  if (props.mode === 'connect') return `Connect mode: press Enter on one person, then on another, to tie them. ${base}`
  if (props.mode === 'cut') return `Cut mode: select a person and use Cut in their panel to cut a tie. ${base}`
  return base
})

const ariaSummary = computed(() => {
  void props.graph.version.value
  const n = props.graph.nodes.size
  const l = layout.links.length
  return `Network: ${n} ${n === 1 ? 'person' : 'people'}, ${l} ${l === 1 ? 'tie' : 'ties'}`
})

const tooltip = computed(() => {
  void props.graph.version.value
  if (panning.value || pointer.type === 'touch') return null
  const link = hoverLink.value
  if (!hoverNode.value && link && props.mode === 'cut') {
    return { title: `${nameOf(link.a)} – ${nameOf(link.b)}`, sub: '', note: 'Click to cut this tie', rows: [] as [string, string][], color: '', ai: false }
  }
  const id = hoverNode.value
  if (!id || (drag && drag.moved && props.mode !== 'connect')) return null
  const g = props.graph.nodes.get(id)
  if (!g) return null
  const label = nameOf(id)
  const rows: [string, string][] = []
  const ties = layout.degree(id)
  rows.push(['Ties', String(ties)])
  const status = statusOf(g)
  if (status) rows.push(['Status', status])
  const shown = new Set<string>()
  if (props.colorBy && !props.colorBy.startsWith('@')) {
    rows.push([colorTitle(props.colorBy), formatValue(valueOf(g, props.colorBy), 32) || '—'])
    shown.add(props.colorBy)
  }
  if (props.labelBy && props.labelBy !== 'id') shown.add(props.labelBy)
  for (const key of Object.keys(g.data)) {
    if (rows.length >= 6) break
    if (shown.has(key) || NOISY_KEYS.has(key) || key === 'ai' || key.startsWith('_')) continue
    const v = g.data[key]
    if (v === null || v === undefined || typeof v === 'object') continue
    rows.push([key, formatValue(v, 32)])
  }
  return {
    title: label,
    sub: label !== id ? id : '',
    note: '',
    rows,
    color: fillOf(id, scale.value, theme),
    ai: isAi(g),
  }
})

// ---- exports ------------------------------------------------------------------------------------

function exportLegend (colors: ColorScale | null): LegendBlock | null {
  if (!colors || !props.colorBy || colors.kind === 'none') return null
  return { title: colorTitle(props.colorBy), entries: colors.legend, gradient: colors.gradient, missing: colors.missing }
}
function exportScene () {
  const b = layout.bounds()
  const t = PAPER_THEME
  const key = props.colorBy
  const colors = key
    ? scaleFor(Array.from(props.graph.nodes.values()).map(n => valueOf(n, key, degreeOf)), { dark: false, memory: memories.get(key), colorHook: key === 'color' })
    : null
  const legend = exportLegend(colors)
  const legendRows = legend ? (legend.gradient ? 1 : legend.entries.length) + (legend.missing ? 1 : 0) : 0
  const legendH = legend ? 54 + legendRows * 20 : 0
  // a picture framed around the network at a comfortable scale (not a fixed page size)
  const pad = 56
  const bw = b ? Math.max(160, b.maxX - b.minX) : 160
  const bh = b ? Math.max(160, b.maxY - b.minY) : 160
  const k = Math.min(1.5, (2400 - pad * 2) / bw, (2400 - pad * 2 - legendH) / bh)
  const w = Math.round(Math.max(720, bw * k + pad * 2))
  const h = Math.round(Math.max(480, bh * k + pad * 2 + legendH))
  const v = fitView(w, h, { top: pad, right: pad, bottom: pad + legendH, left: pad })
  const scene = buildScene({ v, w, h, t, time: 0, live: false, colors, allLabels: props.showAllLabels || layout.nodes.size <= 150 })
  return { scene, legend, t, w, h }
}
function exportTitle () {
  return 'Network'
}
/** A PNG of the whole network (twice the size, for print), light background. */
function exportPng (): Promise<Blob | null> {
  const { scene, legend, t, w, h } = exportScene()
  const c = document.createElement('canvas')
  const s = 2
  c.width = w * s
  c.height = h * s
  const cx = c.getContext('2d')
  if (!cx) return Promise.resolve(null)
  cx.scale(s, s)
  drawScene(cx, scene, t)
  if (legend) drawLegend(cx, legend, t, h)
  return new Promise(resolve => c.toBlob(b => resolve(b), 'image/png'))
}
/** An SVG of the whole network (scales to any size; editable in Illustrator, Inkscape, Figma). */
function exportSvg (): string {
  const { scene, legend, t } = exportScene()
  return sceneToSvg(scene, t, { title: exportTitle(), legend })
}
/** Where everyone is (layout units), for the JSON and GraphML exports. */
function positions (): Map<string, { x?: number, y?: number }> {
  return new Map(Array.from(layout.nodes.values()).map((n: LayoutNode) => [n.id, { x: n.x, y: n.y }]))
}

// ---- keeping in step with the graph and the page --------------------------------------------------

let firstFitDone = false
function syncLayout () {
  const r = layout.sync(props.graph)
  if (r.structural && layout.nodes.size) {
    if (!props.frozen) {
      if (reducedMotion) {
        layout.reheat(r.added + r.removed > 3 ? 0.8 : 0.3)
        layout.settle(120, 500)
      } else if (r.added > 12) {
        // many at once (first load, a replay): settle most of the way before showing them
        layout.reheat(1)
        layout.settle(45, 260)
      } else {
        layout.reheat(r.added + r.removed > 2 ? 0.5 : 0.25)
      }
    }
    if (layout.follow) {
      if (!firstFitDone) { fit(false, true); firstFitDone = true }
      fitWhenCalm = true
    }
  }
  if (!layout.nodes.size) {
    connectFrom.value = null
    hoverNode.value = null
    hoverLink.value = null
  }
  if (hoverNode.value && !layout.nodes.has(hoverNode.value)) hoverNode.value = null
  if (hoverLink.value && !layout.links.some(l => l.id === hoverLink.value!.id)) hoverLink.value = null
  if (connectFrom.value && !layout.nodes.has(connectFrom.value)) connectFrom.value = null
  if (kbFocus.value && !layout.nodes.has(kbFocus.value)) kbFocus.value = null
  invalidate()
}

watch(() => props.graph.version.value, syncLayout)
watch(() => props.sizeByDegree, (v) => { layout.setSizeByDegree(v); invalidate() }, { immediate: true })
watch(() => props.frozen, (v) => { if (!v) { layout.reheat(0.15) } invalidate() })
watch(() => props.mode, () => { connectFrom.value = null; hoverLink.value = null; invalidate() })
watch(() => [props.selectedId, props.colorBy, props.labelBy, props.showAllLabels, props.highlight, scale.value], invalidate)
watch(() => props.pending.map(p => `${p.op}:${p.a}:${p.b}`).join('|'), invalidate)

// ---- lifecycle ----------------------------------------------------------------------------------

let ro: ResizeObserver | null = null
let mo: MutationObserver | null = null
let mq: MediaQueryList | null = null

function refreshTheme () {
  requestAnimationFrame(() => {
    theme = readTheme(stage.value || null)
    widths.clear()
    themeTick.value++
    invalidate()
  })
}

function measureStage () {
  const el = stage.value
  const c = canvas.value
  if (!el || !c) return
  const r = el.getBoundingClientRect()
  if (r.width < 1 || r.height < 1) return
  const w = Math.round(r.width)
  const h = Math.round(r.height)
  const ratio = Math.min(3, window.devicePixelRatio || 1)
  if (w === size.w && h === size.h && ratio === dpr) return
  const first = !size.w
  const dw = w - size.w
  const dh = h - size.h
  size.w = w
  size.h = h
  dpr = ratio
  c.width = Math.round(w * dpr)
  c.height = Math.round(h * dpr)
  c.style.width = `${w}px`
  c.style.height = `${h}px`
  if (first) {
    if (layout.view && !layout.follow) Object.assign(view, layout.view)
    else { fit(false, true); firstFitDone = layout.nodes.size > 0 }
  } else if (layout.follow) {
    fit(false, true)
  } else {
    // keep the middle of the picture in the middle
    view.x += dw / 2
    view.y += dh / 2
  }
  draw(performance.now())
}

onMounted(() => {
  mounted = true
  const c = canvas.value!
  try { ctx = c.getContext('2d') } catch (e) { ctx = null }
  theme = readTheme(stage.value || null)
  themeTick.value++
  layout.sync(props.graph)
  if (layout.view) {
    Object.assign(view, layout.view)
    firstFitDone = true
  }
  if (!layout.moving && layout.nodes.size && !layout.view) layout.reheat(0.6)
  if (layout.nodes.size && !layout.view) {
    if (reducedMotion) layout.settle(150, 600)
    else layout.settle(45, 260)
  }
  measureStage()
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => measureStage())
    ro.observe(stage.value!)
  }
  if (typeof MutationObserver !== 'undefined') {
    mo = new MutationObserver(refreshTheme)
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] })
  }
  if (window.matchMedia) {
    mq = window.matchMedia('(prefers-color-scheme: dark)')
    mq.addEventListener?.('change', refreshTheme)
  }
  const fonts = (document as any).fonts
  if (fonts && fonts.ready) fonts.ready.then(() => { widths.clear(); invalidate() }).catch(() => {})
  invalidate()
})

onBeforeUnmount(() => {
  mounted = false
  layout.view = { ...view }
  if (raf) cancelAnimationFrame(raf)
  raf = 0
  ro?.disconnect()
  mo?.disconnect()
  mq?.removeEventListener?.('change', refreshTheme)
})

defineExpose({
  fit,
  focusOn,
  reveal,
  relayout,
  zoomBy,
  exportPng,
  exportSvg,
  positions,
  cancelConnect: () => { connectFrom.value = null; invalidate() },
  /** Whether a tie is being drawn (Esc should cancel it first). */
  isConnecting: () => !!connectFrom.value,
})
</script>

<style>
.an {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 240px;
  overflow: hidden;
  background: var(--graph-bg);
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  outline: none;
  cursor: grab;
}
.an:focus-visible { box-shadow: inset 0 0 0 2px var(--accent); }
.an.is-panning { cursor: grabbing; }
.an--connect { cursor: crosshair; }
.an__canvas { position: absolute; inset: 0; display: block; }

.an__empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--muted);
  pointer-events: none;
  text-align: center;
  padding: 24px;
}
.an__empty > * { pointer-events: auto; }

.an__hint {
  position: absolute;
  left: 50%;
  top: 14px;
  transform: translateX(-50%);
  max-width: calc(100% - 32px);
  padding: 7px 14px;
  border-radius: var(--radius-pill);
  background: var(--text);
  color: var(--bg);
  font-size: var(--text-sm);
  font-weight: 550;
  box-shadow: var(--shadow);
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  z-index: 2;
}
@media (max-width: 900px) { .an__hint { top: 52px; } }
.an-fade-enter-active, .an-fade-leave-active { transition: opacity 140ms var(--ease); }
.an-fade-enter-from, .an-fade-leave-to { opacity: 0; }

.an__tip {
  position: absolute;
  transform: translate(-50%, -100%);
  min-width: 150px;
  max-width: 240px;
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  background: var(--surface-raised);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  pointer-events: none;
  font-size: var(--text-xs);
  z-index: 3;
}
.an__tip.is-below { transform: translate(-50%, 0); }
.an__tip-title { display: flex; align-items: center; gap: 7px; font-weight: 650; font-size: var(--text-sm); color: var(--text); }
.an__tip-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.an__tip-dot { flex: none; width: 10px; height: 10px; border-radius: 50%; background: var(--dot); }
.an__tip-dot.is-ring { background: transparent; box-shadow: inset 0 0 0 3px var(--dot); }
.an__tip-sub { color: var(--faint); font-family: var(--font-mono); font-size: 11px; margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.an__tip-note { color: var(--danger-soft-text); margin-top: 2px; }
.an__tip-row { display: flex; justify-content: space-between; gap: 12px; color: var(--muted); margin-top: 3px; }
.an__tip-row:first-of-type { margin-top: 7px; padding-top: 7px; border-top: 1px solid var(--border); }
.an__tip-row span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.an__tip-row b { color: var(--text-soft); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-variant-numeric: tabular-nums; }

.an__zoom {
  position: absolute;
  right: 14px;
  bottom: 14px;
  display: flex;
  flex-direction: column;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  overflow: hidden;
  z-index: 2;
  cursor: default;
}
.an__zoom-btn {
  display: grid;
  place-items: center;
  width: 34px;
  height: 32px;
  border: 0;
  background: transparent;
  color: var(--text-soft);
  cursor: pointer;
}
.an__zoom-btn + .an__zoom-btn { border-top: 1px solid var(--border); }
.an__zoom-btn:hover { background: var(--surface-hover); color: var(--text); }
.an__zoom-btn:focus-visible { outline-offset: -2px; }
</style>
