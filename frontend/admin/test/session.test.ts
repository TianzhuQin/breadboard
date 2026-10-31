import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  INT_MAX, MAX_TEXT, buildFields, buildLaunchParams, checkParam, checkSessionName, fieldErrors, normalizeType,
  parseBound, rangeText, suggestName,
} from '../src/pages/session/params'
import {
  FEED_CAP, describeEvent, eventNameCounts, eventTone, filterEvents, formatDataValue, formatValue, fromLive, fromSeed, mergeEvents,
  mergeFeed, parseServerTime, participantsSeen, summarizeData,
} from '../src/pages/session/events'
import EventRow from '../src/pages/session/EventRow.vue'
import {
  classifyStartReply, cleanError, detectGameStarted, engineGameFlag, formatDuration, isLocalOrigin, isPrivateOrigin, isStartReply, joinLinks,
  networkStats, safeFileName, waitingRoom,
} from '../src/pages/session/live'
import LocalOnlyNote from '../src/pages/session/LocalOnlyNote.vue'
import { busy, resumeSession, startGame, stopSession } from '../src/pages/session/controls'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts } from '../src/ui/toast'
import type { Parameter } from '../src/store/types'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const store = vi.hoisted(() => ({
  launch: vi.fn(),
  startGame: vi.fn(() => true),
  selectInstance: vi.fn(async () => null),
  stopInstance: vi.fn(async (): Promise<any> => ({ user: {} })),
  ensureConnected: vi.fn(() => true),
  nextMessage: vi.fn((_match: (m: any) => boolean, _ms?: number): Promise<any> => new Promise(() => {})),
  confirmEngineRebuild: vi.fn(async () => true),
  engineUse: vi.fn(async () => ({ who: '', pausesAutopilot: false })),
  autopilotNow: vi.fn(async (): Promise<any> => null),
}))
vi.mock('@/store/admin', async () => {
  const { reactive, computed, ref } = await import('vue')
  const admin = reactive<any>({
    experiment: null, instance: null, user: null, output: [], events: [], engineReloadedAt: 0, engine: 'ready', email: 'me@lab.org',
    engineInfoCurrent: true, engineInfoAt: 0,
  })
  return {
    admin,
    actions: { launch: store.launch, startGame: store.startGame, selectInstance: store.selectInstance, stopInstance: store.stopInstance },
    instances: computed(() => (admin.experiment?.instances || [])),
    nextMessage: store.nextMessage,
    ensureConnected: store.ensureConnected,
    engineElsewhere: ref(null),
    NOT_CONNECTED: 'Not connected to the server',
    isResearcherRun: (source?: string) => source === 'SendScript' || source === 'SendStep',
    graph: { nodes: new Map(), links: new Map(), version: { value: 0 } },
  }
})
vi.mock('@/store/engineGuard', () => ({
  confirmEngineRebuild: store.confirmEngineRebuild,
  engineUse: store.engineUse,
  REBUILD_PAUSES_AUTOPILOT: 'This also pauses the autopilot.',
}))
// what GET /autopilot says, for the stop's question (the rest of the module as it is)
vi.mock('../src/pages/autopilot/autopilot', async (orig) => ({ ...(await orig<any>()), autopilotNow: store.autopilotNow }))
// what GET /autopilot says, read when a page mounts (null: no autopilot, or an older server)
const autopilotHint = vi.hoisted(() => ({ status: null as any }))
vi.mock('../src/pages/session/autopilot', async (orig) => {
  const { ref } = await import('vue')
  return { ...(await orig<any>()), useAutopilotStatus: () => ref(autopilotHint.status) }
})
const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', async (orig) => ({
  ...(await orig<any>()),
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn(), push: routerPush }),
}))

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

  it('says a preview\'s sign-in is a researcher\'s, and names computer players as the other pages do', () => {
    expect(describeEvent('clientLogIn', { clientId: '_6', preview: 'true' })).toBe('A researcher’s preview signed in as AI 6')
    expect(describeEvent('clientLogIn', { clientId: 't1', preview: true })).toBe('A researcher’s preview signed in as t1')
    expect(describeEvent('clientLogIn', { clientId: 'p1' })).toBe('Someone signed in')
    expect(summarizeData({ player: '_4', choice: 'Blue', playerId: 'p2' }).shown).toEqual([['player', 'AI 4 (_4)'], ['choice', 'Blue'], ['playerId', 'p2']])
    expect(formatDataValue('clientId', '_12')).toBe('AI 12 (_12)')
    // other data keeps its values as recorded
    expect(formatDataValue('choice', '_4')).toBe('_4')
    const w = mount(EventRow, { props: { event: { key: 'e1', id: 1, name: 'clientLogIn', at: Date.now(), data: { clientId: '_6', preview: 'true' } } } })
    expect(w.find('.live-evr__plain').text()).toBe('A researcher’s preview signed in as AI 6')
    // (the whole line on hover too, where a narrow column clamps it)
    expect(w.find('.live-evr__plain').attributes('title')).toBe('A researcher’s preview signed in as AI 6')
    expect(w.find('.live-evr__data').text()).toContain('AI 6 (_6)')
    w.unmount()
  })

  it('explains failures, ends and the newer events in plain words, and failures stand out', () => {
    for (const name of ['stepFailed', 'sessionStalled', 'sessionFailed']) expect(eventTone(name)).toBe('danger')
    for (const name of ['sessionTooLong', 'serverStopping', 'noCompletionCode', 'answerDropped', 'sessionFull']) expect(eventTone(name)).toBe('warning')
    expect(eventTone('engineReloaded')).toBe('accent')
    expect(eventTone('prolificComplete')).toBe('success')
    // someone left out is no milestone (it ends in "Start", as a step's own event does)
    expect(eventTone('missedStart')).toBe('warning')
    expect(describeEvent('missedStart')).toBe('The game started without someone who was away')
    expect(describeEvent('stepFailed')).toBe('A step failed: its code threw an error')
    expect(describeEvent('sessionStalled')).toMatch(/nothing left to do/)
    expect(describeEvent('sessionTooLong')).toMatch(/longest session/)
    expect(describeEvent('serverStopping')).toMatch(/server was stopped/)
    expect(describeEvent('sessionFailed')).toMatch(/autopilot ended the session/)
    for (const name of ['aiChoice', 'prolificComplete', 'noCompletionCode', 'engineReloaded', 'sessionFull', 'participantComment', 'answerDropped', 'connectionGap']) {
      expect(describeEvent(name), name).not.toBe('')
    }
  })

  it('names the step and line of a failure, and says whether a reload started the game again, from the event\'s data', () => {
    expect(describeEvent('stepFailed', { step: 'RoundStep', error: 'line 72: No such property: roundNote' })).toBe('A step failed (RoundStep, line 72): its code threw an error')
    expect(describeEvent('sessionFailed', { step: 'RoundStep', line: '71', error: 'No such property: x' })).toBe('A step failure (RoundStep, line 71) stalled the game, so the autopilot ended the session')
    expect(describeEvent('sessionStalled', { step: 'FinishStep', error: 'boom' })).toBe('After a step failed (FinishStep), the game had nothing left to do, so it was ended')
    expect(describeEvent('engineReloaded', { restarted: 'true', by: 'a@example.org' })).toBe('A researcher reloaded the engine: the game started again from the beginning')
    expect(describeEvent('engineReloaded', { restarted: false, by: 'a@example.org' })).toBe('A researcher reloaded the engine; the session carried on')
    expect(describeEvent('connectionGap', { player: 'p1', awayMs: '25300' })).toBe('A participant’s connection dropped for 25 s, then came back')
    expect(describeEvent('answerDropped', { player: 'p1', ageMs: 8000 })).toBe('A participant’s answer was dropped: it waited 8 s for the connection')
    // without that data, the plain line
    expect(describeEvent('stepFailed', {})).toBe('A step failed: its code threw an error')
    expect(describeEvent('engineReloaded')).toBe('A researcher reloaded the engine')
    expect(describeEvent('connectionGap', { awayMs: 'soon' })).toBe('A participant’s connection dropped for a moment, then came back')
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
    ])).toEqual({ state: 'waiting', here: 3, min: 4, max: 8, seconds: null, startsIn: null })
    // a countdown wins, with the fewest seconds left
    expect(waitingRoom([
      node('p1', { _system: { autopilot: { state: 'waiting', here: 3, min: 4 } } }),
      node('p2', { _system: { autopilot: { state: 'starting', here: 4, min: 4, seconds: 10 } } }),
      node('p3', { _system: { autopilot: { state: 'starting', here: 4, min: 4, seconds: 6 } } }),
    ])).toEqual({ state: 'starting', here: 4, min: 4, max: null, seconds: 6, startsIn: null })
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

  it('says a finished or stopped session\'s network card that the session has ended, not that nobody joined', async () => {
    const NetworkPreview = (await import('../src/pages/session/NetworkPreview.vue')).default
    const stubs = { AdminNetwork: { template: '<div class="net-stub"><slot name="empty" /></div>' }, RouterLink: true }
    let w = mount(NetworkPreview, { props: { people: 0, ties: 0, ended: true }, global: { stubs } })
    expect(w.find('.live-net__empty').text()).toBe('This session has ended.')
    expect(w.find('.b-panel__subtitle').text()).toBe('The session has ended')
    w.unmount()
    w = mount(NetworkPreview, { props: { people: 0, ties: 0 }, global: { stubs } })
    expect(w.find('.live-net__empty').text()).toBe('No one has joined yet.')
    expect(w.find('.b-panel__subtitle').text()).toBe('0 people · 0 ties')
    w.unmount()
    // what is still there when it ended
    w = mount(NetworkPreview, { props: { people: 2, ties: 1, ended: true }, global: { stubs } })
    expect(w.find('.b-panel__subtitle').text()).toBe('2 people · 1 tie')
    w.unmount()
  })

  it('says under the links that participants type an ID to join', async () => {
    const JoinLinks = (await import('../src/pages/session/JoinLinks.vue')).default
    const w = mount(JoinLinks, { props: { experimentId: 12, instanceId: 44, joinable: true } })
    expect(w.find('.live-links__ids').text()).toBe('With the first two links, participants type an ID to join: any name or number you give them, one seat per ID. People from Prolific don\'t type one.')
    w.unmount()
  })

  it('says a link for Prolific needs the server\'s public address, without the tip for the lab\'s network', async () => {
    const { admin } = await import('@/store/admin') as any
    admin.lanAddresses = ['http://10.0.0.5:9000']
    // (the tests' page is at localhost)
    const host = window.location.host
    const prolific = mount(LocalOnlyNote, { props: { what: 'This URL uses', internet: true } })
    const text = prolific.text().replace(/\s+/g, ' ')
    expect(text).toContain(`This URL uses ${host}, which only works on this computer.`)
    expect(text).toContain('Prolific sends people from the internet, so it needs the server’s public address: open the console at that address and copy it there (see putting Breadboard on a server).')
    expect(text).not.toContain('same network')
    expect(prolific.find('a').attributes('href')).toMatch(/install\.md#putting-it-on-a-server$/)
    prolific.unmount()
    // other links keep it
    const links = mount(LocalOnlyNote)
    expect(links.text().replace(/\s+/g, ' ')).toContain('For people on the same network (say, the lab\'s computers), open the console at http://10.0.0.5:9000')
    links.unmount()
    admin.lanAddresses = []
    // a lab network's address is no good for Prolific either
    expect(isPrivateOrigin('http://192.168.1.23:9000')).toBe(true)
    expect(isPrivateOrigin('http://10.254.125.201:19841')).toBe(true)
    expect(isPrivateOrigin('http://172.20.1.2')).toBe(true)
    expect(isPrivateOrigin('http://172.40.1.2')).toBe(false)
    expect(isPrivateOrigin('http://lab-mac.local:9000')).toBe(true)
    expect(isPrivateOrigin('https://bb.lab.org')).toBe(false)
    expect(isPrivateOrigin('http://localhost:9000')).toBe(false)
  })

  it('tells when the game has started', () => {
    const out = (kind: any, text: string, at: number) => ({ id: at, kind, text, at })
    const base = { events: [], output: [], since: 0 }
    expect(detectGameStarted(base).started).toBe(false)
    expect(detectGameStarted({ ...base, hasStarted: true })).toMatchObject({ started: true, source: 'server', sure: true })
    expect(detectGameStarted({ ...base, output: [out('output', 'initStep.start()\n\n==>', 50)] })).toEqual({ started: true, source: 'output', at: 50, sure: true, nearReload: false, forgotten: false })
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

  it('never takes a start event alone as certain, and says when it may be from before the last reload', () => {
    const ev = (name: string, at: number | null, id = 1) => mergeEvents([], [{ id, name, datetime: at === null ? '' : new Date(at).toISOString(), data: {} }])
    const base = { output: [], since: 0 }
    // the log shows a start (the autopilot's, or a start that threw after logging it): started, but not for certain
    expect(detectGameStarted({ ...base, events: ev('autopilotStart', 200000) })).toMatchObject({ started: true, source: 'events', sure: false, nearReload: false })
    expect(detectGameStarted({ ...base, events: ev('InitStepStart', 200000), since: 150000 })).toMatchObject({ started: true, sure: false, nearReload: false })
    expect(detectGameStarted({ ...base, events: ev('InitStepStart', null) })).toMatchObject({ started: true, sure: false, nearReload: false })
    // a few seconds before the reload (the clocks may disagree), or at an unknown time after one
    expect(detectGameStarted({ ...base, events: ev('InitStepStart', 198000), since: 200000 })).toMatchObject({ started: true, sure: false, nearReload: true, at: 198000 })
    expect(detectGameStarted({ ...base, events: ev('InitStepStart', null), since: 200000 })).toMatchObject({ started: true, sure: false, nearReload: true })
    // one clearly after the reload wins over one that may be from before it
    const both = mergeFeed(ev('InitStepStart', 198000, 1), ev('autopilotStart', 230000, 2))
    expect(detectGameStarted({ ...base, events: both, since: 200000 })).toMatchObject({ nearReload: false, at: 230000 })
    // the engine's own answer makes it certain
    const out = [{ id: 1, kind: 'error' as const, text: 'Caught error: initStep started already', source: 'RunGame', at: 240000 }]
    expect(detectGameStarted({ events: ev('InitStepStart', 200000), output: out, since: 0 })).toMatchObject({ started: true, source: 'output', sure: true })
  })

  it('takes a start that threw as not started, though the log shows its start event', () => {
    const ev = (name: string, at: number, id = 1) => mergeEvents([], [{ id, name, datetime: new Date(at).toISOString(), data: {} }])
    const failed = (at: number) => ({ id: at, kind: 'error' as const, text: 'Caught error: NullPointerException: Cannot get property \'x\' on null object\n at InitStep.groovy:4', source: 'RunGame', at })
    // Step.start() logged InitStepStart just before the step threw; the server's flag may still say started
    const r = detectGameStarted({ hasStarted: true, events: ev('InitStepStart', 300000), output: [failed(301000)], since: 0 })
    expect(r).toMatchObject({ started: false, sure: true })
    // the server's clock a few seconds ahead of this computer's: still the same failed start
    expect(detectGameStarted({ events: ev('InitStepStart', 304000), output: [failed(301000)], since: 0 }).started).toBe(false)
    // a start after it (the autopilot's, or another console's) counts again, from the log only
    expect(detectGameStarted({ events: ev('autopilotStart', 330000), output: [failed(301000)], since: 0 })).toMatchObject({ started: true, sure: false, at: 330000 })
    // a successful retry from this console wins
    const retried = [failed(301000), { id: 2, kind: 'output' as const, text: 'initStep.start()\n\n==>null', source: 'RunGame', at: 320000 }]
    expect(detectGameStarted({ events: ev('InitStepStart', 319000), output: retried, since: 0 })).toMatchObject({ started: true, sure: true })
    // a refusal changed nothing, nor did an error that isn't RunGame's answer
    const refused = { id: 3, kind: 'error' as const, text: 'Nothing was done: the engine is running "X"', source: 'RunGame', at: 301000 }
    expect(detectGameStarted({ hasStarted: true, events: [], output: [refused], since: 0 })).toMatchObject({ started: true, source: 'server' })
    const timer = { id: 4, kind: 'error' as const, text: 'Caught error: timer failed', at: 301000 }
    expect(detectGameStarted({ events: ev('InitStepStart', 300000), output: [timer], since: 0 }).started).toBe(true)
    // a failure from before the engine last reloaded is about code that no longer runs
    expect(detectGameStarted({ hasStarted: true, events: [], output: [failed(301000)], since: 400000 })).toMatchObject({ started: true, source: 'server' })
  })

  it('believes the engine\'s own flag over the session\'s, which outlives a reload', () => {
    const flagAt = 500000
    const ev = (name: string, at: number, receivedAt?: number, id = at) => mergeEvents([], [{ id, name, datetime: new Date(at).toISOString(), data: {}, receivedAt }])
    const out = (kind: any, text: string, at: number) => ({ id: at, kind, text, source: 'RunGame', at })
    const base = { events: [], output: [], since: 0 }
    // resumed: the session says it started, the engine (rebuilt since) says it isn't running
    expect(detectGameStarted({ ...base, hasStarted: true, engine: { started: false, at: flagAt } }))
      .toEqual({ started: false, source: 'engine', at: null, sure: true, nearReload: false, forgotten: true })
    // its start events from before the flag was read don't count either, also with no reload seen here
    const old = ev('InitStepStart', 300000)
    expect(detectGameStarted({ ...base, hasStarted: true, events: old, engine: { started: false, at: flagAt } }).started).toBe(false)
    expect(detectGameStarted({ ...base, events: old, engine: { started: false, at: flagAt } })).toMatchObject({ started: false, forgotten: false })
    // and the engine says it runs
    expect(detectGameStarted({ ...base, engine: { started: true, at: flagAt } })).toMatchObject({ started: true, source: 'engine', sure: true })
    // what this console heard after the flag was read is newer: a start answered here ...
    expect(detectGameStarted({ ...base, hasStarted: true, output: [out('output', 'initStep.start()\n\n==>null', flagAt + 10)], engine: { started: false, at: flagAt } }))
      .toMatchObject({ started: true, source: 'output', sure: true })
    // (not one from before it)
    expect(detectGameStarted({ ...base, output: [out('output', 'initStep.start()\n\n==>null', flagAt - 10)], engine: { started: false, at: flagAt } }).started).toBe(false)
    // ... a start that threw here ...
    expect(detectGameStarted({ ...base, output: [out('error', 'Caught error: Boom', flagAt + 10)], engine: { started: true, at: flagAt } }))
      .toMatchObject({ started: false, forgotten: false })
    // (when the game had started before the reload, a start tried again that threw leaves it the
    // forgotten one: the server puts the session's flag back as it was, so it still turns people away)
    expect(detectGameStarted({ ...base, hasStarted: true, output: [out('error', 'Caught error: Boom', flagAt + 10)], engine: { started: false, at: flagAt } }))
      .toMatchObject({ started: false, sure: true, forgotten: true })
    // ... or another console's start pushed live
    expect(detectGameStarted({ ...base, events: ev('autopilotStart', 600000, flagAt + 5), engine: { started: false, at: flagAt } }))
      .toMatchObject({ started: true, source: 'events', sure: false, at: 600000 })
    expect(detectGameStarted({ ...base, events: ev('autopilotStart', 600000, flagAt - 5), engine: { started: false, at: flagAt } }).started).toBe(false)
  })

  it('takes the engine\'s flag only when it is current and about this session', () => {
    const engine = { experimentId: 1, experimentName: 'E', sessionId: 9, sessionName: 'S', owner: 'me@lab.org', gameStarted: false }
    expect(engineGameFlag(engine, true, 9, 1234)).toEqual({ started: false, at: 1234 })
    expect(engineGameFlag({ ...engine, gameStarted: true }, true, 9, 1234)).toEqual({ started: true, at: 1234 })
    // a rebuild under way, another session, nothing said (an older server), no engine
    expect(engineGameFlag(engine, false, 9, 1234)).toBe(null)
    expect(engineGameFlag(engine, true, 8, 1234)).toBe(null)
    expect(engineGameFlag(engine, true, undefined, 1234)).toBe(null)
    expect(engineGameFlag({ ...engine, gameStarted: null }, true, 9, 1234)).toBe(null)
    const { gameStarted: _drop, ...older } = engine
    expect(engineGameFlag(older, true, 9, 1234)).toBe(null)
    expect(engineGameFlag(null, true, 9, 1234)).toBe(null)
  })

  it('recognises the engine\'s answer to a start', () => {
    expect(isStartReply({ error: '', output: 'initStep.start()\n\n==>null' })).toBe(true)
    expect(isStartReply({ error: 'Caught error: initStep started already\n' })).toBe(true)
    expect(isStartReply({ error: '', output: 'InitStep source;null;\n\n==>' })).toBe(false)
    expect(isStartReply({ action: 'addNode', id: 'p1' })).toBe(false)
    expect(isStartReply({ user: {} })).toBe(false)
    expect(isStartReply({})).toBe(false)
    // other engine errors (a late joiner's OnJoinStep) are not the answer to the start
    expect(isStartReply({ error: 'Caught error: groovy.lang.MissingPropertyException: No such property: x for class: OnJoinStep\n' })).toBe(false)
    // a reply the server tags as the start's own is, error or not
    expect(isStartReply({ request: 'RunGame', error: 'Caught error: Boom\n' })).toBe(true)
    expect(isStartReply({ request: 'RunGame', error: '', output: 'x' })).toBe(true)
    // a tag decides: another request's answer or OnJoinStep's frame is never the start's, whatever it says
    expect(isStartReply({ request: 'SendScript', error: '', output: 'initStep.start()\n\n==>null' })).toBe(false)
    expect(isStartReply({ source: 'OnJoinStep', error: 'Caught error: initStep started already\n' })).toBe(false)
    expect(isStartReply({ request: 'RunGame', error: 'Caught error: initStep started already\n' })).toBe(true)
    expect(classifyStartReply({ request: 'RunGame', error: 'Caught error: initStep started already\n' })).toEqual({ kind: 'already' })
    expect(classifyStartReply({ request: 'RunGame', error: 'The engine is running another experiment.', output: 'initStep.start()' }))
      .toEqual({ kind: 'error', message: 'The engine is running another experiment.' })
    expect(classifyStartReply({ request: 'RunGame', error: 'Caught error: Boom\n' })).toEqual({ kind: 'error', message: 'Boom' })
    expect(classifyStartReply({ request: 'RunGame', error: '', output: 'initStep.start()' })).toEqual({ kind: 'started' })
    expect(classifyStartReply({ request: 'RunGame', error: '' })).toEqual({ kind: 'started' })
    expect(classifyStartReply({ error: '', output: 'initStep.start()\n\n==>' })).toEqual({ kind: 'started' })
    expect(classifyStartReply({ error: 'Caught error: initStep started already\n' })).toEqual({ kind: 'already' })
    expect(classifyStartReply({ error: 'Caught error: MissingPropertyException: No such property: x\n  at InitStep.groovy:3' }))
      .toEqual({ kind: 'error', message: 'MissingPropertyException: No such property: x' })
    expect(classifyStartReply(null)).toEqual({ kind: 'timeout' })
    expect(cleanError('Caught error: Boom\nmore')).toBe('Boom')
  })

  it('shows the network preview at its own fit, not the Network page\'s zoom and pan', async () => {
    const NetworkPreview = (await import('../src/pages/session/NetworkPreview.vue')).default
    const w = mount(NetworkPreview, { props: { people: 40, ties: 1 }, global: { stubs: { AdminNetwork: true, RouterLink: true } } })
    expect(w.text()).toContain('40 people · 1 tie')
    const net = w.findComponent({ name: 'AdminNetwork' })
    expect(net.exists()).toBe(true)
    expect(net.props('rememberView')).toBe(false)
    w.unmount()
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

// ---- the session controls -------------------------------------------------------------------

describe('session controls', () => {
  let store2: any
  const ctx = { connected: 2, people: 3, waitingRoom: false }
  const inst = { id: 5, name: 'Pilot', status: 'STOPPED' } as any

  beforeEach(async () => {
    store2 = await import('@/store/admin')
    store2.engineElsewhere.value = null
    store2.admin.instance = null
    for (const f of Object.values(store) as any[]) f.mockClear()
    toasts.splice(0, toasts.length)
    if (confirmState.open) settleConfirm(false)
  })

  it('does not ask or send while offline, and says so at once when the connection drops meanwhile', async () => {
    store.ensureConnected.mockReturnValueOnce(false)
    await startGame(ctx)
    expect(confirmState.open).toBe(false)
    expect(store.startGame).not.toHaveBeenCalled()

    const done = startGame(ctx)
    expect(confirmState.open).toBe(true)
    store.startGame.mockReturnValueOnce(false)
    settleConfirm(true)
    await done
    // no 20 s wait for an answer to a request that was never sent
    expect(busy.start).toBe(false)
    expect(toasts.some(t => t.title === 'Not connected to the server' && /not started/.test(t.detail || ''))).toBe(true)
    expect(toasts.some(t => /No answer/.test(t.title))).toBe(false)
  })

  it('says in plain words what starting the game does, without the code it runs', async () => {
    const done = startGame(ctx)
    expect(confirmState.open).toBe(true)
    expect(confirmState.req!.title).toBe('Start the game?')
    expect(confirmState.req!.message).toContain('Everyone who is waiting is let in and the experiment begins. People who arrive after this are turned away.')
    expect(confirmState.req!.message).not.toContain('initStep')
    settleConfirm(false)
    await done
    expect(store.startGame).not.toHaveBeenCalled()
  })

  it('takes the server\'s tagged answer to the start', async () => {
    store.nextMessage.mockImplementationOnce(async (match: any) => {
      const m = { request: 'RunGame', error: '', output: 'initStep.start()\n\n==>null' }
      return match(m) ? m : null
    })
    const done = startGame(ctx)
    settleConfirm(true)
    await done
    expect(store.startGame).toHaveBeenCalledTimes(1)
    expect(toasts.some(t => t.title === 'The game has started')).toBe(true)
  })

  it('refuses to start while the engine runs another experiment', async () => {
    store2.engineElsewhere.value = { experimentId: 2, experimentName: 'Other study', sessionId: 8, sessionName: 'S', owner: 'bob@lab.org' }
    await startGame(ctx)
    expect(confirmState.open).toBe(false)
    expect(store.startGame).not.toHaveBeenCalled()
    expect(toasts[toasts.length - 1].detail).toMatch(/running “Other study” right now, not this session/)
  })

  it('names whoever the restart disconnects before resuming', async () => {
    store.engineUse.mockResolvedValueOnce({ who: 'The autopilot has session “Auto 3” open. 4 players are in the network right now.', pausesAutopilot: false })
    const done = resumeSession(inst)
    await flushPromises()
    expect(confirmState.req!.title).toBe('Resume “Pilot”?')
    expect(confirmState.req!.message).toMatch(/^The autopilot has session “Auto 3” open/)
    expect(confirmState.req!.message).toMatch(/every researcher on this server shares/)
    expect(confirmState.req!.danger).toBe(true)
    settleConfirm(false)
    await done
    expect(store.selectInstance).not.toHaveBeenCalled()

    // nobody using the engine: the plain question
    const again = resumeSession(inst)
    await flushPromises()
    expect(confirmState.req!.danger).toBe(false)
    expect(confirmState.req!.message).toMatch(/^The experiment's scripts restart/)
    settleConfirm(false)
    await again
  })

  it('shows the resume is under way while it asks who uses the engine, and ignores a second click', async () => {
    let answer!: (v: string) => void
    store.engineUse.mockImplementationOnce(() => new Promise(r => { answer = (who: string) => r({ who, pausesAutopilot: false }) }))
    const done = resumeSession(inst)
    expect(busy.resume).toBe(5)
    await resumeSession(inst)
    expect(store.engineUse).toHaveBeenCalledTimes(1)
    answer('')
    await flushPromises()
    expect(confirmState.req!.title).toBe('Resume “Pilot”?')
    settleConfirm(false)
    await done
    // declined: nothing sent, and the button is free again
    expect(store.selectInstance).not.toHaveBeenCalled()
    expect(busy.resume).toBe(null)
  })

  it('says that stopping the autopilot\'s session pauses the autopilot', async () => {
    const live = { id: 9, name: 'Auto 3', status: 'RUNNING' } as any
    store2.admin.experiment = { id: 12, name: 'Exp' }
    store.autopilotNow.mockResolvedValueOnce({ settings: { experimentId: 12 }, status: { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    let done = stopSession(live)
    // asking the server takes a moment: the button shows it at once
    expect(busy.stop).toBe(true)
    await flushPromises()
    expect(confirmState.req!.title).toBe('Stop “Auto 3”?')
    expect(confirmState.req!.message).toContain('until you press Resume on the Autopilot page')
    settleConfirm(true)
    await done
    expect(store.stopInstance).toHaveBeenCalledWith(9)
    expect(toasts[toasts.length - 1]).toMatchObject({ title: 'Stopped “Auto 3”', detail: expect.stringMatching(/autopilot is paused/) })
    expect(busy.stop).toBe(false)

    // already paused, or not the autopilot's session: no word about it
    store.autopilotNow.mockResolvedValueOnce({ settings: { experimentId: 12 }, status: { on: true, paused: true, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } } })
    done = stopSession(live)
    await flushPromises()
    expect(confirmState.req!.message).not.toContain('Autopilot page')
    settleConfirm(false)
    await done
    expect(store.stopInstance).toHaveBeenCalledTimes(1)
    store.autopilotNow.mockResolvedValueOnce(null)
    done = stopSession(live)
    await flushPromises()
    expect(confirmState.req!.message).not.toContain('Autopilot page')
    settleConfirm(true)
    await done
    expect(toasts[toasts.length - 1].detail).not.toMatch(/autopilot/)
    store2.admin.experiment = null
  })

  it('says why a session that was over already wasn\'t stopped (the autopilot isn\'t paused for it)', async () => {
    const live = { id: 9, name: 'Auto 3', status: 'RUNNING' } as any
    const why = 'Nothing was stopped: session "Auto 3" is already over (it has finished).'
    store.autopilotNow.mockResolvedValueOnce(null)
    store.stopInstance.mockImplementationOnce(async () => {
      store2.admin.requestError = { request: 'StopGame', text: why, at: Date.now() }
      return null
    })
    const done = stopSession(live)
    await flushPromises()
    settleConfirm(true)
    await done
    expect(toasts[toasts.length - 1]).toMatchObject({ title: '“Auto 3” was not stopped', detail: why })

    // an older refusal isn't the answer: no word from the server is said as such
    store.autopilotNow.mockResolvedValueOnce(null)
    store2.admin.requestError = { request: 'StopGame', text: why, at: Date.now() - 60000 }
    store.stopInstance.mockImplementationOnce(async () => null)
    const again = stopSession(live)
    await flushPromises()
    settleConfirm(true)
    await again
    expect(toasts[toasts.length - 1]).toMatchObject({ title: 'The server did not confirm the stop' })
    store2.admin.requestError = null
  })

  it('says that resuming pauses the autopilot when the engine runs its session', async () => {
    store.engineUse.mockResolvedValueOnce({ who: 'The autopilot has session “Auto 3” open.', pausesAutopilot: true })
    const done = resumeSession(inst)
    await flushPromises()
    expect(confirmState.req!.message).toContain('This also pauses the autopilot.')
    settleConfirm(false)
    await done
  })

  it('says a start after a reload starts the game again', async () => {
    let done = startGame({ ...ctx, again: true })
    expect(confirmState.req!.message).toMatch(/engine was reloaded since then, so this starts it again/)
    expect(confirmState.req!.message).not.toMatch(/Start does nothing until the engine is reloaded/)
    settleConfirm(false)
    await done
    done = startGame(ctx)
    expect(confirmState.req!.message).toMatch(/Start does nothing until the engine is reloaded/)
    settleConfirm(false)
    await done
  })

  it('does not ask to resume while offline, nor send when the connection drops meanwhile', async () => {
    store.ensureConnected.mockReturnValueOnce(false)
    await resumeSession(inst)
    expect(store.engineUse).not.toHaveBeenCalled()
    expect(confirmState.open).toBe(false)

    const done = resumeSession(inst)
    await flushPromises()
    store.ensureConnected.mockReturnValueOnce(false)
    settleConfirm(true)
    await done
    expect(store.selectInstance).not.toHaveBeenCalled()
    expect(busy.resume).toBe(null)
  })
})

// ---- the live session page ---------------------------------------------------------------------

describe('SessionPage', () => {
  let store2: any
  const stubs = { NewSessionDialog: true, NoSession: true, EventFeed: true, JoinLinks: true, NetworkPreview: true, SessionParams: true, RouterLink: true }
  const startEvent = (name: string, at: number) => ({ id: at, name, datetime: new Date(at).toISOString(), data: {} })

  beforeEach(async () => {
    store2 = await import('@/store/admin')
    store2.engineElsewhere.value = null
    store2.admin.experiment = { id: 12, name: 'Exp', instances: [], parameters: [] }
    store2.admin.instance = { id: 9, name: 'Live one', status: 'RUNNING', creationTime: '', data: [], events: [] }
    store2.admin.events = []
    store2.admin.output = []
    store2.admin.engineReloadedAt = 0
  })

  async function mountPage () {
    const SessionPage = (await import('../src/pages/session/SessionPage.vue')).default
    return mount(SessionPage, { global: { stubs } })
  }
  const startButton = (w: VueWrapper) => w.findAll('button').find(b => b.text().includes('Start the game'))

  it('leaves the error card\'s explained error its full height, so its message and Full error aren\'t squeezed', () => {
    // (EngineError is a flex column: a height cap on it shrank its message and "Full error" to a few
    // pixels. jsdom lays nothing out, so this reads the rule itself.)
    const page = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'pages', 'session', 'SessionPage.vue'), 'utf8')
    const rule = /\.live-err__text\s*\{([^}]*)\}/.exec(page)
    expect(rule).not.toBeNull()
    expect(rule![1]).not.toMatch(/max-height\s*:|(^|[\s;])overflow(-y)?\s*:/)
  })

  it('names the step an engine error happened in, links to its line, and keeps Breadboard\'s own code under Full error', async () => {
    const text = 'Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: roundNoteText for class: Script306\n' +
      '  at Script306.groovy:71\n  at step.groovy:200\n  at step.groovy:160\n  at actions.groovy:327'
    store2.admin.output = [{ id: 5, kind: 'error', text, source: 'roundStep', step: 'RoundStep', at: Date.now() }]
    routerPush.mockClear()
    const w = await mountPage()
    const card = w.find('.live-err')
    expect(card.text()).toContain('Unknown name “roundNoteText”')
    expect(card.findAll('.eng-err__places li').map(li => li.text())).toEqual(['Line 71 of RoundStep'])
    expect(card.find('.eng-err__raw').text()).toContain('at step.groovy:200')
    await card.find('button.eng-err__place').trigger('click')
    expect(routerPush).toHaveBeenCalledWith({ path: '/steps', query: { step: 'RoundStep', line: '71' } })
    // a server that doesn't name the step: "a step", without a link
    store2.admin.output = [{ id: 6, kind: 'error', text, source: 'roundStep', at: Date.now() }]
    await nextTick()
    expect(w.find('.eng-err__place.is-own').text()).toBe('Line 71 of a step')
    expect(w.find('button.eng-err__place').exists()).toBe(false)
    w.unmount()
  })

  it('links to the step in the experiment the engine runs, and names that experiment when it isn\'t the one worked on', async () => {
    const text = 'Caught error: ScriptException: groovy.lang.MissingPropertyException: No such property: two for class: Script12\n  at Script12.groovy:23'
    store2.admin.output = [{ id: 7, kind: 'error', text, source: 'initStep', step: 'InitStep', at: Date.now() }]
    const engine = (experimentId: number, experimentName: string) =>
      ({ experimentId, experimentName, sessionId: 4, sessionName: 'Autopilot · 1', owner: 'me@lab.org', gameStarted: false, stepsChanged: false, autopilotSession: true })
    store2.admin.user = { email: 'me@lab.org', experiments: [], currentScript: '', engine: engine(12, 'Exp') }
    routerPush.mockClear()
    const w = await mountPage()
    try {
      // the experiment worked on (12, "Exp")
      expect(w.find('.live-err__title').text()).toMatch(/^The experiment's scripts reported an error/)
      await w.find('button.eng-err__place').trigger('click')
      expect(routerPush).toHaveBeenLastCalledWith({ path: '/steps', query: { step: 'InitStep', line: '23', experiment: '12' } })
      // the autopilot's, while this console works on another: its InitStep, not this one's
      store2.admin.user = { ...store2.admin.user, engine: engine(13, 'Game A') }
      await nextTick()
      expect(w.find('.live-err__title').text()).toMatch(/^The scripts of “Game A” reported an error/)
      await w.find('button.eng-err__place').trigger('click')
      expect(routerPush).toHaveBeenLastCalledWith({ path: '/steps', query: { step: 'InitStep', line: '23', experiment: '13' } })
    } finally {
      store2.admin.user = null
      w.unmount()
    }
  })

  it('offers "start anyway" when only the session\'s log shows the start', async () => {
    // the autopilot's start (or one that threw after logging its start) seen in the feed
    store2.admin.engineReloadedAt = Date.now() - 60000
    store2.admin.events = [startEvent('InitStepStart', Date.now() - 1000), startEvent('autopilotStart', Date.now() - 1000)]
    const w = await mountPage()
    expect(w.text()).toContain('Game started')
    expect(w.text()).toContain('Start it anyway')
    expect(w.text()).not.toContain('may not be running now')
    w.unmount()
  })

  it('shows no "start anyway" once the engine answered this console\'s start', async () => {
    store2.admin.events = [startEvent('InitStepStart', Date.now() - 1000)]
    store2.admin.output = [{ id: 1, kind: 'output', text: 'initStep.start()\n\n==>null', source: 'RunGame', at: Date.now() }]
    const w = await mountPage()
    expect(w.text()).toContain('Game started')
    expect(w.text()).not.toContain('Start it anyway')
    w.unmount()
  })

  it('offers Start the game again after a start that threw', async () => {
    store2.admin.instance = { ...store2.admin.instance, hasStarted: true }
    store2.admin.events = [startEvent('InitStepStart', Date.now() - 1000)]
    store2.admin.output = [{ id: 1, kind: 'error', text: 'Caught error: Cannot get property \'x\' on null object', source: 'RunGame', at: Date.now() }]
    const w = await mountPage()
    expect(startButton(w)).toBeDefined()
    expect(w.text()).toContain('Game not started')
    expect(w.text()).not.toContain('Game started')
    w.unmount()
  })

  it('keeps "start anyway" when the start may be from before the engine reloaded', async () => {
    store2.admin.engineReloadedAt = Date.now()
    store2.admin.events = [startEvent('InitStepStart', Date.now() - 2000)]
    const w = await mountPage()
    expect(w.text()).toContain('Start it anyway')
    expect(w.text()).toContain('may not be running now')
    w.unmount()
  })

  it('offers Start the game when the engine was reloaded since the game started (the engine says so)', async () => {
    store2.admin.instance = { ...store2.admin.instance, hasStarted: true }
    store2.admin.events = [startEvent('InitStepStart', Date.now() - 600000)]
    store2.admin.user = { engine: { experimentId: 12, experimentName: 'Exp', sessionId: 9, sessionName: 'Live one', owner: 'me@lab.org', gameStarted: false } }
    store2.admin.engineInfoAt = Date.now()
    const w = await mountPage()
    expect(startButton(w)).toBeDefined()
    expect(w.text()).toContain('Game not running')
    expect(w.text()).not.toContain('Game in progress')
    expect(w.find('.live-hero__explain').text()).toContain("closed to newcomers, but its game isn't running in the engine")
    expect(w.find('.live-hero__explain').text()).toContain('the engine was reloaded since it started')
    expect(w.find('.live-hero__explain').text()).not.toContain('When everyone you need is here')
    // the engine says it runs
    store2.admin.user = { engine: { ...store2.admin.user.engine, gameStarted: true } }
    await nextTick()
    expect(startButton(w)).toBeUndefined()
    expect(w.text()).toContain('Game in progress')
    expect(w.text()).not.toContain('Start it anyway')
    // an older server says nothing: the session's flag decides, as before
    store2.admin.user = { engine: { ...store2.admin.user.engine, gameStarted: null } }
    store2.admin.engineInfoAt = 0
    await nextTick()
    expect(w.text()).toContain('Game in progress')
    w.unmount()
    store2.admin.user = null
  })

  it('promises no next session while the autopilot is paused', async () => {
    const { autopilotStatusOf } = await import('../src/pages/session/autopilot')
    const raw = (status: any) => ({ settings: { continuous: true, nextSessionDelaySeconds: 10 }, status: { on: true, experimentId: 12, instance: null, ...status } })
    store2.admin.instance = { id: 9, name: 'Done one', status: 'FINISHED', creationTime: '', data: [], events: [] }
    autopilotHint.status = autopilotStatusOf(raw({}))
    let w = await mountPage()
    // (and the few seconds restarting the scripts takes)
    expect(w.find('.live-hero__explain').text()).toContain('Autopilot opens the next session about 10 seconds after one finishes, plus a few seconds to restart the experiment\'s scripts.')
    w.unmount()
    autopilotHint.status = autopilotStatusOf({ settings: { continuous: true, nextSessionDelaySeconds: 0 }, status: { on: true, experimentId: 12, instance: null } })
    w = await mountPage()
    expect(w.find('.live-hero__explain').text()).toContain('Autopilot opens the next session a few seconds after one finishes, once the experiment\'s scripts have restarted.')
    expect(w.find('.live-hero__explain').text()).not.toContain('right after')
    w.unmount()
    autopilotHint.status = autopilotStatusOf(raw({ paused: true, problemKind: 'paused' }))
    expect(autopilotHint.status.halted).toBe('paused')
    w = await mountPage()
    expect(w.find('.live-hero__explain').text()).toContain('Autopilot is paused: it opens no new session until someone presses Resume on the Autopilot page.')
    expect(w.find('.live-hero__explain').text()).not.toContain('opens the next session')
    w.unmount()
    // its one session finished ("continuous" off, or a newer server that says done)
    expect(autopilotStatusOf(raw({ problemKind: 'done' }))!.halted).toBe('done')
    expect(autopilotStatusOf(raw({ done: true }))!.halted).toBe('done')
    expect(autopilotStatusOf(raw({ problemKind: 'waiting' }))!.halted).toBe(null)
    // what it waits for, as the Autopilot page reads it: another experiment's session, or the engine
    const other = { id: 4, name: 'pilot 3', experimentId: 5, experimentName: 'Ultimatum', owner: 'a@lab.org' }
    expect(autopilotStatusOf(raw({ problemKind: 'waiting', problem: 'Another session is running: "pilot 3" (a@lab.org).', engineSession: other }))!.waiting).toEqual({ session: other })
    expect(autopilotStatusOf(raw({ problem: 'Another session is running: "pilot 3" (a@lab.org).', engineSession: other }))!.waiting).toEqual({ session: other })
    expect(autopilotStatusOf(raw({ problemKind: 'waiting', problem: '"Ultimatum" is loaded in the engine for b@lab.org, who has a console open.' }))!.waiting).toEqual({ session: null })
    expect(autopilotStatusOf(raw({}))!.waiting).toBe(null)
    expect(autopilotStatusOf(raw({ paused: true, problemKind: 'paused', problem: 'It is paused: …', engineSession: other }))!.waiting).toBe(null)
    expect(autopilotStatusOf(raw({ problemKind: 'error', problem: 'There is no admin account to open sessions as.', engineSession: other }))!.waiting).toBe(null)
    expect(autopilotStatusOf({ error: 'x' })).toBe(null)

    // with no session open, the empty page says so too
    const NoSession = (await import('../src/pages/session/NoSession.vue')).default
    const paused = autopilotStatusOf(raw({ paused: true }))
    const none = mount(NoSession, { props: { autopilot: paused }, global: { stubs: { RouterLink: true } } })
    expect(none.text()).toContain('Autopilot runs this experiment but is paused: it opens no new session until someone presses Resume on the Autopilot page.')
    await none.setProps({ autopilot: autopilotStatusOf(raw({})) })
    expect(none.text()).toContain('Autopilot runs this experiment and opens a session by itself, again after each one finishes.')
    none.unmount()
    autopilotHint.status = null
  })

  it('tells when the autopilot opens the next session soon, as the server does for the study links, and what keeps it from it', async () => {
    const { autopilotHold, autopilotOpensNextSoon, autopilotStatusOf } = await import('../src/pages/session/autopilot')
    const raw = (status: any, settings: any = {}) => autopilotStatusOf({ settings: { continuous: true, nextSessionDelaySeconds: 10, ...settings }, status: { on: true, experimentId: 12, instance: null, ...status } })
    const other = { id: 4, name: 'pilot 3', experimentId: 5, experimentName: 'Ultimatum', owner: 'a@lab.org' }
    // nothing in the way (Autopilot.opensNextSoon): on for this experiment, continuous, neither paused nor done, no problem
    expect(autopilotOpensNextSoon(raw({}), 12)).toBe(true)
    expect(autopilotHold(raw({}), 12)).toBe(null)
    expect(raw({ problem: 'There is no admin account to open sessions as.', problemKind: 'error' })!.problem).toBe('There is no admin account to open sessions as.')
    // another experiment's session on the shared engine, or a researcher who has another experiment loaded there
    const waitingForSession = raw({ problemKind: 'waiting', problem: 'Another session is running: "pilot 3" (a@lab.org).', engineSession: other })
    expect(autopilotOpensNextSoon(waitingForSession, 12)).toBe(false)
    expect(autopilotHold(waitingForSession, 12)).toEqual({ by: 'session', session: other })
    const waitingForEngine = raw({ problemKind: 'waiting', problem: '"Ultimatum" is loaded in the engine for b@lab.org, who has a console open.' })
    expect(autopilotOpensNextSoon(waitingForEngine, 12)).toBe(false)
    expect(autopilotHold(waitingForEngine, 12)).toEqual({ by: 'engine' })
    // any other problem the Autopilot page shows: the last session reported an error, the disk is almost full, ...
    for (const problem of ['The last session it opened reported an error: RoundStep failed', 'The disk is almost full (80 MB free where the database is kept)']) {
      const st = raw({ problemKind: 'error', problem })
      expect(autopilotOpensNextSoon(st, 12)).toBe(false)
      expect(autopilotHold(st, 12)).toEqual({ by: 'problem' })
    }
    // it doesn't open this experiment's next session by itself at all: nothing to hold up
    for (const st of [raw({ paused: true, problemKind: 'paused', problem: 'It is paused: …' }), raw({ done: true, problemKind: 'done', problem: 'Its session finished' }), raw({}, { continuous: false }), raw({ on: false })]) {
      expect(autopilotOpensNextSoon(st, 12)).toBe(false)
      expect(autopilotHold(st, 12)).toBe(null)
    }
    expect(autopilotOpensNextSoon(raw({}), 13)).toBe(false)
    expect(autopilotHold(waitingForSession, 13)).toBe(null)
    expect(autopilotOpensNextSoon(null, 12)).toBe(false)
  })

  it('disables Start the game while the engine runs another experiment, and says why', async () => {
    const w = await mountPage()
    expect(startButton(w)!.attributes('disabled')).toBeUndefined()
    store2.engineElsewhere.value = { experimentId: 2, experimentName: 'Other study', sessionId: 8, sessionName: 'S', owner: 'bob@lab.org' }
    await nextTick()
    expect(startButton(w)!.attributes('disabled')).toBeDefined()
    expect(startButton(w)!.attributes('title')).toMatch(/running “Other study” right now/)
    expect(w.find('.live-hero__explain').text()).toMatch(/running “Other study” right now, not this session/)
    w.unmount()
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
    store.ensureConnected.mockClear()
    store.confirmEngineRebuild.mockClear()
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

  it('says the session opens right away, and that its game starts when you or its waiting room start it', async () => {
    await open()
    expect(page().find('.nsd-foot-note').text()).toBe('Opens right away. Its game starts when you, or its waiting room, start it.')
  })

  it('pre-fills the name and defaults, and flags a default that cannot be used', async () => {
    await open()
    expect((page().findAll('input')[0].element as HTMLInputElement).value).toBe('Session 2')
    expect(text()).toContain('This parameter has no default. Enter a value.')
    expect(text()).toContain('How many rounds')
    expect(text()).toContain('1 to 10')
    expect(numberInput().attributes('aria-invalid')).toBe('true')
  })

  it('starts from the last session\'s values, and says so', async () => {
    admin.experiment.instances = [
      { id: 3, name: 'Session 1', status: 'FINISHED', data: [{ name: 'nRounds', value: '2' }] },
      { id: 4, name: 'Session 2', status: 'FINISHED', data: [{ name: 'nRounds', value: '4' }, { name: 'label', value: 'red' }, { name: 'chat', value: 'false' }] },
    ]
    await open()
    expect((numberInput().element as HTMLInputElement).value).toBe('4')
    expect(page().findAll('input').some(i => (i.element as HTMLInputElement).value === 'red')).toBe(true)
    expect(text()).toContain('Filled in as in “Session 2”, the last session.')
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
    const [name, params, experimentId] = store.launch.mock.calls[0]
    expect(name).toBe('Session 2')
    expect(params).toEqual({ nRounds: 4, chat: true, rate: 0.333, label: 'blue' })
    // for the experiment whose parameters the form showed
    expect(experimentId).toBe(12)
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

  it('asks before rebuilding the shared engine, and creates nothing when declined', async () => {
    admin.instance = { id: 9, name: 'Live one', status: 'RUNNING' }
    await open()
    await numberInput().setValue('3')
    store.confirmEngineRebuild.mockResolvedValueOnce(false)
    await submit()
    expect(store.confirmEngineRebuild).toHaveBeenCalledWith({ title: 'Create “Session 2” now?', action: 'Creating a session', confirmLabel: 'Stop it and create' })
    expect(store.launch).not.toHaveBeenCalled()
    store.launch.mockResolvedValue({ user: {} })
    await submit()
    expect(store.launch).toHaveBeenCalledTimes(1)
  })

  it('shows progress while it checks who uses the engine, and creates nothing if closed meanwhile', async () => {
    const w = await open()
    await numberInput().setValue('3')
    let answer!: (v: boolean) => void
    store.confirmEngineRebuild.mockImplementationOnce(() => new Promise<boolean>(r => { answer = r }))
    store.launch.mockResolvedValue({ user: {} })
    await submit()
    const buttons = page().findAll('button')
    const cancel = buttons.find(b => b.text() === 'Cancel')!
    const create = buttons.find(b => b.attributes('type') === 'submit')!
    expect(cancel.attributes('disabled')).toBeDefined()
    expect(create.attributes('aria-busy')).toBe('true')
    // a second submit meanwhile asks nothing more
    await submit()
    expect(store.confirmEngineRebuild).toHaveBeenCalledTimes(1)
    // another experiment is selected meanwhile: the dialog closes, and nothing is created for it
    admin.experiment = { ...admin.experiment, id: 13 }
    await nextTick()
    answer(true)
    await flushPromises()
    expect(store.launch).not.toHaveBeenCalled()
    const opens = w.emitted('update:open') || []
    expect(opens[opens.length - 1]).toEqual([false])
  })

  it('creates nothing when the dialog was closed while the engine was checked', async () => {
    const w = await open()
    await numberInput().setValue('3')
    let answer!: (v: boolean) => void
    store.confirmEngineRebuild.mockImplementationOnce(() => new Promise<boolean>(r => { answer = r }))
    await submit()
    await w.setProps({ open: false })
    answer(true)
    await flushPromises()
    expect(store.launch).not.toHaveBeenCalled()
  })

  it('never launches the parameters for an experiment selected after it opened', async () => {
    const w = await open()
    await numberInput().setValue('3')
    // another experiment is selected (here or in another tab) while the form stays on screen
    admin.experiment = { ...admin.experiment, id: 13, parameters: [] }
    await nextTick()
    store.launch.mockResolvedValue({ user: {} })
    await submit()
    expect(store.confirmEngineRebuild).not.toHaveBeenCalled()
    expect(store.launch).not.toHaveBeenCalled()
    const opens = w.emitted('update:open') || []
    expect(opens[opens.length - 1]).toEqual([false])
  })

  it('sends nothing while offline', async () => {
    await open()
    await numberInput().setValue('3')
    store.ensureConnected.mockReturnValueOnce(false)
    await submit()
    expect(store.confirmEngineRebuild).not.toHaveBeenCalled()
    expect(store.launch).not.toHaveBeenCalled()
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
