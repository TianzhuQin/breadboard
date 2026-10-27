<template>
  <BMenu :items="items" up :width="230">
    <template #trigger="{ toggle, attrs }">
      <button type="button" class="acct" v-bind="attrs" @click="toggle">
        <span class="acct__avatar" aria-hidden="true">{{ initial }}</span>
        <span class="acct__email">{{ admin.email }}</span>
        <MoreHorizontal :size="16" class="acct__more" aria-hidden="true" />
      </button>
    </template>
  </BMenu>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { MoreHorizontal, Sun, Moon, Monitor, History, LogOut } from 'lucide-vue-next'
import { BMenu, type MenuItem } from '@/ui'
import { admin, logout } from '@/store/admin'
import { theme, setTheme } from '@/lib/theme'

const initial = computed(() => (admin.email || '?').trim().charAt(0).toUpperCase())
const items = computed<MenuItem[]>(() => [
  { heading: 'Appearance' },
  { label: 'System', icon: Monitor, active: theme.value === 'system', run: () => setTheme('system') },
  { label: 'Light', icon: Sun, active: theme.value === 'light', run: () => setTheme('light') },
  { label: 'Dark', icon: Moon, active: theme.value === 'dark', run: () => setTheme('dark') },
  { divider: true },
  { label: 'Open the previous console', icon: History, run: () => { window.location.href = '/legacy-admin' } },
  { label: 'Sign out', icon: LogOut, run: () => logout() },
])
</script>

<style>
.acct {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-top: 6px;
  padding: 7px 8px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.acct:hover { background: var(--surface-hover); }
.acct__avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, color-mix(in srgb, var(--accent) 45%, var(--surface)), var(--accent));
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  flex: none;
}
.acct__email { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--text-sm); color: var(--text-soft); }
.acct__more { color: var(--faint); }
.b-menu:has(.acct) { display: flex; }
</style>
