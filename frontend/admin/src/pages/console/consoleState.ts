import { reactive, watch } from 'vue'
import { actions } from '@/store/admin'
import { expectReply, lastOutputId } from './engineReplies'

/**
 * The console's own memory, kept for the whole visit (not only while the page is open):
 *  - the editor buffer. It is seeded once from the server's `currentScript` and then belongs to the
 *    researcher: server pushes never replace it (they did in the old console, §3.2.3), and a draft
 *    is kept in this browser so a reload doesn't lose it;
 *  - the runs made here, with the engine's answers (the output panel keeps only the latest 500
 *    lines of everything, and can be cleared);
 *  - the history of scripts run, saved in this browser.
 */

export interface ConsoleRun {
  id: number
  script: string
  /** How it was started, e.g. "Script", "Lines 3–5", "Snippet: Count people". */
  label: string
  /** The editor line the script starts on, when it came from the editor (to map error lines back). */
  fromLine: number | null
  at: number
  status: 'pending' | 'ok' | 'error' | 'noreply'
  values: string[]
  error: string | null
  doneAt: number | null
}

export interface HistoryItem {
  script: string
  /** last run */
  at: number
  count: number
  /** how the last run went (null while it runs) */
  ok: boolean | null
}

export const HISTORY_CAP = 100
export const RUNS_CAP = 100

export const consoleState = reactive({
  owner: null as string | null,
  buffer: '',
  draftSavedAt: 0,
  runs: [] as ConsoleRun[],
  history: [] as HistoryItem[],
})

let runSeq = 0

// ---- pure helpers ---------------------------------------------------------------------------

/** The editor's first content: this browser's draft when there is one, else the server's copy. */
export function initialBuffer (draft: string | null, serverScript: string | null | undefined) {
  return draft !== null ? draft : (serverScript || '')
}

/** Put a script at the top of the history (the same text again moves up and counts once more). */
export function addToHistory (list: HistoryItem[], script: string, at: number, cap = HISTORY_CAP): HistoryItem[] {
  const key = script.trim()
  const old = list.find(h => h.script.trim() === key)
  const item: HistoryItem = { script, at, count: (old?.count || 0) + 1, ok: null }
  return [item, ...list.filter(h => h !== old)].slice(0, cap)
}

/** Record how the newest run of a script went. */
export function markHistory (list: HistoryItem[], script: string, ok: boolean) {
  const h = list.find(x => x.script.trim() === script.trim())
  if (h) h.ok = ok
}

export function searchHistory (list: HistoryItem[], query: string) {
  const q = query.trim().toLowerCase()
  return q ? list.filter(h => h.script.toLowerCase().includes(q)) : list
}

/** Only well-formed entries survive a read from storage. */
export function sanitizeHistory (data: unknown): HistoryItem[] {
  if (!Array.isArray(data)) return []
  return data
    .filter((h: any) => h && typeof h.script === 'string' && h.script.trim())
    .map((h: any) => ({ script: h.script, at: Number(h.at) || 0, count: Math.max(1, Number(h.count) || 1), ok: typeof h.ok === 'boolean' ? h.ok : null }))
    .slice(0, HISTORY_CAP)
}

// ---- storage (this browser only) --------------------------------------------------------------

const draftKey = (owner: string) => `bb-console-draft:${owner}`
const historyKey = (owner: string) => `bb-console-history:${owner}`

function read (key: string): string | null {
  try { return localStorage.getItem(key) } catch (e) { return null }
}
function write (key: string, value: string) {
  try { localStorage.setItem(key, value); return true } catch (e) { return false }
}

/** Load this account's buffer and history once per visit (again only when the account changes). */
export function seedConsole (owner: string, serverScript: string | null | undefined) {
  if (consoleState.owner === owner) return
  consoleState.owner = owner
  consoleState.buffer = initialBuffer(read(draftKey(owner)), serverScript)
  let saved: unknown = null
  try { saved = JSON.parse(read(historyKey(owner)) || 'null') } catch (e) { saved = null }
  consoleState.history = sanitizeHistory(saved)
  consoleState.runs = []
}

export function saveDraftNow () {
  if (!consoleState.owner) return false
  const ok = write(draftKey(consoleState.owner), consoleState.buffer)
  if (ok) consoleState.draftSavedAt = Date.now()
  return ok
}

let draftTimer: ReturnType<typeof setTimeout> | null = null
watch(() => consoleState.buffer, () => {
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(saveDraftNow, 400)
})
watch(() => consoleState.history, (h) => {
  if (consoleState.owner) write(historyKey(consoleState.owner), JSON.stringify(h))
}, { deep: true })

// ---- running ----------------------------------------------------------------------------------

/**
 * Send a script to the engine and follow its answer. Null when it could not be sent (the connection
 * is down): nothing is run, logged or kept in the history then.
 */
export function runConsoleScript (script: string, meta: { label: string, fromLine: number | null }): ConsoleRun | null {
  const requestId = actions.runScript(script)
  if (!requestId) return null
  // runScript logs the script itself as the newest output line; the answer comes after it
  const afterId = lastOutputId()
  const run = reactive<ConsoleRun>({
    id: ++runSeq,
    script,
    label: meta.label,
    fromLine: meta.fromLine,
    at: Date.now(),
    status: 'pending',
    values: [],
    error: null,
    doneAt: null,
  })
  consoleState.runs.push(run)
  if (consoleState.runs.length > RUNS_CAP) consoleState.runs.splice(0, consoleState.runs.length - RUNS_CAP)
  consoleState.history = addToHistory(consoleState.history, script, run.at)

  expectReply(script, afterId, { request: 'SendScript', requestId }).then(reply => {
    run.doneAt = Date.now()
    if (!reply) {
      run.status = 'noreply'
      return
    }
    run.status = reply.ok ? 'ok' : 'error'
    run.values = reply.values
    run.error = reply.error
    markHistory(consoleState.history, script, reply.ok)
  })
  return run
}

export function clearRuns () {
  consoleState.runs = []
}

export function removeFromHistory (script: string) {
  consoleState.history = consoleState.history.filter(h => h.script !== script)
}

export function clearHistory () {
  consoleState.history = []
}
