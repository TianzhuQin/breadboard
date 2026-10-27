import { watch } from 'vue'
import { admin } from '@/store/admin'
import type { OutputEntry } from '@/store/types'

/**
 * Pairs the engine's answers with the scripts this console (and the Steps page) sent.
 *
 * The server answers every SendScript / SendStep with exactly one frame, in order
 * (ScriptBoard.processScript): {output: "<script.trim()>\n\n==><result>"} on success, which names
 * its script, or {error: "Caught error: …"}, which doesn't. OnJoin/OnLeave output and other pages'
 * runs share the same stream (admin.output), so a success is matched by its echo and an error goes
 * to the oldest script still waiting for an answer.
 */
export interface EngineReply {
  ok: boolean
  /** The values after "==>": one per item of a Gremlin pipeline, none for a null result. */
  values: string[]
  /** The error text as the server sent it (with "Caught error: "), or null. */
  error: string | null
  at: number
}

interface Waiter {
  script: string
  afterId: number
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

/** Which waiting script (index) an output entry answers, or -1. */
export function matchReply (waiters: { script: string, afterId: number }[], entry: Pick<OutputEntry, 'id' | 'kind' | 'text'>): number {
  if (entry.kind === 'output') return waiters.findIndex(w => entry.id > w.afterId && splitValues(entry.text, w.script) !== null)
  if (entry.kind === 'error') return waiters.findIndex(w => entry.id > w.afterId)
  return -1
}

/** The id of the newest output entry (0 when there is none). */
export function lastOutputId () {
  const out = admin.output
  return out.length ? out[out.length - 1].id : 0
}

const waiters: Waiter[] = []
let seen = lastOutputId()

function settle (i: number, reply: EngineReply | null) {
  const [w] = waiters.splice(i, 1)
  if (w.timer) clearTimeout(w.timer)
  w.done(reply)
}

watch(lastOutputId, () => {
  // the entries that arrived since last time, oldest first
  const fresh: OutputEntry[] = []
  for (let i = admin.output.length - 1; i >= 0 && admin.output[i].id > seen; i--) fresh.unshift(admin.output[i])
  if (fresh.length) seen = fresh[fresh.length - 1].id
  for (const e of fresh) {
    if (!waiters.length) break
    const i = matchReply(waiters, e)
    if (i < 0) continue
    const values = e.kind === 'output' ? splitValues(e.text, waiters[i].script) || [] : []
    settle(i, { ok: e.kind === 'output', values, error: e.kind === 'error' ? e.text : null, at: Date.now() })
  }
})

/**
 * Wait for the engine's answer to `script`, sent after output entry `afterId`. Resolves null when
 * nothing came within `timeoutMs` (the connection dropped, or the engine is stuck).
 */
export function expectReply (script: string, afterId: number, timeoutMs = 90000): Promise<EngineReply | null> {
  return new Promise(resolve => {
    const w: Waiter = { script, afterId, done: resolve, timer: null }
    w.timer = setTimeout(() => {
      const i = waiters.indexOf(w)
      if (i >= 0) settle(i, null)
    }, timeoutMs)
    waiters.push(w)
  })
}

/** For tests: forget every waiting script. */
export function __resetReplies () {
  while (waiters.length) settle(0, null)
  seen = lastOutputId()
}
