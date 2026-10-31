<template>
  <!-- The server's researcher accounts. Everyone signed in works on every experiment, so this is a
       list of people, not of permissions. -->
  <section class="set" aria-labelledby="set-researchers">
    <div class="set__about">
      <h2 id="set-researchers" class="set__title">Researchers</h2>
      <p class="set__desc">Everyone here can open, change and run every experiment on this server.</p>
    </div>
    <div class="set__body">
      <div class="set-card set-people">
        <p v-if="loadError" class="set-people__error" role="alert">
          {{ loadError }}
          <BButton size="sm" variant="ghost" @click="load">Try again</BButton>
        </p>
        <p v-else-if="loading && !list.length" class="set-people__loading">Loading the accounts…</p>
        <ul v-else class="set-people__list" aria-label="Researcher accounts">
          <li v-for="r in list" :key="r.email" class="set-people__row">
            <span class="set-people__avatar" aria-hidden="true">{{ r.email.charAt(0).toUpperCase() }}</span>
            <span class="set-people__who">
              <span class="set-people__email">{{ r.email }}</span>
              <span v-if="r.name && r.name !== r.email" class="set-people__name">{{ r.name }}</span>
            </span>
            <BBadge v-if="r.you" tone="accent">You</BBadge>
            <span v-else class="set-people__actions">
              <BButton size="sm" variant="ghost" :icon="KeyRound" :loading="busy === `reset:${r.email}`" :disabled="!!busy" @click="reset(r)">
                New password
              </BButton>
              <BButton size="sm" variant="danger-ghost" :icon="UserMinus" :loading="busy === `remove:${r.email}`" :disabled="!!busy" @click="remove(r)">
                Remove
              </BButton>
            </span>
          </li>
        </ul>

        <!-- a password made by the server, shown once -->
        <div v-if="handout" class="set-people__handout" role="status">
          <p class="set-people__handout-title">
            {{ handout.kind === 'added' ? `Account added for ${handout.email}.` : `New password for ${handout.email}.` }}
          </p>
          <p v-if="lanAddress" class="set-people__handout-text">
            Give them this password, and the server's address on this network,
            <b class="set-people__addr">{{ signInAddress }}</b> (this page's own address, localhost, works on this
            computer only; the copied details have the network's). They can change the password in Settings
            once they have signed in. It is shown only once.
          </p>
          <p v-else class="set-people__handout-text">
            Give them this password, and the address of this page. They can change it in Settings once
            they have signed in. It is shown only once.
          </p>
          <!-- at localhost, with no address on a network known: the copied address works here only -->
          <LocalOnlyNote v-if="!lanAddress" class="set-people__local" what="The copied details use" compact />
          <div class="set-people__secret">
            <code class="set-people__code">{{ handout.password }}</code>
            <CopyButton :text="handoutText" label="Copy sign-in details" size="sm" />
            <BButton size="sm" variant="ghost" @click="handout = null">Done</BButton>
          </div>
        </div>

        <form class="set-people__add" novalidate @submit.prevent="add">
          <BField label="Add a researcher" :error="addError" hint="Their email address. They sign in with it here.">
            <template #default="{ id, describedby }">
              <div class="set-people__add-row">
                <BInput
                  :id="id"
                  v-model="newEmail"
                  type="email"
                  autocomplete="off"
                  placeholder="name@university.edu"
                  :aria-describedby="describedby"
                  :aria-invalid="addError ? 'true' : undefined"
                />
                <BButton type="submit" variant="primary" :icon="UserPlus" :loading="busy === 'add'" :disabled="!!busy && busy !== 'add'">Add</BButton>
              </div>
            </template>
          </BField>
        </form>
      </div>
      <p class="set__note">
        To keep studies apart from another lab, run a separate Breadboard server for them.
      </p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { KeyRound, UserMinus, UserPlus } from 'lucide-vue-next'
import { BBadge, BButton, BField, BInput, confirm, notify } from '@/ui'
import CopyButton from '../recruit/CopyButton.vue'
import LocalOnlyNote from '../session/LocalOnlyNote.vue'
import { isLocalOrigin } from '../session/live'
import { admin } from '@/store/admin'
import { describeError } from '../experiments/experiments'
import { looksLikeEmail, researchers, type Researcher } from './settings'

const list = ref<Researcher[]>([])
const loading = ref(false)
const loadError = ref('')
/** what is being done: 'add', 'reset:<email>' or 'remove:<email>' */
const busy = ref('')
const newEmail = ref('')
const addError = ref('')
const handout = ref<null | { kind: 'added' | 'reset', email: string, password: string }>(null)

// Opened at localhost, this page's address only works on this computer: a colleague reaches the
// server at its address on this network, which the server says (GET /state), as LocalOnlyNote finds it.
const local = isLocalOrigin(window.location.origin)
const lanAddress = computed(() => (local && admin.lanAddresses?.length ? admin.lanAddresses[0].replace(/\/+$/, '') : ''))
const signInAddress = computed(() => `${lanAddress.value || window.location.origin}/`)
const handoutText = computed(() => handout.value
  ? `Breadboard: ${signInAddress.value}\nEmail: ${handout.value.email}\nPassword: ${handout.value.password}`
  : '')

watch(newEmail, () => { addError.value = '' })

async function load () {
  loading.value = true
  loadError.value = ''
  try {
    list.value = await researchers.list()
  } catch (e) {
    loadError.value = `Couldn't load the accounts. ${describeError(e)}`
  } finally {
    loading.value = false
  }
}
load()

async function add () {
  if (busy.value) return
  const email = newEmail.value.trim()
  if (!looksLikeEmail(email)) {
    addError.value = 'Enter their email address, like name@university.edu.'
    return
  }
  if (list.value.some(r => r.email.toLowerCase() === email.toLowerCase())) {
    addError.value = `${email} already has an account.`
    return
  }
  busy.value = 'add'
  try {
    const res = await researchers.add(email)
    list.value = res.list
    handout.value = { kind: 'added', email: res.email, password: res.password }
    newEmail.value = ''
  } catch (e) {
    addError.value = describeError(e)
  } finally {
    busy.value = ''
  }
}

async function reset (r: Researcher) {
  if (busy.value) return
  const ok = await confirm({
    title: `Give ${r.email} a new password?`,
    message: 'Their current password stops working, and every browser they are signed in on is signed out. You\'ll see the new password once, to pass on.',
    confirmLabel: 'Make a new password',
  })
  if (!ok) return
  busy.value = `reset:${r.email}`
  try {
    const res = await researchers.resetPassword(r.email)
    handout.value = { kind: 'reset', email: res.email, password: res.password }
  } catch (e) {
    notify.error('The password was not changed', describeError(e))
  } finally {
    busy.value = ''
  }
}

async function remove (r: Researcher) {
  if (busy.value) return
  const ok = await confirm({
    title: `Remove ${r.email}?`,
    message: 'They are signed out and can\'t sign in again. Experiments, sessions and data stay: they belong to everyone here.',
    confirmLabel: 'Remove account',
    danger: true,
  })
  if (!ok) return
  busy.value = `remove:${r.email}`
  try {
    list.value = await researchers.remove(r.email)
    if (handout.value?.email === r.email) handout.value = null
    notify.success('Account removed', r.email)
  } catch (e) {
    notify.error('The account was not removed', describeError(e))
  } finally {
    busy.value = ''
  }
}
</script>

<style>
.set-people { display: flex; flex-direction: column; gap: 14px; padding: 6px 18px 16px; }
.set-people__loading { padding: 12px 0 0; font-size: var(--text-sm); color: var(--muted); }
.set-people__error { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
.set-people__list { list-style: none; margin: 0; padding: 0; }
.set-people__row { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.set-people__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--bg-subtle);
  color: var(--text-soft);
  font-weight: 650;
  font-size: var(--text-sm);
  flex: none;
}
.set-people__who { flex: 1; min-width: 160px; display: flex; flex-direction: column; }
.set-people__email { font-weight: 550; overflow-wrap: anywhere; }
.set-people__name { font-size: var(--text-xs); color: var(--muted); }
.set-people__actions { display: flex; gap: 4px; flex-wrap: wrap; }
.set-people__handout { padding: 12px 14px; border-radius: var(--radius); background: var(--success-soft); color: var(--success-soft-text); }
.set-people__handout-title { font-weight: 650; }
.set-people__handout-text { margin-top: 2px; font-size: var(--text-sm); }
.set-people__addr { font-weight: 650; overflow-wrap: anywhere; }
.set-people__local { margin-top: 8px; }
.set-people__secret { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.set-people__code {
  padding: 5px 10px;
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-mono);
  font-size: var(--text-md);
  letter-spacing: 0.04em;
  user-select: all;
}
.set-people__add-row { display: flex; gap: 8px; }
.set-people__add-row .b-input { flex: 1; min-width: 0; }
</style>
