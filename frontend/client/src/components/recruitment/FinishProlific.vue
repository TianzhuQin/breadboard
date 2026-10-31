<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { PlayerData, participantToken } from '@human-nature-lab/breadboard-core'

const props = defineProps<{
  player: PlayerData
}>()

const data = computed(() => props.player._system?.recruitment)

const submitUrl = computed(() => {
  return `https://app.prolific.com/submissions/complete?cc=${data.value?.completionCode}`
})

// How long the comment may take to save, from the click, before the screen says it couldn't be (ms):
// the request for a comment token it may have to make first included.
const SAVE_WAIT_MS = 10000
// How long a request for a comment token may go unanswered before the screen stops it (ms): see
// commentToken. At Finish, the comment then goes with the seat's token alone.
const TOKEN_WAIT_MS = 4000
// How often the screen asks for a new comment token while it is open (ms): see commentToken.
const TOKEN_REFRESH_MS = 60 * 60 * 1000

/** The seat's token, as the page's own requests carry it, with what the server reads as JSON. */
function headers (): Record<string, string> {
  const h: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = participantToken()
  if (token) h['X-Breadboard-Token'] = token
  return h
}

/**
 * The token the comment is sent with ('comment', next to the page's own address: GET, with the seat's
 * token), asked for as this screen appears. The server keeps the comment with it for a week, also once
 * it has forgotten the seat's token (ten more sessions have ended since, or it restarted), which used
 * to refuse the comment. It is asked for again every hour while the screen is open, and the server
 * gives a new one for as long as it knows the seat, which it forgets only once the session has ended:
 * the last one is good until about a week after that, however long the session went on after this
 * screen appeared. Null when the server gives none (an older one, a researcher's preview, the network
 * down, no answer within TOKEN_WAIT_MS: then asked for again as the comment is sent). A request that
 * gets no answer in that time is stopped: Finish would wait for it, and one that never answers (a hung
 * server, a connection that died without a word) would hold one of the few connections the browser
 * opens to the server.
 */
let commentToken: string | null = null
async function getCommentToken (): Promise<string | null> {
  const stop = typeof AbortController === 'function' ? new AbortController() : null
  let timer: ReturnType<typeof setTimeout> | undefined
  const ask = async (): Promise<string | null> => {
    const res = await fetch('comment', { headers: headers(), credentials: 'same-origin', signal: stop ? stop.signal : undefined })
    if (!res || res.status !== 200) return null
    const body = await res.json()
    return body && typeof body.token === 'string' && body.token ? body.token : null
  }
  try {
    return await Promise.race([
      ask(),
      new Promise<null>(resolve => {
        timer = setTimeout(() => {
          if (stop) stop.abort()
          resolve(null)
        }, TOKEN_WAIT_MS)
      }),
    ])
  } catch (e) {
    return null
  } finally {
    clearTimeout(timer)
  }
}
/** Asks for a new comment token, and keeps the one there is when the server gives none. */
async function renewCommentToken () {
  const newer = await getCommentToken()
  if (newer) commentToken = newer
}
let refresh: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  if (!data.value || data.value.noFeedback) return
  refresh = setInterval(renewCommentToken, TOKEN_REFRESH_MS)
  await renewCommentToken()
})
onBeforeUnmount(() => clearInterval(refresh))

/**
 * The comment goes to the server on its own ('comment', POST, with the seat's token and the comment
 * token), which keeps it in the participant's session's data (participantComment): this screen comes
 * once the game is over, when the page's live connection may be closed and the server may run the
 * next session already. Sent through the game only, it was lost. Without a comment token, one is
 * asked for first; when none comes (within TOKEN_WAIT_MS), the comment goes with the seat's token
 * alone, which the server takes for as long as it knows the seat. True once the server has it; false
 * when it doesn't (refused, no network, or no answer within SAVE_WAIT_MS of the click, that request
 * for a token included).
 */
async function saveComment (comment: string): Promise<boolean> {
  const send = async (): Promise<boolean> => {
    if (!commentToken) await renewCommentToken()
    const body: Record<string, string> = { text: comment }
    if (commentToken) body.token = commentToken
    const res = await fetch('comment', { method: 'POST', headers: headers(), credentials: 'same-origin', body: JSON.stringify(body) })
    return !!res && res.status >= 200 && res.status < 300
  }
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      send(),
      new Promise<boolean>(resolve => { timer = setTimeout(() => resolve(false), SAVE_WAIT_MS) }),
    ])
  } catch (e) {
    return false
  } finally {
    clearTimeout(timer)
  }
}

const feedback = ref('')
/**
 * 'saving' the comment, 'leaving' for Prolific, or the comment 'failed' to save: the screen says so,
 * and goes to Prolific only when the participant asks (it used to go all the same, and the comment
 * was lost without anyone knowing).
 */
const state = ref<'ready' | 'saving' | 'leaving' | 'failed'>('ready')
const busy = computed(() => state.value === 'saving' || state.value === 'leaving')
let toldTheGame = false

function leave (started: number) {
  state.value = 'leaving'
  setTimeout(() => {
    window.location.href = submitUrl.value
  }, Math.max(0, 1000 - (Date.now() - started)))
}

async function submit () {
  const started = Date.now()
  // (still sent to the game as well, once, for an experiment's own code that listens for it)
  if (!toldTheGame) {
    toldTheGame = true
    try {
      Breadboard.send('exiting', { feedback: feedback.value })
    } catch (e) {}
  }
  const comment = feedback.value.trim()
  if (comment) {
    state.value = 'saving'
    if (!(await saveComment(comment))) {
      state.value = 'failed'
      return
    }
  }
  leave(started)
}

function leaveWithoutIt () {
  leave(Date.now())
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
      <p v-if="state === 'failed'" class="bb-finish__failed" role="alert">
        Your comment couldn’t be saved, so the researcher won’t get it this way. Try again, or copy it and
        send it to the researcher through Prolific’s messages.
      </p>
      <button type="button" class="bb-finish__btn" :disabled="busy" @click="submit">
        {{ state === 'saving' ? 'Saving your comment…' : state === 'leaving' ? 'Returning to Prolific…' : state === 'failed' ? 'Try again' : 'Finish and return to Prolific' }}
      </button>
      <button v-if="state === 'failed'" type="button" class="bb-finish__btn bb-finish__btn--quiet" @click="leaveWithoutIt">
        Return to Prolific without it
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
  /* (over Vuetify's grey box for code) */
  background: none !important;
  box-shadow: none !important;
  padding: 0 !important;
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
.bb-finish__btn--quiet { margin-top: 10px; background: #fff; color: #4338ca; border: 1px solid #c7d2fe; box-shadow: none; }
.bb-finish__btn--quiet:hover { background: #eef2ff; }
.bb-finish__failed { margin: 0 0 14px; padding: 10px 12px; border-radius: 12px; background: #fff7ed; color: #9a3412; font-size: 14px; line-height: 1.5; text-align: left; }
.bb-finish__btn:disabled { opacity: 0.7; cursor: default; }
.bb-finish__btn:focus-visible { outline: 3px solid #a5b4fc; outline-offset: 3px; }
.bb-finish__note { margin: 14px 0 0; font-size: 13px; color: #64748b; overflow-wrap: anywhere; }
.bb-finish__note a { color: #4f46e5; }
</style>
