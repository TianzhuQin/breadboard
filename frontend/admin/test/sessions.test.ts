import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick, reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { HttpError } from '../src/lib/http'
import {
  canDelete, canResume, canStop, csvDataRows, csvField, describeError, eventNameCounts, eventsToCsv, eventsToWideCsv,
  filterEvents, formatClock, groupEvents, matchesSession, mergeEvents, normalizeEvent, parseServerTime, safeFileName,
  sortEvents, sortSessions, toCsv, waitUntil,
} from '../src/pages/sessions/sessionData'

// ---- mocks for the component and action tests ---------------------------------------------------

vi.mock('@/store/admin', async () => {
  const { reactive, computed, ref } = await import('vue')
  const admin = reactive({
    experiment: null as any,
    instance: null as any,
    events: [] as any[],
    engine: 'ready',
    email: 'r@lab.org',
    user: null as any,
  })
  const graph = { nodes: new Map() }
  const instances = computed(() => [...(admin.experiment?.instances || [])].sort((a: any, b: any) => b.id - a.id))
  const running = computed(() => !!admin.instance && admin.instance.status === 'RUNNING')
  const connectionState = ref('open')
  const actions = {
    selectInstance: vi.fn(async () => ({ user: {} })),
    stopInstance: vi.fn(async () => ({ user: {} })),
    deleteInstance: vi.fn(async () => ({ user: {} })),
    instanceEvents: vi.fn(async () => null),
  }
  return { admin, instances, running, connectionState, actions, graph }
})
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }, saveText: vi.fn() }
})
vi.mock('@/ui', async (orig) => {
  const real = await orig<typeof import('../src/ui')>()
  return {
    ...real,
    confirm: vi.fn(async () => true),
    notify: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
    toast: vi.fn(),
  }
})
const push = vi.fn()
const replace = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push, replace }),
  useRoute: () => reactive({ query: {} }),
}))

const store = await import('@/store/admin') as any
const httpMod = await import('@/lib/http') as any
const ui = await import('@/ui') as any

function session (id: number, name: string, status: any, data: any[] = [], creationTime = '2026-10-27 13:06:00,5') {
  return { id, name, status, creationTime, hitId: '', hits: [], data }
}

// ---- dates (bug §3.6.3) -------------------------------------------------------------------------

describe('parseServerTime', () => {
  it('reads the comma part as whole, unpadded milliseconds', () => {
    expect(parseServerTime('2026-10-27 13:06:00,5')!.getMilliseconds()).toBe(5)
    expect(parseServerTime('2026-10-27 13:06:00,95')!.getMilliseconds()).toBe(95)
    expect(parseServerTime('2026-10-27 13:06:00,123')!.getMilliseconds()).toBe(123)
  })

  it('orders times within one second correctly (the old string sort put ,95 after ,123)', () => {
    const a = parseServerTime('2026-10-27 13:06:00,95')!
    const b = parseServerTime('2026-10-27 13:06:00,123')!
    expect(a.getTime()).toBeLessThan(b.getTime())
    expect('2026-10-27 13:06:00,95' > '2026-10-27 13:06:00,123').toBe(true) // what went wrong before
  })

  it('reads the server string as local wall-clock time', () => {
    const d = parseServerTime('2026-01-02 03:04:05,6')!
    expect([d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds()]).toEqual([2026, 0, 2, 3, 4, 5])
  })

  it('accepts times without milliseconds or seconds, and dotted fractions', () => {
    expect(parseServerTime('2026-10-27 13:06:07')!.getSeconds()).toBe(7)
    expect(parseServerTime('2026-10-27 13:06')!.getMinutes()).toBe(6)
    expect(parseServerTime('2026-10-27T13:06:00.5')!.getMilliseconds()).toBe(500)
  })

  it('reads ISO-8601 UTC from live event pushes', () => {
    expect(parseServerTime('2026-10-27T12:06:00.005Z')!.toISOString()).toBe('2026-10-27T12:06:00.005Z')
  })

  it('returns null for missing or unreadable times', () => {
    expect(parseServerTime('')).toBeNull()
    expect(parseServerTime(undefined)).toBeNull()
    expect(parseServerTime('yesterday')).toBeNull()
    expect(parseServerTime('2026-13-45 99:99:99,1')).toBeNull()
  })

  it('formats event clocks with padded milliseconds', () => {
    expect(formatClock(parseServerTime('2026-10-27 09:06:00,5'))).toBe('09:06:00.005')
    expect(formatClock(null)).toBe('—')
  })
})

// ---- sessions -----------------------------------------------------------------------------------

describe('sortSessions', () => {
  const list = [
    session(3, 'pilot 10', 'FINISHED', [{ id: 1, name: 'a', value: '1' }]),
    session(12, 'pilot 2', 'RUNNING'),
    session(7, 'Main', 'STOPPED', [{ id: 2, name: 'a', value: '1' }, { id: 3, name: 'b', value: '2' }]),
    // same creation second as #12, but created later: ids, not strings, decide
    session(13, 'pilot 1', 'STOPPED', [], '2026-10-27 13:06:00,123'),
  ]

  it('puts the newest first by id, whatever the time strings say', () => {
    expect(sortSessions(list).map(s => s.id)).toEqual([13, 12, 7, 3])
    expect(sortSessions(list, 'created', 'asc').map(s => s.id)).toEqual([3, 7, 12, 13])
  })

  it('sorts names naturally (pilot 2 before pilot 10)', () => {
    expect(sortSessions(list, 'name', 'asc').map(s => s.name)).toEqual(['Main', 'pilot 1', 'pilot 2', 'pilot 10'])
  })

  it('sorts by status (running first) and breaks ties by id', () => {
    expect(sortSessions(list, 'status', 'asc').map(s => s.id)).toEqual([12, 7, 13, 3])
  })

  it('sorts by the number of launch parameters', () => {
    expect(sortSessions(list, 'params', 'desc').map(s => s.id)).toEqual([7, 3, 13, 12])
  })

  it('does not change the list it was given', () => {
    const copy = list.map(s => s.id)
    sortSessions(list, 'name')
    expect(list.map(s => s.id)).toEqual(copy)
  })
})

describe('matchesSession', () => {
  const s = session(42, 'Pilot North', 'RUNNING', [{ id: 1, name: 'groupSize', value: '8' }])
  it('finds sessions by name, id or launch parameter', () => {
    expect(matchesSession(s, '')).toBe(true)
    expect(matchesSession(s, 'north')).toBe(true)
    expect(matchesSession(s, '42')).toBe(true)
    expect(matchesSession(s, '#42')).toBe(true)
    expect(matchesSession(s, 'groupsize')).toBe(true)
    expect(matchesSession(s, '8')).toBe(true)
    expect(matchesSession(s, 'south')).toBe(false)
  })
})

describe('what can be done to a session', () => {
  it('deletes only stopped or finished sessions (the server ignores the rest)', () => {
    expect(['RUNNING', 'TESTING', 'STOPPED', 'FINISHED', 'ARCHIVED'].filter(st => canDelete({ status: st as any }))).toEqual(['STOPPED', 'FINISHED'])
  })
  it('resumes runnable sessions and stops running ones', () => {
    expect(canResume({ status: 'STOPPED' })).toBe(true)
    expect(canResume({ status: 'FINISHED' })).toBe(false)
    expect(canStop({ status: 'RUNNING' })).toBe(true)
    expect(canStop({ status: 'STOPPED' })).toBe(false)
  })
})

// ---- events -------------------------------------------------------------------------------------

describe('events', () => {
  const fromShowEvent = { id: 900, name: 'clientLogIn', datetime: '2026-10-27 13:06:00,95', data: [{ id: 901, name: 'clientId', value: 'p1' }] }
  const fromUserJson = { id: 902, name: 'choice', datetime: '2026-10-27 13:06:00,123', eventData: [{ id: 903, name: 'value', value: 'cooperate' }] }
  const fromPush = { id: null, name: 'choice', datetime: '2026-10-27T23:59:59.000Z', data: { value: 'defect', round: 2, meta: { a: 1 } } }

  it('reads all three shapes the server sends', () => {
    const a = normalizeEvent(fromShowEvent)
    expect(a).toMatchObject({ id: 900, name: 'clientLogIn', data: [{ name: 'clientId', value: 'p1' }] })
    expect(normalizeEvent(fromUserJson).data).toEqual([{ name: 'value', value: 'cooperate' }])
    const c = normalizeEvent(fromPush)
    expect(c.id).toBeNull()
    expect(c.data).toEqual([{ name: 'value', value: 'defect' }, { name: 'round', value: '2' }, { name: 'meta', value: '{"a":1}' }])
    expect(normalizeEvent({ id: '5', name: '', datetime: 'x' })).toMatchObject({ id: 5, name: '(unnamed)', time: null })
  })

  it('orders by time, then id, with unpadded milliseconds read correctly', () => {
    const evs = [fromUserJson, fromShowEvent].map(normalizeEvent)
    expect(sortEvents(evs).map(e => e.id)).toEqual([900, 902])
    const same = [
      normalizeEvent({ id: 7, name: 'b', datetime: '2026-10-27 13:06:00,5' }),
      normalizeEvent({ id: 6, name: 'a', datetime: '2026-10-27 13:06:00,5' }),
    ]
    expect(sortEvents(same).map(e => e.id)).toEqual([6, 7])
  })

  it('merges the loaded list with live pushes, once per id', () => {
    const base = [normalizeEvent(fromShowEvent)]
    const live = [normalizeEvent(fromShowEvent), normalizeEvent(fromPush), normalizeEvent(fromPush)]
    // ids dedupe; pushes without an id are all kept
    expect(mergeEvents(base, live)).toHaveLength(3)
  })

  it('filters by name and by words in names and values', () => {
    const evs = [fromShowEvent, fromUserJson, fromPush].map(normalizeEvent)
    expect(filterEvents(evs, { name: 'choice' })).toHaveLength(2)
    expect(filterEvents(evs, { query: 'DEFECT' })).toHaveLength(1)
    expect(filterEvents(evs, { query: 'clientid' })).toHaveLength(1)
    expect(filterEvents(evs, { query: 'choice', name: 'clientLogIn' })).toHaveLength(0)
    expect(filterEvents(evs, { query: '900' })).toHaveLength(1)
  })

  it('groups by event name with the data names as columns', () => {
    const evs = sortEvents([fromShowEvent, fromUserJson, fromPush].map(normalizeEvent))
    const groups = groupEvents(evs)
    expect(groups.map(g => [g.name, g.events.length])).toEqual([['choice', 2], ['clientLogIn', 1]])
    expect(groups[0].keys).toEqual(['value', 'round', 'meta'])
    expect(eventNameCounts(evs)).toEqual([{ name: 'choice', count: 2 }, { name: 'clientLogIn', count: 1 }])
  })
})

// ---- CSV ----------------------------------------------------------------------------------------

describe('CSV', () => {
  it('quotes every field and doubles quotes (bug §3.6.2)', () => {
    expect(csvField('say "hi", ok')).toBe('"say ""hi"", ok"')
    expect(csvField(null)).toBe('""')
    expect(csvField(3)).toBe('"3"')
    expect(toCsv([['a', 'b'], ['line\nbreak', '"']])).toBe('"a","b"\n"line\nbreak",""""\n')
  })

  it('writes values exactly as recorded, like the server\'s file (no apostrophe before = + - @)', () => {
    expect(csvField('=HYPERLINK("http://x","y")')).toBe('"=HYPERLINK(""http://x"",""y"")"')
    expect(csvField('@anna yes')).toBe('"@anna yes"')
    expect(csvField('-')).toBe('"-"')
    expect(csvField('+44 20 7946 0958')).toBe('"+44 20 7946 0958"')
    expect(csvField('\tx')).toBe('"\tx"')
    expect(csvField(-0.5)).toBe('"-0.5"')
    const evs = [normalizeEvent({ id: 1, name: 'chat', datetime: '2026-10-27 13:06:00,5', data: [{ name: 'text', value: '=1+1' }, { name: 'to', value: '@anna' }] })]
    const rows = eventsToCsv(evs).split('\n')
    expect(rows[1]).toMatch(/,"text","=1\+1"$/)
    expect(rows[2]).toMatch(/,"to","@anna"$/)
    expect(eventsToWideCsv(evs).split('\n')[1]).toMatch(/,"=1\+1","@anna"$/)
  })

  it('exports events in the server file\'s columns, keeping events without data', () => {
    const evs = [
      normalizeEvent({ id: 1, name: 'start', datetime: '2026-10-27T12:00:00.000Z', data: [] }),
      normalizeEvent({ id: 2, name: 'say "x"', datetime: '2026-10-27T12:00:01.250Z', data: [{ name: 'text', value: 'a,b' }, { name: 'n', value: '1' }] }),
    ]
    expect(eventsToCsv(evs)).toBe([
      '"id","event","event_date","data_name","data_value"',
      '"1","start","2026-10-27T12:00:00.000Z","",""',
      '"2","say ""x""","2026-10-27T12:00:01.250Z","text","a,b"',
      '"2","say ""x""","2026-10-27T12:00:01.250Z","n","1"',
      '',
    ].join('\n'))
  })

  it('exports one event as a table with a column per data name', () => {
    const evs = [
      normalizeEvent({ id: 1, name: 'choice', datetime: '2026-10-27T12:00:00.000Z', data: { player: 'p1', value: 'A' } }),
      normalizeEvent({ id: 2, name: 'choice', datetime: '2026-10-27T12:00:01.000Z', data: { player: 'p2' } }),
    ]
    expect(eventsToWideCsv(evs, ['player', 'value'])).toBe([
      '"id","event","event_date","player","value"',
      '"1","choice","2026-10-27T12:00:00.000Z","p1","A"',
      '"2","choice","2026-10-27T12:00:01.000Z","p2",""',
      '',
    ].join('\n'))
    expect(eventsToWideCsv(evs).split('\n')[0]).toBe('"id","event","event_date","player","value"')
  })

  it('makes file names every system accepts', () => {
    expect(safeFileName('pilot 1')).toBe('pilot 1')
    expect(safeFileName('a/b:c*?"<>|d')).toBe('a-b-c-d')
    expect(safeFileName('  ..hidden  ')).toBe('hidden')
    expect(safeFileName('')).toBe('session')
    expect(safeFileName('', 'sessions')).toBe('sessions')
  })

  it('counts the data rows of a downloaded file', () => {
    expect(csvDataRows('"id","event"\n')).toBe(0)
    expect(csvDataRows('"id","event"\n"1","a"\n"2","b"\n')).toBe(2)
    expect(csvDataRows('')).toBe(0)
  })
})

// ---- errors and waiting -------------------------------------------------------------------------

describe('describeError', () => {
  it('turns a Play HTML error page into a short sentence', () => {
    const e = new HttpError(500, '<!DOCTYPE html><html><head><title>Execution exception</title></head><body>…</body></html>')
    expect(describeError(e)).toBe('The server ran into an error (500): Execution exception')
  })
  it('explains missing items, refusals, network failures and plain errors', () => {
    expect(describeError(new HttpError(404, 'Not Found'))).toMatch(/could not find/)
    expect(describeError(new HttpError(403, 'Forbidden'))).toMatch(/refused/)
    expect(describeError(new HttpError(400, 'Bad name'))).toBe('Bad name')
    expect(describeError(new TypeError('Failed to fetch'))).toMatch(/Could not reach the server/)
    expect(describeError(new Error('boom'))).toBe('boom')
  })
})

describe('waitUntil', () => {
  it('resolves as soon as the state changes', async () => {
    const s = reactive({ ok: false })
    const p = waitUntil(() => s.ok, 1000)
    s.ok = true
    await expect(p).resolves.toBe(true)
  })
  it('gives up after the time limit', async () => {
    vi.useFakeTimers()
    const p = waitUntil(() => false, 500)
    vi.advanceTimersByTime(600)
    await expect(p).resolves.toBe(false)
    vi.useRealTimers()
  })
})

// ---- actions (download and delete failures are shown, bug §3.3.7) -------------------------------

describe('useSessionActions', () => {
  let actionsApi: ReturnType<typeof import('../src/pages/sessions/useSessionActions').useSessionActions>
  beforeEach(async () => {
    vi.clearAllMocks()
    store.connectionState.value = 'open'
    store.admin.experiment = { id: 5, name: 'Trust game', instances: [session(1, 'pilot 1', 'STOPPED'), session(2, 'pilot 2', 'RUNNING')] }
    store.admin.instance = store.admin.experiment.instances[1]
    const { useSessionActions } = await import('../src/pages/sessions/useSessionActions')
    actionsApi = useSessionActions()
  })

  it('downloads a session\'s data as <name>_<id>.csv', async () => {
    httpMod.http.get.mockResolvedValueOnce('"id","event","event_date","data_name","data_value"\n"1","a","t","n","v"\n')
    await actionsApi.downloadData({ id: 1, name: 'pilot 1' })
    expect(httpMod.http.get).toHaveBeenCalledWith('/csv/data/1', { raw: true })
    expect(httpMod.saveText).toHaveBeenCalledWith('pilot 1_1.csv', expect.stringContaining('"id"'))
    expect(ui.notify.success).toHaveBeenCalled()
  })

  it('says so when a download fails, in words', async () => {
    httpMod.http.get.mockRejectedValueOnce(new HttpError(500, '<html><title>NullPointerException</title></html>'))
    const ok = await actionsApi.downloadData({ id: 1, name: 'pilot 1' })
    expect(ok).toBe(false)
    expect(httpMod.saveText).not.toHaveBeenCalled()
    expect(ui.notify.error).toHaveBeenCalledWith('Could not download the data of “pilot 1”', 'The server ran into an error (500): NullPointerException')
  })

  it('downloads the list of sessions as <experiment>.csv', async () => {
    httpMod.http.get.mockResolvedValueOnce('"id","instance"\n')
    await actionsApi.downloadList({ id: 5, name: 'Trust/game' })
    expect(httpMod.http.get).toHaveBeenCalledWith('/csv/instances/5', { raw: true })
    expect(httpMod.saveText).toHaveBeenCalledWith('Trust-game.csv', '"id","instance"\n')
  })

  it('asks for the name before deleting, and reports a delete the server refused', async () => {
    const s = store.admin.experiment.instances[0]
    const ok = await actionsApi.remove(s)
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ typeToConfirm: 'pilot 1', danger: true }))
    expect(store.actions.deleteInstance).toHaveBeenCalledWith(1)
    // the session is still listed: the server ignored the delete
    expect(ok).toBe(false)
    expect(ui.notify.error).toHaveBeenCalledWith('“pilot 1” was not deleted', expect.stringContaining('refused'))
  })

  it('confirms a delete once the session is gone', async () => {
    const s = store.admin.experiment.instances[0]
    store.actions.deleteInstance.mockImplementationOnce(async () => {
      store.admin.experiment.instances = store.admin.experiment.instances.filter((x: any) => x.id !== 1)
      return { user: {} }
    })
    expect(await actionsApi.remove(s)).toBe(true)
    expect(ui.notify.success).toHaveBeenCalledWith('Deleted “pilot 1”')
  })

  it('does not offer to delete a running session', async () => {
    const ok = await actionsApi.remove(store.admin.experiment.instances[1])
    expect(ok).toBe(false)
    expect(ui.confirm).not.toHaveBeenCalled()
    expect(store.actions.deleteInstance).not.toHaveBeenCalled()
  })

  it('does nothing when the researcher cancels', async () => {
    ui.confirm.mockResolvedValueOnce(false)
    expect(await actionsApi.stop(store.admin.experiment.instances[1])).toBe(false)
    expect(store.actions.stopInstance).not.toHaveBeenCalled()
  })

  it('resumes a session and waits until it really runs', async () => {
    const s = store.admin.experiment.instances[0]
    store.actions.selectInstance.mockImplementationOnce(async () => {
      store.admin.instance = { ...s, status: 'RUNNING' }
      return { user: {} }
    })
    expect(await actionsApi.resume(s)).toBe(true)
    // the researcher's own session was running, so the confirmation says it ends (danger)
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ danger: true, message: expect.stringContaining('Your session “pilot 2” is running.') }))
    expect(ui.notify.success).toHaveBeenCalled()
  })

  it('warns about sessions another account or the autopilot runs on the shared engine, as the server reports them', async () => {
    store.admin.instance = null
    store.admin.user = { engine: { experimentId: 6, experimentName: 'Public goods', sessionId: 30, sessionName: 'their run', owner: 'boss@lab.org' } }
    httpMod.http.get.mockImplementation(async (url: string) => url === '/autopilot'
      ? { source: 'admin', settings: { enabled: true, experimentId: 5 }, status: { on: true, experimentId: 5, instance: { id: 8, name: 'auto 3', status: 'RUNNING' } } }
      : null)
    ui.confirm.mockResolvedValueOnce(false)
    try {
      expect(await actionsApi.resume(store.admin.experiment.instances[0])).toBe(false)
      const req = ui.confirm.mock.calls[0][0]
      expect(req.danger).toBe(true)
      expect(req.message).not.toContain('Your session')
      expect(req.message).toContain('The autopilot has session “auto 3” open.')
      expect(req.message).toContain('boss@lab.org has session “their run” of “Public goods” running.')
      expect(store.actions.selectInstance).not.toHaveBeenCalled()
    } finally {
      httpMod.http.get.mockReset()
      store.admin.user = null
    }
  })

  it('resumes without a warning when nobody is using the engine', async () => {
    // a row still marked running is no evidence: the server's engine info says what runs
    store.admin.instance = null
    ui.confirm.mockResolvedValueOnce(false)
    await actionsApi.resume(store.admin.experiment.instances[0])
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ danger: false }))
    expect(ui.confirm.mock.calls[0][0].message).not.toMatch(/running/)
  })

  it('stops a session that is not the current one, saying the current one is not affected', async () => {
    store.admin.instance = null
    store.actions.stopInstance.mockImplementationOnce(async () => {
      store.admin.experiment.instances[1].status = 'STOPPED'
      return { user: {} }
    })
    expect(await actionsApi.stop(store.admin.experiment.instances[1])).toBe(true)
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('current session is not affected') }))
  })

  it('stops the current session once the server confirms', async () => {
    store.actions.stopInstance.mockImplementationOnce(async () => {
      store.admin.experiment.instances[1].status = 'STOPPED'
      store.admin.instance = null
      return { user: {} }
    })
    expect(await actionsApi.stop(store.admin.experiment.instances[1])).toBe(true)
    expect(store.actions.stopInstance).toHaveBeenCalledWith(2)
    expect(ui.notify.success).toHaveBeenCalledWith('Stopped “pilot 2”', expect.any(String))
  })

  it('says that stopping the autopilot\'s session pauses the autopilot', async () => {
    httpMod.http.get.mockImplementation(async (url: string) => url === '/autopilot'
      ? { source: 'admin', settings: { enabled: true, experimentId: 5 }, status: { on: true, experimentId: 5, instance: { id: 2, name: 'pilot 2', status: 'RUNNING' } } }
      : null)
    store.actions.stopInstance.mockImplementationOnce(async () => {
      store.admin.experiment.instances[1].status = 'STOPPED'
      return { user: {} }
    })
    try {
      expect(await actionsApi.stop(store.admin.experiment.instances[1])).toBe(true)
      expect(ui.confirm.mock.calls[0][0].message).toMatch(/stopping its session pauses it.*until you press Resume on the Autopilot page/s)
      expect(ui.notify.success).toHaveBeenCalledWith('Stopped “pilot 2”', expect.stringMatching(/autopilot is paused/))
    } finally {
      httpMod.http.get.mockReset()
    }
  })

  it('does not mention the autopilot when it runs another session', async () => {
    httpMod.http.get.mockImplementation(async (url: string) => url === '/autopilot'
      ? { source: 'admin', settings: { enabled: true, experimentId: 5 }, status: { on: true, experimentId: 5, instance: { id: 8, name: 'auto 3', status: 'RUNNING' } } }
      : null)
    ui.confirm.mockResolvedValueOnce(false)
    try {
      await actionsApi.stop(store.admin.experiment.instances[1])
      expect(ui.confirm.mock.calls[0][0].message).not.toMatch(/autopilot/i)
    } finally {
      httpMod.http.get.mockReset()
    }
  })

  it('refuses to queue session changes while disconnected', async () => {
    store.connectionState.value = 'retrying'
    expect(await actionsApi.stop(store.admin.experiment.instances[1])).toBe(false)
    expect(ui.confirm).not.toHaveBeenCalled()
    expect(ui.notify.error).toHaveBeenCalledWith('Not connected to the server', expect.any(String))
  })
})

// ---- the page -------------------------------------------------------------------------------------

describe('SessionsPage', () => {
  afterEach(() => { document.body.innerHTML = '' })

  async function mountPage () {
    const SessionsPage = (await import('../src/pages/sessions/SessionsPage.vue')).default
    const w = mount(SessionsPage, { attachTo: document.body })
    await flushPromises()
    return w
  }

  beforeEach(() => {
    vi.clearAllMocks()
    store.admin.experiment = {
      id: 5,
      name: 'Trust game',
      instances: [
        session(1, 'pilot 1', 'FINISHED', [{ id: 1, name: 'rounds', value: '10' }]),
        session(3, 'main', 'RUNNING', [{ id: 2, name: 'rounds', value: '12' }, { id: 3, name: 'groups', value: '4' }]),
        session(2, 'pilot 2', 'STOPPED'),
      ],
    }
    store.admin.instance = store.admin.experiment.instances[1]
  })

  it('lists sessions newest first and marks the current one', async () => {
    const w = await mountPage()
    const rows = w.findAll('tr.ss__row')
    expect(rows.map(r => r.find('.ss__name-text').text())).toEqual(['main', 'pilot 2', 'pilot 1'])
    expect(rows[0].classes()).toContain('is-current')
    expect(rows[0].text()).toContain('Current')
    // the current session's launch parameters are open
    expect(w.find('#ss-details-3').text()).toContain('groups')
    w.unmount()
  })

  it('expands a row to show its launch parameters', async () => {
    const w = await mountPage()
    expect(w.find('#ss-details-1').exists()).toBe(false)
    await w.findAll('tr.ss__row')[2].find('button.ss__chev').trigger('click')
    expect(w.find('#ss-details-1').text()).toContain('rounds')
    expect(w.find('#ss-details-1').text()).toContain('10')
    w.unmount()
  })

  it('filters by status and search', async () => {
    const w = await mountPage()
    const tabs = w.findAll('[role="tab"]')
    await tabs.find(t => t.text().startsWith('Stopped'))!.trigger('click')
    expect(w.findAll('tr.ss__row').map(r => r.find('.ss__name-text').text())).toEqual(['pilot 2'])
    await tabs.find(t => t.text().startsWith('All'))!.trigger('click')
    await w.find('input[aria-label="Search sessions"]').setValue('12')
    expect(w.findAll('tr.ss__row').map(r => r.find('.ss__name-text').text())).toEqual(['main'])
    await w.find('input[aria-label="Search sessions"]').setValue('nothing like this')
    expect(w.text()).toContain('No sessions match')
    w.unmount()
  })

  it('sorts by a column header', async () => {
    const w = await mountPage()
    const nameHeader = w.findAll('th button.ss__sort').find(b => b.text().startsWith('Session'))!
    await nameHeader.trigger('click')
    expect(w.findAll('tr.ss__row').map(r => r.find('.ss__name-text').text())).toEqual(['main', 'pilot 1', 'pilot 2'])
    expect(nameHeader.element.closest('th')!.getAttribute('aria-sort')).toBe('ascending')
    w.unmount()
  })

  it('teaches what a session is when there are none', async () => {
    store.admin.experiment = { id: 5, name: 'Trust game', instances: [] }
    store.admin.instance = null
    const w = await mountPage()
    expect(w.text()).toContain('No sessions yet')
    const newBtn = w.findAll('button').filter(b => b.text() === 'New session')
    await newBtn[newBtn.length - 1].trigger('click')
    expect(push).toHaveBeenCalledWith('/session?new=1')
    w.unmount()
  })

  it('opens a session\'s events and shows why when they cannot be loaded', async () => {
    store.actions.instanceEvents.mockResolvedValueOnce({ name: 'pilot 1', events: [
      { id: 10, name: 'clientLogIn', datetime: '2026-10-27 13:06:00,5', data: [{ id: 11, name: 'clientId', value: 'p1' }] },
      { id: 12, name: 'choice', datetime: '2026-10-27 13:06:01,20', data: [{ id: 13, name: 'value', value: 'A' }] },
    ] })
    const w = await mountPage()
    await w.findAll('tr.ss__row')[2].find('button[aria-label="View the events of pilot 1"]').trigger('click')
    await flushPromises()
    const drawer = document.body.querySelector('.sev')!
    expect(drawer.textContent).toContain('clientLogIn')
    expect(drawer.textContent).toContain('choice')
    expect(drawer.querySelectorAll('tbody tr')).toHaveLength(2)
    w.unmount()

    store.actions.instanceEvents.mockResolvedValueOnce(null)
    const w2 = await mountPage()
    await w2.findAll('tr.ss__row')[1].find('button[aria-label="View the events of pilot 2"]').trigger('click')
    await flushPromises()
    await nextTick()
    expect(document.body.textContent).toContain('Couldn’t load the events')
    w2.unmount()
  })
})

describe('SessionEventsDrawer', () => {
  afterEach(() => { document.body.innerHTML = '' })
  beforeEach(() => {
    vi.clearAllMocks()
    store.admin.experiment = { id: 5, name: 'Trust game', instances: [session(3, 'main', 'RUNNING')] }
    store.admin.instance = store.admin.experiment.instances[0]
    store.admin.events = []
  })

  async function mountDrawer (s: any) {
    const Drawer = (await import('../src/pages/sessions/SessionEventsDrawer.vue')).default
    const w = mount(Drawer, { props: { open: true, session: s }, attachTo: document.body })
    await flushPromises()
    return w
  }
  const rows = () => [...document.body.querySelectorAll('.sev tbody tr')].map(r => r.textContent || '')

  it('leaves out live events another session recorded', async () => {
    store.actions.instanceEvents.mockResolvedValueOnce({ name: 'main', events: [] })
    const w = await mountDrawer(store.admin.instance)
    store.admin.events.push(
      { id: 1, name: 'mine', datetime: '2026-10-27T13:06:00.000Z', data: {}, experimentInstanceId: 3 },
      { id: 2, name: 'theirs', datetime: '2026-10-27T13:06:01.000Z', data: {}, experimentInstanceId: 9 },
      { id: 3, name: 'unnamed', datetime: '2026-10-27T13:06:02.000Z', data: {} },
    )
    await flushPromises()
    // an older server names no session: taken while this one runs
    expect(rows().join(' ')).toContain('mine')
    expect(rows().join(' ')).toContain('unnamed')
    expect(rows().join(' ')).not.toContain('theirs')

    // once it has finished, an event that names no session could be anyone's
    store.admin.instance.status = 'FINISHED'
    await flushPromises()
    expect(rows().join(' ')).toContain('mine')
    expect(rows().join(' ')).not.toContain('unnamed')
    w.unmount()
  })

  it('loads the events again in full before exporting a live session', async () => {
    const long = 'x'.repeat(2500)
    store.actions.instanceEvents.mockResolvedValueOnce({ name: 'main', events: [] })
    const w = await mountDrawer(store.admin.instance)
    // the live push cut the value short
    store.admin.events.push({ id: 7, name: 'chat', datetime: '2026-10-27T13:06:00.000Z', data: { text: `${'x'.repeat(2000)}…` }, experimentInstanceId: 3 })
    await flushPromises()
    store.actions.instanceEvents.mockResolvedValueOnce({ name: 'main', events: [
      { id: 6, name: 'clientLogIn', datetime: '2026-10-27 13:05:59,0', data: [{ name: 'clientId', value: 'p1' }] },
      { id: 7, name: 'chat', datetime: '2026-10-27 13:06:00,0', data: [{ name: 'text', value: long }] },
    ] })
    const exportAll = [...document.body.querySelectorAll<HTMLButtonElement>('.sev button')].find(b => b.textContent?.includes('Export all'))!
    exportAll.click()
    await flushPromises()
    expect(store.actions.instanceEvents).toHaveBeenCalledTimes(2)
    const [, csv] = httpMod.saveText.mock.calls[0]
    expect(csv).toContain(long)
    expect(csv).toContain('clientLogIn')
    expect(csv).not.toContain('…')
    w.unmount()
  })

  it('does not export a list it could not load again', async () => {
    store.actions.instanceEvents.mockResolvedValueOnce({ name: 'main', events: [{ id: 6, name: 'a', datetime: '2026-10-27 13:05:59,0', data: [] }] })
    const w = await mountDrawer(store.admin.instance)
    store.actions.instanceEvents.mockResolvedValueOnce(null)
    const exportAll = [...document.body.querySelectorAll<HTMLButtonElement>('.sev button')].find(b => b.textContent?.includes('Export all'))!
    exportAll.click()
    await flushPromises()
    expect(httpMod.saveText).not.toHaveBeenCalled()
    expect(ui.notify.error).toHaveBeenCalledWith('Couldn’t export the events', expect.stringContaining('server’s CSV'))
    w.unmount()
  })
})

describe('SessionDetails', () => {
  it('offers Stop for a running session that isn’t current, without steering to “Make current”', async () => {
    const SessionDetails = (await import('../src/pages/sessions/SessionDetails.vue')).default
    const w = mount(SessionDetails, {
      props: { session: session(9, 'old run', 'RUNNING') as any, experimentId: 5, current: false, locked: false, busy: { resume: false, stop: false, delete: false, data: false } },
    })
    expect(w.text()).not.toMatch(/Make it current/)
    expect(w.findAll('button').some(b => b.text() === 'Stop')).toBe(true)
    // a finished session still says why it can't run again
    await w.setProps({ session: session(9, 'old run', 'FINISHED') as any })
    expect(w.text()).toMatch(/A finished session can’t be resumed/)
  })
})

describe('liveEventOf', () => {
  it('takes events named for this session, and unnamed ones only while it runs', async () => {
    const { liveEventOf } = await import('../src/pages/sessions/sessionData')
    expect(liveEventOf({ experimentInstanceId: 3 }, 3, false)).toBe(true)
    expect(liveEventOf({ experimentInstanceId: '3' }, 3, false)).toBe(true)
    expect(liveEventOf({ experimentInstanceId: 4 }, 3, true)).toBe(false)
    expect(liveEventOf({ experimentInstanceId: null }, 3, true)).toBe(false)
    expect(liveEventOf({}, 3, true)).toBe(true)
    expect(liveEventOf({}, 3, false)).toBe(false)
  })
})
