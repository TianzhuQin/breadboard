<template>
  <div v-if="info" class="engine-notice" role="status">
    <Cpu :size="16" class="engine-notice__icon" aria-hidden="true" />
    <p class="engine-notice__text">
      <template v-if="live">
        The engine is running <strong>{{ engineName }}</strong> ({{ whose }}).
        You are working on <strong>{{ selectedName }}</strong>: its
        <RouterLink to="/steps">Build pages</RouterLink> and <RouterLink to="/sessions">data</RouterLink> are here;
        to run it, end that session first{{ autopilot ? ' (that pauses the autopilot)' : '' }}.
      </template>
      <template v-else>
        The engine has <strong>{{ engineName }}</strong> loaded, with no session running<template v-if="ownerText"> ({{ ownerText }})</template>.
        You are working on <strong>{{ selectedName }}</strong>: load it into the engine to run it here.
      </template>
    </p>
    <div class="engine-notice__actions">
      <BButton v-if="!live" size="sm" :icon="RotateCw" class="engine-notice__btn" @click="load">Load {{ selectedName }}</BButton>
      <BButton v-if="live && autopilot" size="sm" :icon="Bot" class="engine-notice__btn" @click="router.push('/autopilot')">Autopilot</BButton>
      <BButton v-if="target" size="sm" :variant="live ? 'secondary' : 'ghost'" class="engine-notice__btn" :loading="switching" @click="switchTo">
        Work on “{{ target.name }}”
      </BButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Bot, Cpu, RotateCw } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { admin, actions, engineElsewhere, ensureConnected, experiments } from '@/store/admin'
import { confirmEngineRebuild, confirmSwitch, isAutopilotSession, sessionWords } from '@/store/engineGuard'

/**
 * On the Run pages: the engine is shared, and it can be running another experiment than the one
 * selected here, whose session selecting left running (the autopilot's, another researcher's, or
 * this account's own), or which is just loaded. The server then refuses this experiment's Start,
 * Run, scripts and previews. Say what the engine runs and what that means here, with the ways on.
 */

const router = useRouter()
const info = engineElsewhere
const live = computed(() => info.value?.sessionId != null)
const engineName = computed(() => info.value?.experimentName ? `“${info.value.experimentName}”` : 'another experiment')
const selectedName = computed(() => admin.experiment ? `“${admin.experiment.name}”` : 'your experiment')
const ownerText = computed(() => {
  const owner = info.value?.owner
  if (!owner) return ''
  return owner.toLowerCase() === admin.email.toLowerCase() ? 'loaded for your account' : `loaded for ${owner}`
})
/** The engine's experiment, to switch to (it may have been deleted since). */
const target = computed(() => experiments.value.find(e => e.id === info.value?.experimentId) || null)

// Whether the live session is the autopilot's is asked of the server (ending it pauses the autopilot).
const autopilot = ref(false)
let asked = 0
watch(() => info.value?.sessionId ?? null, async (id) => {
  const mine = ++asked
  autopilot.value = false
  const its = await isAutopilotSession(id)
  if (mine === asked) autopilot.value = its
}, { immediate: true })
const whose = computed(() => info.value ? sessionWords(info.value, autopilot.value) : '')

const switching = ref(false)
/**
 * Work on the experiment the engine runs. While its session is live, selecting changes only what this
 * console works on (nothing is rebuilt, so nothing is asked); with none live it loads that experiment.
 */
async function switchTo () {
  const e = target.value
  if (!e || switching.value || !ensureConnected()) return
  if (!await confirmSwitch({ title: `Switch to “${e.name}”?`, action: 'Switching experiments', confirmLabel: 'Switch anyway' })) return
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

/** Load the selected experiment's steps into the engine, which runs no session now (a reload). */
async function load () {
  if (!ensureConnected()) return
  const name = admin.experiment?.name || 'your experiment'
  if (!await confirmEngineRebuild({ title: `Load “${name}” into the engine?`, action: 'Loading it', confirmLabel: 'Load anyway' })) return
  // (the connection may have dropped while the question was open)
  if (!actions.reloadEngine()) ensureConnected()
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
.engine-notice__text a { color: inherit; text-decoration: underline; text-underline-offset: 2px; }
.engine-notice__actions { display: flex; flex-wrap: wrap; gap: 6px; flex: none; max-width: 45%; justify-content: flex-end; }
.engine-notice__btn { max-width: 100%; }
.engine-notice__btn .b-btn__label { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
@media (max-width: 900px) {
  .engine-notice { padding: 9px 12px; flex-wrap: wrap; }
  .engine-notice__actions { max-width: 100%; margin-left: 28px; justify-content: flex-start; }
}
</style>
