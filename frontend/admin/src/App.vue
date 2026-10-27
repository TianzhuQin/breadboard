<template>
  <div v-if="admin.auth === 'checking'" class="app-splash" role="status">
    <BrandMark :size="40" />
    <BSpinner :size="22" />
  </div>
  <LoginPage v-else-if="admin.auth === 'signedOut'" />
  <SetupPage v-else-if="admin.auth === 'firstUser'" />
  <div v-else-if="admin.auth === 'unreachable'" class="app-splash">
    <BEmpty :icon="WifiOff" title="Can't reach the Breadboard server" text="Check that it is running, then try again.">
      <template #actions><BButton variant="primary" @click="boot()">Try again</BButton></template>
    </BEmpty>
  </div>
  <AppShell v-else />
  <BToasts />
  <BConfirmHost />
</template>

<script setup lang="ts">
import { WifiOff } from 'lucide-vue-next'
import { admin, boot } from './store/admin'
import { BButton, BEmpty, BSpinner } from './ui'
import BToasts from './ui/BToasts.vue'
import BConfirmHost from './ui/BConfirmHost.vue'
import BrandMark from './shell/BrandMark.vue'
import AppShell from './shell/AppShell.vue'
import LoginPage from './pages/auth/LoginPage.vue'
import SetupPage from './pages/auth/SetupPage.vue'
</script>

<style>
.app-splash {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 22px;
}
</style>
