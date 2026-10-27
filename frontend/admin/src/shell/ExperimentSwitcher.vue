<template>
  <div ref="root" class="xsw" @keydown.esc.stop="close(true)">
    <button
      ref="trigger"
      type="button"
      class="xsw__btn"
      :aria-expanded="open ? 'true' : 'false'"
      aria-haspopup="listbox"
      :aria-controls="listId"
      @click="toggle"
    >
      <span class="xsw__icon" aria-hidden="true"><FlaskConical :size="16" /></span>
      <span class="xsw__text">
        <span class="xsw__eyebrow">Experiment</span>
        <span class="xsw__name">{{ admin.experiment ? admin.experiment.name : 'Choose an experiment' }}</span>
      </span>
      <ChevronsUpDown :size="15" class="xsw__chev" aria-hidden="true" />
    </button>

    <Transition name="b-menu">
      <div v-if="open" class="xsw__pop">
        <div class="xsw__search">
          <Search :size="15" aria-hidden="true" />
          <input
            ref="searchEl"
            v-model="query"
            type="text"
            placeholder="Find an experiment"
            aria-label="Find an experiment"
            :aria-activedescendant="activeId"
            :aria-controls="listId"
            @keydown.down.prevent="move(1)"
            @keydown.up.prevent="move(-1)"
            @keydown.enter.prevent="pickActive"
          >
        </div>
        <p v-if="running" class="xsw__note">Stop the running session before switching experiments.</p>
        <ul :id="listId" class="xsw__list" role="listbox" aria-label="Experiments">
          <li
            v-for="(e, i) in filtered"
            :id="`${listId}-${e.id}`"
            :key="e.id"
            role="option"
            class="xsw__opt"
            :class="{ 'is-active': i === active, 'is-current': admin.experiment && e.id === admin.experiment.id }"
            :aria-selected="admin.experiment && e.id === admin.experiment.id ? 'true' : 'false'"
            :aria-disabled="running ? 'true' : undefined"
            @mouseenter="active = i"
            @click="pick(e)"
          >
            <span class="xsw__opt-name">{{ e.name }}</span>
            <span class="xsw__opt-meta">{{ e.instances.length }} {{ e.instances.length === 1 ? 'session' : 'sessions' }}</span>
            <Check v-if="admin.experiment && e.id === admin.experiment.id" :size="15" class="xsw__check" />
          </li>
          <li v-if="!filtered.length" class="xsw__none">{{ experiments.length ? 'No match' : 'No experiments yet' }}</li>
        </ul>
        <div class="xsw__actions">
          <button type="button" class="xsw__action" @click="go('/experiments?action=new')"><Plus :size="15" /> New experiment</button>
          <button type="button" class="xsw__action" @click="go('/experiments?action=import')"><Upload :size="15" /> Import</button>
          <button type="button" class="xsw__action" @click="go('/experiments')"><Settings2 :size="15" /> Manage</button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { FlaskConical, ChevronsUpDown, Search, Check, Plus, Upload, Settings2 } from 'lucide-vue-next'
import { admin, actions, experiments, running } from '@/store/admin'
import type { Experiment } from '@/store/types'
import { notify } from '@/ui'
import { uid } from '@/ui/uid'

const router = useRouter()
const open = ref(false)
const query = ref('')
const active = ref(0)
const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const searchEl = ref<HTMLInputElement>()
const listId = uid('xsw')

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  return q ? experiments.value.filter(e => e.name.toLowerCase().includes(q)) : experiments.value
})
const activeId = computed(() => filtered.value[active.value] ? `${listId}-${filtered.value[active.value].id}` : undefined)

function toggle () { open.value ? close() : show() }
async function show () {
  open.value = true
  query.value = ''
  active.value = Math.max(0, filtered.value.findIndex(e => admin.experiment && e.id === admin.experiment.id))
  await nextTick()
  searchEl.value?.focus()
}
function close (refocus = false) {
  if (!open.value) return
  open.value = false
  if (refocus) trigger.value?.focus()
}
function move (d: number) {
  const n = filtered.value.length
  if (n) active.value = (active.value + d + n) % n
}
function pickActive () {
  const e = filtered.value[active.value]
  if (e) pick(e)
}
async function pick (e: Experiment) {
  if (running.value) return
  close(true)
  if (admin.experiment && e.id === admin.experiment.id) return
  await actions.selectExperiment(e.id)
  if (admin.experiment?.id === e.id) notify.success(`Switched to ${e.name}`)
  else notify.error(`Could not switch to ${e.name}`, admin.requestError?.request === 'SelectExperiment' ? admin.requestError.text : 'The server did not confirm the switch. The output panel may say why.')
}
function go (to: string) {
  close()
  router.push(to)
}
function onDoc (ev: MouseEvent) {
  if (root.value && !root.value.contains(ev.target as Node)) close()
}
watch(open, v => v ? document.addEventListener('mousedown', onDoc) : document.removeEventListener('mousedown', onDoc))
watch(query, () => { active.value = 0 })
onBeforeUnmount(() => document.removeEventListener('mousedown', onDoc))
</script>

<style>
.xsw { position: relative; }
.xsw__btn {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-sunken);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--duration) var(--ease), background var(--duration) var(--ease);
}
.xsw__btn:hover { border-color: var(--border-strong); background: var(--surface-hover); }
.xsw__icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: var(--accent-soft); color: var(--accent); flex: none; }
.xsw__text { display: flex; flex-direction: column; min-width: 0; flex: 1; }
.xsw__eyebrow { font-size: 10.5px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; color: var(--faint); }
.xsw__name { font-weight: 600; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.xsw__chev { color: var(--faint); flex: none; }
.xsw__pop {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 70;
  width: max(100%, 300px);
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.xsw__search { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); color: var(--faint); }
.xsw__search input { flex: 1; border: 0; outline: 0; background: transparent; color: var(--text); }
.xsw__note { padding: 8px 12px; font-size: var(--text-xs); color: var(--warning-soft-text); background: var(--warning-soft); }
.xsw__list { list-style: none; margin: 0; padding: 5px; max-height: 320px; overflow-y: auto; }
.xsw__opt { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border-radius: var(--radius-xs); cursor: pointer; }
.xsw__opt.is-active { background: var(--surface-hover); }
.xsw__opt[aria-disabled='true'] { cursor: not-allowed; opacity: 0.6; }
.xsw__opt-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 520; }
.xsw__opt.is-current .xsw__opt-name { font-weight: 650; }
.xsw__opt-meta { font-size: var(--text-xs); color: var(--faint); white-space: nowrap; }
.xsw__check { color: var(--accent); }
.xsw__none { padding: 14px; text-align: center; color: var(--muted); font-size: var(--text-sm); }
.xsw__actions { display: flex; border-top: 1px solid var(--border); padding: 5px; gap: 2px; }
.xsw__action {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 7px 6px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  color: var(--text-soft);
  font-size: var(--text-sm);
  font-weight: 550;
  cursor: pointer;
}
.xsw__action:hover { background: var(--surface-hover); color: var(--text); }
</style>
