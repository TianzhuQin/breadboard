import { describe, it, expect, beforeEach, vi } from 'vitest'

// The store's HTTP calls are answered here; the 401 handler it registers is kept for the tests.
const h = vi.hoisted(() => ({ unauthorized: null as null | ((body: any) => void) }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return {
    ...real,
    http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() },
    onUnauthorized: (fn: (body: any) => void) => { h.unauthorized = fn },
  }
})

import { http, HttpError } from '@/lib/http'
import { admin, actions, adoptUid, boot, connection, graph, logout, __receive } from '../src/store/admin'
import { confirmEngineRebuild, confirmSwitch } from '../src/store/engineGuard'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts } from '../src/ui/toast'

const get = vi.mocked(http.get)
const post = vi.mocked(http.post)
const tick = () => new Promise(r => setTimeout(r, 0))
const STALE = 'You are not signed in (or your sign-in has expired). Sign in again.'

function signedInAs (email: string, uid: string) {
  admin.auth = 'signedIn'
  admin.email = email
  admin.uid = uid
  admin.ready = true
}
/** What an account saw: output with an error, a session, a live event, a pending request. */
function somethingSeen () {
  __receive({ user: { email: admin.email, experiments: [], currentScript: '' }, experimentInstance: { id: 4, name: 'A', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] } })
  __receive({ error: 'Caught error: boom (participant 5f3c9a1b)' })
  __receive({ event: { id: 1, name: 'clientLogIn', datetime: '', data: { prolificPid: '5f3c9a1b' } } })
  __receive({ tieEdit: { ok: true, op: 'add', a: 'p1', b: 'p2' } })
  __receive({ action: 'addNode', id: 'p1' })
}
function expectForgotten () {
  expect(admin.user).toBe(null)
  expect(admin.instance).toBe(null)
  expect(admin.output).toEqual([])
  expect(admin.events).toEqual([])
  expect(admin.unseenErrors).toBe(0)
  expect(admin.requestError).toBe(null)
  expect(admin.lastTieEdit).toBe(null)
  expect(admin.engine).toBe('ready')
  expect(graph.nodes.size).toBe(0)
}

beforeEach(() => {
  vi.restoreAllMocks()
  get.mockReset()
  post.mockReset()
  toasts.splice(0, toasts.length)
  vi.spyOn(connection, 'start').mockImplementation(() => {})
})

describe('a sign-in changed in another tab', () => {
  it('reconnects with the current sign-in when the server refuses the old one', async () => {
    signedInAs('r@lab', 'old')
    get.mockResolvedValue({ uid: 'new', email: 'r@lab', connectSocket: 'auto' })
    __receive({ error: STALE, request: 'SelectExperiment' })
    await vi.waitFor(() => expect(connection.start).toHaveBeenCalledWith('new', 'auto'))
    expect(get).toHaveBeenCalledWith('/state', { quiet401: true })
    expect(admin.uid).toBe('new')
    expect(admin.auth).toBe('signedIn')
    expect(toasts.some(t => t.title === 'Reconnected')).toBe(true)
  })

  it('signs out instead of looping when the current sign-in is the one refused', async () => {
    signedInAs('r@lab', 'same')
    somethingSeen()
    get.mockResolvedValue({ uid: 'same', email: 'r@lab', connectSocket: 'auto' })
    __receive({ error: STALE, request: 'LogIn' })
    await vi.waitFor(() => expect(admin.auth).toBe('signedOut'))
    expect(connection.start).not.toHaveBeenCalled()
    expectForgotten()
  })

  it('signs out when there is no sign-in any more', async () => {
    signedInAs('r@lab', 'old')
    get.mockRejectedValue(new HttpError(401, 'Please sign in'))
    __receive({ error: STALE, request: 'RunGame' })
    await vi.waitFor(() => expect(admin.auth).toBe('signedOut'))
  })
})

describe('the server closing this console after a sign-out or password change elsewhere', () => {
  // what the server sends each console socket of the account that holds the old uid, before closing it
  const CLOSED = { error: 'You are not signed in any more: this account signed out or its password was changed. Sign in again.', status: 'unauthorized' }

  it('reconnects once, with the uid /state gives, and never with the old one again', async () => {
    signedInAs('r@lab', 'old')
    let answer!: (v: any) => void
    get.mockImplementationOnce(() => new Promise(r => { answer = r }))
    __receive(CLOSED)
    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/state', { quiet401: true })
    // the closed socket reconnects by itself meanwhile, still with the old uid, and is refused again
    __receive({ error: STALE, request: 'LogIn', status: 'unauthorized' })
    __receive(CLOSED)
    expect(get).toHaveBeenCalledTimes(1)
    answer({ uid: 'new', email: 'r@lab', connectSocket: 'auto' })
    await vi.waitFor(() => expect(connection.start).toHaveBeenCalledWith('new', 'auto'))
    expect(connection.start).toHaveBeenCalledTimes(1)
    expect(connection.start).not.toHaveBeenCalledWith('old', expect.anything())
    expect(admin.uid).toBe('new')
    expect(admin.auth).toBe('signedIn')
    // the new uid refused too (signed out again): /state gives it once more, so sign out, not loop
    get.mockResolvedValue({ uid: 'new', email: 'r@lab', connectSocket: 'auto' })
    const stop = vi.spyOn(connection, 'stop')
    __receive(CLOSED)
    await vi.waitFor(() => expect(admin.auth).toBe('signedOut'))
    expect(stop).toHaveBeenCalled()
    expect(connection.start).toHaveBeenCalledTimes(1)
  })

  it('signs out, and stops the connection, when the account signed out elsewhere', async () => {
    signedInAs('r@lab', 'old')
    somethingSeen()
    const stop = vi.spyOn(connection, 'stop')
    get.mockRejectedValue(new HttpError(401, 'Please sign in', { status: 'unauthorized' }))
    __receive(CLOSED)
    await vi.waitFor(() => expect(admin.auth).toBe('signedOut'))
    expect(stop).toHaveBeenCalled()
    expect(connection.start).not.toHaveBeenCalled()
    expectForgotten()
    // a late frame of the closed socket changes nothing
    __receive(CLOSED)
    await tick()
    expect(get).toHaveBeenCalledTimes(1)
  })
})

describe('a new password', () => {
  it('reconnects with the new sign-in at once, and tells the other tabs', async () => {
    get.mockResolvedValue({ uid: 'u1', email: 'r@lab', connectSocket: 'ws://lab/connect' })
    await boot()
    expect(connection.start).toHaveBeenLastCalledWith('u1', 'ws://lab/connect')
    const setItem = vi.fn()
    vi.stubGlobal('localStorage', { setItem, getItem: () => null, removeItem () {} })
    adoptUid('u2')
    expect(admin.uid).toBe('u2')
    expect(admin.auth).toBe('signedIn')
    expect(connection.start).toHaveBeenLastCalledWith('u2', 'ws://lab/connect')
    // (other tabs hear of it through the storage event)
    expect(setItem).toHaveBeenCalledWith('bb-admin-auth', expect.stringMatching(/^in /))
    // the same one again: nothing to do
    adoptUid('u2')
    adoptUid('')
    expect(connection.start).toHaveBeenCalledTimes(2)
    vi.unstubAllGlobals()
  })
})

describe('signing out', () => {
  it('forgets what the account saw once the server ended the session', async () => {
    signedInAs('r@lab', 'u1')
    somethingSeen()
    const stop = vi.spyOn(connection, 'stop')
    // the server answers /logout (a POST, so a link elsewhere can't sign anyone out) with 401 once the session is cleared
    post.mockRejectedValue(new HttpError(401, 'Please sign in'))
    expect(await logout()).toBe(true)
    expect(post).toHaveBeenCalledWith('/logout', undefined, { quiet401: true })
    expect(stop).toHaveBeenCalled()
    expect(admin.auth).toBe('signedOut')
    expectForgotten()
  })

  it('stays signed in, and says so, when the server could not be told', async () => {
    signedInAs('r@lab', 'u1')
    const stop = vi.spyOn(connection, 'stop')
    post.mockRejectedValue(new TypeError('Failed to fetch'))
    expect(await logout()).toBe(false)
    expect(admin.auth).toBe('signedIn')
    expect(stop).not.toHaveBeenCalled()
    expect(toasts.some(t => t.kind === 'error' && t.title === 'Could not sign out')).toBe(true)

    post.mockRejectedValue(new HttpError(502, 'Bad Gateway'))
    expect(await logout()).toBe(false)
    expect(admin.auth).toBe('signedIn')
  })

  it('forgets everything when the session expires, and answers pending requests', async () => {
    signedInAs('r@lab', 'u1')
    somethingSeen()
    vi.spyOn(connection, 'send').mockReturnValue(true)
    const pending = actions.selectInstance(4)
    h.unauthorized!({})
    expect(await pending).toBe(null)
    expect(admin.auth).toBe('signedOut')
    expectForgotten()
  })
})

describe('asking before an engine rebuild', () => {
  it('does not ask while nobody uses the engine', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    graph.clear()
    get.mockRejectedValue(new TypeError('Failed to fetch'))
    expect(await confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments' })).toBe(true)
    expect(confirmState.open).toBe(false)
  })

  it('names the players of a session that is not ours, and the autopilot\'s session', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    __receive({ action: 'addNode', id: 'p1' })
    __receive({ action: 'addNode', id: 'p2' })
    get.mockResolvedValue({ status: { on: true, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    const answer = confirmEngineRebuild({ title: 'Switch to “B”?', action: 'Switching experiments', confirmLabel: 'Switch anyway' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(get).toHaveBeenCalledWith('/autopilot', { quiet401: true })
    expect(confirmState.req).toMatchObject({ title: 'Switch to “B”?', danger: true, confirmLabel: 'Switch anyway' })
    expect(confirmState.req!.message).toContain('2 players are in the network')
    expect(confirmState.req!.message).toContain('The autopilot has session “Auto 3” open.')
    expect(confirmState.req!.message).toContain('disconnected')
    settleConfirm(false)
    expect(await answer).toBe(false)
    graph.clear()
    await tick()
  })

  it('asks before a switch the researcher didn\'t choose in the switcher, saying why first, and what it rebuilds under whom', async () => {
    const ask = 'InitStep, line 23, is a step of “Game A”. You are working on “Game B”.'
    const opts = { title: 'Switch to “Game A” to open InitStep?', action: 'Switching experiments', confirmLabel: 'Switch to “Game A”', ask }
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    graph.clear()

    // nobody uses the engine: asked all the same, as a plain question
    get.mockResolvedValue({ status: { on: false, instance: null } })
    let answer = confirmSwitch(opts)
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req).toMatchObject({ title: opts.title, message: ask, confirmLabel: 'Switch to “Game A”' })
    expect(confirmState.req!.danger).toBeFalsy()
    settleConfirm(true)
    expect(await answer).toBe(true)
    // (without a reason to give, as from the switcher: not asked)
    expect(await confirmSwitch({ title: 'Switch?', action: 'Switching experiments' })).toBe(true)
    expect(confirmState.open).toBe(false)

    // a session is live in the engine: the switch leaves it alone, so only the reason is said
    get.mockResolvedValue({ status: { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    answer = confirmSwitch(opts)
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toBe(ask)
    settleConfirm(false)
    expect(await answer).toBe(false)
    expect(await confirmSwitch({ title: 'Switch?', action: 'Switching experiments' })).toBe(true)

    // the switch rebuilds the engine under people in its network: the reason, then the warning
    __receive({ action: 'addNode', id: 'p1' })
    get.mockRejectedValue(new TypeError('Failed to fetch'))
    answer = confirmSwitch(opts)
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req).toMatchObject({ danger: true, confirmLabel: 'Switch to “Game A”' })
    expect(confirmState.req!.message).toMatch(/^InitStep, line 23, is a step of “Game A”\. You are working on “Game B”\.\n\n1 player is in the network right now, perhaps in another researcher's session\.\n\nSwitching experiments restarts the script engine/)
    settleConfirm(false)
    expect(await answer).toBe(false)
    graph.clear()
    await tick()
  })

  it('does not warn about the people a finished session left in the network', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    __receive({ action: 'addNode', id: 'p1' })
    get.mockResolvedValue({ status: { on: false, instance: { id: 9, name: 'Earlier', status: 'FINISHED' } } })
    expect(await confirmEngineRebuild({ title: 'New session?', action: 'Creating a session' })).toBe(true)
    expect(confirmState.open).toBe(false)
    graph.clear()
  })

  it('says the autopilot pauses when the rebuild ends its live session', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    graph.clear()
    const live = { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } }
    get.mockResolvedValue({ status: live })
    let answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments', detail: 'Session “Pilot 2” is stopped.' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('The autopilot has session “Auto 3” open.')
    expect(confirmState.req!.message).toMatch(/Session “Pilot 2” is stopped\.\n\nThis also pauses the autopilot: .* until someone presses Resume on the Autopilot page, so .*\.$/)
    settleConfirm(false)
    await answer

    // paused already, or switched off (its session still open): nothing more to pause
    for (const status of [{ ...live, paused: true }, { ...live, on: false }]) {
      get.mockResolvedValue({ status })
      answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments' })
      await vi.waitFor(() => expect(confirmState.open).toBe(true))
      expect(confirmState.req!.message).toContain('The autopilot has session “Auto 3” open.')
      expect(confirmState.req!.message).not.toContain('pauses the autopilot')
      settleConfirm(false)
      await answer
    }
  })

  it('names the session the engine runs, also another account\'s', async () => {
    const engine = { experimentId: 3, experimentName: 'Trust', sessionId: 12, sessionName: 'Pilot 2', owner: 'other@lab' }
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '', engine })
    graph.clear()
    get.mockRejectedValue(new TypeError('Failed to fetch'))
    let answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('other@lab has session “Pilot 2” of “Trust” running.')
    settleConfirm(false)
    expect(await answer).toBe(false)

    // the autopilot's session is named once
    get.mockResolvedValue({ status: { on: true, instance: { id: 12, name: 'Pilot 2', status: 'RUNNING' } } })
    answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('The autopilot has session “Pilot 2” open.')
    expect(confirmState.req!.message).not.toContain('other@lab')
    settleConfirm(false)
    await answer
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
  })

  it('asks the server what the engine runs now, also a session started since this console last heard', async () => {
    // this console asked when the engine said it rebuilt, before the other account's session had started
    const engine = { experimentId: 3, experimentName: 'Trust', sessionId: null, sessionName: null, owner: 'other@lab' }
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '', engine })
    graph.clear()
    const pilot = { id: 12, name: 'Pilot 2', experimentId: 3, experimentName: 'Trust', owner: 'other@lab' }
    get.mockResolvedValue({ status: { on: false, instance: null, engineSession: pilot } })
    let answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments', detail: 'Session “Pilot 2” is stopped.' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('other@lab has session “Pilot 2” of “Trust” running.')
    expect(confirmState.req!.message).toMatch(/the network is emptied\. Session “Pilot 2” is stopped\.$/)
    settleConfirm(false)
    expect(await answer).toBe(false)

    // named once when this console knows it too (this account's own: no name); the autopilot's session beside it
    admin.email = 'r@lab'
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '', engine: { ...engine, sessionId: 12, sessionName: 'Pilot 2' } })
    get.mockResolvedValue({ status: { on: true, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' }, engineSession: { ...pilot, id: '12', owner: 'R@Lab' } } })
    answer = confirmEngineRebuild({ title: 'Switch?', action: 'Switching experiments' })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    const message = confirmState.req!.message || ''
    expect(message).toContain('The autopilot has session “Auto 3” open. The engine is running session “Pilot 2” of “Trust”.')
    expect(message.match(/Pilot 2/g)!.length).toBe(1)
    settleConfirm(false)
    await answer
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
  })
})

describe('switching experiments', () => {
  it('does not show the ended session of the experiment selected before', () => {
    __receive({ user: { email: 'r@lab', experiments: [], currentScript: '' }, experiment: { id: 4, name: 'Other', instances: [] }, experimentInstance: { id: 9, name: 'Session 3', status: 'FINISHED' } })
    expect(admin.instance).toBeNull()
    __receive({ user: { email: 'r@lab', experiments: [], currentScript: '' }, experiment: { id: 4, name: 'Other', instances: [{ id: 9, name: 'Session 3', status: 'FINISHED' }] }, experimentInstance: { id: 9, name: 'Session 3', status: 'FINISHED' } })
    expect(admin.instance?.id).toBe(9)
  })
})

describe('what a reload or resume carries on', () => {
  it('leaves the session it carries on out of those it ends, and says nothing of the autopilot pausing then', async () => {
    const { engineUse, reloadKeeps } = await import('../src/store/engineGuard')
    admin.email = 'r@lab'
    const engine = { experimentId: 3, experimentName: 'Trust', sessionId: 9, sessionName: 'Auto 3', owner: 'r@lab', gameStarted: false }
    __receive({ user: { email: 'r@lab', experiments: [], currentScript: '' }, experiment: { id: 3, name: 'Trust', instances: [] }, experimentInstance: { id: 9, name: 'Auto 3', status: 'RUNNING' }, engine })
    graph.clear()
    get.mockResolvedValue({ status: { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    expect(reloadKeeps()).toBe(9)
    const kept = await engineUse(9)
    expect(kept).toEqual({ who: '', pausesAutopilot: false, endsSession: false })
    const ended = await engineUse()
    expect(ended.pausesAutopilot).toBe(true)
    expect(ended.endsSession).toBe(true)
    // (it is the autopilot's, though it runs as this account)
    expect(ended.who).toContain('The autopilot has session “Auto 3” open.')
    expect(ended.who).not.toContain('Your session')

    // another account's engine: a reload here doesn't carry it on, and the session is the autopilot's
    // or that account's, not this one's
    __receive({ user: { email: 'r@lab', experiments: [], currentScript: '' }, experiment: { id: 3, name: 'Trust', instances: [] }, experimentInstance: { id: 9, name: 'Auto 3', status: 'RUNNING' }, engine: { ...engine, owner: 'other@lab' } })
    expect(reloadKeeps()).toBeUndefined()
    expect((await engineUse()).who).toContain('The autopilot has session “Auto 3” open.')
    get.mockResolvedValue({ status: { on: false, instance: null } })
    expect((await engineUse()).who).toContain('other@lab has session “Auto 3” running.')
    get.mockResolvedValue({ status: { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
  })

  it('asks before a reload that carries on a session whose game is going or that has anyone connected, the autopilot\'s too', async () => {
    const { engineUse, reloadKeeps } = await import('../src/store/engineGuard')
    admin.email = 'r@lab'
    const engine = { experimentId: 3, experimentName: 'Trust', sessionId: 9, sessionName: 'Auto 3', owner: 'r@lab', gameStarted: true }
    const userJson = (eng: any) => __receive({ user: { email: 'r@lab', experiments: [], currentScript: '' }, experiment: { id: 3, name: 'Trust', instances: [] }, experimentInstance: { id: 9, name: 'Auto 3', status: 'RUNNING' }, engine: eng })
    userJson(engine)
    graph.clear()
    __receive({ action: 'addNode', id: 'p1' })
    __receive({ action: 'nodePropertyChanged', id: 'p1', key: '_system', value: { connected: true } })
    __receive({ action: 'addNode', id: 'p2' })
    __receive({ action: 'nodePropertyChanged', id: 'p2', key: '_system', value: { connected: true } })
    __receive({ action: 'addNode', id: '_1' })
    __receive({ action: 'nodePropertyChanged', id: '_1', key: '_system', value: { connected: true } })
    // the autopilot's own session, opened as this account: the server carries it on, and it isn't paused
    get.mockResolvedValue({ status: { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    expect(reloadKeeps()).toBe(9)
    let use = await engineUse(9)
    expect(use).toEqual({
      who: 'The autopilot’s session “Auto 3” carries on, but its game is in progress: reloading starts the game again from the beginning. The 2 people in it are disconnected for a moment and rejoin by themselves.',
      pausesAutopilot: false,
      endsSession: false,
    })
    const answer = confirmEngineRebuild({ title: 'Reload the script engine?', action: 'Reloading', confirmLabel: 'Reload anyway', keeping: 9 })
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toMatch(/^The autopilot’s session “Auto 3” carries on, but its game is in progress: .*rejoin by themselves\.\n\nReloading restarts the script engine/)
    expect(confirmState.req!.message).not.toContain('pauses the autopilot')
    settleConfirm(false)
    expect(await answer).toBe(false)

    // this account's own session, before its game: asked while anyone is connected
    get.mockResolvedValue({ status: { on: false, instance: null } })
    userJson({ ...engine, gameStarted: false })
    use = await engineUse(9)
    expect(use.who).toBe('Your session “Auto 3” carries on. The 2 people in it are disconnected for a moment and rejoin by themselves.')
    graph.clear()
    expect((await engineUse(9)).who).toBe('')
    // its game going, with nobody connected: the game starts again all the same
    userJson(engine)
    expect((await engineUse(9)).who).toBe('Your session “Auto 3” carries on, but its game is in progress: reloading starts the game again from the beginning.')
    // a stopped session that is resumed isn't in the engine: nothing of the sort
    userJson({ ...engine, sessionId: null, sessionName: null })
    expect((await engineUse(9)).who).toBe('')
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
  })
})
