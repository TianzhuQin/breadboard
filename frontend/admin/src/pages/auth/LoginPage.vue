<template>
  <AuthCard title="Sign in to Breadboard" subtitle="The researcher's console">
    <form novalidate @submit.prevent="submit">
      <p v-if="error" class="auth__error" role="alert">{{ error }}</p>
      <p v-if="forgot" class="auth__note">
        Forgot your password? Another researcher can give you a new one in Settings → Researchers. Or whoever
        runs the server can stop Breadboard and start it once with
        <code>-Dbreadboard.resetPassword=your@email</code> (see “Troubleshooting” in docs/install.md).
      </p>
      <p v-else-if="admin.signOutReason === 'removed'" class="auth__note" role="status">
        You were signed out because another researcher removed this account. Ask them to add it
        again if you still need it.
      </p>
      <p v-else-if="admin.signOutReason === 'ended'" class="auth__note" role="status">
        You were signed out because this account signed out, or its password was changed, in another
        browser or tab. Sign in again to continue here.
      </p>
      <BField label="Email">
        <template #default="{ id }">
          <BInput :id="id" v-model="email" type="email" autocomplete="username" :autofocus="!email" required />
        </template>
      </BField>
      <BField label="Password">
        <template #default="{ id }">
          <BInput :id="id" v-model="password" type="password" autocomplete="current-password" :autofocus="!!email" required />
        </template>
      </BField>
      <BButton type="submit" variant="primary" size="lg" block :loading="busy">Sign in</BButton>
    </form>
    <template #foot>
      Looking for the previous console? <a href="/legacy-admin">Open it here</a>.
    </template>
  </AuthCard>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { BButton, BField, BInput } from '@/ui'
import { admin, login } from '@/store/admin'
import AuthCard from './AuthCard.vue'

// (Signed out from elsewhere: the address is still known.)
const email = ref(admin.signOutReason ? admin.email : '')
const password = ref('')
const busy = ref(false)
const error = ref('')
// after a wrong password: how to get a new one
const forgot = ref(false)

async function submit () {
  error.value = ''
  if (!email.value.trim() || !password.value) {
    error.value = 'Enter your email and password.'
    return
  }
  busy.value = true
  try {
    await login(email.value.trim(), password.value)
  } catch (e: any) {
    if (e && e.status === 400) {
      error.value = 'That email and password don\'t match an account.'
      forgot.value = true
    } else {
      error.value = e?.message || 'Could not sign in.'
    }
  } finally {
    busy.value = false
  }
}
</script>
