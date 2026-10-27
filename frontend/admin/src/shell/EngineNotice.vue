<template>
  <div v-if="info" class="engine-notice" role="status">
    <Cpu :size="16" class="engine-notice__icon" aria-hidden="true" />
    <p class="engine-notice__text">
      <template v-if="info.sessionName">
        The engine is running <strong>{{ experimentName }}</strong>’s session <strong>{{ info.sessionName }}</strong>
      </template>
      <template v-else>
        The engine has <strong>{{ experimentName }}</strong> loaded, with no session running
      </template>
      <template v-if="ownerText"> ({{ ownerText }})</template>.
      <template v-if="target && endsSession">
        Live controls here act on nothing. Opening {{ target.name }} here stops that session for everyone in it.
      </template>
      <template v-else>
        {{ target ? 'Live controls here act on nothing until you switch.' : 'Live controls here act on nothing for now.' }}
      </template>
    </p>
    <BButton v-if="target" size="sm" class="engine-notice__btn" :loading="switching" @click="switchTo">
      {{ endsSession ? `End that session and open ${target.name}` : `Switch to ${target.name}` }}
    </BButton>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Cpu } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { admin, actions, engineElsewhere, ensureConnected, experiments } from '@/store/admin'
import { confirmEngineRebuild } from '@/store/engineGuard'

/**
 * The engine is shared, and it can be running another experiment than the selected one (another
 * account's session, the autopilot's, or one left loaded). The server then refuses Start, Run and
 * the other live controls, so say what it runs, and offer to switch when that is this account's.
 */

const info = engineElsewhere
const experimentName = computed(() => info.value?.experimentName || 'another experiment')
const ownerText = computed(() => {
  const owner = info.value?.owner
  if (!owner) return ''
  return owner.toLowerCase() === admin.email.toLowerCase() ? 'your account' : owner
})
/** The engine's experiment, when it is one of this account's (another account's can't be selected). */
const target = computed(() => experiments.value.find(e => e.id === info.value?.experimentId) || null)
/**
 * Whether switching to it ends the session the engine runs. Selecting an experiment rebuilds the
 * engine and stops its session (ScriptBoard's ChangeExperiment), unless that session is this
 * account's own selected running session of it, which is taken up again.
 */
const endsSession = computed(() => {
  const e = info.value
  if (!e || (e.sessionId === null && !e.sessionName)) return false
  const own = admin.instance
  return !(own && e.sessionId !== null && own.id === e.sessionId && own.status === 'RUNNING')
})

const switching = ref(false)
async function switchTo () {
  const e = target.value
  if (!e || switching.value || !ensureConnected()) return
  // Selecting an experiment rebuilds the engine every time, also for the experiment it already runs
  // (ScriptBoard's ChangeExperiment), which ends the session it runs: ask as the switcher does, and
  // say so plainly when there is one.
  const session = info.value?.sessionName || (info.value?.sessionId != null ? String(info.value.sessionId) : '')
  const ok = endsSession.value
    ? await confirmEngineRebuild({
      title: `End “${session}” and open “${e.name}”?`,
      action: `Opening “${e.name}” here`,
      detail: `Session “${session}” is stopped: the people in it can’t rejoin it, and its links stop working.`,
      confirmLabel: 'End it and open',
    })
    : await confirmEngineRebuild({ title: `Switch to “${e.name}”?`, action: 'Switching experiments', confirmLabel: 'Switch anyway' })
  if (!ok) return
  switching.value = true
  try {
    await actions.selectExperiment(e.id)
  } finally {
    switching.value = false
  }
  if (admin.experiment?.id !== e.id) {
    notify.error(`Could not switch to ${e.name}`, admin.requestError?.request === 'SelectExperiment' ? admin.requestError.text : 'The server did not confirm the switch. The output panel may say why.')
  }
}
</script>

<style>
.engine-notice {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 20px;
  border-bottom: 1px solid var(--border);
  background: var(--info-soft);
  color: var(--info-soft-text);
  font-size: var(--text-sm);
}
.engine-notice__icon { flex: none; }
.engine-notice__text { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.engine-notice__text strong { font-weight: 650; }
.engine-notice__btn { flex: none; max-width: 40%; }
.engine-notice__btn .b-btn__label { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
@media (max-width: 900px) {
  .engine-notice { padding: 9px 12px; flex-wrap: wrap; }
  .engine-notice__btn { max-width: 100%; margin-left: 28px; }
}
</style>
