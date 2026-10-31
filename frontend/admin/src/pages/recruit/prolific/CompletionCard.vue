<template>
  <BPanel title="When a participant finishes" subtitle="Send them back to Prolific with your completion code.">
    <!-- An experiment with a prolificCode launch parameter (the network game example) gives Prolific
         participants that code by itself: there is no code to add to its steps, only the code to set. -->
    <div v-if="codeParam" class="rc-stack">
      <ol class="pl-steps">
        <li>
          <span class="pl-steps__n" aria-hidden="true">1</span>
          <div>
            <strong>In Prolific</strong>, under study completion, choose to send participants back with a
            completion code and note the code Prolific shows you.
          </div>
        </li>
        <li>
          <span class="pl-steps__n" aria-hidden="true">2</span>
          <div class="pl-steps__body">
            <p>
              <strong>On the Parameters page</strong>, make that code the default of <code>{{ CODE_PARAMETER }}</code>. This
              experiment shows it to Prolific participants by itself when they finish: there is no code to add to its steps.
            </p>
            <p v-if="codeNow" class="pl-code-now">Now: <code class="rc-mono">{{ codeNow }}</code></p>
            <p v-else class="pl-code-now is-empty">
              <TriangleAlert :size="15" aria-hidden="true" /> Empty for now, so Prolific participants finish without a code.
            </p>
            <div class="pl-code-go">
              <BButton size="sm" :icon-right="ArrowRight" @click="router.push('/parameters')">Parameters</BButton>
              <span class="rc-sub">A session keeps the value it opened with; the autopilot's sessions take the default.</span>
            </div>
          </div>
        </li>
        <li>
          <span class="pl-steps__n" aria-hidden="true">3</span>
          <div>
            <strong>The participant</strong> sees a thank-you screen with the code and a
            <em>Finish</em> button that takes them to Prolific, which marks their submission complete.
          </div>
        </li>
      </ol>
    </div>
    <div v-else class="rc-stack">
      <ol class="pl-steps">
        <li>
          <span class="pl-steps__n" aria-hidden="true">1</span>
          <div>
            <strong>In Prolific</strong>, under study completion, choose to send participants back with a
            completion code and note the code Prolific shows you.
          </div>
        </li>
        <li>
          <span class="pl-steps__n" aria-hidden="true">2</span>
          <div class="pl-steps__body">
            <p><strong>In your steps</strong>, put this where a participant is done (for example in the last step). It completes participants from Prolific only:</p>
            <BField label="Your completion code" hint="Fills in the code below; remembered in this browser." class="pl-code-field">
              <template #default="{ id, describedby }">
                <BInput :id="id" v-model="code" placeholder="e.g. C1A2B3C4" class="rc-mono" autocomplete="off" spellcheck="false" :aria-describedby="describedby" />
              </template>
            </BField>
            <div class="pl-snippet">
              <pre class="rc-code">{{ snippet }}</pre>
              <div class="pl-snippet__copy"><CopyButton :text="snippet" size="sm" label="Copy code" /></div>
            </div>
            <dl class="rc-kv pl-opts">
              <dt><code>completionCode</code></dt>
              <dd>Required. The code from Prolific.</dd>
              <dt><code>message</code></dt>
              <dd>Optional text (HTML) shown above the code.</dd>
              <dt><code>bonus</code></dt>
              <dd>Optional amount kept with the player, for paying bonuses on Prolific (see “From Prolific in every session”).</dd>
              <dt><code>noFeedback: true</code></dt>
              <dd>Hides the comments box on the last screen.</dd>
            </dl>
          </div>
        </li>
        <li>
          <span class="pl-steps__n" aria-hidden="true">3</span>
          <div>
            <strong>The participant</strong> sees a thank-you screen with the code and a
            <em>Finish</em> button that takes them to Prolific, which marks their submission complete.
          </div>
        </li>
      </ol>
      <div class="rc-note">
        <Info :size="16" aria-hidden="true" />
        <span>
          Pay people who arrive but never get to play, for example when a waiting room is cancelled for lack of players:
          complete them with a separate code in Autopilot’s <code>onCancel</code> (see <code>groovy/autopilot.groovy</code>).
        </span>
      </div>
    </div>
  </BPanel>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Info, TriangleAlert } from 'lucide-vue-next'
import { BButton, BField, BInput, BPanel } from '@/ui'
import type { Parameter } from '@/store/types'
import CopyButton from '../CopyButton.vue'
import { readStore, writeStore } from '../storage'
import { CODE_PARAMETER, completionSnippet } from './prolific'

const props = defineProps<{ experimentId: number, parameters?: Parameter[] | null }>()
const router = useRouter()

/** The experiment's prolificCode launch parameter, when it has one; its default is the code its sessions give. */
const codeParam = computed(() => (props.parameters || []).find(p => p && p.name === CODE_PARAMETER) || null)
const codeNow = computed(() => String(codeParam.value?.defaultVal ?? '').trim())

const key = computed(() => `bb.recruit.prolific.code.${props.experimentId}`)
const code = ref(readStore(key.value) || '')
watch(key, (k) => { code.value = readStore(k) || '' })
watch(code, (c) => writeStore(key.value, c.trim() || null))

const snippet = computed(() => completionSnippet(code.value))
</script>

<style>
.pl-steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 16px; }
.pl-steps > li { display: grid; grid-template-columns: 26px minmax(0, 1fr); gap: 12px; color: var(--text-soft); font-size: var(--text-sm); line-height: 1.55; }
.pl-steps strong { color: var(--text); }
.pl-steps__n { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; background: var(--accent-soft); color: var(--accent-soft-text); font-size: var(--text-xs); font-weight: 700; }
.pl-steps__body { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.pl-code-field { max-width: 320px; }
.pl-snippet { position: relative; }
.pl-snippet .rc-code { padding-right: 110px; }
.pl-snippet__copy { position: absolute; top: 8px; right: 8px; }
.pl-opts { font-size: var(--text-xs); }
.pl-opts dt code { color: var(--text-soft); }
.pl-code-now { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; color: var(--text); }
.pl-code-now.is-empty { color: var(--warning-soft-text); }
.pl-code-now svg { flex: none; }
.pl-code-go { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
</style>
