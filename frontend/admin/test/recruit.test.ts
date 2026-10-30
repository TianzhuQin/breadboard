import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { h } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'

// Node's own (file-less) localStorage shadows jsdom's here; use a plain in-memory one, before the
// page modules read it on import.
vi.hoisted(() => {
  const memory = new Map<string, string>()
  ;(globalThis as any).localStorage = {
    getItem: (k: string) => (memory.has(k) ? memory.get(k)! : null),
    setItem: (k: string, v: string) => { memory.set(k, String(v)) },
    removeItem: (k: string) => { memory.delete(k) },
    clear: () => memory.clear(),
  }
})

vi.mock('@/lib/http', async () => {
  const actual = await vi.importActual<typeof import('@/lib/http')>('@/lib/http')
  return {
    ...actual,
    http: { get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() },
    saveText: vi.fn(),
  }
})

vi.mock('@/store/admin', async () => {
  const { reactive, computed, ref, shallowRef } = await import('vue')
  const admin = reactive<{ experiment: any, instance: any, ready: boolean }>({ experiment: null, instance: null, ready: true })
  return {
    admin,
    connection: { send: vi.fn() },
    connectionState: ref('open'),
    nextMessage: vi.fn(async () => null),
    NOT_CONNECTED: 'Not connected to the server',
    actions: { refresh: vi.fn(() => Promise.resolve(null)), selectExperiment: vi.fn() },
    experiments: computed(() => (admin.experiment ? [admin.experiment] : [])),
    instances: computed(() => (admin.experiment?.instances || [])),
    graph: { nodes: new Map(), version: shallowRef(0) },
  }
})

import { http, HttpError } from '@/lib/http'
import { admin as realAdmin, connection, connectionState, nextMessage } from '@/store/admin'
import {
  assignmentsCsv, bonusFromAnswers, bonusTotal, buildRequirement, csvCell, describeError, describeRequirement, hitCounts, mapPool,
  MASTERS_ID, newRequirement, parseAnnotation, parseAnswers, parseIntegerList, parseMoney, parseWorkerIds, toCsv, validateRequirement,
  MAX_AUTOSTART_SECONDS,
} from '@/pages/recruit/mturk/lib'
import { FORM_KEY, defaultHitForm, hitFormBody, loadHitForm, readSchedules, validateHitForm } from '@/pages/recruit/mturk/hitForm'
import { bonusToken, newRequestToken, TOKEN_RE } from '@/pages/recruit/mturk/bonusTokens'
import { toasts } from '@/ui/toast'
import { toRow } from '@/pages/recruit/mturk/rows'
import { completionSnippet, groupBySession, prolificBonusLines, prolificPlayers, prolificRecordsOf, prolificStudyUrl, prolificTarget, groovyString } from '@/pages/recruit/prolific/prolific'
import BonusDialog from '@/pages/recruit/mturk/BonusDialog.vue'
import CreateHitTab from '@/pages/recruit/mturk/CreateHitTab.vue'
import RecruitPage from '@/pages/recruit/RecruitPage.vue'
import HitDetail from '@/pages/recruit/mturk/HitDetail.vue'
import AutopilotCard from '@/pages/recruit/prolific/AutopilotCard.vue'
import { moneyState, settleMoney } from '@/pages/recruit/mturk/moneyConfirm'
import { activeJobs, track } from '@/pages/recruit/mturk/jobs'
import { confirmSignOut, unsavedTotal } from '@/shell/unsaved'
import { confirmState, settleConfirm } from '@/ui/confirm'

// the mocked store holds partial objects
const admin = realAdmin as any

const NS = 'http://mechanicalturk.amazonaws.com/AWSMechanicalTurkDataSchemas/2005-10-01/QuestionFormAnswers.xsd'
function answersXml (pairs: [string, string][]) {
  const body = pairs.map(([k, v]) => `<Answer><QuestionIdentifier>${k}</QuestionIdentifier><FreeText>${v}</FreeText></Answer>`).join('')
  return `<?xml version="1.0" encoding="ASCII"?><QuestionFormAnswers xmlns="${NS}">${body}</QuestionFormAnswers>`
}

// ---- answers XML ------------------------------------------------------------------------------

describe('parseAnswers', () => {
  it('reads every answer in order, with entities decoded', () => {
    const p = parseAnswers(answersXml([['bonus', '0.35'], ['reason', 'Final score'], ['comments', 'Fun &amp; quick &lt;3']]))
    expect(p.error).toBeNull()
    expect(p.keys).toEqual(['bonus', 'reason', 'comments'])
    expect(p.values.comments).toBe('Fun & quick <3')
    expect(bonusFromAnswers(p.values)).toBe(0.35)
  })

  it('understands selections and uploads instead of throwing (the old parser needed FreeText)', () => {
    const xml = `<QuestionFormAnswers xmlns="${NS}">
      <Answer><QuestionIdentifier>colour</QuestionIdentifier><SelectionIdentifier>red</SelectionIdentifier><SelectionIdentifier>blue</SelectionIdentifier></Answer>
      <Answer><QuestionIdentifier>file</QuestionIdentifier><UploadedFileSizeInBytes>12</UploadedFileSizeInBytes><UploadedFileKey>abc</UploadedFileKey></Answer>
    </QuestionFormAnswers>`
    const p = parseAnswers(xml)
    expect(p.values).toEqual({ colour: 'red, blue', file: 'file: abc' })
  })

  it('keeps both values of a field submitted twice', () => {
    const p = parseAnswers(answersXml([['pick', 'a'], ['pick', 'b']]))
    expect(p.keys).toEqual(['pick'])
    expect(p.values.pick).toBe('a, b')
  })

  it('reports broken XML on the row instead of failing the page', () => {
    const p = parseAnswers('<QuestionFormAnswers><Answer>')
    expect(p.error).toMatch(/not valid XML/)
    expect(p.keys).toEqual([])
    expect(toRow({ assignmentId: 'A1', workerId: 'W1', approvalTime: null, rejectionTime: null, answer: '<x', assignmentCompleted: false }).answers.error).toBeTruthy()
  })

  it('treats a missing answer as no answers', () => {
    expect(parseAnswers(null)).toEqual({ values: {}, keys: [], error: null })
    expect(parseAnswers('  ')).toEqual({ values: {}, keys: [], error: null })
  })
})

// ---- money ------------------------------------------------------------------------------------

describe('money', () => {
  it('adds bonuses up in cents', () => {
    expect(bonusTotal([0.1, 0.2])).toBe(0.3)
    expect(bonusTotal([0.335, 0.335])).toBe(0.68)
    expect(bonusTotal([1.5, null, undefined, NaN, -2, 0])).toBe(1.5)
    expect(bonusTotal([])).toBe(0)
  })

  it('parses typed amounts and answer bonuses, rounding to cents', () => {
    expect(parseMoney('1.5')).toBe(1.5)
    expect(parseMoney('$2.00')).toBe(2)
    expect(parseMoney('0.3333')).toBe(0.33)
    expect(parseMoney('.5')).toBe(0.5)
    expect(parseMoney('0')).toBeNull()
    expect(parseMoney('0.004')).toBeNull()
    expect(parseMoney('-1')).toBeNull()
    expect(parseMoney('1,50')).toBeNull()
    expect(parseMoney('abc')).toBeNull()
    expect(bonusFromAnswers({ bonus: 'NaN' })).toBeNull()
    expect(bonusFromAnswers({})).toBeNull()
  })

  it('pre-fills the bonus to pay from the answer', () => {
    const r = toRow({ assignmentId: 'A1', workerId: 'W1', approvalTime: null, rejectionTime: null, answer: answersXml([['bonus', '0.3333']]), assignmentCompleted: true })
    expect(r.bonusAsked).toBe(0.33)
    expect(r.bonusInput).toBe('0.33')
    expect(r.completed).toBe(true)
  })
})

// ---- CSV --------------------------------------------------------------------------------------

describe('CSV', () => {
  it('quotes every cell and doubles quotes', () => {
    expect(csvCell('say "hi", ok')).toBe('"say ""hi"", ok"')
    expect(csvCell('line 1\nline 2')).toBe('"line 1\nline 2"')
    expect(csvCell(null)).toBe('""')
    expect(csvCell(12)).toBe('"12"')
    expect(toCsv([['a', 'b'], ['c,d', 'e']])).toBe('"a","b"\r\n"c,d","e"\r\n')
  })

  it('writes values exactly as recorded, like the server\'s data file (no apostrophe, no byte-order mark)', () => {
    expect(csvCell('=HYPERLINK("x")')).toBe('"=HYPERLINK(""x"")"')
    expect(csvCell('@anna yes')).toBe('"@anna yes"')
    expect(csvCell('-')).toBe('"-"')
    expect(csvCell('-0.5')).toBe('"-0.5"')
    expect(csvCell('+3')).toBe('"+3"')
    expect(csvCell('\tindented')).toBe('"\tindented"')
    const csv = assignmentsCsv({ hitId: '=H1', title: '@t' }, [
      { assignmentId: 'A1', workerId: 'W1', status: 'approved', approvalTime: null, rejectionTime: null, bonusPaid: null, completed: true, answers: { a: '-1+2' }, answerKeys: ['a'] },
    ])
    expect(csv.charCodeAt(0)).toBe('"'.charCodeAt(0))
    expect(csv).not.toContain('\uFEFF')
    expect(csv.split('\r\n')[1]).toMatch(/^"=H1","@t",/)
    expect(csv.split('\r\n')[1]).toMatch(/,"-1\+2"$/)
  })

  it('escapes the HIT title and every field, not only answers', () => {
    const csv = assignmentsCsv(
      { hitId: 'H1', title: 'Game "A", round 2', creationTime: Date.UTC(2026, 9, 27, 14, 5) },
      [
        { assignmentId: 'A1', workerId: 'W1', status: 'approved', approvalTime: '2026-10-27T15:00Z', rejectionTime: null, bonusPaid: 0.5, completed: true, answers: { bonus: '0.5', comments: 'nice, "fun"' }, answerKeys: ['bonus', 'comments'] },
        { assignmentId: 'A2', workerId: 'W2', status: 'submitted', approvalTime: null, rejectionTime: null, bonusPaid: null, completed: false, answers: { workerId: 'spoof' }, answerKeys: ['workerId'] },
      ],
    )
    const lines = csv.trimEnd().split('\r\n')
    expect(lines[0]).toBe('"hitId","hitTitle","creationTime","assignmentId","workerId","approvalTime","rejectionTime","bonusGranted","status","completed","bonus","comments","answer_workerId"')
    expect(lines[1]).toBe('"H1","Game ""A"", round 2","2026-10-27T14:05:00.000Z","A1","W1","2026-10-27T15:00Z","","0.50","approved","true","0.5","nice, ""fun""",""')
    expect(lines[2]).toBe('"H1","Game ""A"", round 2","2026-10-27T14:05:00.000Z","A2","W2","","","","submitted","false","","","spoof"')
  })
})

// ---- qualification requirements --------------------------------------------------------------

describe('qualification requirements', () => {
  const WIRE_KEYS = ['actionsGuarded', 'comparator', 'integerValues', 'locales', 'otherExperiment', 'selectedQualificationType']

  it('accepts integer values (the old field could never validate)', () => {
    expect(parseIntegerList('1000')).toEqual({ values: [1000], error: null })
    expect(parseIntegerList(' 1, 2 ,3 ')).toEqual({ values: [1, 2, 3], error: null })
    expect(parseIntegerList('95%').error).toMatch(/whole number/)
    expect(parseIntegerList('').error).toBeTruthy()
  })

  it('builds "approval rate at least 95%" exactly as the server reads it', () => {
    const d = newRequirement('approvedPercent')
    const w = buildRequirement(d, { sandbox: true })
    expect(Object.keys(w).sort()).toEqual(WIRE_KEYS)
    expect(Object.keys(w.selectedQualificationType).sort()).toEqual(['label', 'qualificationTypeId'])
    expect(w).toMatchObject({ comparator: 'GreaterThanOrEqualTo', integerValues: '95', locales: [], otherExperiment: '', actionsGuarded: 'Accept' })
    expect(w.selectedQualificationType.qualificationTypeId).toBe('000000000000000000L0')
    expect(describeRequirement(d)).toBe('Approval rate at least 95%')
  })

  it('normalises several values for "one of"', () => {
    const d = { ...newRequirement('custom'), customTypeId: 'ABCDEF123', comparator: 'In' as const, integerValues: '1, 2 3' }
    expect(buildRequirement(d, { sandbox: false }).integerValues).toBe('1,2,3')
    expect(validateRequirement({ ...d, comparator: 'GreaterThan' }).integerValues).toMatch(/one number/)
  })

  it('checks ranges', () => {
    expect(validateRequirement({ ...newRequirement('approvedPercent'), integerValues: '101' }).integerValues).toMatch(/100 or less/)
    expect(validateRequirement({ ...newRequirement('approvedCount'), integerValues: '-1' }).integerValues).toMatch(/0 or more/)
    expect(() => buildRequirement({ ...newRequirement('approvedCount'), integerValues: 'lots' }, { sandbox: true })).toThrow(/whole number/)
  })

  it('sends no values for Exists / DoesNotExist and picks the Masters id of the mode', () => {
    const d = newRequirement('masters')
    expect(buildRequirement(d, { sandbox: true })).toMatchObject({ comparator: 'Exists', integerValues: '', selectedQualificationType: { qualificationTypeId: MASTERS_ID.sandbox } })
    expect(buildRequirement(d, { sandbox: false }).selectedQualificationType.qualificationTypeId).toBe(MASTERS_ID.production)
    expect(buildRequirement(newRequirement('adult'), { sandbox: true }).integerValues).toBe('1')
  })

  it('always sends locale subdivisions as strings (the server trims them)', () => {
    const d = { ...newRequirement('locale'), locales: [{ country: 'us', subdivision: ' ca ' }, { country: 'gb', subdivision: '' }] }
    const w = buildRequirement(d, { sandbox: true })
    expect(w.locales).toEqual([{ country: 'US', subdivision: 'CA' }, { country: 'GB', subdivision: '' }])
    expect(w.integerValues).toBe('')
    expect(describeRequirement(d)).toBe('Located in US-CA, GB')
    expect(validateRequirement({ ...d, locales: [{ country: 'USA', subdivision: '' }] }).locales).toMatch(/two-letter/)
    expect(validateRequirement({ ...d, locales: [] }).locales).toBeTruthy()
  })

  it('needs the looked-up type for an experiment requirement', () => {
    const d = { ...newRequirement('experiment'), experimentUid: 'uid-1', experimentName: 'Trust game' }
    expect(validateRequirement({ ...d, experimentUid: '' }).experimentUid).toBeTruthy()
    expect(() => buildRequirement(d, { sandbox: true })).toThrow(/could not be found/)
    const w = buildRequirement(d, { sandbox: true, experimentTypeId: 'Q123' })
    expect(w).toMatchObject({ comparator: 'DoesNotExist', integerValues: '', actionsGuarded: 'DiscoverPreviewAndAccept', otherExperiment: '' })
    expect(w.selectedQualificationType).toEqual({ label: 'Trust game participant', qualificationTypeId: 'Q123' })
    expect(describeRequirement(d)).toBe('Never took part in “Trust game”')
  })
})

// ---- other helpers ------------------------------------------------------------------------------

describe('HIT helpers', () => {
  it('reads the experiment from the annotation, even when the name broke the JSON', () => {
    expect(parseAnnotation('{"experimentType":"breadboard","experimentUid":"u1","experimentName":"Game"}')).toEqual({ breadboard: true, experimentUid: 'u1', experimentName: 'Game' })
    expect(parseAnnotation('{"experimentType":"breadboard","experimentUid":"u2","experimentName":"The "best" game"}').experimentUid).toBe('u2')
    expect(parseAnnotation(null)).toEqual({ breadboard: false, experimentUid: null, experimentName: null })
    expect(parseAnnotation('payment for W1').breadboard).toBe(false)
  })

  it('counts submissions to review apart from workers still busy', () => {
    expect(hitCounts({ maxAssignments: 20, numberOfAssignmentsAvailable: 5, numberOfAssignmentsPending: 3, numberOfAssignmentsCompleted: 10 }))
      .toEqual({ requested: 20, remaining: 5, inProgress: 3, reviewed: 10, toReview: 2 })
  })

  it('cleans up pasted worker ids', () => {
    const r = parseWorkerIds('A1B2C3D4E5F6G7\n a1b2c3d4e5f6g7 , "A9Z8Y7X6W5V4"\nWorkerId\n\n')
    expect(r.ids).toEqual(['A1B2C3D4E5F6G7', 'A9Z8Y7X6W5V4'])
    expect(r.duplicates).toEqual(['A1B2C3D4E5F6G7'])
    expect(r.invalid).toEqual(['WorkerId'])
  })

  it('words errors for people', () => {
    expect(describeError(new HttpError(400, 'No AWS keys provided', 'No AWS keys provided')).noKeys).toBe(true)
    const aws = describeError(new HttpError(400, 'x', 'The HIT is not reviewable. (Service: AmazonMTurk; Status Code: 400; Error Code: RequestError; Request ID: 1)'))
    expect(aws.message).toBe('The HIT is not reviewable.')
    expect(aws.detail).toMatch(/^Service: AmazonMTurk/)
    expect(describeError(new HttpError(500, 'x', '<!DOCTYPE html><html>boom</html>')).message).toMatch(/unexpected error \(HTTP 500\)/)
    expect(describeError(new TypeError('Failed to fetch')).message).toMatch(/reach the server/)
  })

  it('runs at most N at a time and keeps results in order', async () => {
    let active = 0
    let peak = 0
    const res = await mapPool([1, 2, 3, 4, 5], 2, async (n) => {
      active++
      peak = Math.max(peak, active)
      await new Promise(r => setTimeout(r, 5))
      active--
      if (n === 3) throw new Error('three')
      return n * 10
    })
    expect(peak).toBe(2)
    expect(res.map(r => r.status)).toEqual(['fulfilled', 'fulfilled', 'rejected', 'fulfilled', 'fulfilled'])
    expect((res[4] as PromiseFulfilledResult<number>).value).toBe(50)
  })
})

describe('Create HIT form', () => {
  beforeEach(() => localStorage.clear())

  it('asks for what is missing and checks MTurk limits', () => {
    const e = validateHitForm(defaultHitForm())
    expect(Object.keys(e)).toEqual(['title', 'description', 'keywords'])
    const f = { ...defaultHitForm(), title: 'T', description: 'D', keywords: 'k', reward: '0', lifetime: 10, assignmentDuration: 1.5 }
    const e2 = validateHitForm(f)
    expect(e2.reward).toBeTruthy()
    expect(e2.lifetime).toMatch(/at least 30 s/)
    expect(e2.assignmentDuration).toMatch(/whole seconds/)
  })

  it('refuses an automatic start the server cannot schedule (32-bit overflow)', () => {
    const f = { ...defaultHitForm(), title: 'T', description: 'D', keywords: 'k', lifetime: MAX_AUTOSTART_SECONDS, tutorialTime: 10 }
    expect(validateHitForm(f).autoLaunch).toBeTruthy()
    expect(validateHitForm({ ...f, autoLaunch: false }).autoLaunch).toBeUndefined()
  })

  it('builds the request with strings where the server wants them and requirements last', () => {
    const f = { ...defaultHitForm(), title: ' T ', description: 'D', keywords: 'k', reward: '1.5' }
    const body = hitFormBody(f, { experimentId: 3, instanceId: 7, requirements: [] })
    const keys = Object.keys(body)
    expect(keys[keys.length - 1]).toBe('qualificationRequirements')
    expect(body).toMatchObject({ title: 'T', reward: '1.50', experimentId: '3', experimentInstanceId: '7', hitLifetime: 300, tutorialTime: 300, assignmentDuration: 5400, maxAssignments: 20, disallowPrevious: 'type' })
  })

  it('picks up the old console’s remembered settings the first time', () => {
    localStorage.setItem('createHitTitle', 'Old title')
    localStorage.setItem('createHitLifetime', '600')
    localStorage.setItem('createHitAutoLaunch', 'false')
    const f = loadHitForm()
    expect(f).toMatchObject({ title: 'Old title', lifetime: 600, autoLaunch: false, tutorialTime: 300 })
  })
})

describe('Prolific helpers', () => {
  it('builds the study URL with Prolific’s placeholders', () => {
    expect(prolificStudyUrl('https://bb.example.org', 12)).toBe('https://bb.example.org/play/12/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}')
  })

  it('points at the session the engine runs while its game hasn’t started, as the server does', () => {
    const s = (id: number, status: string, hasStarted = false) => ({ id, status, hasStarted, name: `s${id}` })
    const list = [s(9, 'RUNNING', true), s(5, 'RUNNING'), s(7, 'RUNNING'), s(8, 'FINISHED')]
    expect(prolificTarget(list, 5)).toEqual({ open: s(5, 'RUNNING'), started: null })
    // its game has started: nothing is open, and that is why
    expect(prolificTarget(list, 9)).toEqual({ open: null, started: s(9, 'RUNNING', true) })
    // sessions left RUNNING that the engine doesn't run (e.g. after a restart) take no one
    expect(prolificTarget(list, null)).toEqual({ open: null, started: null })
    expect(prolificTarget(list, 8)).toEqual({ open: null, started: null })
    expect(prolificTarget(list, 42)).toEqual({ open: null, started: null })
    // engine info not known yet: the newest running session stands in for it
    expect(prolificTarget(list)).toEqual({ open: null, started: s(9, 'RUNNING', true) })
    expect(prolificTarget([s(5, 'RUNNING'), s(7, 'RUNNING')])).toEqual({ open: s(7, 'RUNNING'), started: null })
    expect(prolificTarget([])).toEqual({ open: null, started: null })
  })

  it('says the study link leads nowhere while the only running session’s game has started', async () => {
    const StudyLinkCard = (await import('@/pages/recruit/prolific/StudyLinkCard.vue')).default
    const exp = { id: 12, name: 'Game', instances: [] }
    const started = { id: 9, name: 'Friday pilot', status: 'RUNNING', hasStarted: true }
    const stubs = { RouterLink: { template: '<a><slot /></a>' } }
    const w = mount(StudyLinkCard, { props: { experiment: exp as any, target: null, started: started as any }, global: { stubs } })
    expect(w.find('.rc-note--success').exists()).toBe(false)
    expect(w.find('.rc-note--warning').text()).toMatch(/game in Friday pilot has already started/)
    expect(w.find('.rc-note--warning').text()).toMatch(/No session is open right now/)
    await w.setProps({ target: { id: 10, name: 'Saturday', status: 'RUNNING', hasStarted: false } as any })
    expect(w.find('.rc-note--success').text()).toMatch(/leads to Saturday/)
  })

  it('says, while the autopilot opens the next session by itself, that the link holds newcomers until it opens', async () => {
    const StudyLinkCard = (await import('@/pages/recruit/prolific/StudyLinkCard.vue')).default
    const exp = { id: 12, name: 'Game', instances: [] }
    const started = { id: 9, name: 'Autopilot 3', status: 'RUNNING', hasStarted: true }
    const stubs = { RouterLink: { template: '<a><slot /></a>' } }
    const w = mount(StudyLinkCard, { props: { experiment: exp as any, target: null, started: started as any, autopilot: true }, global: { stubs } })
    const note = () => w.find('.rc-note--info').text().replace(/\s+/g, ' ')
    expect(w.find('.rc-note--warning').exists()).toBe(false)
    expect(note()).toBe('The game in Autopilot 3 has started. The autopilot opens the next session when it ends; until then, the link shows a page that waits for that session and takes newcomers in as soon as it opens.')
    expect(w.text()).not.toContain('No session is open right now')
    // between two of its sessions
    await w.setProps({ started: null })
    expect(note()).toMatch(/^The autopilot opens the next session in a moment; until then, the link shows a page that waits for it/)
    // a session open to newcomers
    await w.setProps({ target: { id: 10, name: 'Autopilot 4', status: 'RUNNING', hasStarted: false } as any })
    expect(w.find('.rc-note--success').text().replace(/\s+/g, ' ')).toContain('Once its game has started, newcomers wait on a page that takes them into the autopilot’s next session as soon as it opens.')
    w.unmount()
  })

  it('lists Prolific players and their bonuses', () => {
    const nodes = [
      { id: 'p1', data: { _system: { recruitment: { source: 'prolific', prolificPid: 'PID-B', completed: true, completionCode: 'C1', bonus: 1.255 } } } },
      { id: 'p2', data: { _system: { connected: false, recruitment: { source: 'prolific', prolificPid: 'PID-A' } } } },
      { id: 'p3', data: { _system: { recruitment: { source: 'mturk' } } } },
      { id: '_ai', data: {} },
    ]
    const players = prolificPlayers(nodes)
    expect(players.map(p => p.prolificPid)).toEqual(['PID-A', 'PID-B'])
    expect(players[0].connected).toBe(false)
    expect(prolificBonusLines(players)).toBe('PID-B,1.26')
  })

  it('gives code that completes Prolific participants only, and says to use the step\'s own name for the participant', () => {
    expect(completionSnippet(' C7K2M9QX ')).toBe([
      '// p is the participant: use the name your step gives them (often player, or p in g.V.each { p -> ... })',
      'if (p._system?.recruitment?.source == "prolific") {',
      '  recruitment.completeProlific(p, [',
      "    completionCode: 'C7K2M9QX',",
      "    message: 'Thank you for taking part!'",
      '  ])',
      '}',
    ].join('\n'))
    expect(completionSnippet('')).toContain("completionCode: 'YOUR-CODE',")
  })

  it('says an experiment with a prolificCode parameter gives the code by itself, and offers no code to paste', async () => {
    const CompletionCard = (await import('@/pages/recruit/prolific/CompletionCard.vue')).default
    const router = makeRouter()
    const param = (defaultVal: string) => ({ id: 1, name: 'prolificCode', type: 'Text' as const, minVal: '', maxVal: '', defaultVal, description: '' })
    let w = mount(CompletionCard, { props: { experimentId: 3, parameters: [param('C7K2M9QX')] }, global: { plugins: [router] } })
    const text = () => w.text().replace(/\s+/g, ' ')
    expect(text()).toContain('On the Parameters page, make that code the default of prolificCode. This experiment shows it to Prolific participants by itself when they finish: there is no code to add to its steps.')
    expect(w.find('.pl-code-now').text()).toBe('Now: C7K2M9QX')
    expect(w.find('pre.rc-code').exists()).toBe(false)
    expect(w.findAll('button').some(b => b.text() === 'Copy code')).toBe(false)
    await w.findAll('button').find(b => b.text() === 'Parameters')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/parameters')
    w.unmount()
    // not set yet: said, as Prolific participants would get no code
    w = mount(CompletionCard, { props: { experimentId: 3, parameters: [param('')] }, global: { plugins: [router] } })
    expect(w.find('.pl-code-now').text()).toBe('Empty for now, so Prolific participants finish without a code.')
    w.unmount()
    // without one: the guarded code to copy
    w = mount(CompletionCard, { props: { experimentId: 3, parameters: [] }, global: { plugins: [router] } })
    expect(w.find('pre.rc-code').text()).toContain('if (p._system?.recruitment?.source == "prolific") {')
    expect(w.findAll('button').some(b => b.text() === 'Copy code')).toBe(true)
    w.unmount()
  })

  it('writes completion codes as safe Groovy strings', () => {
    expect(groovyString("it's")).toBe("'it\\'s'")
    // a line break or a backslash must not end or bend the literal
    expect(groovyString('a\nb\rc\\d')).toBe("'a\\nb\\rc\\\\d'")
    expect(groovyString('${x}')).toBe("'${x}'")
  })
})

// ---- components -------------------------------------------------------------------------------

function makeRouter () {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/recruit', component: RecruitPage },
      { path: '/:p(.*)*', component: { render: () => h('div') } },
    ],
  })
}

describe('Recruitment page', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(http.get).mockReset()
    vi.mocked(http.post).mockReset()
    admin.experiment = { id: 3, uid: 'uid-3', name: 'Trust game', instances: [{ id: 7, name: 's1', status: 'RUNNING', hits: [] }] }
    admin.instance = { id: 7, name: 's1', status: 'RUNNING', hits: [] }
  })

  it('makes no Mechanical Turk calls until its tab is opened, then explains missing AWS keys', async () => {
    vi.mocked(http.get).mockImplementation(async (url: string) => {
      if (url.startsWith('/amtadmin/')) throw new HttpError(400, 'No AWS keys provided', 'No AWS keys provided')
      throw new HttpError(404, 'Not found')
    })
    const router = makeRouter()
    router.push('/recruit')
    await router.isReady()
    const w = mount({ render: () => h(RouterView) }, { global: { plugins: [router], stubs: { teleport: true } }, attachTo: document.body })
    await flushPromises()
    expect(w.text()).toContain('/play/3/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}')
    expect(vi.mocked(http.get).mock.calls.some(([u]) => String(u).startsWith('/amtadmin/'))).toBe(false)

    const mturkTab = w.findAll('[role="tab"]').find(t => t.text().includes('Mechanical Turk'))!
    await mturkTab.trigger('click')
    // the Mechanical Turk code is loaded with the tab
    await vi.dynamicImportSettled()
    await flushPromises()
    expect(vi.mocked(http.get)).toHaveBeenCalledWith('/amtadmin/getAccountBalance?sandbox=true')
    expect(w.text()).toContain('Connect Breadboard to Mechanical Turk')
    expect(w.text()).toContain('AMT_ACCESS_KEY')
    // the keys go in the server's own settings file, not in conf/application-prod.conf, which each
    // release replaces and anyone can read; a new release comes without conf/local.conf, so the note
    // says to copy it over
    expect(w.text()).toContain('conf/local.conf')
    expect(w.text()).toContain('copy conf/local.conf into each new release when you upgrade')
    expect(w.text()).not.toContain('application-prod.conf')
    w.unmount()
  })
})

describe('Recruitment page with the autopilot', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(http.get).mockReset()
    admin.experiment = { id: 3, uid: 'uid-3', name: 'Trust game', parameters: [], instances: [{ id: 7, name: 'Autopilot 7', status: 'RUNNING', hasStarted: true, hits: [] }] }
    admin.instance = { id: 7, name: 'Autopilot 7', status: 'RUNNING', hasStarted: true, hits: [] }
  })

  async function page (autopilot: any) {
    vi.mocked(http.get).mockImplementation(async (url: string) => {
      if (url === '/autopilot') return autopilot
      throw new HttpError(404, 'Not found')
    })
    const router = makeRouter()
    router.push('/recruit')
    await router.isReady()
    const w = mount({ render: () => h(RouterView) }, { global: { plugins: [router], stubs: { teleport: true } }, attachTo: document.body })
    await flushPromises()
    return w
  }

  it('says the autopilot opens the next session when this game ends, and that late arrivals wait for it', async () => {
    const w = await page({ settings: { continuous: true, nextSessionDelaySeconds: 10 }, status: { on: true, experimentId: 3, experimentName: 'Trust game', instance: { id: 7, name: 'Autopilot 7', status: 'RUNNING' } } })
    const text = () => w.text().replace(/\s+/g, ' ')
    expect(text()).toContain('The game in Autopilot 7 has started. The autopilot opens the next session when it ends')
    expect(text()).toContain('Late arrivals wait for the next session.')
    expect(text()).not.toContain('let Autopilot keep one open')
    w.unmount()
  })

  it('says the link leads nowhere while the autopilot is paused', async () => {
    const w = await page({ settings: { continuous: true }, status: { on: true, paused: true, problemKind: 'paused', experimentId: 3, instance: { id: 7, name: 'Autopilot 7', status: 'RUNNING' } } })
    const text = () => w.text().replace(/\s+/g, ' ')
    expect(text()).toContain('has already started, so right now the link shows “No session is open right now”')
    expect(text()).toContain('Late arrivals are turned away politely.')
    w.unmount()
  })
})

describe('Mechanical Turk requests under way', () => {
  it('make signing out ask first, as requests still running rather than unsaved changes', async () => {
    let finish!: () => void
    const job = track(() => new Promise<void>(resolve => { finish = resolve }))
    expect(activeJobs.value).toBe(1)
    // not an unsaved change...
    expect(unsavedTotal()).toBe(0)
    // ...but signing out asks, and says what is still running
    const answer = confirmSignOut()
    expect(confirmState.open).toBe(true)
    expect(confirmState.req!.title).toBe('Sign out while requests are still running?')
    expect(confirmState.req!.message).toContain('Still running: Mechanical Turk requests (1).')
    settleConfirm(false)
    expect(await answer).toBe(false)
    finish()
    await job
    expect(activeJobs.value).toBe(0)
    expect(await confirmSignOut()).toBe(true)
  })
})

describe('Bonus dialog', () => {
  it('shows the total of the amounts reported in the answers and lets them be edited', async () => {
    const rows = [
      toRow({ assignmentId: 'A1', workerId: 'W1', approvalTime: null, rejectionTime: null, answer: answersXml([['bonus', '0.30']]), assignmentCompleted: false }),
      toRow({ assignmentId: 'A2', workerId: 'W2', approvalTime: null, rejectionTime: null, answer: answersXml([['bonus', '0.50']]), assignmentCompleted: false }),
      toRow({ assignmentId: 'A3', workerId: 'W3', approvalTime: null, rejectionTime: null, answer: null, assignmentCompleted: false }),
    ]
    const w = mount(BonusDialog, { props: { open: true, rows }, global: { stubs: { teleport: true } } })
    await flushPromises()
    expect(w.text()).toContain('Paying 2 workers')
    expect(w.text()).toContain('$0.80')

    const inputs = w.findAll('input')
    await inputs[2].setValue('1.25')
    expect(w.text()).toContain('$2.05')
    await w.find('form').trigger('submit')
    const [items, reason] = w.emitted('pay')![0] as [{ row: { id: string }, amount: number }[], string]
    expect(items.map(i => [i.row.id, i.amount])).toEqual([['A1', 0.3], ['A2', 0.5], ['A3', 1.25]])
    expect(reason).toBe('Final game score.')
  })
})

describe('Prolific participants of every session', () => {
  const answer = {
    participants: [
      { sessionId: 12, sessionName: 'Autopilot 2', sessionStatus: 'RUNNING', player: 'pp-c', prolificPid: 'PID-C', studyId: 's', prolificSessionId: 'x', joinedAt: '2026-10-30T13:27:11.000Z', completed: true, completedAt: '2026-10-30T13:29:00.000Z', completionCode: 'C7K2M9QX', bonus: 0.5, finishedWithoutCode: false },
      { sessionId: 12, sessionName: 'Autopilot 2', sessionStatus: 'RUNNING', player: 'pp-d', prolificPid: 'PID-D', studyId: 's', prolificSessionId: 'y', joinedAt: '2026-10-30T13:27:20.000Z', completed: false, completedAt: null, completionCode: null, bonus: null, finishedWithoutCode: false },
      { sessionId: 11, sessionName: 'Autopilot 1', sessionStatus: 'FINISHED', player: 'pp-a', prolificPid: 'PID-A', studyId: 's', prolificSessionId: 'z', joinedAt: '2026-10-30T13:20:47.000Z', completed: true, completedAt: '2026-10-30T13:23:00.000Z', completionCode: 'C7K2M9QX', bonus: 1.255, finishedWithoutCode: false },
      { sessionId: 11, sessionName: 'Autopilot 1', sessionStatus: 'FINISHED', player: 'pp-b', prolificPid: 'PID-B', studyId: 's', prolificSessionId: 'w', joinedAt: '2026-10-30T13:20:50.000Z', completed: false, completedAt: null, completionCode: null, bonus: null, finishedWithoutCode: true },
      { sessionId: 11, sessionName: 'Autopilot 1', sessionStatus: 'FINISHED', player: 'pp-e', prolificPid: 'PID-E', studyId: 's', prolificSessionId: 'v', joinedAt: '2026-10-30T13:21:00.000Z', completed: false, completedAt: null, completionCode: null, bonus: null, finishedWithoutCode: false },
      // anything else is left out
      null, { sessionId: 9 },
    ],
  }

  beforeEach(() => {
    vi.mocked(http.get).mockReset()
    admin.experiment = { id: 3, name: 'Network game', parameters: [], instances: [] }
    admin.instance = null
  })

  it('reads the server\'s list, groups it by session, and adds up one participant\'s bonuses for Prolific', () => {
    const records = prolificRecordsOf(answer)
    expect(records.map(r => r.player)).toEqual(['pp-c', 'pp-d', 'pp-a', 'pp-b', 'pp-e'])
    expect(records[1]).toMatchObject({ completed: false, completionCode: '', bonus: null, finishedWithoutCode: false })
    expect(groupBySession(records).map(g => [g.sessionName, g.joined, g.finished])).toEqual([['Autopilot 2', 2, 1], ['Autopilot 1', 3, 2]])
    expect(prolificBonusLines(records)).toBe('PID-C,0.50\nPID-A,1.26')
    // the same person paid in two sessions: one line, the amounts added up
    expect(prolificBonusLines([{ prolificPid: 'PID-A', bonus: 1.25 }, { prolificPid: 'PID-B', bonus: null }, { prolificPid: 'PID-A', bonus: 0.5 }, { prolificPid: '', bonus: 2 }])).toBe('PID-A,1.75')
    expect(prolificRecordsOf(null)).toEqual([])
  })

  it('lists who joined and who finished in every session, with codes, bonuses and Copy bonus list', async () => {
    vi.mocked(http.get).mockImplementation(async (url: string) => {
      if (url === '/experiment/3/prolific') return answer
      throw new HttpError(404, 'Not found')
    })
    const ProlificHistory = (await import('@/pages/recruit/prolific/ProlificHistory.vue')).default
    const written: string[] = []
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (t: string) => { written.push(t) } }, configurable: true })
    const w = mount(ProlificHistory, { props: { experimentId: 3 } })
    await flushPromises()
    const text = () => w.text().replace(/\s+/g, ' ')
    expect(text()).toContain('5 joined · 3 finished · in 2 sessions')
    expect(w.findAll('.pl-hist__session').map(r => r.text().replace(/\s+/g, ' '))).toEqual([
      'Autopilot 2 Running 2 joined · 1 finished',
      'Autopilot 1 Finished 3 joined · 2 finished',
    ])
    const rows = w.findAll('tbody tr:not(.pl-hist__session)').map(r => r.findAll('td').map(td => td.text().replace(/\s+/g, ' ')))
    expect(rows.map(r => [r[0], r[2], r[3], r[4]])).toEqual([
      ['PID-C Player pp-c', 'Finished', 'C7K2M9QX', '0.50'],
      ['PID-D Player pp-d', 'Not yet', '', ''],
      ['PID-A Player pp-a', 'Finished', 'C7K2M9QX', '1.26'],
      ['PID-B Player pp-b', 'No code', '', ''],
      ['PID-E Player pp-e', 'No', '', ''],
    ])
    await w.findAll('button').find(b => b.text() === 'Copy bonus list')!.trigger('click')
    await flushPromises()
    expect(written).toEqual(['PID-C,0.50\nPID-A,1.26'])
    w.unmount()
  })

  it('says when nobody came from Prolific yet, and when the list can\'t be loaded', async () => {
    const ProlificHistory = (await import('@/pages/recruit/prolific/ProlificHistory.vue')).default
    vi.mocked(http.get).mockResolvedValueOnce({ participants: [] })
    let w = mount(ProlificHistory, { props: { experimentId: 3 } })
    await flushPromises()
    expect(w.text()).toContain('Nobody has joined this experiment from Prolific yet.')
    expect(w.find('table').exists()).toBe(false)
    w.unmount()
    vi.mocked(http.get).mockRejectedValueOnce(new HttpError(500, 'Database unavailable'))
    w = mount(ProlificHistory, { props: { experimentId: 3 } })
    await flushPromises()
    expect(w.text()).toContain('Couldn\'t load the Prolific participants')
    vi.mocked(http.get).mockResolvedValueOnce(answer)
    await w.findAll('button').find(b => b.text().includes('Try again'))!.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('5 joined · 3 finished')
    w.unmount()
  })
})

describe('Prolific autopilot card', () => {
  it('says when the autopilot of this experiment is paused', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ source: 'admin', status: { on: true, experimentId: 3, experimentName: 'Trust game', paused: true } })
    const w = mount(AutopilotCard, { props: { experimentId: 3 }, global: { plugins: [makeRouter()] } })
    await flushPromises()
    expect(w.text()).toContain('Paused for this experiment')
    vi.mocked(http.get).mockResolvedValueOnce({ source: 'admin', status: { on: true, experimentId: 3, experimentName: 'Trust game' } })
    const w2 = mount(AutopilotCard, { props: { experimentId: 3 }, global: { plugins: [makeRouter()] } })
    await flushPromises()
    expect(w2.text()).toContain('On for this experiment')
  })
  it('says when its one session has finished, so the Prolific link leads nowhere', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ source: 'config', status: { on: true, experimentId: 3, experimentName: 'Trust game', done: true } })
    const w = mount(AutopilotCard, { props: { experimentId: 3 }, global: { plugins: [makeRouter()] } })
    await flushPromises()
    expect(w.text()).toContain('Done: it opens no more sessions')
    expect(w.text()).not.toContain('On for this experiment')
  })
})

describe('Create HIT', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(connection.send).mockReset()
    vi.mocked(connection.send).mockReturnValue(true)
    vi.mocked(nextMessage).mockReset()
    vi.mocked(nextMessage).mockResolvedValue({ output: 'AMT HIT created, initStep will automatically start in 10.0 minutes and 0.0 seconds.' })
    ;(connectionState as any).value = 'open'
    toasts.splice(0, toasts.length)
    admin.experiment = { id: 3, uid: 'uid-3', name: 'Trust game', instances: [] }
    admin.instance = { id: 7, name: 's1', status: 'RUNNING', hits: [] }
  })

  it('posts the HIT, then asks the server to start the game by itself', async () => {
    localStorage.setItem(FORM_KEY, JSON.stringify({ title: 'Play a game', description: 'A short game', keywords: 'game' }))
    const created = { id: 1, hitId: 'HIT1', title: 'Play a game', creationTime: '2026-10-27 14:00:00,0', description: 'A short game', lifetimeInSeconds: '300', tutorialTime: '300', maxAssignments: '20', externalURL: 'https://bb.example.org/game/3/7/amt', reward: '1.00', disallowPrevious: 'type', sandbox: true }
    const api = { mode: 'sandbox', sandbox: true, createHit: vi.fn(async (_body: unknown) => created), experimentTypeId: vi.fn() }
    const router = makeRouter()
    const w = mount(CreateHitTab, { props: { api: api as any }, global: { plugins: [router], stubs: { teleport: true } } })
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(api.createHit).toHaveBeenCalledTimes(1)
    const body = api.createHit.mock.calls[0][0] as Record<string, unknown>
    expect(body).toMatchObject({ title: 'Play a game', experimentId: '3', experimentInstanceId: '7', reward: '1.00', qualificationRequirements: [] })
    expect(connection.send).toHaveBeenCalledWith('SubmitAMTTask', { lifetimeInSeconds: 300, tutorialTime: 300 })
    expect(w.text()).toContain('HIT1')
    expect(readSchedules().HIT1).toBeTruthy()
  })

  async function postWithAutoStart () {
    localStorage.setItem(FORM_KEY, JSON.stringify({ title: 'Play a game', description: 'A short game', keywords: 'game' }))
    const created = { id: 1, hitId: 'HIT2', title: 'Play a game', creationTime: '2026-10-27 14:00:00,0', description: 'A short game', lifetimeInSeconds: '300', tutorialTime: '300', maxAssignments: '20', externalURL: 'https://bb.example.org/game/3/7/amt', reward: '1.00', disallowPrevious: 'type', sandbox: true }
    const api = { mode: 'sandbox', sandbox: true, createHit: vi.fn(async (_body: unknown) => created), experimentTypeId: vi.fn() }
    const w = mount(CreateHitTab, { props: { api: api as any }, global: { plugins: [makeRouter()], stubs: { teleport: true } } })
    await flushPromises()
    await w.find('form').trigger('submit')
    await flushPromises()
    return { w, api }
  }

  it('says the game won\'t start by itself when the connection dropped after posting', async () => {
    vi.mocked(connection.send).mockReturnValue(false)
    const { api } = await postWithAutoStart()
    expect(api.createHit).toHaveBeenCalledTimes(1)
    expect(readSchedules().HIT2).toBeUndefined()
    const warning = toasts.find(t => t.kind === 'warning')!
    expect(warning.title).toBe('The game won’t start by itself')
    expect(warning.detail).toMatch(/Not connected to the server/)
    expect(warning.timeout).toBe(0)
  })

  it('says the game won\'t start by itself when the server refuses the automatic start', async () => {
    vi.mocked(nextMessage).mockResolvedValue({ error: 'Another researcher\'s experiment is loaded.', request: 'SubmitAMTTask' })
    await postWithAutoStart()
    expect(readSchedules().HIT2).toBeUndefined()
    expect(toasts.find(t => t.kind === 'warning')!.detail).toMatch(/Another researcher's experiment is loaded\./)
  })

  it('does not post a HIT that should start by itself while disconnected', async () => {
    ;(connectionState as any).value = 'retrying'
    const { w, api } = await postWithAutoStart()
    expect(api.createHit).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/Not connected to the server/)
  })

  it('shows what is missing instead of posting', async () => {
    const api = { mode: 'sandbox', sandbox: true, createHit: vi.fn(), experimentTypeId: vi.fn() }
    const w = mount(CreateHitTab, { props: { api: api as any }, global: { plugins: [makeRouter()], stubs: { teleport: true } }, attachTo: document.body })
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(api.createHit).not.toHaveBeenCalled()
    expect(w.text()).toContain('Give the HIT a title')
    expect(connection.send).not.toHaveBeenCalled()
    w.unmount()
  })

  it('asks for a running session first', async () => {
    admin.instance = { id: 7, name: 's1', status: 'STOPPED', hits: [] }
    const api = { mode: 'sandbox', sandbox: true, createHit: vi.fn(), experimentTypeId: vi.fn() }
    const w = mount(CreateHitTab, { props: { api: api as any }, global: { plugins: [makeRouter()], stubs: { teleport: true } } })
    expect(w.text()).toContain('Start a session first')
    expect(w.find('form').exists()).toBe(false)
  })
})

describe('HIT submissions', () => {
  const assignments = [
    { assignmentId: 'A1', workerId: 'W1AAAAAAAAAA', approvalTime: null, rejectionTime: null, answer: answersXml([['bonus', '0.30']]), assignmentCompleted: false },
    { assignmentId: 'A2', workerId: 'W2AAAAAAAAAA', approvalTime: '2026-10-27T13:05Z', rejectionTime: null, answer: answersXml([['bonus', '1.20']]), assignmentCompleted: true },
    { assignmentId: 'A3', workerId: 'W3AAAAAAAAAA', approvalTime: null, rejectionTime: null, answer: null, assignmentCompleted: false },
  ]
  function fakeApi () {
    return {
      mode: 'sandbox',
      sandbox: true,
      listAssignments: vi.fn(async () => ({ assignments, nextToken: null })),
      listBonusPayments: vi.fn(async () => ({ bonusPayments: [{ workerId: 'W2AAAAAAAAAA', bonusAmount: '1.20', assignmentId: 'A2', reason: 'r', grantTime: 0 }], nextToken: null })),
      experimentTypeId: vi.fn(async () => 'Q1'),
      qualificationScore: vi.fn(async (w: string) => ({ status: w === 'W2AAAAAAAAAA' ? 'Granted' : 'None' })),
      approve: vi.fn(async () => null),
    }
  }

  it('never creates a qualification type for a HIT not posted from Breadboard', async () => {
    const api = fakeApi()
    const w = mount(HitDetail, {
      props: { hit: { hitid: 'H1', title: 'Payment', reward: '1.00', maxAssignments: 1 }, annotation: { breadboard: false, experimentUid: null, experimentName: null }, api: api as any },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    expect(api.experimentTypeId).not.toHaveBeenCalled()
    expect(api.qualificationScore).not.toHaveBeenCalled()
    expect(w.text()).toContain('Not posted from Breadboard')
  })

  it('approves only after the money confirmation, and only work still to review', async () => {
    const api = fakeApi()
    const w = mount(HitDetail, {
      props: { hit: { hitid: 'H1', title: 'Game', reward: '1.00', maxAssignments: 20 }, annotation: { breadboard: true, experimentUid: 'u1', experimentName: 'Game' }, api: api as any },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    expect(api.experimentTypeId).toHaveBeenCalledWith('u1')
    expect(w.text()).toContain('$1.20 paid')

    await w.find('thead input[type="checkbox"]').setValue(true)
    const approve = w.findAll('button').find(b => b.text().startsWith('Approve'))!
    expect(approve.text()).toBe('Approve 2')
    await approve.trigger('click')
    await flushPromises()
    expect(moneyState.open).toBe(true)
    expect(moneyState.req?.total).toBe(2)
    expect(api.approve).not.toHaveBeenCalled()

    settleMoney(true)
    await flushPromises()
    expect(api.approve.mock.calls.map(c => (c as unknown[])[0])).toEqual(['A1', 'A3'])
    expect(w.emitted('reviewed')![0]).toEqual([2])
  })

  function deferred () {
    let resolve!: () => void
    let reject!: (e: unknown) => void
    const promise = new Promise<null>((res, rej) => { resolve = () => res(null); reject = rej })
    return { promise, resolve, reject }
  }
  const buttonStarting = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').find(b => b.text().startsWith(label))!

  it('keeps every row of a bonus run out of the buttons until the run and the payments reload are over', async () => {
    const many = ['A1', 'A2', 'A3', 'A4'].map((id, i) => ({
      assignmentId: id, workerId: `W${i}AAAAAAAAAA`, approvalTime: null, rejectionTime: null, answer: answersXml([['bonus', '0.50']]), assignmentCompleted: false,
    }))
    const calls: Record<string, ReturnType<typeof deferred>> = {}
    const api = {
      ...fakeApi(),
      listAssignments: vi.fn(async () => ({ assignments: many, nextToken: null })),
      listBonusPayments: vi.fn(async () => ({ bonusPayments: [], nextToken: null })),
      sendBonus: vi.fn((id: string) => { calls[id] = deferred(); return calls[id].promise }),
    }
    const w = mount(HitDetail, {
      props: { hit: { hitid: 'H1', title: 'Game', reward: '1.00', maxAssignments: 20 }, annotation: { breadboard: false, experimentUid: null, experimentName: null }, api: api as any },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    await w.find('thead input[type="checkbox"]').setValue(true)
    expect(buttonStarting(w, 'Bonus').text()).toBe('Bonus · $2.00…')

    const dialog = w.findComponent(BonusDialog)
    const items = (dialog.props('rows') as any[]).map(row => ({ row, amount: 0.5 }))
    dialog.vm.$emit('pay', items, 'Score')
    await flushPromises()
    // two at a time: the other two wait, and nothing can be paid, approved or rejected meanwhile
    expect(api.sendBonus.mock.calls.map(c => c[0])).toEqual(['A1', 'A2'])
    expect(w.text().match(/Waiting…/g)?.length).toBe(2)
    expect(buttonStarting(w, 'Bonus').attributes('disabled')).toBeDefined()
    expect(buttonStarting(w, 'Bonus').text()).toBe('Bonus…')
    expect(buttonStarting(w, 'Approve').attributes('disabled')).toBeDefined()
    expect(buttonStarting(w, 'Reject').attributes('disabled')).toBeDefined()
    expect(dialog.props('rows')).toEqual([])

    // a second click on the same rows is ignored
    dialog.vm.$emit('pay', items, 'Score')
    await flushPromises()
    expect(api.sendBonus).toHaveBeenCalledTimes(2)

    calls.A1.resolve(); calls.A2.resolve()
    await flushPromises()
    calls.A3.resolve(); calls.A4.resolve()
    await flushPromises()
    expect(api.sendBonus).toHaveBeenCalledTimes(4)
    expect(api.listBonusPayments).toHaveBeenCalledTimes(2)
    expect(w.text()).not.toContain('Waiting…')
    // every paid row left the selection
    expect(w.find('.hd__count').text()).toBe('4 submissions')
  })

  it('treats a lost or 5xx bonus answer as maybe paid, and a refusal as not paid', async () => {
    const api = {
      ...fakeApi(),
      sendBonus: vi.fn(async (id: string) => {
        if (id === 'A1') throw new HttpError(504, 'Gateway Timeout', '<html><body>504</body></html>')
        throw new HttpError(400, 'Insufficient funds (Service: AmazonMTurk; Status Code: 400)')
      }),
    }
    const w = mount(HitDetail, {
      props: { hit: { hitid: 'H1', title: 'Game', reward: '1.00', maxAssignments: 20 }, annotation: { breadboard: false, experimentUid: null, experimentName: null }, api: api as any },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    await w.find('thead input[type="checkbox"]').setValue(true)
    const dialog = w.findComponent(BonusDialog)
    expect((dialog.props('rows') as any[]).map(r => r.id)).toEqual(['A1', 'A3'])
    dialog.vm.$emit('pay', (dialog.props('rows') as any[]).map(row => ({ row, amount: 0.5 })), 'Score')
    await flushPromises()

    expect(w.text()).toContain('may or may not have been paid')
    expect(w.text()).toContain('Insufficient funds')
    // A3 was refused outright and can be tried again; A1 may have been paid, so it is left out
    expect((dialog.props('rows') as any[]).map(r => r.id)).toEqual(['A3'])
    expect(buttonStarting(w, 'Bonus').attributes('title')).toMatch(/Leaves out 1 worker who may already have a bonus/)

    // once the researcher has checked, they can allow it again
    await buttonStarting(w, 'Not paid?').trigger('click')
    expect((dialog.props('rows') as any[]).map(r => r.id)).toEqual(['A1', 'A3'])
  })

  it('sends each payment with its own request token, reuses it on a retry, and takes a repeat as already paid', async () => {
    localStorage.clear()
    let lost = true
    const api = {
      ...fakeApi(),
      sendBonus: vi.fn(async (id: string) => {
        if (id === 'A1' && lost) { lost = false; throw new HttpError(504, 'Gateway Timeout', '<html><body>504</body></html>') }
        return id === 'A1' ? { sent: true, duplicate: true } : { sent: true }
      }),
    }
    const w = mount(HitDetail, {
      props: { hit: { hitid: 'H1', title: 'Game', reward: '1.00', maxAssignments: 20 }, annotation: { breadboard: false, experimentUid: null, experimentName: null }, api: api as any },
      global: { stubs: { teleport: true } },
    })
    await flushPromises()
    await w.find('thead input[type="checkbox"]').setValue(true)
    const dialog = w.findComponent(BonusDialog)
    dialog.vm.$emit('pay', (dialog.props('rows') as any[]).map(row => ({ row, amount: 0.5 })), 'Score')
    await flushPromises()
    const [a1, a3] = api.sendBonus.mock.calls.map(c => (c as unknown[])[4] as string)
    expect(a1).toMatch(TOKEN_RE)
    expect(a3).toMatch(TOKEN_RE)
    expect(a1).not.toBe(a3)
    expect(w.text()).toContain('may or may not have been paid')

    // the retry carries the same token, and MTurk's "already paid" counts as paid
    await buttonStarting(w, 'Not paid?').trigger('click')
    await w.find('thead input[type="checkbox"]').setValue(true)
    const retry = (dialog.props('rows') as any[]).find(r => r.id === 'A1')
    expect(retry).toBeTruthy()
    dialog.vm.$emit('pay', [{ row: retry, amount: 0.75 }], 'Score')
    await flushPromises()
    expect(api.sendBonus).toHaveBeenCalledTimes(3)
    expect((api.sendBonus.mock.calls[2] as unknown[])[4]).toBe(a1)
    expect(w.text()).not.toContain('may or may not have been paid')
    expect(w.findAll('tbody tr').find(tr => tr.text().includes('A1'))!.text()).not.toContain('Not paid?')
    // and a reload of the page keeps it
    expect(bonusToken('A1')).toBe(a1)
  })

  it('makes request tokens MTurk accepts, even without randomUUID', () => {
    expect(newRequestToken()).toMatch(TOKEN_RE)
    const real = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', { value: { getRandomValues: real.getRandomValues.bind(real) }, configurable: true })
    try {
      expect(newRequestToken()).toMatch(TOKEN_RE)
      Object.defineProperty(globalThis, 'crypto', { value: undefined, configurable: true })
      expect(newRequestToken()).toMatch(TOKEN_RE)
    } finally {
      Object.defineProperty(globalThis, 'crypto', { value: real, configurable: true })
    }
  })
})

describe('Approving an assignment', () => {
  it('is a POST, so a link on another site cannot approve (and pay) one', async () => {
    const { mturkApi } = await import('@/pages/recruit/mturk/api')
    vi.mocked(http.get).mockReset()
    vi.mocked(http.post).mockReset()
    vi.mocked(http.post).mockResolvedValue(undefined)
    await mturkApi('sandbox').approve('A 1')
    expect(vi.mocked(http.post)).toHaveBeenCalledWith('/amtadmin/approveAssignment/A%201?sandbox=true')
    expect(vi.mocked(http.get)).not.toHaveBeenCalled()
  })
})
