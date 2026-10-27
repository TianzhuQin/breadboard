<template>
  <div
    class="pf"
    :class="[`pf--${device}`, { 'is-loaded': loaded }]"
    :style="{ width: `${geo.width}px`, height: `${geo.height}px`, '--pf-bezel': `${geo.bezel}px`, '--pf-scale': geo.scale }"
  >
    <div class="pf__screen">
      <!--
        The page is loaded with ?preview=1 and a preview token in its fragment (previewLink.ts), not
        with the researcher's sign-in. Without allow-same-origin it runs in an opaque origin (the
        server also sends it with a CSP sandbox, so the same holds in a tab of its own): a script
        that a participant hid in what the page shows can't reach the console (window.parent), its
        cookies, storage or socket. The token lets it act only as this player of this session.
        No allow-top-navigation, so an end-of-study redirect written for window.top can't navigate
        the console away; no allow-popups-to-escape-sandbox, so its pop-ups stay sandboxed too.
      -->
      <iframe
        v-if="src"
        :src="src"
        :title="title"
        class="pf__frame"
        :style="{ width: `${geo.deviceWidth}px`, height: `${geo.deviceHeight}px`, transform: `scale(${geo.scale})` }"
        :tabindex="interactive ? 0 : -1"
        :loading="lazy ? 'lazy' : undefined"
        sandbox="allow-scripts allow-forms allow-modals allow-popups"
        referrerpolicy="same-origin"
        @load="loaded = true"
      />
    </div>
    <!-- above a tile's cover button (a later sibling), so Retry gets the click -->
    <div v-if="error" class="pf__error" role="alert">
      <TriangleAlert :size="16" class="pf__error-icon" aria-hidden="true" />
      <p class="pf__error-title">Couldn’t open this page</p>
      <p class="pf__error-text">{{ error }}</p>
      <BButton size="sm" :icon="RotateCw" @click="retry">Retry</BButton>
    </div>
    <div v-else-if="!loaded" class="pf__loading" aria-hidden="true">
      <BSpinner :size="18" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RotateCw, TriangleAlert } from 'lucide-vue-next'
import { BButton, BSpinner } from '@/ui'
import { frameGeometry, type DeviceKind, type PreviewTarget } from './preview'
import { usePreviewLink } from './previewLink'

const props = defineProps<{
  /** whose page to show; a preview token is fetched for it (again when it changes) */
  target: PreviewTarget | null
  title: string
  device: DeviceKind
  /** Outer width in CSS pixels; the height follows the device's shape. */
  width: number
  /** Whether keyboard focus may enter the page (off while a cover button handles clicks). */
  interactive?: boolean
  lazy?: boolean
}>()

const geo = computed(() => frameGeometry(props.device, props.width))
const { src, error, retry } = usePreviewLink(() => props.target)
const loaded = ref(false)
watch(src, () => { loaded.value = false })
</script>

<style>
.pf {
  /* The participant page is always light (client/src/theme/bb-theme.css), so its canvas is white
     in both console themes: this is the participant's browser, not console chrome. */
  --pf-canvas: #fff;
  position: relative;
  flex: none;
  overflow: hidden;
  background: var(--surface);
  /* a ring, not a border, so the page inside keeps its exact scaled size */
  box-shadow: 0 0 0 1px var(--border-strong), var(--shadow);
  border-radius: var(--radius-sm);
}
.pf--phone {
  padding: var(--pf-bezel);
  background: var(--border-strong);
  box-shadow: 0 0 0 1px var(--border-strong), var(--shadow);
  border-radius: calc(var(--pf-scale) * 56px);
}
.pf__screen {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: var(--pf-canvas);
}
.pf--phone .pf__screen { border-radius: calc(var(--pf-scale) * 44px); }
.pf__frame {
  position: absolute;
  top: 0;
  left: 0;
  border: 0;
  transform-origin: 0 0;
  background: var(--pf-canvas);
}
.pf__loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: var(--surface-sunken);
}
.pf__error {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 8px 10px;
  overflow: auto;
  text-align: center;
  background: var(--surface-sunken);
  color: var(--text-soft);
  font-size: var(--text-xs);
  line-height: 1.4;
}
.pf__error-icon { flex: none; color: var(--warning); }
.pf__error-title { font-weight: 650; color: var(--text); }
.pf__error-text { max-width: 320px; overflow-wrap: anywhere; }
.pf__error .b-btn { margin-top: 4px; flex: none; }
</style>
