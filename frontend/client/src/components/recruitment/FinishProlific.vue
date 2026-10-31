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

// How long the comment may take to save, from the click, before the screen says it isn't confirmed yet
// (ms): the request for a comment token it may have to make first included. Its request goes on.
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
// (once the screen has gone, a save that ends after that changes nothing: see send)
let gone = false
onMounted(async () => {
  if (!data.value || data.value.noFeedback) return
  refresh = setInterval(renewCommentToken, TOKEN_REFRESH_MS)
  await renewCommentToken()
})
onBeforeUnmount(() => {
  gone = true
  clearInterval(refresh)
})

const feedback = ref('')
/**
 * 'saving' the comment, 'leaving' for Prolific, or what kept the comment from being saved: the server
 * 'refused' it, it is 'lost' for all the page can tell, or no answer has come in time ('unconfirmed';
 * see Sent). The screen says so, and goes to Prolific only when the participant asks (it used to go all
 * the same, and the comment was lost without anyone knowing), or, for a comment not confirmed yet, once
 * the server has it after all.
 */
const state = ref<'ready' | 'saving' | 'leaving' | 'refused' | 'lost' | 'unconfirmed'>('ready')
const busy = computed(() => state.value === 'saving' || state.value === 'leaving')
const notSaved = computed(() => state.value === 'refused' || state.value === 'lost' || state.value === 'unconfirmed')
let toldTheGame = false

/**
 * What became of one request with the comment: the server 'saved' it, or 'refused' it (an answer
 * 4xx: it keeps nothing then), or it is 'lost' for all the page can tell (no answer at all, or a
 * server error): the server may have kept it before the connection dropped, or before a proxy in front
 * of it gave up waiting for it, so the screen doesn't say that the researcher won't get it.
 */
type Sent = 'saved' | 'refused' | 'lost'

/**
 * The comment last sent and its request (send), until the server's answer comes. Kept when no answer
 * comes within SAVE_WAIT_MS: the server may have the comment already, its answer only late (a slow
 * network, a server busy for a while). Try again waits for this request rather than sending the
 * comment a second time, which the session's data would then have twice, and sends it again only once
 * this one has failed; and the screen goes on to Prolific by itself once the server has it.
 */
let underway: { comment: string, sent: Promise<Sent> } | null = null

/**
 * Sends the comment to the server on its own ('comment', POST, with the seat's token and the comment
 * token), which keeps it in the participant's session's data (participantComment): this screen comes
 * once the game is over, when the page's live connection may be closed and the server may run the
 * next session already. Sent through the game only, it was lost. Without a comment token, one is
 * asked for first; when none comes (within TOKEN_WAIT_MS), the comment goes with the seat's token
 * alone, which the server takes for as long as it knows the seat. The server keeps the same comment
 * from the same participant once, so sending it again is safe.
 */
function send (comment: string): Promise<Sent> {
  const sent = (async (): Promise<Sent> => {
    try {
      if (!commentToken) await renewCommentToken()
      const body: Record<string, string> = { text: comment }
      if (commentToken) body.token = commentToken
      const res = await fetch('comment', { method: 'POST', headers: headers(), credentials: 'same-origin', body: JSON.stringify(body) })
      if (res && res.status >= 200 && res.status < 300) return 'saved'
      return res && res.status >= 400 && res.status < 500 ? 'refused' : 'lost'
    } catch (e) {
      return 'lost'
    }
  })()
  const attempt = { comment, sent }
  underway = attempt
  sent.then(outcome => {
    if (underway !== attempt) return
    underway = null
    // The answer came after the screen said the comment wasn't confirmed yet, with nothing waiting for
    // it (Try again waits in saveComment). Saved after all, the screen goes on to Prolific, as Finish
    // asked, at once (the click is long past), unless the comment has been changed since: then it is
    // ready to send that. Not saved, the screen says so.
    if (gone || state.value !== 'unconfirmed') return
    if (outcome !== 'saved') state.value = outcome
    else if (feedback.value.trim() === comment) leave(0)
    else state.value = 'ready'
  })
  return sent
}

/**
 * Saves the comment: what became of its request (Sent), or 'unconfirmed' when no answer has come within
 * SAVE_WAIT_MS of the click, the request for a comment token included (that request goes on: see
 * underway). The same comment's request still waiting for its answer is waited for, not sent again; the
 * comment is sent again once that request has failed.
 */
async function saveComment (comment: string): Promise<Sent | 'unconfirmed'> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<'unconfirmed'>(resolve => { timer = setTimeout(() => resolve('unconfirmed'), SAVE_WAIT_MS) })
  try {
    if (underway && underway.comment === comment) {
      const earlier = await Promise.race([underway.sent, late])
      if (earlier === 'saved' || earlier === 'unconfirmed') return earlier
    }
    return await Promise.race([send(comment), late])
  } finally {
    clearTimeout(timer)
  }
}

/** Goes to Prolific a second after the click (started), or at once when that is past. */
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
    const outcome = await saveComment(comment)
    if (outcome !== 'saved') {
      state.value = outcome
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
      <p v-if="state === 'refused'" class="bb-finish__failed" role="alert">
        Your comment couldn’t be saved, so the researcher won’t get it this way. Try again, or copy it and
        send it to the researcher through Prolific’s messages.
      </p>
      <!-- (the server may have it in these two: nothing here says it is lost) -->
      <p v-else-if="state === 'lost'" class="bb-finish__failed" role="alert">
        Your comment may not have reached the researcher: the server couldn’t be reached, or didn’t confirm
        it. Try again, or copy it and send it to the researcher through Prolific’s messages.
      </p>
      <p v-else-if="state === 'unconfirmed'" class="bb-finish__failed" role="alert">
        Your comment hasn’t been confirmed yet: the server hasn’t answered. This page goes on to Prolific
        by itself once the server has saved it. You can also try again, or copy your comment and send it
        to the researcher through Prolific’s messages.
      </p>
      <button type="button" class="bb-finish__btn" :disabled="busy" @click="submit">
        {{ state === 'saving' ? 'Saving your comment…' : state === 'leaving' ? 'Returning to Prolific…' : notSaved ? 'Try again' : 'Finish and return to Prolific' }}
      </button>
      <button v-if="notSaved" type="button" class="bb-finish__btn bb-finish__btn--quiet" @click="leaveWithoutIt">
        {{ state === 'unconfirmed' ? 'Return to Prolific without waiting' : 'Return to Prolific without it' }}
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
