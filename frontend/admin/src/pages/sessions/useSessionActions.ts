import { computed, reactive } from 'vue'
import { admin, actions, connectionState } from '@/store/admin'
import { http, saveText } from '@/lib/http'
import { confirm, notify, toast } from '@/ui'
import { openOutput } from '@/shell/output'
import type { Experiment, Instance } from '@/store/types'
import { canDelete, canResume, canStop, csvDataRows, describeError, safeFileName, waitUntil } from './sessionData'
import { runningSessions, sessionNames } from './runningSessions'

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
    // the engine is shared, so a session someone else (or the autopilot) runs ends too
    const others = await runningSessions(s.id)
    const mine = others.find(o => o.mine)
    const rest = others.filter(o => !o.mine)
    const verb = s.status === 'STOPPED' ? 'Resume' : 'Make current'
    const lines = [
      `The script engine restarts with this session's launch parameters, and the network starts empty: people who were in it have to join again (once its game has started, nobody can).`,
    ]
    if (mine) lines.push(`“${mine.name}” is running now. It will be stopped, and everyone in it is disconnected.`)
    if (rest.length) {
      lines.push(`${sessionNames(rest)} ${rest.length === 1 ? 'appears' : 'appear'} to be running${mine ? ' as well' : ''}. ` +
        'The script engine is shared by every account and the autopilot, so restarting it ends the session it is running, and everyone in it is disconnected.')
    }
    const ok = await confirm({
      title: `${verb} “${s.name}”?`,
      message: lines.join('\n\n'),
      confirmLabel: s.status === 'STOPPED' ? 'Resume session' : 'Make current',
      danger: others.length > 0,
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
    const ok = await confirm({
      title: `Stop “${s.name}”?`,
      message: (admin.instance?.id === s.id
        ? 'Nobody new can join, and it stops being the current session. People already on the page stay connected until the engine reloads, and everything recorded so far is kept.'
        : 'Nobody new can join it. Everything recorded so far is kept, and your current session is not affected.') + '\n\nYou can resume it later from this page.',
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
        notify.success(`Stopped “${s.name}”`, 'Resume it here whenever you want to continue.')
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
