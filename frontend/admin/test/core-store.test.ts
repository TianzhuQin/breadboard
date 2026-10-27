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
import { confirmEngineRebuild } from '../src/store/engineGuard'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts } from '../src/ui/toast'

const get = vi.mocked(http.get)
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
    // the server answers /logout with 401 once the session is cleared
    get.mockRejectedValue(new HttpError(401, 'Please sign in'))
    expect(await logout()).toBe(true)
    expect(stop).toHaveBeenCalled()
    expect(admin.auth).toBe('signedOut')
    expectForgotten()
  })

  it('stays signed in, and says so, when the server could not be told', async () => {
    signedInAs('r@lab', 'u1')
    const stop = vi.spyOn(connection, 'stop')
    get.mockRejectedValue(new TypeError('Failed to fetch'))
    expect(await logout()).toBe(false)
    expect(admin.auth).toBe('signedIn')
    expect(stop).not.toHaveBeenCalled()
    expect(toasts.some(t => t.kind === 'error' && t.title === 'Could not sign out')).toBe(true)

    get.mockRejectedValue(new HttpError(502, 'Bad Gateway'))
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
