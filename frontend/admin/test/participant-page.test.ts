import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canSave, isCustomClientGraph, isDirty, isLegacyPage, javaHashCode, newPart, readPart, serverDiffers,
} from '../src/pages/participant-page/participantPage'
import { DEFAULT_CLIENT_GRAPH, DEFAULT_CLIENT_HTML, isNewParticipantPage } from '../src/pages/participant-page/templates'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', () => ({
  http,
  HttpError: class HttpError extends Error {
    constructor (public status: number, message: string, public body?: any) { super(message) }
  },
}))
const store = vi.hoisted(() => ({ admin: null as any, refresh: vi.fn() }))
vi.mock('@/store/admin', async () => {
  const { reactive } = await import('vue')
  store.admin = reactive({ email: 'r@lab.org', experiment: null, instance: null })
  return { admin: store.admin, actions: { refresh: store.refresh } }
})
const leave = vi.hoisted(() => ({ guard: null as null | (() => any) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
  onBeforeRouteLeave: (fn: () => any) => { leave.guard = fn },
}))

const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
const conf = (f: string) => readFileSync(join(repo, 'conf', 'defaults', f), 'utf8')

describe('templates', () => {
  it('match the server’s defaults for new experiments', () => {
    expect(DEFAULT_CLIENT_HTML).toBe(conf('default-client-html.html'))
    expect(DEFAULT_CLIENT_GRAPH).toBe(conf('default-client-graph.js'))
  })
  // A release has no conf/defaults folder (the file is inside a jar), so the comment named a file
  // nobody could open.
  it('point to the previous default page where a researcher can open it', () => {
    expect(DEFAULT_CLIENT_HTML).not.toMatch(/see conf\/defaults/)
    expect(DEFAULT_CLIENT_HTML).toContain('https://github.com/TianzhuQin/breadboard/blob/main/conf/defaults/legacy-client-html.html')
  })
  it('know the new page whatever its comments say, as experiments made before 3.1 have it', () => {
    const before31 = DEFAULT_CLIENT_HTML.replace(/ {6}The previous default page[\s\S]*?legacy-client-html\.html\n/,
      '      The previous default page (SVGGraph) still works: see conf/defaults/legacy-client-html.html.\n')
    expect(before31).not.toBe(DEFAULT_CLIENT_HTML)
    expect(isNewParticipantPage(before31)).toBe(true)
    expect(isNewParticipantPage(DEFAULT_CLIENT_HTML.replace(/\n/g, '\r\n'))).toBe(true)
    expect(isNewParticipantPage(DEFAULT_CLIENT_HTML.replace('<BBGame', '<BBGame layout="force"'))).toBe(false)
    expect(isNewParticipantPage(conf('legacy-client-html.html'))).toBe(false)
  })
  it('tell the old layout from the new one', () => {
    expect(isLegacyPage(conf('legacy-client-html.html'))).toBe(true)
    expect(isLegacyPage(DEFAULT_CLIENT_HTML)).toBe(false)
    expect(isCustomClientGraph(DEFAULT_CLIENT_GRAPH)).toBe(false)
    expect(isCustomClientGraph('function Graph(clientId, parentElement) {}')).toBe(true)
  })
})

describe('javaHashCode (clientHtmlHash / clientGraphHash)', () => {
  it('matches Java’s String.hashCode()', () => {
    expect(javaHashCode('')).toBe(0)
    expect(javaHashCode('hello')).toBe(99162322)
    expect(javaHashCode('Hello, World!')).toBe(1498789909)
    expect(javaHashCode('\u{1F600}')).toBe(1772899)
    expect(javaHashCode('<v-app>\n'.repeat(50))).toBe(894062100)
  })
})

describe('part state', () => {
  it('reads the text out of a GET answer', () => {
    expect(readPart('style', { style: 'a{}' })).toBe('a{}')
    expect(readPart('style', { style: null })).toBe('')
    expect(readPart('clientHtml', { style: 'x' })).toBeNull()
    expect(readPart('clientHtml', 'Invalid Experiment ID')).toBeNull()
  })
  it('never lets a part whose load failed be saved', () => {
    const p = { ...newPart(), status: 'error' as const, value: '' }
    expect(canSave(p)).toBe(false)
    expect(isDirty(p)).toBe(false)
  })
  it('does not count line endings as an edit', () => {
    const p = { ...newPart(), status: 'ready' as const, original: 'a\r\nb', value: 'a\nb' }
    expect(isDirty(p)).toBe(false)
    expect(isDirty({ ...p, value: 'a\nc' })).toBe(true)
  })
  it('tells a change made elsewhere from our own save', () => {
    const p = { ...newPart(), status: 'ready' as const, original: 'one', value: 'two' }
    expect(serverDiffers('clientHtml', p, javaHashCode('one'))).toBe(false)
    expect(serverDiffers('clientHtml', p, javaHashCode('three'))).toBe(true)
    expect(serverDiffers('clientHtml', { ...p, pending: 'two' }, javaHashCode('two'))).toBe(false)
    expect(serverDiffers('style', p, 'one')).toBe(false)
    expect(serverDiffers('style', p, 'body{}')).toBe(true)
    expect(serverDiffers('style', p, null)).toBe(false)
  })
})

// a textarea stands in for CodeMirror
const EditorStub = defineComponent({
  name: 'BCodeEditor',
  props: { modelValue: String, readonly: Boolean, onSave: Function, onRun: Function },
  emits: ['update:modelValue'],
  setup (props, { emit }) {
    return () => h('textarea', {
      class: 'stub-editor',
      value: props.modelValue,
      readonly: props.readonly,
      onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value),
    })
  },
})

describe('ParticipantPagePage', () => {
  beforeEach(async () => {
    await import('@/store/admin')
    http.get.mockReset()
    http.post.mockReset()
    store.refresh.mockReset()
    store.refresh.mockResolvedValue({ user: {} })
    store.admin.experiment = reactive({ id: 4, name: 'Trust', fileMode: false, style: '', clientHtmlHash: 0, clientGraphHash: 0 })
    store.admin.email = 'r@lab.org'
    leave.guard = null
    const { docs } = await import('../src/pages/participant-page/pageDocs')
    docs.clear()
  })

  async function mountPage () {
    const { default: Page } = await import('../src/pages/participant-page/ParticipantPagePage.vue')
    const w = mount(Page, { attachTo: document.body, global: { stubs: { BCodeEditor: EditorStub } } })
    await flushPromises()
    return w
  }
  const saveButton = (w: any) => w.findAll('button').find((b: any) => /^Save Client HTML/.test(b.text()))

  it('loads the first tab, saves edits and asks for fresh state', async () => {
    http.get.mockResolvedValue({ clientHtml: '<v-app></v-app>' })
    http.post.mockResolvedValue(null)
    const w = await mountPage()
    expect(http.get).toHaveBeenCalledWith('/customize/clientHtml/4')
    const ed = w.find('textarea.stub-editor')
    expect((ed.element as HTMLTextAreaElement).value).toBe('<v-app></v-app>')
    expect(saveButton(w).attributes('disabled')).toBeDefined()
    await ed.setValue('<v-app>hi</v-app>')
    expect(w.text()).toMatch(/Unsaved changes/)
    await saveButton(w).trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/customize/clientHtml/4', { clientHtml: '<v-app>hi</v-app>' })
    expect(store.refresh).toHaveBeenCalled()
    expect(w.text()).toMatch(/Saved at/)
    w.unmount()
  })

  it('never saves a tab whose load failed, even with Cmd+S', async () => {
    const { HttpError } = await import('@/lib/http')
    http.get.mockRejectedValue(new (HttpError as any)(400, 'Invalid Experiment ID'))
    const w = await mountPage()
    expect(w.text()).toMatch(/Couldn’t load the Client HTML/)
    expect(w.text()).toMatch(/Invalid Experiment ID/)
    expect(w.find('textarea.stub-editor').exists()).toBe(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', metaKey: true, cancelable: true }))
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(saveButton(w).attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('is read only in file mode and follows the files when they change (DL-20)', async () => {
    store.admin.experiment.fileMode = true
    http.get.mockResolvedValueOnce({ clientHtml: 'v1' }).mockResolvedValueOnce({ clientHtml: 'v2' })
    const w = await mountPage()
    expect(w.find('textarea.stub-editor').attributes('readonly')).toBeDefined()
    expect(saveButton(w)).toBeUndefined()
    store.admin.experiment = reactive({ ...store.admin.experiment, clientHtmlHash: javaHashCode('v2') })
    await flushPromises()
    expect(http.get).toHaveBeenCalledTimes(2)
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('v2')
    w.unmount()
  })

  it('keeps edits and warns when the page changed elsewhere', async () => {
    http.get.mockResolvedValue({ clientHtml: 'mine' })
    const w = await mountPage()
    await w.find('textarea.stub-editor').setValue('mine, edited')
    store.admin.experiment = reactive({ ...store.admin.experiment, clientHtmlHash: javaHashCode('theirs') })
    await flushPromises()
    expect(http.get).toHaveBeenCalledTimes(1)
    expect(w.text()).toMatch(/changed somewhere else/)
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('mine, edited')
    w.unmount()
  })

  it('asks before leaving with unsaved edits', async () => {
    http.get.mockResolvedValue({ clientHtml: 'a' })
    const w = await mountPage()
    expect(await leave.guard!()).toBe(true)
    await w.find('textarea.stub-editor').setValue('b')
    const ui = await import('@/ui/confirm')
    const pending = leave.guard!()
    expect(ui.confirmState.open).toBe(true)
    expect(ui.confirmState.req?.message).toMatch(/Client HTML/)
    ui.settleConfirm(false)
    expect(await pending).toBe(false)
    w.unmount()
  })

  // signing out (or a 401) swaps the console for the sign-in form: the page goes without a route change
  it('keeps unsaved edits when the page is taken down without leaving it', async () => {
    http.get.mockResolvedValue({ clientHtml: 'a' })
    store.admin.experiment.clientHtmlHash = javaHashCode('a')
    let w = await mountPage()
    await w.find('textarea.stub-editor').setValue('a, edited')
    w.unmount()
    w = await mountPage()
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('a, edited')
    expect(w.text()).toMatch(/Unsaved changes/)
    expect(w.text()).not.toMatch(/changed somewhere else/)
    expect(http.get).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('counts unsaved pages for the sign-out question, until they are left behind', async () => {
    http.get.mockResolvedValue({ clientHtml: 'a' })
    const { unsavedTotal } = await import('../src/shell/unsaved')
    const w = await mountPage()
    expect(unsavedTotal()).toBe(0)
    await w.find('textarea.stub-editor').setValue('a, edited')
    expect(unsavedTotal()).toBe(1)
    // taken down without leaving (signed out): still there to lose
    w.unmount()
    expect(unsavedTotal()).toBe(1)
    const { docs } = await import('../src/pages/participant-page/pageDocs')
    docs.clear()
    expect(unsavedTotal()).toBe(0)
  })

  it('notices a change made elsewhere while the kept edits were away', async () => {
    http.get.mockResolvedValue({ clientHtml: 'a' })
    store.admin.experiment.clientHtmlHash = javaHashCode('a')
    let w = await mountPage()
    await w.find('textarea.stub-editor').setValue('a, edited')
    w.unmount()
    store.admin.experiment.clientHtmlHash = javaHashCode('theirs')
    w = await mountPage()
    expect(w.text()).toMatch(/changed somewhere else/)
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('a, edited')
    w.unmount()
  })

  it('says nothing about kept edits when the experiment goes away (signing out)', async () => {
    http.get.mockResolvedValue({ clientHtml: 'a' })
    const w = await mountPage()
    await w.find('textarea.stub-editor').setValue('a, edited')
    const { toasts } = await import('@/ui/toast')
    toasts.splice(0)
    store.admin.experiment = null
    await flushPromises()
    expect(toasts.some(t => /unsaved changes are kept/.test(t.title))).toBe(false)
    w.unmount()
    const { docs } = await import('../src/pages/participant-page/pageDocs')
    expect(docs.size).toBe(1)
  })

  it('never shows one account’s unsaved edits to another', async () => {
    http.get.mockResolvedValueOnce({ clientHtml: 'a' }).mockResolvedValueOnce({ clientHtml: 'b' })
    let w = await mountPage()
    await w.find('textarea.stub-editor').setValue('a, edited')
    w.unmount()
    store.admin.email = 'other@lab.org'
    await flushPromises()
    w = await mountPage()
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('b')
    expect(w.text()).not.toMatch(/Unsaved changes/)
    w.unmount()
  })

  it('drops the edits when the researcher leaves the page without saving', async () => {
    http.get.mockResolvedValueOnce({ clientHtml: 'a' }).mockResolvedValueOnce({ clientHtml: 'a' })
    let w = await mountPage()
    await w.find('textarea.stub-editor').setValue('a, edited')
    const ui = await import('@/ui/confirm')
    const pending = leave.guard!()
    ui.settleConfirm(true)
    expect(await pending).toBe(true)
    w.unmount()
    w = await mountPage()
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe('a')
    w.unmount()
  })

  it('inserts the new participant page after a confirm, without saving', async () => {
    http.get.mockImplementation((url: string) => Promise.resolve(url.includes('clientGraph') ? { clientGraph: DEFAULT_CLIENT_GRAPH } : { clientHtml: conf('legacy-client-html.html') }))
    const w = await mountPage()
    expect(w.text()).toMatch(/previous page layout/)
    const btn = w.findAll('button').find(b => /Insert the new participant page/.test(b.text()))!
    await btn.trigger('click')
    const ui = await import('@/ui/confirm')
    expect(ui.confirmState.req?.title).toMatch(/Replace the Client HTML/)
    ui.settleConfirm(true)
    await flushPromises()
    expect((w.find('textarea.stub-editor').element as HTMLTextAreaElement).value).toBe(DEFAULT_CLIENT_HTML)
    expect(http.post).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/Unsaved changes/)
    w.unmount()
  })
})
