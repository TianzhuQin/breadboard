import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})
vi.mock('@/store/admin', async () => {
  const { computed, reactive, ref, shallowRef } = await import('vue')
  return {
    admin: reactive({ experiment: { id: 12, name: 'Public goods' }, instance: null, events: [] }),
    experiments: computed(() => [{ id: 12, name: 'Public goods' }, { id: 13, name: 'Trust' }]),
    running: ref(false),
    graph: { version: shallowRef(0), nodes: new Map() },
  }
})
const confirmMock = vi.hoisted(() => vi.fn(async (_req: any) => true))
vi.mock('@/ui/confirm', () => ({ confirm: confirmMock, confirmState: { open: false, req: null, resolve: null }, settleConfirm: () => {} }))

import {
  DEFAULTS, autopilotActivity, describeWaitingRoom, humanSeconds, normalizeSettings, normalizeState, participantLinks,
  saveOpensSession, toForm, toSettings, validateAutopilot, waitingRoomLive, type AutopilotForm,
} from '../src/pages/autopilot/autopilot'
import { HttpError } from '../src/lib/http'
import { admin as mockedAdmin, running as mockedRunning } from '@/store/admin'
import AutopilotPage from '../src/pages/autopilot/AutopilotPage.vue'

const form = (patch: (f: AutopilotForm) => void = () => {}) => {
  const f = toForm(DEFAULTS)
  patch(f)
  return f
}

// ---- validation ----------------------------------------------------------------------------------

describe('autopilot form validation', () => {
  it('accepts the defaults', () => {
    expect(validateAutopilot(form())).toEqual({})
  })

  it('needs an experiment when switched on', () => {
    expect(validateAutopilot(form(f => { f.enabled = true }))).toHaveProperty('experimentId')
    expect(validateAutopilot(form(f => { f.enabled = true; f.experimentId = 12 }))).toEqual({})
  })

  it('needs at least one player, and a maximum no smaller than the minimum', () => {
    expect(validateAutopilot(form(f => { f.waitingRoom.minPlayers = 0 })))
      .toHaveProperty(['waitingRoom.minPlayers'], 'At least 1.')
    expect(validateAutopilot(form(f => { f.waitingRoom.minPlayers = null })))
      .toHaveProperty(['waitingRoom.minPlayers'])
    expect(validateAutopilot(form(f => { f.waitingRoom.minPlayers = 4; f.waitingRoom.maxPlayers = 3 })))
      .toHaveProperty(['waitingRoom.maxPlayers'], 'Can\'t be less than the minimum (4).')
    expect(validateAutopilot(form(f => { f.waitingRoom.minPlayers = 4; f.waitingRoom.maxPlayers = 4 }))).toEqual({})
    expect(validateAutopilot(form(f => { f.waitingRoom.maxPlayers = null }))).toEqual({})
  })

  it('wants whole, non-negative seconds within a day', () => {
    expect(validateAutopilot(form(f => { f.waitingRoom.waitSeconds = -1 }))).toHaveProperty(['waitingRoom.waitSeconds'], 'Can\'t be negative.')
    expect(validateAutopilot(form(f => { f.waitingRoom.countdownSeconds = 2.5 }))).toHaveProperty(['waitingRoom.countdownSeconds'], 'Use a whole number.')
    expect(validateAutopilot(form(f => { f.waitingRoom.maxWaitSeconds = 0 }))).toHaveProperty(['waitingRoom.maxWaitSeconds'])
    expect(validateAutopilot(form(f => { f.waitingRoom.maxWaitSeconds = 90000 }))).toHaveProperty(['waitingRoom.maxWaitSeconds'], 'At most a day (86400 seconds).')
    expect(validateAutopilot(form(f => { f.replaceDisconnected.afterSeconds = 0 }))).toHaveProperty(['replaceDisconnected.afterSeconds'])
    expect(validateAutopilot(form(f => { f.nextSessionDelaySeconds = null }))).toHaveProperty('nextSessionDelaySeconds')
  })

  it('ignores sections that are switched off', () => {
    const f = form(f => {
      f.continuous = false
      f.nextSessionDelaySeconds = null
      f.waitingRoom.enabled = false
      f.waitingRoom.minPlayers = 0
      f.replaceDisconnected.enabled = false
      f.replaceDisconnected.afterSeconds = null
    })
    expect(validateAutopilot(f)).toEqual({})
    // and what is sent still passes the server's checks
    const s = toSettings(f)
    expect(s.waitingRoom.minPlayers).toBe(DEFAULTS.waitingRoom.minPlayers)
    expect(s.replaceDisconnected.afterSeconds).toBe(DEFAULTS.replaceDisconnected.afterSeconds)
    expect(s.nextSessionDelaySeconds).toBe(DEFAULTS.nextSessionDelaySeconds)
  })

  it('sends empty optional fields as null', () => {
    const s = toSettings(form(f => { f.waitingRoom.maxPlayers = null; f.waitingRoom.maxWaitSeconds = null }))
    expect(s.waitingRoom.maxPlayers).toBeNull()
    expect(s.waitingRoom.maxWaitSeconds).toBeNull()
  })
})

describe('reading the server\'s answer', () => {
  it('fills in what is missing with defaults', () => {
    const s = normalizeSettings({ enabled: true, experimentId: '12', waitingRoom: { minPlayers: 4 } })
    expect(s.experimentId).toBe(12)
    expect(s.waitingRoom.minPlayers).toBe(4)
    expect(s.waitingRoom.waitSeconds).toBe(60)
    expect(s.replaceDisconnected).toEqual(DEFAULTS.replaceDisconnected)
  })
  it('reads the state, with unknown sources as "off"', () => {
    const st = normalizeState({ source: 'weird', status: { on: 1, instance: { id: 3, name: 'auto', status: 'RUNNING' } } })
    expect(st.source).toBe('off')
    expect(st.status.on).toBe(true)
    expect(st.status.instance).toEqual({ id: 3, name: 'auto', status: 'RUNNING' })
    expect(st.status.links).toBeNull()
    expect(st.status.problem).toBeNull()
  })
  it('keeps the server\'s reason why the autopilot can\'t run', () => {
    expect(normalizeState({ status: { on: false, problem: 'The experiment it was set to run (id 4) no longer exists.' } }).status.problem)
      .toBe('The experiment it was set to run (id 4) no longer exists.')
    expect(normalizeState({ status: { problem: '  ' } }).status.problem).toBeNull()
    expect(normalizeState({ status: { problem: 3 } }).status.problem).toBeNull()
  })
  it('knows when saving opens a session at once', () => {
    const status = (instance: any, experimentId = 12) => normalizeState({ status: { on: true, experimentId, instance } }).status
    const on = { enabled: true, experimentId: 12 }
    expect(saveOpensSession(on, status(null))).toBe(true)
    expect(saveOpensSession(on, status({ id: 3, name: 'a', status: 'FINISHED' }))).toBe(true)
    expect(saveOpensSession(on, status({ id: 3, name: 'a', status: 'RUNNING' }))).toBe(false)
    // the open session belongs to the experiment it ran before
    expect(saveOpensSession({ enabled: true, experimentId: 13 }, status({ id: 3, name: 'a', status: 'RUNNING' }))).toBe(true)
    expect(saveOpensSession({ enabled: false, experimentId: 12 }, status(null))).toBe(false)
  })
})

describe('words and links', () => {
  it('says durations plainly', () => {
    expect(humanSeconds(1)).toBe('1 second')
    expect(humanSeconds(45)).toBe('45 seconds')
    expect(humanSeconds(60)).toBe('1 minute')
    expect(humanSeconds(150)).toBe('2 min 30 s')
    expect(humanSeconds(7200)).toBe('2 hours')
  })

  it('describes the waiting room', () => {
    expect(describeWaitingRoom(form())).toMatch(/^Once 2 people are here, Breadboard waits 1 minute for more to join, then counts down 5 seconds/)
    expect(describeWaitingRoom(form(f => { f.waitingRoom.minPlayers = 4; f.waitingRoom.maxPlayers = 4 }))).toMatch(/^As soon as 4 people are here/)
    expect(describeWaitingRoom(form(f => { f.waitingRoom.maxWaitSeconds = 600 }))).toMatch(/10 minutes after the first person arrived, the session is called off/)
    expect(describeWaitingRoom(form(f => { f.waitingRoom.enabled = false }))).toMatch(/no waiting room/)
  })

  it('makes the stable participant links absolute', () => {
    const st = normalizeState({ status: { experimentId: 12, links: { login: '/play/12/login', prolific: '/play/12/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}' } } })
    expect(participantLinks(st.status, 12, 'https://lab.org')).toEqual({
      login: 'https://lab.org/play/12/login',
      prolific: 'https://lab.org/play/12/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}',
    })
  })

  it('builds the links itself when the server sends none', () => {
    const l = participantLinks(null, 7, 'http://localhost:9000/')!
    expect(l.login).toBe('http://localhost:9000/play/7/login')
    expect(l.prolific).toBe('http://localhost:9000/play/7/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}')
    expect(participantLinks(null, null, 'http://x')).toBeNull()
  })

  it('reads the waiting room from the players\' nodes, ignoring computer players', () => {
    const nodes = [
      { id: '_ai1', data: { _system: { autopilot: { state: 'waiting', here: 9 } } } },
      { id: 'p1', data: { _system: { connected: true, autopilot: { state: 'waiting', here: 2, min: 4, max: 8 } } } },
      { id: 'p2', data: { _system: { connected: true, autopilot: { state: 'waiting', here: 3, min: 4, max: 8 } } } },
      { id: 'p3', data: {} },
    ]
    expect(waitingRoomLive(nodes)).toEqual({ state: 'waiting', here: 3, min: 4, max: 8, seconds: null })
    expect(waitingRoomLive([])).toBeNull()
  })

  it('turns the autopilot\'s events into sentences, newest first', () => {
    const list = autopilotActivity([
      { name: 'clientLogIn', datetime: '2026-10-27T10:00:00Z', data: {} },
      { name: 'autopilotStart', datetime: '2026-10-27T10:01:00Z', data: { players: '4' } },
      { name: 'aiTakeover', datetime: '2026-10-27T10:05:00Z', data: { playerId: 'p2' } },
    ])
    expect(list.map(a => a.text)).toEqual(['The computer took over p2\'s seat', 'Game started with 4 people'])
  })
})

// ---- the page --------------------------------------------------------------------------------------

function stateJson (patch: any = {}) {
  return {
    source: 'admin',
    settings: { ...DEFAULTS, enabled: false, experimentId: 12 },
    status: { on: false, experimentId: 12, experimentName: 'Public goods', instance: null, links: null },
    ...patch,
  }
}

async function mountPage () {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: AutopilotPage }, { path: '/session', component: { template: '<div />' } }] })
  router.push('/')
  await router.isReady()
  const w = mount({ template: '<RouterView />' }, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return w
}

describe('Autopilot page', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
    confirmMock.mockClear()
    confirmMock.mockResolvedValue(true)
    document.body.innerHTML = ''
  })

  it('says the server needs an update when the API is missing (404)', async () => {
    http.get.mockRejectedValue(new HttpError(404, 'Not Found'))
    const w = await mountPage()
    expect(w.text()).toMatch(/needs a server update/)
    w.unmount()
  })

  it('shows settings from application.conf read-only and explains why', async () => {
    http.get.mockResolvedValue(stateJson({ source: 'config', settings: { ...DEFAULTS, enabled: true, experimentId: 12 }, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }))
    const w = await mountPage()
    expect(w.text()).toMatch(/come from the server's configuration file/)
    const inputs = w.findAll('input.b-input__el')
    expect(inputs.length).toBeGreaterThan(0)
    expect(inputs.every(i => (i.element as HTMLInputElement).disabled)).toBe(true)
    expect(w.find('.ap-savebar').exists()).toBe(false)
    // links are built from the experiment id when the server doesn't send them
    expect(w.find('.aplink__url').element.getAttribute('value') || (w.find('.aplink__url').element as HTMLInputElement).value).toMatch(/\/play\/12\/login$/)
    w.unmount()
  })

  it('does not save invalid settings', async () => {
    http.get.mockResolvedValue(stateJson())
    const w = await mountPage()
    const min = w.findAll('input.b-input__el').find(i => i.attributes('min') === '1' && !i.attributes('placeholder'))!
    await min.setValue('0')
    expect(w.text()).toMatch(/At least 1\./)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    w.unmount()
  })

  it('asks before turning on, then posts the settings', async () => {
    http.get.mockResolvedValue(stateJson())
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ settings: body, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: { login: '/play/12/login', prolific: '/play/12/prolific' } } }))
    const w = await mountPage()
    await w.find('.ap-sec input[role="switch"]').setValue(true)
    expect(w.text()).toMatch(/Unsaved changes/)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(confirmMock.mock.calls[0][0].title).toMatch(/Run “Public goods” unattended/)
    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, body] = http.post.mock.calls[0]
    expect(url).toBe('/autopilot')
    expect(body).toMatchObject({ enabled: true, experimentId: 12, waitingRoom: { minPlayers: 2 } })
    expect(w.text()).toMatch(/The autopilot is on/)
    expect(w.text()).not.toMatch(/Unsaved changes/)
    w.unmount()
  })

  it('warns before a save while it is on ends a session of another experiment', async () => {
    const on = { settings: { ...DEFAULTS, enabled: true, experimentId: 12 }, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }
    http.get.mockResolvedValue(stateJson(on))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ ...on, settings: body }))
    const a = mockedAdmin as any
    const r = mockedRunning as any
    a.experiment = { id: 13, name: 'Trust' }
    a.instance = { id: 7, name: 'Trust pilot', status: 'RUNNING' }
    r.value = true
    try {
      const w = await mountPage()
      const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
      await countdown.setValue('10')
      confirmMock.mockResolvedValueOnce(false)
      await w.find('form.ap-form').trigger('submit')
      await flushPromises()
      expect(confirmMock).toHaveBeenCalledTimes(1)
      const req = confirmMock.mock.calls[0][0]
      expect(req.danger).toBe(true)
      expect(req.title).toMatch(/Save and open a session of “Public goods”/)
      expect(req.message).toMatch(/“Trust pilot” appears to be running/)
      expect(http.post).not.toHaveBeenCalled()
      w.unmount()
    } finally {
      a.experiment = { id: 12, name: 'Public goods' }
      a.instance = null
      r.value = false
    }
  })

  it('saves without asking while its own session is open', async () => {
    const on = { settings: { ...DEFAULTS, enabled: true, experimentId: 12 }, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: { id: 4, name: 'auto 1', status: 'RUNNING' }, links: null } }
    http.get.mockResolvedValue(stateJson(on))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ ...on, settings: body }))
    const w = await mountPage()
    const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
    await countdown.setValue('10')
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(http.post).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('says why a switched-on autopilot isn\'t running', async () => {
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: false, experimentId: null, experimentName: null, instance: null, links: null, problem: 'The experiment it was set to run (id 12) no longer exists. Choose another one.' },
    }))
    const w = await mountPage()
    expect(w.find('.apst__title').text()).toBe('The autopilot can’t run')
    expect(w.find('.apst__problem').text()).toMatch(/no longer exists/)
    expect(w.text()).not.toMatch(/Nothing opens sessions by itself/)
    w.unmount()
  })

  it('shows the problem of a running autopilot and stops promising a session', async () => {
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, problem: 'The last session it opened reported an error: boom' },
    }))
    const w = await mountPage()
    expect(w.find('.apst__problem').text()).toMatch(/reported an error: boom/)
    expect(w.text()).not.toMatch(/opens one shortly/)
    w.unmount()
  })

  it('shows the server\'s refusal in words', async () => {
    http.get.mockResolvedValue(stateJson())
    http.post.mockRejectedValue(new HttpError(400, 'maxPlayers must be at least minPlayers'))
    const w = await mountPage()
    const min = w.findAll('input.b-input__el').find(i => i.attributes('min') === '1' && !i.attributes('placeholder'))!
    await min.setValue('3')
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(w.find('.ap-error').text()).toMatch(/maxPlayers must be at least minPlayers/)
    w.unmount()
  })
})
