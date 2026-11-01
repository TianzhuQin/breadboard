import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('@/lib/http', () => ({
  http: { get: vi.fn(), post: vi.fn().mockResolvedValue({}) },
  HttpError: class extends Error {},
  onUnauthorized: vi.fn(),
}))

import { http } from '@/lib/http'
import { admin, boot, connection, logout, __receive } from '../src/store/admin'
import AppShell from '../src/shell/AppShell.vue'

const OWNER = 'owner@example.org'
const OTHER = 'operator@example.org'
const old = { id: 40, name: 'Original session', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] }
const engine = { experimentId: 1, experimentName: 'Example', sessionId: old.id, sessionName: old.name, owner: OWNER }
const experiment = (instances: any[]) => ({ id: 1, name: 'Example', instances })

function picture (instance: any = old, running: any = engine) {
  __receive({ user: { email: OWNER, experiments: [], currentScript: '' }, experiment: experiment([instance]), experimentInstance: instance, engine: running })
}
function stopped (reason = 'newSession', changes: any = {}) {
  return { event: { id: 101, experimentInstanceId: old.id, name: 'sessionStopped', datetime: '2026-11-01T12:00:00Z', data: { by: OTHER, reason }, ...changes } }
}
async function shell () {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/steps', component: { render: () => h('p', 'Steps') } },
    { path: '/parameters', component: { render: () => h('p', 'Parameters') } },
  ] })
  await router.push('/steps')
  await router.isReady()
  const w = mount(AppShell, { global: { plugins: [router], stubs: {
    SideNav: true, TopBar: true, ConnectionNotice: true, EngineNotice: true, OutputDrawer: true,
  } } })
  return { w, router }
}

beforeEach(async () => {
  await logout()
  admin.auth = 'signedIn'
  admin.email = OWNER
  picture()
})
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

describe('another account ending the owner’s session', () => {
  it.each([
    ['newSession', 'created a new session'],
    ['otherSession', 'resumed or selected another session'],
    ['reloadEngine', 'reloaded the engine'],
  ])('keeps the %s notice after automatic follow, navigation and a minute', async (reason, action) => {
    const { w, router } = await shell()
    __receive(stopped(reason))
    // The server moves every account following this experiment to the replacement session. Its
    // User JSON clears the old live feed, even when the owner is looking at Steps, as here.
    const replacement = { ...old, id: 41, name: 'Replacement session' }
    picture(replacement, { ...engine, sessionId: 41, sessionName: replacement.name, owner: OTHER })
    await nextTick()
    expect(admin.instance?.id).toBe(41)
    expect(admin.events).toEqual([])
    const notice = () => w.find('.session-end-notice')
    expect(notice().text()).toContain('Original session')
    expect(notice().text()).toContain(OTHER)
    expect(notice().text()).toContain(action)
    await router.push('/parameters')
    vi.useFakeTimers()
    await vi.advanceTimersByTimeAsync(60000)
    expect(notice().attributes('role')).toBe('status')
    await notice().get('button[aria-label="Dismiss session end notice"]').trigger('click')
    expect(notice().exists()).toBe(false)
    // A repeated event must not reopen an acknowledged notice, even with an old state snapshot.
    picture()
    __receive(stopped(reason))
    await nextTick()
    expect(notice().exists()).toBe(false)
    w.unmount()
  })

  it('does not announce historical events loaded in a User JSON', async () => {
    const { w } = await shell()
    picture({ ...old, status: 'STOPPED', events: [{ ...stopped().event, eventData: [{ name: 'by', value: OTHER }, { name: 'reason', value: 'newSession' }] }] })
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(false)
    w.unmount()
  })

  it('ignores own actions, another owner’s session, other session IDs and missing identities', async () => {
    const { w } = await shell()
    __receive(stopped('newSession', { data: { by: OWNER.toUpperCase(), reason: 'newSession' } }))
    __receive(stopped('newSession', { experimentInstanceId: 99 }))
    __receive(stopped('newSession', { data: { reason: 'newSession' } }))
    picture(old, { ...engine, owner: OTHER })
    __receive(stopped())
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(false)
    w.unmount()
  })

  it('still reaches an owner who has another experiment selected', async () => {
    const { w } = await shell()
    __receive({ user: { email: OWNER, experiments: [], currentScript: '' }, experiment: { id: 2, name: 'Other experiment', instances: [] }, engine })
    __receive(stopped())
    await nextTick()
    expect(w.find('.session-end-notice').text()).toContain('Original session')
    expect(admin.events).toEqual([])
    w.unmount()
  })

  it('clears account information on sign-out and when another account signs in', async () => {
    const { w } = await shell()
    __receive(stopped())
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(true)
    await logout()
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(false)
    admin.auth = 'signedIn'
    admin.email = OWNER
    picture()
    __receive(stopped()) // signing out also forgot the old account’s deduplication keys
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(true)
    vi.mocked(http.get).mockResolvedValue({ uid: 'another-uid', email: OTHER, connectSocket: 'auto' })
    vi.spyOn(connection, 'start').mockImplementation(() => {})
    await boot()
    await nextTick()
    expect(w.find('.session-end-notice').exists()).toBe(false)
    w.unmount()
  })
})
