<template>
  <div class="auth">
    <div class="auth__glow" aria-hidden="true" />
    <div class="auth__card">
      <div class="auth__brand">
        <BrandMark :size="44" />
        <h1 class="auth__title">{{ title }}</h1>
        <p v-if="subtitle" class="auth__sub">{{ subtitle }}</p>
      </div>
      <slot />
    </div>
    <p class="auth__foot"><slot name="foot" /></p>
  </div>
</template>

<script setup lang="ts">
import BrandMark from '@/shell/BrandMark.vue'
defineProps<{ title: string, subtitle?: string }>()
</script>

<style>
.auth {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  overflow: hidden;
}
.auth__glow {
  position: absolute;
  inset: -20% -10% auto;
  height: 70%;
  background:
    radial-gradient(600px 300px at 30% 30%, rgba(99, 102, 241, 0.16), transparent 70%),
    radial-gradient(500px 280px at 75% 20%, rgba(14, 165, 233, 0.1), transparent 70%);
  pointer-events: none;
}
.auth__card {
  position: relative;
  width: 100%;
  max-width: 400px;
  padding: 32px 30px 28px;
  background: var(--surface-raised);
  border: 1px solid var(--border);
  border-radius: 20px;
  box-shadow: var(--shadow-lg);
}
.auth__brand { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 6px; margin-bottom: 24px; }
.auth__title { margin-top: 12px; font-size: 22px; }
.auth__sub { color: var(--muted); font-size: var(--text-md); }
.auth__foot { position: relative; margin-top: 18px; font-size: var(--text-sm); color: var(--muted); }
.auth form { display: flex; flex-direction: column; gap: 16px; }
.auth__error { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--danger-soft); color: var(--danger-soft-text); font-size: var(--text-sm); }
</style>
