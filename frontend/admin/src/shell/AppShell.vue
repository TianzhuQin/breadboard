<template>
  <div class="shell" :class="{ 'nav-open': navOpen }">
    <!-- not a fragment link: under the hash router "#main" would be read as the page /main -->
    <a href="#main" class="shell__skip" @click.prevent="mainEl?.focus()">Skip to content</a>
    <div
      id="shell-nav"
      ref="navEl"
      class="shell__nav"
      :inert="navHidden || undefined"
    >
      <SideNav @navigate="closeNav('main')" />
    </div>
    <div v-if="navOpen" class="shell__scrim" aria-hidden="true" @click="closeNav('menu')" />

    <div class="shell__main">
      <TopBar ref="topBar" :output-open="outputOpen" :nav-open="navOpen" @menu="openNav" @toggle-output="toggleOutput" />
      <EngineNotice />
      <main id="main" ref="mainEl" class="shell__content" :class="{ 'is-bleed': bleed }" tabindex="-1">
        <div v-if="!admin.ready" class="shell__loading" role="status">
          <BSpinner :size="22" />
          <span>Loading your experiments…</span>
        </div>
        <NeedsExperiment v-else-if="route.meta.needsExperiment && !admin.experiment" />
        <RouterView v-else v-slot="{ Component }">
          <component :is="Component" />
        </RouterView>
      </main>
      <OutputDrawer v-if="outputOpen" @close="outputOpen = false" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import OutputDrawer from './OutputDrawer.vue'
import NeedsExperiment from './NeedsExperiment.vue'
import EngineNotice from './EngineNotice.vue'
import { BSpinner } from '@/ui'
import { admin, actions } from '@/store/admin'
import { openOutput } from './output'

const route = useRoute()
const navOpen = ref(false)
const outputOpen = openOutput
const bleed = computed(() => !!route.meta.fullBleed)
const navEl = ref<HTMLElement>()
const mainEl = ref<HTMLElement>()
const topBar = ref<InstanceType<typeof TopBar>>()

// Below 900px the navigation is a drawer, only moved off-screen when closed: keep it out of the
// tab order and away from screen readers then.
const narrow = ref(false)
let narrowQuery: MediaQueryList | null = null
function onNarrow () {
  narrow.value = !!narrowQuery?.matches
  if (!narrow.value) navOpen.value = false
}
const navHidden = computed(() => narrow.value && !navOpen.value)

async function openNav () {
  navOpen.value = true
  await nextTick()
  const link = navEl.value?.querySelector<HTMLElement>('a.is-active') || navEl.value?.querySelector<HTMLElement>('a[href]')
  link?.focus()
}
/** Close the drawer; focus goes back to the menu button, or to the page after choosing one. */
function closeNav (focus: 'menu' | 'main') {
  if (!navOpen.value) return
  const hadFocus = !!navEl.value && navEl.value.contains(document.activeElement)
  navOpen.value = false
  if (focus === 'menu') topBar.value?.focusMenu()
  else if (hadFocus) mainEl.value?.focus({ preventScroll: true })
}

function toggleOutput () {
  outputOpen.value = !outputOpen.value
}
watch(outputOpen, (v) => { if (v) actions.markErrorsSeen() })
// new errors while the panel is open count as seen
watch(() => admin.unseenErrors, (n) => { if (n && outputOpen.value) actions.markErrorsSeen() })

function onKey (e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'j') {
    e.preventDefault()
    toggleOutput()
  }
  // (menus and dialogs inside handle their own Escape and stop it)
  if (e.key === 'Escape' && navOpen.value) closeNav('menu')
}
onMounted(() => {
  window.addEventListener('keydown', onKey)
  if (typeof window.matchMedia === 'function') {
    narrowQuery = window.matchMedia('(max-width: 900px)')
    onNarrow()
    narrowQuery.addEventListener?.('change', onNarrow)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  narrowQuery?.removeEventListener?.('change', onNarrow)
})
watch(() => route.fullPath, () => closeNav('main'))
// Pages scroll inside <main>, which stays from page to page: start each new page at its top.
watch(() => route.path, () => { if (mainEl.value) mainEl.value.scrollTop = 0 })
</script>

<style>
.shell {
  display: grid;
  grid-template-columns: var(--sidebar-width) minmax(0, 1fr);
  height: 100vh;
  height: 100dvh;
}
.shell__skip { position: absolute; left: -9999px; top: 8px; z-index: 300; padding: 8px 12px; background: var(--surface); border-radius: var(--radius-sm); box-shadow: var(--shadow); }
.shell__skip:focus { left: 8px; }
.shell__nav { min-height: 0; }
.shell__main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
/* (position: relative: absolutely placed things inside, like screen-reader text, are placed in
   the scroll pane, not against <body>, where they made the whole window scroll) */
.shell__content { position: relative; flex: 1; min-height: 0; overflow: auto; outline: none; overscroll-behavior: contain; }
.shell__content:not(.is-bleed) > * { max-width: 1280px; margin-left: auto; margin-right: auto; }
.shell__content.is-bleed { overflow: hidden; display: flex; flex-direction: column; }
.shell__content.is-bleed > * { flex: 1; min-height: 0; }
.shell__loading { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 20vh 0; color: var(--muted); }
.shell__scrim { display: none; }
@media (max-width: 900px) {
  .shell { grid-template-columns: minmax(0, 1fr); }
  .shell__nav {
    position: fixed;
    inset: 0 auto 0 0;
    width: min(300px, 86vw);
    z-index: 90;
    transform: translateX(-102%);
    transition: transform 220ms var(--ease);
    box-shadow: var(--shadow-lg);
  }
  .shell.nav-open .shell__nav { transform: none; }
  .shell__scrim { display: block; position: fixed; inset: 0; z-index: 80; background: var(--overlay); }
}
</style>
