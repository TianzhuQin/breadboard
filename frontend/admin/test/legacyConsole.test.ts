import { describe, expect, it, vi } from 'vitest'
import { keepScriptDraft } from '../../design/design/scriptDraft.js'
import ExperimentImportCtrl, { importUrl } from '../../design/experiment-import/experiment-import.controller.js'
import { connectionNotice } from '../../design/client/connectionNotice.js'

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

  // Both were emptied, so the file had to be chosen again to fix only the name.
  it('keeps the chosen file and the name when the server refuses the name', () => {
    const scope: any = { $watch: () => {}, selectExperiment: () => () => {} }
    let refuse: (err: any) => void = () => {}
    const Upload = { upload: vi.fn(() => ({ then: (_ok: any, bad: (err: any) => void) => { refuse = bad } })) }
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      ExperimentImportCtrl(scope, Upload, (fn: () => void) => fn())
      const file = { name: 'Second-study_2.zip' }
      scope.import.file = file
      scope.import.name = 'Bad/Name'
      scope.importExperiment()
      expect(Upload.upload).toHaveBeenCalledWith(expect.objectContaining({ url: '/experiment/import/Bad%2FName', data: { file } }))
      refuse({ status: 400, data: 'Names can\'t contain / or \\ or line breaks.' })
      expect(scope.import.error).toBe('Names can\'t contain / or \\ or line breaks.')
      expect(scope.import.file).toBe(file)
      expect(scope.import.name).toBe('Bad/Name')
      // fixed and sent again: the refusal is gone
      scope.import.name = 'Second study'
      scope.importExperiment()
      expect(scope.import.error).toBe('')
      expect(Upload.upload).toHaveBeenLastCalledWith(expect.objectContaining({ url: '/experiment/import/Second%20study' }))
    } finally {
      quiet.mockRestore()
    }
  })
})

// The older AngularJS participant client (frontend/design/client.js), in its line above the page
describe('the older participant client\'s connection notice', () => {
  it('says why a preview only watches: the player\'s own page, or the computer, has the seat', () => {
    expect(connectionNotice({ connection: 'watching', preview: true, computerPlayer: false }))
      .toBe('Watching only: this player’s own page is connected, so you see their screen but can’t answer for them.')
    // every preview of a computer player watches: there is no page of its own to speak of
    expect(connectionNotice({ connection: 'watching', preview: true, computerPlayer: true }))
      .toBe('Watching only: the computer plays this seat, so you see what it does but can’t answer for it.')
  })

  it('says where the seat went, and nothing while the page has it', () => {
    expect(connectionNotice({ connection: 'elsewhere', preview: false })).toBe('This study is open in another tab or window. Reload this page to use it here.')
    expect(connectionNotice({ connection: 'elsewhere', preview: true })).toBe('This player is open in another preview or tab.')
    expect(connectionNotice({ connection: 'open', preview: false, computerPlayer: false })).toBe('')
    expect(connectionNotice(undefined)).toBe('')
  })
})
