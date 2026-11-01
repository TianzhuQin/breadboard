import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryStorage } from './memoryStorage'

// Breadboard's own words on a participant's page (its banners, waiting screen, network and finish
// screens) follow the language of the session's texts, which the steps choose with
// c.setDefaultLanguage. The page learns it from its state (/state's "language"), and from the server
// as it connects and whenever the steps choose another ({"bbLanguage": code}): Breadboard.language,
// and LANGUAGE_EVENT when it changes. The page's lang follows, for screen readers and Chinese fonts.

const PAGE = '/game/1/2/p1/connected'
const SOCKET = 'ws://lab.example/game/1/2/p1/connect'

/** A WebSocket that the test, as the server, accepts and writes to. */
class FakeSocket {
  static OPEN = 1
  static all: FakeSocket[] = []
  readyState = 0
  sent: any[] = []
  private on: Record<string, ((ev: any) => void)[]> = {}
  constructor (public url: string) { FakeSocket.all.push(this) }
  addEventListener (type: string, fn: (ev: any) => void) { (this.on[type] ||= []).push(fn) }
  send (data: any) { this.sent.push(data) }
  close () { this.readyState = 3; setTimeout(() => this.fire('close'), 0) }
  fire (type: string, ev: any = {}) { for (const fn of this.on[type] || []) fn(ev) }
  accept () { this.readyState = 1; this.fire('open') }
  frame (data: any) { this.fire('message', { data: JSON.stringify(data) }) }
}

let current: any = null

beforeEach(() => {
  FakeSocket.all = []
  vi.stubGlobal('WebSocket', FakeSocket)
  vi.stubGlobal('BroadcastChannel', class { onmessage = null; postMessage () {} close () {} })
  const location = { pathname: PAGE, search: '', hash: '', href: 'http://lab.example' + PAGE, protocol: 'http:', host: 'lab.example' }
  vi.stubGlobal('location', location)
  vi.stubGlobal('localStorage', new MemoryStorage())
  document.documentElement.lang = 'en'
  vi.useFakeTimers()
  vi.spyOn(console, 'log').mockImplementation(() => {})
  vi.spyOn(console, 'debug').mockImplementation(() => {})
})

afterEach(() => {
  if (current) current.disconnect()
  current = null
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** A participant's page whose state says `state`, connected; what it heard of the language. */
async function openPage (state: any) {
  vi.stubGlobal('fetch', vi.fn(async () => ({ status: 200, json: async () => ({ clientId: 'p1', connectSocket: SOCKET, ...state }) })))
  vi.resetModules()
  const { Breadboard, LANGUAGE_EVENT } = await import('../src/breadboard')
  current = Breadboard
  const heard: any[] = []
  const styles: string[] = []
  Breadboard.on(LANGUAGE_EVENT, (code: any) => heard.push(code))
  Breadboard.on('style', (css: string) => styles.push(css))
  await Breadboard.connect()
  FakeSocket.all[0].accept()
  return { Breadboard, heard, styles, socket: FakeSocket.all[0] }
}

describe('the language of the session\'s texts', () => {
  it('comes with the page\'s state, and the page\'s lang follows', async () => {
    const { Breadboard, heard } = await openPage({ language: 'zho' })
    expect(Breadboard.language).toBe('zho')
    expect(heard).toEqual(['zho'])
    expect(document.documentElement.lang).toBe('zh-CN')
  })

  it('changes when the server says so, also next to the experiment\'s style as the page connects', async () => {
    const { Breadboard, heard, styles, socket } = await openPage({ language: null })
    expect(Breadboard.language).toBe(null)
    expect(heard).toEqual([])
    expect(document.documentElement.lang).toBe('en')

    socket.frame({ style: '.bb-app { color: red }', bbLanguage: 'zho' })
    expect(Breadboard.language).toBe('zho')
    expect(heard).toEqual(['zho'])
    expect(styles).toEqual(['.bb-app { color: red }'])
    expect(document.documentElement.lang).toBe('zh-CN')

    // (the same again: nothing to tell)
    socket.frame({ bbLanguage: 'zho' })
    expect(heard).toEqual(['zho'])

    socket.frame({ bbLanguage: 'spa' })
    expect(Breadboard.language).toBe('spa')
    expect(heard).toEqual(['zho', 'spa'])
    expect(document.documentElement.lang).toBe('en')

    socket.frame({ bbLanguage: null })
    expect(Breadboard.language).toBe(null)
    expect(heard).toEqual(['zho', 'spa', null])
  })

  it('names the page\'s lang for any code of Chinese', async () => {
    const { htmlLangOf } = await import('../src/breadboard')
    for (const code of ['zho', 'ZHO', 'chi', 'cmn', 'zh', 'zh-CN', 'zh_TW']) expect(htmlLangOf(code)).toBe('zh-CN')
    for (const code of ['eng', 'spa', '', null, undefined, 'zhx']) expect(htmlLangOf(code)).toBe('en')
  })
})
