<template>
  <!-- Copies of the database the server makes by itself (models.Backups). -->
  <section class="set" aria-labelledby="set-backups">
    <div class="set__about">
      <h2 id="set-backups" class="set__title">Backups</h2>
      <p class="set__desc">Copies of everything: experiments, sessions, data and accounts.</p>
    </div>
    <div class="set__body">
      <div class="set-card set-backups">
        <p v-if="loadError" class="set-backups__problem" role="alert">
          {{ loadError }}
          <BButton size="sm" variant="ghost" @click="load">Try again</BButton>
        </p>
        <template v-else-if="status">
          <p v-if="!status.enabled" class="set-backups__off">
            This server doesn't back up its database by itself (it is switched off with
            breadboard.backup.enabled, or the database isn't a file).
          </p>
          <template v-else>
            <div class="set-backups__head">
              <span class="set-backups__icon" :class="{ 'is-problem': !!status.problem }" aria-hidden="true">
                <DatabaseBackup :size="18" />
              </span>
              <p class="set-backups__summary">
                <strong>{{ latestText }}</strong>
                <span>
                  A new one every {{ status.everyHours === 24 ? 'day' : `${status.everyHours} hours` }}; the newest
                  {{ status.keep }} of each kind are kept: these, those made by hand, and those made before an upgrade.
                </span>
              </p>
              <BButton :icon="Save" :loading="busy" @click="backUpNow">Back up now</BButton>
            </div>
            <p v-if="status.problem" class="set-backups__problem" role="alert">{{ status.problem }}</p>
            <ul v-if="status.backups.length" class="set-backups__list" aria-label="Backups, newest first">
              <li v-for="b in shown" :key="b.name" class="set-backups__row">
                <span class="set-backups__when">{{ when(b) }}</span>
                <span v-if="kind(b)" class="set-backups__kind">{{ kind(b) }}</span>
                <span class="set-backups__size">{{ fileSize(b.size) }}</span>
                <BButton size="sm" variant="ghost" :icon="Download" :href="backups.url(b.name)" :aria-label="`Download the backup of ${when(b)}`">
                  Download
                </BButton>
              </li>
            </ul>
            <p class="set__note">
              Kept in <span class="mono">{{ status.folder }}</span> on the server. To go back to one: stop
              Breadboard, unzip it into the <span class="mono">db</span> folder (replacing the file there), and
              start Breadboard again. Copy them to another disk now and then.
            </p>
          </template>
        </template>
        <p v-else class="set-backups__off">Loading…</p>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { DatabaseBackup, Download, Save } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'
import { describeError } from '../experiments/experiments'
import { formatDateTime, parseServerTime } from '../sessions/sessionData'
import { backups, fileSize, type Backup, type BackupStatus } from './settings'

const status = ref<BackupStatus | null>(null)
const loadError = ref('')
const busy = ref(false)

async function load () {
  loadError.value = ''
  try {
    status.value = await backups.status()
  } catch (e) {
    loadError.value = `Couldn't read the backups. ${describeError(e)}`
  }
}
load()

const shown = computed(() => (status.value?.backups || []).slice(0, 5))
const when = (b: Backup) => formatDateTime(parseServerTime(b.createdAt)) || b.name
function kind (b: Backup) {
  if (/-manual\.zip$/.test(b.name)) return 'Made by hand'
  if (/-upgrade\.zip$/.test(b.name)) return 'Before an upgrade'
  return ''
}
const latestText = computed(() => {
  const latest = status.value?.backups[0]
  return latest ? `Last backup: ${when(latest)}` : 'No backup yet'
})

async function backUpNow () {
  if (busy.value) return
  busy.value = true
  try {
    status.value = await backups.backUpNow()
    notify.success('Backed up', 'The newest backup is at the top of the list.')
  } catch (e) {
    notify.error('The backup failed', describeError(e))
    load()
  } finally {
    busy.value = false
  }
}
</script>

<style>
.set-backups { display: flex; flex-direction: column; gap: 12px; }
.set-backups__head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.set-backups__icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--success-soft);
  color: var(--success-soft-text);
  flex: none;
}
.set-backups__icon.is-problem { background: var(--danger-soft); color: var(--danger-soft-text); }
.set-backups__summary { flex: 1; min-width: 200px; display: flex; flex-direction: column; font-size: var(--text-sm); color: var(--muted); }
.set-backups__summary strong { color: var(--text); font-size: var(--text-md); font-weight: 600; }
.set-backups__problem { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.set-backups__off { font-size: var(--text-sm); color: var(--muted); }
.set-backups__list { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--border); }
.set-backups__row { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--border); font-size: var(--text-sm); flex-wrap: wrap; }
.set-backups__when { flex: 1; min-width: 150px; font-variant-numeric: tabular-nums; }
.set-backups__kind { font-size: var(--text-xs); color: var(--muted); }
.set-backups__size { color: var(--muted); font-variant-numeric: tabular-nums; min-width: 64px; text-align: right; }
</style>
