import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryStorage } from './memoryStorage'

// A participant page that was open while the server restarted holds a seat token the server has
// forgotten. It used to retry its websocket for ever, saying "Reconnecting…". Now the server says
// why it turns the page away (ClientController.connectClient) and the page acts on it; a short
// network drop still ends in the same seat.

const PAGE = '/game/1/2/p1/connected'
const SOCKET = 'ws://lab.example/game/1/2/p1/connect'
const CONFIG = { clientId: 'p1', connectSocket: SOCKET }
const SIGN_IN = { error: 'not-logged-in', login: '/game/1/2/login' }
/** What the server sends a page whose session it doesn't run (any more) */
// what the server sends a page it turns away whose session isn't running (ClientController.notRunningScreen)
const NOT_RUNNING = { sessionOver: { player: { _system: {}, finished: true, ended: 'notRunning', text: '<h3>This session isn\'t running right now</h3>' } } }

/** A WebSocket that the test, as the server, accepts, writes to and drops. */
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
  drop () { this.readyState = 3; this.fire('close') }
  /** The connection can't be made */
  fail () { this.readyState = 3; this.fire('error'); this.fire('close') }
  /** What the page sent, read back */
  sentFrames () { return this.sent.map(d => JSON.parse(d)) }
}

/** /state's answers, one per request (the last one repeats) */
function stateAnswers (...answers: [number, any][]) {
  let k = 0
  return vi.fn(async (_url: string, _init?: any) => {
    const [status, body] = answers[Math.min(k++, answers.length - 1)]
    return { status, json: async () => body }
  })
}

/** Let the requests and promises under way finish (the timers stay where they are). */
async function settle () {
  for (let k = 0; k < 20; k++) await Promise.resolve()
}

let location: any
/** The page under test, disconnected afterwards (so it stops listening to the window) */
let current: any = null

beforeEach(() => {
  FakeSocket.all = []
  vi.stubGlobal('WebSocket', FakeSocket)
  location = { pathname: PAGE, search: '', hash: '', href: 'http://lab.example' + PAGE, protocol: 'http:', host: 'lab.example' }
  vi.stubGlobal('location', location)
  vi.stubGlobal('localStorage', new MemoryStorage())
  document.body.innerHTML = '<div id="app">Loading…</div>'
  vi.useFakeTimers()
  vi.spyOn(console, 'log').mockImplementation(() => {})
  vi.spyOn(console, 'debug').mockImplementation(() => {})
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  if (current) current.disconnect()
  current = null
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/**
 * A newly loaded participant page: it gets its state, opens its socket, and the server takes it
 * (unless `accept` is false).
 */
async function openPage (fetch: ReturnType<typeof stateAnswers>, accept = true) {
  vi.stubGlobal('fetch', fetch)
  vi.resetModules()
  const { Breadboard, ENDED_EVENT, CONNECTION_EVENT } = await import('../src/breadboard')
  current = Breadboard
  const players: any[] = []
  const messages: string[] = []
  const connections: string[] = []
  let ended = 0
  Breadboard.on('player', (p: any) => players.push(p))
  Breadboard.on('message', (d: string) => messages.push(d))
  Breadboard.on(ENDED_EVENT, () => ended++)
  Breadboard.on(CONNECTION_EVENT, () => connections.push(Breadboard.connection))
  await Breadboard.connect()
  if (accept) FakeSocket.all[0].accept()
  return { Breadboard, players, messages, connections, ended: () => ended }
}

/** The server goes away and comes back: the page's next attempt is taken. */
async function reconnect (k: number) {
  FakeSocket.all[k - 1].drop()
  await vi.advanceTimersByTimeAsync(10000)
  FakeSocket.all[k].accept()
}

describe('a page from before 3.1, or the AngularJS client', () => {
  it('finds no player in the notice, so a completion code on its screen stays', async () => {
    // they read frame.player; the notice is under sessionOver
    expect((NOT_RUNNING as any).player).toBeUndefined()
  })
})

describe('a page whose session is over', () => {
  it('shows what the server says and never reconnects again', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    await reconnect(1)
    FakeSocket.all[1].frame(NOT_RUNNING)
    FakeSocket.all[1].drop()
    expect(page.players).toEqual([NOT_RUNNING.sessionOver.player])
    expect(page.ended()).toBe(1)
    expect(page.Breadboard.ended).toBe(true)
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(2)
  })

  it('keeps the participant\'s own end, with its completion code, on the screen', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    const done = { _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C0DE' } }, text: 'Thank you' }
    FakeSocket.all[0].frame({ player: done })
    await reconnect(1)
    FakeSocket.all[1].frame(NOT_RUNNING)
    FakeSocket.all[1].drop()
    expect(page.players).toEqual([done])
    expect(page.ended()).toBe(1)
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(2)
  })
})

describe('a page whose seat the server has forgotten, in a session that runs', () => {
  it('goes to the sign-in that the page\'s state names', async () => {
    const fetch = stateAnswers([200, CONFIG], [401, SIGN_IN])
    const page = await openPage(fetch)
    await reconnect(1)
    FakeSocket.all[1].frame({ reauth: true })
    FakeSocket.all[1].drop()
    await settle()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch.mock.calls[1][0]).toBe('state')
    expect(location.href).toBe('/game/1/2/login')
    expect(page.players).toEqual([])
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(2)
  })

  it('says a preview has ended instead', async () => {
    location.search = '?preview=1'
    location.href = 'http://lab.example' + PAGE + '?preview=1'
    await openPage(stateAnswers([200, CONFIG], [401, SIGN_IN]))
    FakeSocket.all[0].frame({ reauth: true })
    FakeSocket.all[0].drop()
    await settle()
    expect(location.href).toBe('http://lab.example' + PAGE + '?preview=1')
    expect(document.getElementById('app')!.textContent).toContain('Open it again from the researcher console')
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('keeps the participant\'s own end on the screen instead', async () => {
    const page = await openPage(stateAnswers([200, CONFIG], [401, SIGN_IN]))
    const done = { _system: {}, finished: true, text: 'Thank you. Your code is C0DE.' }
    FakeSocket.all[0].frame({ player: done })
    await reconnect(1)
    FakeSocket.all[1].frame({ reauth: true })
    FakeSocket.all[1].drop()
    await settle()
    expect(location.href).toBe('http://lab.example' + PAGE)
    expect(page.players).toEqual([done])
    expect(page.ended()).toBe(1)
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(2)
  })
})

describe('reconnecting', () => {
  it('after a short network drop, goes on in the same seat', async () => {
    const fetch = stateAnswers([200, CONFIG])
    const page = await openPage(fetch)
    FakeSocket.all[0].frame({ player: { _system: {}, text: 'Round 1' } })
    await reconnect(1)
    FakeSocket.all[1].frame({ player: { _system: {}, text: 'Round 1' } })
    expect(FakeSocket.all.map(s => s.url)).toEqual([SOCKET, SOCKET])
    expect(page.players.length).toBe(2)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(page.Breadboard.ended).toBe(false)
  })

  it('asks for the page\'s state again when the server closes it without a word three times', async () => {
    const fetch = stateAnswers([200, CONFIG], [401, SIGN_IN])
    await openPage(fetch)
    await reconnect(1)
    await reconnect(2)
    expect(fetch).toHaveBeenCalledTimes(1)
    FakeSocket.all[2].drop()
    await settle()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(location.href).toBe('/game/1/2/login')
  })

  it('goes on trying when the page\'s state still answers', async () => {
    const fetch = stateAnswers([200, CONFIG])
    const page = await openPage(fetch)
    await reconnect(1)
    await reconnect(2)
    await reconnect(3)
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(location.href).toBe('http://lab.example' + PAGE)
    FakeSocket.all[3].frame({ player: { _system: {}, text: 'Round 2' } })
    expect(page.players).toEqual([{ _system: {}, text: 'Round 2' }])
    expect(page.Breadboard.ended).toBe(false)
  })
})

describe('a page whose study was opened in another tab or window', () => {
  // What the server writes to the older socket before closing it, when a newer one takes the seat.
  // The two tabs used to take the seat from each other every few seconds, for ever.
  const REPLACED = { bbClosed: 'replaced' }

  it('stops reconnecting, and takes the seat back only when the participant asks', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ player: { _system: {}, text: 'Round 1' } })
    FakeSocket.all[0].frame(REPLACED)
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('elsewhere')
    expect(page.Breadboard.replaced).toBe(true)
    expect(page.connections).toContain('elsewhere')
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    // nor when the network or the page comes back, or the participant tries again
    window.dispatchEvent(new Event('online'))
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    page.Breadboard.reconnectNow()
    expect(FakeSocket.all.length).toBe(1)
    expect(page.Breadboard.ended).toBe(false)

    // "Use it here": one new connection, at once (the other tab is told in turn)
    page.Breadboard.useHere()
    expect(FakeSocket.all.length).toBe(2)
    expect(page.Breadboard.replaced).toBe(false)
    FakeSocket.all[1].accept()
    expect(page.Breadboard.connection).toBe('open')
    FakeSocket.all[1].frame({ player: { _system: {}, text: 'Round 1' } })
    expect(page.players.length).toBe(2)
    // and from then on it reconnects as before
    FakeSocket.all[1].drop()
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeSocket.all.length).toBe(3)
  })

  it('keeps the seat it was given back while the other tab is told', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(REPLACED)
    // asked before the server has even closed the old socket
    page.Breadboard.useHere()
    expect(FakeSocket.all.length).toBe(2)
    FakeSocket.all[0].drop()
    FakeSocket.all[1].accept()
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(FakeSocket.all.length).toBe(2)
    expect(page.Breadboard.connection).toBe('open')
  })
})

describe('the page\'s connection, as the participant hears of it', () => {
  it('says when it opens, drops and opens again', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]), false)
    expect(page.Breadboard.connection).toBe('connecting')
    expect(page.Breadboard.isOpen).toBe(false)
    FakeSocket.all[0].accept()
    expect(page.Breadboard.connection).toBe('open')
    expect(page.Breadboard.isOpen).toBe(true)
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('lost')
    await vi.advanceTimersByTimeAsync(1000)
    FakeSocket.all[1].accept()
    expect(page.connections).toEqual(['connecting', 'open', 'lost', 'lost', 'open'])
  })

  it('says the network may block it when it has never opened after three attempts', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]), false)
    FakeSocket.all[0].fail()
    expect(page.Breadboard.connection).toBe('lost')
    for (let k = 1; k < 3; k++) {
      await vi.advanceTimersByTimeAsync(5000)
      FakeSocket.all[k].fail()
    }
    expect(page.Breadboard.connection).toBe('blocked')
    // it goes on trying, and the participant can try at once
    page.Breadboard.reconnectNow()
    expect(FakeSocket.all.length).toBe(4)
    FakeSocket.all[3].accept()
    expect(page.Breadboard.connection).toBe('open')
  })

  it('tries again at once when the participant asks', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].drop()
    expect(FakeSocket.all.length).toBe(1)
    page.Breadboard.reconnectNow()
    expect(FakeSocket.all.length).toBe(2)
  })

  it('says when the session is over for the page, and tries no more', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(NOT_RUNNING)
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('ended')
    page.Breadboard.reconnectNow()
    page.Breadboard.useHere()
    window.dispatchEvent(new Event('online'))
    expect(FakeSocket.all.length).toBe(1)
  })
})

describe('the heartbeat', () => {
  const PING = '{"action":"Ping"}'

  it('finds a connection that died without closing, and connects again at once in the same seat', async () => {
    const fetch = stateAnswers([200, CONFIG])
    const page = await openPage(fetch)
    await vi.advanceTimersByTimeAsync(5000)
    expect(FakeSocket.all[0].sent).toEqual([PING])
    FakeSocket.all[0].frame({ pong: 1761700000000 })
    // the answer is the page's own business
    expect(page.messages).toEqual([])
    expect(page.players).toEqual([])
    // the network goes away without a word: no banner, no new connection, until now
    await vi.advanceTimersByTimeAsync(14000)
    expect(FakeSocket.all.length).toBe(1)
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeSocket.all.length).toBe(2)
    expect(page.Breadboard.connection).toBe('lost')
    FakeSocket.all[1].accept()
    FakeSocket.all[1].frame({ player: { _system: {}, text: 'Round 1' } })
    expect(page.players.length).toBe(1)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('keeps a connection to a server that doesn\'t answer (3.0), in a quiet waiting room too', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    await vi.advanceTimersByTimeAsync(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(page.Breadboard.connection).toBe('open')
  })

  it('is left out of the previous console\'s socket, whose server would answer with an error', async () => {
    await openPage(stateAnswers([200, { uid: 'u1', connectSocket: 'ws://lab.example/connect' }]))
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(FakeSocket.all[0].sent).toEqual([])
  })
})

describe('answers given while the connection is down', () => {
  it('are dropped once they have waited 5 s for it; the page\'s other messages still go', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ player: { _system: {}, text: 'Round 1' } })
    FakeSocket.all[0].drop()
    page.Breadboard.sendChoice('stale-choice')
    page.Breadboard.sendGroupChoice('stale-group-choice')
    await page.Breadboard.sendEdgeEdit('remove', 'na', 'nb')
    page.Breadboard.sendType('LogIn', { clientId: 'p1' })
    await vi.advanceTimersByTimeAsync(6000)
    page.Breadboard.sendChoice('fresh-choice')
    FakeSocket.all[1].accept()
    const sent = FakeSocket.all[1].sentFrames()
    expect(sent.map((m: any) => m.action)).toEqual(['LogIn', 'MakeChoice'])
    expect(sent[1].choiceUID).toBe('fresh-choice')
  })
})

describe('the token kept for a browser that comes back', () => {
  const KEY = 'breadboard-token:' + PAGE
  const keep = () => localStorage.setItem(KEY, JSON.stringify({ token: 'tok', until: Date.now() + 60 * 60 * 1000 }))

  it('is forgotten when the session is over for the page', async () => {
    keep()
    await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(NOT_RUNNING)
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('is forgotten when the server no longer takes it', async () => {
    keep()
    await openPage(stateAnswers([200, CONFIG], [401, SIGN_IN]))
    FakeSocket.all[0].frame({ reauth: true })
    await settle()
    expect(location.href).toBe('/game/1/2/login')
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('is kept while the page only reconnects', async () => {
    keep()
    await openPage(stateAnswers([200, CONFIG]))
    await reconnect(1)
    FakeSocket.all[1].frame({ player: { _system: {}, text: 'Round 1' } })
    expect(JSON.parse(localStorage.getItem(KEY)!).token).toBe('tok')
  })
})
