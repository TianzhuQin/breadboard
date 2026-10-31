<template>
  <BPanel flush class="live-net">
    <template #header>
      <div class="live-net__titles">
        <h3 class="b-panel__title">Network</h3>
        <p class="b-panel__subtitle">{{ summary }}</p>
      </div>
    </template>
    <template #actions>
      <BButton size="sm" variant="ghost" :icon-right="ArrowRight" href="#/network">Open</BButton>
    </template>
    <div class="live-net__stage">
      <!-- a glance only: the full, editable view is on the Network page. It always fits everyone
           in, whatever zoom and pan the Network page was left at -->
      <div class="live-net__view" inert>
        <AdminNetwork :graph="graph" mode="view" :controls="false" :remember-view="false">
          <!-- (an ended session's network is gone once the engine restarts for the next one) -->
          <template #empty><p class="live-net__empty">{{ ended ? 'This session has ended.' : 'No one has joined yet.' }}</p></template>
        </AdminNetwork>
      </div>
      <!-- the whole picture is a click target; keyboard users have the Open button above -->
      <RouterLink to="/network" class="live-net__open" tabindex="-1" aria-hidden="true">
        <span class="live-net__pill">Open the network <ArrowRight :size="14" /></span>
      </RouterLink>
    </div>
  </BPanel>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowRight } from 'lucide-vue-next'
import { BButton, BPanel } from '@/ui'
import AdminNetwork from '@/network/AdminNetwork.vue'
import { graph } from '@/store/admin'

/** `ended`: the session doesn't run any more (finished or stopped). */
const props = defineProps<{ people: number, ties: number, ended?: boolean }>()
const summary = computed(() => {
  if (props.ended && !props.people && !props.ties) return 'The session has ended'
  const p = props.people === 1 ? '1 person' : `${props.people} people`
  const t = props.ties === 1 ? '1 tie' : `${props.ties} ties`
  return `${p} · ${t}`
})
</script>

<style>
.live-net__titles { flex: 1; min-width: 0; }
.live-net__stage { position: relative; height: 300px; overflow: hidden; border-radius: 0 0 var(--radius-lg) var(--radius-lg); }
.live-net__view { position: absolute; inset: 0; pointer-events: none; }
.live-net__empty { color: var(--muted); font-size: var(--text-sm); }
.live-net__open {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  padding: 12px;
  text-decoration: none !important;
}
.live-net__pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: var(--radius-pill);
  background: var(--surface-raised);
  border: 1px solid var(--border);
  box-shadow: var(--shadow);
  color: var(--text-soft);
  font-size: var(--text-xs);
  font-weight: 600;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity var(--duration) var(--ease), transform var(--duration) var(--ease);
}
.live-net__open:hover .live-net__pill { opacity: 1; transform: none; }
@media (hover: none) {
  .live-net__pill { opacity: 1; transform: none; }
}
</style>
