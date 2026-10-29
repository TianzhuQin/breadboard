<template>
  <section class="pd" :aria-labelledby="headingId">
    <header class="pd__head">
      <span class="pd__avatar" :class="{ 'is-ai': ai, 'is-away': connection === 'disconnected' }" :style="{ '--dot': color || 'var(--accent)' }" aria-hidden="true" />
      <div class="pd__titles">
        <div class="pd__name-row">
          <h2 :id="headingId" ref="heading" class="pd__name" tabindex="-1">{{ name }}</h2>
          <button v-if="name === id" type="button" class="pd__copy" :aria-label="`Copy the id ${id}`" title="Copy the id" @click="copyText(id, `Copied ${id}`)">
            <Copy :size="13" aria-hidden="true" />
          </button>
        </div>
        <button v-if="name !== id" type="button" class="pd__id" :title="`Copy the id ${id}`" @click="copyText(id, `Copied ${id}`)">
          <span>{{ id }}</span><Copy :size="12" aria-hidden="true" />
        </button>
      </div>
      <BButton variant="ghost" size="sm" :icon="X" :aria-label="`Close ${name}'s details`" title="Close (Esc)" @click="emit('close')" />
    </header>

    <BEmpty
      v-if="!node"
      compact
      :icon="UserX"
      title="Not in this session"
      text="This player has left, or the session changed. Pick someone else on the network or in the list."
    >
      <template #actions>
        <BButton size="sm" @click="emit('close')">Close</BButton>
      </template>
    </BEmpty>

    <template v-else>
      <div class="pd__badges">
        <BBadge v-if="ai" tone="accent"><Bot :size="13" aria-hidden="true" />AI player</BBadge>
        <BBadge v-else-if="connection === 'connected'" tone="success" dot>Connected</BBadge>
        <BBadge v-else-if="connection === 'disconnected'" tone="warning" dot>Disconnected</BBadge>
        <BBadge v-if="choices.length" tone="info">Has a choice to make</BBadge>
        <BBadge v-if="autopilot" tone="info">{{ autopilotLabel }}</BBadge>
        <BBadge v-if="group !== undefined" title="The engine's group (set by group steps)">Group {{ group }}</BBadge>
        <button
          v-if="recruitment"
          type="button"
          class="pd__chip"
          :title="recruitment.prolificPid ? `Copy the Prolific ID ${recruitment.prolificPid}` : 'Recruited through Prolific'"
          @click="recruitment.prolificPid && copyText(recruitment.prolificPid, 'Copied the Prolific ID')"
        >
          <BBadge tone="neutral">{{ recruitmentLabel }}</BBadge>
        </button>
      </div>

      <div class="pd__actions">
        <BButton size="sm" :icon="MonitorSmartphone" :href="`#/preview?player=${encodeURIComponent(id)}`" :title="previewTitle">Preview their screen</BButton>
        <BButton v-if="context === 'players'" size="sm" :icon="Share2" @click="emit('locate', id)">Show on network</BButton>
        <BButton v-else size="sm" :icon="Crosshair" @click="emit('locate', id)">Centre on them</BButton>
      </div>

      <!-- what they see -->
      <section class="pd__section" :aria-labelledby="`${headingId}-screen`">
        <div class="pd__section-head">
          <h3 :id="`${headingId}-screen`" class="pd__h">On their screen</h3>
          <button v-if="text" type="button" class="pd__link" :aria-pressed="showSource ? 'true' : 'false'" @click="showSource = !showSource">
            {{ showSource ? 'Show page' : 'Show HTML' }}
          </button>
        </div>
        <pre v-if="showSource && text" class="pd__source">{{ text }}</pre>
        <ParticipantScreen v-else :html="text" :css="admin.experiment?.style" :title="`What ${name} sees`" :height="210" />

        <div v-if="choices.length" class="pd__choices">
          <p class="pd__sub">Their choices. Choosing submits it for them, as if they clicked it.</p>
          <div class="pd__choice-list">
            <BButton
              v-for="c in choices"
              :key="c.uid"
              size="sm"
              :variant="choosing === c.uid ? 'primary' : 'secondary'"
              :loading="choosing === c.uid"
              :disabled="!!choosing || c._route === 'group' || !!choiceBlocked"
              :title="c._route === 'group' ? 'Group choices can only be made on the participant page' : choiceBlocked || `Choose “${c.name}” for ${name}`"
              @click="choose(c)"
            >{{ c.name }}</BButton>
          </div>
          <p v-if="choiceBlocked" class="pd__note">{{ choiceBlocked }}.</p>
          <p v-else-if="choices.some(c => c._route === 'group')" class="pd__note">Group choices can only be made on the participant page.</p>
        </div>
      </section>

      <!-- ties -->
      <section class="pd__section" :aria-labelledby="`${headingId}-ties`">
        <div class="pd__section-head">
          <h3 :id="`${headingId}-ties`" class="pd__h">Ties <span class="pd__count">{{ neighbors.length }}</span></h3>
        </div>
        <ul v-if="neighbors.length" class="pd__ties" role="list">
          <li v-for="nb in neighbors" :key="nb.id" class="pd__tie-item">
            <div class="pd__tie">
              <span class="pd__tie-dot" :class="`is-${nb.tone}`" aria-hidden="true" />
              <button type="button" class="pd__tie-name" :title="`Show ${nb.label}`" @click="emit('select', nb.id)">
                {{ nb.label }}<span v-if="nb.label !== nb.id" class="pd__tie-id">{{ nb.id }}</span>
              </button>
              <span v-for="k in nb.kinds" :key="k" class="pd__tie-kind" :title="`Joined by a “${k}” link, which can't be cut here`">{{ k }}</span>
              <button
                v-if="nb.props"
                type="button"
                class="pd__tie-more"
                :aria-expanded="openTies.has(nb.id) ? 'true' : 'false'"
                :aria-label="`${openTies.has(nb.id) ? 'Hide' : 'Show'} the properties of the tie with ${nb.label}`"
                :title="openTies.has(nb.id) ? 'Hide the tie\'s properties' : 'Show the tie\'s properties'"
                @click="toggleTie(nb.id)"
              >
                <ChevronRight :size="13" class="pd__tie-chev" :class="{ 'is-open': openTies.has(nb.id) }" aria-hidden="true" />{{ nb.props }}
              </button>
              <BButton
                v-if="nb.tie"
                variant="danger-ghost"
                size="sm"
                :icon="Scissors"
                :loading="isPending(id, nb.id)"
                :disabled="!canEdit"
                :aria-label="`Cut the tie between ${name} and ${nb.label}`"
                :title="canEdit ? `Cut the tie between ${name} and ${nb.label}` : editBlockedReason"
                @click="cut(nb.id)"
              >Cut</BButton>
            </div>
            <div v-if="nb.props && openTies.has(nb.id)" class="pd__tie-props">
              <template v-for="l in nb.links" :key="l.id">
                <h4 v-if="nb.links.length > 1 && l.data.length" class="pd__h pd__h--minor">{{ l.kind }}</h4>
                <ul v-if="l.data.length" class="pd__props" role="list">
                  <PropertyRow v-for="([k, v]) in l.data" :key="k" :name="k" :value="v" :path="k" />
                </ul>
              </template>
            </div>
          </li>
        </ul>
        <p v-else class="pd__sub">{{ name }} isn't tied to anyone yet.</p>
        <TiePicker
          class="pd__picker"
          :candidates="candidates"
          :disabled="!canEdit || !candidates.length"
          :label="`Tie ${name} to someone`"
          :placeholder="canEdit ? (candidates.length ? `Tie ${name} to…` : 'Tied to everyone already') : editBlockedReason"
          @pick="tieTo"
        />
      </section>

      <!-- everything else -->
      <section class="pd__section" :aria-labelledby="`${headingId}-props`">
        <div class="pd__section-head">
          <h3 :id="`${headingId}-props`" class="pd__h">Properties <span class="pd__count">{{ ownProps.length + engineProps.length }}</span></h3>
        </div>
        <BInput
          v-if="ownProps.length + engineProps.length > 8"
          v-model="propQuery"
          size="sm"
          :icon="Search"
          placeholder="Find a property"
          aria-label="Find a property"
          class="pd__prop-search"
        />
        <ul v-if="shownOwn.length" class="pd__props" role="list">
          <PropertyRow v-for="([k, v]) in shownOwn" :key="k" :name="k" :value="v" :path="k" />
        </ul>
        <p v-else-if="!ownProps.length" class="pd__sub">The experiment hasn't set any properties on them yet.</p>
        <p v-else class="pd__sub">No property matches “{{ propQuery }}”.</p>
        <template v-if="shownEngine.length">
          <h4 class="pd__h pd__h--minor">Engine data</h4>
          <ul class="pd__props" role="list">
            <PropertyRow v-for="([k, v]) in shownEngine" :key="k" :name="k" :value="v" :path="k" />
          </ul>
        </template>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { X, Copy, Bot, UserX, MonitorSmartphone, Share2, Crosshair, Scissors, Search, ChevronRight } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BInput, confirm, notify, toast } from '@/ui'
import { uid } from '@/ui/uid'
import { admin, engineElsewhere, ensureConnected, graph, NOT_CONNECTED } from '@/store/admin'
import { openOutput } from '@/shell/output'
import {
  NOISY_LINK_KEYS, autopilotOf, choicesOf, compareIds, connectionOf, isAi, isTie, labelOf, recruitmentOf, type Choice,
} from '@/network/players'
import { editTie, isPending, tieEditsBlocked } from '@/network/tieEdits'
import ParticipantScreen from './ParticipantScreen.vue'
import PropertyRow from './PropertyRow.vue'
import TiePicker, { type TieCandidate } from './TiePicker.vue'
import { copyText } from './clipboard'
import { shortError, submitChoice } from './choices'

/**
 * Everything about one player, live: who they are, what they see, their choices (and choosing for
 * them), their ties (cut, or tie to someone), and every property. Used on the Network and Players
 * pages.
 */
const props = withDefaults(defineProps<{
  id: string
  /** Property to name people by (as on the network). */
  labelBy?: string | null
  /** Their colour on the network. */
  color?: string | null
  context?: 'network' | 'players'
}>(), { labelBy: null, color: null, context: 'network' })

const emit = defineEmits<{
  close: []
  /** Show someone else (e.g. a tie). */
  select: [id: string]
  /** Show them on the network. */
  locate: [id: string]
}>()

const headingId = uid('pd')
const heading = ref<HTMLElement>()

// a fresh object per change: the store keeps the same node and swaps its data, so returning the
// node itself would leave everything below stale (the old panel's "not live" bug)
const node = computed(() => {
  void graph.version.value
  const n = graph.nodes.get(props.id)
  return n ? { id: n.id, data: n.data } : null
})
const nameFor = (id: string) => labelOf(graph.nodes.get(id) || { id, data: {} }, props.labelBy)
const name = computed(() => { void graph.version.value; return nameFor(props.id) })
const ai = computed(() => isAi(node.value))
const connection = computed(() => connectionOf(node.value))
// A preview of someone whose own page is connected only watches it; anyone else's acts for them.
const previewTitle = computed(() => connection.value === 'connected'
  ? 'Opens their page on the Preview page, read-only: their own page is connected, and is left alone.'
  : 'Opens their page on the Preview page. Choices made in the preview count as theirs.')
const recruitment = computed(() => recruitmentOf(node.value))
const autopilot = computed(() => autopilotOf(node.value))
const group = computed(() => {
  const g = node.value?.data?._system?.groupId
  return g === undefined || g === null || g === '' ? undefined : g
})
const choices = computed(() => choicesOf(node.value))
const text = computed(() => {
  const t = node.value?.data?.text
  return typeof t === 'string' ? t : (t == null ? '' : String(t))
})

const recruitmentLabel = computed(() => {
  const r = recruitment.value
  if (!r) return ''
  const src = r.source === 'prolific' ? 'Prolific' : r.source
  return r.prolificPid ? `${src} · ${r.prolificPid.length > 10 ? r.prolificPid.slice(0, 8) + '…' : r.prolificPid}` : src
})
const autopilotLabel = computed(() => {
  const a = autopilot.value
  if (!a) return ''
  if (a.state === 'waiting') return a.here != null && a.min != null ? `Waiting room · ${a.here} of ${a.min}` : 'In the waiting room'
  if (a.state === 'starting') return a.seconds != null ? `Starting in ${a.seconds}s` : 'Game starting'
  if (a.state === 'cancelled') return 'Waiting room closed'
  return a.state
})

function toneOf (id: string): TieCandidate['tone'] {
  const n = graph.nodes.get(id)
  if (isAi(n)) return 'ai'
  const c = connectionOf(n)
  return c === 'connected' ? 'on' : c === 'disconnected' ? 'off' : 'none'
}

interface Neighbor {
  id: string
  label: string
  tone: TieCandidate['tone']
  /** Joined by a tie (a "connected" edge), which can be cut here. */
  tie: boolean
  /** The kinds of their other links (e.g. "partner"), which can't be edited here. */
  kinds: string[]
  /** Each link between them with its properties (ties first). */
  links: { id: string, kind: string, data: [string, any][] }[]
  /** How many properties those links have. */
  props: number
}
const linkProps = (data: Record<string, any>): [string, any][] => Object.keys(data)
  .filter(k => !NOISY_LINK_KEYS.has(k) && data[k] !== null && data[k] !== undefined && !(typeof data[k] === 'object' && !Object.keys(data[k]).length))
  .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }))
  .map(k => [k, data[k]])

/** Everyone joined to them, by a tie or by a link of another kind the experiment added. */
const neighbors = computed<Neighbor[]>(() => {
  void graph.version.value
  const byId = new Map<string, Neighbor>()
  for (const l of graph.links.values()) {
    const other = l.source === props.id ? l.target : l.target === props.id ? l.source : null
    if (other === null || other === props.id) continue
    let nb = byId.get(other)
    if (!nb) {
      nb = { id: other, label: nameFor(other), tone: toneOf(other), tie: false, kinds: [], links: [], props: 0 }
      byId.set(other, nb)
    }
    const tie = isTie(l)
    const kind = l.label || 'connected'
    if (tie) nb.tie = true
    else if (!nb.kinds.includes(kind)) nb.kinds.push(kind)
    const data = linkProps(l.data)
    nb.props += data.length
    nb.links[tie ? 'unshift' : 'push']({ id: l.id, kind, data })
  }
  return Array.from(byId.values()).sort((a, b) => compareIds(a.label, b.label))
})
const openTies = ref(new Set<string>())
function toggleTie (other: string) {
  const next = new Set(openTies.value)
  if (!next.delete(other)) next.add(other)
  openTies.value = next
}
const candidates = computed<TieCandidate[]>(() => {
  void graph.version.value
  // someone joined only by another kind of link can still be tied to
  const tied = new Set(neighbors.value.filter(n => n.tie).map(n => n.id))
  const out: TieCandidate[] = []
  for (const other of graph.nodes.keys()) {
    if (other === props.id || tied.has(other)) continue
    out.push({ id: other, label: nameFor(other), tone: toneOf(other) })
  }
  return out.sort((a, b) => compareIds(a.label, b.label))
})

// ---- properties ----------------------------------------------------------------------------------

const ENGINE_KEYS = new Set(['_system', 'timers', 'edgeEdits', 'private', 'ai', 'lastupdated', 'timerUpdatedAt'])
const propQuery = ref('')
const ownProps = computed<[string, any][]>(() => {
  const d = node.value?.data || {}
  return Object.keys(d).filter(k => k !== 'text' && k !== 'choices' && !ENGINE_KEYS.has(k))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }))
    .map(k => [k, d[k]])
})
const engineProps = computed<[string, any][]>(() => {
  const d = node.value?.data || {}
  return Object.keys(d).filter(k => ENGINE_KEYS.has(k)).sort().map(k => [k, d[k]])
})
function matchesQuery ([k, v]: [string, any]) {
  const q = propQuery.value.trim().toLowerCase()
  if (!q) return true
  if (k.toLowerCase().includes(q)) return true
  return v !== null && typeof v !== 'object' && String(v).toLowerCase().includes(q)
}
const shownOwn = computed(() => ownProps.value.filter(matchesQuery))
const shownEngine = computed(() => engineProps.value.filter(matchesQuery))

// ---- what they see -------------------------------------------------------------------------------

const showSource = ref(false)
const choosing = ref<string | null>(null)
/** The server refuses choices while the shared engine runs another experiment (these players are in its session). */
const choiceBlocked = computed(() => {
  const e = engineElsewhere.value
  return e ? `The engine is running ${e.experimentName ? `“${e.experimentName}”` : 'another experiment'} right now, so choices can't be made from here` : ''
})

async function choose (c: Choice) {
  if (c._route === 'group' || choosing.value || choiceBlocked.value || !ensureConnected()) return
  const who = name.value
  const ok = await confirm({
    title: `Choose “${c.name}” for ${who}?`,
    message: `This submits the choice exactly as if ${who} had clicked it, and the game carries on from there. It can't be undone.`,
    confirmLabel: 'Choose for them',
  })
  if (!ok) return
  choosing.value = c.uid
  const outcome = await submitChoice(props.id, c.uid)
  choosing.value = null
  if (outcome.ok) {
    notify.success(`Chose “${c.name}” for ${who}`)
  } else if ('gone' in outcome) {
    // they answered, or it expired, while the confirmation was open
    notify.warning(
      `Nothing chosen for ${who}`,
      outcome.sent
        ? `“${c.name}” had already left their screen when it reached the game, so nothing changed.`
        : `“${c.name}” is no longer on their screen. They may have answered, or it expired. Nothing was sent.`,
    )
  } else if ('offline' in outcome) {
    // the connection dropped while the confirmation was open
    notify.error(NOT_CONNECTED, `Nothing was sent, so “${c.name}” is still waiting on ${who}'s screen. Try again once the connection is back.`)
  } else if ('error' in outcome) {
    toast('error', `The choice for ${who} failed`, shortError(outcome.error), {
      action: { label: 'Show output', run: () => { openOutput.value = true } },
    })
  } else if (outcome.onScreen) {
    notify.warning('No answer from the server yet', `“${c.name}” is still on ${who}'s screen. The game may be busy, or the choice may need answers only the participant page can collect. Check again in a moment.`)
  } else {
    // it left their screen, but that alone doesn't say whose answer it was
    notify.warning('No answer from the server yet', `The choice is no longer on ${who}'s screen, but the server hasn't said whether yours was made or ${who} answered first. Their properties and the session's events show what was chosen.`)
  }
}

// ---- ties ----------------------------------------------------------------------------------------

// off while the session isn't running, or while the engine runs another experiment
const canEdit = computed(() => !tieEditsBlocked.value)
const editBlockedReason = tieEditsBlocked

function cut (other: string) {
  void editTie('remove', props.id, other, { name: nameFor })
}
function tieTo (other: string) {
  void editTie('add', props.id, other, { name: nameFor })
}

// a different player: start from the top, without the old search
watch(() => props.id, () => {
  propQuery.value = ''
  showSource.value = false
  openTies.value = new Set()
})

defineExpose({ focusHeading: () => heading.value?.focus() })
</script>

<style>
.pd { display: flex; flex-direction: column; gap: 16px; padding: 0 18px 24px; }
.pd__head {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin: 0 -18px;
  padding: 16px 14px 12px 18px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}
.pd__avatar {
  flex: none;
  width: 28px;
  height: 28px;
  margin-top: 1px;
  border-radius: 50%;
  background: var(--dot);
  box-shadow: 0 0 0 3px var(--surface), 0 0 0 4px var(--border);
}
.pd__avatar.is-ai { background: var(--surface); box-shadow: inset 0 0 0 7px var(--dot), 0 0 0 3px var(--surface), 0 0 0 4px var(--border); }
.pd__avatar.is-away { opacity: 0.55; }
.pd__titles { flex: 1; min-width: 0; }
.pd__name-row { display: flex; align-items: center; gap: 4px; min-width: 0; }
.pd__copy {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--faint);
  cursor: pointer;
}
.pd__copy:hover { background: var(--surface-hover); color: var(--text); }
.pd__name { min-width: 0; font-size: 17px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; outline: none; }
.pd__id {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 100%;
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--faint);
  font-family: var(--font-mono);
  font-size: 12px;
  cursor: pointer;
}
.pd__id span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pd__id:hover { color: var(--text-soft); }
.pd__badges { display: flex; flex-wrap: wrap; gap: 6px; }
.pd__badges:empty { display: none; }
.pd__badges .b-badge svg { margin-left: -2px; }
.pd__chip { padding: 0; border: 0; background: transparent; cursor: pointer; border-radius: var(--radius-pill); }
.pd__actions { display: flex; flex-wrap: wrap; gap: 8px; }
.pd__section { display: flex; flex-direction: column; gap: 10px; padding-top: 16px; border-top: 1px solid var(--border); }
.pd__section-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.pd__h { font-size: var(--text-xs); font-weight: 650; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted); display: flex; align-items: center; gap: 6px; }
.pd__h--minor { margin-top: 6px; }
.pd__count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--radius-pill);
  background: var(--bg-subtle);
  color: var(--text-soft);
  font-size: 11px;
  letter-spacing: 0;
  font-variant-numeric: tabular-nums;
}
.pd__link { padding: 0; border: 0; background: transparent; color: var(--accent); font-size: var(--text-xs); font-weight: 600; cursor: pointer; }
.pd__link:hover { text-decoration: underline; }
.pd__source {
  margin: 0;
  max-height: 320px;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--code-bg);
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  user-select: text;
}
.pd__sub { color: var(--muted); font-size: var(--text-sm); }
.pd__note { color: var(--faint); font-size: var(--text-xs); }
.pd__choices { display: flex; flex-direction: column; gap: 8px; }
.pd__choice-list { display: flex; flex-wrap: wrap; gap: 6px; }
.pd__ties { margin: 0; padding: 0; display: flex; flex-direction: column; }
.pd__tie-item { list-style: none; }
.pd__tie { display: flex; align-items: center; gap: 9px; padding: 3px 0 3px 2px; border-radius: var(--radius-xs); }
.pd__tie:hover { background: var(--surface-hover); }
.pd__tie-kind {
  flex: none;
  padding: 1px 7px;
  border-radius: var(--radius-pill);
  background: var(--bg-subtle);
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.pd__tie-more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: none;
  padding: 2px 6px 2px 2px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--faint);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.pd__tie-more:hover { background: var(--surface-hover); color: var(--text); }
.pd__tie-chev { transition: transform var(--duration) var(--ease); }
.pd__tie-chev.is-open { transform: rotate(90deg); }
.pd__tie-props { padding: 2px 0 8px 17px; }
.pd__tie-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--border-strong); }
.pd__tie-dot.is-on { background: var(--success); }
.pd__tie-dot.is-off { background: transparent; box-shadow: inset 0 0 0 1.5px var(--muted); }
.pd__tie-dot.is-ai { background: transparent; box-shadow: inset 0 0 0 2.5px var(--accent); }
.pd__tie-name {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font-size: var(--text-sm);
  font-weight: 520;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pd__tie-name:hover { color: var(--accent); }
.pd__tie-id { color: var(--faint); font-family: var(--font-mono); font-size: 11px; font-weight: 400; }
.pd__picker { margin-top: 2px; }
.pd__prop-search { margin-bottom: 2px; }
.pd__props { margin: 0 -6px; padding: 0; }
</style>
