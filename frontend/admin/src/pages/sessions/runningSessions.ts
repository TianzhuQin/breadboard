import { admin } from '@/store/admin'
import { fetchAutopilot, type AutopilotState } from '../autopilot/autopilot'

/** A session that looks like it is running, and where the console saw it. */
export interface RunningSession {
  id: number
  name: string
  /** the researcher's own current session */
  mine: boolean
  /** the one the autopilot keeps open */
  autopilot: boolean
}

const live = (status?: string | null) => status === 'RUNNING' || status === 'TESTING'

/** GET /autopilot, or null when it fails or takes too long (an older server, a busy one, no connection). */
export async function autopilotNow (ms = 4000): Promise<AutopilotState | null> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<null>(resolve => { timer = setTimeout(() => resolve(null), ms) })
  try {
    return await Promise.race([fetchAutopilot().catch(() => null), late])
  } finally {
    clearTimeout(timer)
  }
}

/**
 * Sessions that look like they are running, apart from `except`. The script engine is shared: when
 * it restarts (SelectInstance, SelectExperiment) it ends whichever session it runs, whoever started
 * it. The console can't see the engine itself, so this gathers what it can: the researcher's own
 * current session, running rows in the experiment's list (a row can be stale), and the session the
 * autopilot keeps open.
 */
export async function runningSessions (except: number | null = null): Promise<RunningSession[]> {
  const out = new Map<number, RunningSession>()
  const add = (id: number, name: string, how: Partial<RunningSession>) => {
    if (id === except) return
    const known = out.get(id)
    out.set(id, { id, name: known?.name || name || `Session #${id}`, mine: !!(known?.mine || how.mine), autopilot: !!(known?.autopilot || how.autopilot) })
  }
  if (admin.instance && live(admin.instance.status)) add(admin.instance.id, admin.instance.name, { mine: true })
  for (const i of admin.experiment?.instances || []) if (live(i.status)) add(i.id, i.name, {})
  const ap = await autopilotNow()
  const inst = ap?.status.instance
  if (inst && live(inst.status)) add(inst.id, inst.name, { autopilot: true })
  return [...out.values()]
}

/** “a”, “a” and “b”, “a”, “b” and “c” (each marked when the autopilot runs it). */
export function sessionNames (list: readonly RunningSession[]): string {
  const names = list.map(s => `“${s.name}”${s.autopilot ? ' (the autopilot’s)' : ''}`)
  return names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}` : (names[0] || '')
}
