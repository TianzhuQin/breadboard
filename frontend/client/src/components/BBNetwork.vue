<template>
  <div class="bb-network" :style="{ height }">
    <!-- What the player may do right now. The same bar then shows the confirmation and the server's
         answer, so the picture doesn't move; once shown it keeps its place. -->
    <div v-if="barReserved || reserveEditing" ref="bar" class="bb-network__bar" tabindex="-1">
      <!-- Fade in only: the confirmation must exist at once so focus can move to it, and nothing may
           wait for a fade-out, which browsers pause in background tabs. -->
      <transition name="bb-network-fade">
        <div
          v-if="proposal"
          key="confirm"
          ref="confirm"
          class="bb-network__confirm"
          role="dialog"
          :aria-labelledby="uid('confirm-text')"
          @keydown.esc.prevent="cancelProposal"
        >
          <span :id="uid('confirm-text')" class="bb-network__confirm-text">{{ proposalText }}</span>
          <button ref="cancelButton" type="button" class="bb-network__btn bb-network__btn--ghost" @click="cancelProposal">Cancel</button>
          <button
            type="button"
            class="bb-network__btn"
            :class="proposal.op === 'remove' ? 'bb-network__btn--danger' : 'bb-network__btn--primary'"
            @click="confirmProposal"
          >{{ proposal.op === 'remove' ? 'Cut tie' : 'Connect' }}</button>
        </div>
        <div v-else-if="toast" key="toast" class="bb-network__toast" :class="{ 'bb-network__toast--error': !toast.ok }">{{ toast.text }}</div>
        <div v-else-if="hint" key="hint" class="bb-network__hint">{{ hint }}</div>
      </transition>
    </div>
    <!-- one polite live region that is always there, so screen readers hear hints and answers -->
    <div class="bb-network__sr" aria-live="polite" aria-atomic="true">{{ liveText }}</div>

    <div ref="stage" class="bb-network__stage">
      <svg
        class="bb-network__svg"
        :viewBox="viewBox"
        preserveAspectRatio="xMidYMid meet"
        role="group"
        :aria-label="ariaLabel"
        @pointermove="onPointerMove"
        @pointerleave="hoverPoint = null"
        @click.self="clearSelection"
      >
        <defs>
          <filter :id="uid('shadow')" x="-50%" y="-50%" width="200%" height="200%">
            <feDropShadow dx="0" dy="5" stdDeviation="7" flood-color="#0f172a" flood-opacity="0.16" />
          </filter>
          <radialGradient v-for="g in gradients" :key="g.id" :id="g.id" cx="35%" cy="28%" r="80%">
            <stop offset="0%" :stop-color="g.light" />
            <stop offset="100%" :stop-color="g.base" />
          </radialGradient>
        </defs>

        <!-- orbit guide for the ring layout -->
        <circle v-if="isRing && others.length > 1" class="bb-network__orbit" cx="0" cy="0" :r="ringR" />

        <!-- ties -->
        <g class="bb-network__edges">
          <g v-for="e in drawnEdges" :key="e.id" class="bb-network__edge" :class="e.classes">
            <path :d="e.d" class="bb-network__edge-line" />
            <!-- a wide invisible line that is easy to hit; every tie has one, for edgeClick -->
            <path
              :d="e.d"
              class="bb-network__edge-hit"
              :role="e.interactive ? 'button' : 'img'"
              :tabindex="e.interactive ? 0 : -1"
              :focusable="e.interactive ? 'true' : 'false'"
              :aria-label="e.ariaLabel"
              @mouseenter="hoverEdge = e.key"
              @mouseleave="hoverEdge = null"
              @focus="onEdgeFocus(e, $event)"
              @blur="hoverEdge = null"
              @click="onEdgeClick(e, $event)"
              @keydown.enter.prevent="onEdgeClick(e, $event)"
              @keydown.space.prevent="onEdgeClick(e, $event)"
            >
              <title v-if="e.title">{{ e.title }}</title>
            </path>
            <g v-if="e.selected" :transform="`translate(${e.mx}, ${e.my})`" class="bb-network__scissors">
              <circle r="15" />
              <path d="M-5 -5 L5 5 M5 -5 L-5 5" />
            </g>
          </g>
          <!-- preview of a tie being added, following the mouse -->
          <line
            v-if="addPreview"
            class="bb-network__edge-preview"
            :x1="addPreview.x1" :y1="addPreview.y1" :x2="addPreview.x2" :y2="addPreview.y2"
          />
        </g>

        <!-- people -->
        <g class="bb-network__nodes">
          <g
            v-for="n in drawnNodes"
            :key="n.id"
            class="bb-network__node"
            :class="n.classes"
            :transform="`translate(${n.x}, ${n.y})`"
            :role="n.interactive ? 'button' : null"
            :tabindex="n.interactive ? 0 : null"
            :aria-label="n.ariaLabel"
            :aria-pressed="n.interactive ? String(n.selected) : null"
            @click="onNodeClick(n, $event)"
            @keydown.enter.prevent="onNodeClick(n, $event)"
            @keydown.space.prevent="onNodeClick(n, $event)"
          >
            <title v-if="n.title">{{ n.title }}</title>
            <circle v-if="n.isEgo" class="bb-network__halo" :r="n.r + 12" />
            <circle class="bb-network__disc" :r="n.r" :fill="`url(#${n.gradient})`" :filter="`url(#${uid('shadow')})`" />
            <circle v-if="n.selected" class="bb-network__selection" :r="n.r + 7" />
            <slot name="node-content" :node="n.node" :radius="n.r" :is-ego="n.isEgo">
              <text v-if="n.isEgo" class="bb-network__ego-label" :style="{ fill: n.ink }" text-anchor="middle" dominant-baseline="central">{{ egoLabel }}</text>
              <text v-else-if="n.initials" class="bb-network__initials" :style="{ fontSize: `${Math.round(n.r * 0.57)}px`, fill: n.ink }" text-anchor="middle" dominant-baseline="central">{{ n.initials }}</text>
            </slot>
            <text v-if="!n.isEgo && n.shortLabel" class="bb-network__label" :style="{ fontSize: `${labelSize}px` }" text-anchor="middle" :y="n.labelY">{{ n.shortLabel }}</text>
            <g v-if="n.badge" :transform="`translate(0, ${n.badgeY})`" class="bb-network__badge">
              <rect :x="-badgeWidth(n.badge) / 2" y="-12" :width="badgeWidth(n.badge)" height="24" rx="12" />
              <text text-anchor="middle" dominant-baseline="central">{{ n.badge }}</text>
            </g>
          </g>
        </g>
      </svg>
    </div>

    <!-- The same changes as a list: for keyboards, screen readers, and when tapping the picture is
         hard. Folded away by default, so a long list never pushes the picture around. -->
    <!-- (Kept, but out of sight, while editing is off: the picture doesn't jump when it comes back.) -->
    <div v-if="showMoveList && (movesReserved || reserveEditing)" class="bb-network__moves" :class="{ 'is-off': !edits.enabled }" :aria-hidden="edits.enabled ? null : 'true'">
      <button
        ref="movesToggle"
        type="button"
        class="bb-network__moves-toggle"
        :aria-expanded="String(movesOpen)"
        :aria-controls="uid('moves')"
        :disabled="!moves.length || (!movesPossible && !movesOpen)"
        @click="movesOpen = !movesOpen"
      >{{ movesToggleText }}</button>
      <ul v-if="movesOpen && moves.length" :id="uid('moves')" class="bb-network__move-list">
        <li v-for="m in moves" :key="m.key" class="bb-network__move-item">
          <button
            type="button"
            class="bb-network__move"
            :class="'is-' + m.op"
            :disabled="!m.available"
            @click="propose(m.op, m.a, m.b)"
          >{{ m.text }}</button>
          <span v-if="!m.available && m.reason" class="bb-network__move-reason">{{ m.reason }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import { forceSimulation, forceLink, forceManyBody, forceCenter, forceCollide } from 'd3-force'

type Point = { x: number, y: number }
type AnyNode = { id: string, data: any, [key: string]: any }
type AnyEdge = { id: number | string, source: AnyNode, target: AnyNode, data: any }
type Candidate = { op: 'add' | 'remove', a: string, b: string, available?: boolean, reason?: string }
type Move = { op: 'add' | 'remove', a: string, b: string, turn?: number }

const PALETTE: Record<string, [string, string]> = {
  ego: ['#8b8df8', '#4f46e5'],
  neutral: ['#ffffff', '#dfe4ee'],
}
// A stage smaller than this (px) hasn't been laid out yet.
const MIN_STAGE = 48
// gap kept between neighbors on the ring, and room for a name under (or over) a person
const NODE_GAP = 18
const LABEL_ROOM = 30
const BADGE_ROOM = 30
const SUCCESS_TOAST_MS = 2600
const ERROR_TOAST_MS = 7000
const PENDING_MS = 8000

let instances = 0

/**
 * The network as a participant sees it: themselves in the middle, their neighbors around them,
 * and (with g.setVisibility("egoTies")) the ties among those neighbors. Neighbors who know each
 * other sit next to each other on the circle, and longer ties among them curve around the outside.
 * Neighbors without a name get one ("Person A", ...), so every change can be described in words.
 *
 * When the experiment allows it (edgeEdits.allow on the server), ties can be cut by clicking them
 * and two people tied by clicking one and then the other. Every change is confirmed first and
 * checked by the server, and the same changes are offered as a list for keyboards and screen
 * readers.
 *
 * Takes the same `player` and `graph` objects as SVGGraph, sets node.isEgo like it does, and emits
 * nodeClick / edgeClick for clicks on any person or tie. SVGGraph's layout props (width, height,
 * centerEgo, nodeStroke, ...) and edge label slots don't apply here.
 */
export default Vue.extend({
  name: 'BBNetwork',
  props: {
    player: { type: Object, required: true },
    graph: { type: Object, required: true },
    /** "ring": you in the centre, neighbors on a circle. "force": a force-directed layout. "auto": ring when you are in the graph. */
    layout: { type: String, default: 'auto' },
    height: { type: String, default: '100%' },
    /** Fill colour for a node: a CSS colour, or a function (node, player) => colour. */
    nodeColor: { type: [String, Function], default: null },
    /** Text under a node: a function (node, player) => string. Defaults to its name or label. */
    nodeLabel: { type: Function, default: null },
    /** Small pill under the label, e.g. "5 ties · 3 R": a function (node, player) => string. */
    nodeBadge: { type: Function, default: null },
    nodeRadius: { type: [Number, Function], default: 30 },
    egoLabel: { type: String, default: 'You' },
    /** Offer the changes as a list too, under the picture. */
    showMoveList: { type: Boolean, default: true },
    /**
     * Keep room for the editing bar and the list from the start, so the picture doesn't shrink
     * when a later round allows editing. Automatic once the server has sent player.edgeEdits.
     */
    editable: { type: Boolean, default: false },
    ariaLabel: { type: String, default: 'Your network' },
  },
  data () {
    return {
      instanceId: ++instances,
      current: {} as Record<string, Point>,
      targets: {} as Record<string, Point>,
      ring: [] as string[],
      // the ring's radius and the node size factor, fitted to the space available; fitScale < 1
      // shrinks the whole picture when even that doesn't fit
      ringR: 170,
      nodeScale: 1,
      fitScale: 1,
      size: { w: 0, h: 0 },
      destroyed: false,
      resizeObserver: null as any,
      animation: 0 as number,
      fallbackNames: {} as Record<string, string>,
      hoverEdge: null as string | null,
      hoverPoint: null as Point | null,
      selectedNode: null as string | null,
      proposal: null as null | Move,
      opener: null as HTMLElement | SVGElement | null,
      pending: {} as Record<string, number>,
      toast: null as null | { ok: boolean, text: string },
      toastTimer: 0 as any,
      resultHandler: null as any,
      barReserved: false,
      movesReserved: false,
      // "tap" on touch screens, "click" with a mouse
      press: typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(pointer: coarse)').matches ? 'tap' : 'click',
      movesOpen: false,
      movesAutoOpened: false,
      // people drawn too small to tap reliably (then the list opens by itself)
      tooSmallToTap: false,
    }
  },
  computed: {
    egoId (): string { return this.player && this.player.id },
    nodes (): AnyNode[] { return (this.graph && this.graph.nodes) || [] },
    edges (): AnyEdge[] { return (this.graph && this.graph.edges) || [] },
    ego (): AnyNode | undefined { return this.nodes.find(n => n.id === this.egoId) },
    others (): AnyNode[] {
      return this.nodes.filter(n => n.id !== this.egoId).slice().sort((a, b) => String(a.id).localeCompare(String(b.id)))
    },
    isRing (): boolean {
      return this.layout === 'ring' || (this.layout === 'auto' && !!this.ego)
    },
    // The largest radius of a person, which the layout must make room for. A nodeRadius function
    // is only ever called with real nodes.
    baseRadius (): number {
      if (typeof this.nodeRadius === 'number') return this.nodeRadius
      let max = 0
      for (const n of this.nodes) max = Math.max(max, Number((this.nodeRadius as any)(n, this.player)) || 0)
      return max || 30
    },
    reserveEditing (): boolean {
      return this.editable || (!!this.player && this.player.edgeEdits !== undefined && this.player.edgeEdits !== null)
    },
    // The ring's radius with plenty of room; less space shrinks it (fitRing).
    idealRingRadius (): number {
      const n = this.others.length
      return n === 1 ? 200 : Math.max(170, Math.min(420, 60 + n * 28))
    },
    labelSize (): number {
      return Math.round(15 * Math.max(0.8, this.nodeScale))
    },
    // How wide a name may be before it is shortened: the space between neighbors on the ring.
    labelRoom (): number {
      const n = this.ring.length
      return this.isRing && n > 1 ? Math.max(48, (2 * Math.PI * this.ringR) / n - 10) : 160
    },
    // The graph is updated in place, so layout watches a summary of who is in it and who is tied.
    structure (): string {
      return this.nodes.map(n => n.id).sort().join(',') + '/' + Object.keys(this.edgeKeys).sort().join(',')
    },
    layoutKey (): string {
      return `${this.isRing}|${Math.round(this.baseRadius)}|${!!this.nodeBadge}`
    },
    // Which pairs are tied, as "a|b" keys; layout and editing both use it.
    edgeKeys (): Record<string, boolean> {
      const keys: Record<string, boolean> = {}
      for (const e of this.edges) keys[pairKey(e.source.id, e.target.id)] = true
      return keys
    },
    edits (): any {
      return (this.player && this.player.edgeEdits) || { enabled: false }
    },
    candidates (): Candidate[] | null {
      return Array.isArray(this.edits.candidates) ? this.edits.candidates : null
    },
    candidateIndex (): Record<string, Candidate> {
      const index: Record<string, Candidate> = {}
      for (const c of this.candidates || []) index[`${c.op}|${pairKey(c.a, c.b)}`] = c
      return index
    },
    nodeById (): Record<string, AnyNode> {
      const map: Record<string, AnyNode> = {}
      for (const n of this.nodes) map[n.id] = n
      return map
    },
    // One gradient per distinct colour, with ids from their position in this list.
    gradientIndex (): Record<string, string> {
      const index: Record<string, string> = {}
      for (const n of this.nodes) {
        const base = this.colorsFor(n)[1]
        if (!(base in index)) index[base] = this.uid(`g${Object.keys(index).length}`)
      }
      return index
    },
    gradients (): { id: string, light: string, base: string }[] {
      const seen: Record<string, boolean> = {}
      const list: { id: string, light: string, base: string }[] = []
      for (const n of this.nodes) {
        const [light, base] = this.colorsFor(n)
        const id = this.gradientIndex[base]
        if (!seen[id]) {
          seen[id] = true
          list.push({ id, light, base })
        }
      }
      return list
    },
    // The stage has a real size (see fitRing).
    measured (): boolean {
      return this.size.w >= MIN_STAGE && this.size.h >= MIN_STAGE
    },
    viewBox (): string {
      // Ring with a known size: one unit is one pixel, so text and people stay the same size on
      // every screen and the ring fits itself to the space (fitRing); fitScale shrinks it all only
      // when even the smallest ring doesn't fit. Labels hang below the bottom row, so the picture
      // sits a little high.
      if (this.isRing && this.measured && this.fitScale > 0) {
        const w = this.size.w / this.fitScale
        const h = this.size.h / this.fitScale
        return `${-w / 2} ${-h / 2 + 8} ${w} ${h}`
      }
      const pts = Object.values(this.targets)
      if (!pts.length) return '-300 -300 600 600'
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
      for (const p of pts) {
        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x)
        minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y)
      }
      // room for the curved ties around the ring, and for labels and badges under the nodes
      const curve = this.isRing ? this.maxCurveBulge : 0
      const pad = 70 + curve
      const w = Math.max(maxX - minX + pad * 2, 420)
      const h = Math.max(maxY - minY + pad * 2 + 50, 420)
      const cx = (minX + maxX) / 2
      const cy = (minY + maxY) / 2 + 20
      return `${cx - w / 2} ${cy - h / 2} ${w} ${h}`
    },
    maxCurveBulge (): number {
      let max = 0
      for (const e of this.edges) {
        const steps = this.ringSteps(e.source.id, e.target.id)
        if (steps != null && steps > 1) max = Math.max(max, this.bulgeFor(steps))
      }
      return max
    },
    // What each person looks like and whether they can be chosen; doesn't depend on the animated
    // positions, so it isn't recomputed on every frame.
    nodeStates (): Record<string, any> {
      const states: Record<string, any> = {}
      for (const node of this.nodes) {
        const isEgo = node.id === this.egoId
        const r = isEgo ? Math.round(this.radiusFor(node) * 1.35) : this.radiusFor(node)
        const label = isEgo ? '' : this.labelFor(node)
        // what the participant hears and reads in the list, even when no label is drawn
        const spoken = isEgo ? this.egoLabel : this.nameOf(node.id)
        const selectable = this.nodeSelectable(node.id)
        const selected = this.selectedNode === node.id ||
          (!!this.proposal && this.proposal.op === 'add' && (this.proposal.a === node.id || this.proposal.b === node.id))
        const blockedReason = this.selectedNode && this.selectedNode !== node.id ? this.addBlockedReason(this.selectedNode, node.id) : null
        const name = isEgo ? 'yourself' : spoken
        // a made-up name that doesn't fit is left out: the letter is on the disc anyway
        let shortLabel = shorten(label, this.labelRoom, this.labelSize)
        if (shortLabel !== label && label === this.fallbackNames[node.id]) shortLabel = ''
        // On the ring, people in the upper part get their name above them, so the line to the
        // participant in the middle doesn't run through it.
        const t = this.targets[node.id]
        const above = this.isRing && !isEgo && !!t && t.y < -0.25 * this.ringR
        const labelY = above ? -(r + 10) : r + this.labelSize + 8
        const badgeY = above
          ? -(r + (label ? this.labelSize + 30 : 22))
          : r + (label ? this.labelSize + 30 : 22)
        states[node.id] = {
          id: node.id,
          node,
          r,
          isEgo,
          label,
          shortLabel,
          initials: !isEgo && spoken ? initialsOf(label || spoken) : '',
          labelY,
          badgeY,
          badge: this.nodeBadge ? this.nodeBadge(node, this.player) : '',
          gradient: this.gradientIndex[this.colorsFor(node)[1]],
          // white or dark letters, whichever reads better on this person's colour
          ink: inkOn(this.colorsFor(node)),
          selected,
          interactive: selectable,
          blockedReason,
          title: blockedReason || (selectable ? (this.selectedNode ? `Connect with ${name}` : `Choose ${name} to introduce`) : (label !== shortLabel ? label : '')),
          ariaLabel: spoken,
          classes: {
            'is-ego': isEgo,
            'is-ai': node.data && node.data.ai === 1,
            'is-selectable': selectable,
            'is-blocked': !!blockedReason,
            'is-dimmed': !!this.selectedNode && !isEgo && this.selectedNode !== node.id && !selectable,
          },
        }
      }
      return states
    },
    drawnNodes (): any[] {
      return this.nodes.map(node => {
        const p = this.current[node.id] || this.targets[node.id] || { x: 0, y: 0 }
        return { ...this.nodeStates[node.id], x: p.x, y: p.y }
      })
    },
    edgeStates (): Record<string, any> {
      const states: Record<string, any> = {}
      for (const edge of this.edges) {
        const a = edge.source.id, b = edge.target.id
        const key = pairKey(a, b)
        const own = a === this.egoId || b === this.egoId
        const removable = this.removeAllowed(a, b)
        const blocked = this.removeBlockedReason(a, b)
        const selected = !!this.proposal && this.proposal.op === 'remove' && pairKey(this.proposal.a, this.proposal.b) === key
        const between = `${this.nameOf(a)} and ${this.nameOf(b)}`
        const title = blocked
          ? `Tie between ${between}: ${blocked}`
          : (removable ? `Cut the tie between ${between}` : '')
        states[String(edge.id)] = {
          id: String(edge.id),
          key,
          edge,
          own,
          removable,
          blocked,
          selected,
          interactive: removable || !!blocked,
          title,
          ariaLabel: title || `Tie between ${between}`,
        }
      }
      return states
    },
    drawnEdges (): any[] {
      return this.edges.map(edge => {
        const s = this.edgeStates[String(edge.id)]
        const a = this.current[edge.source.id] || this.targets[edge.source.id] || { x: 0, y: 0 }
        const b = this.current[edge.target.id] || this.targets[edge.target.id] || { x: 0, y: 0 }
        const shape = this.edgeShape(edge.source.id, edge.target.id, a, b, s.own)
        return {
          ...s,
          d: shape.d,
          mx: shape.mx,
          my: shape.my,
          classes: {
            'is-own': s.own,
            'is-among': !s.own,
            'is-removable': s.removable,
            'is-blocked': !!s.blocked,
            'is-clickable': s.interactive || !!this.$listeners.edgeClick,
            'is-hover': this.hoverEdge === s.key,
            'is-selected': s.selected,
          },
        }
      })
    },
    addPreview (): any {
      if (!this.selectedNode || !this.hoverPoint) return null
      const a = this.current[this.selectedNode]
      return a ? { x1: a.x, y1: a.y, x2: this.hoverPoint.x, y2: this.hoverPoint.y } : null
    },
    proposalText (): string {
      if (!this.proposal) return ''
      // ("you" first: "Cut the tie between you and Person B?")
      const [first, second] = this.proposal.b === this.egoId ? [this.proposal.b, this.proposal.a] : [this.proposal.a, this.proposal.b]
      const a = this.nameOf(first)
      const b = this.nameOf(second)
      return this.proposal.op === 'remove' ? `Cut the tie between ${a} and ${b}?` : `Connect ${a} and ${b}?`
    },
    // Changes that can be made right now, as the picture shows them.
    canCut (): boolean {
      return Object.values(this.edgeStates).some((s: any) => s.removable)
    },
    canConnect (): boolean {
      return this.nodes.some(n => this.nodeSelectable(n.id))
    },
    hint (): string {
      const e = this.edits
      if (!e.enabled) return ''
      if (this.selectedNode) return `Now choose who ${this.nameOf(this.selectedNode)} should be connected with.`
      const parts: string[] = []
      const how: string[] = []
      if (e.remove && this.canCut) {
        parts.push(e.remainingRemoves != null ? `cut ${e.remainingRemoves} tie${e.remainingRemoves === 1 ? '' : 's'}` : 'cut ties')
        how.push(`${this.press} a tie to cut it`)
      }
      if (e.add && this.canConnect) {
        parts.push(e.remainingAdds != null ? `make ${e.remainingAdds} new tie${e.remainingAdds === 1 ? '' : 's'}` : 'make new ties')
        how.push(`${this.press} two people to connect them`)
      }
      if (!parts.length) return e.add || e.remove ? 'There is no change you can make right now.' : 'You have made all the changes you can for now.'
      const instructions = how.join('; ')
      return `You can ${parts.join(' and ')}. ${instructions.charAt(0).toUpperCase()}${instructions.slice(1)}.`
    },
    liveText (): string {
      if (this.toast) return this.toast.text
      if (this.proposal) return this.proposalText
      return this.hint
    },
    // The changes on offer: the server's candidates, or else every change the picture allows.
    moves (): any[] {
      if (!this.edits.enabled) return []
      const list: Move[] = []
      if (this.candidates) {
        for (const c of this.candidates) {
          if (this.nodeById[c.a] && this.nodeById[c.b]) list.push({ op: c.op, a: c.a, b: c.b })
        }
      } else {
        if (this.edits.remove) {
          for (const s of Object.values(this.edgeStates) as any[]) {
            if (s.removable) list.push({ op: 'remove', a: s.edge.source.id, b: s.edge.target.id })
          }
        }
        if (this.edits.add) {
          const ids = this.nodes.map(n => n.id)
          for (let i = 0; i < ids.length; i++) {
            for (let j = i + 1; j < ids.length; j++) {
              if (this.addAllowed(ids[i], ids[j])) list.push({ op: 'add', a: ids[i], b: ids[j] })
            }
          }
        }
      }
      return list.map(m => {
        const c = this.candidateIndex[`${m.op}|${pairKey(m.a, m.b)}`]
        return {
          ...m,
          key: `${m.op}:${pairKey(m.a, m.b)}`,
          available: m.op === 'add' ? this.addAllowed(m.a, m.b) : (this.connected(m.a, m.b) && this.removeAllowed(m.a, m.b)),
          reason: c && c.available === false ? (c.reason || 'Not available') : '',
          text: moveText(m.op, m.a === this.egoId ? m.b : m.a, m.a === this.egoId || m.b === this.egoId ? null : m.b, this.nameOf),
        }
      })
    },
    // Worth opening: something can be done, or the experiment says why something can't.
    movesPossible (): boolean { return this.moves.some((m: any) => m.available || m.reason) },
    movesToggleText (): string {
      if (!this.moves.length || !this.movesPossible) return 'No changes to list right now'
      if (this.movesOpen) return 'Hide the list of changes'
      const n = this.moves.filter(m => m.available).length
      return `Show the changes as a list (${n} possible)`
    },
    // When anything a selection or proposal depends on changes, check they still make sense.
    editingKey (): string {
      const e = this.edits
      return [this.structure, e.enabled, e.add, e.remove, e.scope, e.remainingAdds, e.remainingRemoves,
        (this.candidates || []).map(c => `${c.op}${c.a}${c.b}${c.available}`).join(',')].join('|')
    },
  },
  watch: {
    structure: {
      immediate: true,
      handler () {
        this.markEgo()
        this.nameUnnamed()
        this.relayout()
      },
    },
    layoutKey () { this.relayout() },
    editingKey () { this.dropStaleSelection() },
    // A new turn (e.g. the next round) or no editing at all: a question still open is about a
    // turn that is over.
    'edits.turn' () { this.dropProposal() },
    'edits.enabled' (enabled: boolean) {
      if (!enabled) this.dropProposal()
    },
    tooSmallToTap (small: boolean) { this.openListIfNeeded(small) },
    hint (text: string) { if (text) this.barReserved = true },
    proposal (p: any) { if (p) this.barReserved = true },
    moves (list: any[]) {
      if (list.length) this.movesReserved = true
      this.openListIfNeeded(this.tooSmallToTap)
    },
  },
  created () {
    // Listen for the server's answers before anything else can go wrong.
    const bb = (window as any).Breadboard
    if (bb && bb.on) {
      this.resultHandler = (...args: any[]) => {
        const r = Array.isArray(args[0]) ? args[0][0] : args[0]
        if (!r) return
        if (r.op && r.a && r.b) this.$delete(this.pending, `${r.op}:${pairKey(r.a, r.b)}`)
        if (!r.ok) {
          this.showToast(false, r.reason || 'That change was not made.')
        } else if (r.applied === false) {
          // the experiment collects proposals instead of changing the network now
          this.showToast(true, 'Your change was sent.')
        } else {
          this.showToast(true, r.op === 'remove' ? 'Tie cut.' : 'Connected.')
        }
      }
      bb.on('bb:edge-edit-result', this.resultHandler)
    }
    if (this.hint) this.barReserved = true
    if (this.moves.length) this.movesReserved = true
  },
  mounted () {
    const stage = this.$refs.stage as HTMLElement | undefined
    if (stage && typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(entries => {
        const box = entries[0].contentRect
        this.setSize(Math.round(box.width), Math.round(box.height))
      })
      this.resizeObserver.observe(stage)
      // and once more after the page has settled (a transition may still be sizing it now)
      this.remeasureSoon()
    }
  },
  beforeDestroy () {
    this.destroyed = true
    if (this.resizeObserver) this.resizeObserver.disconnect()
    cancelAnimationFrame(this.animation)
    clearTimeout(this.toastTimer)
    const bb = (window as any).Breadboard
    if (bb && bb.off && this.resultHandler) bb.off('bb:edge-edit-result', this.resultHandler)
  },
  methods: {
    uid (name: string): string { return `bbn${this.instanceId}-${name}` },
    radiusFor (node: AnyNode): number {
      const r = typeof this.nodeRadius === 'function' ? (this.nodeRadius as any)(node, this.player) : (this.nodeRadius as number)
      return Math.round(r * this.nodeScale)
    },
    labelFor (node: AnyNode): string {
      if (this.nodeLabel) {
        const custom = this.nodeLabel(node, this.player)
        if (custom != null) return String(custom)
      }
      const d = node.data || {}
      return d.name || d.label || this.fallbackNames[node.id] || ''
    },
    nameOf (id: string): string {
      if (id === this.egoId) return 'you'
      const n = this.nodeById[id]
      return (n && this.labelFor(n)) || this.fallbackNames[id] || 'this person'
    },
    colorsFor (node: AnyNode): [string, string] {
      let c: any = this.nodeColor
      if (typeof c === 'function') c = c(node, this.player)
      if (!c && node.data && typeof node.data.color === 'string') c = node.data.color
      if (!c) return node.id === this.egoId ? PALETTE.ego : PALETTE.neutral
      return [lighten(c), c]
    },
    // Like SVGGraph, so node functions and slots written for it (node.isEgo) keep working.
    markEgo () {
      for (const node of this.nodes) {
        if (node.isEgo !== (node.id === this.egoId)) node.isEgo = node.id === this.egoId
      }
    },
    // Every neighbor gets a stand-in name, "Person A", "Person B", ..., in the order they first
    // appear, so they keep it for the whole session. It is used wherever they have no name or
    // label of their own (or a nodeLabel function returns none).
    nameUnnamed () {
      let next = Object.keys(this.fallbackNames).length
      let added = false
      const names = { ...this.fallbackNames }
      // On the ring, clockwise from the top: Person A at 12 o'clock, B next to them, and so on.
      const order = this.isRing ? this.ringOrder() : []
      const place = (id: string) => { const i = order.indexOf(id); return i < 0 ? Infinity : i }
      const unnamed = this.others.filter(n => !names[n.id]).sort((a, b) => place(a.id) - place(b.id))
      for (const node of unnamed) {
        names[node.id] = `Person ${next < 26 ? String.fromCharCode(65 + next) : next + 1}`
        next++
        added = true
      }
      if (added) this.fallbackNames = names
    },
    // --- layout ---
    relayout () {
      const targets: Record<string, Point> = {}
      if (!this.nodes.length) {
        this.ring = []
      } else if (this.isRing) {
        if (this.ego) targets[this.ego.id] = { x: 0, y: 0 }
        const order = this.ringOrder()
        const n = order.length
        this.ring = order
        this.fitRing()
        const r = this.ringR
        order.forEach((id, i) => {
          const angle = -Math.PI / 2 + (2 * Math.PI * i) / Math.max(n, 1)
          targets[id] = { x: Math.round(r * Math.cos(angle)), y: Math.round(r * Math.sin(angle)) }
        })
      } else {
        const simNodes: any[] = this.nodes.map(n => {
          const prev = this.current[n.id]
          return { id: n.id, x: prev ? prev.x : (Math.random() - 0.5) * 300, y: prev ? prev.y : (Math.random() - 0.5) * 300 }
        })
        const byId: Record<string, any> = {}
        for (const s of simNodes) byId[s.id] = s
        const links = this.edges
          .filter(e => byId[e.source.id] && byId[e.target.id])
          .map(e => ({ source: byId[e.source.id], target: byId[e.target.id] }))
        const sim = forceSimulation(simNodes)
          .force('charge', forceManyBody().strength(-520))
          .force('link', forceLink(links).distance(140).strength(0.6))
          .force('center', forceCenter(0, 0))
          .force('collide', forceCollide(this.baseRadius * 1.9))
          .stop()
        for (let i = 0; i < 300; i++) sim.tick()
        for (const s of simNodes) targets[s.id] = { x: Math.round(s.x), y: Math.round(s.y) }
        this.ring = []
        this.nodeScale = 1
        this.fitScale = 1
      }
      if (!sameTargets(this.targets, targets)) {
        this.targets = targets
        this.animate()
      }
    },
    // Fit the ring into the space available: a smaller radius first, then smaller people when they
    // would otherwise overlap, and only when even that doesn't fit, a smaller picture altogether.
    // People never overlap each other or the participant in the middle.
    fitRing () {
      const n = this.ring.length
      const base = this.baseRadius
      let R = this.idealRingRadius
      let scale = 1
      let fit = 1
      // (A stage measured while the page swaps the waiting screen for the game can be a pixel or
      // two across: fitting to that drew the picture inside out, into a corner.)
      if (this.measured && n) {
        let steps = 0
        for (const e of this.edges) {
          const k = this.ringSteps(e.source.id, e.target.id)
          if (k != null) steps = Math.max(steps, k)
        }
        const half = Math.min(this.size.w, this.size.h - 16) / 2
        const outside = (r: number) => r + LABEL_ROOM + (this.nodeBadge ? BADGE_ROOM : 0) + (steps > 1 ? bulge(r, steps) : 0)
        const spacing = (r: number) => (n > 1 ? (n * (2 * r + NODE_GAP)) / (2 * Math.PI) : 0)
        let r = base
        let room = half - outside(r)
        if (room < spacing(r)) {
          scale = Math.max(0.55, room / spacing(r))
          r = base * scale
          room = half - outside(r)
        }
        const egoR = base * scale * 1.35
        const minR = Math.max(egoR + 12 + r + 10, spacing(r))
        // use most of a large screen, but never less than keeps people apart
        R = Math.max(minR, Math.min(Math.max(R, 0.8 * room), room))
        const needed = R + outside(r)
        fit = needed > half ? Math.max(0.05, half / needed) : 1
        this.tooSmallToTap = 2 * r * fit < 24
      }
      this.ringR = Math.round(R)
      this.nodeScale = scale
      this.fitScale = fit
    },
    setSize (w: number, h: number) {
      if (Math.abs(w - this.size.w) > 1 || Math.abs(h - this.size.h) > 1) {
        this.size = { w, h }
        this.relayout()
      }
    },
    measure () {
      const stage = this.$refs.stage as HTMLElement | undefined
      if (!stage || typeof stage.getBoundingClientRect !== 'function') return
      const box = stage.getBoundingClientRect()
      this.setSize(Math.round(box.width), Math.round(box.height))
    },
    // Measure again over the next moments, until the stage has a real size.
    remeasureSoon () {
      let tries = 0
      const again = () => {
        if (this.destroyed) return
        this.measure()
        if (!this.measured && ++tries < 20) setTimeout(again, 150)
      }
      if (typeof requestAnimationFrame === 'function') requestAnimationFrame(again)
      else setTimeout(again, 0)
    },
    // When people are drawn too small to tap, offer the list instead, once.
    openListIfNeeded (small: boolean) {
      if (small && !this.movesAutoOpened && this.moves.length) {
        this.movesAutoOpened = true
        this.movesOpen = true
      }
    },
    /**
     * The order of the neighbors around the circle: people who are tied to each other next to each
     * other, so ties among neighbors are short. The previous order breaks ties, so people move as
     * little as possible when the network changes.
     */
    ringOrder (): string[] {
      const ids = this.others.map(n => n.id)
      const previous = this.ring
      const rank = (id: string) => {
        const i = previous.indexOf(id)
        return i >= 0 ? i : previous.length + ids.indexOf(id)
      }
      const adj: Record<string, string[]> = {}
      for (const id of ids) adj[id] = []
      for (const e of this.edges) {
        const a = e.source.id, b = e.target.id
        if (a !== b && adj[a] && adj[b]) { adj[a].push(b); adj[b].push(a) }
      }
      const byRank = ids.slice().sort((a, b) => rank(a) - rank(b))
      const placed: Record<string, boolean> = {}
      const order: string[] = []
      const openDegree = (id: string) => adj[id].filter(x => !placed[x]).length
      const best = (list: string[]) => list.slice().sort((a, b) => (openDegree(a) - openDegree(b)) || (rank(a) - rank(b)))[0]
      for (const start of byRank) {
        if (placed[start]) continue
        // the start's whole group of neighbors who are (indirectly) tied to each other
        const group: string[] = []
        const queue = [start]
        const seen: Record<string, boolean> = { [start]: true }
        while (queue.length) {
          const id = queue.shift() as string
          group.push(id)
          for (const x of adj[id]) if (!seen[x]) { seen[x] = true; queue.push(x) }
        }
        let cur: string | undefined = best(group)
        while (cur) {
          placed[cur] = true
          order.push(cur)
          const next: string[] = adj[cur].filter(x => !placed[x])
          const rest = group.filter(x => !placed[x])
          cur = next.length ? best(next) : (rest.length ? best(rest) : undefined)
        }
      }
      return keepPlaces(order, previous)
    },
    // How many places apart two neighbors are around the circle (null when not both on it).
    ringSteps (a: string, b: string): number | null {
      const n = this.ring.length
      const i = this.ring.indexOf(a), j = this.ring.indexOf(b)
      if (i < 0 || j < 0) return null
      const d = Math.abs(i - j)
      return Math.min(d, n - d)
    },
    bulgeFor (steps: number): number {
      return bulge(this.baseRadius * this.nodeScale, steps)
    },
    // A straight line, or for a tie between neighbors who are not next to each other on the circle,
    // a curve around the outside so it doesn't run through the middle.
    edgeShape (idA: string, idB: string, a: Point, b: Point, own: boolean): { d: string, mx: number, my: number } {
      const straight = { d: `M${a.x} ${a.y}L${b.x} ${b.y}`, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 }
      if (own || !this.isRing) return straight
      const steps = this.ringSteps(idA, idB)
      if (steps == null) return straight
      const ra = Math.hypot(a.x, a.y), rb = Math.hypot(b.x, b.y)
      const R = (ra + rb) / 2
      const ta = Math.atan2(a.y, a.x)
      let delta = Math.atan2(b.y, b.x) - ta
      while (delta > Math.PI) delta -= 2 * Math.PI
      while (delta < -Math.PI) delta += 2 * Math.PI
      // the participant's disc plus its halo, with a little air
      const egoRoom = this.baseRadius * this.nodeScale * 1.35 + 20
      if (steps <= 1 && R * Math.cos(delta / 2) > egoRoom) return straight
      if (Math.abs(Math.abs(delta) - Math.PI) < 1e-6) delta = Math.PI
      const mid = ta + delta / 2
      const apex = R + this.bulgeFor(steps)
      const control = 2 * apex - R * Math.cos(delta / 2)
      const cx = control * Math.cos(mid), cy = control * Math.sin(mid)
      return {
        d: `M${a.x} ${a.y}Q${cx} ${cy} ${b.x} ${b.y}`,
        mx: 0.25 * a.x + 0.5 * cx + 0.25 * b.x,
        my: 0.25 * a.y + 0.5 * cy + 0.25 * b.y,
      }
    },
    animate () {
      cancelAnimationFrame(this.animation)
      const from: Record<string, Point> = {}
      for (const id of Object.keys(this.targets)) {
        from[id] = this.current[id] || { ...this.targets[id] }
      }
      if (typeof requestAnimationFrame !== 'function' || prefersReducedMotion()) {
        this.current = { ...this.targets }
        return
      }
      const start = performance.now()
      const duration = 650
      const step = (now: number) => {
        const t = Math.min(1, (now - start) / duration)
        const k = 1 - Math.pow(1 - t, 3)
        const next: Record<string, Point> = {}
        for (const id of Object.keys(this.targets)) {
          const a = from[id], b = this.targets[id]
          next[id] = { x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k }
        }
        this.current = next
        if (t < 1) this.animation = requestAnimationFrame(step)
      }
      this.animation = requestAnimationFrame(step)
    },
    // --- what may be edited (the server checks again) ---
    candidateFor (op: string, a: string, b: string): Candidate | undefined {
      return this.candidateIndex[`${op}|${pairKey(a, b)}`]
    },
    isPending (op: string, a: string, b: string): boolean {
      return !!this.pending[`${op}:${pairKey(a, b)}`]
    },
    inScope (a: string, b: string): boolean {
      const ego = this.egoId
      // Without the ties among neighbors on screen, the page can't tell whether two of them are tied
      // already, so it doesn't offer to connect them (the server's refusal would give it away).
      if (this.edits.tiesHidden && a !== ego && b !== ego && !this.connected(a, b)) return false
      switch (this.edits.scope) {
        case 'own': return a === ego || b === ego
        case 'visible': return true
        default: return a !== ego && b !== ego
      }
    },
    removeAllowed (a: string, b: string): boolean {
      if (!this.edits.enabled || !this.edits.remove || this.isPending('remove', a, b)) return false
      if (this.candidates) {
        const c = this.candidateFor('remove', a, b)
        return !!c && c.available !== false
      }
      return this.inScope(a, b)
    },
    removeBlockedReason (a: string, b: string): string | null {
      if (!this.edits.enabled || !this.edits.remove || !this.candidates) return null
      const c = this.candidateFor('remove', a, b)
      return c && c.available === false ? (c.reason || 'Not available') : null
    },
    connected (a: string, b: string): boolean {
      return !!this.edgeKeys[pairKey(a, b)]
    },
    addAllowed (a: string, b: string): boolean {
      if (!this.edits.enabled || !this.edits.add || a === b || this.connected(a, b) || this.isPending('add', a, b)) return false
      if (!this.nodeById[a] || !this.nodeById[b]) return false
      if (this.candidates) {
        const c = this.candidateFor('add', a, b)
        return !!c && c.available !== false
      }
      return this.inScope(a, b)
    },
    addBlockedReason (a: string, b: string): string | null {
      if (!this.edits.enabled || !this.edits.add) return null
      if (this.candidates) {
        const c = this.candidateFor('add', a, b)
        if (c && c.available === false) return c.reason || 'Not available'
      }
      return null
    },
    nodeSelectable (id: string): boolean {
      if (!this.edits.enabled || !this.edits.add) return false
      if (this.selectedNode) return this.selectedNode === id || this.addAllowed(this.selectedNode, id)
      return this.nodes.some(other => other.id !== id && this.addAllowed(id, other.id))
    },
    // A selection or proposal the participant can no longer act on (someone left, a tie changed,
    // the experiment changed what is allowed) would leave the picture stuck, so drop it.
    dropStaleSelection () {
      const sel = this.selectedNode
      if (sel) {
        const present = !!this.nodeById[sel]
        const partner = present && this.nodeSelectable(sel) && this.nodes.some(o => o.id !== sel && this.addAllowed(sel, o.id))
        if (!partner) {
          this.clearSelection()
          if (present && this.edits.enabled && this.edits.add) this.showToast(false, `${this.nameOf(sel)} has no one left to connect with.`)
        }
      }
      const p = this.proposal
      if (p) {
        const still = p.op === 'remove'
          ? this.connected(p.a, p.b) && this.removeAllowed(p.a, p.b)
          : this.addAllowed(p.a, p.b)
        if (!still) {
          const hadFocus = this.focusInConfirm()
          this.proposal = null
          if (this.edits.enabled) this.showToast(false, 'That change is no longer possible.')
          if (hadFocus) this.returnFocus()
        }
      }
    },
    // --- interaction ---
    onPointerMove (ev: PointerEvent) {
      // only a mouse has a pointer to follow; on touch screens the preview line would stick
      if (!this.selectedNode || (ev.pointerType && ev.pointerType !== 'mouse')) return
      const svg = ev.currentTarget as SVGSVGElement
      if (!svg.createSVGPoint) return
      const pt = svg.createSVGPoint()
      pt.x = ev.clientX
      pt.y = ev.clientY
      const ctm = svg.getScreenCTM()
      if (!ctm) return
      const p = pt.matrixTransform(ctm.inverse())
      this.hoverPoint = { x: p.x, y: p.y }
    },
    clearSelection () {
      this.selectedNode = null
      this.hoverPoint = null
    },
    onNodeClick (n: any, ev: Event) {
      if (ev.isTrusted) this.$emit('nodeClick', n.node, ev)
      if (!n.interactive) {
        // say why on touch screens too, where there is no tooltip
        if (n.blockedReason) this.showToast(false, n.blockedReason)
        return
      }
      if (!this.selectedNode) {
        this.selectedNode = n.id
      } else if (this.selectedNode === n.id) {
        this.clearSelection()
      } else if (this.addAllowed(this.selectedNode, n.id)) {
        this.propose('add', this.selectedNode, n.id)
      }
    },
    onEdgeClick (e: any, ev: Event) {
      if (ev.isTrusted) this.$emit('edgeClick', e.edge, ev)
      const a = e.edge.source.id, b = e.edge.target.id
      if (this.removeAllowed(a, b)) {
        this.propose('remove', a, b)
      } else if (e.blocked) {
        this.showToast(false, e.blocked)
      }
    },
    propose (op: 'add' | 'remove', a: string, b: string) {
      if (this.isPending(op, a, b)) return
      this.opener = (typeof document !== 'undefined' ? document.activeElement : null) as any
      this.clearSelection()
      this.proposal = { op, a, b, turn: this.edits.turn }
      if (this.edits.confirm === false) {
        this.confirmProposal()
      } else {
        this.$nextTick(() => this.focusConfirm())
      }
    },
    // Move the keyboard focus to the confirmation and bring it into view (also on phones, where
    // the bar may be above the fold). Cancel gets the focus, so a repeated Enter can't confirm.
    focusConfirm () {
      const button = this.$refs.cancelButton as HTMLElement | undefined
      if (!this.proposal || !button) return
      if (typeof button.focus === 'function') button.focus()
      const bar = this.$refs.confirm as HTMLElement | undefined
      if (bar && typeof bar.scrollIntoView === 'function') bar.scrollIntoView({ block: 'nearest' })
    },
    focusInConfirm (): boolean {
      const box = this.$refs.confirm as HTMLElement | undefined
      return !!box && typeof document !== 'undefined' && box.contains(document.activeElement)
    },
    // Back to where the participant was. That element may be gone or disabled once the change is
    // made (the tie is cut, the listed change no longer offered); then the list's button, or the
    // bar where the server's answer appears.
    returnFocus () {
      const el = this.opener as any
      this.opener = null
      const root = this.$el as HTMLElement
      const moves = root && root.querySelector ? root.querySelector('.bb-network__moves') : null
      const fromList = !!el && !!moves && moves.contains(el)
      this.$nextTick(() => {
        const usable = (x: any) => !!x && x.isConnected && !x.disabled && typeof x.matches === 'function' && x.matches('button, [tabindex]')
        const choices: any[] = [el, fromList ? this.$refs.movesToggle : null, this.$refs.bar]
        const target = choices.find(usable)
        if (target) target.focus()
      })
    },
    onEdgeFocus (e: any, ev: FocusEvent) {
      // highlight for the keyboard; a mouse click already shows its own state
      let keyboard = true
      try { keyboard = (ev.target as Element).matches(':focus-visible') } catch (_) {}
      if (keyboard) this.hoverEdge = e.key
    },
    cancelProposal () {
      this.proposal = null
      this.returnFocus()
    },
    dropProposal () {
      if (!this.proposal && !this.selectedNode) return
      const hadFocus = this.focusInConfirm()
      this.proposal = null
      this.clearSelection()
      if (hadFocus) this.returnFocus()
    },
    confirmProposal () {
      const p = this.proposal
      if (!p) return
      this.proposal = null
      if (this.isPending(p.op, p.a, p.b)) return
      const key = `${p.op}:${pairKey(p.a, p.b)}`
      const stamp = Date.now()
      this.$set(this.pending, key, stamp)
      // in case no answer comes back; a newer send of the same change keeps its own entry
      setTimeout(() => { if (this.pending[key] === stamp) this.$delete(this.pending, key) }, PENDING_MS)
      this.$emit('edit', p)
      const bb = (window as any).Breadboard
      if (bb && bb.sendEdgeEdit) {
        if (p.turn == null) bb.sendEdgeEdit(p.op, p.a, p.b)
        else bb.sendEdgeEdit(p.op, p.a, p.b, p.turn)
      }
      this.returnFocus()
    },
    showToast (ok: boolean, text: string) {
      clearTimeout(this.toastTimer)
      this.barReserved = true
      this.toast = { ok, text }
      this.toastTimer = setTimeout(() => { this.toast = null }, ok ? SUCCESS_TOAST_MS : ERROR_TOAST_MS)
    },
    badgeWidth (text: string): number {
      return Math.max(40, String(text).length * 8.2 + 22)
    },
  },
})

function pairKey (a: string, b: string): string {
  return a < b ? `${a}|${b}` : `${b}|${a}`
}

// How far outside the circle a curved tie goes: neighbors next to each other are joined along the
// circle itself, ties that pass people by go around them (but not ever further out).
function bulge (r: number, steps: number): number {
  if (steps <= 1) return 0
  return r + 22 + 14 * Math.min(4, steps - 2)
}

function prefersReducedMotion (): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

// Shorten a name that wouldn't fit between its neighbors; the full name is in the tooltip.
function shorten (label: string, maxWidth: number, fontSize: number): string {
  if (!label) return ''
  const perChar = fontSize * 0.58
  const max = Math.max(3, Math.floor(maxWidth / perChar))
  return label.length <= max ? label : label.slice(0, max - 1).trimEnd() + '…'
}

// Turning or mirroring the circle doesn't change which neighbors sit next to each other, so of all
// those versions of `order` pick the one where people move least from where they were.
function keepPlaces (order: string[], previous: string[]): string[] {
  const n = order.length
  if (n < 2 || !previous.length) return order
  const was: Record<string, number> = {}
  previous.forEach((id, i) => { was[id] = i / previous.length })
  let best = order
  let bestCost = Infinity
  for (const dir of [1, -1]) {
    for (let shift = 0; shift < n; shift++) {
      const variant: string[] = []
      for (let i = 0; i < n; i++) variant.push(order[((dir * i + shift) % n + n) % n])
      let cost = 0
      variant.forEach((id, i) => {
        if (was[id] === undefined) return
        const d = Math.abs(i / n - was[id])
        cost += Math.min(d, 1 - d)
      })
      if (cost < bestCost - 1e-9) {
        bestCost = cost
        best = variant
      }
    }
  }
  return best
}

function sameTargets (a: Record<string, Point>, b: Record<string, Point>): boolean {
  const ka = Object.keys(a), kb = Object.keys(b)
  if (ka.length !== kb.length) return false
  return kb.every(k => a[k] && a[k].x === b[k].x && a[k].y === b[k].y)
}

function initialsOf (label: string): string {
  const words = String(label).trim().split(/\s+/).filter(Boolean)
  if (!words.length) return ''
  if (words.length === 1) return words[0].slice(0, 1).toUpperCase()
  // "Person A" -> "A"
  if (words[0] === 'Person' && words.length === 2) return words[1].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

// A lighter tint of a CSS colour for the gradient highlight (hex colours; others pass through).
// "Cut your tie with Ana" / "Cut the tie between Ana and Ben"; "Connect with Ana" / "Connect Ana and Ben".
function moveText (op: string, a: string, b: string | null, nameOf: (id: string) => string): string {
  if (op === 'remove') return b == null ? `Cut your tie with ${nameOf(a)}` : `Cut the tie between ${nameOf(a)} and ${nameOf(b)}`
  return b == null ? `Connect with ${nameOf(a)}` : `Connect ${nameOf(a)} and ${nameOf(b)}`
}

function luminance (hex: string): number | null {
  const m = /^#?([0-9a-f]{6})$/i.exec((hex || '').trim())
  if (!m) return null
  const v = parseInt(m[1], 16)
  const lin = (c: number) => { const x = c / 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4) }
  return 0.2126 * lin((v >> 16) & 255) + 0.7152 * lin((v >> 8) & 255) + 0.0722 * lin(v & 255)
}
const INK_LIGHT = '#ffffff'
const INK_DARK = '#334155'
// White or dark slate letters, whichever contrasts more with the disc's colour (its gradient only
// lightens the top edge).
function inkOn ([, base]: [string, string]): string {
  const b = luminance(base)
  if (b == null) return INK_DARK
  const onWhite = 1.05 / (b + 0.05)
  const onDark = (b + 0.05) / ((luminance(INK_DARK) as number) + 0.05)
  return onWhite >= onDark ? INK_LIGHT : INK_DARK
}

function lighten (color: string): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(color.trim())
  if (!m) return color
  const v = parseInt(m[1], 16)
  const mix = (c: number) => Math.round(c + (255 - c) * 0.45)
  const r = mix((v >> 16) & 255), g = mix((v >> 8) & 255), b = mix(v & 255)
  return '#' + [r, g, b].map(x => x.toString(16).padStart(2, '0')).join('')
}
</script>

<style>
.bb-network {
  position: relative;
  width: 100%;
  min-height: 320px;
  display: flex;
  flex-direction: column;
  font-family: var(--bb-font, 'Inter', system-ui, -apple-system, 'Segoe UI', sans-serif);
  user-select: none;
}
.bb-network__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.bb-network__stage {
  position: relative;
  flex: 1 1 auto;
  min-height: 280px;
}
.bb-network__svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}
.bb-network__orbit {
  fill: none;
  stroke: #e2e8f0;
  stroke-width: 2;
  stroke-dasharray: 2 9;
  stroke-linecap: round;
  pointer-events: none;
}
.bb-network__edge-line {
  fill: none;
  stroke: #d5dbe5;
  stroke-width: 3;
  stroke-linecap: round;
  transition: stroke 160ms ease, stroke-width 160ms ease, opacity 160ms ease;
}
.bb-network__edge.is-among .bb-network__edge-line {
  stroke: #64748b;
  stroke-width: 3.5;
}
/* Ties that can be cut stand out, and keep their own colour family: the participant's own in
   their (indigo) colour, ties between others darker slate. */
.bb-network__edge.is-own.is-removable .bb-network__edge-line {
  stroke: #818cf8;
}
.bb-network__edge.is-among.is-removable .bb-network__edge-line {
  stroke: #475569;
}
.bb-network__edge.is-blocked .bb-network__edge-line {
  stroke: #a3aec0;
  stroke-dasharray: 1 7;
}
.bb-network__edge.is-hover .bb-network__edge-line {
  stroke-width: 5;
}
.bb-network__edge.is-removable.is-hover .bb-network__edge-line {
  stroke: #e11d48;
  stroke-dasharray: 10 7;
}
.bb-network__edge.is-selected .bb-network__edge-line {
  stroke: #e11d48;
  stroke-width: 5.5;
  stroke-dasharray: none;
}
.bb-network__edge-hit {
  fill: none;
  stroke: transparent;
  stroke-width: 26;
  stroke-linecap: round;
  outline: none;
}
/* a finger needs a wider target than a mouse */
@media (pointer: coarse) {
  .bb-network__edge-hit { stroke-width: 44; }
}
.bb-network__edge.is-clickable .bb-network__edge-hit {
  cursor: pointer;
}
.bb-network__edge.is-blocked .bb-network__edge-hit {
  cursor: not-allowed;
}
.bb-network__edge-preview {
  stroke: #6366f1;
  stroke-width: 3.5;
  stroke-dasharray: 8 8;
  stroke-linecap: round;
  pointer-events: none;
}
.bb-network__scissors {
  pointer-events: none;
}
.bb-network__scissors circle {
  fill: #e11d48;
  stroke: #fff;
  stroke-width: 3;
}
.bb-network__scissors path {
  stroke: #fff;
  stroke-width: 2.5;
  stroke-linecap: round;
}
.bb-network__node {
  outline: none;
  animation: bb-network-fade-in 420ms ease both;
  transition: opacity 200ms ease;
}
.bb-network__node.is-selectable {
  cursor: pointer;
}
.bb-network__node.is-dimmed,
.bb-network__node.is-blocked {
  opacity: 0.4;
}
.bb-network__node.is-blocked {
  cursor: not-allowed;
}
.bb-network__disc {
  stroke: #ffffff;
  stroke-width: 3;
  transform-box: fill-box;
  transform-origin: center;
  transition: transform 180ms ease;
  animation: bb-network-pop-in 460ms cubic-bezier(0.2, 0.9, 0.3, 1.25) both;
}
.bb-network__node:not(.is-ego) .bb-network__disc {
  stroke: #cfd6e2;
  stroke-width: 1.5;
}
.bb-network__node.is-selectable:hover .bb-network__disc,
.bb-network__node.is-selectable:focus-visible .bb-network__disc {
  transform: scale(1.08);
  stroke: #6366f1;
  stroke-width: 2.5;
}
.bb-network__node:focus-visible .bb-network__disc {
  stroke: #4f46e5;
  stroke-width: 3.5;
}
.bb-network__node.is-ai .bb-network__disc {
  stroke-dasharray: 6 4;
}
.bb-network__halo {
  fill: rgba(99, 102, 241, 0.1);
  stroke: rgba(99, 102, 241, 0.3);
  stroke-width: 1.5;
  transform-box: fill-box;
  transform-origin: center;
  animation: bb-network-breathe 3.2s ease-in-out infinite;
  pointer-events: none;
}
.bb-network__selection {
  fill: none;
  stroke: #6366f1;
  stroke-width: 3.5;
  stroke-dasharray: 6 5;
  transform-box: fill-box;
  transform-origin: center;
  animation: bb-network-spin 6s linear infinite;
  pointer-events: none;
}
.bb-network__ego-label {
  fill: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.01em;
  pointer-events: none;
}
.bb-network__initials {
  fill: #475569;
  font-weight: 650;
  pointer-events: none;
}
.bb-network__label {
  pointer-events: none;
  fill: #1e293b;
  font-weight: 600;
  paint-order: stroke;
  stroke: rgba(255, 255, 255, 0.9);
  stroke-width: 5px;
  stroke-linejoin: round;
}
.bb-network__badge {
  pointer-events: none;
}
.bb-network__badge rect {
  fill: #f1f5f9;
  stroke: #e2e8f0;
}
.bb-network__badge text {
  fill: #475569;
  font-size: 12.5px;
  font-weight: 600;
}
/* A fixed height, so what the bar shows never resizes the picture; longer content (a confirmation
   that wraps on a phone) hangs over the top of the picture instead. */
.bb-network__bar {
  position: relative;
  height: 60px;
  flex: none;
  outline: none;
}
/* On a phone the question can take two lines: the bar grows instead of covering the people. */
@media (max-width: 600px) {
  .bb-network__bar { height: auto; min-height: 60px; padding: 10px 0; box-sizing: border-box; }
  .bb-network__bar > * { position: relative; top: 0; }
}
/* fingers need bigger buttons */
@media (pointer: coarse) {
  .bb-network__btn, .bb-network__moves-toggle, .bb-network__move { min-height: 44px; }
}
.bb-network__bar > * {
  position: absolute;
  top: 10px;
  left: 0;
  right: 0;
  margin: 0 auto;
  width: fit-content;
  max-width: calc(100% - 24px);
  box-sizing: border-box;
  z-index: 2;
}
.bb-network__hint {
  max-width: 100%;
  padding: 8px 16px;
  border-radius: 999px;
  background: #eef2ff;
  color: #3730a3;
  font-size: 14px;
  font-weight: 500;
  text-align: center;
}
.bb-network__confirm {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 6px 8px 6px 18px;
  border-radius: 14px;
  background: #0f172a;
  color: #f8fafc;
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.24);
  max-width: 100%;
  flex-wrap: wrap;
}
.bb-network__confirm-text {
  font-size: 15px;
  font-weight: 500;
  margin-right: 4px;
}
.bb-network__toast {
  padding: 8px 16px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #065f46;
  font-size: 14px;
  font-weight: 600;
}
.bb-network__toast--error {
  background: #fff1f2;
  color: #9f1239;
}
.bb-network__moves.is-off {
  visibility: hidden;
}
.bb-network__moves {
  flex: none;
  padding: 4px 16px 16px;
  text-align: center;
}
.bb-network__moves-toggle {
  border: none;
  background: transparent;
  color: #4338ca;
  font: inherit;
  font-size: 13.5px;
  font-weight: 600;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
}
.bb-network__moves-toggle:hover:not(:disabled) {
  background: #eef2ff;
}
.bb-network__moves-toggle:disabled {
  color: #64748b;
  cursor: default;
}
.bb-network__move-list {
  list-style: none;
  margin: 6px 0 0;
  padding: 2px;
  max-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}
.bb-network__move-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.bb-network__move {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #334155;
  border-radius: 999px;
  padding: 6px 12px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 150ms ease, border-color 150ms ease;
}
.bb-network__move.is-remove:hover:not(:disabled) {
  background: #fff1f2;
  border-color: #fecdd3;
  color: #9f1239;
}
.bb-network__move.is-add:hover:not(:disabled) {
  background: #eef2ff;
  border-color: #c7d2fe;
  color: #3730a3;
}
.bb-network__move:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.bb-network__move-reason {
  font-size: 12px;
  color: #64748b;
  max-width: 220px;
}
.bb-network__btn {
  border: none;
  border-radius: 10px;
  padding: 8px 14px;
  font: inherit;
  font-size: 14px;
  font-weight: 650;
  cursor: pointer;
}
.bb-network__btn--ghost {
  background: transparent;
  color: #cbd5e1;
}
.bb-network__btn--ghost:hover {
  color: #fff;
}
.bb-network__btn--primary {
  background: #6366f1;
  color: #fff;
}
.bb-network__btn--danger {
  background: #e11d48;
  color: #fff;
}
.bb-network__btn:focus-visible,
.bb-network__move:focus-visible,
.bb-network__moves-toggle:focus-visible {
  outline: 3px solid #a5b4fc;
  outline-offset: 2px;
}
.bb-network-fade-enter-active {
  transition: opacity 200ms ease;
}
.bb-network-fade-enter {
  opacity: 0;
}
.bb-network-fade-leave-active {
  display: none;
}
@keyframes bb-network-fade-in {
  from { opacity: 0; }
}
@keyframes bb-network-pop-in {
  from { transform: scale(0.5); }
}
@keyframes bb-network-breathe {
  0%, 100% { transform: scale(1); opacity: 0.9; }
  50% { transform: scale(1.07); opacity: 0.55; }
}
@keyframes bb-network-spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .bb-network__node, .bb-network__disc, .bb-network__halo, .bb-network__selection { animation: none; }
  .bb-network-fade-enter-active { transition: none; }
}
</style>
