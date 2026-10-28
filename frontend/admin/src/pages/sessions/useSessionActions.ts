import { computed, reactive } from 'vue'
import { admin, actions, connectionState } from '@/store/admin'
import { REBUILD_PAUSES_AUTOPILOT, engineUse } from '@/store/engineGuard'
import { http, saveText } from '@/lib/http'
import { confirm, notify, toast } from '@/ui'
import { openOutput } from '@/shell/output'
import type { Experiment, Instance } from '@/store/types'
import { STOP_PAUSES_AUTOPILOT, autopilotNow, stopPausesAutopilot } from '../autopilot/autopilot'
import { canDelete, canResume, canStop, csvDataRows, describeError, safeFileName, waitUntil } from './sessionData'

type Job = 'resume' | 'stop' | 'delete' | 'data' | 'list'

/**
 * What the Sessions page can do to a session, each with its confirmation, progress and outcome.
 * The server answers lifecycle actions only with a new User JSON (and sometimes not at all, e.g.
 * a refused delete, §3.3.7), so each action checks that the state really changed and says so in
 * words when it did not.
 */
export function useSessionActions () {
  const jobs = reactive<Record<string, boolean>>({})
  const key = (job: Job, id: number | string) => `${job}:${id}`
  const isBusy = (job: Job, id: number | string) => !!jobs[key(job, id)]
  /** One engine: while it restarts or a session stops, other lifecycle buttons wait. */
  const lifecycleBusy = computed(() => Object.keys(jobs).some(k => jobs[k] && /^(resume|stop|delete):/.test(k)))

  async function run<T> (job: Job, id: number | string, fn: () => Promise<T>): Promise<T | undefined> {
    const k = key(job, id)
    if (jobs[k]) return undefined
    jobs[k] = true
    try {
      return await fn()
    } finally {
      delete jobs[k]
    }
  }

  function connected (): boolean {
    if (connectionState.value === 'open') return true
    notify.error('Not connected to the server', 'Wait until the connection is back (see the top bar), then try again.')
    return false
  }

  const showOutput = { label: 'Show output', run: () => { openOutput.value = true } }
  const find = (id: number) => (admin.experiment?.instances || []).find(i => i.id === id)

  /** Make a session the current one and (re)start it: SelectInstance. */
  async function resume (s: Instance): Promise<boolean> {
    if (!canResume(s) || !connected()) return false
    // the engine is shared: who it would disconnect, whoever's session they are in ('' when nobody)
    const { who, pausesAutopilot, endsSession } = await engineUse(s.id)
    const verb = s.status === 'STOPPED' ? 'Resume' : 'Make current'
    const lines = [
      `The script engine restarts with this session's launch parameters, and the network starts empty: people who were in it join again with its links, and its game starts again from the beginning. The data recorded so far is kept, and a sessionResumed event marks where the new run begins.`,
    ]
    if (who) {
      lines.push(endsSession
        ? `${who} Every researcher on this server and the autopilot share the script engine, so restarting it ends the session it is running, and everyone in the network is disconnected.`
        : `${who} Restarting the engine disconnects everyone in the network; they can join again with this session's links.`)
    }
    if (pausesAutopilot) lines.push(REBUILD_PAUSES_AUTOPILOT)
    const ok = await confirm({
      title: `${verb} “${s.name}”?`,
      message: lines.join('\n\n'),
      confirmLabel: s.status === 'STOPPED' ? 'Resume session' : 'Make current',
      danger: !!who,
    })
    if (!ok) return false
    return !!await run('resume', s.id, async () => {
      const askedAt = Date.now()
      await actions.selectInstance(s.id)
      const refused = () => admin.requestError?.request === 'SelectInstance' && admin.requestError.at >= askedAt
      // The first User JSON can predate the restart; wait for the session to really run.
      const started = await waitUntil(() => refused() || (admin.instance?.id === s.id && admin.instance.status === 'RUNNING'), 60000)
      if (refused()) {
        toast('error', `Could not resume “${s.name}”`, admin.requestError!.text, { action: showOutput })
        return false
      }
      if (started) {
        notify.success(`“${s.name}” is running`, 'It is now the current session. Participants can join with its link.')
        return true
      }
      if (admin.instance?.id === s.id) {
        toast('error', `“${s.name}” is current but did not start`, 'The engine may have hit an error while restarting it. The output panel shows what happened.', { action: showOutput })
      } else {
        toast('error', `Could not resume “${s.name}”`, 'The server did not confirm the restart within a minute. The output panel may show why.', { action: showOutput })
      }
      return false
    })
  }

  /** StopGame: the session stops taking players; it can be resumed later. */
  async function stop (s: Instance): Promise<boolean> {
    if (!canStop(s)) return false
    if (!connected()) return false
    // Stopping the autopilot's session pauses the autopilot (the server's Autopilot.sessionStopped).
    // The sessions listed here are the selected experiment's.
    const pausesAutopilot = stopPausesAutopilot(await autopilotNow(), { id: s.id, experimentId: admin.experiment?.id }, admin.user?.engine?.sessionId)
    const ok = await confirm({
      title: `Stop “${s.name}”?`,
      message: (admin.instance?.id === s.id
        ? 'Nobody new can join, its game stops and its participants see a page saying the session was stopped. Everything recorded so far is kept.'
        : 'Nobody new can join it. Everything recorded so far is kept, and your current session is not affected.') +
        (pausesAutopilot ? `\n\n${STOP_PAUSES_AUTOPILOT}` : '') +
        '\n\nYou can resume it later from this page; its game then starts again from the beginning.',
      confirmLabel: 'Stop session',
      danger: true,
    })
    if (!ok) return false
    return !!await run('stop', s.id, async () => {
      await actions.stopInstance(s.id)
      const stopped = await waitUntil(() => {
        const now = find(s.id)
        return !now || !canStop(now)
      }, 15000)
      if (stopped) {
        notify.success(`Stopped “${s.name}”`, pausesAutopilot
          ? 'The autopilot is paused and opens no new session until you press Resume on the Autopilot page. Resume this session here whenever you want to continue.'
          : 'Resume it here whenever you want to continue.')
        return true
      }
      notify.error(`“${s.name}” is still running`, 'The server did not confirm the stop. Try again, or check the output panel.')
      return false
    })
  }

  /** DeleteInstance, after typing the name: the session and all its data, for good. */
  async function remove (s: Instance): Promise<boolean> {
    if (!canDelete(s)) {
      notify.warning('Stop the session first', 'Only stopped or finished sessions can be deleted.')
      return false
    }
    if (!connected()) return false
    const name = (s.name || '').trim() || `session ${s.id}`
    const ok = await confirm({
      title: `Delete “${s.name}” and its data?`,
      message: 'This permanently deletes the session, its launch parameters and every event recorded in it. Download its data first if you might need it.\n\nThis can’t be undone.',
      confirmLabel: 'Delete session',
      danger: true,
      typeToConfirm: name,
    })
    if (!ok) return false
    return !!await run('delete', s.id, async () => {
      const reply = await actions.deleteInstance(s.id)
      // The server ignores a delete it refuses and just sends the state again.
      const gone = await waitUntil(() => !find(s.id), reply ? 2000 : 1)
      if (gone) {
        notify.success(`Deleted “${s.name}”`)
        return true
      }
      const now = find(s.id)
      notify.error(`“${s.name}” was not deleted`, reply
        ? `The server refused${now ? ` (the session is ${now.status.toLowerCase()})` : ''}. Only stopped or finished sessions can be deleted.`
        : 'The server did not answer. Check the connection and try again.')
      return false
    })
  }

  /** The session's events as the server's CSV (one row per data item). */
  async function downloadData (s: Pick<Instance, 'id' | 'name'>): Promise<boolean> {
    return !!await run('data', s.id, async () => {
      try {
        const text = await http.get<string>(`/csv/data/${s.id}`, { raw: true })
        const file = `${safeFileName(s.name)}_${s.id}.csv`
        saveText(file, text)
        const rows = csvDataRows(text)
        if (rows) notify.success(`Downloaded ${file}`, `${rows.toLocaleString()} data ${rows === 1 ? 'row' : 'rows'}.`)
        else notify.info(`Downloaded ${file}`, 'No events with data have been recorded in this session yet, so the file has only the header row.')
        return true
      } catch (e) {
        notify.error(`Could not download the data of “${s.name}”`, describeError(e))
        return false
      }
    })
  }

  /** Every session of the experiment with its launch parameters, as the server's CSV. */
  async function downloadList (exp: Pick<Experiment, 'id' | 'name'>): Promise<boolean> {
    return !!await run('list', exp.id, async () => {
      try {
        const text = await http.get<string>(`/csv/instances/${exp.id}`, { raw: true })
        const file = `${safeFileName(exp.name, 'sessions')}.csv`
        saveText(file, text)
        notify.success(`Downloaded ${file}`, 'One row per session, with its status, start time (UTC) and launch parameters.')
        return true
      } catch (e) {
        notify.error('Could not download the list of sessions', describeError(e))
        return false
      }
    })
  }

  return { isBusy, lifecycleBusy, resume, stop, remove, downloadData, downloadList }
}
