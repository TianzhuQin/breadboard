<template>
  <BPage intro="The guides included with this version of Breadboard. They work without GitHub.">
    <nav class="help-nav" aria-label="Guides">
      <RouterLink v-for="g in guides" :key="g.file" :to="`/help/${g.file}`">{{ g.label }}</RouterLink>
    </nav>
    <p class="help-secondary">Breadboard {{ version }} · <a :href="githubGuide(file, route.hash)" target="_blank" rel="noopener noreferrer">Read this guide on GitHub</a></p>
    <p v-if="loading" role="status">Loading guide…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <BButton @click="load">Try again</BButton>
    </div>
    <MarkdownDoc v-else :source="source" :file="file" />
  </BPage>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { BButton, BPage } from '@/ui'
import { http } from '@/lib/http'
import { version } from '../../../package.json'
import { guides, githubGuide } from './help'
import MarkdownDoc from './MarkdownDoc'

const route = useRoute()
const file = computed(() => guides.find(g => g.file === route.params.file)?.file || 'console.md')
const loading = ref(false)
const error = ref('')
const source = ref('')
let request = 0
async function scrollToAnchor () {
  await nextTick()
  let id = route.hash.slice(1)
  try { id = decodeURIComponent(id) } catch { /* keep an invalid escape as written */ }
  const heading = id ? document.getElementById(id) : null
  if (heading) { heading.scrollIntoView?.({ block: 'start' }); heading.focus({ preventScroll: true }) }
}
async function load () {
  const own = ++request
  loading.value = true
  error.value = ''
  try {
    const text = await http.get<string>(`/docs/${file.value}`, { raw: true })
    if (own !== request) return
    source.value = text
  } catch {
    if (own !== request) return
    error.value = 'This guide could not be loaded. Check the connection and try again, or read it on GitHub using the link above.'
  } finally {
    if (own === request) { loading.value = false; await scrollToAnchor() }
  }
}
watch(file, load, { immediate: true })
watch(() => route.hash, scrollToAnchor)
</script>

<style>
.help-nav { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.help-nav a { color: var(--muted); }
.help-nav .router-link-active { color: var(--accent); font-weight: 650; }
.help-secondary { color: var(--muted); font-size: var(--text-sm); }
.help-doc { max-width: 960px; min-width: 0; line-height: 1.65; overflow-wrap: anywhere; }
.help-doc h1, .help-doc h2, .help-doc h3, .help-doc h4, .help-doc h5, .help-doc h6 { margin: 1.4em 0 .55em; scroll-margin-top: 16px; }
.help-doc h1 { font-size: 28px; margin-top: 0; }
.help-doc h2 { font-size: 22px; border-bottom: 1px solid var(--border); padding-bottom: 6px; }
.help-doc h3 { font-size: 18px; }
.help-doc p, .help-doc ul, .help-doc ol, .help-doc pre, .help-doc blockquote, .help-table { margin: .75em 0; }
.help-doc ul, .help-doc ol { padding-left: 1.7em; }
.help-doc ul { list-style: disc; }
.help-doc ol { list-style: decimal; }
.help-doc li { margin: .25em 0; }
.help-doc li > p { margin: .3em 0; }
.help-doc a, .help-secondary a { color: var(--accent); text-decoration: underline; }
.help-doc code { background: var(--surface-hover); padding: 1px 4px; border-radius: 3px; font-size: .9em; }
.help-doc pre { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 14px; overflow: auto; }
.help-doc pre code { background: none; padding: 0; }
.help-table { overflow-x: auto; }
.help-doc table { width: 100%; border-collapse: collapse; }
.help-doc th, .help-doc td { padding: 8px 10px; border: 1px solid var(--border); text-align: left; vertical-align: top; }
.help-doc th { background: var(--surface-hover); font-weight: 650; }
.help-doc blockquote { border-left: 3px solid var(--border); padding-left: 14px; color: var(--muted); }
</style>
