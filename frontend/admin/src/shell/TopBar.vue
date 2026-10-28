<template>
  <header class="topbar">
    <button
      ref="menuBtn"
      type="button"
      class="topbar__menu"
      aria-label="Open navigation"
      aria-controls="shell-nav"
      :aria-expanded="navOpen ? 'true' : 'false'"
      @click="emit('menu')"
    >
      <Menu :size="20" />
    </button>
    <div class="topbar__titles">
      <h1 class="topbar__title">{{ title }}</h1>
      <p v-if="admin.experiment" class="topbar__sub">{{ admin.experiment.name }}<span v-if="admin.experiment.fileMode" class="topbar__file"> · file mode</span></p>
    </div>

    <div class="topbar__right">
      <RouterLink to="/session" class="topbar__session" :class="`is-${sessionTone}`" :title="sessionTitle">
        <span class="topbar__session-dot" aria-hidden="true" />
        <span class="topbar__session-text">{{ sessionLabel }}</span>
      </RouterLink>

      <BMenu :items="engineItems" align="right" :width="250">
        <template #trigger="{ toggle, attrs }">
          <button type="button" class="topbar__chip" :class="`is-${engineState}`" v-bind="attrs" :title="engineTitle" @click="toggle">
            <BSpinner v-if="engineState === 'loading'" :size="13" label="Loading the experiment's steps" />
            <component :is="engineIcon" v-else :size="15" aria-hidden="true" />
            <span class="topbar__chip-text">{{ engineLabel }}</span>
          </button>
        </template>
      </BMenu>

      <span v-if="connectionState !== 'open'" class="topbar__chip is-warn" role="status">
        <WifiOff :size="15" aria-hidden="true" />
        <span class="topbar__chip-text">{{ connectionState === 'retrying' ? 'Reconnecting…' : 'Connecting…' }}</span>
      </span>

      <button
        type="button"
        class="topbar__icon-btn"
        :class="{ 'is-on': outputOpen }"
        :aria-pressed="outputOpen ? 'true' : 'false'"
        :aria-label="admin.unseenErrors ? `Output, ${admin.unseenErrors} new errors` : 'Output'"
        title="Output (Ctrl+J)"
        @click="emit('toggle-output')"
      >
        <PanelBottom :size="18" />
        <span v-if="admin.unseenErrors" class="topbar__badge">{{ admin.unseenErrors > 9 ? '9+' : admin.unseenErrors }}</span>
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Menu, WifiOff, PanelBottom, Cpu, CircleCheck, TriangleAlert, RotateCw } from 'lucide-vue-next'
import { BMenu, BSpinner, confirm, type MenuItem } from '@/ui'
import { admin, actions, connectionState, ensureConnected } from '@/store/admin'
import { REBUILD_PAUSES_AUTOPILOT, engineUse, reloadKeeps } from '@/store/engineGuard'

defineProps<{ outputOpen: boolean, navOpen?: boolean }>()
const emit = defineEmits<{ menu: [], 'toggle-output': [] }>()
const menuBtn = ref<HTMLButtonElement>()
/** For the shell: give focus back to the menu button when the phone navigation closes. */
defineExpose({ focusMenu: () => menuBtn.value?.focus() })
const route = useRoute()
const title = computed(() => (route.meta.title as string) || 'Breadboard')

const sessionTone = computed(() => {
  const s = admin.instance?.status
  if (s === 'RUNNING' || s === 'TESTING') return 'live'
  if (s === 'FINISHED') return 'done'
  if (s === 'STOPPED') return 'stopped'
  return 'none'
})
const sessionLabel = computed(() => {
  const i = admin.instance
  if (!i) return 'No session'
  const status = { RUNNING: 'Running', TESTING: 'Testing', STOPPED: 'Stopped', FINISHED: 'Finished', ARCHIVED: 'Archived' }[i.status] || i.status
  return `${status} · ${i.name}`
})
const sessionTitle = computed(() => admin.instance ? `Session "${admin.instance.name}" (${admin.instance.status.toLowerCase()})` : 'No session is selected')

// (With no experiment chosen yet, e.g. right after creating the first account, the engine's loading
// means nothing to the researcher: it just says Engine.)
// Steps saved since the last load matter only to a running session (and to the Script console):
// the next session loads what is saved, so with none running the chip just says they are saved.
const engineState = computed(() => {
  if (!admin.experiment) return 'ready'
  const live = admin.instance?.status === 'RUNNING' || admin.instance?.status === 'TESTING'
  if (admin.engine === 'stale' && !admin.engineProblem && !live) return 'saved'
  return admin.engine
})
const engineLabel = computed(() => ({ ready: 'Engine', loading: 'Loading steps…', loaded: 'Steps loaded', saved: 'Steps saved', stale: 'Reload needed' }[engineState.value]))
const engineIcon = computed(() => ({ ready: Cpu, loading: Cpu, loaded: CircleCheck, saved: CircleCheck, stale: TriangleAlert }[engineState.value]))
const engineTitle = computed(() => engineState.value === 'saved'
  ? 'The next session you open uses the saved steps. Reload the engine only to try them now (Script console, Preview).'
  : admin.engine === 'stale'
    ? (admin.engineProblem || 'The steps changed since the engine loaded them. Reload it to use the new steps.')
    : 'The script engine that runs the experiment')
const engineHeading = computed(() => {
  if (admin.engine !== 'stale') return 'Script engine'
  if (engineState.value === 'saved') return 'Steps saved since the last load'
  return admin.engineProblem ? 'The last reload may not have finished' : 'Steps changed since the last load'
})

async function reload () {
  if (!ensureConnected()) return
  // the engine is shared by every account and the autopilot, so the people in it may not be ours
  // (this account's own running session carries on through a reload)
  const { who, pausesAutopilot } = await engineUse(reloadKeeps())
  const ok = await confirm({
    title: 'Reload the script engine?',
    message: who
      ? `${who}\n\nReloading restarts the experiment's scripts from its steps. The engine is shared by every researcher on this server, so everyone in the network is disconnected and the network is emptied.` +
        (pausesAutopilot ? `\n\n${REBUILD_PAUSES_AUTOPILOT}` : '')
      : 'This restarts the experiment\'s scripts from its steps.',
    confirmLabel: 'Reload',
    danger: !!who,
  })
  // (the connection may have dropped while the question was open)
  if (ok && !actions.reloadEngine()) ensureConnected()
}
const engineItems = computed<MenuItem[]>(() => [
  { heading: engineHeading.value },
  { label: 'Reload engine', icon: RotateCw, disabled: admin.engine === 'loading', run: reload },
])
</script>

<style>
.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 14px;
  height: var(--topbar-height);
  padding: 0 20px;
  background: color-mix(in srgb, var(--bg) 82%, transparent);
  backdrop-filter: saturate(1.4) blur(10px);
  border-bottom: 1px solid var(--border);
}
.topbar__menu { display: none; border: 0; background: transparent; color: var(--text-soft); padding: 6px; border-radius: var(--radius-xs); cursor: pointer; }
.topbar__menu:hover { background: var(--surface-hover); }
.topbar__titles { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.topbar__title { min-width: 0; font-size: 17px; font-weight: 650; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.topbar__sub { font-size: var(--text-sm); color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.topbar__file { color: var(--warning-soft-text); }
.topbar__right { display: flex; align-items: center; gap: 8px; min-width: 0; }
.topbar__session {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 30px;
  max-width: 260px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-soft);
  font-size: var(--text-sm);
  font-weight: 560;
  text-decoration: none !important;
}
.topbar__session:hover { border-color: var(--border-strong); color: var(--text); }
.topbar__session-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.topbar__session-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--faint); flex: none; }
.topbar__session.is-live { border-color: color-mix(in srgb, var(--success) 35%, var(--border)); color: var(--success-soft-text); background: var(--success-soft); }
.topbar__session.is-live .topbar__session-dot { background: var(--success); box-shadow: 0 0 0 3px color-mix(in srgb, var(--success) 25%, transparent); animation: b-live 2s ease-in-out infinite; }
.topbar__session.is-done .topbar__session-dot { background: var(--info); }
.topbar__session.is-stopped .topbar__session-dot { background: var(--warning); }
.topbar__chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--muted);
  font-size: var(--text-sm);
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
}
.topbar__chip:hover { background: var(--surface-hover); color: var(--text); }
.topbar__chip.is-loaded { color: var(--success); }
.topbar__chip.is-stale { color: var(--warning-soft-text); background: var(--warning-soft); }
.topbar__chip.is-warn { color: var(--warning-soft-text); background: var(--warning-soft); cursor: default; }
.topbar__icon-btn {
  position: relative;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.topbar__icon-btn:hover, .topbar__icon-btn.is-on { background: var(--surface-hover); color: var(--text); }
.topbar__badge {
  position: absolute;
  top: 2px;
  right: 1px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--danger);
  color: var(--on-danger);
  font-size: 10.5px;
  font-weight: 700;
  line-height: 17px;
  text-align: center;
  border: 2px solid var(--bg);
  box-sizing: content-box;
}
@media (max-width: 1100px) {
  /* icon only; the words stay for screen readers (the connection chip is a status) */
  .topbar__chip-text {
    position: absolute;
    width: 1px;
    height: 1px;
    margin: -1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    white-space: nowrap;
  }
  .topbar__sub { display: none; }
}
@media (max-width: 900px) {
  .topbar { padding: 0 12px; }
  .topbar__menu { display: block; }
  .topbar__session { max-width: 150px; }
}
@media (max-width: 480px) {
  .topbar { gap: 8px; }
  .topbar__session { max-width: 108px; padding: 0 9px; }
  .topbar__right { gap: 2px; }
}
</style>
