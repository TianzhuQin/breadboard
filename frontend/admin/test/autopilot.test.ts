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
  DEFAULTS, autopilotActivity, describeEngineSession, describeWaitingRoom, humanSeconds, normalizeSettings, normalizeState,
  participantLinks, problemKind, stopPausesAutopilot, takeOverTarget, toForm, toSettings, validateAutopilot, waitingRoomLive,
  type AutopilotForm,
} from '../src/pages/autopilot/autopilot'
import { HttpError } from '../src/lib/http'
import { unsavedTotal } from '../src/shell/unsaved'
import { toasts } from '../src/ui/toast'
import { admin as mockedAdmin } from '@/store/admin'
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
  it('reads the pause, the next opening and the session that holds the engine', () => {
    const st = normalizeState({
      status: {
        on: true,
        paused: true,
        nextOpenAt: '2026-10-27T14:05:10Z',
        engineSession: { id: '9', name: 'pilot', experimentId: 13, experimentName: 'Trust', owner: 'b@lab.org' },
      },
    }).status
    expect(st.paused).toBe(true)
    expect(st.nextOpenAt).toBe('2026-10-27T14:05:10Z')
    expect(st.engineSession).toEqual({ id: 9, name: 'pilot', experimentId: 13, experimentName: 'Trust', owner: 'b@lab.org' })
    // an older server sends none of them
    const old = normalizeState({ status: { on: true, paused: 'yes', nextOpenAt: 'soon', engineSession: { name: 'x' } } }).status
    expect(old).toMatchObject({ paused: false, nextOpenAt: null, engineSession: null })
  })
  it('knows which live session a save would have to end, from what the server says', () => {
    const status = (patch: any) => normalizeState({ status: { on: true, experimentId: 12, experimentName: 'Public goods', ...patch } }).status
    const on = { enabled: true, experimentId: 12 }
    const other = { id: 9, name: 'pilot', experimentId: 13, experimentName: 'Trust', owner: 'b@lab.org' }
    expect(takeOverTarget(on, status({ engineSession: other }))).toEqual(other)
    expect(takeOverTarget({ enabled: false, experimentId: 12 }, status({ engineSession: other }))).toBeNull()
    // the session it holds is of the experiment the save chooses: nothing to end
    expect(takeOverTarget({ enabled: true, experimentId: 13 }, status({ engineSession: other }))).toBeNull()
    expect(takeOverTarget(on, status({ instance: { id: 3, name: 'auto 1', status: 'RUNNING' } }))).toBeNull()
    // moving the autopilot to another experiment while its own session is open
    expect(takeOverTarget({ enabled: true, experimentId: 13 }, status({ instance: { id: 3, name: 'auto 1', status: 'RUNNING' } })))
      .toEqual({ id: 3, name: 'auto 1', experimentId: 12, experimentName: 'Public goods', owner: null })
    expect(takeOverTarget({ enabled: true, experimentId: 13 }, status({ instance: { id: 3, name: 'auto 1', status: 'FINISHED' } }))).toBeNull()
    expect(describeEngineSession(other)).toBe('“pilot” of “Trust”, run by b@lab.org')
    expect(describeEngineSession({ ...other, experimentName: null, owner: null })).toBe('“pilot”')
  })
  it('tells waiting by design apart from a fault', () => {
    const kind = (status: any, continuous = true) => problemKind(normalizeState({ settings: { enabled: true, experimentId: 12, continuous }, status }))
    expect(kind({ on: true })).toBeNull()
    expect(kind({ on: true, paused: true, problem: 'It is paused: its session "a" was stopped.' })).toBe('paused')
    expect(kind({ on: true, problem: 'Its session "a" has finished, and opening the next session (continuous) is off.' }, false)).toBe('done')
    expect(kind({ on: true, engineSession: { id: 9, name: 'pilot' }, problem: 'Another session is running: "pilot".' })).toBe('waiting')
    expect(kind({ on: true, problem: 'The last session it opened reported an error: boom' })).toBe('error')
  })
  it('does not call a real problem "waiting" just because another session is live', () => {
    const kind = (status: any) => problemKind(normalizeState({ settings: { enabled: true, experimentId: 12 }, status }))
    const other = { id: 9, name: 'pilot 3', experimentId: 13 }
    // the server names every live session, also when the problem comes before it
    expect(kind({ on: true, engineSession: other, problem: 'There is no admin account to open sessions as.' })).toBe('error')
    // application.conf names an experiment that doesn't exist: nothing runs, whatever else is live
    expect(kind({
      on: false,
      engineSession: other,
      problem: 'application.conf names the experiment "Trust gme" (breadboard.autopilot.experiment), but there is no experiment with that name.',
    })).toBe('error')
  })
  it('follows the server when it says what the problem is', () => {
    const st = (status: any, continuous = true) => normalizeState({ settings: { enabled: true, experimentId: 12, continuous }, status })
    expect(st({ on: true, done: true, problemKind: 'done', problem: 'Its session finished.' }).status).toMatchObject({ done: true, problemKind: 'done' })
    expect(problemKind(st({ on: true, done: true, problem: 'Its session finished.' }))).toBe('done')
    expect(problemKind(st({ on: true, problemKind: 'waiting', problem: 'Waiting for “pilot”.' }))).toBe('waiting')
    // unknown kinds and older servers fall back to reading the status
    expect(st({ problemKind: 'weird', done: 'yes' }).status).toMatchObject({ done: false, problemKind: null })
  })
  it('knows when stopping a session pauses the autopilot', () => {
    const ap = (status: any, settings: any = { enabled: true, experimentId: 12 }) => normalizeState({ source: 'admin', settings, status })
    const own = { id: 4, name: 'auto 1', status: 'RUNNING' }
    expect(stopPausesAutopilot(ap({ on: true, experimentId: 12, instance: own }), { id: 4, experimentId: 12 })).toBe(true)
    expect(stopPausesAutopilot(ap({ on: true, experimentId: 12, instance: own }), { id: 5, experimentId: 12 })).toBe(false)
    // a test session the engine runs is left out of status.instance
    expect(stopPausesAutopilot(ap({ on: true, experimentId: 12, instance: null }), { id: 7, experimentId: 12 }, 7)).toBe(true)
    expect(stopPausesAutopilot(ap({ on: true, experimentId: 12, instance: null }), { id: 7, experimentId: 13 }, 7)).toBe(false)
    // off, already paused, or no answer: nothing to say
    expect(stopPausesAutopilot(ap({ on: false, experimentId: 12, instance: own }), { id: 4, experimentId: 12 })).toBe(false)
    expect(stopPausesAutopilot(ap({ on: true, paused: true, experimentId: 12, instance: own }), { id: 4, experimentId: 12 })).toBe(false)
    expect(stopPausesAutopilot(null, { id: 4, experimentId: 12 })).toBe(false)
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

  it('waits for another experiment\'s session by default, and ends it only after a danger confirmation', async () => {
    const pilot = { id: 9, name: 'Trust pilot', experimentId: 13, experimentName: 'Trust', owner: 'b@lab.org' }
    const on = { settings: { ...DEFAULTS, enabled: true, experimentId: 12 }, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, engineSession: pilot, problem: 'Another session is running: "Trust pilot" (b@lab.org). The autopilot opens its own session when that one ends.' } }
    http.get.mockResolvedValue(stateJson(on))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ ...on, settings: body }))
    const w = await mountPage()
    // shown as information, naming the session, its experiment and who runs it
    expect(w.find('.apst__problem').classes()).toContain('is-info')
    expect(w.text()).not.toMatch(/has a problem/)
    expect(w.find('.apst__problem').text()).toMatch(/Waiting for another session to end/)
    expect(w.find('.ap-holder').text()).toMatch(/“Trust pilot” of “Trust”, run by b@lab\.org is running/)
    expect(w.find('.ap-holder').text()).toMatch(/waits for that session to end/)

    // a plain save never ends it
    const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
    await countdown.setValue('10')
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][1]).not.toHaveProperty('takeOver')

    // "Save and end it now" asks first (danger), naming all three
    confirmMock.mockResolvedValueOnce(false)
    await w.find('.ap-holder button').trigger('click')
    await flushPromises()
    const req = confirmMock.mock.calls[0][0]
    expect(req.danger).toBe(true)
    expect(req.title).toBe('End “Trust pilot” and run “Public goods”?')
    expect(req.message).toMatch(/“Trust pilot” of “Trust”, run by b@lab\.org/)
    expect(req.message).toMatch(/waits for it to end/)
    expect(http.post).toHaveBeenCalledTimes(1)

    await w.find('.ap-holder button').trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(2)
    expect(http.post.mock.calls[1][1]).toMatchObject({ enabled: true, experimentId: 12, takeOver: true })
    w.unmount()
  })

  it('says it waits when turning on while another session holds the engine', async () => {
    const pilot = { id: 9, name: 'Trust pilot', experimentId: 13, experimentName: 'Trust', owner: null }
    http.get.mockResolvedValue(stateJson({ status: { on: false, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, engineSession: pilot } }))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ settings: body, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, engineSession: pilot } }))
    const a = mockedAdmin as any
    // the console's engine info names the owner the autopilot's status left out
    a.user = { engine: { experimentId: 13, experimentName: 'Trust', sessionId: 9, sessionName: 'Trust pilot', owner: 'b@lab.org' } }
    try {
      const w = await mountPage()
      await w.find('.ap-sec input[role="switch"]').setValue(true)
      await w.find('form.ap-form').trigger('submit')
      await flushPromises()
      const req = confirmMock.mock.calls[0][0]
      expect(req.danger).toBeFalsy()
      expect(req.message).toMatch(/“Trust pilot” of “Trust”, run by b@lab\.org is running on the script engine, so Breadboard waits for it to end/)
      expect(http.post.mock.calls[0][1]).not.toHaveProperty('takeOver')
      w.unmount()
    } finally {
      a.user = undefined
    }
  })

  it('shows a paused autopilot and when the next session opens, as information', async () => {
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: {
        on: true,
        paused: true,
        experimentId: 12,
        experimentName: 'Public goods',
        instance: { id: 4, name: 'auto 1', status: 'STOPPED' },
        links: null,
        // another experiment's session finished meanwhile, which sets the server's timer all the same
        nextOpenAt: new Date(Date.now() + 600000).toISOString(),
        problem: 'It is paused: its session "auto 1" was stopped by b@lab.org. It opens no new session until the settings are saved again.',
      },
    }))
    const w = await mountPage()
    expect(w.find('.apst__title').text()).toBe('The autopilot is paused')
    expect(w.find('.apst__badges').text()).toMatch(/Paused/)
    expect(w.find('.apst__problem').classes()).toContain('is-info')
    expect(w.find('.apst__problem').attributes('role')).toBe('status')
    expect(w.text()).toMatch(/Press Resume below to start again/)
    // paused, it opens nothing when that timer runs out
    expect(w.text()).not.toMatch(/The next session opens at/)
    // unchanged settings can still be saved, to resume it
    const submit = w.find('.ap-savebar button[type="submit"]')
    expect(submit.text()).toBe('Resume')
    expect(submit.attributes('disabled')).toBeUndefined()
    http.post.mockResolvedValueOnce(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null },
    }))
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][1]).toMatchObject({ enabled: true, experimentId: 12 })
    w.unmount()

    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, nextOpenAt: new Date(Date.now() + 8000).toISOString() },
    }))
    const w2 = await mountPage()
    expect(w2.text()).toMatch(/The next session opens at/)
    expect(w2.text()).not.toMatch(/opens one shortly/)
    w2.unmount()
  })

  it('lets a done autopilot open another session without changing anything', async () => {
    const settings = { ...DEFAULTS, enabled: true, experimentId: 12, continuous: false }
    http.get.mockResolvedValue(stateJson({
      settings,
      status: {
        on: true,
        experimentId: 12,
        experimentName: 'Public goods',
        instance: { id: 4, name: 'auto 1', status: 'FINISHED' },
        links: null,
        nextOpenAt: new Date(Date.now() + 600000).toISOString(),
        problem: 'Its session "auto 1" has finished, and opening the next session (continuous) is off, so it opens no more. Save the settings to open another.',
      },
    }))
    toasts.splice(0)
    const w = await mountPage()
    expect(w.find('.apst__problem').classes()).toContain('is-info')
    expect(w.find('.apst__problem').text()).toMatch(/Its one session has finished/)
    expect(w.find('.apst__sub').text()).toMatch(/Press Open another session below/)
    expect(w.text()).not.toMatch(/The next session opens at/)
    expect(w.find('.ap-savebar').text()).toMatch(/Its one session has finished/)
    const submit = w.find('.ap-savebar button[type="submit"]')
    expect(submit.text()).toBe('Open another session')
    expect(submit.attributes('disabled')).toBeUndefined()

    // Cmd/Ctrl+S does what the button does
    http.post.mockResolvedValueOnce(stateJson({ settings, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    await flushPromises()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][1]).toMatchObject({ enabled: true, experimentId: 12, continuous: false })
    expect(toasts.some(t => t.title === 'Opening another session')).toBe(true)
    // nothing left to save
    expect(w.find('.ap-savebar button[type="submit"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('says a paused autopilot opens nothing, even once another session ends', async () => {
    const pilot = { id: 9, name: 'Trust pilot', experimentId: 13, experimentName: 'Trust', owner: 'b@lab.org' }
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, paused: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, engineSession: pilot, problem: 'It is paused: its session "auto 1" was stopped.' },
    }))
    const w = await mountPage()
    expect(w.find('.ap-holder').text()).toMatch(/The autopilot is paused, so it opens nothing until you press Resume/)
    expect(w.find('.ap-holder').text()).not.toMatch(/The autopilot waits for that session to end/)
    // once edited, saving resumes it: then it waits for that session
    const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
    await countdown.setValue('10')
    expect(w.find('.ap-holder').text()).toMatch(/When you save, the autopilot waits for that session to end/)
    w.unmount()
  })

  it('shows the real problem, not "waiting", when the configured experiment does not exist', async () => {
    http.get.mockResolvedValue(stateJson({
      source: 'config',
      settings: { ...DEFAULTS, enabled: true, experimentId: null },
      status: {
        on: false,
        experimentId: null,
        experimentName: null,
        instance: null,
        links: null,
        engineSession: { id: 9, name: 'pilot 3', experimentId: 13, experimentName: 'Trust', owner: null },
        problem: 'application.conf names the experiment "Trust gme" (breadboard.autopilot.experiment), but there is no experiment with that name.',
      },
    }))
    const w = await mountPage()
    expect(w.find('.apst__title').text()).toBe('The autopilot can’t run')
    expect(w.find('.apst__problem').attributes('role')).toBe('alert')
    expect(w.find('.apst__problem').text()).toMatch(/there is no experiment with that name/)
    expect(w.text()).not.toMatch(/Waiting for another session/)
    w.unmount()
  })

  it('counts unsaved settings when signing out, while the page is open', async () => {
    http.get.mockResolvedValue(stateJson())
    const w = await mountPage()
    expect(unsavedTotal()).toBe(0)
    const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
    await countdown.setValue('10')
    expect(unsavedTotal()).toBe(1)
    w.unmount()
    expect(unsavedTotal()).toBe(0)
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
