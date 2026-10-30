<template>
  <AuthCard title="Welcome to Breadboard" subtitle="Create the first researcher account for this server.">
    <form novalidate @submit.prevent="submit">
      <p v-if="error" class="auth__error" role="alert">{{ error }}</p>
      <p v-else-if="settingUp" class="auth__note" role="status">{{ SETTING_UP }}</p>
      <template v-if="admin.setupCodeRequired">
        <p class="auth__note">
          You opened this new server from another computer. To show it's you, enter the setup code the server
          printed in its window (and in <code>logs/application.log</code>).
        </p>
        <BField label="Setup code">
          <template #default="{ id }">
            <BInput :id="id" v-model="setupCode" autocomplete="off" spellcheck="false" placeholder="ABCD-EFGH" />
          </template>
        </BField>
      </template>
      <BField label="Email">
        <template #default="{ id }">
          <BInput :id="id" v-model="email" type="email" autocomplete="username" autofocus />
        </template>
      </BField>
      <BField label="Password" hint="At least 8 characters.">
        <template #default="{ id }">
          <BInput :id="id" v-model="password" type="password" autocomplete="new-password" />
        </template>
      </BField>
      <BField label="Confirm password">
        <template #default="{ id }">
          <BInput :id="id" v-model="confirmPw" type="password" autocomplete="new-password" />
        </template>
      </BField>
      <BField label="Default language" :hint="LANGUAGE_HINT">
        <template #default="{ id }">
          <BSelect :id="id" v-model="languageId" :options="languageOptions" placeholder="Loading languages…" />
        </template>
      </BField>
      <BButton type="submit" variant="primary" size="lg" block :loading="busy">Create account</BButton>
    </form>
  </AuthCard>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { BButton, BField, BInput, BSelect } from '@/ui'
import { admin, boot, createFirstUser } from '@/store/admin'
import { HttpError, http } from '@/lib/http'
import AuthCard from './AuthCard.vue'

// What the setting does, which the form didn't say: researchers in China chose Chinese and couldn't
// tell whether it changed the console or what participants see.
const LANGUAGE_HINT = 'The language you write participants’ texts in, on the Content page: new experiments are set up ' +
  'for it. The console itself stays in English. You can change it later in Settings.'

// A server that has just started may still be setting up (it says so with a 503), or not answer
// yet: the page says so and tries again by itself, for a minute or so, instead of failing.
const SETTING_UP = 'Setting up the server, one moment…'
const RETRY_MS = 2000
const RETRIES = 30
const settingUp = ref(false)
let retryTimer: ReturnType<typeof setTimeout> | null = null
let gone = false
onBeforeUnmount(() => {
  gone = true
  if (retryTimer) clearTimeout(retryTimer)
})
/** Whether the server isn't ready yet: still setting up, or not answering (a network error). */
function notReady (e: any): boolean {
  return e instanceof HttpError ? e.status === 503 : e instanceof TypeError
}
/** Wait a moment before trying again (false when the page has gone meanwhile). */
function aMoment (): Promise<boolean> {
  return new Promise(resolve => { retryTimer = setTimeout(() => resolve(!gone), RETRY_MS) })
}

const email = ref('')
const password = ref('')
const confirmPw = ref('')
const languageId = ref<number | null>(null)
const languages = ref<{ id: number, code: string, name: string }[]>([])
const busy = ref(false)
const error = ref('')
const setupCode = ref('')

const languageOptions = computed(() => languages.value.map(l => ({ value: l.id, label: `${l.name} (${l.code})` })))

onMounted(async () => {
  for (let tries = 0; ; tries++) {
    try {
      const res = await http.get<{ languages: { id: number, code: string, name: string }[] }>('/languages', { quiet401: true })
      languages.value = [...res.languages].sort((a, b) => a.name.localeCompare(b.name))
      const english = languages.value.find(l => l.code === 'eng')
      languageId.value = (english || languages.value[0])?.id ?? null
      settingUp.value = false
      return
    } catch (e) {
      if (tries < RETRIES && notReady(e)) {
        settingUp.value = true
        if (await aMoment()) continue
        return
      }
      settingUp.value = false
      error.value = 'Could not load the list of languages.'
      return
    }
  }
})

async function submit () {
  error.value = ''
  if (!email.value.trim()) return void (error.value = 'Enter an email address.')
  // (The email is the account's sign-in and can't be changed later: catch typos now.)
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.value.trim())) return void (error.value = 'Enter a valid email address, like name@university.edu.')
  if (password.value.length < 8) return void (error.value = 'Choose a password of at least 8 characters.')
  if (password.value !== confirmPw.value) return void (error.value = 'The two passwords are different.')
  if (languageId.value == null) return void (error.value = 'Choose a default language.')
  if (admin.setupCodeRequired && !setupCode.value.trim()) return void (error.value = 'Enter the setup code the server printed.')
  busy.value = true
  try {
    for (let tries = 0; ; tries++) {
      try {
        await createFirstUser(email.value.trim(), password.value, languageId.value, admin.setupCodeRequired ? setupCode.value.trim() : undefined)
        return
      } catch (e: any) {
        if (tries < RETRIES && e instanceof HttpError && e.status === 503) {
          settingUp.value = true
          if (await aMoment()) continue
          return
        }
        throw e
      }
    }
  } catch (e: any) {
    if (/already exists/.test(e?.message || '')) {
      // created meanwhile (another tab): show the sign-in page
      await boot()
      return
    }
    error.value = e?.message || 'Could not create the account.'
  } finally {
    settingUp.value = false
    busy.value = false
  }
}
</script>
