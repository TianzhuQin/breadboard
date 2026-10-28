<script lang="ts" setup>
import { computed, ref } from 'vue'
import { PlayerData } from '@human-nature-lab/breadboard-core'

const props = defineProps<{
  player: PlayerData
}>()

const data = computed(() => props.player._system?.recruitment)

const submitUrl = computed(() => {
  return `https://app.prolific.com/submissions/complete?cc=${data.value?.completionCode}`
})

const feedback = ref('')
const sending = ref(false)
function submit() {
  sending.value = true
  Breadboard.send('exiting', { feedback: feedback.value })
  setTimeout(() => {
    window.location.href = submitUrl.value
  }, 1000)
}
</script>

<template>
  <!-- The end for someone who came from Prolific: the experiment's last words, the completion code,
       and the way back to Prolific, on one card like the other end screens. -->
  <div class="bb-finish">
    <div class="bb-finish__card">
      <div class="bb-finish__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="26" height="26"><path d="M5 12.5l4.2 4.2L19 7" /></svg>
      </div>
      <div v-if="player.text" class="bb-finish__text bb-prose" v-html="player.text" />
      <div v-if="data.message" class="bb-finish__text bb-prose" v-html="data.message" />
      <div class="bb-finish__code">
        <span class="bb-finish__label">Your completion code</span>
        <code>{{ data.completionCode }}</code>
      </div>
      <label v-if="!data.noFeedback" class="bb-finish__feedback">
        <span class="bb-finish__label">Comments for the researcher (optional)</span>
        <textarea v-model="feedback" rows="3" />
      </label>
      <button type="button" class="bb-finish__btn" :disabled="sending" @click="submit">
        {{ sending ? 'Returning to Prolific…' : 'Finish and return to Prolific' }}
      </button>
      <p class="bb-finish__note">
        If nothing happens, open <a :href="submitUrl">{{ submitUrl }}</a>, or enter the code above on Prolific.
      </p>
    </div>
  </div>
</template>

<style>
.bb-finish {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px 16px;
  box-sizing: border-box;
}
.bb-finish__card {
  width: 100%;
  max-width: 520px;
  padding: 36px 32px 28px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04), 0 10px 30px rgba(15, 23, 42, 0.06);
  text-align: center;
  box-sizing: border-box;
}
.bb-finish__icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin: 0 auto 18px;
  border-radius: 50%;
  background: #ecfdf5;
  box-shadow: 0 0 0 8px #f0fdf4;
}
.bb-finish__icon path {
  fill: none;
  stroke: #059669;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.bb-finish__text { margin-bottom: 14px; color: #334155; font-size: 15.5px; line-height: 1.6; }
.bb-finish__text :last-child { margin-bottom: 0; }
.bb-finish__label { display: block; margin-bottom: 6px; font-size: 13px; font-weight: 600; color: #64748b; }
.bb-finish__code { margin: 18px 0; padding: 14px; border-radius: 14px; background: #eef2ff; }
.bb-finish__code code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #312e81;
  background: none;
  user-select: all;
}
.bb-finish__feedback { display: block; margin: 0 0 18px; text-align: left; }
.bb-finish__feedback textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border: 1px solid #d8dce6;
  border-radius: 12px;
  font: inherit;
  font-size: 15px;
  resize: vertical;
}
.bb-finish__feedback textarea:focus { outline: none; border-color: #4f46e5; box-shadow: 0 0 0 4px rgba(79, 70, 229, 0.15); }
.bb-finish__btn {
  width: 100%;
  min-height: 48px;
  border: 0;
  border-radius: 12px;
  background: #4f46e5;
  color: #fff;
  font: inherit;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(79, 70, 229, 0.25);
}
.bb-finish__btn:hover { background: #4338ca; }
.bb-finish__btn:disabled { opacity: 0.7; cursor: default; }
.bb-finish__btn:focus-visible { outline: 3px solid #a5b4fc; outline-offset: 3px; }
.bb-finish__note { margin: 14px 0 0; font-size: 13px; color: #64748b; overflow-wrap: anywhere; }
.bb-finish__note a { color: #4f46e5; }
</style>
