<template>
  <span class="rc-copy">
    <BButton
      :size="size"
      :variant="variant"
      :icon="copied ? Check : Copy"
      :aria-label="iconOnly ? (copied ? 'Copied' : ariaLabel || label) : undefined"
      :title="iconOnly ? ariaLabel || label : undefined"
      @click="copy"
    >
      <template v-if="!iconOnly">{{ copied ? 'Copied' : label }}</template>
    </BButton>
    <span class="sr-only" aria-live="polite">{{ copied ? 'Copied to the clipboard' : '' }}</span>
  </span>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { Check, Copy } from 'lucide-vue-next'
import { BButton, notify } from '@/ui'

const props = withDefaults(defineProps<{
  text: string
  label?: string
  ariaLabel?: string
  iconOnly?: boolean
  size?: 'sm' | 'md'
  variant?: 'primary' | 'secondary' | 'ghost'
}>(), { label: 'Copy', size: 'md', variant: 'secondary' })

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

async function copy () {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.text)
    } else {
      fallbackCopy(props.text)
    }
    copied.value = true
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { copied.value = false }, 1800)
  } catch (e) {
    try {
      fallbackCopy(props.text)
      copied.value = true
    } catch (e2) {
      notify.error('Could not copy', 'Select the text and copy it with Ctrl+C (⌘C on a Mac).')
    }
  }
}

// For browsers without the async clipboard (e.g. plain http on another host).
function fallbackCopy (text: string) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.setAttribute('readonly', '')
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  const ok = document.execCommand('copy')
  ta.remove()
  if (!ok) throw new Error('copy refused')
}

onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<style>
.rc-copy { display: inline-flex; flex: none; }
</style>
