<template>
  <div
    ref="stage"
    class="an"
    :class="[`an--${mode}`, { 'is-panning': panning, 'is-dragging': !!dragging }]"
    @pointerdown="onStagePointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @pointerleave="onPointerLeave"
    @wheel.prevent="onWheel"
    @keydown="onKey"
  >
    <svg
      class="an__svg"
      :viewBox="`0 0 ${size.w} ${size.h}`"
      :width="size.w"
      :height="size.h"
      role="img"
      :aria-label="ariaSummary"
    >
      <defs>
        <pattern :id="gridId" :width="24 * view.k" :height="24 * view.k" patternUnits="userSpaceOnUse" :x="view.x" :y="view.y">
          <circle :cx="1" :cy="1" r="1" class="an__grid-dot" />
        </pattern>
      </defs>
      <rect class="an__bg" :width="size.w" :height="size.h" :fill="`url(#${gridId})`" />

      <g :transform="`translate(${view.x},${view.y}) scale(${view.k})`">
        <!-- ties -->
        <g class="an__links">
          <g v-for="l in drawnLinks" :key="l.id" class="an__link" :class="linkClass(l)">
            <line :x1="l.x1" :y1="l.y1" :x2="l.x2" :y2="l.y2" class="an__link-line" :stroke-width="linkWidth / view.k" />
            <line
              :x1="l.x1" :y1="l.y1" :x2="l.x2" :y2="l.y2"
              class="an__link-hit"
              :stroke-width="12 / view.k"
              @pointerenter="hoverLink = l.id"
              @pointerleave="hoverLink = hoverLink === l.id ? null : hoverLink"
              @click.stop="onLinkClick(l)"
            />
          </g>
        </g>

        <!-- edits waiting for the server -->
        <g class="an__pending">
          <line v-for="p in drawnPending" :key="p.key" :x1="p.x1" :y1="p.y1" :x2="p.x2" :y2="p.y2" :class="`is-${p.op}`" :stroke-width="2 / view.k" />
        </g>

        <!-- the tie being drawn in connect mode -->
        <line
          v-if="rubber"
          class="an__rubber"
          :x1="rubber.x1" :y1="rubber.y1" :x2="rubber.x2" :y2="rubber.y2"
          :stroke-width="2 / view.k"
        />

        <!-- people -->
        <g class="an__nodes">
          <g
            v-for="n in drawnNodes"
            :key="n.id"
            class="an__node"
            :class="nodeClass(n)"
            :transform="`translate(${n.x},${n.y})`"
            @pointerdown.stop="onNodePointerDown($event, n)"
            @pointerenter="hoverNode = n.id"
            @pointerleave="hoverNode = hoverNode === n.id ? null : hoverNode"
          >
            <circle v-if="n.id === selectedId || n.id === connectFrom" class="an__halo" :r="n.r + 7 / view.k" />
            <circle class="an__dot" :r="n.r" :fill="n.color" :stroke-width="2 / view.k" />
            <text
              v-if="showLabel(n)"
              class="an__label"
              :y="n.r + 13 / view.k"
              :font-size="11.5 / view.k"
              text-anchor="middle"
            >{{ n.label }}</text>
          </g>
        </g>
      </g>
    </svg>

    <div v-if="!nodeCount" class="an__empty">
      <slot name="empty">
        <p>No one is in this session yet.</p>
      </slot>
    </div>

    <div v-if="hint" class="an__hint" role="status">{{ hint }}</div>

    <div v-if="tooltip" class="an__tip" :style="{ left: `${tooltip.x}px`, top: `${tooltip.y}px` }" aria-hidden="true">
      <div class="an__tip-title">{{ tooltip.title }}</div>
      <div v-for="row in tooltip.rows" :key="row[0]" class="an__tip-row"><span>{{ row[0] }}</span><b>{{ row[1] }}</b></div>
    </div>

    <div class="an__zoom" @pointerdown.stop>
      <button type="button" class="an__zoom-btn" aria-label="Zoom in" title="Zoom in" @click="zoomBy(1.25)"><Plus :size="16" /></button>
      <button type="button" class="an__zoom-btn" aria-label="Zoom out" title="Zoom out" @click="zoomBy(0.8)"><Minus :size="16" /></button>
      <button type="button" class="an__zoom-btn" aria-label="Fit the network to the view" title="Fit to view" @click="fit(true)"><Maximize2 :size="15" /></button>
      <button type="button" class="an__zoom-btn" aria-label="Lay the network out again" title="Re-run layout" @click="relayout"><RefreshCw :size="15" /></button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { forceSimulation, forceLink, forceManyBody, forceCollide, forceX, forceY, type Simulation, type SimulationLinkDatum } from 'd3-force'
import { Plus, Minus, Maximize2, RefreshCw } from 'lucide-vue-next'
import type { LiveGraph, GraphNode, GraphLink } from '@/lib/graph'
import { scaleFor, NEUTRAL } from './palette'
import { uid } from '@/ui/uid'

export type NetworkMode = 'view' | 'connect' | 'cut'
export interface PendingEdit { op: 'add' | 'remove', a: string, b: string }

const props = withDefaults(defineProps<{
  graph: LiveGraph
  mode?: NetworkMode
  selectedId?: string | null
  colorBy?: string | null
  labelBy?: string | null
  sizeByDegree?: boolean
  showAllLabels?: boolean
  pending?: PendingEdit[]
  /** Ids to emphasise, e.g. search matches. */
  highlight?: string[] | null
}>(), { mode: 'view', selectedId: null, colorBy: null, labelBy: null, sizeByDegree: true, showAllLabels: false, pending: () => [], highlight: null })

const emit = defineEmits<{
  select: [id: string | null]
  edit: [edit: PendingEdit]
  legend: [legend: { label: string, color: string }[]]
  notice: [text: string]
}>()

interface SimNode extends GraphNode { r: number }
type SimLink = SimulationLinkDatum<SimNode> & { id: string }

const gridId = uid('an-grid')
const stage = ref<HTMLElement>()
const size = reactive({ w: 800, h: 560 })
const view = reactive({ k: 1, x: 0, y: 0 })
const tick = ref(0)
const hoverNode = ref<string | null>(null)
const hoverLink = ref<string | null>(null)
const connectFrom = ref<string | null>(null)
const pointer = reactive({ x: 0, y: 0, inside: false })
const panning = ref(false)
const dragging = shallowRef<{ node: SimNode, moved: boolean, startX: number, startY: number, pointerId: number } | null>(null)
let panStart: { x: number, y: number, vx: number, vy: number, pointerId: number, moved: boolean } | null = null
let userMovedView = false
let fittedOnce = false

const reducedMotion = typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches

// ---- data -------------------------------------------------------------------------------------

const simNodes = new Map<string, SimNode>()
let simLinks: SimLink[] = []
const sim: Simulation<SimNode, SimLink> = forceSimulation<SimNode, SimLink>()
  .force('link', forceLink<SimNode, SimLink>().id(d => d.id).distance(70).strength(0.5))
  .force('charge', forceManyBody<SimNode>().strength(-240).distanceMax(600))
  .force('collide', forceCollide<SimNode>().radius(d => d.r + 8))
  .force('x', forceX<SimNode>(0).strength(0.045))
  .force('y', forceY<SimNode>(0).strength(0.045))
  .alphaDecay(0.035)
  .stop()

let raf = 0
function loop () {
  raf = 0
  if (sim.alpha() < sim.alphaMin()) {
    maybeFirstFit()
    return
  }
  sim.tick()
  tick.value++
  if (!fittedOnce && sim.alpha() < 0.25) maybeFirstFit()
  raf = requestAnimationFrame(loop)
}
function kick (alpha = 0.35) {
  if (reducedMotion) {
    sim.alpha(alpha)
    for (let i = 0; i < 200 && sim.alpha() > sim.alphaMin(); i++) sim.tick()
    tick.value++
    maybeFirstFit()
    return
  }
  sim.alpha(Math.max(sim.alpha(), alpha))
  if (!raf) raf = requestAnimationFrame(loop)
}

function radiusOf (id: string, degree: number) {
  if (!props.sizeByDegree) return 9
  return Math.min(20, 7.5 + Math.sqrt(degree) * 2.6)
}

/** Bring the simulation's nodes and ties in line with the graph, keeping everyone's place. */
function sync () {
  const g = props.graph
  const degree = new Map<string, number>()
  for (const l of g.links.values()) {
    degree.set(l.source, (degree.get(l.source) || 0) + 1)
    degree.set(l.target, (degree.get(l.target) || 0) + 1)
  }
  let added = 0
  let structural = false
  for (const id of Array.from(simNodes.keys())) {
    if (!g.nodes.has(id)) { simNodes.delete(id); structural = true }
  }
  for (const n of g.nodes.values()) {
    let s = simNodes.get(n.id)
    if (!s) {
      s = n as SimNode
      if (s.x === undefined || s.y === undefined) {
        // start next to someone they are tied to, or near the middle
        const friend = g.neighbors(n.id).map(id => simNodes.get(id)).find(f => f && f.x !== undefined)
        const angle = Math.random() * Math.PI * 2
        const dist = friend ? 30 : 40 + Math.random() * 80
        s.x = (friend ? friend.x! : 0) + Math.cos(angle) * dist
        s.y = (friend ? friend.y! : 0) + Math.sin(angle) * dist
      }
      simNodes.set(n.id, s)
      added++
      structural = true
    }
    s.r = radiusOf(n.id, degree.get(n.id) || 0)
  }
  const prevKeys = new Set(simLinks.map(l => l.id))
  const nextLinks: SimLink[] = []
  for (const l of g.links.values()) {
    if (!simNodes.has(l.source) || !simNodes.has(l.target)) continue
    nextLinks.push({ id: l.id, source: l.source, target: l.target } as any)
    if (!prevKeys.has(l.id)) structural = true
  }
  if (nextLinks.length !== simLinks.length) structural = true
  simLinks = nextLinks
  sim.nodes(Array.from(simNodes.values()))
  ;(sim.force('link') as any).links(simLinks)
  const n = simNodes.size
  ;(sim.force('charge') as any).strength(n > 150 ? -120 : n > 60 ? -180 : -260)
  if (structural) kick(added > 3 || !fittedOnce ? 0.9 : 0.3)
  tick.value++
}

watch(() => props.graph.version.value, sync)
watch(() => props.sizeByDegree, sync)

// ---- what is drawn ---------------------------------------------------------------------------

const nodeCount = computed(() => { void tick.value; return simNodes.size })

const colorScale = computed(() => {
  void props.graph.version.value
  if (!props.colorBy) return null
  const key = props.colorBy
  return scaleFor(Array.from(props.graph.nodes.values()).map(n => n.data[key]))
})
watch(colorScale, (s) => emit('legend', s ? s.legend : []), { immediate: true })

function labelOf (n: GraphNode): string {
  if (!props.labelBy || props.labelBy === 'id') return n.id
  const v = n.data[props.labelBy]
  return v === undefined || v === null || v === '' ? n.id : (typeof v === 'object' ? JSON.stringify(v) : String(v))
}

const highlightSet = computed(() => props.highlight ? new Set(props.highlight) : null)

const focusIds = computed<Set<string> | null>(() => {
  void props.graph.version.value
  const id = hoverNode.value || (props.mode === 'connect' ? connectFrom.value : null) || props.selectedId
  if (!id || !props.graph.nodes.has(id)) return null
  return new Set([id, ...props.graph.neighbors(id)])
})

const drawnNodes = computed(() => {
  void tick.value
  void props.graph.version.value
  const scale = colorScale.value
  const out = []
  for (const n of simNodes.values()) {
    out.push({
      id: n.id,
      x: n.x || 0,
      y: n.y || 0,
      r: n.r,
      color: scale ? scale.color(n.data[props.colorBy!]) : (n.id.startsWith('_') ? NEUTRAL : '#6366f1'),
      label: labelOf(n),
      ai: n.id.startsWith('_'),
    })
  }
  return out
})

const drawnLinks = computed(() => {
  void tick.value
  const out = []
  for (const l of simLinks) {
    const s = l.source as SimNode
    const t = l.target as SimNode
    if (!s || !t || typeof s !== 'object') continue
    out.push({ id: l.id, a: s.id, b: t.id, x1: s.x || 0, y1: s.y || 0, x2: t.x || 0, y2: t.y || 0 })
  }
  return out
})

const drawnPending = computed(() => {
  void tick.value
  return props.pending.map(p => {
    const a = simNodes.get(p.a)
    const b = simNodes.get(p.b)
    if (!a || !b) return null
    return { key: `${p.op}:${p.a}:${p.b}`, op: p.op, x1: a.x || 0, y1: a.y || 0, x2: b.x || 0, y2: b.y || 0 }
  }).filter(Boolean) as { key: string, op: string, x1: number, y1: number, x2: number, y2: number }[]
})

const rubber = computed(() => {
  if (props.mode !== 'connect' || !connectFrom.value || !pointer.inside) return null
  const a = simNodes.get(connectFrom.value)
  if (!a) return null
  const target = hoverNode.value && hoverNode.value !== connectFrom.value ? simNodes.get(hoverNode.value) : null
  const p = target ? { x: target.x || 0, y: target.y || 0 } : toWorld(pointer.x, pointer.y)
  return { x1: a.x || 0, y1: a.y || 0, x2: p.x, y2: p.y }
})

const linkWidth = computed(() => simLinks.length > 400 ? 1 : 1.6)

function nodeClass (n: { id: string, ai: boolean }) {
  const focus = focusIds.value
  const hl = highlightSet.value
  return {
    'is-ai': n.ai,
    'is-selected': n.id === props.selectedId,
    'is-from': n.id === connectFrom.value,
    'is-dim': (focus && !focus.has(n.id)) || (hl && !hl.has(n.id)),
    'is-match': hl && hl.has(n.id),
    'is-target': props.mode === 'connect' && !!connectFrom.value && n.id === hoverNode.value && n.id !== connectFrom.value,
  }
}
function linkClass (l: { id: string, a: string, b: string }) {
  const focus = focusIds.value
  const focusId = hoverNode.value || props.selectedId
  const touches = !!focusId && (l.a === focusId || l.b === focusId)
  return {
    'is-hover': hoverLink.value === l.id,
    'is-focus': touches,
    'is-dim': !!focus && !touches,
  }
}
function showLabel (n: { id: string }) {
  if (props.showAllLabels) return true
  if (n.id === hoverNode.value || n.id === props.selectedId || n.id === connectFrom.value) return true
  if (highlightSet.value && highlightSet.value.has(n.id)) return true
  return simNodes.size <= 40 || view.k >= 1.4
}

const hint = computed(() => {
  if (props.mode === 'connect') {
    if (!connectFrom.value) return 'Click a person to start a tie, then click who to tie them to.'
    return `Now click who to tie ${labelOf(simNodes.get(connectFrom.value) || { id: connectFrom.value, data: {} })} to. Esc to cancel.`
  }
  if (props.mode === 'cut') return 'Click a tie to cut it.'
  return ''
})

const tooltip = computed(() => {
  if (dragging.value || panning.value) return null
  const id = hoverNode.value
  if (!id) return null
  const n = props.graph.nodes.get(id)
  const s = simNodes.get(id)
  if (!n || !s) return null
  const p = toScreen(s.x || 0, s.y || 0)
  const rows: [string, string][] = []
  rows.push(['Ties', String(props.graph.degree(id))])
  for (const key of Object.keys(n.data)) {
    if (rows.length >= 6) break
    if (key.startsWith('_') || key === 'private' || key === 'text' || key === 'choices' || key === 'timers') continue
    const v = n.data[key]
    if (v === null || v === undefined || typeof v === 'object') continue
    rows.push([key, String(v).slice(0, 40)])
  }
  return { x: p.x, y: p.y - s.r * view.k - 12, title: labelOf(n) + (labelOf(n) !== id ? ` · ${id}` : ''), rows }
})

const ariaSummary = computed(() => {
  void props.graph.version.value
  return `Network of ${props.graph.nodes.size} people and ${props.graph.links.size} ties.`
})

// ---- coordinates, zoom and pan ----------------------------------------------------------------

function toWorld (sx: number, sy: number) {
  return { x: (sx - view.x) / view.k, y: (sy - view.y) / view.k }
}
function toScreen (wx: number, wy: number) {
  return { x: wx * view.k + view.x, y: wy * view.k + view.y }
}
function localPoint (e: PointerEvent | WheelEvent | MouseEvent) {
  const rect = stage.value!.getBoundingClientRect()
  return { x: e.clientX - rect.left, y: e.clientY - rect.top }
}
function zoomAt (factor: number, sx: number, sy: number) {
  const k = Math.min(6, Math.max(0.15, view.k * factor))
  const w = toWorld(sx, sy)
  view.k = k
  view.x = sx - w.x * k
  view.y = sy - w.y * k
}
function zoomBy (factor: number) {
  userMovedView = true
  zoomAt(factor, size.w / 2, size.h / 2)
}
function onWheel (e: WheelEvent) {
  userMovedView = true
  const p = localPoint(e)
  if (e.ctrlKey || e.metaKey) {
    // a pinch on a trackpad arrives as a wheel event with ctrlKey
    zoomAt(Math.exp(-e.deltaY * 0.01), p.x, p.y)
  } else if (e.deltaMode === 0 && (Math.abs(e.deltaX) > 0 || Math.abs(e.deltaY) < 40)) {
    // two-finger scrolling on a trackpad moves the picture
    view.x -= e.deltaX
    view.y -= e.deltaY
  } else {
    // a mouse wheel zooms
    zoomAt(Math.exp(-Math.sign(e.deltaY) * Math.min(120, Math.abs(e.deltaY) * (e.deltaMode === 1 ? 40 : 1)) * 0.0022), p.x, p.y)
  }
}

/** Fit everyone in view. */
function fit (animate = false) {
  if (!simNodes.size) return
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const n of simNodes.values()) {
    minX = Math.min(minX, (n.x || 0) - n.r)
    minY = Math.min(minY, (n.y || 0) - n.r)
    maxX = Math.max(maxX, (n.x || 0) + n.r)
    maxY = Math.max(maxY, (n.y || 0) + n.r + 16)
  }
  const pad = 48
  const w = Math.max(1, maxX - minX)
  const h = Math.max(1, maxY - minY)
  const k = Math.min(2.2, Math.max(0.15, Math.min((size.w - pad * 2) / w, (size.h - pad * 2) / h)))
  const target = { k, x: size.w / 2 - ((minX + maxX) / 2) * k, y: size.h / 2 - ((minY + maxY) / 2) * k }
  if (!animate || reducedMotion) {
    Object.assign(view, target)
    return
  }
  const from = { ...view }
  const start = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / 320)
    const e = 1 - Math.pow(1 - t, 3)
    view.k = from.k + (target.k - from.k) * e
    view.x = from.x + (target.x - from.x) * e
    view.y = from.y + (target.y - from.y) * e
    if (t < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
  userMovedView = false
}
function maybeFirstFit () {
  if (!userMovedView && simNodes.size) {
    fit(fittedOnce)
    fittedOnce = true
  }
}
function relayout () {
  for (const n of simNodes.values()) { n.fx = null; n.fy = null }
  userMovedView = false
  fittedOnce = false
  kick(1)
}

/** Centre the view on someone (e.g. a search result). */
function focusOn (id: string) {
  const n = simNodes.get(id)
  if (!n) return
  userMovedView = true
  const k = Math.max(view.k, 1.2)
  const target = { k, x: size.w / 2 - (n.x || 0) * k, y: size.h / 2 - (n.y || 0) * k }
  if (reducedMotion) { Object.assign(view, target); return }
  const from = { ...view }
  const start = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / 360)
    const e = 1 - Math.pow(1 - t, 3)
    view.k = from.k + (target.k - from.k) * e
    view.x = from.x + (target.x - from.x) * e
    view.y = from.y + (target.y - from.y) * e
    if (t < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

// ---- pointer interaction ----------------------------------------------------------------------

function onStagePointerDown (e: PointerEvent) {
  if (e.button !== 0) return
  const p = localPoint(e)
  panStart = { x: p.x, y: p.y, vx: view.x, vy: view.y, pointerId: e.pointerId, moved: false }
  stage.value?.setPointerCapture?.(e.pointerId)
}
function onNodePointerDown (e: PointerEvent, n: { id: string }) {
  if (e.button !== 0) return
  const s = simNodes.get(n.id)
  if (!s) return
  stage.value?.focus({ preventScroll: true })
  const p = localPoint(e)
  dragging.value = { node: s, moved: false, startX: p.x, startY: p.y, pointerId: e.pointerId }
  stage.value?.setPointerCapture?.(e.pointerId)
}
function onPointerMove (e: PointerEvent) {
  const p = localPoint(e)
  pointer.x = p.x
  pointer.y = p.y
  pointer.inside = true
  const d = dragging.value
  if (d && d.pointerId === e.pointerId) {
    if (!d.moved && Math.hypot(p.x - d.startX, p.y - d.startY) < 4) return
    if (props.mode === 'connect') return // in connect mode a drag from a person draws a tie
    d.moved = true
    const w = toWorld(p.x, p.y)
    d.node.fx = w.x
    d.node.fy = w.y
    kick(0.15)
    return
  }
  if (panStart && panStart.pointerId === e.pointerId) {
    const dx = p.x - panStart.x
    const dy = p.y - panStart.y
    if (!panStart.moved && Math.hypot(dx, dy) < 4) return
    panStart.moved = true
    panning.value = true
    userMovedView = true
    view.x = panStart.vx + dx
    view.y = panStart.vy + dy
  }
}
function onPointerUp (e: PointerEvent) {
  const d = dragging.value
  if (d && d.pointerId === e.pointerId) {
    dragging.value = null
    if (props.mode === 'connect') {
      const p = localPoint(e)
      const moved = Math.hypot(p.x - d.startX, p.y - d.startY) >= 4
      // drag from one person to another, or click one then the other
      if (moved) {
        const over = nodeAt(p.x, p.y)
        if (over && over !== d.node.id) {
          proposeAdd(d.node.id, over)
        } else if (!connectFrom.value) {
          connectFrom.value = d.node.id
        }
      } else {
        clickNode(d.node.id)
      }
    } else if (!d.moved) {
      clickNode(d.node.id)
    }
    // a dragged person stays where they were put (double-click the background to free everyone)
    return
  }
  if (panStart && panStart.pointerId === e.pointerId) {
    const wasClick = !panStart.moved
    panStart = null
    panning.value = false
    if (wasClick) {
      if (props.mode === 'connect' && connectFrom.value) connectFrom.value = null
      else emit('select', null)
    }
  }
}
function onPointerLeave () {
  pointer.inside = false
}
function nodeAt (sx: number, sy: number): string | null {
  const w = toWorld(sx, sy)
  let best: string | null = null
  let bestD = Infinity
  for (const n of simNodes.values()) {
    const d = Math.hypot((n.x || 0) - w.x, (n.y || 0) - w.y)
    if (d <= n.r + 6 / view.k && d < bestD) { best = n.id; bestD = d }
  }
  return best
}

function clickNode (id: string) {
  if (props.mode === 'connect') {
    if (!connectFrom.value) {
      connectFrom.value = id
    } else if (connectFrom.value === id) {
      connectFrom.value = null
    } else {
      proposeAdd(connectFrom.value, id)
    }
    return
  }
  emit('select', id === props.selectedId ? null : id)
}
function proposeAdd (a: string, b: string) {
  if (props.graph.linkBetween(a, b)) {
    emit('notice', `${labelOf(props.graph.nodes.get(a)!)} and ${labelOf(props.graph.nodes.get(b)!)} are already tied.`)
  } else {
    emit('edit', { op: 'add', a, b })
  }
  connectFrom.value = null
}
function onLinkClick (l: { id: string, a: string, b: string }) {
  if (props.mode === 'cut') {
    emit('edit', { op: 'remove', a: l.a, b: l.b })
  }
}

function onKey (e: KeyboardEvent) {
  if (e.key === 'Escape' && connectFrom.value) {
    e.stopPropagation()
    connectFrom.value = null
  } else if (e.key === '+' || e.key === '=') {
    zoomBy(1.2)
  } else if (e.key === '-') {
    zoomBy(1 / 1.2)
  } else if (e.key === '0') {
    fit(true)
  }
}

watch(() => props.mode, () => { connectFrom.value = null; hoverLink.value = null })

// ---- lifecycle --------------------------------------------------------------------------------

let ro: ResizeObserver | null = null
onMounted(() => {
  const el = stage.value!
  const measure = () => {
    const r = el.getBoundingClientRect()
    if (r.width < 1 || r.height < 1) return
    const grew = Math.abs(r.width - size.w) > 1 || Math.abs(r.height - size.h) > 1
    size.w = Math.round(r.width)
    size.h = Math.round(r.height)
    if (grew && !userMovedView && fittedOnce) fit(false)
  }
  measure()
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(measure)
    ro.observe(el)
  }
  el.addEventListener('dblclick', (e) => {
    if ((e.target as Element).closest('.an__node, .an__zoom')) return
    for (const n of simNodes.values()) { n.fx = null; n.fy = null }
    kick(0.3)
  })
  sync()
})
onBeforeUnmount(() => {
  ro?.disconnect()
  if (raf) cancelAnimationFrame(raf)
  sim.stop()
})

defineExpose({ fit, focusOn, relayout, cancelConnect: () => { connectFrom.value = null } })
</script>

<style>
.an {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 320px;
  overflow: hidden;
  background: var(--graph-bg);
  touch-action: none;
  user-select: none;
  outline: none;
  cursor: grab;
}
.an.is-panning { cursor: grabbing; }
.an--connect { cursor: crosshair; }
.an__svg { display: block; }
.an__bg { pointer-events: all; }
.an__grid-dot { fill: var(--graph-grid); }

.an__link-line { stroke: var(--border-strong); stroke-linecap: round; transition: stroke 120ms, opacity 160ms; }
.an__link-hit { stroke: transparent; pointer-events: stroke; }
.an__link.is-focus .an__link-line { stroke: var(--accent); }
.an__link.is-dim .an__link-line { opacity: 0.28; }
.an--cut .an__link-hit { cursor: pointer; }
.an--cut .an__link.is-hover .an__link-line { stroke: var(--danger); stroke-width: 3.5; stroke-dasharray: 6 4; opacity: 1; }
.an--view .an__link-hit, .an--connect .an__link-hit { pointer-events: none; }

.an__pending line { stroke-dasharray: 5 5; stroke-linecap: round; animation: an-dash 0.8s linear infinite; }
.an__pending .is-add { stroke: var(--success); }
.an__pending .is-remove { stroke: var(--danger); }
@keyframes an-dash { to { stroke-dashoffset: -10; } }
.an__rubber { stroke: var(--accent); stroke-dasharray: 6 5; stroke-linecap: round; pointer-events: none; }

.an__node { cursor: pointer; transition: opacity 160ms; }
.an.is-dragging .an__node { cursor: grabbing; }
.an--connect .an__node { cursor: crosshair; }
.an__dot { stroke: var(--surface); transition: r 200ms var(--ease); }
.an__node.is-ai .an__dot { stroke: var(--muted); stroke-dasharray: 2.5 2; fill-opacity: 0.55; }
.an__node:hover .an__dot { filter: brightness(1.08); }
.an__node.is-dim { opacity: 0.25; }
.an__node.is-match .an__dot { stroke: var(--warning); }
.an__halo { fill: var(--accent-ring); stroke: var(--accent); stroke-width: 1.5; pointer-events: none; }
.an__node.is-from .an__halo { fill: var(--success-soft); stroke: var(--success); }
.an__node.is-target .an__dot { stroke: var(--success); stroke-width: 3; }
.an__label {
  fill: var(--text-soft);
  font-weight: 550;
  paint-order: stroke;
  stroke: var(--graph-bg);
  stroke-width: 3px;
  stroke-linejoin: round;
  pointer-events: none;
}
.an__node.is-selected .an__label { fill: var(--text); font-weight: 650; }

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
.an__hint {
  position: absolute;
  left: 50%;
  top: 14px;
  transform: translateX(-50%);
  max-width: calc(100% - 32px);
  padding: 7px 14px;
  border-radius: 999px;
  background: var(--text);
  color: var(--bg);
  font-size: var(--text-sm);
  font-weight: 550;
  box-shadow: var(--shadow);
  pointer-events: none;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.an__tip {
  position: absolute;
  transform: translate(-50%, -100%);
  min-width: 140px;
  max-width: 260px;
  padding: 9px 11px;
  border-radius: var(--radius-sm);
  background: var(--surface-raised);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-lg);
  pointer-events: none;
  font-size: var(--text-xs);
  z-index: 3;
}
.an__tip-title { font-weight: 650; font-size: var(--text-sm); margin-bottom: 4px; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.an__tip-row { display: flex; justify-content: space-between; gap: 12px; color: var(--muted); }
.an__tip-row b { color: var(--text-soft); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
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
</style>
