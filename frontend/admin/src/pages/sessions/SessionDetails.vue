<template>
  <div class="sd">
    <div class="sd__grid">
      <section class="sd__section" :aria-labelledby="`${base}-params`">
        <h4 :id="`${base}-params`" class="sd__h">Launch parameters</h4>
        <div v-if="params.length" class="sd__ptable-wrap">
          <table class="sd__ptable">
            <thead>
              <tr><th scope="col">Parameter</th><th scope="col">Value</th></tr>
            </thead>
            <tbody>
              <tr v-for="d in params" :key="d.id ?? d.name">
                <th scope="row">{{ d.name }}</th>
                <td>{{ d.value === '' ? '“”' : d.value }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="sd__quiet">This session was started without launch parameters.</p>
        <p class="sd__fine">Set when the session was created; they can’t be changed afterwards.</p>
      </section>

      <section class="sd__section" :aria-labelledby="`${base}-about`">
        <h4 :id="`${base}-about`" class="sd__h">About this session</h4>
        <dl class="sd__dl">
          <div class="sd__dl-row">
            <dt>Status</dt>
            <dd>
              <BBadge :tone="meta.tone" dot :pulse="meta.pulse">{{ meta.label }}</BBadge>
              <span v-if="meta.hint" class="sd__quiet">{{ meta.hint }}</span>
            </dd>
          </div>
          <div class="sd__dl-row">
            <dt>Created</dt>
            <dd :title="`As the server recorded it: ${session.creationTime}`">{{ formatDateTime(created, { seconds: true }) }}</dd>
          </div>
          <div class="sd__dl-row">
            <dt>Session ID</dt>
            <dd class="mono">{{ session.id }}</dd>
          </div>
          <div class="sd__dl-row">
            <dt>Participant link</dt>
            <dd v-if="session.status === 'RUNNING'" class="sd__link-dd">
              <div class="sd__link">
                <a :href="link" target="_blank" rel="noopener" class="sd__link-text mono" :title="link">{{ link }}</a>
                <BButton size="sm" variant="ghost" :icon="copied ? Check : Copy" :aria-label="copied ? 'Copied' : 'Copy the participant link'" title="Copy link" @click="copy" />
              </div>
              <p v-if="localOnly" class="sd__warn">
                <TriangleAlert :size="14" aria-hidden="true" />
                This link names this computer (“{{ hostname }}”), so it only works here. Participants elsewhere need your server’s public address.
              </p>
            </dd>
            <dd v-else class="sd__quiet">Works only while the session is running.</dd>
          </div>
        </dl>
      </section>
    </div>

    <div class="sd__actions">
      <BButton
        v-if="current && session.status === 'RUNNING'"
        variant="primary"
        size="sm"
        :icon="Activity"
        @click="router.push('/session')"
      >
        Open live session
      </BButton>
      <BButton
        v-else-if="canResume(session)"
        variant="primary"
        size="sm"
        :icon="Play"
        :loading="busy.resume"
        :disabled="locked"
        title="Restarts the engine with this session (SelectInstance)"
        @click="emit('resume')"
      >
        {{ session.status === 'STOPPED' ? 'Resume session' : 'Make current' }}
      </BButton>
      <BButton v-if="canStop(session)" size="sm" :icon="Square" :loading="busy.stop" :disabled="locked" @click="emit('stop')">Stop</BButton>
      <BButton size="sm" :icon="ScrollText" @click="emit('events')">View events</BButton>
      <BButton size="sm" :icon="Download" :loading="busy.data" @click="emit('download')">Download data (CSV)</BButton>

      <span class="sd__spacer" />
      <span v-if="resumeHint" class="sd__quiet sd__note">{{ resumeHint }}</span>
      <span v-if="deleteHint" :id="`${base}-nodelete`" class="sd__quiet sd__note">{{ deleteHint }}</span>
      <BButton
        size="sm"
        variant="danger-ghost"
        :icon="Trash2"
        :loading="busy.delete"
        :disabled="!canDelete(session) || locked"
        :aria-describedby="deleteHint ? `${base}-nodelete` : undefined"
        @click="emit('delete')"
      >
        Delete…
      </BButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, Check, Copy, Download, Play, ScrollText, Square, Trash2, TriangleAlert } from 'lucide-vue-next'
import { BBadge, BButton, notify } from '@/ui'
import type { Instance } from '@/store/types'
import { canDelete, canResume, canStop, formatDateTime, isLocalHost, parseServerTime, participantLink, statusMeta } from './sessionData'

const props = defineProps<{
  session: Instance
  experimentId: number
  current: boolean
  /** Another lifecycle action is in progress. */
  locked: boolean
  busy: { resume: boolean, stop: boolean, delete: boolean, data: boolean }
}>()
const emit = defineEmits<{ resume: [], stop: [], delete: [], events: [], download: [] }>()
const router = useRouter()

const base = `sd-${props.session.id}`
const meta = computed(() => statusMeta(props.session.status))
const created = computed(() => parseServerTime(props.session.creationTime))
const params = computed(() => [...(props.session.data || [])].sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true })))
const link = computed(() => participantLink(props.experimentId, props.session.id))
const hostname = window.location.hostname
const localOnly = isLocalHost(hostname)

const resumeHint = computed(() => {
  if (canResume(props.session) || canStop(props.session)) return ''
  return props.session.status === 'FINISHED'
    ? 'A finished session can’t be resumed. Start a new session to run the experiment again.'
    : 'This session can’t run again.'
})
const deleteHint = computed(() => {
  if (canDelete(props.session)) return ''
  return canStop(props.session) ? 'Stop it before deleting it.' : 'Only stopped or finished sessions can be deleted.'
})

const copied = ref(false)
async function copy () {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(link.value)
    } else {
      // older browsers, or pages not served over https
      const ta = document.createElement('textarea')
      ta.value = link.value
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      if (!ok) throw new Error('The browser did not allow copying.')
    }
    copied.value = true
    notify.success('Participant link copied')
    setTimeout(() => { copied.value = false }, 2000)
  } catch (e: any) {
    notify.error('Could not copy the link', `${e?.message || e}. Select the link and copy it by hand.`)
  }
}
</script>

<style>
.sd { padding: 16px 20px 18px 52px; display: flex; flex-direction: column; gap: 16px; }
.sd__grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px 32px; }
.sd__section { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.sd__h { font-size: var(--text-xs); font-weight: 650; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted); }
.sd__quiet { color: var(--muted); font-size: var(--text-sm); }
.sd__fine { color: var(--faint); font-size: var(--text-xs); }
.sd__ptable-wrap { max-height: 260px; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--surface); }
.sd__ptable { width: 100%; border-collapse: collapse; font-size: var(--text-sm); }
.sd__ptable th, .sd__ptable td { padding: 6px 12px; text-align: left; border-bottom: 1px solid var(--border); vertical-align: top; }
.sd__ptable tbody tr:last-child th, .sd__ptable tbody tr:last-child td { border-bottom: 0; }
.sd__ptable thead th { position: sticky; top: 0; background: var(--surface-sunken); font-size: var(--text-xs); font-weight: 600; color: var(--muted); }
.sd__ptable tbody th { font-family: var(--font-mono); font-size: 12px; font-weight: 550; color: var(--text-soft); white-space: nowrap; }
.sd__ptable tbody td { font-family: var(--font-mono); font-size: 12px; overflow-wrap: anywhere; }
.sd__dl { margin: 0; display: flex; flex-direction: column; gap: 8px; }
.sd__dl-row { display: grid; grid-template-columns: 120px minmax(0, 1fr); gap: 12px; align-items: baseline; }
.sd__dl dt { font-size: var(--text-sm); color: var(--muted); }
.sd__dl dd { margin: 0; min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; font-size: var(--text-sm); }
.sd__link-dd { flex-direction: column; align-items: stretch !important; }
.sd__link { display: flex; align-items: center; gap: 4px; min-width: 0; }
.sd__link-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.sd__warn { display: flex; gap: 6px; align-items: flex-start; font-size: var(--text-xs); color: var(--warning-soft-text); line-height: 1.45; }
.sd__warn svg { flex: none; margin-top: 1px; }
.sd__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-top: 14px; border-top: 1px solid var(--border); }
.sd__spacer { flex: 1; }
.sd__note { font-size: var(--text-xs); }
@media (max-width: 760px) {
  .sd { padding: 14px 16px 16px; }
  .sd__grid { grid-template-columns: minmax(0, 1fr); }
  .sd__dl-row { grid-template-columns: minmax(0, 1fr); gap: 2px; }
  .sd__spacer { display: none; }
}
</style>
