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
  DEFAULTS, autopilotActivity, describeEngineSession, describeWaitingRoom, haltOf, humanSeconds, normalizeSettings, normalizeState,
  participantLinks, pauseFailure, pausedLine, problemKind, resumeLabel, stopPausesAutopilot, takeOverTarget, toForm, toSettings, validateAutopilot, waitingRoomLive,
  type AutopilotForm,
  stepsCall,
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

  it('takes the longest session in whole minutes, up to a day, or no limit', () => {
    expect(DEFAULTS.maxSessionMinutes).toBe(120)
    expect(validateAutopilot(form(f => { f.maxSessionMinutes = 0 }))).toHaveProperty('maxSessionMinutes', 'At least 1.')
    expect(validateAutopilot(form(f => { f.maxSessionMinutes = 1441 }))).toHaveProperty('maxSessionMinutes', 'At most a day (1440 minutes).')
    expect(validateAutopilot(form(f => { f.maxSessionMinutes = 2.5 }))).toHaveProperty('maxSessionMinutes', 'Use a whole number.')
    expect(validateAutopilot(form(f => { f.maxSessionMinutes = 1440 }))).toEqual({})
    // empty: no limit
    expect(validateAutopilot(form(f => { f.maxSessionMinutes = null }))).toEqual({})
    expect(toSettings(form(f => { f.maxSessionMinutes = null })).maxSessionMinutes).toBeNull()
    expect(toSettings(form(f => { f.maxSessionMinutes = 90 })).maxSessionMinutes).toBe(90)
    // also when the continuous switch is off: it applies to every session
    expect(validateAutopilot(form(f => { f.continuous = false; f.maxSessionMinutes = 0 }))).toHaveProperty('maxSessionMinutes')
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
  it('reads the longest session: a number, no limit (null), or the default from an older server', () => {
    expect(normalizeSettings({ maxSessionMinutes: 90 }).maxSessionMinutes).toBe(90)
    expect(normalizeSettings({ maxSessionMinutes: '45' }).maxSessionMinutes).toBe(45)
    expect(normalizeSettings({ maxSessionMinutes: null }).maxSessionMinutes).toBeNull()
    expect(normalizeSettings({}).maxSessionMinutes).toBe(120)
    expect(normalizeSettings({ maxSessionMinutes: 'soon' }).maxSessionMinutes).toBe(120)
  })
  it('reads the notice of a session ended for running too long', () => {
    const st = normalizeState({ status: { on: true, notice: 'Session "auto 3" was ended after 120 minutes, the longest a session may run.', noticeAt: '2026-10-29T10:05:00Z' } }).status
    expect(st.notice).toBe('Session "auto 3" was ended after 120 minutes, the longest a session may run.')
    expect(st.noticeAt).toBe('2026-10-29T10:05:00Z')
    expect(normalizeState({ status: { on: true } }).status).toMatchObject({ notice: null, noticeAt: null })
    expect(normalizeState({ status: { notice: ' ', noticeAt: 'later' } }).status).toMatchObject({ notice: null, noticeAt: null })
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
  it('knows when only Resume lets it open sessions again, from the server\'s flags first', () => {
    const st = (status: any, continuous = true) => normalizeState({ settings: { enabled: true, experimentId: 12, continuous }, status })
    expect(haltOf(st({ on: true, paused: true, problemKind: 'paused', problem: 'It is paused.' }))).toBe('paused')
    expect(haltOf(st({ on: true, done: true, problemKind: 'done', problem: 'Its session finished.' }))).toBe('done')
    // done by the server's flag, whatever else its message is about
    expect(haltOf(st({ on: true, done: true, problemKind: 'error', problem: 'There is no admin account to open sessions as.' }))).toBe('done')
    // an older server without "done": read from its message
    expect(haltOf(st({ on: true, problem: 'Its session "a" has finished, and opening the next session (continuous) is off.' }, false))).toBe('done')
    expect(haltOf(st({ on: true, problemKind: 'waiting', problem: 'Waiting.' }))).toBeNull()
    expect(haltOf(st({ on: false, paused: true }))).toBeNull()
    expect(haltOf(null)).toBeNull()
    expect(resumeLabel('paused')).toBe('Resume')
    expect(resumeLabel('done')).toBe('Open another session')
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

  it('says when the guards ended its session', () => {
    const list = autopilotActivity([
      { name: 'autopilotStart', datetime: '2026-10-29T10:01:00Z', data: { players: '2' } },
      { name: 'sessionTooLong', datetime: '2026-10-29T12:01:30Z', data: { minutes: '120', limit: '120' } },
    ])
    expect(list[0]).toMatchObject({ text: 'Ended: the game ran out of time (longest session: 120 min)', tone: 'warning' })
    expect(autopilotActivity([{ name: 'sessionStalled', datetime: '2026-10-29T10:03:00Z', data: { step: 'RoundStep', error: 'line 7: x' } }])[0])
      .toMatchObject({ text: 'Stopped: nothing was left to do after the step RoundStep failed', tone: 'danger' })
  })

  it('lists step failures that let the game go on, and the failures and stops that ended it', () => {
    const at = (m: number) => `2026-10-30T10:${String(m).padStart(2, '0')}:00Z`
    const list = autopilotActivity([
      { name: 'autopilotStart', datetime: at(1), data: { players: '2' } },
      { name: 'stepFailed', datetime: at(2), data: { step: 'RoundStep', error: 'line 72: No such property: roundNote' } },
      { name: 'stepFailed', datetime: at(3), data: { step: 'RoundStep', error: 'line 72: No such property: roundNote' } },
      { name: 'stepFailed', datetime: at(4), data: { step: 'FinishStep', error: 'line 9: boom' } },
      { name: 'sessionFailed', datetime: at(4), data: { step: 'FinishStep', line: '9', error: 'boom' } },
    ], 10)
    expect(list.map(a => [a.text, a.tone])).toEqual([
      ['Ended: FinishStep failed at line 9 and stalled the game', 'danger'],
      ['FinishStep failed at line 9', 'danger'],
      ['RoundStep failed at line 72; the game went on', 'warning'],
      ['RoundStep failed at line 72; the game went on', 'warning'],
      ['Game started with 2 people', 'success'],
    ])
    // a failure the game went on after, until it had nothing left to do
    expect(autopilotActivity([
      { name: 'stepFailed', datetime: at(2), data: { step: 'RoundStep', error: 'line 77: boom' } },
      { name: 'sessionStalled', datetime: at(4), data: { step: 'RoundStep', error: 'line 77: boom' } },
    ]).map(a => a.text)).toEqual(['Stopped: nothing was left to do after the step RoundStep failed', 'RoundStep failed at line 77'])
    expect(autopilotActivity([{ name: 'sessionStopped', datetime: at(5), data: { by: 'b@lab.org' } }])[0]).toMatchObject({ text: 'Stopped by b@lab.org', tone: 'warning' })
    expect(autopilotActivity([{ name: 'serverStopping', datetime: at(5), data: { reason: 'x' } }])[0]).toMatchObject({ text: 'Ended: the server was stopped' })
    expect(autopilotActivity([{ name: 'stepsFailedToLoad', datetime: at(5), data: { error: 'InitStep: line 24: No such property: five' } }])[0])
      .toMatchObject({ text: 'Stopped before anyone joined: InitStep failed at line 24 as the steps loaded', tone: 'danger' })
    expect(autopilotActivity([{ name: 'engineReloaded', datetime: at(5), data: { restarted: 'true', by: 'a@lab.org' } }])[0])
      .toMatchObject({ text: 'The engine was reloaded by a@lab.org; the game started again from the beginning', tone: 'info' })
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

  it('says the pause comes with a few seconds of restarting the scripts', async () => {
    http.get.mockResolvedValue(stateJson())
    const w = await mountPage()
    const hint = w.findAll('.b-field').find(f => f.text().includes('Pause before the next session'))!
    expect(hint.text()).toContain('Restarting the experiment\'s scripts adds a few seconds; 0 opens the next session a few seconds after one finishes.')
    expect(hint.text()).not.toContain('at once')
    w.unmount()
    expect(validateAutopilot(form(f => { f.nextSessionDelaySeconds = null })).nextSessionDelaySeconds).toBe('Enter a number of seconds (0 for no pause).')
  })

  it('does not save invalid settings, and brings the field into clear view', async () => {
    http.get.mockResolvedValue(stateJson())
    const scrolled = vi.fn()
    Element.prototype.scrollIntoView = scrolled
    const w = await mountPage()
    // the page's toasts keep above its save bar
    expect(document.documentElement.classList.contains('ap-lifts-toasts')).toBe(true)
    const min = w.findAll('input.b-input__el').find(i => i.attributes('min') === '1' && !i.attributes('placeholder'))!
    await min.setValue('0')
    expect(w.text()).toMatch(/At least 1\./)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    // the field with the problem, into the middle of the window (not behind the bar at its bottom)
    expect(scrolled).toHaveBeenCalledWith({ block: 'center' })
    expect(scrolled.mock.contexts[0]).toBe(min.element)
    expect(document.activeElement).toBe(min.element)
    w.unmount()
    expect(document.documentElement.classList.contains('ap-lifts-toasts')).toBe(false)
    delete (Element.prototype as any).scrollIntoView
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

  it('never set up: suggests the current experiment, and gives a lone participant ten minutes', async () => {
    http.get.mockResolvedValue(stateJson({ source: 'off', settings: { ...DEFAULTS, enabled: false, experimentId: null } }))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ settings: body }))
    const w = await mountPage()
    await w.find('.ap-sec input[role="switch"]').setValue(true)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    const sent = http.post.mock.calls[0][1]
    expect(sent.experimentId).toBe(12)
    expect(sent.waitingRoom).toMatchObject({ enabled: true, maxWaitSeconds: 600 })
    w.unmount()
  })

  it('explains a pause for a mistake in a step as the Steps page does, with a link to the step at that line', async () => {
    const tail = '. It opens no new session until someone presses Resume (or saves the settings) on the Autopilot page.'
    const loadFailed = 'It is paused: the steps of its session "Autopilot · Oct 30, 13:20:47 UTC" didn\'t load (InitStep: line 24: No such property: five), so that session was stopped before anyone could join. Fix the step on the Steps page first' + tail
    expect(pauseFailure(loadFailed)).toEqual({
      step: 'InitStep',
      line: 24,
      message: 'No such property: five',
      text: 'It is paused: the steps of its session "Autopilot · Oct 30, 13:20:47 UTC" didn\'t load, so that session was stopped before anyone could join. Fix the step on the Steps page first' + tail,
    })
    const stalled = 'It is paused: the step "RoundStep" failed during its session "A (1)" (line 71: No signature of method: foo() is applicable for argument types: (java.lang.String)), and then the game had nothing left to do, so that session was stopped. Fix the step on the Steps page first' + tail
    expect(pauseFailure(stalled)).toMatchObject({ step: 'RoundStep', line: 71, message: 'No signature of method: foo() is applicable for argument types: (java.lang.String)' })
    expect(pauseFailure('It is paused: the step "finishStep" failed during its session "S" (boom), so that session was stopped.')).toMatchObject({ step: 'finishStep', line: null, message: 'boom' })
    expect(pauseFailure('It is paused: its session "auto 1" was stopped by b@lab.org.')).toBeNull()

    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, paused: true, problemKind: 'paused', experimentId: 12, experimentName: 'Public goods', instance: null, links: null, problem: loadFailed },
    }))
    const w = await mountPage()
    const box = w.find('.apst__problem')
    // a mistake to fix, not just information
    expect(box.classes()).not.toContain('is-info')
    expect(box.text()).toContain('Unknown name “five”')
    expect(box.text()).toContain('Groovy doesn\'t know this name.')
    expect(box.find('.apst__problem-text').text()).not.toContain('No such property')
    const link = box.find('a.apst__open-step')
    expect(link.text()).toBe('Open InitStep at line 24')
    expect(link.attributes('href')).toBe('/steps?step=InitStep&line=24')
    w.unmount()
  })

  it('lists what its session recorded before the page opened, and what arrives live, in its recent activity', async () => {
    const recorded = [
      { id: 1, name: 'autopilotStart', datetime: '2026-10-30T10:01:00.000Z', eventData: [{ id: 1, name: 'players', value: '2' }] },
      { id: 2, name: 'stepFailed', datetime: '2026-10-30T10:02:00.000Z', eventData: [{ id: 2, name: 'step', value: 'RoundStep' }, { id: 3, name: 'error', value: 'line 72: No such property: x' }] },
    ]
    mockedAdmin.instance = { id: 4, name: 'auto 1', status: 'RUNNING', events: recorded } as any
    try {
      http.get.mockResolvedValue(stateJson({
        settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
        status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: { id: 4, name: 'auto 1', status: 'RUNNING' }, links: null },
      }))
      const w = await mountPage()
      const lines = () => w.findAll('.apst__activity li').map(li => li.text())
      expect(lines()).toHaveLength(2)
      expect(lines()[0]).toContain('RoundStep failed at line 72; the game went on')
      expect(lines()[1]).toContain('Game started with 2 people')
      // a failure arriving live
      mockedAdmin.events.push({ id: 3, experimentInstanceId: 4, name: 'stepFailed', datetime: '2026-10-30T10:03:00.000Z', data: { step: 'RoundStep', error: 'line 72: No such property: x' } } as any)
      await flushPromises()
      expect(lines()).toHaveLength(3)
      w.unmount()
    } finally {
      mockedAdmin.instance = null
      mockedAdmin.events.splice(0)
    }
  })

  it('says under "paused" why, in a line that matches the reason, or a neutral one', async () => {
    expect(pausedLine('It is paused: the steps of its session "S" didn\'t load (InitStep: line 24: x), so that session was stopped.')).toBe('The steps of its last session didn’t load, so it opens no new session until you press Resume.')
    expect(pausedLine('It is paused: the step "RoundStep" failed during its session "S" (line 71: x), so that session was stopped.')).toBe('A step failed in its last session, so it opens no new session until you press Resume.')
    expect(pausedLine('It is paused: 3 sessions in a row ran longer than 1 minute (the longest a session may run) and were ended, the last one "S".')).toBe('Its last sessions ran out of time, so it opens no new session until you press Resume.')
    expect(pausedLine('It is paused: its session "auto 1" was stopped by b@lab.org.')).toBe('Its session was stopped, so it opens no new session until you press Resume.')
    expect(pausedLine('It is paused: its session "auto 1" ended when b@lab.org reloaded the engine.')).toBe('Its session was ended from the console, so it opens no new session until you press Resume.')
    expect(pausedLine('It is paused.')).toBe('It opens no new session until you press Resume (see why below).')
    expect(pausedLine(null)).toBe('It opens no new session until you press Resume (see why below).')

    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, paused: true, problemKind: 'paused', experimentId: 12, experimentName: 'Public goods', instance: null, links: null, problem: 'It is paused: 3 sessions in a row ran longer than 1 minute (the longest a session may run) and were ended, the last one "S".' },
    }))
    const w = await mountPage()
    expect(w.find('.apst__sub').text()).toBe('Its last sessions ran out of time, so it opens no new session until you press Resume.')
    expect(w.find('.apst__sub').text()).not.toContain('was stopped')
    w.unmount()
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
    // the way out is right in the status card
    expect(w.find('.apst__resume').text()).toBe('Resume')
    // paused, it opens nothing when that timer runs out
    expect(w.text()).not.toMatch(/The next session opens at/)
    // without edits, the button resumes it as it is: no settings are sent
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
    expect(http.post.mock.calls[0]).toEqual(['/autopilot/resume'])
    expect(w.find('.apst__title').text()).toBe('The autopilot is on')
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
    expect(w.find('.apst__title').text()).toBe('The autopilot is done')
    expect(w.find('.apst__badges').text()).toBe('Done')
    expect(w.find('.apst__resume').text()).toBe('Open another session')
    expect(w.text()).not.toMatch(/The next session opens at/)
    expect(w.find('.ap-savebar').text()).toMatch(/Its one session has finished/)
    const submit = w.find('.ap-savebar button[type="submit"]')
    expect(submit.text()).toBe('Open another session')
    expect(submit.attributes('disabled')).toBeUndefined()

    // Cmd/Ctrl+S does what the button does: resume, sending no settings
    http.post.mockResolvedValueOnce(stateJson({ settings, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true }))
    await flushPromises()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0]).toEqual(['/autopilot/resume'])
    expect(toasts.some(t => t.title === 'Opening another session')).toBe(true)
    // nothing left to save
    expect(w.find('.ap-savebar button[type="submit"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('saves the edits of a paused autopilot as settings (which resumes it too)', async () => {
    const settings = { ...DEFAULTS, enabled: true, experimentId: 12 }
    http.get.mockResolvedValue(stateJson({
      settings,
      status: { on: true, paused: true, problemKind: 'paused', experimentId: 12, experimentName: 'Public goods', instance: null, links: null, problem: 'It is paused: its session "auto 1" was stopped.' },
    }))
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ settings: body, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }))
    const w = await mountPage()
    const countdown = w.findAll('input.b-input__el').find(i => i.attributes('min') === '0' && (i.element as HTMLInputElement).value === '5')!
    await countdown.setValue('10')
    expect(w.find('.ap-savebar button[type="submit"]').text()).toBe('Save')
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, body] = http.post.mock.calls[0]
    expect(url).toBe('/autopilot')
    expect(body).toMatchObject({ enabled: true, experimentId: 12, waitingRoom: { countdownSeconds: 10 } })
    w.unmount()
  })

  it('lets a paused or done autopilot from application.conf be resumed, and never asks to save it', async () => {
    const settings = { ...DEFAULTS, enabled: true, experimentId: 12, continuous: false }
    const resumed = stateJson({ source: 'config', settings, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } })
    for (const [status, label] of [
      [{ paused: true, problemKind: 'paused', problem: 'It is paused: its session "auto 1" was stopped by b@lab.org. It opens no new session until someone presses Resume on the Autopilot page.' }, 'Resume'],
      [{ done: true, problemKind: 'done', problem: 'Its session "auto 1" has finished and continuous is off, so it opens no more until someone presses Open another session on the Autopilot page.' }, 'Open another session'],
    ] as const) {
      http.get.mockReset()
      http.post.mockReset()
      toasts.splice(0)
      http.get.mockResolvedValue(stateJson({
        source: 'config',
        settings,
        status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: { id: 4, name: 'auto 1', status: 'FINISHED' }, links: null, ...status },
      }))
      const w = await mountPage()
      // read-only: no field can change, so there is nothing to discard or save
      expect(w.findAll('input.b-input__el').every(i => (i.element as HTMLInputElement).disabled)).toBe(true)
      expect(w.find('.ap-savebar').text()).not.toMatch(/Discard|Unsaved/)
      // the status says which button to press, and never to save
      expect(w.find(".apst__resume").text()).toBe(label)
      expect(w.find('.apst').text()).not.toMatch(/\bsav(e|ed|ing)\b/i)
      const submit = w.find('.ap-savebar button[type="submit"]')
      expect(submit.text()).toBe(label)
      expect(submit.attributes('disabled')).toBeUndefined()
      http.post.mockResolvedValueOnce(resumed)
      await submit.trigger('click')
      await flushPromises()
      expect(http.post).toHaveBeenCalledTimes(1)
      expect(http.post.mock.calls[0]).toEqual(['/autopilot/resume'])
      expect(toasts.some(t => t.title === (label === 'Resume' ? 'The autopilot is running again' : 'Opening another session'))).toBe(true)
      // resumed: nothing left to press on a read-only page
      expect(w.find('.ap-savebar').exists()).toBe(false)
      w.unmount()
    }
  })

  it('says why a resume was refused and shows what the server says now', async () => {
    const settings = { ...DEFAULTS, enabled: true, experimentId: 12 }
    http.get.mockResolvedValueOnce(stateJson({
      settings,
      status: { on: true, paused: true, problemKind: 'paused', experimentId: 12, experimentName: 'Public goods', instance: null, links: null, problem: 'It is paused.' },
    }))
    const w = await mountPage()
    http.post.mockRejectedValueOnce(new HttpError(400, 'The autopilot is off, so there is nothing to resume. Switch it on and save instead.'))
    http.get.mockResolvedValue(stateJson({ settings: { ...settings, enabled: false } }))
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post.mock.calls[0]).toEqual(['/autopilot/resume'])
    expect(w.find('.ap-error').text()).toBe('Not resumed. The autopilot is off, so there is nothing to resume. Switch it on and save instead.')
    expect(http.get).toHaveBeenCalledTimes(2)
    expect(w.find('.apst__title').text()).toBe('The autopilot is off')
    w.unmount()
  })

  it('shows the server\'s words when it waits for a researcher who has another experiment loaded', async () => {
    const problem = '"Trust" is loaded in the engine for b@lab.org, who has a console open, so the autopilot leaves the engine to them. It opens its own session once they close the console.'
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null, engineSession: null, problemKind: 'waiting', problem },
    }))
    const w = await mountPage()
    const box = w.find('.apst__problem')
    expect(box.classes()).toContain('is-info')
    expect(box.find('.apst__problem-title').text()).toBe('Waiting for the script engine')
    expect(box.find('.apst__problem-text').text()).toBe(problem)
    expect(w.text()).not.toMatch(/Waiting for another session to end/)
    // not paused or done: nothing to resume, and nothing unsaved
    const submit = w.find('.ap-savebar button[type="submit"]')
    expect(submit.text()).toBe('Save')
    expect(submit.attributes('disabled')).toBeDefined()
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

  it('shows the longest session with its default and saves it, or no limit once emptied', async () => {
    http.get.mockResolvedValue(stateJson())
    http.post.mockImplementation(async (_url: string, body: any) => stateJson({ settings: body }))
    const w = await mountPage()
    const field = () => w.findAll('.b-field').find(f => f.text().includes('Longest session'))!
    const input = () => field().find('input.b-input__el')
    expect((input().element as HTMLInputElement).value).toBe('120')
    expect(input().attributes('placeholder')).toBe('No limit')
    expect(field().text()).toMatch(/ran out of time\. Empty: no limit\. \(2 hours\)/)
    // in the section about sessions
    expect(w.find('section[aria-labelledby="ap-sec-sessions"]').text()).toMatch(/Longest session/)

    await input().setValue('0')
    expect(field().text()).toMatch(/At least 1\./)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()

    await input().setValue('')
    expect(field().text()).not.toMatch(/At least/)
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post.mock.calls[0][1]).toMatchObject({ maxSessionMinutes: null })
    expect((input().element as HTMLInputElement).value).toBe('')

    await input().setValue('90')
    await w.find('form.ap-form').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(2)
    expect(http.post.mock.calls[1][1]).toMatchObject({ maxSessionMinutes: 90 })
    w.unmount()
  })

  it('shows a session it ended for running too long, also while it runs on', async () => {
    const notice = 'Session "auto 3" was ended after 120 minutes, the longest a session may run. Its participants were told that the session ran out of time.'
    http.get.mockResolvedValue(stateJson({
      settings: { ...DEFAULTS, enabled: true, experimentId: 12 },
      status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: { id: 5, name: 'auto 4', status: 'RUNNING' }, links: null, notice, noticeAt: new Date().toISOString() },
    }))
    const w = await mountPage()
    const box = w.find('.apst__notice')
    expect(box.exists()).toBe(true)
    expect(box.attributes('role')).toBe('status')
    expect(box.find('.apst__problem-title').text()).toMatch(/^A session ran out of time \(at .+\)$/)
    expect(box.find('.apst__problem-text').text()).toBe(notice)
    // not a fault of the autopilot's: it is on, and nothing to resume
    expect(w.find('.apst__title').text()).toBe('The autopilot is on')
    expect(w.find('.apst__resume').exists()).toBe(false)
    w.unmount()

    http.get.mockResolvedValue(stateJson({ settings: { ...DEFAULTS, enabled: true, experimentId: 12 }, status: { on: true, experimentId: 12, experimentName: 'Public goods', instance: null, links: null } }))
    const w2 = await mountPage()
    expect(w2.find('.apst__notice').exists()).toBe(false)
    w2.unmount()
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

describe('the experiment\'s own autopilot calls', () => {
  it('finds a call in the steps, as written, and skips commented-out ones', () => {
    const steps = [
      { source: 'x = 1\n// autopilot.waitingRoom([minPlayers: 9])\n' },
      { source: 'autopilot.waitingRoom([minPlayers: 2, maxPlayers: NETWORK_SIZE,\n   waitSeconds: 20])\nautopilot.replaceDisconnected([afterSeconds: 30])' },
    ]
    expect(stepsCall(steps, 'waitingRoom')).toBe('autopilot.waitingRoom([minPlayers: 2, maxPlayers: NETWORK_SIZE, waitSeconds: 20])')
    expect(stepsCall(steps, 'replaceDisconnected')).toBe('autopilot.replaceDisconnected([afterSeconds: 30])')
    expect(stepsCall([{ source: 'nothing here' }], 'waitingRoom')).toBeNull()
  })
})
