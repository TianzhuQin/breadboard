import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  INT_MAX, MAX_TEXT, buildFields, buildLaunchParams, checkParam, checkSessionName, fieldErrors, normalizeType,
  parseBound, rangeText, suggestName,
} from '../src/pages/session/params'
import {
  FEED_CAP, describeEvent, eventNameCounts, eventTone, filterEvents, formatValue, fromLive, fromSeed, mergeEvents,
  mergeFeed, parseServerTime, participantsSeen, summarizeData,
} from '../src/pages/session/events'
import {
  classifyStartReply, cleanError, detectGameStarted, formatDuration, isLocalOrigin, isStartReply, joinLinks,
  networkStats, safeFileName, waitingRoom,
} from '../src/pages/session/live'
import type { Parameter } from '../src/store/types'

const store = vi.hoisted(() => ({ launch: vi.fn() }))
vi.mock('@/store/admin', async () => {
  const { reactive, computed } = await import('vue')
  const admin = reactive<any>({ experiment: null, instance: null, output: [], events: [], engineReloadedAt: 0 })
  return {
    admin,
    actions: { launch: store.launch },
    instances: computed(() => (admin.experiment?.instances || [])),
    nextMessage: vi.fn(),
    graph: { nodes: new Map(), links: new Map(), version: { value: 0 } },
  }
})

function param (p: Partial<Parameter> & { name: string, type: any }): Parameter {
  return { id: 1, minVal: '', maxVal: '', defaultVal: '', description: '', ...p }
}

// ---- launch parameters ---------------------------------------------------------------------

describe('launch parameters', () => {
  it('knows the four types and treats anything else as text', () => {
    expect(normalizeType('Integer')).toBe('Integer')
    expect(normalizeType('decimal')).toBe('Decimal')
    expect(normalizeType('BOOLEAN')).toBe('Boolean')
    expect(normalizeType('Colour')).toBe('Text')
    expect(normalizeType(null)).toBe('Text')
  })

  it('reads bounds, and ignores blank or unreadable ones', () => {
    expect(parseBound('1')).toBe(1)
    expect(parseBound(' 2.5 ')).toBe(2.5)
    expect(parseBound('')).toBeNull()
    expect(parseBound('abc')).toBeNull()
    expect(parseBound(null)).toBeNull()
    expect(rangeText(1, 10)).toBe('1 to 10')
    expect(rangeText(1, null)).toBe('1 or more')
    expect(rangeText(null, 10)).toBe('Up to 10')
    expect(rangeText(null, null)).toBe('')
  })

  it('pre-fills defaults by type', () => {
    const fields = buildFields([
      param({ name: 'rounds', type: 'Integer', defaultVal: '5', minVal: '1', maxVal: '10' }),
      param({ name: 'chat', type: 'Boolean', defaultVal: 'true' }),
      param({ name: 'ai', type: 'Boolean', defaultVal: null as any }),
      param({ name: 'rate', type: 'Decimal', defaultVal: '0.333' }),
      param({ name: 'label', type: 'Text', defaultVal: 'hello' }),
    ])
    expect(fields.map(f => f.initial)).toEqual(['5', true, false, '0.333', 'hello'])
    expect(fields[0].min).toBe(1)
    expect(fields[0].max).toBe(10)
    expect(fields.every(f => f.defaultProblem === null)).toBe(true)
  })

  it('points out defaults that cannot be sent (the old console sent NaN → null for them)', () => {
    const [empty, decimalInt, outOfRange] = buildFields([
      param({ name: 'a', type: 'Integer', defaultVal: '' }),
      param({ name: 'b', type: 'Integer', defaultVal: '5.5' }),
      param({ name: 'c', type: 'Decimal', defaultVal: '20', maxVal: '10' }),
    ])
    expect(empty.defaultProblem).toMatch(/no default/)
    expect(decimalInt.defaultProblem).toMatch(/whole number/)
    expect(outOfRange.defaultProblem).toMatch(/at most 10/)
  })

  it('checks whole numbers', () => {
    const f = { type: 'Integer' as const, min: 1, max: 10 }
    expect(checkParam(f, '5')).toEqual({ value: 5 })
    expect(checkParam(f, ' 7 ')).toEqual({ value: 7 })
    expect(checkParam(f, '').error).toMatch(/Enter a whole number/)
    expect(checkParam(f, '2.5').error).toMatch(/no decimals/)
    expect(checkParam(f, 'ten').error).toMatch(/e\.g\. 10/)
    expect(checkParam(f, '1,000').error).toMatch(/without commas/)
    expect(checkParam(f, '0').error).toBe('Must be between 1 and 10.')
    expect(checkParam(f, '11').error).toBe('Must be between 1 and 10.')
    expect(checkParam({ type: 'Integer', min: 3, max: null }, '2').error).toBe('Must be at least 3.')
    expect(checkParam({ type: 'Integer', min: null, max: 3 }, '4').error).toBe('Must be at most 3.')
    // the server parses with Integer.parseInt
    expect(checkParam({ type: 'Integer', min: null, max: null }, String(INT_MAX)).value).toBe(INT_MAX)
    expect(checkParam({ type: 'Integer', min: null, max: null }, String(INT_MAX + 1)).error).toMatch(/Too large/)
    expect(checkParam({ type: 'Integer', min: null, max: null }, '99999999999999999999').error).toMatch(/Too large/)
  })

  it('checks decimals without the old 0.01 step (a 0.333 default used to block the form)', () => {
    const f = { type: 'Decimal' as const, min: 0, max: 1 }
    expect(checkParam(f, '0.333')).toEqual({ value: 0.333 })
    expect(checkParam(f, '.5')).toEqual({ value: 0.5 })
    expect(checkParam(f, '1e-3')).toEqual({ value: 0.001 })
    expect(checkParam(f, '0,5').error).toMatch(/Use a dot/)
    expect(checkParam(f, '').error).toBe('Enter a number.')
    expect(checkParam(f, 'abc').error).toMatch(/Enter a number/)
    expect(checkParam(f, '1.5').error).toBe('Must be between 0 and 1.')
    expect(checkParam({ type: 'Decimal', min: null, max: null }, '1e400').error).toMatch(/too large/)
  })

  it('accepts any text up to the database limit, and switches as booleans', () => {
    const t = { type: 'Text' as const, min: null, max: null }
    expect(checkParam(t, '')).toEqual({ value: '' })
    expect(checkParam(t, 'x'.repeat(MAX_TEXT))).toEqual({ value: 'x'.repeat(MAX_TEXT) })
    expect(checkParam(t, 'x'.repeat(MAX_TEXT + 1)).error).toMatch(/255 characters/)
    const b = { type: 'Boolean' as const, min: null, max: null }
    expect(checkParam(b, true)).toEqual({ value: true })
    expect(checkParam(b, false)).toEqual({ value: false })
    expect(checkParam(b, 'TRUE')).toEqual({ value: true })
    expect(checkParam(b, null)).toEqual({ value: false })
  })

  it('never builds a payload with null, NaN or a missing parameter', () => {
    const fields = buildFields([
      param({ name: 'n', type: 'Integer', minVal: '0', maxVal: '100' }),
      param({ name: 'p', type: 'Decimal' }),
      param({ name: 'b', type: 'Boolean' }),
      param({ name: 't', type: 'Text' }),
    ])
    const junk: any[] = ['', ' ', 'NaN', 'Infinity', '-', '.', 'e5', '1e', '0x10', '1_000', null, undefined, '12abc', '٣']
    for (const n of junk) {
      for (const p of junk) {
        const r = buildLaunchParams(fields, { n, p, b: true, t: 'x' })
        if (r.ok) {
          for (const v of Object.values(r.params)) {
            expect(v).not.toBeNull()
            if (typeof v === 'number') expect(Number.isFinite(v)).toBe(true)
          }
          expect(Object.keys(r.params).sort()).toEqual(['b', 'n', 'p', 't'])
        } else {
          expect(Object.keys(r.errors).length).toBeGreaterThan(0)
        }
      }
    }
    const ok = buildLaunchParams(fields, { n: '42', p: '-0.5', b: false, t: '' })
    expect(ok).toEqual({ ok: true, params: { n: 42, p: -0.5, b: false, t: '' } })
    // what goes over the wire keeps its types
    expect(JSON.parse(JSON.stringify(ok.ok && ok.params))).toEqual({ n: 42, p: -0.5, b: false, t: '' })
  })

  it('lists only the fields with a problem', () => {
    const fields = buildFields([param({ name: 'n', type: 'Integer', defaultVal: '3' }), param({ name: 'm', type: 'Integer' })])
    expect(fieldErrors(fields, { n: '3', m: '' })).toEqual({ m: 'Enter a whole number.' })
  })

  it('checks and suggests session names', () => {
    expect(checkSessionName('  ', [])).toMatch(/name/)
    expect(checkSessionName('Pilot', ['pilot '])).toMatch(/already has this name/)
    expect(checkSessionName('Pilot 2', ['Pilot'])).toBeNull()
    expect(checkSessionName('x'.repeat(256), [])).toMatch(/255/)
    expect(suggestName([])).toBe('Session 1')
    expect(suggestName(['a', 'Session 3'])).toBe('Session 4')
    expect(suggestName(['Session 2', 'session 3'])).toBe('Session 4')
  })
})

// ---- event feed ------------------------------------------------------------------------------

describe('event feed', () => {
  it('reads both server time formats', () => {
    expect(parseServerTime('2026-10-27T15:00:00.123Z')).toBe(Date.UTC(2026, 9, 27, 15, 0, 0, 123))
    // instance JSON: server local time, milliseconds not zero-padded (",5" is 5 ms)
    expect(parseServerTime('2026-10-27 15:00:00,5')).toBe(new Date(2026, 9, 27, 15, 0, 0, 5).getTime())
    expect(parseServerTime('2026-10-27 15:00:00,123')).toBe(new Date(2026, 9, 27, 15, 0, 0, 123).getTime())
    expect(parseServerTime('2026-10-27 15:00:00')).toBe(new Date(2026, 9, 27, 15, 0, 0).getTime())
    expect(parseServerTime('')).toBe(0)
    expect(parseServerTime('nonsense')).toBe(0)
    expect(parseServerTime(undefined)).toBe(0)
  })

  it('turns both shapes into one', () => {
    const seed = fromSeed({ id: 7, name: 'clientLogIn', datetime: '2026-10-27 15:00:00,0', eventData: [{ id: 1, name: 'clientId', value: 'p1' }] })
    expect(seed).toMatchObject({ key: 'e7', id: 7, name: 'clientLogIn', data: { clientId: 'p1' } })
    const live = fromLive({ id: null as any, name: 'choice', datetime: '2026-10-27T15:00:01Z', data: { round: 2, pick: { a: 1 } } })
    expect(live.id).toBeNull()
    expect(live.data).toEqual({ round: 2, pick: { a: 1 } })
    expect(live.key.startsWith('l')).toBe(true)
  })

  it('merges recorded and live events newest first, without duplicates', () => {
    const seed = [
      { id: 1, name: 'a', datetime: '2026-10-27T10:00:00Z', eventData: [] },
      { id: 2, name: 'b', datetime: '2026-10-27T10:00:01Z', eventData: [] },
    ]
    const live = [
      { id: 2, name: 'b', datetime: '2026-10-27T10:00:01Z', data: {} }, // also recorded
      { id: 3, name: 'c', datetime: '2026-10-27T10:00:02Z', data: {} },
      { id: null as any, name: 'd', datetime: '2026-10-27T10:00:03Z', data: { x: 1 } },
      { id: null as any, name: 'd', datetime: '2026-10-27T10:00:03Z', data: { x: 1 } }, // same, twice
    ]
    const merged = mergeEvents(seed, live)
    expect(merged.map(e => e.name)).toEqual(['d', 'c', 'b', 'a'])
    expect(mergeEvents(seed, live, 2).map(e => e.name)).toEqual(['d', 'c'])
    expect(mergeEvents(null, null)).toEqual([])
  })

  it('orders events from the same millisecond by id', () => {
    const t = '2026-10-27T10:00:00Z'
    const merged = mergeFeed([], [fromLive({ id: 4, name: 'x', datetime: t, data: {} }), fromLive({ id: 9, name: 'y', datetime: t, data: {} })])
    expect(merged.map(e => e.id)).toEqual([9, 4])
  })

  it('counts and filters by name', () => {
    const ev = mergeEvents([], [
      { id: 1, name: 'choice', datetime: '2026-10-27T10:00:00Z', data: {} },
      { id: 2, name: 'choice', datetime: '2026-10-27T10:00:01Z', data: {} },
      { id: 3, name: 'clientLogIn', datetime: '2026-10-27T10:00:02Z', data: {} },
    ])
    expect(eventNameCounts(ev)).toEqual([{ name: 'choice', count: 2 }, { name: 'clientLogIn', count: 1 }])
    expect(filterEvents(ev, 'choice').map(e => e.id)).toEqual([2, 1])
    expect(filterEvents(ev, null)).toHaveLength(3)
  })

  it('summarises event data as short text', () => {
    expect(formatValue('a  b\nc')).toBe('a b c')
    expect(formatValue(null)).toBe('—')
    expect(formatValue('')).toBe('""')
    expect(formatValue({ a: [1, 2] })).toBe('{"a":[1,2]}')
    expect(formatValue('x'.repeat(100), 10)).toBe(`${'x'.repeat(9)}…`)
    expect(summarizeData({ a: 1, b: true, c: 'x', d: 4 })).toEqual({ shown: [['a', '1'], ['b', 'true'], ['c', 'x']], more: 1 })
    expect(summarizeData({})).toEqual({ shown: [], more: 0 })
  })

  it('colours and explains well-known events', () => {
    expect(eventTone('clientConnected')).toBe('success')
    expect(eventTone('clientDisconnected')).toBe('warning')
    expect(eventTone('aiTakeover')).toBe('warning')
    expect(eventTone('autopilotCancelled')).toBe('danger')
    expect(eventTone('InitStepStart')).toBe('accent')
    expect(eventTone('choice')).toBe('neutral')
    expect(describeEvent('InitStepStart')).toBe('Step “InitStep” started')
    expect(describeEvent('RoundTimeout')).toBe('Step “Round” timed out')
    expect(describeEvent('aiTakeover')).toMatch(/took over/)
    expect(describeEvent('myEvent')).toBe('')
  })

  it('counts distinct participants from arrival events, not AI seats', () => {
    const ev = mergeEvents([], [
      { id: 1, name: 'clientLogIn', datetime: '2026-10-27T10:00:00Z', data: { clientId: 'p1' } },
      { id: 2, name: 'clientConnected', datetime: '2026-10-27T10:00:01Z', data: { clientId: 'p1' } },
      { id: 3, name: 'clientConnected', datetime: '2026-10-27T10:00:02Z', data: { clientId: 'p2' } },
      { id: 4, name: 'clientConnected', datetime: '2026-10-27T10:00:03Z', data: { clientId: '_bot1' } },
      { id: 5, name: 'choice', datetime: '2026-10-27T10:00:04Z', data: { clientId: 'p9' } },
    ])
    expect([...participantsSeen(ev)].sort()).toEqual(['p1', 'p2'])
  })

  it('keeps the feed capped', () => {
    const live = Array.from({ length: FEED_CAP + 20 }, (_, i) => ({ id: i + 1, name: 'x', datetime: new Date(1e12 + i).toISOString(), data: {} }))
    const merged = mergeEvents([], live, FEED_CAP)
    expect(merged).toHaveLength(FEED_CAP)
    expect(merged[0].id).toBe(FEED_CAP + 20)
  })
})

// ---- live state ------------------------------------------------------------------------------

function node (id: string, data: Record<string, any> = {}) {
  return { id, data }
}

describe('live session state', () => {
  it('counts people, connections, AI seats and ties', () => {
    const s = networkStats([
      node('p1', { _system: { connected: true } }),
      node('p2', { _system: { connected: false } }),
      node('p3', { _system: { connected: true }, ai: 1 }),
      node('p4'),
      node('_bot1', { _system: { connected: true } }),
    ], 4)
    expect(s).toEqual({ people: 4, connected: 2, aiSeats: 2, bots: 1, takenOver: 1, ties: 4 })
  })

  it('summarises the waiting room from the players\' autopilot status', () => {
    expect(waitingRoom([node('p1'), node('_ai', { _system: { autopilot: { state: 'waiting', here: 9 } } })])).toBeNull()
    expect(waitingRoom([
      node('p1', { _system: { autopilot: { state: 'waiting', here: 2, min: 4, max: 8 } } }),
      node('p2', { _system: { autopilot: { state: 'waiting', here: 3, min: 4, max: 8 } } }),
    ])).toEqual({ state: 'waiting', here: 3, min: 4, max: 8, seconds: null })
    // a countdown wins, with the fewest seconds left
    expect(waitingRoom([
      node('p1', { _system: { autopilot: { state: 'waiting', here: 3, min: 4 } } }),
      node('p2', { _system: { autopilot: { state: 'starting', here: 4, min: 4, seconds: 10 } } }),
      node('p3', { _system: { autopilot: { state: 'starting', here: 4, min: 4, seconds: 6 } } }),
    ])).toEqual({ state: 'starting', here: 4, min: 4, max: null, seconds: 6 })
    expect(waitingRoom([node('p1', { _system: { autopilot: { state: 'cancelled' } } })])?.state).toBe('cancelled')
  })

  it('builds the participant links', () => {
    const l = joinLinks('https://bb.lab.org/', 12, 44)
    expect(l.session).toBe('https://bb.lab.org/game/12/44/login')
    expect(l.current).toBe('https://bb.lab.org/play/12/login')
    expect(l.prolific).toBe('https://bb.lab.org/play/12/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}')
    expect(joinLinks('http://x', 1, 0).session).toBeNull()
    expect(isLocalOrigin('http://localhost:9000')).toBe(true)
    expect(isLocalOrigin('http://127.0.0.1:9000')).toBe(true)
    expect(isLocalOrigin('https://bb.lab.org')).toBe(false)
  })

  it('tells when the game has started', () => {
    const out = (kind: any, text: string, at: number) => ({ id: at, kind, text, at })
    const base = { events: [], output: [], since: 0 }
    expect(detectGameStarted(base).started).toBe(false)
    expect(detectGameStarted({ ...base, hasStarted: true }).source).toBe('server')
    expect(detectGameStarted({ ...base, output: [out('output', 'initStep.start()\n\n==>', 50)] })).toEqual({ started: true, source: 'output', at: 50 })
    expect(detectGameStarted({ ...base, output: [out('error', 'Caught error: initStep started already', 60)] }).started).toBe(true)
    // a start before the engine reloaded no longer counts
    expect(detectGameStarted({ ...base, output: [out('output', 'initStep.start()\n\n==>', 50)], since: 100 }).started).toBe(false)
    // console input that merely mentions it is not a start
    expect(detectGameStarted({ ...base, output: [out('input', 'initStep.start()', 150)] }).started).toBe(false)
    const ev = mergeEvents([], [{ id: 1, name: 'InitStepStart', datetime: new Date(200000).toISOString(), data: {} }])
    expect(detectGameStarted({ ...base, events: ev })).toMatchObject({ started: true, source: 'events' })
    expect(detectGameStarted({ ...base, events: ev, since: 900000 }).started).toBe(false)
    const auto = mergeEvents([], [{ id: 1, name: 'autopilotStart', datetime: new Date(200000).toISOString(), data: {} }])
    expect(detectGameStarted({ ...base, events: auto }).started).toBe(true)
  })

  it('recognises the engine\'s answer to a start', () => {
    expect(isStartReply({ error: '', output: 'initStep.start()\n\n==>null' })).toBe(true)
    expect(isStartReply({ error: 'Caught error: initStep started already\n' })).toBe(true)
    expect(isStartReply({ error: '', output: 'InitStep source;null;\n\n==>' })).toBe(false)
    expect(isStartReply({ action: 'addNode', id: 'p1' })).toBe(false)
    expect(isStartReply({ user: {} })).toBe(false)
    expect(isStartReply({})).toBe(false)
    expect(classifyStartReply({ error: '', output: 'initStep.start()\n\n==>' })).toEqual({ kind: 'started' })
    expect(classifyStartReply({ error: 'Caught error: initStep started already\n' })).toEqual({ kind: 'already' })
    expect(classifyStartReply({ error: 'Caught error: MissingPropertyException: No such property: x\n  at InitStep.groovy:3' }))
      .toEqual({ kind: 'error', message: 'MissingPropertyException: No such property: x' })
    expect(classifyStartReply(null)).toEqual({ kind: 'timeout' })
    expect(cleanError('Caught error: Boom\nmore')).toBe('Boom')
  })

  it('formats durations and file names', () => {
    expect(formatDuration(45000)).toBe('45 s')
    expect(formatDuration(12 * 60000)).toBe('12 min')
    expect(formatDuration(65 * 60000)).toBe('1 h 5 min')
    expect(formatDuration(120 * 60000)).toBe('2 h')
    expect(formatDuration(27 * 3600000)).toBe('1 d 3 h')
    expect(formatDuration(-1)).toBe('—')
    expect(safeFileName('pilot: 1/2 "x"')).toBe('pilot- 1-2 -x-')
    expect(safeFileName('')).toBe('session')
  })
})

// ---- the new-session dialog ------------------------------------------------------------------

describe('NewSessionDialog', () => {
  let admin: any
  let wrapper: VueWrapper | null = null
  // the dialog is teleported to <body>; test-utils' Teleport stub re-creates its content on
  // every re-render, so the real one is used and the page is read from the document
  const page = () => new DOMWrapper(document.body)
  const text = () => document.body.textContent || ''
  const numberInput = () => page().findAll('input').find(i => i.attributes('inputmode') === 'numeric')!
  const submit = async () => {
    await page().find('form').trigger('submit')
    await flushPromises()
  }

  beforeEach(async () => {
    admin = (await import('@/store/admin')).admin
    store.launch.mockReset()
    admin.instance = null
    admin.experiment = {
      id: 12,
      name: 'Exp',
      instances: [{ id: 3, name: 'Session 1', status: 'FINISHED' }],
      parameters: [
        param({ name: 'nRounds', type: 'Integer', defaultVal: '', minVal: '1', maxVal: '10', description: 'How many rounds' }),
        param({ name: 'chat', type: 'Boolean', defaultVal: 'true' }),
        param({ name: 'rate', type: 'Decimal', defaultVal: '0.333' }),
        param({ name: 'label', type: 'Text', defaultVal: 'blue' }),
      ],
    }
  })
  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
  })

  async function open () {
    const NewSessionDialog = (await import('../src/pages/session/NewSessionDialog.vue')).default
    wrapper = mount(NewSessionDialog, { props: { open: true }, attachTo: document.body, global: { stubs: { RouterLink: true } } })
    await nextTick()
    return wrapper
  }

  it('pre-fills the name and defaults, and flags a default that cannot be used', async () => {
    await open()
    expect((page().findAll('input')[0].element as HTMLInputElement).value).toBe('Session 2')
    expect(text()).toContain('This parameter has no default. Enter a value.')
    expect(text()).toContain('How many rounds')
    expect(text()).toContain('1 to 10')
    expect(numberInput().attributes('aria-invalid')).toBe('true')
  })

  it('refuses to send an empty number, and sends typed values once fixed', async () => {
    const w = await open()
    await submit()
    expect(store.launch).not.toHaveBeenCalled()
    expect(text()).toContain('One field needs your attention.')

    await numberInput().setValue('12')
    await submit()
    expect(store.launch).not.toHaveBeenCalled()
    expect(text()).toContain('Must be between 1 and 10.')

    store.launch.mockResolvedValue({ user: {} })
    await numberInput().setValue('4')
    await submit()
    expect(store.launch).toHaveBeenCalledTimes(1)
    const [name, params] = store.launch.mock.calls[0]
    expect(name).toBe('Session 2')
    expect(params).toEqual({ nRounds: 4, chat: true, rate: 0.333, label: 'blue' })
    const opens = w.emitted('update:open') || []
    expect(opens[opens.length - 1]).toEqual([false])
    expect(w.emitted('created')?.[0]).toEqual(['Session 2'])
  })

  it('sends a switched-off boolean as false', async () => {
    await open()
    await numberInput().setValue('2')
    await page().find('input[role="switch"]').setValue(false)
    store.launch.mockResolvedValue({ user: {} })
    await submit()
    expect(store.launch.mock.calls[0][1]).toEqual({ nRounds: 2, chat: false, rate: 0.333, label: 'blue' })
  })

  it('keeps the dialog open and says so when the server does not confirm', async () => {
    const w = await open()
    await numberInput().setValue('3')
    store.launch.mockResolvedValue(null)
    await submit()
    expect(store.launch).toHaveBeenCalledTimes(1)
    expect(text()).toContain('The server did not confirm the new session.')
    expect(w.emitted('update:open')).toBeFalsy()
  })

  it('warns that a running session will be stopped', async () => {
    admin.instance = { id: 9, name: 'Live one', status: 'RUNNING' }
    await open()
    expect(text()).toContain('“Live one” is running.')
    expect(text()).toContain('Stop it and create')
  })

  it('rejects a name another session already has', async () => {
    await open()
    await page().findAll('input')[0].setValue('session 1')
    await numberInput().setValue('3')
    await submit()
    expect(store.launch).not.toHaveBeenCalled()
    expect(text()).toContain('already has this name')
  })
})
