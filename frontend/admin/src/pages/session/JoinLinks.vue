<template>
  <BPanel title="Links for participants" :subtitle="joinable ? 'Send one of these to the people taking part.' : 'Nobody can join until the session runs again.'">
    <div class="live-links">
      <LocalOnlyNote />
      <div v-for="row in rows" :key="row.key" class="live-link">
        <div class="live-link__head">
          <component :is="row.icon" :size="15" class="live-link__icon" aria-hidden="true" />
          <span :id="`${base}-${row.key}`" class="live-link__label">{{ row.label }}</span>
        </div>
        <div class="live-link__box">
          <BInput
            :ref="(el: any) => inputs[row.key] = el"
            :model-value="row.url"
            size="sm"
            readonly
            class="mono"
            :aria-labelledby="`${base}-${row.key}`"
            :aria-describedby="`${base}-${row.key}-hint`"
            @focus="select(row.key)"
          />
          <BButton
            size="sm"
            :icon="copied === row.key ? Check : Copy"
            :aria-label="`Copy the link: ${row.label}`"
            :title="copied === row.key ? 'Copied' : 'Copy'"
            @click="copy(row.key, row.url)"
          />
          <BButton
            v-if="row.open"
            size="sm"
            variant="ghost"
            :icon="ExternalLink"
            :href="row.url"
            target="_blank"
            rel="noopener"
            :aria-label="`Open the link in a new tab: ${row.label}`"
            title="Open in a new tab"
          />
        </div>
        <p :id="`${base}-${row.key}-hint`" class="live-link__hint">{{ row.hint }}</p>
      </div>
      <p class="sr-only" role="status" aria-live="polite">{{ status }}</p>
    </div>
  </BPanel>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, type Component } from 'vue'
import { Copy, Check, ExternalLink, LogIn, Repeat, Users } from 'lucide-vue-next'
import { BButton, BInput, BPanel, notify } from '@/ui'
import { uid } from '@/ui/uid'
import { joinLinks } from './live'
import LocalOnlyNote from './LocalOnlyNote.vue'

const props = defineProps<{ experimentId: number, instanceId: number | null, joinable: boolean }>()

const base = uid('live-link')
const origin = typeof window !== 'undefined' ? window.location.origin : ''

interface Row { key: string, label: string, url: string, hint: string, icon: Component, open: boolean }
const rows = computed<Row[]>(() => {
  const l = joinLinks(origin, props.experimentId, props.instanceId)
  const out: Row[] = []
  if (l.session) {
    out.push({
      key: 'session',
      label: 'This session',
      url: l.session,
      hint: 'Opens this session\'s sign-in page. It works while this session is running.',
      icon: LogIn,
      open: props.joinable,
    })
  }
  out.push({
    key: 'current',
    label: 'Whichever session is open',
    url: l.current,
    hint: 'Leads to the open session of this experiment that hasn\'t started its game yet. Share it once and reuse it for every session.',
    icon: Repeat,
    open: props.joinable,
  })
  out.push({
    key: 'prolific',
    label: 'Prolific study URL',
    url: l.prolific,
    hint: 'Paste it into Prolific as the study URL. Prolific fills in the {{%…%}} parts for each participant.',
    icon: Users,
    open: false,
  })
  return out
})

const inputs = reactive<Record<string, any>>({})
function select (key: string) {
  inputs[key]?.select?.()
}

const copied = ref<string | null>(null)
const status = ref('')
let timer: ReturnType<typeof setTimeout> | null = null
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })

async function writeClipboard (text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch (e) {
    // no clipboard API outside https/localhost: fall back to a hidden text box
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      return ok
    } catch (e2) {
      return false
    }
  }
}

async function copy (key: string, url: string) {
  const ok = await writeClipboard(url)
  if (ok) {
    copied.value = key
    status.value = 'Link copied.'
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => { copied.value = null; status.value = '' }, 1800)
  } else {
    inputs[key]?.focus?.()
    select(key)
    notify.error('Could not copy the link', 'Your browser blocked the clipboard. The link is selected: press Ctrl+C (⌘C on a Mac) to copy it.')
  }
}
</script>

<style>
.live-links { display: flex; flex-direction: column; gap: 16px; }
.live-link { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.live-link__head { display: flex; align-items: center; gap: 7px; }
.live-link__icon { color: var(--muted); }
.live-link__label { font-size: var(--text-sm); font-weight: 600; color: var(--text-soft); }
.live-link__box { display: flex; gap: 6px; min-width: 0; }
.live-link__box .b-input { flex: 1; min-width: 0; }
.live-link__hint { font-size: var(--text-xs); color: var(--muted); line-height: 1.45; }
</style>
