<template>
  <BPage
    v-if="exp"
    intro="See the participant page exactly as each player sees it, live. Choose whose pages to show, then click one to open it full size."
  >
    <template v-if="isRunning" #actions>
      <BButton :icon="UserPlus" :href="joinLink" target="_blank" rel="noopener" title="Opens the sign-in page in a new tab, as a newcomer sees it: type an ID there (any name or number; one seat per ID)">
        Join as a new participant
      </BButton>
    </template>

    <!-- No session, or one that isn't running: participant pages only open while it runs -->
    <BPanel v-if="!inst">
      <BEmpty
        :icon="MonitorSmartphone"
        title="No session is running"
        text="Previews show the participant page of a running session, live, exactly as each player sees it. Start a session, then come back to watch it."
      >
        <template #actions>
          <BButton variant="primary" :icon="Plus" @click="router.push('/session?new=1')">New session</BButton>
          <BButton :icon="Database" @click="router.push('/sessions')">Resume an earlier one</BButton>
        </template>
      </BEmpty>
    </BPanel>
    <BPanel v-else-if="!isRunning">
      <BEmpty
        :icon="MonitorOff"
        :title="`“${inst.name}” is ${statusWord}`"
        :text="canResume(inst)
          ? 'Participant pages open only while a session is running. Resume this session, or start a new one, to preview it.'
          : 'Participant pages open only while a session is running. Start a new session to preview the experiment.'"
      >
        <template #actions>
          <BButton
            v-if="canResume(inst)"
            variant="primary"
            :icon="Play"
            :loading="sessionActions.isBusy('resume', inst.id)"
            @click="sessionActions.resume(inst)"
          >
            Resume “{{ inst.name }}”
          </BButton>
          <BButton :variant="canResume(inst) ? 'secondary' : 'primary'" :icon="Database" @click="router.push('/sessions')">Sessions &amp; data</BButton>
          <BButton :icon="Plus" @click="router.push('/session?new=1')">New session</BButton>
        </template>
      </BEmpty>
    </BPanel>

    <template v-else>
      <div v-if="!noticeSeen" class="pv__notice" role="note">
        <Info :size="18" class="pv__notice-icon" aria-hidden="true" />
        <div class="pv__notice-text">
          <strong>Previews are live.</strong>
          What you click is a real choice for that player, recorded with the session’s data.
          A player whose own page is connected, and a computer player, are shown read-only: you see their screen, and their page (or the computer) carries on.
          A test player, whose own page hasn’t been in the session, is played here: the preview counts as them being connected until their own page comes back.
          Anyone else (someone who left, or whose seat the computer took) can be answered for here, but counts as away, as anyone who leaves does: the computer takes their seat as usual, or goes on playing it.
        </div>
        <BButton size="sm" variant="ghost" @click="dismissNotice">Got it</BButton>
      </div>

      <div v-if="admin.engine === 'loading'" class="pv__engine" role="status">
        <BSpinner :size="14" /> The engine is restarting. Previews reconnect by themselves when it is back.
      </div>

      <div class="pv">
        <aside class="pv__side" :class="{ 'is-collapsed': !pickerOpen }">
          <button type="button" class="pv__side-toggle" :aria-expanded="pickerOpen ? 'true' : 'false'" aria-controls="pv-picker" @click="pickerOpen = !pickerOpen">
            <Users :size="16" aria-hidden="true" />
            <span>Players · {{ memory.ids.length }} of {{ players.length }} shown</span>
            <ChevronDown :size="16" class="pv__side-chev" aria-hidden="true" />
          </button>
          <div id="pv-picker" class="pv__picker">
            <PlayerPicker
              :players="players"
              :selected="selected"
              :max="MAX_PREVIEWS"
              :list-key="listKey"
              @toggle="toggle"
              @pick="pick"
              @clear="clearAll"
            />
          </div>
        </aside>

        <section class="pv__main" aria-label="Previews">
          <div class="pv__toolbar">
            <BTabs v-model="memory.device" variant="pill" :tabs="deviceTabs" aria-label="Screen" />
            <BTabs v-model="memory.size" variant="pill" :tabs="sizeTabs" aria-label="Preview size" />
            <BSwitch v-model="memory.interactive" title="When on, you can click and type inside the small previews; when off, clicking one opens it full size">
              Click inside previews
            </BSwitch>
            <BButton class="pv__reload" size="sm" variant="ghost" :icon="RotateCw" :disabled="!tiles.length" @click="reloadAll">Reload all</BButton>
          </div>

          <div ref="gridBox" class="pv__gridbox">
            <BEmpty
              v-if="!players.length"
              :icon="UsersRound"
              title="Nobody is in the network yet"
              text="A preview needs a player. Join as a new participant to see what newcomers see, or wait for people to arrive: they appear in the list as they join."
            >
              <template #actions>
                <BButton variant="primary" :icon="UserPlus" :href="joinLink" target="_blank" rel="noopener">Join as a new participant</BButton>
              </template>
            </BEmpty>

            <BEmpty
              v-else-if="!tiles.length"
              :icon="MonitorSmartphone"
              title="Choose whose page to see"
              text="Tick players in the list, or pick a group. Test players are the ones your steps marked with testmode."
            >
              <template #actions>
                <BButton v-if="testCount" :icon="FlaskConical" @click="pick('test')">Test players ({{ testCount }})</BButton>
                <BButton v-if="aiCount" :icon="Bot" @click="pick('ai')">AI players ({{ aiCount }})</BButton>
                <BButton :icon="ListOrdered" @click="pick('first', 4)">First {{ Math.min(4, players.length) }}</BButton>
              </template>
            </BEmpty>

            <ul v-else class="pv__grid">
              <PreviewTile
                v-for="t in tiles"
                :key="t.id"
                :player-id="t.id"
                :target="targetOf(t.id)"
                :device="memory.device"
                :width="tileW"
                :interactive="memory.interactive"
                :test="isTestPlayer(t)"
                :ai="isAIPlayer(t)"
                :paused="focusId === t.id ? 'focus' : tabbed.has(t.id) ? 'tab' : null"
                :frame-key="`${globalNonce}-${nonces[t.id] || 0}`"
                :note="t.missing && admin.engine !== 'loading' ? 'Not in the game right now: a preview adds nobody. Their page shows here once they join.' : ''"
                @focus="focusId = t.id"
                @reload="reload(t.id)"
                @open-tab="openTab(t.id)"
                @remove="remove(t.id)"
                @resume="tabbed.delete(t.id); reload(t.id)"
              />
            </ul>
          </div>
        </section>
      </div>

      <PreviewFocus
        :player-id="focusId"
        :target="focusId ? targetOf(focusId) : null"
        :index="focusIndex"
        :count="tiles.length"
        :initial-device="memory.device"
        :reload-key="focusId ? nonces[focusId] || 0 : 0"
        @close="closeFocus"
        @step="step"
        @open-tab="focusId && openTab(focusId)"
      />
    </template>
  </BPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bot, ChevronDown, Database, FlaskConical, Info, ListOrdered, Monitor, MonitorOff, MonitorSmartphone, Play, Plus,
  RotateCw, Smartphone, UserPlus, Users, UsersRound,
} from 'lucide-vue-next'
import { BButton, BEmpty, BPage, BPanel, BSpinner, BSwitch, BTabs, notify, type TabItem } from '@/ui'
import { admin, graph } from '@/store/admin'
import { playerName } from '@/network/players'
import { describeError } from '../experiments/experiments'
import { canResume } from '../sessions/sessionData'
import { useSessionActions } from '../sessions/useSessionActions'
import PlayerPicker from './PlayerPicker.vue'
import PreviewTile from './PreviewTile.vue'
import PreviewFocus from './PreviewFocus.vue'
import {
  MAX_PREVIEWS, autoPicked, isAIPlayer, isComputerPlayer, isTestPlayer, joinUrl, previewMemory as memory, previewPageUrl,
  quickPick, shownReadOnly, sortPlayers, tileWidth, type PlayerLike, type PreviewTarget, type QuickPick,
} from './preview'
import { fetchPreviewToken } from './previewLink'

const router = useRouter()
const route = useRoute()
const sessionActions = useSessionActions()
const exp = computed(() => admin.experiment)
const inst = computed(() => admin.instance)
const isRunning = computed(() => inst.value?.status === 'RUNNING')
const statusWord = computed(() => (inst.value?.status || '').toLowerCase())

const joinLink = computed(() => exp.value && inst.value ? joinUrl(exp.value.id, inst.value.id) : '')
/** Whose page a tile or link shows: each one fetches its own preview token for it. */
function targetOf (playerId: string): PreviewTarget | null {
  return exp.value && inst.value ? { experimentId: exp.value.id, instanceId: inst.value.id, playerId } : null
}

// ---- players ------------------------------------------------------------------------------------

const players = computed<PlayerLike[]>(() => {
  void graph.version.value // the graph's maps aren't reactive; its version is
  return sortPlayers([...graph.nodes.values()].map(n => ({ id: n.id, data: n.data })))
})
const testCount = computed(() => players.value.filter(isTestPlayer).length)
const aiCount = computed(() => players.value.filter(isAIPlayer).length)
const selected = computed(() => new Set(memory.ids))
const tiles = computed(() => {
  void graph.version.value
  return sortPlayers(memory.ids.map(id => {
    const n = graph.nodes.get(id)
    return { id, data: n ? n.data : {}, missing: !n }
  }))
})

// ---- choosing ---------------------------------------------------------------------------------

const listKey = ref(0)

function tooMany () {
  notify.warning(`Up to ${MAX_PREVIEWS} previews at a time`, 'Each preview is a whole participant page; more would slow your browser down. Remove some first.')
}

/**
 * A preview never takes the seat of a player whose own page is connected, nor of a computer player:
 * it only watches. Said as they are added, so a researcher isn't left wondering why nothing there can
 * be clicked.
 */
function noteReadOnly (list: PlayerLike[]) {
  const people = list.filter(p => !isComputerPlayer(p))
  const computers = list.filter(isComputerPlayer)
  if (people.length) {
    const one = people.length === 1
    notify.info(
      one ? `${playerName(people[0].id)} is connected right now` : `${people.length} of these players are connected right now`,
      `${one ? 'Their preview is' : 'Their previews are'} read-only: you see what they see, but can’t answer for them. Their own page is left alone.`,
    )
  }
  if (computers.length) {
    const one = computers.length === 1
    notify.info(
      one ? `${playerName(computers[0].id)} is a computer player` : `${computers.length} of these are computer players`,
      `${one ? 'Its preview is' : 'Their previews are'} read-only: you see what the computer does, and it goes on playing.`,
    )
  }
}

function toggle (id: string, on: boolean) {
  if (!on) { remove(id); return }
  const p = players.value.find(x => x.id === id)
  if (!p || selected.value.has(id)) return
  if (memory.ids.length >= MAX_PREVIEWS) { tooMany(); listKey.value++; return }
  noteReadOnly(shownReadOnly([p], selected.value))
  memory.ids = [...memory.ids, id]
}

/** A quick pick replaces what is shown. */
function pick (kind: QuickPick, n?: number) {
  let chosen = quickPick(players.value, kind, n ?? 4)
  if (!chosen.length) {
    notify.info(kind === 'test' ? 'No test players' : kind === 'ai' ? 'No computer players' : 'Nobody to show')
    return
  }
  if (chosen.length > MAX_PREVIEWS) {
    tooMany()
    chosen = chosen.slice(0, MAX_PREVIEWS)
  }
  noteReadOnly(shownReadOnly(chosen, selected.value))
  memory.ids = chosen.map(p => p.id)
  listKey.value++
}

function remove (id: string) {
  memory.ids = memory.ids.filter(x => x !== id)
  tabbed.delete(id)
  if (focusId.value === id) focusId.value = null
}
function clearAll () {
  memory.ids = []
  tabbed.clear()
  focusId.value = null
}

// ---- showing ----------------------------------------------------------------------------------

const deviceTabs: TabItem[] = [
  { value: 'desktop', label: 'Desktop', icon: Monitor },
  { value: 'phone', label: 'Phone', icon: Smartphone },
]
const sizeTabs: TabItem[] = [
  { value: 'S', label: 'Small' },
  { value: 'M', label: 'Medium' },
  { value: 'L', label: 'Large' },
]

const gridBox = ref<HTMLElement>()
const available = ref(0)
let ro: ResizeObserver | null = null
watch(gridBox, (el) => {
  ro?.disconnect()
  ro = null
  if (!el) return
  available.value = el.clientWidth
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => { available.value = el.clientWidth })
    ro.observe(el)
  }
})
onBeforeUnmount(() => ro?.disconnect())
const tileW = computed(() => tileWidth(memory.device, memory.size, available.value))

const nonces = reactive<Record<string, number>>({})
const globalNonce = ref(0)
function reload (id: string) { nonces[id] = (nonces[id] || 0) + 1 }
function reloadAll () { globalNonce.value++ }

/** Pages open elsewhere: one connection per player, so the tile steps aside. */
const tabbed = reactive(new Set<string>())
const focusId = ref<string | null>(null)
const focusIndex = computed(() => tiles.value.findIndex(t => t.id === focusId.value))
function step (delta: number) {
  const next = tiles.value[focusIndex.value + delta]
  if (next) {
    tabbed.delete(next.id)
    focusId.value = next.id
  }
}
function closeFocus () {
  const id = focusId.value
  focusId.value = null
  // the tile connects again in place of the full-size page
  if (id) reload(id)
}
watch(focusId, (id) => { if (id) tabbed.delete(id) })

// A player who isn't in the network (the session was resumed, or its engine reloaded, and they
// haven't joined again yet) is shown as not in the game: a preview of a real session adds nobody, and
// its page stops there. Once they are in the network again, their page is loaded again, to show them.
const wasMissing = new Set<string>()
watch(tiles, (list) => {
  for (const t of list) {
    if (t.missing) wasMissing.add(t.id)
    else if (wasMissing.delete(t.id)) reload(t.id)
  }
  for (const id of [...wasMissing]) if (!list.some(t => t.id === id)) wasMissing.delete(id)
}, { immediate: true })

async function openTab (id: string) {
  const target = targetOf(id)
  if (!target) return
  // The tab is opened now, while the click still counts, so pop-up blockers let it through; the
  // page loads in it once its preview token is here. It is cut off from this tab first.
  const w = window.open('about:blank', '_blank')
  if (!w) {
    notify.error('The browser blocked the new tab', 'Allow pop-ups for this site, then try again.')
    return
  }
  try { w.opener = null } catch (e) { /* not allowed: the page loads anyway */ }
  // one connection per player: the tile and the full-size view step aside right away
  tabbed.add(id)
  if (focusId.value === id) focusId.value = null
  try {
    const { token } = await fetchPreviewToken(target)
    if (w.closed) {
      tabbed.delete(id)
      return
    }
    w.location.href = previewPageUrl(target, token)
  } catch (e) {
    try { w.close() } catch (err) { /* already gone */ }
    // nothing opened: the tile shows the page again
    tabbed.delete(id)
    notify.error(`Couldn’t open ${playerName(id)}’s page in a new tab`, describeError(e))
  }
}

// ---- following the session ---------------------------------------------------------------

// A new session starts from a clean slate; on the first look the test players are shown, as in
// the old Testing panel (but not those whose seat is connected: see autoPicked).
watch(() => [inst.value?.id, players.value.length] as const, ([id]) => {
  if (id == null) return
  if (memory.instanceId !== id) {
    memory.instanceId = id
    memory.ids = []
    memory.autoPicked = false
    tabbed.clear()
    focusId.value = null
  }
  if (!memory.autoPicked && players.value.length) {
    memory.autoPicked = true
    const tests = autoPicked(players.value)
    if (tests.length && !memory.ids.length) memory.ids = tests.map(p => p.id)
  }
}, { immediate: true })

// "Preview their screen" from the player panel links here with ?player=<id>: show that player full size.
watch(() => [route.query.player, players.value.length] as const, ([q]) => {
  const id = typeof q === 'string' ? q : ''
  const p = players.value.find(x => x.id === id)
  if (!id || !p) return
  router.replace({ query: { ...route.query, player: undefined } })
  if (!selected.value.has(id)) toggle(id, true)
  if (selected.value.has(id)) focusId.value = id
}, { immediate: true })

// ---- the panel and the notice -----------------------------------------------------------------

// On narrow screens the list sits above the previews; once some are chosen it starts folded away.
const narrow = typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 900px)').matches
const pickerOpen = ref(!(narrow && memory.ids.length))
// (a new key whenever what previews do changes, so the notice is shown once more: previews of
// connected players became read-only, then those of computer players, then a preview of someone who
// left stopped counting as them)
const NOTICE_KEY = 'bb.preview.liveNoticeSeen.4'
const noticeSeen = ref(readFlag(NOTICE_KEY))
function dismissNotice () {
  noticeSeen.value = true
  try { localStorage.setItem(NOTICE_KEY, '1') } catch (e) { /* private mode */ }
}
function readFlag (key: string) {
  try { return localStorage.getItem(key) === '1' } catch (e) { return false }
}
</script>

<style>
.pv { display: grid; grid-template-columns: 296px minmax(0, 1fr); gap: 20px; align-items: start; }
.pv__side {
  position: sticky;
  top: 16px;
  display: flex;
  flex-direction: column;
  max-height: calc(100dvh - var(--topbar-height) - 40px);
  padding: 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}
.pv__picker { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.pv__picker > .pp { flex: 1; }
.pv__side-toggle { display: none; }
.pv__main { min-width: 0; display: flex; flex-direction: column; gap: 14px; }
.pv__toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
.pv__reload { margin-left: auto; }
.pv__gridbox { min-width: 0; }
.pv__grid { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 20px 18px; align-items: flex-start; }

.pv__notice {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--info-soft);
  color: var(--text-soft);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.pv__notice-icon { flex: none; color: var(--info); margin-top: 1px; }
.pv__notice-text { flex: 1; min-width: 0; }
.pv__notice-text strong { color: var(--text); }
.pv__engine { display: flex; align-items: center; gap: 8px; font-size: var(--text-sm); color: var(--muted); }

@media (max-width: 900px) {
  .pv { grid-template-columns: minmax(0, 1fr); }
  .pv__side { position: static; max-height: none; padding: 0; }
  .pv__side-toggle {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 12px 14px;
    border: 0;
    background: transparent;
    color: var(--text);
    font-weight: 600;
    text-align: left;
    cursor: pointer;
  }
  .pv__side-toggle span { flex: 1; }
  .pv__side-chev { color: var(--muted); transition: transform var(--duration) var(--ease); }
  .pv__side:not(.is-collapsed) .pv__side-chev { transform: rotate(180deg); }
  .pv__picker { padding: 0 14px 14px; }
  .pv__side.is-collapsed .pv__picker { display: none; }
  .pv__picker .pp__list { max-height: 260px; }
  .pv__picker .pp__head { display: none; }
}
@media (max-width: 640px) {
  .pv__reload { margin-left: 0; }
  .pv__notice { flex-wrap: wrap; }
}
</style>
