<template>
  <BDialog
    :open="open"
    :title="turningOn ? 'Edit this experiment as files?' : 'Go back to editing in the console?'"
    :subtitle="exp ? exp.name : undefined"
    :dismissable="!busy"
    :closable="!busy"
    @update:open="v => !v && close()"
  >
    <div v-if="exp" class="xfm">
      <p class="xfm__lead">
        <template v-if="turningOn">
          In file mode Breadboard reads this experiment from files on the server instead of its database, so you can
          edit it with your own code editor or keep it in version control.
        </template>
        <template v-else>
          Breadboard reads the files back into its database, and the experiment can be edited in this console again.
        </template>
      </p>

      <div class="xfm__path">
        <FolderOpen :size="16" aria-hidden="true" />
        <span>Folder on the server</span>
        <code>dev/{{ dir }}/</code>
      </div>

      <ul class="xfm__list">
        <template v-if="turningOn">
          <li>
            <Download :size="15" aria-hidden="true" />
            <span>Steps, content, parameters, images, the participant page and its style are written to that folder.</span>
          </li>
          <li class="is-warn">
            <TriangleAlert :size="15" aria-hidden="true" />
            <span><strong>If the folder already exists, it is deleted first.</strong> Anything you changed there before is lost.</span>
          </li>
          <li>
            <Lock :size="15" aria-hidden="true" />
            <span>Steps, Content, Parameters, Images and the participant page become read-only in this console.</span>
          </li>
          <li>
            <Zap :size="15" aria-hidden="true" />
            <span>When a file in <code>Steps/</code> is saved on the server, it runs at once and the console updates.</span>
          </li>
        </template>
        <template v-else>
          <li class="is-warn">
            <TriangleAlert :size="15" aria-hidden="true" />
            <span><strong>The experiment's steps, content, parameters and images in the database are replaced</strong> by what is in the folder now.</span>
          </li>
          <li>
            <FolderOpen :size="15" aria-hidden="true" />
            <span>The folder stays on the server as it is.</span>
          </li>
          <li>
            <Pencil :size="15" aria-hidden="true" />
            <span>Editing in this console works again.</span>
          </li>
        </template>
      </ul>

      <p v-if="blocked" class="xfm__blocked" role="alert">{{ blocked }}</p>
      <p v-if="error" class="xfm__error" role="alert">{{ error }}</p>
    </div>

    <template #footer>
      <BButton variant="ghost" :disabled="busy" @click="close">Cancel</BButton>
      <BButton
        :variant="turningOn ? 'primary' : 'danger'"
        :loading="busy"
        :disabled="!!blocked"
        :icon="turningOn ? HardDrive : Database"
        @click="apply"
      >
        {{ turningOn ? 'Switch to file mode' : 'Import the files and switch' }}
      </BButton>
    </template>
  </BDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Database, Download, FolderOpen, HardDrive, Lock, Pencil, TriangleAlert, Zap } from 'lucide-vue-next'
import { BButton, BDialog, notify } from '@/ui'
import { admin, actions, running } from '@/store/admin'
import { devDirName } from './experiments'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [v: boolean] }>()

// The server toggles the selected experiment only (ToggleFileMode has no id).
const exp = computed(() => admin.experiment)
const turningOn = ref(true)
const busy = ref(false)
const error = ref('')
const dir = computed(() => exp.value ? devDirName(exp.value) : '')
const blocked = computed(() => running.value
  ? 'A session is running. Stop it first, so its participants keep the steps and content they started with.'
  : '')

// Freeze the direction when the dialog opens, so the push that follows doesn't flip the wording.
watch(() => props.open, (open) => {
  if (!open) return
  turningOn.value = !exp.value?.fileMode
  error.value = ''
  busy.value = false
}, { immediate: true })

function close () {
  if (!busy.value) emit('update:open', false)
}

async function apply () {
  const e = exp.value
  if (!e || blocked.value) return
  const wanted = turningOn.value
  const stepsBefore = e.steps?.length || 0
  busy.value = true
  error.value = ''
  try {
    await actions.toggleFileMode()
    const now = admin.experiment
    // The server only logs a failure to read or write the folder and leaves the mode as it was.
    if (!now || now.id !== e.id || !!now.fileMode !== wanted) {
      error.value = wanted
        ? `The server couldn't write to dev/${dir.value}/. Check that it may write to its dev folder; the server log has details.`
        : `The server couldn't read dev/${dir.value}/. Check that the folder is still there and readable; the server log has details.`
      busy.value = false
      return
    }
    busy.value = false
    emit('update:open', false)
    if (wanted) {
      notify.success('File mode is on', `Edit the files in dev/${dir.value}/ on the server.`)
      if (stepsBefore > 0 && !(now.steps?.length)) {
        // Known server issue: the export writes "Steps" but file mode reads "steps", which differ on Linux.
        notify.warning('No steps were found in file mode',
          'The server may be looking in a “steps” folder while it wrote “Steps”. Rename the folder to lower case on the server, or switch back to the database.')
      }
    } else {
      notify.success('Back to the database', 'You can edit the experiment in the console again.')
    }
  } catch (err: any) {
    error.value = err?.message || 'Something went wrong.'
    busy.value = false
  }
}
</script>

<style>
.xfm { display: flex; flex-direction: column; gap: 16px; }
.xfm__lead { color: var(--text-soft); }
.xfm__path {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface-sunken);
  font-size: var(--text-sm);
  color: var(--muted);
}
.xfm__path code { color: var(--text); font-weight: 550; word-break: break-all; }
.xfm__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.xfm__list li { display: flex; gap: 10px; align-items: flex-start; font-size: var(--text-sm); color: var(--text-soft); line-height: 1.5; }
.xfm__list li svg { flex: none; margin-top: 2px; color: var(--muted); }
.xfm__list li.is-warn { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--warning-soft); color: var(--warning-soft-text); }
.xfm__list li.is-warn svg { color: inherit; }
.xfm__list code { font-size: 0.95em; }
.xfm__blocked, .xfm__error { padding: 10px 12px; border-radius: var(--radius-sm); font-size: var(--text-sm); }
.xfm__blocked { background: var(--warning-soft); color: var(--warning-soft-text); }
.xfm__error { background: var(--danger-soft); color: var(--danger-soft-text); }
</style>
