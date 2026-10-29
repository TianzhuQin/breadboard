import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

// ---- mocks: the store and the HTTP layer --------------------------------------------------------

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})

const store = vi.hoisted(() => {
  const exps: any[] = []
  return {
    exps,
    running: { value: false },
    actions: {
      selectExperiment: vi.fn(async () => null),
      refresh: vi.fn(async () => null),
      deleteExperiment: vi.fn(async () => null),
      toggleFileMode: vi.fn(async () => null),
    },
  }
})
vi.mock('@/store/admin', async () => {
  const { computed, reactive, ref } = await import('vue')
  const list = reactive(store.exps)
  const running = ref(false)
  Object.defineProperty(store.running, 'value', { get: () => running.value, set: (v) => { running.value = v } })
  const admin = reactive({ experiment: null as any, instance: null as any, email: 'r@lab.org', events: [], user: null as any, requestError: null as any })
  ;(store as any).admin = admin
  // what the engine runs when that is another experiment than the selected one (engineGuard reads it)
  const engineElsewhere = ref<any>(null)
  ;(store as any).engineElsewhere = engineElsewhere
  const graph = { nodes: new Map() }
  ;(store as any).graph = graph
  return {
    admin,
    actions: store.actions,
    experiments: computed(() => list),
    running,
    engineElsewhere,
    ensureConnected: () => true,
    graph,
  }
})

const confirmMock = vi.hoisted(() => vi.fn(async (_req: any) => true))
vi.mock('@/ui/confirm', () => ({ confirm: confirmMock, confirmState: { open: false, req: null, resolve: null }, settleConfirm: () => {} }))

import { createRouter, createMemoryHistory } from 'vue-router'
import ExperimentsPage from '../src/pages/experiments/ExperimentsPage.vue'
import {
  checkExperimentZip, describeError, devDirName, formatWhen, importNameFromFile, importUrl, lastSession, manifestName, uniqueName,
  parseServerTime, readZipEntries, runningSession, uniqueLanguages, validateExperimentName, zipFileName,
} from '../src/pages/experiments/experiments'
import { HttpError } from '../src/lib/http'
import ImportExperimentDialog from '../src/pages/experiments/ImportExperimentDialog.vue'
import NewExperimentDialog from '../src/pages/experiments/NewExperimentDialog.vue'
import { switchToNew } from '../src/pages/experiments/switchTo'
import { toasts } from '../src/ui/toast'

// ---- a tiny zip writer (stored entries only) for the inspection tests ------------------------------

function makeZip (files: { name: string, data?: string }[]): Uint8Array {
  const enc = new TextEncoder()
  const locals: Uint8Array[] = []
  const centrals: Uint8Array[] = []
  let offset = 0
  for (const f of files) {
    const name = enc.encode(f.name)
    const data = enc.encode(f.data ?? '')
    const local = new Uint8Array(30 + name.length + data.length)
    const lv = new DataView(local.buffer)
    lv.setUint32(0, 0x04034b50, true)
    lv.setUint16(8, 0, true) // stored
    lv.setUint32(18, data.length, true)
    lv.setUint32(22, data.length, true)
    lv.setUint16(26, name.length, true)
    local.set(name, 30)
    local.set(data, 30 + name.length)
    const central = new Uint8Array(46 + name.length)
    const cv = new DataView(central.buffer)
    cv.setUint32(0, 0x02014b50, true)
    cv.setUint16(10, 0, true)
    cv.setUint32(20, data.length, true)
    cv.setUint32(24, data.length, true)
    cv.setUint16(28, name.length, true)
    cv.setUint32(42, offset, true)
    central.set(name, 46)
    locals.push(local)
    centrals.push(central)
    offset += local.length
  }
  const cdSize = centrals.reduce((n, c) => n + c.length, 0)
  const eocd = new Uint8Array(22)
  const ev = new DataView(eocd.buffer)
  ev.setUint32(0, 0x06054b50, true)
  ev.setUint16(8, files.length, true)
  ev.setUint16(10, files.length, true)
  ev.setUint32(12, cdSize, true)
  ev.setUint32(16, offset, true)
  const out = new Uint8Array(offset + cdSize + 22)
  let p = 0
  for (const part of [...locals, ...centrals, eocd]) { out.set(part, p); p += part.length }
  return out
}

const EXPORTED = [
  { name: '.breadboard', data: JSON.stringify({ version: 'v2.4.0', experimentName: 'Public goods', experimentUid: 'u' }) },
  { name: 'style.css' },
  { name: 'client-html.html' },
  { name: 'client-graph.js' },
  { name: 'Steps/InitStep.groovy' },
  { name: 'Steps/OnJoinStep.groovy' },
  { name: 'Content/eng/Welcome.html' },
  { name: 'parameters.csv' },
  { name: 'Images/logo.png' },
]

// ---- names --------------------------------------------------------------------------------------

describe('experiment names', () => {
  it('asks for a name', () => {
    expect(validateExperimentName('')).toMatch(/name/)
    expect(validateExperimentName('   ')).toMatch(/name/)
  })
  it('refuses slashes, control characters, dots only and very long names', () => {
    expect(validateExperimentName('a/b')).toMatch(/\//)
    expect(validateExperimentName('a\\b')).toMatch(/\\/)
    expect(validateExperimentName('a\nb')).toMatch(/line breaks/)
    expect(validateExperimentName('..')).toMatch(/letters/)
    expect(validateExperimentName('x'.repeat(101))).toMatch(/100/)
  })
  it('refuses a name already in use, whatever the case and spaces', () => {
    expect(validateExperimentName(' public Goods ', ['Public goods'])).toMatch(/already/)
    expect(validateExperimentName('Public goods 2', ['Public goods'])).toBeNull()
  })
  it('accepts ordinary names, including symbols that are encoded in the URL', () => {
    expect(validateExperimentName('Trust game #3 (pilot) 50% & more?')).toBeNull()
  })
})

describe('import name handling', () => {
  it('suggests the file name without folder and .zip', () => {
    expect(importNameFromFile('Public-goods_12.zip')).toBe('Public-goods_12')
    expect(importNameFromFile('C:\\Users\\me\\Trust game.ZIP')).toBe('Trust game')
    expect(importNameFromFile('archive.zip.zip')).toBe('archive.zip')
    expect(importNameFromFile('no-extension')).toBe('no-extension')
    expect(importNameFromFile('notes.txt')).toBe('notes')
    expect(importNameFromFile('')).toBe('')
    expect(uniqueName('Network game', ['network GAME', 'Network game 2'])).toBe('Network game 3')
    expect(uniqueName('Fresh', ['Other'])).toBe('Fresh')
  })
  it('encodes the name in the import URL (the old console sent it raw)', () => {
    expect(importUrl('Trust game #3?')).toBe('/experiment/import/Trust%20game%20%233%3F')
    expect(importUrl(' 50% done ')).toBe('/experiment/import/50%25%20done')
    expect(importUrl('a/b')).toBe('/experiment/import/a%2Fb')
  })
})

describe('file names', () => {
  it('names an export <Name-with-dashes>_<id>.zip', () => {
    expect(zipFileName({ id: 12, name: 'Public goods  game' })).toBe('Public-goods-game_12.zip')
    expect(zipFileName({ id: 3, name: 'What? A/B: "test"' })).toBe('What-AB-test_3.zip')
    expect(zipFileName({ id: 4, name: '   ' })).toBe('experiment_4.zip')
  })
  it('names the file-mode folder like the server does', () => {
    expect(devDirName({ id: 7, name: 'My study' })).toBe('My-study_7')
  })
})

describe('sessions and dates', () => {
  const exp: any = { instances: [
    { id: 4, name: 'b', status: 'FINISHED', creationTime: '2026-10-20 09:00:00,1' },
    { id: 9, name: 'c', status: 'RUNNING', creationTime: '2026-10-27 14:03:11,25' },
    { id: 2, name: 'a', status: 'STOPPED', creationTime: '2026-01-02 08:00:00,0' },
  ] }
  it('finds the newest and the running session', () => {
    expect(lastSession(exp)!.id).toBe(9)
    expect(runningSession(exp)!.id).toBe(9)
    expect(runningSession({ instances: [] } as any)).toBeNull()
  })
  it('reads the server\'s time format as the server\'s clock time', () => {
    const d = parseServerTime('2026-10-27 14:03:11,25')!
    expect([d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes()]).toEqual([2026, 9, 27, 14, 3])
    expect(parseServerTime('garbage')).toBeNull()
  })
  it('says today and yesterday in words', () => {
    const now = new Date(2026, 9, 27, 18, 0)
    expect(formatWhen(new Date(2026, 9, 27, 14, 3), now)).toMatch(/^Today/)
    expect(formatWhen(new Date(2026, 9, 26, 9, 0), now)).toMatch(/^Yesterday/)
    expect(formatWhen(new Date(2025, 0, 2), now)).toMatch(/2025/)
    expect(formatWhen(null)).toBe('—')
  })
  it('names each language once, by id and then by code', () => {
    const en = { id: 1, code: 'en', name: 'English' }
    expect(uniqueLanguages([en, { ...en }, { id: 3, code: 'EN', name: 'English' }, { id: 2, code: 'de', name: 'German' }]).map(l => l.id))
      .toEqual([1, 2])
    expect(uniqueLanguages([{ id: 4, code: 'fr', name: 'French' }, { id: 5, code: '', name: 'Custom' }, { id: 6, code: '', name: 'Other' }]).map(l => l.id))
      .toEqual([4, 5, 6])
    expect(uniqueLanguages(undefined)).toEqual([])
  })
})

describe('errors in words', () => {
  it('turns HTML error pages into a sentence', () => {
    expect(describeError(new HttpError(500, '<!DOCTYPE html><html><body>NullPointerException</body></html>'))).toMatch(/server ran into an error \(500\)/)
    expect(describeError(new HttpError(413, 'Entity too large'))).toMatch(/too large/)
  })
  it('keeps the server\'s own text', () => {
    expect(describeError(new HttpError(400, 'No Steps or Content directories found. Please upload a valid experiment'))).toMatch(/No Steps or Content/)
  })
  it('explains a network failure', () => {
    expect(describeError(new TypeError('Failed to fetch'))).toMatch(/reach the server/)
  })
})

// ---- zip inspection -----------------------------------------------------------------------------

describe('checking a zip before it is uploaded', () => {
  it('accepts an export and counts what is inside', async () => {
    const bytes = makeZip(EXPORTED)
    expect(readZipEntries(bytes)!.length).toBe(EXPORTED.length)
    const c = checkExperimentZip(bytes)
    expect(c.ok).toBe(true)
    expect(c.root).toBe('')
    expect([c.steps, c.content, c.images]).toEqual([2, 1, 1])
    expect(await manifestName(bytes, c)).toBe('Public goods')
  })

  it('accepts a zip of one folder (as Finder makes), ignoring __MACOSX', () => {
    const c = checkExperimentZip(makeZip([
      ...EXPORTED.map(f => ({ ...f, name: `Public goods/${f.name}` })),
      { name: '__MACOSX/Public goods/._style.css' },
    ]))
    expect(c.ok).toBe(true)
    expect(c.root).toBe('Public goods/')
    expect(c.steps).toBe(2)
  })

  it('accepts an export without a Content folder (an experiment with no content items), as the server does', () => {
    const c = checkExperimentZip(makeZip(EXPORTED.filter(f => !f.name.startsWith('Content/'))))
    expect(c.ok).toBe(true)
    expect(c.problem).toBeUndefined()
    expect(c.root).toBe('')
    expect([c.steps, c.content]).toEqual([2, 0])
  })

  it('accepts a zip whose only folder is Steps or Content, reading it from the top level', () => {
    const steps = checkExperimentZip(makeZip([{ name: 'Steps/A.groovy' }, { name: 'Steps/B.groovy' }]))
    expect(steps.ok).toBe(true)
    expect(steps.root).toBe('')
    expect(steps.steps).toBe(2)
    const content = checkExperimentZip(makeZip([{ name: 'Content/eng/intro.html' }]))
    expect(content.ok).toBe(true)
    expect(content.root).toBe('')
    expect(content.content).toBe(1)
  })

  it('refuses files that are not experiments', () => {
    expect(checkExperimentZip(new TextEncoder().encode('hello, not a zip')).problem).toMatch(/isn't a zip/)
    expect(checkExperimentZip(makeZip([])).problem).toMatch(/empty/)
    expect(checkExperimentZip(makeZip([{ name: 'photos/a.jpg' }, { name: 'notes.txt' }])).problem).toMatch(/needs a Steps or Content folder/)
    expect(checkExperimentZip(makeZip([{ name: 'only/readme.txt' }])).ok).toBe(false)
  })

  it('lets folders in the wrong case through with a warning', () => {
    const c = checkExperimentZip(makeZip([{ name: 'steps/A.groovy' }, { name: 'content/eng/x.html' }]))
    expect(c.ok).toBe(true)
    expect(c.warning).toMatch(/exactly/)
  })
})

// ---- the dialogs ---------------------------------------------------------------------------------

describe('Import dialog', () => {
  beforeEach(() => {
    store.exps.splice(0, store.exps.length, { id: 1, name: 'Existing', instances: [], steps: [], languages: [] })
    http.post.mockReset()
    store.actions.selectExperiment.mockClear()
    store.actions.refresh.mockClear()
    store.running.value = false
    document.body.innerHTML = ''
  })

  async function openWith (file: File) {
    const w = mount(ImportExperimentDialog, { props: { open: false, initialFile: file }, attachTo: document.body })
    await w.setProps({ open: true })
    // FileReader in jsdom is asynchronous
    for (let i = 0; i < 5; i++) { await flushPromises(); await new Promise(r => setTimeout(r, 0)) }
    return w
  }
  const input = () => document.querySelector<HTMLInputElement>('.b-dialog input.b-input__el')!
  const submit = () => document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!

  it('fills the name from the file and posts it encoded, with the zip as "file"', async () => {
    http.post.mockResolvedValue({ id: 42, name: 'Trust game #3' })
    const file = new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'Trust game #3.zip')
    const w = await openWith(file)
    expect(input().value).toBe('Trust game #3')
    submit().click()
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, body] = http.post.mock.calls[0]
    expect(url).toBe('/experiment/import/Trust%20game%20%233')
    expect(body).toBeInstanceOf(FormData)
    expect((body.get('file') as File).name).toBe('Trust game #3.zip')
    // "work on it" is ticked by default
    expect(store.actions.selectExperiment).toHaveBeenCalledWith(42)
    const opened = w.emitted('update:open') || []
    expect(opened[opened.length - 1]).toEqual([false])
    w.unmount()
  })

  it('switches to the import without asking while another account\'s session runs (it keeps running)', async () => {
    http.post.mockResolvedValue({ id: 44, name: 'Shared' })
    ;(store as any).admin.user = { engine: { experimentId: 1, experimentName: 'Existing', sessionId: 41, sessionName: 'live run', owner: 'boss@lab.org' } }
    confirmMock.mockClear()
    try {
      const w = await openWith(new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'Shared.zip'))
      submit().click()
      await flushPromises()
      expect(confirmMock).not.toHaveBeenCalled()
      expect(store.actions.selectExperiment).toHaveBeenCalledWith(44)
      w.unmount()
    } finally {
      ;(store as any).admin.user = null
    }
  })

  it('asks before switching to the import when that would empty a network nobody can account for', async () => {
    http.post.mockResolvedValue({ id: 45, name: 'Unsure' })
    // players in the network, and the server can't say whose (GET /autopilot doesn't answer)
    ;(store as any).graph.nodes.set('p1', { id: 'p1', data: {} })
    confirmMock.mockClear()
    confirmMock.mockResolvedValueOnce(false)
    try {
      const w = await openWith(new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'Unsure.zip'))
      submit().click()
      await flushPromises()
      expect(confirmMock.mock.calls[0][0]).toMatchObject({ title: 'Work on “Unsure” now?', danger: true })
      expect(confirmMock.mock.calls[0][0].message).toContain('1 player is in the network right now')
      expect(store.actions.selectExperiment).not.toHaveBeenCalled()
      // the new experiment still shows up in the list
      expect(store.actions.refresh).toHaveBeenCalled()
      w.unmount()
    } finally {
      ;(store as any).graph.nodes.clear()
    }
  })

  it('uses the name recorded in the zip when there is one', async () => {
    const w = await openWith(new File([makeZip(EXPORTED) as BlobPart], 'Public-goods_12.zip'))
    expect(input().value).toBe('Public goods')
    w.unmount()
  })

  it('does not upload a zip the server would reject (it would leave an empty experiment behind)', async () => {
    const w = await openWith(new File([new TextEncoder().encode('nope') as BlobPart], 'notes.zip'))
    expect(document.body.textContent).toMatch(/isn't a zip archive/)
    submit().click()
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    w.unmount()
  })

  it('suggests a free name, and refuses a duplicate one typed in before uploading', async () => {
    const w = await openWith(new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'existing.zip'))
    const name = document.querySelector<HTMLInputElement>('.b-dialog input[maxlength="120"]')!
    expect(name.value).toMatch(/ 2$/)
    name.value = name.value.replace(/ 2$/, '')
    name.dispatchEvent(new Event('input'))
    await flushPromises()
    submit().click()
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(document.body.textContent).toMatch(/already an experiment called/)
    w.unmount()
  })

  it('shows the server\'s refusal in words and keeps the file for a retry', async () => {
    http.post.mockRejectedValue(new HttpError(400, 'Uploaded file is too large'))
    const w = await openWith(new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'Big study.zip'))
    submit().click()
    await flushPromises()
    expect(document.querySelector('.ximp__error')!.textContent).toMatch(/Uploaded file is too large/)
    expect(document.body.textContent).toMatch(/Big study\.zip/)
    expect(w.emitted('update:open')).toBeUndefined()
    w.unmount()
  })

  it('works on the import even while a session runs (switching leaves it running)', async () => {
    store.running.value = true
    http.post.mockResolvedValue({ id: 43, name: 'Later' })
    const w = await openWith(new File([makeZip(EXPORTED.slice(1)) as BlobPart], 'Later.zip'))
    const tick = document.querySelector<HTMLInputElement>('.b-dialog input[type="checkbox"]')!
    expect(tick.checked).toBe(true)
    expect(tick.disabled).toBe(false)
    submit().click()
    await flushPromises()
    expect(store.actions.selectExperiment).toHaveBeenCalledWith(43)
    w.unmount()
  })
})

describe('New experiment dialog', () => {
  beforeEach(() => {
    store.exps.splice(0, store.exps.length, { id: 5, name: 'Base', instances: [], steps: [], languages: [] })
    http.put.mockReset()
    store.actions.refresh.mockClear()
    store.actions.selectExperiment.mockClear()
    store.running.value = false
    document.body.innerHTML = ''
  })

  it('offers the ready-to-run example first when there is no experiment yet', async () => {
    store.exps.splice(0, store.exps.length)
    http.put.mockResolvedValue({ id: 6, name: 'Network game' })
    const w = mount(NewExperimentDialog, { props: { open: false }, attachTo: document.body })
    await w.setProps({ open: true })
    await flushPromises()
    expect(document.querySelector<HTMLInputElement>('.b-dialog input.b-input__el')!.value).toBe('Network game')
    document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!.click()
    await flushPromises()
    expect(http.put).toHaveBeenCalledWith('/experiment', expect.objectContaining({ newExperimentName: 'Network game', example: 'network-game' }))
    w.unmount()
  })

  it('creates from the template with copyExperimentId -1 and asks for the new list', async () => {
    http.put.mockResolvedValue({ id: 6, name: 'Fresh' })
    const w = mount(NewExperimentDialog, { props: { open: false }, attachTo: document.body })
    await w.setProps({ open: true })
    const name = document.querySelector<HTMLInputElement>('.b-dialog input.b-input__el')!
    name.value = 'Fresh'
    name.dispatchEvent(new Event('input'))
    document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!.click()
    await flushPromises()
    expect(http.put).toHaveBeenCalledWith('/experiment', { newExperimentName: 'Fresh', copyExperimentId: -1 })
    expect(store.actions.refresh).toHaveBeenCalled()
    expect(store.actions.selectExperiment).toHaveBeenCalledWith(6)
    w.unmount()
  })

  it('works on it while another account\'s session runs, and says that session keeps running', async () => {
    http.put.mockResolvedValue({ id: 6, name: 'Fresh' })
    const engine = { experimentId: 5, experimentName: 'Base', sessionId: 40, sessionName: 'their run', owner: 'boss@lab.org' }
    const admin = (store as any).admin
    admin.user = { engine }
    confirmMock.mockClear()
    toasts.splice(0)
    // the server's answer: the new experiment selected, the engine still running the other one
    store.actions.selectExperiment.mockImplementationOnce(async () => {
      admin.experiment = { id: 6, name: 'Fresh', instances: [], steps: [], languages: [] }
      ;(store as any).engineElsewhere.value = engine
      return null
    })
    try {
      const w = mount(NewExperimentDialog, { props: { open: false }, attachTo: document.body })
      await w.setProps({ open: true })
      const tick = document.querySelector<HTMLInputElement>('.b-dialog input[type="checkbox"]')!
      expect(tick.checked).toBe(true)
      expect(tick.disabled).toBe(false)
      const name = document.querySelector<HTMLInputElement>('.b-dialog input.b-input__el')!
      name.value = 'Fresh'
      name.dispatchEvent(new Event('input'))
      document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!.click()
      await flushPromises()
      expect(http.put).toHaveBeenCalledTimes(1)
      expect(confirmMock).not.toHaveBeenCalled()
      expect(store.actions.selectExperiment).toHaveBeenCalledWith(6)
      const said = toasts.find(t => t.title === 'Created “Fresh”')
      expect(said?.detail).toBe('You are now working on it. The engine keeps running “Base” (session “their run”), untouched. Edit this experiment and see its data here; to run it, end that session first.')
      const opened = w.emitted('update:open') || []
      expect(opened[opened.length - 1]).toEqual([false])
      w.unmount()
    } finally {
      admin.user = null
      admin.experiment = null
      ;(store as any).engineElsewhere.value = null
    }
  })

  it('does not give an earlier refusal as the reason the switch to it failed', async () => {
    const admin = (store as any).admin
    admin.requestError = { request: 'SelectExperiment', text: 'An old refusal.', at: Date.now() - 30 * 60000 }
    toasts.splice(0)
    try {
      expect(await switchToNew(6, 'Fresh', 'Created “Fresh”')).toBe(false)
      const t = toasts.find(x => x.title === 'Created “Fresh”')
      expect(t?.detail).toMatch(/the switch to it didn't go through/)
      expect(t?.detail).not.toMatch(/old refusal/)

      store.actions.selectExperiment.mockImplementationOnce(async () => {
        admin.requestError = { request: 'SelectExperiment', text: 'The engine is busy', at: Date.now() }
        return null
      })
      toasts.splice(0)
      await switchToNew(6, 'Fresh', 'Created “Fresh”')
      expect(toasts.find(x => x.title === 'Created “Fresh”')?.detail).toMatch(/didn't go through\. The engine is busy\. /)
    } finally {
      admin.requestError = null
    }
  })

  it('starts as a copy with a free name when asked to duplicate', async () => {
    store.exps.push({ id: 7, name: 'Base copy', instances: [], steps: [], languages: [] })
    http.put.mockResolvedValue({ id: 8, name: 'Base copy 2' })
    const w = mount(NewExperimentDialog, { props: { open: false, copyFrom: 5 }, attachTo: document.body })
    await w.setProps({ open: true })
    expect(document.querySelector<HTMLInputElement>('.b-dialog input.b-input__el')!.value).toBe('Base copy 2')
    document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!.click()
    await flushPromises()
    expect(http.put).toHaveBeenCalledWith('/experiment', { newExperimentName: 'Base copy 2', copyExperimentId: 5 })
    w.unmount()
  })

  it('does not send an empty name', async () => {
    const w = mount(NewExperimentDialog, { props: { open: false }, attachTo: document.body })
    await w.setProps({ open: true })
    document.querySelector<HTMLButtonElement>('.b-dialog__foot button[type="submit"]')!.click()
    await flushPromises()
    expect(http.put).not.toHaveBeenCalled()
    expect(document.body.textContent).toMatch(/Give the experiment a name/)
    w.unmount()
  })
})


describe('Experiments page', () => {
  const A: any = { id: 300, name: 'Alpha', fileMode: false, instances: [{ id: 1, name: 's1', status: 'FINISHED', creationTime: '2026-10-01 10:00:00,0' }], steps: [{}], content: [], parameters: [], languages: [{ code: 'eng', name: 'English' }] }
  const B: any = { id: 301, name: 'Beta', fileMode: true, instances: [], steps: [], content: [], parameters: [], languages: [] }

  function makeRouter () {
    const r = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/experiments', component: ExperimentsPage },
        { path: '/session', component: { template: '<div />' } },
      ],
    })
    return r
  }
  async function mountAt (path: string) {
    const router = makeRouter()
    router.push(path)
    await router.isReady()
    const w = mount({ template: '<RouterView />' }, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return { w, router }
  }

  beforeEach(() => {
    store.exps.splice(0, store.exps.length, A, B)
    ;(store as any).admin.experiment = A
    store.running.value = false
    store.actions.deleteExperiment.mockClear()
    confirmMock.mockClear()
    confirmMock.mockResolvedValue(true)
    document.body.innerHTML = ''
  })

  it('lists every experiment with the current one marked and file mode shown', async () => {
    const { w } = await mountAt('/experiments')
    const cards = w.findAll('.xcard')
    expect(cards.map(c => c.find('.xcard__name').text())).toEqual(['Alpha', 'Beta'])
    expect(cards[0].classes()).toContain('is-current')
    expect(cards[1].text()).toMatch(/File mode/)
    expect(w.find('.xcur__name').text()).toBe('Alpha')
    w.unmount()
  })

  it('shows why the server couldn\'t switch file mode, but not an older refusal', async () => {
    const FileModeDialog = (await import('../src/pages/experiments/FileModeDialog.vue')).default
    const admin = (store as any).admin
    admin.requestError = { request: 'ToggleFileMode', text: 'An old refusal.', at: Date.now() - 60000 }
    store.actions.toggleFileMode.mockImplementationOnce(async () => {
      admin.requestError = { request: 'ToggleFileMode', text: 'Couldn\'t switch file mode: dev/Alpha is not writable', at: Date.now() }
      return null
    })
    const w = mount(FileModeDialog, { props: { open: true }, attachTo: document.body })
    await flushPromises()
    const go = () => [...document.querySelectorAll<HTMLButtonElement>('.b-dialog button')].find(b => /Switch to file mode/.test(b.textContent || ''))!
    go().click()
    await flushPromises()
    expect(document.querySelector('.xfm__error')!.textContent).toBe('Couldn\'t switch file mode: dev/Alpha is not writable')

    // no answer from a newer server (or an older one that only logs it): the old refusal isn't it
    admin.requestError = { request: 'ToggleFileMode', text: 'An old refusal.', at: Date.now() - 60000 }
    store.actions.toggleFileMode.mockImplementationOnce(async () => null)
    go().click()
    await flushPromises()
    expect(document.querySelector('.xfm__error')!.textContent).toMatch(/couldn't write to dev\/Alpha_300\/.*server log/)
    admin.requestError = null
    w.unmount()
  })

  it('opens the New dialog from ?action=new and clears the query', async () => {
    const { w, router } = await mountAt('/experiments?action=new')
    expect(document.querySelector('.b-dialog__title')!.textContent).toBe('New experiment')
    expect(router.currentRoute.value.query.action).toBeUndefined()
    w.unmount()
  })

  it('opens the Import dialog from ?action=import', async () => {
    const { w } = await mountAt('/experiments?action=import')
    expect(document.querySelector('.b-dialog__title')!.textContent).toBe('Import an experiment')
    w.unmount()
  })

  async function deleteFromMenu (w: any, index: number) {
    await w.findAll('.xcard')[index].find('.xcard__more').trigger('click')
    const item = w.findAll('.xcard')[index].findAll('.b-menu__item').find((b: any) => /Delete/.test(b.text()))
    await item.trigger('click')
    await flushPromises()
  }

  it('deletes by id after typing the name to confirm', async () => {
    const { w } = await mountAt('/experiments')
    await deleteFromMenu(w, 1)
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(confirmMock.mock.calls[0][0]).toMatchObject({ typeToConfirm: 'Beta', danger: true })
    expect(store.actions.deleteExperiment).toHaveBeenCalledWith(B)
    w.unmount()
  })

  it('does not delete while a session is running', async () => {
    store.running.value = true
    const { w } = await mountAt('/experiments')
    await deleteFromMenu(w, 1)
    expect(confirmMock).not.toHaveBeenCalled()
    expect(store.actions.deleteExperiment).not.toHaveBeenCalled()
    w.unmount()
  })

  it('does nothing when the researcher cancels', async () => {
    confirmMock.mockResolvedValue(false)
    const { w } = await mountAt('/experiments')
    await deleteFromMenu(w, 0)
    expect(store.actions.deleteExperiment).not.toHaveBeenCalled()
    w.unmount()
  })

  const workOn = (w: any, index: number) => w.findAll('.xcard')[index].findAll('button').find((b: any) => b.text().includes('Work on this'))

  it('switches at once when no session appears to be running', async () => {
    store.actions.selectExperiment.mockClear()
    const { w } = await mountAt('/experiments')
    await workOn(w, 1).trigger('click')
    await flushPromises()
    expect(confirmMock).not.toHaveBeenCalled()
    expect(store.actions.selectExperiment).toHaveBeenCalledWith(301)
    w.unmount()
  })

  it('switches without asking while a session runs on the shared engine, which keeps running', async () => {
    store.actions.selectExperiment.mockClear()
    // another account's session (the engine info in the User JSON says who runs what)
    const engine = { experimentId: 300, experimentName: 'Alpha', sessionId: 2, sessionName: 'lab manager run', owner: 'boss@lab.org' }
    const admin = (store as any).admin
    admin.user = { engine }
    toasts.splice(0)
    store.actions.selectExperiment.mockImplementationOnce(async () => {
      admin.experiment = B
      ;(store as any).engineElsewhere.value = engine
      return null
    })
    try {
      const { w } = await mountAt('/experiments')
      await workOn(w, 1).trigger('click')
      await flushPromises()
      expect(confirmMock).not.toHaveBeenCalled()
      expect(store.actions.selectExperiment).toHaveBeenCalledWith(301)
      const said = toasts.find(t => t.title === 'Now working on “Beta”')
      expect(said?.detail).toBe('The engine keeps running “Alpha” (session “lab manager run”), untouched. Edit this experiment and see its data here; to run it, end that session first.')
      w.unmount()
    } finally {
      admin.user = null
      ;(store as any).engineElsewhere.value = null
    }
  })

  it('switches without asking while this account\'s own session runs (no more “Stop the running session first”)', async () => {
    store.actions.selectExperiment.mockClear()
    store.running.value = true
    ;(store as any).admin.instance = { id: 1, name: 's1', status: 'RUNNING' }
    toasts.splice(0)
    try {
      const { w } = await mountAt('/experiments')
      await workOn(w, 1).trigger('click')
      await flushPromises()
      expect(confirmMock).not.toHaveBeenCalled()
      expect(store.actions.selectExperiment).toHaveBeenCalledWith(301)
      expect(toasts.some(t => /Stop the running session|autopilot is running/.test(t.title))).toBe(false)
      w.unmount()
    } finally {
      ;(store as any).admin.instance = null
    }
  })

  it('asks before a switch that would empty a network nobody can account for', async () => {
    store.actions.selectExperiment.mockClear()
    ;(store as any).graph.nodes.set('p1', { id: 'p1', data: {} })
    confirmMock.mockResolvedValueOnce(false)
    try {
      const { w } = await mountAt('/experiments')
      await workOn(w, 1).trigger('click')
      await flushPromises()
      expect(confirmMock).toHaveBeenCalledTimes(1)
      expect(confirmMock.mock.calls[0][0]).toMatchObject({ danger: true, title: 'Switch to “Beta”?', confirmLabel: 'Switch anyway' })
      expect(confirmMock.mock.calls[0][0].message).toContain('1 player is in the network right now')
      expect(store.actions.selectExperiment).not.toHaveBeenCalled()
      w.unmount()
    } finally {
      ;(store as any).graph.nodes.clear()
    }
  })

  it('gives an earlier refusal as the reason only when it answers this switch', async () => {
    const admin = (store as any).admin
    store.actions.selectExperiment.mockClear()
    admin.requestError = { request: 'SelectExperiment', text: 'An old refusal.', at: Date.now() - 30 * 60000 }
    toasts.splice(0)
    try {
      const { w } = await mountAt('/experiments')
      // no answer in time: the 10:00 refusal is not this switch's reason
      await workOn(w, 1).trigger('click')
      await flushPromises()
      const failed = toasts.find(t => t.title === 'Couldn\'t switch to “Beta”')
      expect(failed?.detail).toMatch(/didn't confirm the change/)

      // a refusal of this very switch is shown as the server said it
      store.actions.selectExperiment.mockImplementationOnce(async () => {
        admin.requestError = { request: 'SelectExperiment', text: 'The engine is busy.', at: Date.now() }
        return null
      })
      toasts.splice(0)
      await workOn(w, 1).trigger('click')
      await flushPromises()
      expect(toasts.find(t => t.title === 'Couldn\'t switch to “Beta”')?.detail).toBe('The engine is busy.')
      w.unmount()
    } finally {
      admin.requestError = null
    }
  })

  it('lists each language once on the cards', async () => {
    store.exps.splice(1, 1, { ...B, languages: [{ id: 1, code: 'en', name: 'English' }, { id: 1, code: 'en', name: 'English' }, { id: 7, code: 'EN', name: 'English' }, { id: 2, code: 'de', name: 'German' }] })
    const { w } = await mountAt('/experiments')
    expect(w.findAll('.xcard')[1].find('.xcard__langs-text').text()).toBe('Languages: English, German')
    w.unmount()
  })

  it('says when the experiment being deleted is the autopilot’s', async () => {
    http.get.mockImplementation(async (url: string) => url === '/autopilot'
      ? { source: 'admin', settings: { enabled: true, experimentId: 301 }, status: { on: true, experimentId: 301 } }
      : null)
    try {
      const { w } = await mountAt('/experiments')
      await deleteFromMenu(w, 1)
      expect(confirmMock.mock.calls[0][0].message).toMatch(/autopilot runs this experiment, so it stops opening sessions/)
      w.unmount()
    } finally {
      http.get.mockReset()
    }
  })
})
