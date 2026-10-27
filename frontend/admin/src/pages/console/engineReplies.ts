import { watch } from 'vue'
import { admin, connectionState } from '@/store/admin'
import type { OutputEntry } from '@/store/types'

/**
 * Pairs the engine's answers with the scripts this console (and the Steps page) sent.
 *
 * The server answers every SendScript / SendStep with exactly one frame, in order
 * (ScriptBoard.processScript): {output: "<script.trim()>\n\n==><result>"} on success, which names
 * its script, or {error: "Caught error: …"}, which doesn't. Newer servers tag both with the request
 * they answer ("request": "SendScript" / "SendStep", which the store keeps as the entry's source)
 * and tag the frames of OnJoin/OnLeave and of steps loading with their own "source". OnJoin/OnLeave
 * output, other pages' runs and refusals of other requests share the same stream (admin.output), so
 * a success is matched by its echo, and an error goes to the oldest script still waiting for an
 * answer (of its request, when tagged). Older servers tag nothing: an untagged error is taken as an
 * answer there, but not once the server has shown that it tags its answers.
 *
 * Newest of all, each request carries a "requestId" and the server echoes it on its answer: an
 * answer with one belongs to that script only, wherever it stands in line. Matching by echo and
 * place in line stays for servers that don't echo it.
 *
 * Errors are paired by their place in line, so the line must hold only scripts whose answer can
 * still come: a script that timed out keeps its place (its late answer is not taken for the next
 * script's), and when the connection drops every waiting script gets null at once (their answers
 * went to the old socket and never come).
 */
export interface EngineReply {
  ok: boolean
  /** The values after "==>": one per item of a Gremlin pipeline, none for a null result. */
  values: string[]
  /** The error text as the server sent it (with "Caught error: "), or null. */
  error: string | null
  at: number
}

/** The requests a script is sent with (RunStep is answered as SendStep). */
export type ScriptRequest = 'SendScript' | 'SendStep'

/** A script waiting for its answer, as matchReply sees it. */
export interface Waiting {
  script: string
  afterId: number
  /** What it was sent with; any script request's answer fits when left out. */
  request?: ScriptRequest
  /** The id it was sent with (actions.runScript / runStep), which newer servers echo on the answer. */
  requestId?: string
}

/** How a script was sent: its request, and the id the request carried. */
export interface Sent {
  request: ScriptRequest
  requestId?: string | null
}

interface Waiter extends Waiting {
  done: (reply: EngineReply | null) => void
  timer: ReturnType<typeof setTimeout> | null
}

export function echoPrefix (script: string) {
  return script.trim() + '\n\n==>'
}

/** The result values of a success frame for `script`, or null when the frame is not its echo. */
export function splitValues (output: string, script: string): string[] | null {
  const prefix = echoPrefix(script)
  if (!output.startsWith(prefix)) return null
  const rest = output.slice(prefix.length)
  return rest.length ? rest.split('\n==>') : []
}

/** The requests whose frames can answer a waiting script. */
const SCRIPT_REQUESTS = new Set(['SendScript', 'SendStep'])
/** Sources only a server that tags its script answers sends. */
const TAGGED_SOURCES = new Set(['OnJoinStep', 'OnLeaveStep', 'Steps'])

/**
 * Whether an output entry shows that the server tags its script answers: a script's own frame (its
 * echo, or "Caught error: …") with a tag, or a frame from OnJoin/OnLeave or the steps loading.
 * (A tagged refusal is no sign: {error, request} refusals came before the tags.)
 */
export function showsTags (entry: Pick<OutputEntry, 'kind' | 'text' | 'source'>): boolean {
  if (!entry.source || (entry.kind !== 'output' && entry.kind !== 'error')) return false
  if (TAGGED_SOURCES.has(entry.source)) return true
  return entry.kind === 'output' || entry.text.startsWith('Caught error:')
}

/**
 * Which waiting script (index) an output entry answers, or -1. A frame tagged with another source
 * (a refused LaunchGame, DeleteInstance, SelectExperiment …, OnJoinStep / OnLeaveStep, the steps
 * loading) is not an answer, so it never settles a run; a tagged answer only settles a script sent
 * with the same request. With `tagged` (the server tags its answers), an untagged frame is no
 * answer either (it comes from the AMT timer starting the game, a deleted experiment …).
 *
 * An answer that carries a request id is the answer of the script sent with that id, and of no
 * other script that was sent with an id.
 */
export function matchReply (waiters: Waiting[], entry: Pick<OutputEntry, 'id' | 'kind' | 'text' | 'source' | 'requestId'>, tagged = false): number {
  if (entry.kind !== 'output' && entry.kind !== 'error') return -1
  const id = entry.requestId
  if (id) {
    const own = waiters.findIndex(w => w.requestId === id)
    if (own >= 0) return own
  }
  const source = entry.source
  if (source ? !SCRIPT_REQUESTS.has(source) : tagged) return -1
  const fits = (w: Waiting) => entry.id > w.afterId && (!source || !w.request || w.request === source) && !(id && w.requestId)
  if (entry.kind === 'output') return waiters.findIndex(w => fits(w) && splitValues(entry.text, w.script) !== null)
  return waiters.findIndex(fits)
}

/** The id of the newest output entry (0 when there is none). */
export function lastOutputId () {
  const out = admin.output
  return out.length ? out[out.length - 1].id : 0
}

const waiters: Waiter[] = []
let seen = lastOutputId()
/** Whether this server tags its script answers (see showsTags). */
let serverTags = false

function settle (i: number, reply: EngineReply | null) {
  const [w] = waiters.splice(i, 1)
  if (w.timer) clearTimeout(w.timer)
  w.done(reply)
}

/** Pair the output entries that arrived since last time with the scripts waiting for them. */
function takeFresh () {
  // the entries that arrived since last time, oldest first
  const fresh: OutputEntry[] = []
  for (let i = admin.output.length - 1; i >= 0 && admin.output[i].id > seen; i--) fresh.unshift(admin.output[i])
  if (fresh.length) seen = fresh[fresh.length - 1].id
  for (const e of fresh) {
    if (!serverTags && showsTags(e)) serverTags = true
    if (!waiters.length) continue
    const i = matchReply(waiters, e, serverTags)
    if (i < 0) continue
    const values = e.kind === 'output' ? splitValues(e.text, waiters[i].script) || [] : []
    settle(i, { ok: e.kind === 'output', values, error: e.kind === 'error' ? e.text : null, at: Date.now() })
  }
}
watch(lastOutputId, takeFresh)

// The connection dropped (or reconnects with another sign-in): the answers to the scripts sent
// before went to the old socket and never come. Give them null now ("No answer"), so they no longer
// stand in line for the next script's error.
watch(connectionState, (s) => {
  if (s === 'open' || !waiters.length) return
  // (answers that arrived just before the drop still count)
  takeFresh()
  while (waiters.length) settle(0, null)
}, { flush: 'sync' })

/**
 * Wait for the engine's answer to `script`, sent after output entry `afterId` with `sent` (its
 * request, or the request and the id it carried). Resolves null when nothing came within
 * `timeoutMs` (the engine is busy or stuck), and at once when the connection drops.
 */
export function expectReply (script: string, afterId: number, sent?: ScriptRequest | Sent, timeoutMs = 90000): Promise<EngineReply | null> {
  const how: Sent | undefined = typeof sent === 'string' ? { request: sent } : sent
  return new Promise(resolve => {
    const w: Waiter = { script, afterId, request: how?.request, requestId: how?.requestId || undefined, done: resolve, timer: null }
    w.timer = setTimeout(() => {
      w.timer = null
      if (!waiters.includes(w)) return
      // A slow script still answers later: it keeps its place in line until then, so that answer
      // is not taken for the next script's. (A dropped connection clears it, see above.)
      w.done = () => {}
      resolve(null)
    }, timeoutMs)
    waiters.push(w)
  })
}

/** For tests: forget every waiting script, and what the server was seen to tag. */
export function __resetReplies () {
  while (waiters.length) settle(0, null)
  seen = lastOutputId()
  serverTags = false
}
