import { notify } from '@/ui'
import { admin, actions } from '@/store/admin'
import { confirmEngineRebuild } from '@/store/engineGuard'

/**
 * After creating or importing an experiment with "Work on it" ticked: switch to it, asking first
 * while someone is using the shared engine (switching restarts it). `done` is the success title,
 * e.g. "Created “Trust game”". Resolves true once the researcher works on it; otherwise says why not.
 */
export async function switchToNew (id: number, name: string, done: string): Promise<boolean> {
  const stay = admin.experiment ? `You're still working on “${admin.experiment.name}”.` : 'You haven\'t switched to it.'
  if (!await confirmEngineRebuild({ title: `Work on “${name}” now?`, action: 'Switching to it', confirmLabel: 'Switch anyway' })) {
    notify.success(done, `${stay} Choose it on the Experiments page when the engine is free.`)
    return false
  }
  await actions.selectExperiment(id)
  if (admin.experiment?.id === id) {
    notify.success(done, 'You are now working on it.')
    return true
  }
  const why = admin.requestError?.request === 'SelectExperiment' ? `${admin.requestError.text.replace(/\.?$/, '.')} ` : ''
  notify.warning(done, `It was saved, but the switch to it didn't go through. ${why}${stay} Choose it on the Experiments page to try again.`)
  return false
}
