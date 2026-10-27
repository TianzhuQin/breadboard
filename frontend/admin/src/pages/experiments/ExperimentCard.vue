<template>
  <article
    class="xcard"
    :class="{ 'is-current': current, 'is-flash': flash }"
    :aria-labelledby="titleId"
    :aria-current="current ? 'true' : undefined"
  >
    <header class="xcard__head">
      <span class="xcard__icon" aria-hidden="true"><FlaskConical :size="17" /></span>
      <div class="xcard__titles">
        <h3 :id="titleId" class="xcard__name" :title="exp.name">{{ exp.name }}</h3>
        <div class="xcard__badges">
          <span class="xcard__id" :title="`Experiment id ${exp.id}`">#{{ exp.id }}</span>
          <BBadge v-if="live" tone="success" dot pulse>{{ live.status === 'TESTING' ? 'Testing' : 'Running' }}</BBadge>
          <BBadge v-if="exp.fileMode" tone="info"><HardDrive :size="12" aria-hidden="true" /> File mode</BBadge>
        </div>
      </div>
      <BMenu :items="menu" align="right" :width="236">
        <template #trigger="{ toggle, attrs }">
          <button type="button" class="xcard__more" v-bind="attrs" :aria-label="`More actions for ${exp.name}`" @click="toggle">
            <Ellipsis :size="18" />
          </button>
        </template>
      </BMenu>
    </header>

    <dl class="xcard__stats">
      <div class="xcard__stat">
        <dt>Sessions</dt>
        <dd class="tabular">{{ sessions }}</dd>
      </div>
      <div class="xcard__stat">
        <dt>Steps</dt>
        <dd class="tabular">{{ exp.steps?.length || 0 }}</dd>
      </div>
      <div class="xcard__stat">
        <dt>Last session</dt>
        <dd :title="last ? `${last.name} (${last.creationTime})` : undefined">{{ last ? whenText : 'None yet' }}</dd>
      </div>
    </dl>

    <footer class="xcard__foot">
      <span class="xcard__langs" :title="languageTitle">
        <Languages :size="14" aria-hidden="true" />
        <span class="xcard__langs-text"><span class="sr-only">Languages: </span>{{ languageText }}</span>
      </span>
      <span v-if="current" class="xcard__current"><Check :size="14" aria-hidden="true" /> Current</span>
      <BButton v-else size="sm" :loading="selecting" :icon-right="ArrowRight" @click="emit('select')">
        Work on this
      </BButton>
    </footer>
  </article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight, Check, Copy, Database, Download, Ellipsis, FlaskConical, HardDrive, Languages, Trash2,
} from 'lucide-vue-next'
import { BBadge, BButton, BMenu, type MenuItem } from '@/ui'
import { uid } from '@/ui/uid'
import type { Experiment } from '@/store/types'
import { formatWhen, lastSession, parseServerTime, runningSession } from './experiments'

const props = defineProps<{
  exp: Experiment
  current: boolean
  selecting?: boolean
  exporting?: boolean
  flash?: boolean
}>()
const emit = defineEmits<{ select: [], export: [], duplicate: [], 'file-mode': [], delete: [] }>()

const titleId = uid('xcard')
const live = computed(() => runningSession(props.exp))
const last = computed(() => lastSession(props.exp))
const sessions = computed(() => props.exp.instances?.length || 0)
const whenText = computed(() => formatWhen(parseServerTime(last.value?.creationTime)))
const languageNames = computed(() => (props.exp.languages || []).map(l => l.name || l.code).filter(Boolean))
const languageText = computed(() => {
  const n = languageNames.value
  if (!n.length) return 'No languages'
  return n.length <= 2 ? n.join(', ') : `${n[0]} +${n.length - 1}`
})
const languageTitle = computed(() => languageNames.value.join(', ') || undefined)

const menu = computed<MenuItem[]>(() => [
  { label: props.exporting ? 'Exporting…' : 'Export as .zip', icon: Download, disabled: props.exporting, run: () => emit('export') },
  { label: 'Duplicate…', icon: Copy, run: () => emit('duplicate') },
  props.exp.fileMode
    ? { label: 'Leave file mode…', icon: Database, run: () => emit('file-mode') }
    : { label: 'Use file mode…', icon: HardDrive, run: () => emit('file-mode') },
  { divider: true },
  { label: 'Delete…', icon: Trash2, danger: true, run: () => emit('delete') },
])
</script>

<style>
.xcard {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 16px 14px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  min-width: 0;
  transition: border-color var(--duration) var(--ease), box-shadow var(--duration) var(--ease);
}
.xcard:hover { border-color: var(--border-strong); }
.xcard.is-current { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent) inset, var(--shadow-sm); }
.xcard.is-flash { animation: xcard-flash 1.6s var(--ease) 1; }
@keyframes xcard-flash { 0%, 40% { box-shadow: 0 0 0 4px var(--accent-ring); } 100% { box-shadow: var(--shadow-sm); } }
.xcard__head { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.xcard__icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; background: var(--bg-subtle); color: var(--muted); flex: none; }
.xcard.is-current .xcard__icon { background: var(--accent-soft); color: var(--accent); }
.xcard__titles { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.xcard__name { font-size: var(--text-lg); font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 1.3; }
.xcard__badges { display: flex; flex-wrap: wrap; gap: 6px; }
.xcard__badges { align-items: center; min-height: 22px; }
.xcard__id { font-size: var(--text-xs); color: var(--faint); font-variant-numeric: tabular-nums; margin-right: 2px; }
.xcard__badges .b-badge svg { margin-left: -2px; }
.xcard__more {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin: -2px -4px 0 0;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.xcard__more:hover, .xcard__more[aria-expanded='true'] { background: var(--surface-hover); color: var(--text); }
.xcard__stats { display: grid; grid-template-columns: auto auto minmax(0, 1fr); gap: 20px; margin: 0; }
.xcard__stat { min-width: 0; }
.xcard__stat dt { font-size: 11px; font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase; color: var(--faint); }
.xcard__stat dd { margin: 2px 0 0; font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.xcard__foot { display: flex; align-items: center; gap: 10px; margin-top: auto; padding-top: 12px; border-top: 1px solid var(--border); min-height: 41px; }
.xcard__langs { flex: 1; min-width: 0; display: inline-flex; align-items: center; gap: 6px; font-size: var(--text-sm); color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.xcard__langs svg { flex: none; }
.xcard__langs-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.xcard__current { display: inline-flex; align-items: center; gap: 5px; font-size: var(--text-sm); font-weight: 600; color: var(--accent); white-space: nowrap; }
</style>
