import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'

vi.mock('@/store/admin', async () => {
  const { reactive, ref, computed } = await import('vue')
  const admin = reactive({
    email: 'r@lab.org',
    ready: true,
    user: { email: 'r@lab.org', currentScript: '', experiments: [{ id: 7, name: 'Trust game' }], defaultLanguage: null } as any,
    experiment: null as any,
    instance: null as any,
    engine: 'ready',
    engineReloadedAt: 0,
    engineProblem: '',
    // whether user.engine (what the shared engine runs) is known to be current
    engineInfoCurrent: true,
    requestError: null as any,
    output: [] as any[],
  })
  // runStep returns the request id the engine's answer carries, like the real store
  let requestSeq = 0
  const actions = {
    runScript: vi.fn((): string | null => `script-${++requestSeq}`),
    runStep: vi.fn((): string | null => `step-${++requestSeq}`),
    refresh: vi.fn(() => Promise.resolve(null)),
    markEngineStale: vi.fn(() => { admin.engine = 'stale' }),
    markEngineCurrent: vi.fn(() => { if (admin.engine === 'stale' && !admin.engineProblem) admin.engine = 'ready' }),
    reloadEngine: vi.fn(() => true),
    selectExperiment: vi.fn(),
  }
  return {
    admin,
    actions,
    ensureConnected: vi.fn(() => true),
    running: computed(() => admin.instance?.status === 'RUNNING'),
    connectionState: ref('open'),
    graph: { nodes: new Map(), version: ref(0) },
    // what the engine runs when that is another experiment, and why Run and Reload are off then
    engineElsewhere: ref(null),
    runBlocked: ref(''),
    reloadBlocked: ref(''),
    // in file mode: why a saved step file isn't loaded now
    fileStepsWait: ref(''),
  }
})

vi.mock('@/lib/http', () => {
  class HttpError extends Error {
    status: number
    body: any
    constructor (status: number, message: string, body?: any) { super(message); this.status = status; this.body = body }
  }
  return { HttpError, http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }, saveText: vi.fn(), onUnauthorized: vi.fn() }
})

import * as store from '@/store/admin'
import { http, HttpError } from '@/lib/http'
import {
  BUILTINS, builtinOf, copyName, draftFromStep, formatName, isDirty, isNew, mergeServerSteps, newDraft, renameDraft,
  sortDrafts, templateSource, uniqueName, validateName, type StepDraft,
  renameInSource,
} from '../src/pages/steps/stepsModel'
import { bucketKey, buckets, deleteDraft, errorText, saveDraft, ensureBucket, runDraft, unsavedDrafts } from '../src/pages/steps/stepsStore'
import { __resetReplies } from '../src/pages/console/engineReplies'
import { toasts } from '../src/ui/toast'
import { unsavedTotal } from '../src/shell/unsaved'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import StepsPage from '../src/pages/steps/StepsPage.vue'

const admin = store.admin as any
const actions = store.actions as any
const connectionState = (store as any).connectionState
const get = http.get as any
const post = http.post as any

beforeEach(async () => {
  // runs an earlier test left waiting for the engine's answer
  __resetReplies()
  await flushPromises()
  admin.engine = 'ready'
  vi.clearAllMocks()
  buckets.clear()
})

describe('templates (as the old console made them)', () => {
  it('turns a step name into the variable its code defines', () => {
    expect(formatName('My-new_step')).toBe('myNewStep')
    expect(formatName('InitStep')).toBe('initStep')
    expect(formatName('NewStep')).toBe('newStep')
    expect(formatName('round 2 payoff')).toBe('round2Payoff')
    expect(formatName('Trust & reward!')).toBe('trustReward')
  })

  it('writes a synchronous step', () => {
    expect(templateSource('Payoff', 'sync')).toBe(
      'payoff = stepFactory.createStep("Payoff")\n' +
      '\n' +
      'payoff.run = {\n' +
      '  println "payoff.run"\n' +
      '}\n' +
      '\n' +
      'payoff.done = {\n' +
      '  println "payoff.done"\n' +
      '}',
    )
  })

  it('writes an asynchronous step and an empty one', () => {
    expect(templateSource('Round-two', 'async')).toBe(
      'roundTwo = stepFactory.createNoUserActionStep("Round-two")\n\nroundTwo.run = {\n  println "roundTwo.run"\n}\n\nroundTwo.done = {\n  println "roundTwo.done"\n}',
    )
    expect(templateSource('Anything', 'empty')).toBe('')
  })

  it('regenerates a new step\'s code from its name until the code is edited', () => {
    const d = newDraft('NewStep', 'sync')
    renameDraft(d, 'Vote')
    expect(d.source).toBe(templateSource('Vote', 'sync'))
    d.source += '\n// mine'
    renameDraft(d, 'Ballot')
    expect(d.name).toBe('Ballot')
    expect(d.source).toContain('vote = stepFactory.createStep("Vote")')
    expect(d.source).toContain('// mine')
  })

  it('knows the built-in steps by name, in any case', () => {
    expect(builtinOf('onjoinstep')?.name).toBe('OnJoinStep')
    expect(builtinOf('Payoff')).toBeNull()
    expect(BUILTINS.map(b => b.name)).toEqual(['InitStep', 'OnJoinStep', 'OnLeaveStep'])
  })
})

describe('unsaved changes', () => {
  const step = (over: Partial<StepDraft> = {}) => ({ ...draftFromStep({ id: 1, name: 'Payoff', source: 'a()' }), ...over })

  it('tracks code and name against the server copy', () => {
    const d = step()
    expect(isDirty(d)).toBe(false)
    d.source = 'b()'
    expect(isDirty(d)).toBe(true)
    d.source = 'a()'
    expect(isDirty(d)).toBe(false)
    d.name = 'Payout'
    expect(isDirty(d)).toBe(true)
  })

  it('counts a step never saved as unsaved, whatever it holds', () => {
    const d = newDraft('X', 'empty')
    expect(isNew(d)).toBe(true)
    expect(isDirty(d)).toBe(true)
  })

  it('sorts the built-in steps first, then by name', () => {
    const names = ['Round 10', 'onleavestep', 'Round 2', 'InitStep', 'apple', 'OnJoinStep'].map(name => ({ name }))
    expect(sortDrafts(names).map(d => d.name)).toEqual(['InitStep', 'OnJoinStep', 'onleavestep', 'apple', 'Round 2', 'Round 10'])
  })

  it('checks names', () => {
    const a = step()
    const b = { ...step({ uid: 'b', id: 2, name: 'Vote', savedName: 'Vote' }) }
    const all = [a, b]
    expect(validateName('', all, a)).toMatch(/name/)
    expect(validateName('vote', all, a)).toMatch(/already called “Vote”/)
    expect(validateName('2nd round', all, a)).toMatch(/letter/)
    expect(validateName('Pay/off', all, a)).toMatch(/only letters/)
    expect(validateName(' Pay', all, a)).toMatch(/space/)
    expect(validateName('Payoff round', all, a)).toBeNull()
    // an old name that breaks today's rules can still be saved unchanged
    const legacy = step({ name: 'old.step', savedName: 'old.step' })
    expect(validateName('old.step', [legacy], legacy)).toBeNull()
  })

  it('finds a free name', () => {
    const all = [{ name: 'NewStep' }, { name: 'newstep2' }]
    expect(uniqueName('NewStep', all)).toBe('NewStep3')
    expect(uniqueName('Other', all)).toBe('Other')
    expect(copyName('Vote', [{ name: 'Vote copy' }])).toBe('Vote copy2')
  })
})

describe('merging the server\'s steps', () => {
  const s = (id: number | null, name: string, source = name + '()') => ({ id, name, source })

  it('adds, follows, keeps edits and drops what is gone', () => {
    const clean = draftFromStep(s(1, 'A'))
    const edited = draftFromStep(s(2, 'B'))
    edited.source = 'mine()'
    const goneClean = draftFromStep(s(3, 'C'))
    const goneEdited = draftFromStep(s(4, 'D'))
    goneEdited.source = 'kept()'
    const fresh = newDraft('E', 'empty')
    const out = mergeServerSteps([clean, edited, goneClean, goneEdited, fresh], [s(1, 'A', 'new()'), s(2, 'B', 'theirs()'), s(5, 'F')], false, 1e12)
    expect(out.map(d => d.name)).toEqual(['A', 'B', 'F', 'D', 'E'])
    expect(clean.source).toBe('new()')
    expect(edited.source).toBe('mine()')
    expect(goneEdited).toMatchObject({ id: null, savedSource: null, orphaned: true, source: 'kept()' })
    expect(out).not.toContain(goneClean)
  })

  it('ignores a server copy older than a save made here moments ago', () => {
    const d = draftFromStep(s(1, 'A', 'saved()'))
    d.savedAt = 1000
    const out = mergeServerSteps([d], [s(1, 'A', 'older()')], false, 3000)
    expect(out[0].source).toBe('saved()')
    // and a just-created step that the server's list doesn't have yet stays
    const n = draftFromStep(s(9, 'New'))
    n.savedAt = 1000
    expect(mergeServerSteps([n], [], false, 2000)).toEqual([n])
  })

  it('reads Windows line endings as the editor will give them back', () => {
    const d = draftFromStep(s(1, 'InitStep', 'a = 1\r\nb = 2\r\n'))
    expect(d.source).toBe('a = 1\nb = 2\n')
    expect(d.savedSource).toBe(d.source)
    // type a character, delete it: back to the saved text
    d.source = 'a = 1\nb = 2\nx'
    d.source = 'a = 1\nb = 2\n'
    expect(isDirty(d)).toBe(false)
    // a server copy with \r\n (or a lone \r) is followed the same way
    const out = mergeServerSteps([d], [s(1, 'InitStep', 'c = 3\r\nd = 4\re')], false, 1e12)
    expect(out[0]).toMatchObject({ source: 'c = 3\nd = 4\ne', savedSource: 'c = 3\nd = 4\ne' })
    expect(isDirty(out[0])).toBe(false)
  })

  it('matches file-mode steps by name', () => {
    const d = draftFromStep(s(null, 'Round'))
    const out = mergeServerSteps([d], [s(null, 'Round', 'changed()'), s(null, 'Extra')], true, 1e12)
    expect(out[0]).toBe(d)
    expect(d.source).toBe('changed()')
    expect(out.map(x => x.name)).toEqual(['Round', 'Extra'])
  })
})

describe('saving', () => {
  it('posts a new step as -1 and remembers its id', async () => {
    const b = ensureBucket(7, false)
    const d = newDraft('Vote', 'sync')
    b.drafts.push(d)
    post.mockResolvedValueOnce({ id: 41, name: 'Vote', source: d.source })
    expect(await saveDraft(b, b.drafts[0])).toBe(true)
    expect(post).toHaveBeenCalledWith('/steps/-1', { experimentId: 7, stepSource: templateSource('Vote', 'sync'), name: 'Vote' })
    expect(b.drafts[0]).toMatchObject({ id: 41, savedName: 'Vote', status: 'saved', engineBehind: true, template: null })
    expect(isDirty(b.drafts[0])).toBe(false)
  })

  it('keeps the server\'s message when a save fails', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 3, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'b()'
    post.mockRejectedValueOnce(new (HttpError as any)(400, 'Invalid Step ID'))
    expect(await saveDraft(b, b.drafts[0])).toBe(false)
    expect(b.drafts[0]).toMatchObject({ status: 'error', error: 'Invalid Step ID' })
    expect(unsavedDrafts.value).toHaveLength(1)
  })

  it('waits for a save already on its way instead of failing', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 3, name: 'A', source: 'a()' }))
    const d = b.drafts[0]
    d.source = 'b()'
    let answer!: (v: any) => void
    post.mockReturnValueOnce(new Promise(resolve => { answer = resolve }))
    const first = saveDraft(b, d)
    const second = saveDraft(b, d)
    answer({ id: 3, name: 'A', source: 'b()' })
    expect(await first).toBe(true)
    expect(await second).toBe(true)
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('saves again what was typed while a save was on its way', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 3, name: 'A', source: 'a()' }))
    const d = b.drafts[0]
    d.source = 'b()'
    let answer!: (v: any) => void
    post.mockReturnValueOnce(new Promise(resolve => { answer = resolve }))
    const first = saveDraft(b, d)
    d.source = 'c()'
    const second = saveDraft(b, d)
    post.mockResolvedValueOnce({ id: 3, name: 'A', source: 'c()' })
    answer({ id: 3, name: 'A', source: 'b()' })
    expect(await first).toBe(true)
    expect(await second).toBe(true)
    expect(post).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenLastCalledWith('/steps/3', { experimentId: 7, stepSource: 'c()', name: 'A' })
    expect(isDirty(d)).toBe(false)
  })

  it('gives a reason when it refuses', async () => {
    const b = ensureBucket(7, true)
    b.drafts.push(draftFromStep({ id: null, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'b()'
    expect(await saveDraft(b, b.drafts[0])).toBe(false)
    expect(b.drafts[0].error).toMatch(/file mode/)
    expect(post).not.toHaveBeenCalled()
  })

  it('says what went wrong in words', () => {
    expect(errorText(new (HttpError as any)(500, ''))).toMatch(/server had a problem/)
    expect(errorText(new TypeError('Failed to fetch'))).toMatch(/Could not reach the server/)
  })
})

describe('the live engine', () => {
  let outId = 1000
  function reply (source: string) {
    admin.output.push({ id: ++outId, kind: 'output', text: source.trim() + '\n\n==>', at: Date.now() })
  }
  beforeEach(() => {
    admin.experiment = { id: 7, name: 'Trust game', fileMode: false, steps: [] }
    admin.engineReloadedAt = Date.now() + Math.random()
  })

  it('is current again once every saved change has been run', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }), draftFromStep({ id: 2, name: 'B', source: 'b()' }))
    const [a, bb] = b.drafts
    a.engineBehind = bb.engineBehind = true
    admin.engine = 'stale'
    runDraft(a)
    reply('a()')
    await flushPromises()
    expect(a.engineBehind).toBe(false)
    expect(admin.engine).toBe('stale') // B is still behind
    expect(actions.markEngineCurrent).not.toHaveBeenCalled()
    runDraft(bb)
    reply('b()')
    await flushPromises()
    // through the store, which leaves a reload that did not finish marked
    expect(actions.markEngineCurrent).toHaveBeenCalledTimes(1)
    expect(admin.engine).toBe('ready')
  })

  it('shows the engine\'s refusal to run a step for another experiment on that step', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }), draftFromStep({ id: 2, name: 'B', source: 'b()' }))
    const [a, bb] = b.drafts
    a.engineBehind = true
    runDraft(a)
    runDraft(bb)
    const [idA, idB] = actions.runStep.mock.results.map((r: any) => r.value)
    const refusal = 'Nothing was run: the engine has another experiment loaded now. Reload the page and try again.'
    // B's answer first: the id says whose it is, though A was sent first
    admin.output.push({ id: ++outId, kind: 'error', text: refusal, source: 'SendStep', requestId: idB, at: Date.now() })
    await flushPromises()
    expect(bb.run).toMatchObject({ status: 'error', error: refusal })
    expect(a.run).toMatchObject({ status: 'pending' })
    admin.output.push({ id: ++outId, kind: 'error', text: refusal, source: 'SendStep', requestId: idA, at: Date.now() })
    await flushPromises()
    expect(a.run).toMatchObject({ status: 'error', error: refusal })
    // the saved version did not load
    expect(a.engineBehind).toBe(true)
    expect(actions.markEngineStale).toHaveBeenCalled()
  })

  it('says at once that a step did not run while the connection is down', () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    const d = b.drafts[0]
    d.engineBehind = true
    actions.runStep.mockReturnValueOnce(null)
    expect(runDraft(d)).toBe(false)
    expect(d.run).toMatchObject({ status: 'notsent' })
    expect(d.engineBehind).toBe(true)
    expect(actions.markEngineStale).toHaveBeenCalledTimes(1)
  })

  it('marks the engine stale when a saved version fails to load, or gets no answer', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }), draftFromStep({ id: 2, name: 'B', source: 'b()' }))
    const [a, bb] = b.drafts
    a.engineBehind = bb.engineBehind = true
    runDraft(a)
    admin.output.push({ id: ++outId, kind: 'error', text: 'Caught error: No such property: x', source: 'SendStep', at: Date.now() })
    await flushPromises()
    expect(a.run).toMatchObject({ status: 'error' })
    expect(a.engineBehind).toBe(true)
    expect(actions.markEngineStale).toHaveBeenCalledTimes(1)
    expect(admin.engine).toBe('stale')
    // the connection drops before B's answer comes
    admin.engine = 'ready'
    runDraft(bb)
    connectionState.value = 'retrying'
    await flushPromises()
    connectionState.value = 'open'
    expect(bb.run).toMatchObject({ status: 'noreply' })
    expect(actions.markEngineStale).toHaveBeenCalledTimes(2)
    expect(admin.engine).toBe('stale')
  })

  it('keeps the “not loaded yet” marks through signing out, and says so again after signing in', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].engineBehind = true
    // signing out (or a 401): the store starts over, which is no reload
    admin.ready = false
    admin.engineReloadedAt = 0
    await nextTick()
    expect(b.drafts[0].engineBehind).toBe(true)
    // signed in again: the first User JSON
    admin.ready = true
    await nextTick()
    expect(actions.markEngineStale).toHaveBeenCalledTimes(1)
    expect(admin.engine).toBe('stale')
    // a real reload still clears them
    admin.engineReloadedAt = Date.now() + 2
    await nextTick()
    expect(b.drafts[0].engineBehind).toBe(false)
  })

  it('keeps the mark about the experiment the engine runs as the selection changes without a rebuild', async () => {
    // the engine runs experiment 7 (its session kept the engine while experiment 8 was selected)
    admin.user.engine = { experimentId: 7, experimentName: 'Trust game', sessionId: 9, sessionName: 'Wave 1', owner: 'r@lab.org', gameStarted: false }
    try {
      await nextTick()
      const b7 = ensureBucket(7, false)
      b7.drafts.push(draftFromStep({ id: 1, name: 'Bonus', source: 'bonus = 1' }))
      ;(http.del as any).mockResolvedValueOnce(undefined)
      expect(await deleteDraft(b7, b7.drafts[0])).toBe(true)
      admin.engine = 'stale'
      // a step deleted from 8 is 8's: it says nothing about what the engine runs
      const b8 = ensureBucket(8, false)
      b8.drafts.push(draftFromStep({ id: 2, name: 'Other', source: 'other = 1' }))
      ;(http.del as any).mockResolvedValueOnce(undefined)
      expect(await deleteDraft(b8, b8.drafts[0])).toBe(true)

      // working on 8: the engine runs 7, so it isn't 8's mark to show
      admin.experiment = { id: 8, name: 'Pilot study', fileMode: false, steps: [] }
      await nextTick()
      expect(admin.engine).toBe('ready')
      // back on 7: what the deleted step defined is still in the engine
      actions.markEngineStale.mockClear()
      admin.experiment = { id: 7, name: 'Trust game', fileMode: false, steps: [] }
      await nextTick()
      expect(actions.markEngineStale).toHaveBeenCalledWith(7)
      expect(admin.engine).toBe('stale')

      // a reload loads 7 again: nothing behind on either
      admin.engineReloadedAt = Date.now() + 5
      await nextTick()
      admin.engine = 'ready'
      actions.markEngineStale.mockClear()
      admin.experiment = { id: 8, name: 'Pilot study', fileMode: false, steps: [] }
      await nextTick()
      admin.experiment = { id: 7, name: 'Trust game', fileMode: false, steps: [] }
      await nextTick()
      expect(actions.markEngineStale).not.toHaveBeenCalled()
      expect(admin.engine).toBe('ready')
    } finally {
      delete admin.user.engine
      admin.experiment = null
    }
  })

  it('throws away the drafts of a deleted experiment, but keeps them through signing out', async () => {
    const user = admin.user
    admin.user = { ...user, experiments: [{ id: 7, name: 'Trust game' }, { id: 8, name: 'Pilot study' }] }
    await nextTick()
    const b = ensureBucket(8, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'edited()'
    ensureBucket(8, true)
    toasts.splice(0)
    admin.user = null
    await nextTick()
    expect(unsavedDrafts.value).toHaveLength(1)
    // signed in again: the experiment is gone (deleted in another tab)
    admin.user = { ...user, experiments: [{ id: 7, name: 'Trust game' }] }
    await nextTick()
    expect(unsavedDrafts.value).toHaveLength(0)
    expect(buckets.has(bucketKey(8, false)) || buckets.has(bucketKey(8, true))).toBe(false)
    expect(toasts.some(t => t.title === 'Unsaved steps of “Pilot study” were thrown away')).toBe(true)
    admin.user = user
  })

  it('counts unsaved steps for the sign-out question', () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }), draftFromStep({ id: 2, name: 'B', source: 'b()' }))
    expect(unsavedTotal()).toBe(0)
    b.drafts[0].source = 'edited()'
    b.drafts[1].source = 'edited too()'
    expect(unsavedTotal()).toBe(2)
    // file mode: the files are the truth, nothing to lose here
    const f = ensureBucket(7, true)
    f.drafts.push(draftFromStep({ id: null, name: 'C', source: 'c()' }))
    f.drafts[0].source = 'edited()'
    expect(unsavedTotal()).toBe(2)
  })

  it('stays stale when a reload did not finish, whatever was run since', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].engineBehind = true
    admin.engine = 'stale'
    admin.engineProblem = 'The engine did not finish reloading.'
    runDraft(b.drafts[0])
    reply('a()')
    await flushPromises()
    expect(admin.engine).toBe('stale')
    admin.engineProblem = ''
  })

  it('stays stale after a delete, until the engine reloads', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }), draftFromStep({ id: 2, name: 'Gone', source: 'g()' }))
    ;(http.del as any).mockResolvedValueOnce(null)
    expect(await deleteDraft(b, b.drafts[1])).toBe(true)
    admin.engine = 'stale'
    b.drafts[0].engineBehind = true
    runDraft(b.drafts[0])
    reply('a()')
    await flushPromises()
    expect(admin.engine).toBe('stale')
    // a reload loads everything that is saved
    b.drafts[0].engineBehind = true
    admin.engineReloadedAt = Date.now() + 1
    await nextTick()
    expect(b.drafts[0].engineBehind).toBe(false)
  })

  it('never keeps one account’s unsaved steps for another', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'edited()'
    expect(unsavedDrafts.value).toHaveLength(1)
    admin.email = 'other@lab.org'
    await nextTick()
    expect(unsavedDrafts.value).toHaveLength(0)
    expect(buckets.size).toBe(0)
    admin.email = 'r@lab.org'
    await nextTick()
  })

  it('offers no way back when signing out clears the experiment', async () => {
    await nextTick()
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'edited()'
    toasts.splice(0)
    admin.experiment = null
    await nextTick()
    expect(toasts.some(x => x.title.startsWith('Unsaved steps'))).toBe(false)
  })

  it('switches back at once while nobody is using the engine', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'edited()'
    toasts.splice(0)
    admin.experiment = { id: 8, name: 'Other', fileMode: false, steps: [] }
    await nextTick()
    const t = toasts.find(x => x.title.startsWith('Unsaved steps'))!
    actions.selectExperiment.mockResolvedValueOnce({ experiment: { id: 7 } })
    t.action!.run()
    await flushPromises()
    expect(confirmState.open).toBe(false)
    expect(actions.selectExperiment).toHaveBeenCalledWith(7)
  })

  it('switches back without asking while a session is running (switching leaves it running)', async () => {
    const b = ensureBucket(7, false)
    b.drafts.push(draftFromStep({ id: 1, name: 'A', source: 'a()' }))
    b.drafts[0].source = 'edited()'
    toasts.splice(0)
    admin.instance = { id: 5, name: 'Pilot', status: 'RUNNING' }
    admin.experiment = { id: 8, name: 'Other', fileMode: false, steps: [] }
    await nextTick()
    const t = toasts.find(x => x.title.startsWith('Unsaved steps'))!
    expect(t.action?.label).toBe('Switch back')
    actions.selectExperiment.mockResolvedValueOnce(null)
    t.action!.run()
    await flushPromises()
    expect(confirmState.open).toBe(false)
    expect(actions.selectExperiment).toHaveBeenCalledWith(7)
    // the server didn't confirm: say so
    expect(toasts.some(x => x.title.includes('didn’t confirm the switch'))).toBe(true)
    admin.instance = null
  })
})

describe('StepsPage', () => {
  const CodeStub = defineComponent({
    name: 'BCodeEditor',
    props: ['modelValue', 'onRun', 'onSave', 'language', 'fill', 'ariaLabel', 'placeholder', 'readonly'],
    emits: ['update:modelValue'],
    setup (props, { emit, expose }) {
      expose({ focus: () => {}, selection: () => '', insert: () => {} })
      return () => h('textarea', { class: 'code-stub', value: props.modelValue, readonly: props.readonly, onInput: (e: any) => emit('update:modelValue', e.target.value) })
    },
  })
  const steps = [
    { id: 3, name: 'Round', source: 'round = 1' },
    { id: 1, name: 'InitStep', source: 'initStep = 1' },
    { id: 2, name: 'OnJoinStep', source: 'onJoinStep = 1' },
    { id: 4, name: 'Apple', source: 'apple = 1' },
  ]

  async function open (fileMode = false) {
    admin.experiment = { id: 7, name: 'Trust game', fileMode, steps: fileMode ? steps.map(s => ({ ...s, id: null })) : steps }
    get.mockResolvedValue({ steps: fileMode ? steps.map(s => ({ ...s, id: null })) : steps })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/steps', component: StepsPage }, { path: '/other', component: { render: () => h('div') } }] })
    router.push('/steps')
    await router.isReady()
    const w = mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router], stubs: { BCodeEditor: CodeStub } } })
    await flushPromises()
    return { w, router }
  }

  it('lists the built-in steps first and opens InitStep', async () => {
    const { w } = await open()
    expect(get).toHaveBeenCalledWith('/steps/7')
    const names = w.findAll('.sitem__name').map(n => n.text())
    expect(names).toEqual(['InitStep', 'OnJoinStep', 'Apple', 'Round'])
    expect(w.find('.steps__title').text()).toBe('InitStep')
    expect(w.text()).toContain('OnLeaveStep') // offered, since this experiment lacks it
    expect((w.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe('initStep = 1')
    w.unmount()
  })

  it('creates a step from a template, names it and saves it', async () => {
    const { w } = await open()
    await w.findAll('button').find(b => b.text() === 'New')!.trigger('click')
    await w.findAll('.b-menu__item').find(b => b.text().startsWith('Synchronous'))!.trigger('click')
    await nextTick()
    const name = w.find('.steps__name-field input')
    expect(name.exists()).toBe(true)
    await name.setValue('Payoff')
    expect((w.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe(templateSource('Payoff', 'sync'))
    await name.trigger('keydown', { key: 'Enter' })
    expect(w.find('.sitem.is-active .sitem__new').exists()).toBe(true)

    post.mockResolvedValueOnce({ id: 50, name: 'Payoff', source: templateSource('Payoff', 'sync') })
    await w.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/steps/-1', { experimentId: 7, stepSource: templateSource('Payoff', 'sync'), name: 'Payoff' })
    expect(actions.refresh).toHaveBeenCalled()
    expect(actions.markEngineStale).toHaveBeenCalled()
    w.unmount()
  })

  it('saves and runs with Cmd/Ctrl+Enter', async () => {
    const { w } = await open()
    await w.find('textarea.code-stub').setValue('initStep = 2')
    expect(w.find('.sitem.is-active .sitem__dot').exists()).toBe(true)
    post.mockResolvedValueOnce({ id: 1, name: 'InitStep', source: 'initStep = 2' })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }))
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/steps/1', { experimentId: 7, stepSource: 'initStep = 2', name: 'InitStep' })
    expect(actions.runStep).toHaveBeenCalledWith({ id: 1, name: 'InitStep', source: 'initStep = 2' })
    w.unmount()
  })

  it('runs after a save that was already on its way (Cmd+S, then Cmd+Enter)', async () => {
    const { w } = await open()
    await w.find('textarea.code-stub').setValue('initStep = 3')
    toasts.splice(0)
    let answer!: (v: any) => void
    post.mockReturnValueOnce(new Promise(resolve => { answer = resolve }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    await nextTick()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }))
    await nextTick()
    answer({ id: 1, name: 'InitStep', source: 'initStep = 3' })
    await flushPromises()
    expect(post).toHaveBeenCalledTimes(1)
    expect(actions.runStep).toHaveBeenCalledWith({ id: 1, name: 'InitStep', source: 'initStep = 3' })
    expect(toasts.some(t => t.kind === 'error')).toBe(false)
    w.unmount()
  })

  it('asks before leaving with unsaved changes, and can stay', async () => {
    const { w, router } = await open()
    await w.find('textarea.code-stub').setValue('changed')
    const nav = router.push('/other')
    await flushPromises()
    expect(document.body.textContent).toContain('Save your changes before leaving?')
    const stay = Array.from(document.body.querySelectorAll('button')).find(b => b.textContent?.trim() === 'Stay')!
    stay.click()
    await nav
    expect(router.currentRoute.value.path).toBe('/steps')
    w.unmount()
  })

  it('is read only in file mode and says why', async () => {
    const { w } = await open(true)
    expect(w.text()).toContain('Read from files')
    expect(w.findAll('button').some(b => b.text() === 'Save')).toBe(false)
    expect(w.find('textarea.code-stub').attributes('readonly')).toBeDefined()
    w.unmount()
  })

  it('says at once when Save & run could not reach the engine', async () => {
    const { w } = await open()
    await w.find('textarea.code-stub').setValue('initStep = 4')
    post.mockResolvedValueOnce({ id: 1, name: 'InitStep', source: 'initStep = 4' })
    actions.runStep.mockReturnValueOnce(null)
    await w.findAll('button').find(b => b.text() === 'Save & run')!.trigger('click')
    await flushPromises()
    expect(actions.runStep).toHaveBeenCalled()
    expect(w.find('.srun.is-notsent').text()).toContain('not connected to the server')
    // saved, but not in the engine yet
    expect(actions.markEngineStale).toHaveBeenCalled()
    admin.engine = 'ready'
    w.unmount()
  })

  it('doesn’t run a step whose save ended after the selected experiment changed', async () => {
    const { w } = await open()
    await w.find('textarea.code-stub').setValue('initStep = 5')
    toasts.splice(0)
    let answer!: (v: any) => void
    post.mockReturnValueOnce(new Promise(resolve => { answer = resolve }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }))
    await nextTick()
    // another tab switches the account to another experiment meanwhile
    get.mockResolvedValue({ steps: [] })
    admin.experiment = { id: 8, name: 'Other', fileMode: false, steps: [] }
    answer({ id: 1, name: 'InitStep', source: 'initStep = 5' })
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/steps/1', { experimentId: 7, stepSource: 'initStep = 5', name: 'InitStep' })
    expect(actions.runStep).not.toHaveBeenCalled()
    expect(toasts.some(t => t.title === '“InitStep” was saved but not run')).toBe(true)
    w.unmount()
  })

  it('offers to copy or discard edits made before file mode was switched on', async () => {
    const { w } = await open()
    await w.find('textarea.code-stub').setValue('initStep = 9')
    expect(w.find('.steps__banner.is-stranded').exists()).toBe(false)
    // file mode is switched on (here or in another tab): the steps come from the files
    const files = steps.map(s => ({ ...s, id: null }))
    get.mockResolvedValue({ steps: files })
    admin.experiment = { ...admin.experiment, fileMode: true, steps: files }
    await flushPromises()
    const banner = w.find('.steps__banner.is-stranded')
    expect(banner.text()).toContain('Not saved: InitStep.')
    expect(unsavedDrafts.value).toHaveLength(1)
    await banner.findAll('button').find(b => b.text() === 'Discard…')!.trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    settleConfirm(true)
    await flushPromises()
    expect(unsavedDrafts.value).toHaveLength(0)
    expect(w.find('.steps__banner.is-stranded').exists()).toBe(false)
    // back out of file mode: the saved version is there
    get.mockResolvedValue({ steps })
    admin.experiment = { ...admin.experiment, fileMode: false, steps }
    await flushPromises()
    expect((w.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe('initStep = 1')
    w.unmount()
  })

  it('asks for a reload only while a session runs (the next session loads the saved steps anyway), and asks first', async () => {
    const { w } = await open()
    const calls = (actions.reloadEngine as any).mock.calls.length
    admin.engine = 'stale'
    await nextTick()
    const reloadButton = () => w.findAll('.steps__banner button').find(b => b.text() === 'Reload engine')
    expect(reloadButton()).toBeUndefined()

    admin.instance = { id: 5, name: 'Pilot', status: 'RUNNING' }
    await nextTick()
    await reloadButton()!.trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    expect(confirmState.req).toMatchObject({ danger: true, title: 'Reload the script engine?' })
    expect(confirmState.req!.message).toContain('Your session “Pilot” is running.')
    settleConfirm(false)
    await flushPromises()
    expect((actions.reloadEngine as any).mock.calls.length).toBe(calls)
    admin.instance = null
    admin.engine = 'ready'
    w.unmount()
  })

  it('says the autopilot\'s next session uses what is saved while its own session is open, with Reload as a secondary action', async () => {
    const { w } = await open()
    try {
      const autopilot = { status: { on: true, experimentId: 7, instance: { id: 5, name: 'Autopilot · 1', status: 'RUNNING' } }, settings: { continuous: true, nextSessionDelaySeconds: 10 } }
      get.mockImplementation(async (url: string) => url === '/autopilot' ? autopilot : { steps })
      admin.instance = { id: 5, name: 'Autopilot · 1', status: 'RUNNING' }
      admin.engine = 'stale'
      await flushPromises()
      const text = () => w.text().replace(/\s+/g, ' ')
      expect(text()).toContain('Saved: the autopilot’s next session uses it. The session it has open now goes on with the steps it started with.')
      expect(text()).not.toContain('The engine isn\'t running your latest steps')
      expect(w.find('.steps__banner.is-stale').exists()).toBe(false)
      // still there, as the lesser choice, and it asks first
      const reload = w.findAll('.steps__banner.is-unattended button').find(b => b.text() === 'Reload engine')!
      expect(reload.classes()).toContain('b-btn--ghost')
      await reload.trigger('click')
      await flushPromises()
      expect(confirmState.open).toBe(true)
      settleConfirm(false)
      await flushPromises()

      // the session open is someone's own (the autopilot is paused): the reload is asked for, as before
      autopilot.status = { ...autopilot.status, paused: true } as any
      admin.instance = { ...admin.instance }
      await flushPromises()
      expect(text()).toContain('The engine isn\'t running your latest steps')
      expect(text()).not.toContain('Saved: the autopilot’s next session uses it')
    } finally {
      get.mockReset()
      settleConfirm(false)
      admin.instance = null
      admin.engine = 'ready'
      w.unmount()
    }
  })
})

describe('opening a step at a line', () => {
  it('finds a step by its name or by the variable its code defines', async () => {
    const { stepNamed, stepAt } = await import('../src/pages/steps/stepsModel')
    const drafts = [{ name: 'RoundStep', savedName: 'RoundStep' }, { name: 'FinishStep', savedName: 'FinishStep' }, { name: 'Draft', savedName: null }]
    expect(stepNamed(drafts, 'RoundStep')).toBe(drafts[0])
    expect(stepNamed(drafts, 'finishStep')).toBe(drafts[1])
    expect(stepNamed(drafts, 'roundstep')).toBe(drafts[0])
    expect(stepNamed(drafts, 'draft')).toBe(drafts[2])
    expect(stepNamed(drafts, 'Missing')).toBe(null)
    expect(stepNamed(drafts, '')).toBe(null)
    expect(stepAt('RoundStep', 71)).toEqual({ path: '/steps', query: { step: 'RoundStep', line: '71' } })
    expect(stepAt('RoundStep', null)).toEqual({ path: '/steps', query: { step: 'RoundStep' } })
  })

  it('opens the step an error names, and says so when there is none', async () => {
    const steps = [{ id: 1, name: 'InitStep', source: 'initStep = 1' }, { id: 4, name: 'Apple', source: 'apple = 1' }]
    admin.experiment = { id: 7, name: 'Trust game', fileMode: false, steps }
    get.mockResolvedValue({ steps })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/steps', component: StepsPage }] })
    router.push('/steps?step=apple&line=1')
    await router.isReady()
    const w = mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router], stubs: { BCodeEditor: { template: '<textarea class="code-stub" />' } } } })
    try {
      await flushPromises()
      expect(w.find('.steps__title').text()).toBe('Apple')
      // once: the address no longer asks for it
      expect(router.currentRoute.value.query).toEqual({})
      toasts.splice(0, toasts.length)
      await router.push('/steps?step=Missing&line=3')
      await flushPromises()
      expect(toasts.some(t => t.title === 'There is no step “Missing”')).toBe(true)
      expect(w.find('.steps__title').text()).toBe('Apple')
    } finally {
      w.unmount()
    }
  })
})

describe('StepsPage while the engine runs another experiment', () => {
  const CodeStub = defineComponent({
    name: 'BCodeEditor',
    props: ['modelValue', 'onRun', 'onSave', 'language', 'fill', 'ariaLabel', 'placeholder', 'readonly'],
    emits: ['update:modelValue'],
    setup (props, { emit, expose }) {
      expose({ focus: () => {}, selection: () => '', insert: () => {} })
      return () => h('textarea', { class: 'code-stub', value: props.modelValue, readonly: props.readonly, onInput: (e: any) => emit('update:modelValue', e.target.value) })
    },
  })
  const steps = [{ id: 1, name: 'InitStep', source: 'initStep = 1' }]
  const RUN = 'The engine is running “Live study”’s session “Wave 2”, so this experiment’s steps can’t run in it until that session has ended.'
  const RELOAD = 'The engine is running “Live study”’s session “Wave 2”, and reloading it would end that session for everyone in it. End that session first, or open a new session of this experiment.'

  async function open () {
    admin.experiment = { id: 7, name: 'Trust game', fileMode: false, steps }
    get.mockResolvedValue({ steps })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/steps', component: StepsPage }] })
    router.push('/steps')
    await router.isReady()
    const w = mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router], stubs: { BCodeEditor: CodeStub } } })
    await flushPromises()
    return w
  }
  const blocked = (run: string, reload: string) => {
    ;(store as any).runBlocked.value = run
    ;(store as any).reloadBlocked.value = reload
  }

  it('turns Run off and says why; Cmd+Enter saves but does not run', async () => {
    blocked(RUN, RELOAD)
    try {
      const w = await open()
      const run = w.findAll('button').find(b => b.text() === 'Run')!
      expect(run.attributes('disabled')).toBeDefined()
      expect(run.attributes('title')).toBe(RUN)
      expect(w.find('.steps__hint.is-blocked').text()).toContain(RUN)
      expect(w.find('.steps__hint.is-blocked').text()).toContain('Saving works')

      await w.find('textarea.code-stub').setValue('initStep = 2')
      toasts.splice(0)
      post.mockResolvedValueOnce({ id: 1, name: 'InitStep', source: 'initStep = 2' })
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }))
      await flushPromises()
      expect(post).toHaveBeenCalledWith('/steps/1', { experimentId: 7, stepSource: 'initStep = 2', name: 'InitStep' })
      expect(actions.runStep).not.toHaveBeenCalled()
      const said = toasts.find(t => t.title === '“InitStep” was saved but not run')
      expect(said?.detail).toBe(RUN)
      w.unmount()
    } finally {
      blocked('', '')
      admin.engine = 'ready'
    }
  })

  it('says in file mode that a step file saved now isn’t loaded into the engine', async () => {
    const WAIT = 'The engine is running “Live study”’s session “Wave 2”; a saved file loads when this experiment’s next session starts.'
    blocked(RUN, RELOAD)
    ;(store as any).fileStepsWait.value = WAIT
    try {
      admin.experiment = { id: 7, name: 'Trust game', fileMode: true, steps }
      get.mockResolvedValue({ steps })
      const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/steps', component: StepsPage }] })
      router.push('/steps')
      await router.isReady()
      const w = mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router], stubs: { BCodeEditor: CodeStub } } })
      await flushPromises()
      const banner = w.find('.steps__banner.is-file')
      expect(banner.text()).toContain('Read from files.')
      expect(banner.text()).toContain(`A step file saved now isn’t loaded into the engine. ${WAIT}`)
      // with the engine running this experiment, nothing of the sort
      blocked('', '')
      ;(store as any).fileStepsWait.value = ''
      await nextTick()
      expect(w.find('.steps__banner.is-file').text()).not.toContain('isn’t loaded')
      w.unmount()
    } finally {
      blocked('', '')
      ;(store as any).fileStepsWait.value = ''
    }
  })

  it('turns Reload off over the other experiment’s live session, and says why', async () => {
    blocked(RUN, RELOAD)
    try {
      const w = await open()
      admin.engine = 'stale'
      admin.engineProblem = 'The engine did not finish reloading.'
      await nextTick()
      const reload = w.findAll('.steps__banner button').find(b => b.text() === 'Reload engine')!
      expect(reload.attributes('disabled')).toBeDefined()
      expect(reload.attributes('title')).toBe(RELOAD)

      // with no session there, the engine can be reloaded to load this experiment
      blocked('The engine has “Live study” loaded, not this one: reload the engine to load this experiment’s steps, then run.', '')
      await nextTick()
      expect(w.findAll('.steps__banner button').find(b => b.text() === 'Reload engine')!.attributes('disabled')).toBeUndefined()
      await w.findAll('.steps__banner button').find(b => b.text() === 'Reload engine')!.trigger('click')
      await flushPromises()
      expect(actions.reloadEngine).toHaveBeenCalled()
      w.unmount()
    } finally {
      blocked('', '')
      admin.engine = 'ready'
      admin.engineProblem = ''
    }
  })
})

describe('duplicating a step', () => {
  it('gives the copy its own step variable and name, so it doesn\'t replace the original', () => {
    const src = 'roundStep = stepFactory.createStep("RoundStep")\nroundStep.run = { roundStepCount++ }\nroundStep.done = { finishStep.start() }'
    const out = renameInSource(src, 'RoundStep', 'RoundStep copy')
    expect(out).toContain('roundStepCopy = stepFactory.createStep("RoundStep copy")')
    expect(out).toContain('roundStepCopy.run')
    // only whole names: another variable that starts the same way is left alone
    expect(out).toContain('roundStepCount++')
    expect(out).toContain('finishStep.start()')
  })
})
