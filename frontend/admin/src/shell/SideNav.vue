<template>
  <nav class="sidenav" aria-label="Console">
    <div class="sidenav__brand">
      <BrandMark :size="28" />
      <span class="sidenav__name">Breadboard</span>
    </div>

    <ExperimentSwitcher class="sidenav__switcher" />

    <div class="sidenav__scroll">
      <div v-for="g in NAV" :key="g.label" class="sidenav__group">
        <div class="sidenav__heading">{{ g.label }}</div>
        <RouterLink
          v-for="item in g.items"
          :key="item.to"
          :to="item.to"
          class="sidenav__link"
          active-class="is-active"
          @click="emit('navigate')"
        >
          <component :is="item.icon" :size="17" class="sidenav__icon" aria-hidden="true" />
          <span class="sidenav__label">{{ item.label }}</span>
          <span v-if="item.to === '/session' && running" class="sidenav__live" aria-label="running" />
          <span v-if="item.to === '/players' && playerCount" class="sidenav__count">{{ playerCount }}</span>
        </RouterLink>
      </div>
    </div>

    <div class="sidenav__footer">
      <RouterLink
        v-for="item in FOOTER_NAV"
        :key="item.to"
        :to="item.to"
        class="sidenav__link"
        active-class="is-active"
        @click="emit('navigate')"
      >
        <component :is="item.icon" :size="17" class="sidenav__icon" aria-hidden="true" />
        <span class="sidenav__label">{{ item.label }}</span>
      </RouterLink>
      <AccountMenu />
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NAV, FOOTER_NAV } from './nav'
import BrandMark from './BrandMark.vue'
import ExperimentSwitcher from './ExperimentSwitcher.vue'
import AccountMenu from './AccountMenu.vue'
import { graph, running } from '@/store/admin'

const emit = defineEmits<{ navigate: [] }>()
const playerCount = computed(() => { void graph.version.value; return graph.nodes.size })
</script>

<style>
.sidenav {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface);
  border-right: 1px solid var(--border);
}
.sidenav__brand { display: flex; align-items: center; gap: 10px; padding: 16px 18px 12px; }
.sidenav__name { font-weight: 700; font-size: 16px; letter-spacing: -0.02em; }
.sidenav__switcher { margin: 4px 12px 10px; }
.sidenav__scroll { flex: 1; overflow-y: auto; padding: 4px 10px 12px; }
.sidenav__group + .sidenav__group { margin-top: 14px; }
.sidenav__heading {
  padding: 6px 10px 6px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.07em;
  text-transform: uppercase;
  color: var(--faint);
}
.sidenav__link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  color: var(--text-soft);
  font-weight: 520;
  text-decoration: none !important;
  transition: background var(--duration) var(--ease), color var(--duration) var(--ease);
}
.sidenav__link:hover { background: var(--surface-hover); color: var(--text); }
.sidenav__link.is-active { background: var(--accent-soft); color: var(--accent-soft-text); font-weight: 600; }
.sidenav__link.is-active .sidenav__icon { color: var(--accent); }
.sidenav__icon { color: var(--muted); flex: none; }
.sidenav__label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sidenav__live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--success);
  box-shadow: 0 0 0 3px var(--success-soft);
  animation: b-live 2s ease-in-out infinite;
}
@keyframes b-live { 50% { box-shadow: 0 0 0 5px transparent; } }
.sidenav__count { font-size: 11px; font-weight: 650; color: var(--muted); background: var(--bg-subtle); padding: 0 7px; border-radius: 999px; line-height: 18px; }
.sidenav__footer { border-top: 1px solid var(--border); padding: 10px; display: flex; flex-direction: column; gap: 2px; }
</style>
