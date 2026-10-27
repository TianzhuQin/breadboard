import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

// ---- mocks: the store, the HTTP layer and confirm ------------------------------------------------

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})

const store = vi.hoisted(() => ({
  actions: {
    refresh: vi.fn(async () => null),
    selectExperiment: vi.fn(async () => null),
  },
  admin: null as any,
}))
vi.mock('@/store/admin', async () => {
  const { computed, reactive, ref } = await import('vue')
  const admin = reactive({ email: 'r@lab.org', experiment: null as any, user: null as any, instance: null as any })
  store.admin = admin
  return {
    admin,
    actions: store.actions,
    experiments: computed(() => admin.user?.experiments || []),
    running: ref(false),
    ensureConnected: vi.fn(() => true),
  }
})

const rebuild = vi.hoisted(() => vi.fn(async (_opts: any) => true))
vi.mock('@/store/engineGuard', () => ({ confirmEngineRebuild: rebuild }))

const confirmMock = vi.hoisted(() => vi.fn(async (_req: any) => true))
vi.mock('@/ui/confirm', () => ({ confirm: confirmMock, confirmState: { open: false, req: null, resolve: null }, settleConfirm: () => {} }))

// Node's own (file-less) localStorage shadows jsdom's here; use a plain in-memory one.
const memory = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => (memory.has(k) ? memory.get(k)! : null),
  setItem: (k: string, v: string) => { memory.set(k, String(v)) },
  removeItem: (k: string) => { memory.delete(k) },
  clear: () => memory.clear(),
})

import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import type { Content, Language } from '../src/store/types'
import {
  activeContent, decodeEntities, hasText, isOldPlaceholder, markPlaceholders, normalizeStyle, plainText, previewDocument,
  roundTripLoss, sameText, tokenize,
} from '../src/pages/content/contentHtml'
import {
  afterSave, buildItems, buildSavePayload, createDraft, duplicateNames, lacking, langKey, languageSets, mergeByName,
  rebase, setName, setText, statusOf, textOf, upsertContent, validateName, type Drafts,
} from '../src/pages/content/contentModel'
import { dropKeptDrafts, explain, unsavedContentCount } from '../src/pages/content/useContent'
import { HttpError } from '../src/lib/http'
import { unsavedTotal } from '../src/shell/unsaved'
import { toasts } from '../src/ui/toast'
import ContentPage from '../src/pages/content/ContentPage.vue'
import VisualEditor from '../src/pages/content/VisualEditor.vue'

const eng: Language = { id: 1, code: 'eng', name: 'English' }
const spa: Language = { id: 2, code: 'spa', name: 'Spanish' }
const fra: Language = { id: 3, code: 'fra', name: 'French' }

function sample (): Content[] {
  return [
    { id: 10, name: 'Intro', translations: [{ id: 100, html: '<p>Hello {0}</p>', language: eng }] },
    { id: 11, name: 'Bye', translations: [{ id: 101, html: '<p>Bye</p>', language: eng }, { id: 102, html: '<p>Adiós</p>', language: spa }] },
  ]
}
const find = (items: ReturnType<typeof buildItems>, name: string) => items.find(i => i.name === name)!

// ---- translation bookkeeping (missing vs modified) -----------------------------------------------

describe('content bookkeeping', () => {
  it('looking at a missing translation changes nothing (the old console saved a placeholder, §3.2.4)', () => {
    const drafts: Drafts = {}
    const items = buildItems(sample(), drafts)
    const intro = find(items, 'Intro')
    expect(statusOf(intro, spa)).toBe('missing')
    expect(textOf(intro, spa)).toBe('')
    // an editor echoing back what it was given
    setText(drafts, intro, spa, '')
    setText(drafts, intro, spa, '   \n')
    expect(drafts).toEqual({})
    const again = find(buildItems(sample(), drafts), 'Intro')
    expect(again.modified).toBe(false)
    expect(buildSavePayload(7, again).body.translations).toEqual([])
  })

  it('typing a missing translation creates it; clearing it again undoes that', () => {
    const drafts: Drafts = {}
    let intro = find(buildItems(sample(), drafts), 'Intro')
    setText(drafts, intro, spa, '<p>Hola {0}</p>')
    intro = find(buildItems(sample(), drafts), 'Intro')
    expect(intro.modified).toBe(true)
    expect(statusOf(intro, spa)).toBe('present')
    expect(intro.translations[langKey(spa)]).toMatchObject({ id: null, saved: null, text: '<p>Hola {0}</p>', modified: true })

    setText(drafts, intro, spa, '')
    expect(drafts).toEqual({})
    expect(find(buildItems(sample(), drafts), 'Intro').modified).toBe(false)
  })

  it('an edit typed back to the saved text is not a change', () => {
    const drafts: Drafts = {}
    let bye = find(buildItems(sample(), drafts), 'Bye')
    setText(drafts, bye, eng, '<p>Bye!</p>')
    bye = find(buildItems(sample(), drafts), 'Bye')
    expect(bye.modified).toBe(true)
    setText(drafts, bye, eng, '<p>Bye</p>')
    expect(drafts).toEqual({})
  })

  it('emptying a saved translation is a change (and is saved as empty)', () => {
    const drafts: Drafts = {}
    let bye = find(buildItems(sample(), drafts), 'Bye')
    setText(drafts, bye, spa, '')
    bye = find(buildItems(sample(), drafts), 'Bye')
    expect(bye.modified).toBe(true)
    expect(statusOf(bye, spa)).toBe('empty')
    expect(buildSavePayload(7, bye).body.translations).toEqual([{ id: 102, html: '', language: spa }])
  })

  it('line endings alone are not a change (CodeMirror hands back \\n)', () => {
    const server: Content[] = [{ id: 1, name: 'A', translations: [{ id: 5, html: '<p>a</p>\r\n<p>b</p>', language: eng }] }]
    const drafts: Drafts = {}
    const a = buildItems(server, drafts)[0]
    setText(drafts, a, eng, '<p>a</p>\n<p>b</p>')
    expect(drafts).toEqual({})
    expect(sameText('x\r\ny', 'x\ny')).toBe(true)
    expect(sameText('x y', 'x  y')).toBe(false)
  })

  it('counts missing, empty and the old placeholder as lacking a language', () => {
    const server: Content[] = [
      { id: 1, name: 'A', translations: [{ id: 1, html: '<p>Type your content for the Spanish language here.</p>', language: spa }] },
      { id: 2, name: 'B', translations: [{ id: 2, html: '<p>&nbsp;</p>', language: spa }] },
      { id: 3, name: 'C', translations: [{ id: 3, html: '<img src="/images/1/a.png">', language: spa }] },
      { id: 4, name: 'D', translations: [] },
    ]
    const items = buildItems(server, {})
    expect(items.map(i => statusOf(i, spa))).toEqual(['placeholder', 'empty', 'present', 'missing'])
    expect(lacking(items, spa).map(i => i.name)).toEqual(['A', 'B', 'D'])
  })

  it('a new text is always unsaved, sorts by name and starts with no translations', () => {
    const drafts: Drafts = {}
    const key = createDraft(drafts, '  Consent ')
    const items = buildItems(sample(), drafts)
    expect(items.map(i => i.name)).toEqual(['Bye', 'Consent', 'Intro'])
    const consent = items.find(i => i.key === key)!
    expect(consent).toMatchObject({ isNew: true, modified: true, id: null, savedName: null })
    expect(statusOf(consent, eng)).toBe('missing')
    // typing then clearing a translation of a new text leaves the text but no translation
    setText(drafts, consent, eng, '<p>x</p>')
    setText(drafts, consent, eng, '')
    expect(drafts[key]).toMatchObject({ isNew: true, name: 'Consent', html: {} })
  })

  it('renaming back to the saved name is not a change', () => {
    const drafts: Drafts = {}
    const intro = find(buildItems(sample(), drafts), 'Intro')
    setName(drafts, intro, 'Welcome')
    const renamed = buildItems(sample(), drafts).find(i => i.key === intro.key)!
    expect(renamed).toMatchObject({ name: 'Welcome', savedName: 'Intro', renamed: true, modified: true })
    setName(drafts, renamed, ' Intro ')
    expect(drafts).toEqual({})
  })

  it('keeps edits when a fresh copy arrives, and forgets the ones that now match it', () => {
    const drafts: Drafts = {}
    const items = buildItems(sample(), drafts)
    setText(drafts, find(items, 'Bye'), eng, '<p>See you</p>')
    setText(drafts, find(items, 'Intro'), eng, '<p>Hi {0}</p>')
    // someone saved "Bye" elsewhere with the same text
    const fresh = sample()
    fresh[1].translations[0].html = '<p>See you</p>'
    const dropped = rebase(drafts, buildItems(fresh, {}))
    expect(dropped).toEqual([])
    expect(Object.keys(drafts)).toEqual(['id:10'])
    expect(textOf(find(buildItems(fresh, drafts), 'Intro'), eng)).toBe('<p>Hi {0}</p>')
  })

  it('drops (and names) edits of texts that disappeared from the server', () => {
    const drafts: Drafts = {}
    const items = buildItems(sample(), drafts)
    setText(drafts, find(items, 'Bye'), eng, '<p>See you</p>')
    const newKey = createDraft(drafts, 'Draft')
    const dropped = rebase(drafts, buildItems([sample()[0]], {}))
    expect(dropped).toEqual(['Bye'])
    expect(Object.keys(drafts)).toEqual([newKey])
  })
})

// ---- the save payload ---------------------------------------------------------------------------

describe('save payload', () => {
  it('sends only the translations that changed: new ones as -1, existing ones with their id', () => {
    const drafts: Drafts = {}
    let bye = find(buildItems(sample(), drafts), 'Bye')
    setText(drafts, bye, spa, '<p>Hasta luego</p>')
    setText(drafts, bye, fra, '<p>Au revoir</p>')
    bye = find(buildItems(sample(), drafts), 'Bye')
    const p = buildSavePayload(7, bye)
    expect(p.url).toBe('/content/11')
    expect(p.body).toEqual({
      experimentId: 7,
      contentId: 11,
      name: 'Bye',
      translations: [
        { id: -1, html: '<p>Au revoir</p>', language: { id: 3, code: 'fra', name: 'French' } },
        { id: 102, html: '<p>Hasta luego</p>', language: { id: 2, code: 'spa', name: 'Spanish' } },
      ],
    })
  })

  it('a rename alone sends no translations', () => {
    const drafts: Drafts = {}
    const intro = find(buildItems(sample(), drafts), 'Intro')
    setName(drafts, intro, 'Welcome')
    const p = buildSavePayload(7, buildItems(sample(), drafts).find(i => i.key === intro.key)!)
    expect(p).toEqual({ url: '/content/10', body: { experimentId: 7, contentId: 10, name: 'Welcome', translations: [] } })
  })

  it('a new text posts to /content/-1 with its non-empty translations', () => {
    const drafts: Drafts = {}
    const key = createDraft(drafts, 'Consent')
    let item = buildItems([], drafts)[0]
    setText(drafts, item, eng, '<p>I agree</p>')
    setText(drafts, item, spa, '  ')
    item = buildItems([], drafts)[0]
    const p = buildSavePayload(7, item)
    expect(p.url).toBe('/content/-1')
    expect(p.body).toEqual({ experimentId: 7, contentId: -1, name: 'Consent', translations: [{ id: -1, html: '<p>I agree</p>', language: eng }] })
    expect(item.key).toBe(key)
  })

  it('after a save, forgets what was sent but keeps what was typed meanwhile', () => {
    const drafts: Drafts = {}
    const key = createDraft(drafts, 'Consent')
    let item = buildItems([], drafts)[0]
    setText(drafts, item, eng, '<p>I agree</p>')
    setText(drafts, item, spa, '<p>Acepto</p>')
    item = buildItems([], drafts)[0]
    const payload = buildSavePayload(7, item)
    // the researcher keeps typing in Spanish while the request is on its way
    setText(drafts, item, spa, '<p>Acepto.</p>')
    const saved: Content = {
      id: 55,
      name: 'Consent',
      translations: [{ id: 501, html: '<p>I agree</p>', language: eng }, { id: 502, html: '<p>Acepto</p>', language: spa }],
    }
    const next = afterSave(drafts, { key }, payload, saved)
    expect(next).toBe('id:55')
    expect(drafts[key]).toBeUndefined()
    expect(Object.keys(drafts)).toEqual(['id:55'])
    const after = buildItems([saved], drafts)[0]
    expect(after.translations[langKey(eng)].modified).toBe(false)
    expect(after.translations[langKey(spa)]).toMatchObject({ id: 502, saved: '<p>Acepto</p>', text: '<p>Acepto.</p>', modified: true })
    expect(buildSavePayload(7, after).body.translations).toEqual([{ id: 502, html: '<p>Acepto.</p>', language: spa }])
  })

  it('replaces the saved text in the server list (or adds a new one)', () => {
    const list = sample()
    const saved: Content = { id: 10, name: 'Welcome', translations: [] }
    expect(upsertContent(list, 10, saved).map(c => c.name)).toEqual(['Welcome', 'Bye'])
    expect(upsertContent(list, null, { id: 12, name: 'New', translations: [] }).map(c => c.id)).toEqual([10, 11, 12])
    expect(list[0].name).toBe('Intro')
  })
})

// ---- names, languages, file mode ----------------------------------------------------------------

describe('names and languages', () => {
  const items = buildItems(sample(), {})
  it('validates names', () => {
    expect(validateName('  ', items)).toMatch(/name/)
    expect(validateName('a/b', items)).toMatch(/can’t contain/)
    expect(validateName('intro', items)).toMatch(/already a text called “Intro”/)
    expect(validateName('Intro', items, 'id:10')).toBeNull()
    expect(validateName('Outro', items)).toBeNull()
    expect(validateName('x'.repeat(201), items)).toMatch(/200/)
  })

  it('finds duplicate names (which scripts can’t tell apart)', () => {
    const dup = buildItems([...sample(), { id: 12, name: 'Intro ', translations: [] }, { id: 13, name: 'bye', translations: [] }], {})
    expect([...duplicateNames(dup)]).toEqual(['Intro'])
  })

  it('lists languages some texts use that the experiment doesn’t have', () => {
    const { languages, extra } = languageSets([eng], sample())
    expect(languages).toEqual([eng])
    expect(extra).toEqual([spa])
  })

  it('shows one text per name in file mode, with all its languages', () => {
    const files: Content[] = [
      { id: null, name: 'Intro', translations: [{ id: null, html: 'Hi', language: eng }] },
      { id: null, name: 'Intro', translations: [{ id: null, html: 'Hola', language: spa }] },
    ]
    expect(mergeByName(files)).toHaveLength(1)
    const items = buildItems(files, {}, true)
    expect(items).toHaveLength(1)
    expect(items[0].key).toBe('file:Intro')
    expect(textOf(items[0], spa)).toBe('Hola')
  })
})

// ---- HTML helpers -------------------------------------------------------------------------------

describe('content HTML', () => {
  it('knows when a translation shows nothing', () => {
    expect(hasText('')).toBe(false)
    expect(hasText('<p>&nbsp;</p><!-- note -->')).toBe(false)
    expect(hasText('<p><br></p>')).toBe(false)
    expect(hasText('<img src="x.png">')).toBe(true)
    expect(hasText('{0}')).toBe(true)
    expect(isOldPlaceholder('<p>Type your content for the English language here.</p>')).toBe(true)
    expect(isOldPlaceholder('<p>Type your content here.</p>')).toBe(false)
    expect(plainText('<style>p{}</style><p>Caf&eacute; &amp; <b>tea</b></p>')).toBe('Café & tea')
    expect(decodeEntities('&lt;b&gt; &#x41;&#66;')).toBe('<b> AB')
  })

  it('tokenizes tags, words and comments without repairing anything', () => {
    expect(tokenize('<P Class="a  b">Hi <!-- x -->there</p>')).toEqual([
      { t: 'open', name: 'p', attrs: { class: 'a b' } },
      { t: 'text', v: 'Hi' },
      { t: 'comment', v: 'x' },
      { t: 'text', v: 'there' },
      { t: 'close', name: 'p' },
    ])
    expect(tokenize('<a title="1 > 0" href=x>go</a>')[0]).toEqual({ t: 'open', name: 'a', attrs: { title: '1 > 0', href: 'x' } })
    expect(normalizeStyle('COLOR: RGB(255, 0, 0); margin:0 ;')).toBe('color:#ff0000;margin:0')
  })

  it('lets the visual editor add structure but not lose or move anything', () => {
    // harmless: paragraphs, <tbody>, reformatted styles, entities, its own data-mce attributes
    expect(roundTripLoss('Hello {0}', '<p>Hello {0}</p>')).toBeNull()
    expect(roundTripLoss('<table><tr><td>{0}</td></tr></table>', '<table><tbody><tr><td>{0}</td></tr></tbody></table>')).toBeNull()
    expect(roundTripLoss('<span style="color:RED">x</span>', '<span style="color: red;">x</span>')).toBeNull()
    expect(roundTripLoss('caf&eacute; <img src="a.png">', '<p>café <img src="a.png" alt="" data-mce-src="a.png"></p>')).toBeNull()
    expect(roundTripLoss('<!-- keep --><p>x</p>', '<!-- keep -->\n<p>x</p>')).toBeNull()
    // lost or moved
    expect(roundTripLoss('<p>a <span class="icon"></span> b</p>', '<p>a b</p>')).toBe('the <span …> tag')
    expect(roundTripLoss('<p>a<span class="icon"></span>b</p>', '<p>ab</p>')).not.toBeNull()
    expect(roundTripLoss('<table><tr>{0}</tr></table>', '<p>{0}</p><table><tbody><tr></tr></tbody></table>')).toMatch(/“\{0\}”|<tr>/)
    expect(roundTripLoss('<p data-x="1">a</p>', '<p>a</p>')).toBe('the <p …> tag')
    expect(roundTripLoss('<p>Score: {0}</p>', '<p>Score:</p>')).toBe('“{0}”')
    expect(roundTripLoss('<b><i>x</b></i>', '<b><i>x</i></b>')).toBe('the end of a <i>')
    expect(roundTripLoss('<!-- note --><p>x</p>', '<p>x</p>')).toBe('an HTML comment')
  })

  it('spots markup that would run code in the visual editor’s frame', () => {
    expect(activeContent('<img src="x" onerror="alert(1)">')).toBe('an onerror event handler')
    expect(activeContent('<svg/onload=alert(1)>')).toBe('an onload event handler')
    expect(activeContent('<a href="jav&#x61;script:alert(1)">x</a>')).toBe('a javascript: link')
    expect(activeContent('<iframe srcdoc="<b>x</b>"></iframe>')).toMatch(/srcdoc/)
    expect(activeContent('<p>Only {0} left. <script>var x = 1</script></p><img src="a.png">')).toBeNull()
  })

  it('highlights {0} placeholders in the preview, but not inside tags or scripts', () => {
    const out = markPlaceholders('<p title="{1}">You earned {0} points</p><script>var s = "{2}"</script>')
    expect(out).toContain('<mark class="bb-ph">{0}</mark>')
    expect(out).toContain('title="{1}"')
    expect(out).not.toContain('<mark class="bb-ph">{2}</mark>')
    expect(markPlaceholders('<p>none</p>')).toBe('<p>none</p>')
  })

  it('builds a self-contained preview document with the experiment’s CSS kept inside it', () => {
    const doc = previewDocument('<p>Hi</p>', { style: 'p { color: red } </style><script>alert(1)</script>' })
    expect(doc).toContain('<main class="bb-card bb-prose"><p>Hi</p></main>')
    expect(doc).not.toContain('</style><script>')
    expect(previewDocument('', { emptyText: 'No Spanish version yet.' })).toContain('No Spanish version yet.')
  })

  it('explains failed requests in words', () => {
    expect(explain(new HttpError(400, 'Invalid Content ID'))).toMatch(/no longer exists/)
    expect(explain(new HttpError(500, 'boom'))).toMatch(/server ran into a problem/)
    expect(explain(new TypeError('Failed to fetch'))).toMatch(/could not be reached/)
  })
})

// ---- the page -----------------------------------------------------------------------------------

const CodeStub = defineComponent({
  name: 'BCodeEditor',
  props: { modelValue: { type: String, default: '' }, readonly: Boolean, ariaLabel: String },
  emits: ['update:modelValue'],
  setup (props, { emit, expose }) {
    expose({ focus: () => {} })
    return () => h('textarea', {
      class: 'code-stub',
      value: props.modelValue,
      readonly: props.readonly,
      'aria-label': props.ariaLabel,
      onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value),
    })
  },
})
const VisualStub = defineComponent({ name: 'VisualEditor', setup: () => () => h('div', { class: 'visual-stub' }) })
const PreviewStub = defineComponent({ name: 'ContentPreview', setup: () => () => h('div', { class: 'preview-stub' }) })

function experiment (over: Record<string, any> = {}) {
  return {
    id: 7,
    name: 'Trust game',
    uid: 'u',
    fileMode: false,
    style: '',
    languages: [eng, spa],
    content: sample(),
    steps: [],
    parameters: [],
    instances: [],
    images: [],
    clientGraphHash: 0,
    clientHtmlHash: 0,
    ...over,
  }
}

async function mountPage (exp = experiment(), all = [exp]) {
  store.admin.experiment = exp
  store.admin.user = { email: 'r@lab.org', defaultLanguage: eng, experiments: all, currentScript: '' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/content', component: ContentPage },
      { path: '/other', component: { render: () => h('p', 'other') } },
      { path: '/experiments', component: { render: () => h('p', 'experiments') } },
    ],
  })
  router.push('/content')
  await router.isReady()
  const wrapper = mount({ render: () => h(RouterView) }, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: { BCodeEditor: CodeStub, VisualEditor: VisualStub, ContentPreview: PreviewStub },
    },
  })
  await flushPromises()
  return { wrapper, router }
}

function saveButton (wrapper: any) {
  const all = wrapper.findAll('.ct-bar__save button')
  return all[all.length - 1]
}
function tab (wrapper: any, name: string) {
  return wrapper.findAll('[role="tab"]').find((b: any) => b.text().includes(name))
}

describe('ContentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    store.admin.email = 'r@lab.org'
    dropKeptDrafts()
    localStorage.clear()
    localStorage.setItem('bb-content-mode', 'html')
    localStorage.setItem('bb-content-preview-html', '0')
    document.body.innerHTML = ''
    http.get.mockImplementation(async (url: string) => {
      if (url === '/content/7') return { content: store.admin.experiment.content }
      if (url === '/languages') return { languages: [eng, spa, fra] }
      throw new Error(`unexpected GET ${url}`)
    })
    http.post.mockImplementation(async (url: string, body: any) => ({
      id: body.contentId === -1 ? 99 : body.contentId,
      name: body.name,
      translations: body.translations.map((t: any, i: number) => ({ ...t, id: t.id === -1 ? 900 + i : t.id })),
    }))
  })

  it('loads the texts and shows which ones lack the selected language', async () => {
    const { wrapper } = await mountPage()
    expect(http.get).toHaveBeenCalledWith('/content/7')
    expect(wrapper.findAll('.ct-row').map(r => r.find('.ct-row__name').text())).toEqual(['Bye', 'Intro'])
    expect(tab(wrapper, 'English').attributes('aria-selected')).toBe('true')
    // Spanish: "Intro" has no Spanish version
    expect(tab(wrapper, 'Spanish').find('.ct-lang__miss').text()).toBe('1')
    await tab(wrapper, 'Spanish').trigger('click')
    const introRow = wrapper.findAll('.ct-row').find(r => r.text().includes('Intro'))!
    expect(introRow.text()).toContain('No Spanish version')
    wrapper.unmount()
  })

  it('viewing a missing translation leaves the text unmodified; typing one saves it as new', async () => {
    const { wrapper } = await mountPage()
    await wrapper.findAll('.ct-row').find(r => r.text().includes('Intro'))!.trigger('click')
    await tab(wrapper, 'Spanish').trigger('click')
    expect(wrapper.text()).toContain('No Spanish version yet')
    expect(saveButton(wrapper).text()).toBe('All saved')
    expect(wrapper.find('.ct-row__dot').exists()).toBe(false)

    await wrapper.find('textarea.code-stub').setValue('<p>Hola {0}</p>')
    expect(saveButton(wrapper).text()).toBe('Save')
    expect(wrapper.find('.ct-row__dot').exists()).toBe(true)

    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(http.post).toHaveBeenCalledWith('/content/10', {
      experimentId: 7,
      contentId: 10,
      name: 'Intro',
      translations: [{ id: -1, html: '<p>Hola {0}</p>', language: spa }],
    })
    expect(store.actions.refresh).toHaveBeenCalled()
    expect(saveButton(wrapper).text()).toBe('All saved')
    wrapper.unmount()
  })

  it('Cmd/Ctrl+S saves every modified text', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Goodbye</p>')
    await wrapper.findAll('.ct-row').find(r => r.text().includes('Intro'))!.trigger('click')
    await wrapper.find('textarea.code-stub').setValue('<p>Welcome {0}</p>')
    expect(saveButton(wrapper).text()).toBe('Save all (2)')

    const e = new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true })
    window.dispatchEvent(e)
    expect(e.defaultPrevented).toBe(true)
    await flushPromises()
    expect(http.post.mock.calls.map(c => c[0]).sort()).toEqual(['/content/10', '/content/11'])
    wrapper.unmount()
  })

  it('a second save waits for the one on its way, so no translation is posted twice', async () => {
    const three = ['Intro', 'Middle', 'Outro'].map((name, i) => ({ id: 10 + i, name, translations: [{ id: 100 + i, html: `<p>${name}</p>`, language: eng }] }))
    const { wrapper } = await mountPage(experiment({ content: three }))
    await tab(wrapper, 'Spanish').trigger('click')
    for (const name of ['Intro', 'Middle', 'Outro']) {
      await wrapper.findAll('.ct-row').find(r => r.text().includes(name))!.trigger('click')
      await wrapper.find('textarea.code-stub').setValue(`<p>${name} (es)</p>`)
    }
    expect(saveButton(wrapper).text()).toBe('Save all (3)')
    // the first text's save is slow
    const answerNormally = http.post.getMockImplementation()!
    let answer!: () => void
    http.post.mockImplementationOnce((url: string, body: any) => new Promise(resolve => { answer = () => resolve(answerNormally(url, body)) }))
    const press = () => window.dispatchEvent(new KeyboardEvent('keydown', { key: 's', ctrlKey: true, cancelable: true }))
    press()
    await flushPromises()
    // pressed again, and the selected text's own Save too
    press()
    await wrapper.find('button[title="Save this text only"]').trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    answer()
    await flushPromises()
    expect(http.post.mock.calls.map(c => c[0])).toEqual(['/content/10', '/content/11', '/content/12'])
    for (const [, body] of http.post.mock.calls) {
      expect(body.translations).toEqual([{ id: -1, html: `<p>${body.name} (es)</p>`, language: spa }])
    }
    expect(saveButton(wrapper).text()).toBe('All saved')
    wrapper.unmount()
  })

  it('posts a text as it is when its turn comes: edited further, or thrown away meanwhile', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Goodbye</p>')
    await wrapper.find('button[aria-label="New text"]').trigger('click')
    await nextTick()
    const input = document.querySelector<HTMLInputElement>('#ct-new-form input')!
    input.value = 'Zebra'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    document.querySelector<HTMLFormElement>('#ct-new-form')!.dispatchEvent(new Event('submit'))
    await flushPromises()
    await wrapper.find('textarea.code-stub').setValue('<p>Stripes</p>')
    await wrapper.findAll('.ct-row').find(r => r.text().includes('Intro'))!.trigger('click')
    await wrapper.find('textarea.code-stub').setValue('<p>Welcome</p>')
    expect(saveButton(wrapper).text()).toBe('Save all (3)')
    const answerNormally = http.post.getMockImplementation()!
    let answer!: () => void
    http.post.mockImplementationOnce((url: string, body: any) => new Promise(resolve => { answer = () => resolve(answerNormally(url, body)) }))
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenLastCalledWith('/content/11', expect.anything())
    // while "Bye" is on its way: "Intro" gets more words, and the new text is thrown away
    await wrapper.find('textarea.code-stub').setValue('<p>Welcome, {0}</p>')
    await wrapper.findAll('.ct-row').find(r => r.text().includes('Zebra'))!.trigger('click')
    await wrapper.find('button[aria-label="More actions for “Zebra”"]').trigger('click')
    await wrapper.findAll('.b-menu__item').find(b => b.text() === 'Throw away this text')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.ct-row').some(r => r.text().includes('Zebra'))).toBe(false)
    answer()
    await flushPromises()
    expect(http.post.mock.calls.map(c => c[0])).toEqual(['/content/11', '/content/10'])
    expect(http.post).toHaveBeenLastCalledWith('/content/10', expect.objectContaining({ translations: [expect.objectContaining({ html: '<p>Welcome, {0}</p>' })] }))
    expect(saveButton(wrapper).text()).toBe('All saved')
    wrapper.unmount()
  })

  it('creates a new text and saves it with id -1', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('button[aria-label="New text"]').trigger('click')
    await nextTick()
    const input = document.querySelector<HTMLInputElement>('#ct-new-form input')!
    input.value = 'intro'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    // names are unique, ignoring case
    expect(document.body.textContent).toContain('already a text called “Intro”')
    input.value = 'Consent'
    input.dispatchEvent(new Event('input'))
    await nextTick()
    document.querySelector<HTMLFormElement>('#ct-new-form')!.dispatchEvent(new Event('submit'))
    await flushPromises()
    expect(wrapper.find('.ct-ed__name').text()).toBe('Consent')
    expect(wrapper.text()).toContain('New · not saved yet')

    await wrapper.find('textarea.code-stub').setValue('<p>I agree</p>')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/content/-1', {
      experimentId: 7,
      contentId: -1,
      name: 'Consent',
      translations: [{ id: -1, html: '<p>I agree</p>', language: eng }],
    })
    wrapper.unmount()
  })

  it('shows a save error in words on the text', async () => {
    http.post.mockRejectedValueOnce(new HttpError(400, 'Invalid translation ID.'))
    const { wrapper } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Later</p>')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('.ct-note--danger').text()).toContain('One of its translations no longer exists')
    expect(wrapper.find('.ct-row__err').exists()).toBe(true)
    expect(saveButton(wrapper).text()).toBe('Save')
    wrapper.unmount()
  })

  it('asks before leaving with unsaved changes', async () => {
    const { wrapper, router } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Changed</p>')
    confirmMock.mockResolvedValueOnce(false)
    await router.push('/other')
    expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ title: 'Leave without saving?' }))
    expect(router.currentRoute.value.path).toBe('/content')
    confirmMock.mockResolvedValueOnce(true)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/other')
    wrapper.unmount()
  })

  // signing out (or a 401) swaps the console for the sign-in form: the page goes without a route change
  it('keeps unsaved edits when the page is taken down without leaving it', async () => {
    let { wrapper } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Kept</p>')
    wrapper.unmount()
    expect(unsavedContentCount.value).toBe(1)
    ;({ wrapper } = await mountPage())
    expect((wrapper.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe('<p>Kept</p>')
    expect(saveButton(wrapper).text()).toBe('Save')
    await saveButton(wrapper).trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/content/11', expect.objectContaining({ translations: [expect.objectContaining({ html: '<p>Kept</p>' })] }))
    wrapper.unmount()
  })

  it('counts unsaved texts for the sign-out question, also once the page is gone', async () => {
    const { wrapper } = await mountPage()
    expect(unsavedTotal()).toBe(0)
    await wrapper.find('textarea.code-stub').setValue('<p>Changed</p>')
    expect(unsavedTotal()).toBe(1)
    wrapper.unmount()
    expect(unsavedTotal()).toBe(1)
    dropKeptDrafts()
    expect(unsavedTotal()).toBe(0)
  })

  it('switches to an experiment with unsaved texts, asking first while the engine is in use', async () => {
    const other = experiment({ id: 8, name: 'Other game' })
    let { wrapper } = await mountPage(other, [experiment(), other])
    await wrapper.find('textarea.code-stub').setValue('<p>Kept</p>')
    wrapper.unmount()
    ;({ wrapper } = await mountPage(experiment(), [experiment(), other]))
    await flushPromises()
    const button = () => wrapper.findAll('.ct-banner button').find(b => b.text() === 'Switch to “Other game”')!
    expect(button().exists()).toBe(true)
    // declined: nothing is switched
    rebuild.mockResolvedValueOnce(false)
    await button().trigger('click')
    await flushPromises()
    expect(rebuild).toHaveBeenCalledWith(expect.objectContaining({ title: 'Switch to “Other game”?', action: 'Switching experiments' }))
    expect(store.actions.selectExperiment).not.toHaveBeenCalled()
    await button().trigger('click')
    await flushPromises()
    expect(store.actions.selectExperiment).toHaveBeenCalledWith(8)
    wrapper.unmount()
  })

  it('throws away the unsaved edits of a deleted experiment, but keeps them through signing out', async () => {
    const other = experiment({ id: 8, name: 'Other game' })
    const { wrapper } = await mountPage(other, [experiment(), other])
    await wrapper.find('textarea.code-stub').setValue('<p>Kept</p>')
    wrapper.unmount()
    toasts.splice(0)
    // signing out empties the user
    const user = store.admin.user
    store.admin.user = null
    store.admin.experiment = null
    await flushPromises()
    expect(unsavedContentCount.value).toBe(1)
    // signed in again: "Other game" was deleted meanwhile
    store.admin.experiment = experiment()
    store.admin.user = { ...user, experiments: [experiment()] }
    await flushPromises()
    expect(unsavedContentCount.value).toBe(0)
    expect(unsavedTotal()).toBe(0)
    expect(toasts.some(t => t.title === 'Unsaved texts of “Other game” were thrown away')).toBe(true)
  })

  it('never shows one account’s unsaved edits to another', async () => {
    let { wrapper } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Mine</p>')
    wrapper.unmount()
    store.admin.email = 'other@lab.org'
    await flushPromises()
    expect(unsavedContentCount.value).toBe(0)
    ;({ wrapper } = await mountPage())
    expect((wrapper.find('textarea.code-stub').element as HTMLTextAreaElement).value).toBe('<p>Bye</p>')
    expect(saveButton(wrapper).text()).toBe('All saved')
    wrapper.unmount()
  })

  it('drops the edits when the researcher leaves the page without saving', async () => {
    let { wrapper, router } = await mountPage()
    await wrapper.find('textarea.code-stub').setValue('<p>Gone</p>')
    confirmMock.mockResolvedValueOnce(true)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/other')
    expect(unsavedContentCount.value).toBe(0)
    wrapper.unmount()
    ;({ wrapper } = await mountPage())
    expect(saveButton(wrapper).text()).toBe('All saved')
    wrapper.unmount()
  })

  it('is read-only in file mode, and follows the file-mode switch live', async () => {
    const files = sample().map(c => ({ ...c, id: null, translations: c.translations.map(t => ({ ...t, id: null })) }))
    const { wrapper } = await mountPage(experiment({ fileMode: true, content: files }))
    expect(wrapper.find('.ct-banner--warn').text()).toContain('File mode is on')
    expect(wrapper.find('textarea.code-stub').attributes('readonly')).toBeDefined()
    expect(wrapper.find('.ct-bar__save button').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="New text"]').attributes('disabled')).toBeDefined()
    expect(http.get).not.toHaveBeenCalledWith('/content/7')

    store.admin.experiment = experiment()
    await flushPromises()
    expect(wrapper.find('.ct-banner--warn').exists()).toBe(false)
    expect(wrapper.find('textarea.code-stub').attributes('readonly')).toBeUndefined()
    wrapper.unmount()
  })

  it('adds a language from the searchable picker', async () => {
    http.post.mockImplementationOnce(async () => fra)
    const { wrapper } = await mountPage()
    await wrapper.findAll('.ct-langs button').find(b => b.text() === 'Languages')!.trigger('click')
    await flushPromises()
    expect(http.get).toHaveBeenCalledWith('/languages')
    const search = document.querySelector<HTMLInputElement>('.ct-ld input[type="search"]')!
    search.value = 'fra'
    search.dispatchEvent(new Event('input'))
    await nextTick()
    const results = Array.from(document.querySelectorAll<HTMLButtonElement>('.ct-ld__result'))
    expect(results.map(b => b.getAttribute('aria-label'))).toEqual(['Add French (fra)'])
    results[0].click()
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/languages', { experimentId: 7, languageId: 3 })
    expect(store.actions.refresh).toHaveBeenCalled()
    expect(tab(wrapper, 'French').attributes('aria-selected')).toBe('true')
    wrapper.unmount()
  })
})


// ---- the visual editor, against a stand-in for TinyMCE ------------------------------------------

/**
 * Just enough of TinyMCE 4's editor API for VisualEditor.vue: setContent "tidies" the markup the
 * way the real one does (loose text gets a paragraph, and a placeholder between table rows is moved
 * out of the table, which is what browsers do with it).
 */
function fakeTinymce () {
  const editors: any[] = []
  const tidy = (html: string) => {
    let out = html.trim() && !html.trim().startsWith('<') ? `<p>${html.trim()}</p>` : html
    out = out.replace(/<table><tr>(\{\d+\})<\/tr><\/table>/, '$1<table><tbody><tr></tr></tbody></table>')
    return out
  }
  return {
    editors,
    get: (id: string) => editors.find(e => e.id === id),
    init (settings: any) {
      const handlers: Record<string, ((e: any) => void)[]> = {}
      let content = ''
      const body = document.createElement('div')
      const ed: any = {
        id: settings.selector.slice(1),
        settings,
        on (names: string, fn: (e: any) => void) { for (const n of names.split(' ')) (handlers[n] ||= []).push(fn) },
        fire (name: string, e: any = {}) { for (const f of handlers[name] || []) f(e) },
        setContent (html: string) { content = tidy(html); ed.fire('SetContent', {}) },
        getContent: () => content,
        undoManager: { clear () {}, add () {} },
        getBody: () => body,
        focus () {},
        remove: vi.fn(),
        // what a person does
        type (html: string) { content = html; ed.fire('input') },
        // TinyMCE tidying again later (e.g. when clicked), without anyone typing
        retidy (html: string) { content = html; ed.fire('NodeChange') },
      }
      editors.push(ed)
      settings.setup(ed)
      Promise.resolve().then(() => ed.fire('init'))
    },
  }
}

describe('VisualEditor', () => {
  let tiny: ReturnType<typeof fakeTinymce>
  beforeEach(() => {
    tiny = fakeTinymce()
    ;(window as any).tinymce = tiny
  })

  async function mountVisual (html: string, docKey = 'id:1|#1', readonly = false) {
    const w = mount(VisualEditor, { props: { html, docKey, readonly, label: 'Intro in English', imageList: [] } })
    await flushPromises()
    return { w, ed: tiny.editors[0] }
  }

  it('keeps every tag and attribute (TinyMCE is set to keep any markup and write characters as-is)', async () => {
    const { ed } = await mountVisual('x')
    expect(ed.settings).toMatchObject({ valid_elements: '*[*]', verify_html: false, convert_urls: false, entity_encoding: 'raw' })
  })

  it('showing a text, and TinyMCE tidying it, never reports a change', async () => {
    const { w, ed } = await mountVisual('Hello {0}')
    expect(ed.getContent()).toBe('<p>Hello {0}</p>')
    ed.retidy('<p>Hello {0}</p>\n')
    ;(w.vm as any).flush()
    expect(w.emitted('change')).toBeUndefined()
    expect(ed.getBody().getAttribute('contenteditable')).toBe('true')
  })

  it('reports edits, and the exact original when they are undone', async () => {
    const { w, ed } = await mountVisual('Hello {0}')
    ed.type('<p>Hello {0}!</p>')
    ;(w.vm as any).flush()
    expect(w.emitted('change')).toEqual([['<p>Hello {0}!</p>', 'id:1|#1']])
    ed.type('<p>Hello {0}</p>')
    ;(w.vm as any).flush()
    expect(w.emitted('change')![1]).toEqual(['Hello {0}', 'id:1|#1'])
  })

  it('notices new words even when no key event announced them', async () => {
    const { w, ed } = await mountVisual('<p>Hi</p>')
    ed.retidy('<p>Hi there</p>')
    ;(w.vm as any).flush()
    expect(w.emitted('change')).toEqual([['<p>Hi there</p>', 'id:1|#1']])
  })

  it('locks a text it would change, and says why', async () => {
    const { w, ed } = await mountVisual('<table><tr>{0}</tr></table>')
    expect(ed.getBody().getAttribute('contenteditable')).toBe('false')
    expect(w.text()).toContain('Shown read-only so nothing is lost')
    expect(w.text()).toContain('“{0}”')
    ed.type('<p>oops</p>')
    ;(w.vm as any).flush()
    expect(w.emitted('change')).toBeUndefined()
    await w.find('.ct-note button').trigger('click')
    expect(w.emitted('use-html')).toHaveLength(1)
  })

  it('never loads a text with event handlers into its (same-origin) frame', async () => {
    const { w, ed } = await mountVisual('<img src="x" onerror="parent.hacked = 1">')
    expect(ed.getContent()).toBe('')
    expect(ed.getBody().getAttribute('contenteditable')).toBe('false')
    expect(w.text()).toContain('an onerror event handler')
    ;(w.vm as any).flush()
    expect(w.emitted('change')).toBeUndefined()
  })

  it('finishes reporting the text it is leaving before showing the next one', async () => {
    const { w, ed } = await mountVisual('<p>A</p>', 'id:1|#1')
    ed.type('<p>A2</p>')
    await w.setProps({ docKey: 'id:2|#1', html: '<p>B</p>' })
    expect(w.emitted('change')).toEqual([['<p>A2</p>', 'id:1|#1']])
    expect(ed.getContent()).toBe('<p>B</p>')
  })

  it('saves on Cmd/Ctrl+S inside the editor, follows read-only, and cleans up', async () => {
    const { w, ed } = await mountVisual('<p>A</p>')
    const e = new KeyboardEvent('keydown', { key: 's', metaKey: true, cancelable: true })
    ed.fire('keydown', e)
    expect(e.defaultPrevented).toBe(true)
    expect(w.emitted('save')).toHaveLength(1)
    await w.setProps({ readonly: true })
    expect(ed.getBody().getAttribute('contenteditable')).toBe('false')
    w.unmount()
    expect(ed.remove).toHaveBeenCalled()
  })
})
