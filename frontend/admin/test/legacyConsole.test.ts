import { describe, expect, it } from 'vitest'
import { keepScriptDraft } from '../../design/design/scriptDraft.js'
import { importUrl } from '../../design/experiment-import/experiment-import.controller.js'

// The previous console (/legacy-admin, frontend/design). Its Script panel is bound to
// breadboard.user.currentScript, and every User JSON the server sends is merged over breadboard.
describe('the previous console\'s Script panel', () => {
  const merged = (user: any, data: any) => Object.assign({ user }, keepScriptDraft(user, data))

  it('keeps an unsaved draft when the server sends the account\'s User JSON unprompted', () => {
    const before = { email: 'r@lab.org', currentScript: 'g.V.count()  // not run yet', selectedExperiment: 'A' }
    // (a session of the experiment opened: the server says which, with the script last run)
    const after = merged(before, { user: { email: 'r@lab.org', currentScript: 'a.ai.isOn', selectedExperiment: 'A', experimentInstanceId: 7 } })
    expect(after.user.currentScript).toBe('g.V.count()  // not run yet')
    expect(after.user.experimentInstanceId).toBe(7)
  })

  it('takes the server\'s script at first, and another account\'s User JSON as it is', () => {
    expect(merged(undefined, { user: { email: 'r@lab.org', currentScript: 'saved' } }).user.currentScript).toBe('saved')
    const other = merged({ email: 'r@lab.org', currentScript: 'draft' }, { user: { email: 'q@lab.org', currentScript: 'theirs' } })
    expect(other.user.currentScript).toBe('theirs')
  })

  it('leaves frames without a User JSON alone', () => {
    const data = { notify: { ScriptEngineReloaded: 1 } }
    expect(keepScriptDraft({ email: 'r@lab.org', currentScript: 'draft' }, data)).toEqual({ notify: { ScriptEngineReloaded: 1 } })
  })
})

describe('the previous console\'s Import', () => {
  it('puts the name into the address encoded, so the server says why it refuses one', () => {
    expect(importUrl('/experiment/import', 'My study')).toBe('/experiment/import/My%20study')
    expect(importUrl('/experiment/import', 'Pilot A/B')).toBe('/experiment/import/Pilot%20A%2FB')
    expect(importUrl('/experiment/import', 'Wave #2?')).toBe('/experiment/import/Wave%20%232%3F')
  })
})
