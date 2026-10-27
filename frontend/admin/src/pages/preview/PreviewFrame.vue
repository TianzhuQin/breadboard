<template>
  <div
    class="pf"
    :class="[`pf--${device}`, { 'is-loaded': loaded }]"
    :style="{ width: `${geo.width}px`, height: `${geo.height}px`, '--pf-bezel': `${geo.bezel}px`, '--pf-scale': geo.scale }"
  >
    <div class="pf__screen">
      <!--
        The participant page is the console's own (same-origin) page: it needs the researcher's
        sign-in to act as the player. With allow-scripts and allow-same-origin together the sandbox
        is no protection: a script in the page can reach the console (window.parent), its cookies
        and the admin socket, and can even lift the sandbox. So whatever HTML the page shows runs
        with the researcher's rights, and the Preview page says so. The flags only keep a
        well-behaved page (an end-of-study redirect written for window.top) from navigating the
        console away, and keep its pop-ups sandboxed. Real isolation needs the participant pages on
        another origin.
      -->
      <iframe
        :src="src"
        :title="title"
        class="pf__frame"
        :style="{ width: `${geo.deviceWidth}px`, height: `${geo.deviceHeight}px`, transform: `scale(${geo.scale})` }"
        :tabindex="interactive ? 0 : -1"
        :loading="lazy ? 'lazy' : undefined"
        sandbox="allow-scripts allow-same-origin allow-forms allow-modals allow-popups allow-downloads"
        referrerpolicy="same-origin"
        @load="loaded = true"
      />
    </div>
    <div v-if="!loaded" class="pf__loading" aria-hidden="true">
      <BSpinner :size="18" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BSpinner } from '@/ui'
import { frameGeometry, type DeviceKind } from './preview'

const props = defineProps<{
  src: string
  title: string
  device: DeviceKind
  /** Outer width in CSS pixels; the height follows the device's shape. */
  width: number
  /** Whether keyboard focus may enter the page (off while a cover button handles clicks). */
  interactive?: boolean
  lazy?: boolean
}>()

const geo = computed(() => frameGeometry(props.device, props.width))
const loaded = ref(false)
watch(() => props.src, () => { loaded.value = false })
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
</style>
