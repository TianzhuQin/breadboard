<template>
  <section class="pp" aria-labelledby="pp-title">
    <header class="pp__head">
      <h3 id="pp-title" class="pp__title">Players</h3>
      <span class="pp__count tabular">{{ selected.size }} of {{ players.length }} shown</span>
    </header>

    <div class="pp__picks" role="group" aria-label="Quick picks">
      <BButton size="sm" :icon="FlaskConical" :disabled="!testCount" :title="testCount ? 'Players the experiment marked with testmode' : 'No player has testmode set'" @click="emit('pick', 'test')">
        Test players <span class="pp__n">{{ testCount }}</span>
      </BButton>
      <BButton size="sm" :icon="Bot" :disabled="!aiCount" :title="aiCount ? 'Seats the computer plays' : 'No computer players'" @click="emit('pick', 'ai')">
        AI players <span class="pp__n">{{ aiCount }}</span>
      </BButton>
      <div class="pp__first">
        <BButton size="sm" :icon="ListOrdered" :disabled="!players.length" @click="emit('pick', 'first', firstN)">First</BButton>
        <div class="pp__n-input">
          <BInput
            v-model="firstN"
            size="sm"
            type="number"
            min="1"
            :max="max"
            aria-label="How many players to show"
          />
        </div>
      </div>
      <BButton size="sm" variant="ghost" :disabled="!selected.size" @click="emit('clear')">Clear</BButton>
    </div>

    <BInput v-model="query" size="sm" :icon="Search" placeholder="Find a player" aria-label="Find a player" />

    <ul v-if="visible.length" :key="listKey" class="pp__list" aria-label="Players to preview">
      <li v-for="p in visible" :key="p.id" class="pp__item" :class="{ 'is-on': selected.has(p.id) }">
        <BCheckbox :model-value="selected.has(p.id)" @update:model-value="(v: boolean) => emit('toggle', p.id, v)">
          <span class="pp__label">
            <span class="pp__id" :title="p.id">{{ playerName(p.id) }}</span>
            <span v-if="isTestPlayer(p)" class="pp__tag" title="Test player (testmode)"><FlaskConical :size="12" aria-hidden="true" /><span class="sr-only">, test player</span></span>
            <span v-if="isAIPlayer(p)" class="pp__tag" title="Played by the computer"><Bot :size="12" aria-hidden="true" /><span class="sr-only">, computer player</span></span>
            <span
              class="pp__dot"
              :class="{ 'is-on': isConnected(p) }"
              :title="connectionText(p)"
            /><span class="sr-only">, {{ connectionText(p) }}</span>
          </span>
        </BCheckbox>
      </li>
    </ul>
    <p v-else class="pp__none">{{ players.length ? 'No player matches.' : 'Nobody is in the network yet.' }}</p>

    <p class="pp__legend">
      <span class="pp__dot is-on" aria-hidden="true" /> connected
      <FlaskConical :size="12" aria-hidden="true" /> test
      <Bot :size="12" aria-hidden="true" /> computer
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { playerName } from '@/network/players'
import { Bot, FlaskConical, ListOrdered, Search } from 'lucide-vue-next'
import { BButton, BCheckbox, BInput } from '@/ui'
import {
  heldByPreview, isAIPlayer, isComputerPlayer, isConnected, isTestPlayer, matchesPlayer, ownPageConnected, type PlayerLike,
  type QuickPick,
} from './preview'

const props = defineProps<{
  players: PlayerLike[]
  selected: ReadonlySet<string>
  max: number
  /** Bumped to redraw the checkboxes when a change was refused. */
  listKey: number
}>()
const emit = defineEmits<{ toggle: [id: string, on: boolean], pick: [kind: QuickPick, n?: number], clear: [] }>()

const query = ref('')
const firstN = ref<number | null>(4)
const visible = computed(() => props.players.filter(p => matchesPlayer(p, query.value)))
const testCount = computed(() => props.players.filter(isTestPlayer).length)
const aiCount = computed(() => props.players.filter(isAIPlayer).length)

function connectionText (p: PlayerLike) {
  if (props.selected.has(p.id)) return 'shown here'
  if (isComputerPlayer(p)) return 'played by the computer, so a preview is read-only'
  if (ownPageConnected(p)) return 'their own page is connected, so a preview is read-only'
  // (another console's preview, which a preview from here would take the seat from)
  if (heldByPreview(p)) {
    return isConnected(p) ? 'a preview has their seat: their own page isn’t connected' : 'away: a preview stands in for them'
  }
  return 'not connected'
}
</script>

<style>
.pp { display: flex; flex-direction: column; gap: 12px; min-height: 0; }
.pp__head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.pp__title { font-size: var(--text-md); font-weight: 650; }
.pp__count { font-size: var(--text-xs); color: var(--muted); }
.pp__picks { display: flex; flex-wrap: wrap; gap: 6px; }
.pp__n { color: var(--muted); font-variant-numeric: tabular-nums; }
.pp__first { display: inline-flex; gap: 4px; align-items: center; }
.pp__n-input { width: 64px; }
.pp__list {
  list-style: none;
  margin: 0;
  padding: 4px;
  flex: 1;
  min-height: 80px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
}
.pp__item { border-radius: var(--radius-xs); }
.pp__item:hover { background: var(--surface-hover); }
.pp__item.is-on { background: var(--accent-soft); }
.pp__item .b-check { display: flex; width: 100%; padding: 6px 8px; }
.pp__item .b-check__label { flex: 1; min-width: 0; }
.pp__label { display: flex; align-items: center; gap: 6px; min-width: 0; }
.pp__id { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono); font-size: var(--text-sm); color: var(--text); }
.pp__tag { display: inline-grid; place-items: center; flex: none; color: var(--muted); }
.pp__dot { flex: none; width: 7px; height: 7px; margin-left: auto; border-radius: 50%; background: var(--border-strong); }
.pp__dot.is-on { background: var(--success); }
.pp__none { font-size: var(--text-sm); color: var(--muted); padding: 8px 2px; }
.pp__legend { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 6px; font-size: var(--text-xs); color: var(--faint); }
.pp__legend .pp__dot { margin-left: 0; }
.pp__legend svg { margin-left: 6px; }
</style>
