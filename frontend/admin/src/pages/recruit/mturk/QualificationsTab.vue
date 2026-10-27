<template>
  <div class="rc-stack">
    <p class="rc-muted qt__intro">
      Each experiment can have a <strong class="rc-strong">participant qualification</strong> on MTurk: a private
      tag you give workers who took part. New HITs can then require it, or keep its holders out
      (Create HIT → Requirements → “Took part in an experiment”).
    </p>

    <ErrorNote v-if="error" title="Could not load the qualifications" :message="error.message" :detail="error.detail" :on-retry="load" />

    <div v-if="loading" class="rc-table-wrap" role="status">
      <div class="rc-loading"><BSpinner :size="16" /> Looking up each experiment’s qualification on MTurk… with many experiments this takes a moment.</div>
    </div>

    <div v-else-if="!error" class="qt__layout">
      <section class="qt__list" aria-label="Experiments">
        <div class="qt__list-head">
          <BSwitch v-model="onlyMine">Only my experiments</BSwitch>
          <BButton size="sm" variant="ghost" :icon="RefreshCw" @click="load">Refresh</BButton>
        </div>
        <div v-if="!visible.length" class="qt__none">
          <p class="rc-muted">{{ onlyMine && rows.length ? 'None of your experiments are in the list.' : 'There are no experiments on the server.' }}</p>
          <BButton v-if="onlyMine && rows.length" size="sm" variant="ghost" @click="onlyMine = false">Show every experiment on the server</BButton>
        </div>
        <ul v-else class="qt__items">
          <li v-for="r in visible" :key="r.experimentUid">
            <button
              type="button"
              class="qt__item"
              :class="{ 'is-on': selectedUid === r.experimentUid }"
              :aria-pressed="selectedUid === r.experimentUid ? 'true' : 'false'"
              @click="selectedUid = r.experimentUid"
            >
              <span class="qt__item-main">
                <span class="qt__item-name">{{ r.experimentName }}</span>
                <span class="rc-sub rc-mono">{{ r.experimentUid }}</span>
              </span>
              <BBadge v-if="r.qualificationTypeId" tone="success">Has one</BBadge>
              <BBadge v-else tone="neutral">None</BBadge>
            </button>
          </li>
        </ul>
      </section>

      <section class="qt__detail" aria-live="polite">
        <BEmpty v-if="!selected" compact :icon="Award" title="Choose an experiment" text="Pick one on the left to see who holds its qualification." />
        <template v-else>
          <header class="qt__head">
            <h3 class="qt__name">{{ selected.experimentName }}</h3>
            <dl class="rc-kv">
              <dt>Experiment UID</dt>
              <dd class="qt__copy"><span class="rc-mono">{{ selected.experimentUid }}</span><CopyButton :text="selected.experimentUid" icon-only size="sm" variant="ghost" aria-label="Copy the experiment UID" /></dd>
              <template v-if="selected.qualificationTypeId">
                <dt>Qualification ID</dt>
                <dd class="qt__copy"><span class="rc-mono">{{ selected.qualificationTypeId }}</span><CopyButton :text="selected.qualificationTypeId" icon-only size="sm" variant="ghost" aria-label="Copy the qualification ID" /></dd>
                <dt>Status</dt>
                <dd>{{ selected.qualificationTypeStatus || '—' }}</dd>
                <dt>Created</dt>
                <dd>{{ formatTime(selected.creationTime) }}</dd>
              </template>
            </dl>
          </header>

          <div v-if="!selected.qualificationTypeId" class="qt__missing">
            <p class="rc-muted">This experiment has no participant qualification on the {{ api.sandbox ? 'sandbox' : 'production' }} site yet. Create one to start tagging the workers who take part.</p>
            <BButton variant="primary" :icon="Plus" :loading="creating" @click="createType">Create qualification</BButton>
          </div>
          <QualificationWorkers v-else :key="selected.qualificationTypeId" :api="api" :type-id="selected.qualificationTypeId" :experiment-name="selected.experimentName" />
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Award, Plus, RefreshCw } from 'lucide-vue-next'
import { BBadge, BButton, BEmpty, BSpinner, BSwitch, notify } from '@/ui'
import { admin, experiments } from '@/store/admin'
import CopyButton from '../CopyButton.vue'
import ErrorNote from '../ErrorNote.vue'
import QualificationWorkers from './QualificationWorkers.vue'
import { forgetQualificationType, type MturkApi, type QualificationTypeRow } from './api'
import { describeError, errorLine, formatTime, type ErrorText } from './lib'

const props = defineProps<{ api: MturkApi }>()

const rows = ref<QualificationTypeRow[]>([])
const loading = ref(false)
const error = ref<ErrorText | null>(null)
const onlyMine = ref(true)
const selectedUid = ref<string | null>(null)
const creating = ref(false)

// The server lists every experiment in its database, including other researchers'.
const mine = computed(() => new Set(experiments.value.map(e => e.uid)))
const visible = computed(() => {
  const list = onlyMine.value ? rows.value.filter(r => mine.value.has(r.experimentUid)) : rows.value
  return [...list].sort((a, b) => (a.experimentName || '').localeCompare(b.experimentName || '', undefined, { numeric: true, sensitivity: 'base' }))
})
const selected = computed(() => rows.value.find(r => r.experimentUid === selectedUid.value) || null)

async function load () {
  loading.value = true
  error.value = null
  try {
    const res = await props.api.qualificationTypes()
    rows.value = res.qualificationTypes || []
    if (!selected.value) {
      const current = admin.experiment?.uid
      selectedUid.value = rows.value.some(r => r.experimentUid === current) ? current! : visible.value[0]?.experimentUid ?? null
    }
  } catch (e) {
    error.value = describeError(e)
  } finally {
    loading.value = false
  }
}

async function createType () {
  const row = selected.value
  if (!row || creating.value) return
  creating.value = true
  try {
    const created = await props.api.addQualificationType(row.experimentUid)
    const i = rows.value.findIndex(r => r.experimentUid === row.experimentUid)
    if (i >= 0) rows.value[i] = { ...row, ...created, experimentName: created.experimentName || row.experimentName }
    forgetQualificationType(props.api.mode, row.experimentUid)
    notify.success('Qualification created', row.experimentName)
  } catch (e) {
    notify.error('Could not create the qualification', errorLine(e))
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<style>
.qt__intro { font-size: var(--text-sm); }
.qt__layout { display: grid; grid-template-columns: minmax(0, 340px) minmax(0, 1fr); gap: 16px; align-items: start; }
@media (max-width: 960px) { .qt__layout { grid-template-columns: minmax(0, 1fr); } }
.qt__list { border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); overflow: hidden; }
.qt__list-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--border); background: var(--surface-sunken); }
.qt__items { list-style: none; margin: 0; padding: 4px; max-height: 560px; overflow: auto; }
.qt__item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 0;
  border-radius: var(--radius-xs);
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: var(--text);
}
.qt__item:hover { background: var(--surface-hover); }
.qt__item.is-on { background: var(--accent-soft); }
.qt__item-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.qt__item-name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qt__item-main .rc-sub { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qt__none { padding: 14px; font-size: var(--text-sm); display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.qt__detail { display: flex; flex-direction: column; gap: 16px; padding: 18px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); min-width: 0; }
.qt__head { display: flex; flex-direction: column; gap: 10px; }
.qt__name { font-size: var(--text-lg); }
.qt__copy { display: flex; align-items: center; gap: 4px; }
.qt__missing { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }
</style>
