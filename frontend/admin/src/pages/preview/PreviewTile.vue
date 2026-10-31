<template>
  <li class="pt" :style="{ width: `${width}px` }" :aria-label="`Preview of ${name}`">
    <div class="pt__head">
      <span class="pt__id" :title="playerId">{{ name }}</span>
      <span class="pt__tags">
        <span v-if="test" class="pt__tag" title="Test player (testmode)"><FlaskConical :size="13" aria-hidden="true" /><span class="sr-only">Test player</span></span>
        <span v-if="ai" class="pt__tag" title="Played by the computer"><Bot :size="13" aria-hidden="true" /><span class="sr-only">Computer player</span></span>
        <!-- (the participant page says so too, but at tile size its words are a few pixels high) -->
        <span v-if="watching" class="pt__tag is-text" :title="watching">
          <Eye :size="13" aria-hidden="true" /><span :class="{ 'sr-only': compact }">Watching</span>
        </span>
        <span v-else-if="role === 'away'" class="pt__tag is-text is-away" title="Away: their own page isn’t connected. You are standing in for them, but they count as away">
          <UserX :size="13" aria-hidden="true" /><span :class="{ 'sr-only': compact }">Away</span>
        </span>
      </span>
      <span class="pt__acts">
        <BButton
          size="sm"
          variant="ghost"
          :icon="RotateCw"
          :disabled="!!paused"
          :aria-label="`Reload ${name}'s page`"
          title="Reload"
          @click="emit('reload')"
        />
        <BMenu :items="menu" align="right" :width="210">
          <template #trigger="{ toggle, attrs }">
            <BButton size="sm" variant="ghost" :icon="Ellipsis" v-bind="attrs" :aria-label="`More for ${name}`" title="More" @click="toggle" />
          </template>
        </BMenu>
      </span>
    </div>

    <div class="pt__body" :style="{ height: `${geo.height}px` }">
      <template v-if="!paused">
        <PreviewFrame
          :key="frameKey"
          :target="target"
          :title="`Participant page of ${name}`"
          :device="device"
          :width="width"
          :interactive="interactive"
          lazy
        />
        <button
          v-if="!interactive"
          type="button"
          class="pt__cover"
          :aria-label="`Open ${name}'s page full size`"
          @click="emit('focus')"
        >
          <span class="pt__cover-hint"><Maximize2 :size="14" aria-hidden="true" /> Open full size</span>
        </button>
      </template>
      <div v-else class="pt__paused">
        <component :is="paused === 'focus' ? Maximize2 : ExternalLink" :size="18" aria-hidden="true" />
        <p>{{ paused === 'focus' ? 'Shown full size' : 'Open in another tab' }}</p>
        <p class="pt__paused-why">One page per player at a time: the newer one would take the connection from the other.</p>
        <BButton v-if="paused === 'tab'" size="sm" @click="emit('resume')">Show here again</BButton>
      </div>
    </div>

    <p v-if="note" class="pt__note" role="status">
      <TriangleAlert :size="13" aria-hidden="true" />
      {{ note }}
    </p>
  </li>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Bot, Ellipsis, ExternalLink, Eye, FlaskConical, Maximize2, RotateCw, TriangleAlert, UserX, X } from 'lucide-vue-next'
import { BButton, BMenu, type MenuItem } from '@/ui'
import { playerName } from '@/network/players'
import PreviewFrame from './PreviewFrame.vue'
import { WATCHING_WHY, frameGeometry, type DeviceKind, type PreviewRole, type PreviewTarget } from './preview'

const props = defineProps<{
  playerId: string
  target: PreviewTarget | null
  device: DeviceKind
  width: number
  interactive: boolean
  test: boolean
  ai: boolean
  /** What a preview of this player does (preview.ts previewRole). */
  role?: PreviewRole | null
  /** Changing it loads the page again, with a new preview token. */
  frameKey: string
  /** Why the page isn't shown here right now. */
  paused: 'focus' | 'tab' | null
  note?: string
}>()
const emit = defineEmits<{ focus: [], reload: [], 'open-tab': [], remove: [], resume: [] }>()

// "AI 6 (_6)" for a computer player, as in the list
const name = computed(() => playerName(props.playerId))
const geo = computed(() => frameGeometry(props.device, props.width))
/** Why the preview only watches ('' when it doesn't), for the Watching tag. */
const watching = computed(() => props.role === 'computer' || props.role === 'watching' ? `Watching: ${WATCHING_WHY[props.role]}` : '')
// small tiles keep the tags' icons only (the words stay for screen readers and in the tooltip)
const compact = computed(() => props.width < 230)
const menu = computed<MenuItem[]>(() => [
  { label: 'Open full size', icon: Maximize2, run: () => emit('focus') },
  { label: 'Open in a new tab', icon: ExternalLink, run: () => emit('open-tab') },
  { label: 'Reload', icon: RotateCw, disabled: !!props.paused, run: () => emit('reload') },
  { divider: true },
  { label: 'Stop previewing', icon: X, run: () => emit('remove') },
])
</script>

<style>
.pt { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.pt__head { display: flex; align-items: center; gap: 6px; min-width: 0; height: 28px; }
.pt__id {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text);
}
.pt__tags { display: inline-flex; gap: 3px; flex: none; }
.pt__tag {
  display: inline-grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: var(--bg-subtle);
  color: var(--text-soft);
}
.pt__tag.is-text {
  display: inline-flex;
  gap: 4px;
  width: auto;
  min-width: 20px;
  padding: 0 6px;
  font-size: var(--text-xs);
  font-weight: 600;
  white-space: nowrap;
}
.pt__tag.is-away { background: var(--warning-soft); color: var(--warning-soft-text); }
.pt__acts { margin-left: auto; display: inline-flex; align-items: center; flex: none; }
.pt__body { position: relative; }
.pt__cover {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 10px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: zoom-in;
}
.pt__cover-hint {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 999px;
  background: var(--surface-raised);
  color: var(--text);
  font-size: var(--text-xs);
  font-weight: 600;
  box-shadow: var(--shadow);
  opacity: 0;
  transform: translateY(4px);
  transition: opacity var(--duration) var(--ease), transform var(--duration) var(--ease);
}
.pt__cover:hover .pt__cover-hint, .pt__cover:focus-visible .pt__cover-hint { opacity: 1; transform: none; }
.pt__cover:hover { box-shadow: inset 0 0 0 2px var(--accent-ring); }
.pt__cover:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.pt__paused {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 12px;
  text-align: center;
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
  color: var(--muted);
  font-size: var(--text-sm);
}
.pt__paused p:first-of-type { color: var(--text-soft); font-weight: 600; }
.pt__paused-why { font-size: var(--text-xs); max-width: 260px; }
.pt__note { display: flex; gap: 5px; align-items: flex-start; font-size: var(--text-xs); color: var(--warning-soft-text); line-height: 1.4; }
.pt__note svg { flex: none; margin-top: 1px; }
</style>
