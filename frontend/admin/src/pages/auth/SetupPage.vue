<template>
  <AuthCard title="Welcome to Breadboard" subtitle="Create the first researcher account for this server.">
    <form novalidate @submit.prevent="submit">
      <p v-if="error" class="auth__error" role="alert">{{ error }}</p>
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
      <BField label="Default language" hint="The language new content is written in.">
        <template #default="{ id }">
          <BSelect :id="id" v-model="languageId" :options="languageOptions" placeholder="Loading languages…" />
        </template>
      </BField>
      <BButton type="submit" variant="primary" size="lg" block :loading="busy">Create account</BButton>
    </form>
  </AuthCard>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BButton, BField, BInput, BSelect } from '@/ui'
import { boot, createFirstUser } from '@/store/admin'
import { http } from '@/lib/http'
import AuthCard from './AuthCard.vue'

const email = ref('')
const password = ref('')
const confirmPw = ref('')
const languageId = ref<number | null>(null)
const languages = ref<{ id: number, code: string, name: string }[]>([])
const busy = ref(false)
const error = ref('')

const languageOptions = computed(() => languages.value.map(l => ({ value: l.id, label: `${l.name} (${l.code})` })))

onMounted(async () => {
  try {
    const res = await http.get<{ languages: { id: number, code: string, name: string }[] }>('/languages', { quiet401: true })
    languages.value = [...res.languages].sort((a, b) => a.name.localeCompare(b.name))
    const english = languages.value.find(l => l.code === 'eng')
    languageId.value = (english || languages.value[0])?.id ?? null
  } catch (e) {
    error.value = 'Could not load the list of languages.'
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
  busy.value = true
  try {
    await createFirstUser(email.value.trim(), password.value, languageId.value)
  } catch (e: any) {
    if (/already exists/.test(e?.message || '')) {
      // created meanwhile (another tab): show the sign-in page
      await boot()
      return
    }
    error.value = e?.message || 'Could not create the account.'
  } finally {
    busy.value = false
  }
}
</script>
