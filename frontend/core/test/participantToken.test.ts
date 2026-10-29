import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryStorage } from './memoryStorage'

// The participant page has to work in an opaque origin: the researcher console's previews run
// in a sandboxed iframe without allow-same-origin (or a tab the server sandboxes with CSP), where
// reading session storage, local storage or document.cookie throws. Browsers that block storage
// (all of it, or for pages embedded in another site) do the same in an ordinary origin.

const PAGE = '/game/1/2/abc/connected'

/** Make storage and cookies throw the way a sandboxed document's do; returns the undo. */
function blockStorage () {
  const refuse = () => {
    throw new DOMException('The document is sandboxed and lacks the "allow-same-origin" flag.', 'SecurityError')
  }
  const saved = ['sessionStorage', 'localStorage'].map(name => [name, Object.getOwnPropertyDescriptor(window, name)] as const)
  for (const [name] of saved) {
    Object.defineProperty(window, name, { configurable: true, get: refuse })
  }
  Object.defineProperty(document, 'cookie', { configurable: true, get: refuse, set: refuse })
  return () => {
    for (const [name, descriptor] of saved) {
      if (descriptor) Object.defineProperty(window, name, descriptor)
      else delete (window as any)[name]
    }
    delete (document as any).cookie
  }
}

/** The core module as a newly loaded page sees it (nothing remembered in memory). */
async function freshCore () {
  vi.resetModules()
  return await import('../src/breadboard')
}

/** A WebSocket that only records where it was opened. */
class FakeSocket {
  static urls: string[] = []
  static OPEN = 1
  readyState = 0
  constructor (public url: string) { FakeSocket.urls.push(url) }
  addEventListener () {}
  send () {}
  close () {}
}

function answer (status: number, body: any) {
  return vi.fn(async (_url: string, _init?: any) => ({ status, json: async () => body }))
}

let undo: (() => void) | null = null

beforeEach(() => {
  window.history.replaceState(null, '', '/')
  try { window.sessionStorage.clear() } catch (e) {}
  vi.stubGlobal('localStorage', new MemoryStorage())
  document.body.innerHTML = '<div id="app">Loading…</div>'
  FakeSocket.urls = []
  vi.spyOn(console, 'log').mockImplementation(() => {})
})

afterEach(() => {
  if (undo) undo()
  undo = null
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('participantToken', () => {
  it('keeps the fragment token in memory where storage and cookies throw', async () => {
    window.history.replaceState(null, '', PAGE + '?preview=1#bbt=tok&x=1')
    undo = blockStorage()
    expect(() => window.sessionStorage).toThrow()
    expect(() => window.localStorage).toThrow()
    expect(() => document.cookie).toThrow()
    const { participantToken } = await freshCore()
    expect(participantToken()).toBe('tok')
    // dropped from the address, the rest of the fragment kept
    expect(window.location.hash).toBe('#x=1')
    expect(window.location.search).toBe('?preview=1')
    expect(participantToken()).toBe('tok')
  })

  // (in an opaque origin browsers drop the history entry's state on a reload: see showPreviewEnded)
  it('finds the token again after a reload where storage is blocked', async () => {
    window.history.replaceState(null, '', PAGE + '?preview=1#bbt=tok')
    undo = blockStorage()
    expect((await freshCore()).participantToken()).toBe('tok')
    expect(window.location.hash).toBe('')
    // a reload: the same history entry, a new copy of the script
    expect((await freshCore()).participantToken()).toBe('tok')
  })

  it('finds the token again after a reload from session storage where it exists', async () => {
    window.history.replaceState(null, '', PAGE + '#bbt=tok')
    expect((await freshCore()).participantToken()).toBe('tok')
    expect(window.sessionStorage.getItem('breadboard-token:' + PAGE)).toBe('tok')
    window.history.replaceState(null, '', PAGE)
    expect((await freshCore()).participantToken()).toBe('tok')
  })

  it('keeps working when the browser refuses to rewrite the address', async () => {
    window.history.replaceState(null, '', PAGE + '#bbt=tok')
    undo = blockStorage()
    vi.spyOn(window.history, 'replaceState').mockImplementation(() => {
      throw new DOMException('refused', 'SecurityError')
    })
    const { participantToken } = await freshCore()
    expect(participantToken()).toBe('tok')
    expect(participantToken()).toBe('tok')
  })

  it('gives none to a page opened without one', async () => {
    window.history.replaceState(null, '', PAGE)
    undo = blockStorage()
    expect((await freshCore()).participantToken()).toBe(null)
  })
})

describe('Breadboard in a sandboxed preview', () => {
  it('sends the token to /state and opens the websocket address /state gives', async () => {
    window.history.replaceState(null, '', PAGE + '?preview=1#bbt=tok')
    undo = blockStorage()
    const connectSocket = 'ws://lab.example/game/1/2/abc/connect?bbt=tok'
    const fetch = answer(200, { clientId: 'abc', connectSocket })
    vi.stubGlobal('fetch', fetch)
    vi.stubGlobal('WebSocket', FakeSocket)
    const { Breadboard } = await freshCore()
    await Breadboard.connect()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0]).toBe('state')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', headers: { 'X-Breadboard-Token': 'tok' } })
    expect(FakeSocket.urls).toEqual([connectSocket])
    Breadboard.disconnect()
  })

  it('says the preview has ended instead of going to the participant login', async () => {
    const location = { pathname: PAGE, search: '?preview=1', hash: '', href: 'http://lab.example' + PAGE + '?preview=1', protocol: 'http:', host: 'lab.example' }
    vi.stubGlobal('location', location)
    undo = blockStorage()
    vi.stubGlobal('fetch', answer(401, { error: 'not-logged-in', login: '/login/1/2' }))
    const { Breadboard } = await freshCore()
    await expect(Breadboard.loadConfig()).rejects.toThrow()
    expect(location.href).toBe('http://lab.example' + PAGE + '?preview=1')
    expect(document.getElementById('app')!.textContent).toContain('Open it again from the researcher console')
  })

  it('still sends a participant without credentials to the login', async () => {
    const location = { pathname: PAGE, search: '', hash: '', href: 'http://lab.example' + PAGE, protocol: 'http:', host: 'lab.example' }
    vi.stubGlobal('location', location)
    vi.stubGlobal('fetch', answer(401, { error: 'not-logged-in', login: '/login/1/2' }))
    const { Breadboard } = await freshCore()
    await expect(Breadboard.loadConfig()).rejects.toThrow('Not logged in')
    expect(location.href).toBe('/login/1/2')
  })
})

// A participant whose browser was closed or crashed couldn't get back to their seat: the page kept
// its token only in session storage, which ends with the tab.
describe('participantToken in a browser that comes back', () => {
  const KEY = 'breadboard-token:' + PAGE

  it('keeps a participant\'s token in local storage too, and finds it there', async () => {
    window.history.replaceState(null, '', PAGE + '#bbt=tok')
    expect((await freshCore()).participantToken()).toBe('tok')
    expect(JSON.parse(localStorage.getItem(KEY)!).token).toBe('tok')
    // the browser was closed: the tab's session storage and history entry are gone
    window.sessionStorage.clear()
    window.history.replaceState(null, '', PAGE)
    expect((await freshCore()).participantToken()).toBe('tok')
  })

  it('keeps it for a day, as long as the server\'s cookie', async () => {
    vi.useFakeTimers()
    window.history.replaceState(null, '', PAGE + '#bbt=tok')
    expect((await freshCore()).participantToken()).toBe('tok')
    window.sessionStorage.clear()
    window.history.replaceState(null, '', PAGE)
    vi.setSystemTime(Date.now() + 23 * 60 * 60 * 1000)
    expect((await freshCore()).participantToken()).toBe('tok')
    vi.setSystemTime(Date.now() + 2 * 60 * 60 * 1000)
    expect((await freshCore()).participantToken()).toBe(null)
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('prefers the tab\'s own token', async () => {
    localStorage.setItem(KEY, JSON.stringify({ token: 'kept', until: Date.now() + 60000 }))
    window.history.replaceState(null, '', PAGE)
    window.sessionStorage.setItem(KEY, 'mine')
    expect((await freshCore()).participantToken()).toBe('mine')
  })

  it('keeps no preview\'s token, and a preview never takes a participant\'s', async () => {
    window.history.replaceState(null, '', PAGE + '?preview=1#bbt=preview-tok')
    expect((await freshCore()).participantToken()).toBe('preview-tok')
    expect(localStorage.getItem(KEY)).toBe(null)
    // a participant's seat on the same page, kept in this browser
    localStorage.setItem(KEY, JSON.stringify({ token: 'participant-tok', until: Date.now() + 60000 }))
    window.sessionStorage.clear()
    window.history.replaceState(null, '', PAGE + '?preview=1')
    expect((await freshCore()).participantToken()).toBe(null)
  })

  it('forgets it when the server no longer takes it', async () => {
    const location = { pathname: PAGE, search: '', hash: '', href: 'http://lab.example' + PAGE, protocol: 'http:', host: 'lab.example' }
    vi.stubGlobal('location', location)
    localStorage.setItem(KEY, JSON.stringify({ token: 'stale', until: Date.now() + 60000 }))
    vi.stubGlobal('fetch', answer(401, { error: 'not-logged-in', login: '/login/1/2' }))
    const { Breadboard } = await freshCore()
    await expect(Breadboard.loadConfig()).rejects.toThrow('Not logged in')
    expect(location.href).toBe('/login/1/2')
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('does without where local storage throws', async () => {
    window.history.replaceState(null, '', PAGE + '#bbt=tok')
    undo = blockStorage()
    expect((await freshCore()).participantToken()).toBe('tok')
  })
})
