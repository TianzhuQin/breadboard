import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import {
  altFromName, checkUploadResponse, fileProblem, findImageUsage, imageLinks, imageSrc, imgTag, pastedName,
} from '../src/pages/images/images'

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
  store.admin = reactive({ experiment: null, instance: null })
  return { admin: store.admin, actions: { refresh: store.refresh } }
})

describe('checkUploadResponse (the server says "Error uploading" with HTTP 200)', () => {
  it('accepts an answer with an id and a file name', () => {
    expect(checkUploadResponse({ id: 12, fileName: 'logo.png' })).toEqual({ ok: true, image: { id: 12, fileName: 'logo.png' } })
    expect(checkUploadResponse({ id: '12', fileName: 'logo.png' })).toEqual({ ok: true, image: { id: 12, fileName: 'logo.png' } })
  })
  it('treats "Error uploading" as a failure', () => {
    const r = checkUploadResponse('Error uploading')
    expect(r.ok).toBe(false)
    expect(!r.ok && r.reason).toMatch(/couldn’t store/)
  })
  it('treats any other text, like an HTML error page, as a failure and quotes it readably', () => {
    const r = checkUploadResponse('<html><body><h1>Oops</h1><p>Something broke</p></body></html>')
    expect(r.ok).toBe(false)
    expect(!r.ok && r.reason).toMatch(/“Oops Something broke”/)
  })
  it('treats an answer without an id or name as a failure', () => {
    for (const body of [null, undefined, '', {}, [], { id: null, fileName: 'x.png' }, { id: 3 }, { id: 3, fileName: '' }, { id: 'abc', fileName: 'x' }]) {
      expect(checkUploadResponse(body).ok).toBe(false)
    }
    const r = checkUploadResponse({ message: 'No experiment' })
    expect(!r.ok && r.reason).toMatch(/No experiment/)
  })
})

describe('image links', () => {
  it('builds absolute links and encodes the file name', () => {
    const l = imageLinks('https://lab.org', 7, { id: 41, fileName: 'my photo #1?.png' })
    expect(l.byName).toBe('https://lab.org/images/7/my%20photo%20%231%3F.png')
    expect(l.byId).toBe('https://lab.org/images/41')
    expect(l.tag).toBe('<img src="https://lab.org/images/7/my%20photo%20%231%3F.png" alt="my photo #1?">')
  })
  it('has no ID link in file mode (images have no id there)', () => {
    const l = imageLinks('http://x', 7, { id: null, fileName: 'a.png' })
    expect(l.byId).toBeNull()
    expect(imageSrc(7, { id: null, fileName: 'a b.png' })).toBe('/images/7/a%20b.png')
    expect(imageSrc(7, { id: 3, fileName: 'a b.png' })).toBe('/images/3')
  })
  it('escapes the HTML tag', () => {
    expect(imgTag('x"y', 'a"b<c>.png')).toBe('<img src="x&quot;y" alt="a&quot;b&lt;c&gt;">')
    expect(altFromName('team-photo_2.png')).toBe('team photo 2')
  })
})

describe('findImageUsage', () => {
  const exp = {
    id: 7,
    style: '.bg { background: url(/images/7/bg.png) }',
    steps: [
      { id: 1, name: 'InitStep', source: 'player.text = "<img src=\'/images/12\'>"' },
      { id: 2, name: 'Other', source: 'see /images/120 and /images/7/logo.png2' },
    ],
    content: [
      { id: 1, name: 'Welcome', translations: [{ id: 1, html: '<img src="http://h/images/7/my%20logo.png">', language: { id: 1, code: 'en', name: 'English' } }] },
    ],
  }
  it('finds links by id and by (encoded) name, but not look-alikes', () => {
    expect(findImageUsage(exp as any, { id: 12, fileName: 'x.png' })).toEqual(['the step InitStep'])
    expect(findImageUsage(exp as any, { id: 99, fileName: 'my logo.png' })).toEqual(['the content “Welcome”'])
    expect(findImageUsage(exp as any, { id: 98, fileName: 'bg.png' })).toEqual(['the participant page style'])
    expect(findImageUsage(exp as any, { id: 1, fileName: 'logo.png' })).toEqual([])
  })
})

describe('fileProblem', () => {
  it('lets images through and says why others are refused', () => {
    expect(fileProblem({ name: 'a.png', type: 'image/png', size: 10 })).toBeNull()
    expect(fileProblem({ name: 'a.webp', type: '', size: 10 })).toBeNull()
    expect(fileProblem({ name: 'notes.pdf', type: 'application/pdf', size: 10 })).toMatch(/not an image/)
    expect(fileProblem({ name: 'a.png', type: 'image/png', size: 0 })).toMatch(/empty/)
    expect(fileProblem({ name: `${'a'.repeat(260)}.png`, type: 'image/png', size: 3 })).toMatch(/too long/)
  })
  it('names pasted screenshots by date', () => {
    expect(pastedName('image/png', new Date(2026, 9, 27, 9, 5, 3))).toBe('pasted-20261027-090503.png')
    expect(pastedName('image/jpeg', new Date(2026, 0, 1))).toMatch(/\.jpg$/)
  })
})

describe('ImagesPage upload', () => {
  beforeEach(async () => {
    await import('@/store/admin') // runs the mock factory, which creates store.admin
    http.post.mockReset()
    store.refresh.mockReset()
    store.refresh.mockResolvedValue({ user: {} })
    store.admin.experiment = reactive({ id: 7, name: 'Trust game', fileMode: false, images: [], steps: [], content: [], style: '' })
  })

  async function pick (files: File[]) {
    const { default: ImagesPage } = await import('../src/pages/images/ImagesPage.vue')
    const w = mount(ImagesPage, { attachTo: document.body })
    const input = w.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: files, configurable: true })
    await input.trigger('change')
    await flushPromises()
    return w
  }
  const png = (name: string) => new File([new Uint8Array([137, 80, 78, 71])], name, { type: 'image/png' })

  it('does not add an image when the server answers "Error uploading"', async () => {
    http.post.mockResolvedValue('Error uploading')
    const w = await pick([png('logo.png')])
    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, form] = http.post.mock.calls[0]
    expect(url).toBe('/images/upload')
    expect((form as FormData).get('experimentId')).toBe('7')
    expect(((form as FormData).get('picture[file]') as File).name).toBe('logo.png')
    expect(w.findAll('.ic')).toHaveLength(0)
    expect(w.find('.ip-up.is-failed').text()).toMatch(/couldn’t store this file/)
    expect(store.refresh).not.toHaveBeenCalled()
    w.unmount()
  })

  it('adds the image the server saved, under the name it chose', async () => {
    http.post.mockResolvedValue({ id: 31, fileName: 'logo-2.png' })
    const w = await pick([png('logo.png')])
    expect(w.findAll('.ic')).toHaveLength(1)
    expect(w.find('.ic__name').text()).toBe('logo-2.png')
    expect(w.find('.ip-up.is-done').text()).toMatch(/Saved as logo-2.png, because logo.png already exists/)
    expect(store.refresh).toHaveBeenCalled()
    w.unmount()
  })

  it('refuses files that are not images before sending them', async () => {
    const w = await pick([new File(['x'], 'notes.txt', { type: 'text/plain' })])
    expect(http.post).not.toHaveBeenCalled()
    expect(w.find('.ip-up.is-failed').text()).toMatch(/not an image/)
    w.unmount()
  })

  it('offers no upload or delete in file mode', async () => {
    store.admin.experiment = reactive({ id: 7, name: 'T', fileMode: true, images: [{ id: null, fileName: 'a.png' }], steps: [], content: [], style: '' })
    const { default: ImagesPage } = await import('../src/pages/images/ImagesPage.vue')
    const w = mount(ImagesPage, { attachTo: document.body })
    expect(w.text()).toMatch(/file mode/)
    expect(w.find('.ip-drop').exists()).toBe(false)
    expect(w.findAll('.ic')).toHaveLength(1)
    w.unmount()
  })
})
