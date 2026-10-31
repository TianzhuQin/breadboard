import { reactive, ref } from 'vue'
import { admin, actions, engineElsewhere, ensureConnected, nextMessage, NOT_CONNECTED } from '@/store/admin'
import { REBUILD_PAUSES_AUTOPILOT, engineUse } from '@/store/engineGuard'
import { http, saveText } from '@/lib/http'
import { confirm, notify, toast } from '@/ui'
import { openOutput } from '@/shell/output'
import type { Instance } from '@/store/types'
import { STOP_PAUSES_AUTOPILOT, autopilotNow, stopPausesAutopilot } from '../autopilot/autopilot'
import { classifyStartReply, engineElsewhereText, isStartReply, safeFileName } from './live'

/**
 * The session controls with their confirmations and outcomes, shared by the page's parts.
 * Every action says what it is about to do, shows progress, and reports how it went in words.
 */

const showOutput = { label: 'Open output', run: () => { openOutput.value = true } }

/** The newest engine error the researcher dismissed on this page (kept while switching pages). */
export const dismissedErrorId = ref(0)

export const busy = reactive({
  start: false,
  stop: false,
  /** id of the session being resumed */
  resume: null as number | null,
  /** id of the session whose data is downloading */
  download: null as number | null,
})

/**
 * RunGame = initStep.start(): everyone waiting is let in and newcomers are turned away. `again`: the
 * game started before, but the engine was reloaded since and no longer runs it.
 */
export async function startGame (ctx: { connected: number, people: number, waitingRoom: boolean, again?: boolean }) {
  if (busy.start) return
  // the server refuses to start a session the engine isn't running (the page disables the button too)
  const elsewhere = engineElsewhere.value
  if (elsewhere) {
    notify.info('The game can\'t be started here', engineElsewhereText(elsewhere))
    return
  }
  if (!ensureConnected()) return
  const who = ctx.people === 0
    ? 'Nobody has joined yet, so the game would start with no one in it.'
    : `${ctx.connected} of ${ctx.people} ${ctx.people === 1 ? 'person is' : 'people are'} connected right now.`
  const ok = await confirm({
    title: 'Start the game?',
    message: [
      who,
      'Everyone who is waiting is let in and the experiment begins (it runs initStep.start()). People who arrive after this are turned away.',
      ctx.waitingRoom ? 'This skips the rest of the waiting room.' : '',
      ctx.again
        ? 'The game started earlier, but the engine was reloaded since then, so this starts it again from the beginning.'
        : 'Once it has started, Start does nothing until the engine is reloaded.',
    ].filter(Boolean).join('\n\n'),
    confirmLabel: 'Start the game',
  })
  if (!ok) return
  busy.start = true
  try {
    // listen before sending so the answer can't slip past
    const reply = nextMessage(isStartReply, 20000)
    if (!actions.startGame()) {
      // the connection dropped while the confirmation was open: nothing was sent
      notify.error(NOT_CONNECTED, 'The game was not started. Try again once the connection is back.')
      return
    }
    const out = classifyStartReply(await reply)
    if (out.kind === 'started') {
      notify.success('The game has started', 'Everyone who was waiting is in. New arrivals are turned away.')
    } else if (out.kind === 'already') {
      notify.info('The game had already started', 'It was running already, so nothing changed.')
    } else if (out.kind === 'error') {
      toast('error', 'The game could not start', out.message, { action: showOutput })
    } else {
      toast('warning', 'No answer from the engine yet', 'The game may still be starting. The output panel shows what the engine says.', { action: showOutput })
    }
  } finally {
    busy.start = false
  }
}

/** StopGame: the session stops taking people in; it can be resumed later. */
export async function stopSession (inst: Instance) {
  if (busy.stop) return
  // marked at once: asking the server about the autopilot can take a few seconds
  busy.stop = true
  try {
    // Stopping the autopilot's session pauses the autopilot (the server's Autopilot.sessionStopped).
    // The session shown here is the selected experiment's.
    const pausesAutopilot = stopPausesAutopilot(await autopilotNow(), { id: inst.id })
    const ok = await confirm({
      title: `Stop “${inst.name}”?`,
      message: [
        'Nobody new can join with its links, and its game stops: its participants see a page saying the session was stopped. Everything recorded so far is kept.',
        pausesAutopilot ? STOP_PAUSES_AUTOPILOT : '',
        'You can resume it later, but its game then starts again from the beginning.',
      ].filter(Boolean).join('\n\n'),
      confirmLabel: 'Stop session',
      danger: true,
    })
    if (!ok) return
    const askedAt = Date.now()
    const m = await actions.stopInstance(inst.id)
    if (!m) {
      // refused, e.g. it had finished meanwhile: the server says so (and its status follows)
      const refusal = admin.requestError
      if (refusal && refusal.request === 'StopGame' && refusal.at >= askedAt) {
        notify.info(`“${inst.name}” was not stopped`, refusal.text)
        return
      }
      notify.error('The server did not confirm the stop', 'Check your connection, then look at Sessions & data to see the session\'s status.')
      return
    }
    toast('success', `Stopped “${inst.name}”`, pausesAutopilot
      ? 'The autopilot is paused and opens no new session until you press Resume on the Autopilot page. Resume this session from this page or from Sessions & data.'
      : 'Resume it from this page or from Sessions & data.', {
      action: { label: 'Resume session', run: () => { void resumeSession(inst) } },
    })
  } finally {
    busy.stop = false
  }
}

/** SelectInstance: make a stopped session current again and restart it (rebuilds the engine). */
export async function resumeSession (inst: Instance) {
  if (busy.resume != null || !ensureConnected()) return
  // marked at once: asking the server who uses the engine can take a few seconds, and the button
  // shows it is working (a second click meanwhile is ignored)
  busy.resume = inst.id
  try {
    const cur = admin.instance
    const other = cur && cur.id !== inst.id && (cur.status === 'RUNNING' || cur.status === 'TESTING') ? cur : null
    // The engine is shared by every account and the autopilot: say whose session and players the
    // restart disconnects ('' when nobody is using it). It names your running session too.
    const { who: users, pausesAutopilot } = await engineUse(inst.id)
    const ok = await confirm({
      title: `Resume “${inst.name}”?`,
      message: [
        users && other ? `${users} “${other.name}” will be stopped.` : users || (other ? `“${other.name}” is running now and will be stopped.` : ''),
        users
          ? 'Resuming restarts the script engine, which every researcher on this server shares: the network is emptied and everyone in it is disconnected, whoever\'s session they are in. People can then join again with this session\'s links.'
          : 'The experiment\'s scripts restart from its steps: the network is emptied and anyone still connected is disconnected. People can then join again with the session\'s links.',
        pausesAutopilot ? REBUILD_PAUSES_AUTOPILOT : '',
        'Its game starts again from the beginning. The data recorded so far is kept, and a sessionResumed event marks where the new run begins.',
      ].filter(Boolean).join('\n\n'),
      confirmLabel: 'Resume session',
      danger: !!users || !!other,
    })
    // (the connection may have dropped while the confirmation was open)
    if (!ok || !ensureConnected()) return
    const running = nextMessage(m => !!m && ((m.request === 'SelectInstance' && typeof m.error === 'string') ||
      ('user' in m && m.experimentInstance && m.experimentInstance.id === inst.id &&
      (m.experimentInstance.status === 'RUNNING' || m.experimentInstance.status === 'TESTING'))), 60000)
    void actions.selectInstance(inst.id)
    const m = await running
    if (m && typeof m.error === 'string') {
      toast('error', `“${inst.name}” did not resume`, m.error, { action: showOutput })
    } else if (m || (admin.instance?.id === inst.id && admin.instance.status === 'RUNNING')) {
      notify.success(`“${inst.name}” is running again`, 'People can join with its links.')
    } else {
      toast('error', `“${inst.name}” did not resume`,
        'The server did not confirm it in time. A finished session can\'t be resumed; start a new one instead. The output panel may say more.',
        { action: showOutput })
    }
  } finally {
    busy.resume = null
  }
}

/** The session's recorded events as CSV (GET /csv/data/:id), saved as "<name>_<id>.csv". */
export async function downloadData (inst: Instance) {
  if (busy.download != null) return
  busy.download = inst.id
  try {
    const text = await http.get<string>(`/csv/data/${inst.id}`, { raw: true })
    saveText(`${safeFileName(inst.name)}_${inst.id}.csv`, text || '')
    notify.success('Downloaded the session\'s data', `${safeFileName(inst.name)}_${inst.id}.csv`)
  } catch (e: any) {
    notify.error('Could not download the data', e?.message || 'The server did not answer.')
  } finally {
    busy.download = null
  }
}
