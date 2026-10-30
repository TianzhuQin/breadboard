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

/** BroadcastChannels of one browser: what one posts, the others of that name hear (not itself) */
class FakeChannel {
  static all: FakeChannel[] = []
  onmessage: ((ev: any) => void) | null = null
  closed = false
  constructor (public name: string) { FakeChannel.all.push(this) }
  postMessage (data: any) {
    for (const c of FakeChannel.all) {
      if (c !== this && !c.closed && c.name === this.name && c.onmessage) c.onmessage({ data })
    }
  }
  close () { this.closed = true }
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
  FakeChannel.all = []
  vi.stubGlobal('BroadcastChannel', FakeChannel)
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
  delete (document as any).visibilityState
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

/** Let the clock run until the page makes its next attempt (at most the longest wait), and return it. */
async function nextAttempt () {
  const n = FakeSocket.all.length
  for (let t = 0; t <= 31000 && FakeSocket.all.length === n; t += 100) await vi.advanceTimersByTimeAsync(100)
  return FakeSocket.all[FakeSocket.all.length - 1]
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

  it('tells the server when it was loaded as it connects, and asks for the seat only after "Use it here"', async () => {
    // (so that an older tab reconnecting by itself doesn't take the seat from the newer one)
    const page = await openPage(stateAnswers([200, { ...CONFIG, connectSocket: SOCKET + '?bbt=tok', loadedAt: 1761800000000 }]))
    expect(FakeSocket.all[0].url).toBe(SOCKET + '?bbt=tok&bbPage=1761800000000')
    FakeSocket.all[0].frame(REPLACED)
    FakeSocket.all[0].drop()
    page.Breadboard.useHere()
    expect(FakeSocket.all[1].url).toBe(SOCKET + '?bbt=tok&bbPage=1761800000000&bbTake=1')
    // the participant's choice holds until a connection opens, and no longer
    FakeSocket.all[1].fail()
    expect((await nextAttempt()).url).toBe(SOCKET + '?bbt=tok&bbPage=1761800000000&bbTake=1')
    FakeSocket.all[2].accept()
    await reconnect(3)
    expect(FakeSocket.all[3].url).toBe(SOCKET + '?bbt=tok&bbPage=1761800000000')
  })

  it('leaves the socket\'s address as it is when its state doesn\'t say when it was loaded', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(REPLACED)
    FakeSocket.all[0].drop()
    page.Breadboard.useHere()
    expect(FakeSocket.all[1].url).toBe(SOCKET + '?bbTake=1')
    expect(FakeSocket.all[0].url).toBe(SOCKET)
  })

  // Its state names the seat (/state gives these), and so does the channel its pages share.
  const SEATED = { ...CONFIG, experimentId: '1', experimentInstanceId: '2' }
  const CHANNEL = 'bb-seat:1:2:p1'
  /** The page goes to 'elsewhere', as when the participant opens the study in another tab */
  async function replacedPage (...seat: [number, any][]) {
    const fetch = stateAnswers([200, SEATED], ...seat)
    const page = await openPage(fetch)
    FakeSocket.all[0].frame(REPLACED)
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('elsewhere')
    return { page, fetch }
  }
  /** The seat checks the page made (/state?seat=1) */
  const seatChecks = (fetch: any) => fetch.mock.calls.filter((c: any[]) => c[0] === 'state?seat=1').length
  function setVisibility (state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('takes the seat back by itself once the other tab has gone, as the other tab says it goes', async () => {
    const { page, fetch } = await replacedPage([200, { seatHeld: false }])
    // the newer tab, which holds the seat, is closed
    new FakeChannel(CHANNEL).postMessage({ bb: 'released' })
    await vi.advanceTimersByTimeAsync(1900)
    expect(seatChecks(fetch)).toBe(0)
    // (by then, a reload of that tab would have taken the seat again)
    await vi.advanceTimersByTimeAsync(100)
    await settle()
    expect(seatChecks(fetch)).toBe(1)
    expect(FakeSocket.all.length).toBe(2)
    expect(FakeSocket.all[1].url).toBe(SOCKET + '?bbTake=1')
    FakeSocket.all[1].accept()
    expect(page.Breadboard.connection).toBe('open')
    // and asks no more
    await vi.advanceTimersByTimeAsync(5 * 60 * 1000)
    expect(seatChecks(fetch)).toBe(1)
  })

  it('stays elsewhere while a page of theirs still holds the seat, and asks again every 20 s', async () => {
    const { page, fetch } = await replacedPage([200, { seatHeld: true }], [200, { seatHeld: true }], [200, { seatHeld: false }])
    new FakeChannel(CHANNEL).postMessage({ bb: 'released' })
    await vi.advanceTimersByTimeAsync(2000)
    await settle()
    expect(seatChecks(fetch)).toBe(1)
    expect(FakeSocket.all.length).toBe(1)
    expect(page.Breadboard.connection).toBe('elsewhere')
    // (e.g. a page in another browser, which tells this one nothing, goes)
    await vi.advanceTimersByTimeAsync(18000)
    await settle()
    expect(seatChecks(fetch)).toBe(2)
    expect(FakeSocket.all.length).toBe(1)
    await vi.advanceTimersByTimeAsync(20000)
    await settle()
    expect(seatChecks(fetch)).toBe(3)
    expect(FakeSocket.all.length).toBe(2)
    expect(FakeSocket.all[1].url).toBe(SOCKET + '?bbTake=1')
  })

  it('takes the seat back only once it is on screen', async () => {
    const { fetch } = await replacedPage([200, { seatHeld: false }])
    setVisibility('hidden')
    new FakeChannel(CHANNEL).postMessage({ bb: 'released' })
    await vi.advanceTimersByTimeAsync(60 * 1000)
    await settle()
    expect(seatChecks(fetch)).toBe(0)
    expect(FakeSocket.all.length).toBe(1)
    setVisibility('visible')
    await settle()
    expect(seatChecks(fetch)).toBe(1)
    expect(FakeSocket.all.length).toBe(2)
  })

  it('tells the participant\'s other pages as it goes, while it holds the seat', async () => {
    const heard: any[] = []
    const other = new FakeChannel(CHANNEL)
    other.onmessage = ev => heard.push(ev.data)
    const page = await openPage(stateAnswers([200, SEATED]))
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false }))
    expect(heard).toEqual([{ bb: 'released' }])
    // not once another page has the seat
    FakeSocket.all[0].frame(REPLACED)
    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false }))
    expect(heard.length).toBe(1)
    expect(page.Breadboard.connection).toBe('elsewhere')
  })

  it('leaves a preview\'s seat to the console', async () => {
    location.search = '?preview=1'
    location.href = 'http://lab.example' + PAGE + '?preview=1'
    const { fetch } = await replacedPage([200, { seatHeld: false }])
    expect(FakeChannel.all.length).toBe(0)
    await vi.advanceTimersByTimeAsync(60 * 1000)
    await settle()
    expect(seatChecks(fetch)).toBe(0)
    expect(FakeSocket.all.length).toBe(1)
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

  it('says the network may block it when, having been open, it can\'t open again while the server answers over HTTP', async () => {
    const fetch = stateAnswers([200, CONFIG])
    const page = await openPage(fetch)
    FakeSocket.all[0].drop()
    for (let k = 0; k < 4; k++) (await nextAttempt()).fail()
    expect(page.Breadboard.connection).toBe('lost')
    expect(fetch).toHaveBeenCalledTimes(1)
    // the fifth in a row: the page asks the server over plain HTTP, which answers, and tries the
    // live connection again at once (a server that has just come back would take it)
    ;(await nextAttempt()).fail()
    await settle()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(FakeSocket.all.length).toBe(7)
    expect(page.Breadboard.connection).toBe('lost')
    FakeSocket.all[6].fail()
    expect(page.Breadboard.connection).toBe('blocked')
    // it goes on trying, and is open once the network lets it through
    await settle()
    ;(await nextAttempt()).accept()
    expect(page.Breadboard.connection).toBe('open')
    // (blocked until then: the next attempt is still under it)
    const since = page.connections.slice(page.connections.indexOf('blocked'))
    expect(since.filter(c => c !== 'blocked')).toEqual(['open'])
  })

  it('takes the attempt right after the server answers over HTTP, when the server has just come back', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].drop()
    for (let k = 0; k < 5; k++) (await nextAttempt()).fail()
    await settle()
    FakeSocket.all[6].accept()
    expect(page.Breadboard.connection).toBe('open')
    expect(page.connections).not.toContain('blocked')
  })

  it('stays lost while the server doesn\'t answer over HTTP either: it is down', async () => {
    let calls = 0
    const fetch = vi.fn(async () => {
      if (calls++ === 0) return { status: 200, json: async () => CONFIG }
      throw new TypeError('Failed to fetch')
    })
    const page = await openPage(fetch as any)
    FakeSocket.all[0].drop()
    for (let k = 0; k < 8; k++) {
      ;(await nextAttempt()).fail()
      await settle()
    }
    expect(fetch.mock.calls.length).toBe(5)
    expect(page.Breadboard.connection).toBe('lost')
    expect(page.connections).not.toContain('blocked')
  })

  it('takes a proxy\'s "bad gateway" for a server that is down, and says it is blocked no more once the server is', async () => {
    const page = await openPage(stateAnswers([200, CONFIG], [200, CONFIG], [502, {}]))
    FakeSocket.all[0].drop()
    for (let k = 0; k < 5; k++) (await nextAttempt()).fail()
    await settle()
    FakeSocket.all[6].fail()
    expect(page.Breadboard.connection).toBe('blocked')
    // the server stops answering (the proxy in front of it says so)
    await settle()
    for (let k = 0; k < 3; k++) {
      ;(await nextAttempt()).fail()
      await settle()
    }
    expect(page.Breadboard.connection).toBe('lost')
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
    expect(FakeSocket.all[0].sent).toEqual([PING])
    await vi.advanceTimersByTimeAsync(5000)
    expect(FakeSocket.all[0].sent).toEqual([PING, PING])
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

describe('signing in', () => {
  const logIns = (k: number) => FakeSocket.all[k].sentFrames().filter((m: any) => m.action === 'LogIn')

  it('signs in on its first connection, and again after a drop, saying how long it was out of touch', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const page = await openPage(stateAnswers([200, CONFIG]), false)
    await page.Breadboard.login()
    // (as it opens, before anything else)
    FakeSocket.all[0].accept()
    expect(FakeSocket.all[0].sentFrames()[0]).toEqual({ action: 'LogIn', clientId: 'p1' })
    // the server last answers 3 s before the connection drops; it is back 0.5 s after
    await vi.advanceTimersByTimeAsync(2000)
    FakeSocket.all[0].frame({ pong: 1 })
    await vi.advanceTimersByTimeAsync(3000)
    FakeSocket.all[0].drop()
    await vi.advanceTimersByTimeAsync(500)
    FakeSocket.all[1].accept()
    expect(FakeSocket.all[1].sentFrames()[0]).toEqual({ action: 'LogIn', clientId: 'p1', awayMs: 3500 })
    // once per connection, however often the page asks (as useBreadboard does on every open)
    await page.Breadboard.login()
    await page.Breadboard.login()
    expect(logIns(1).length).toBe(1)
  })

  it('says how long a connection that died without closing was out of touch', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]), false)
    await page.Breadboard.login()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].frame({ pong: 1 })
    // nothing more comes: after 15 s the page connects again at once
    await vi.advanceTimersByTimeAsync(15000)
    FakeSocket.all[1].accept()
    expect(logIns(1)).toEqual([{ action: 'LogIn', clientId: 'p1', awayMs: 15000 }])
  })

  it('signs in on no other connection: none after "Use it here", none if it never signed in', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]), false)
    await page.Breadboard.login()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].frame({ bbClosed: 'replaced' })
    FakeSocket.all[0].drop()
    page.Breadboard.useHere()
    FakeSocket.all[1].accept()
    expect(logIns(1)).toEqual([])
    // a page that never signs in (the previous console, say) doesn't start to
    const other = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[2].drop()
    await vi.advanceTimersByTimeAsync(10000)
    FakeSocket.all[3].accept()
    expect(logIns(3)).toEqual([])
    expect(other.Breadboard).toBeDefined()
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
    // (then the first heartbeat)
    expect(sent.map((m: any) => m.action)).toEqual(['LogIn', 'MakeChoice', 'Ping'])
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

  // Every page of the origin can read local storage: it goes as soon as it is no longer needed.
  it('is forgotten once the participant is done', async () => {
    keep()
    await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ player: { _system: {}, text: 'Round 1' } })
    expect(localStorage.getItem(KEY)).not.toBe(null)
    FakeSocket.all[0].frame({ player: { _system: {}, finished: true, text: 'Thank you' } })
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('is forgotten once the participant has their completion code', async () => {
    keep()
    await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ player: { _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C0DE' } }, text: 'Thank you' } })
    expect(localStorage.getItem(KEY)).toBe(null)
  })

  it('is left alone by a preview of the same player in this browser', async () => {
    keep()
    location.search = '?preview=1'
    location.href = 'http://lab.example' + PAGE + '?preview=1'
    await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ player: { _system: {}, finished: true, text: 'Thank you' } })
    FakeSocket.all[0].frame(NOT_RUNNING)
    expect(JSON.parse(localStorage.getItem(KEY)!).token).toBe('tok')
  })
})

// A researcher's preview of a player whose own page was connected used to take their seat: the
// player's page was closed and kept taking the seat back. Now the server lets such a preview only
// watch: it says so first ({"bbWatching":true}), mirrors what the player's page gets, and ignores
// what the preview sends, except the heartbeat.
describe('a researcher\'s preview of a player whose own page is connected', () => {
  const WATCHING = { bbWatching: true }
  const PING = '{"action":"Ping"}'
  /** What the page sent for its player: everything but the heartbeat */
  const answers = (s: any) => s.sentFrames().filter((m: any) => m.action !== 'Ping')

  function asPreview () {
    location.search = '?preview=1'
    location.href = 'http://lab.example' + PAGE + '?preview=1'
  }

  /** Every way the page answers for its player */
  async function answerForThem (Breadboard: any) {
    Breadboard.sendChoice('c1')
    Breadboard.sendGroupChoice('g1')
    await Breadboard.sendEdgeEdit('add', 'na', 'nb')
    await Breadboard.send('my-event', { x: 1 })
    Breadboard.sendCustom({ eventName: 'raw' })
    Breadboard.sendType('MakeChoice', { choiceUID: 'c2' })
  }

  it('watches: shows what the player sees, and sends nothing for them but the heartbeat', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, CONFIG]))
    expect(page.Breadboard.preview).toBe(true)
    expect(page.Breadboard.connection).toBe('open')
    FakeSocket.all[0].frame(WATCHING)
    expect(page.Breadboard.connection).toBe('watching')
    expect(page.Breadboard.watching).toBe(true)
    expect(page.connections.slice(-1)).toEqual(['watching'])
    // what the player's page gets
    FakeSocket.all[0].frame({ player: { _system: {}, text: 'Round 1', choices: [{ uid: 'c1', name: 'Cooperate' }] } })
    expect(page.players.map(p => p.text)).toEqual(['Round 1'])
    await answerForThem(page.Breadboard)
    expect(answers(FakeSocket.all[0])).toEqual([])
    await vi.advanceTimersByTimeAsync(5000)
    expect(FakeSocket.all[0].sent).toEqual([PING, PING])
    FakeSocket.all[0].frame({ pong: 1761700000000 })
    expect(page.Breadboard.connection).toBe('watching')
  })

  it('starts watching when the player\'s own page comes back and takes the seat from it', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, CONFIG]))
    page.Breadboard.sendChoice('c1')
    expect(answers(FakeSocket.all[0]).map((m: any) => m.choiceUID)).toEqual(['c1'])
    FakeSocket.all[0].frame(WATCHING)
    expect(page.Breadboard.connection).toBe('watching')
    page.Breadboard.sendChoice('c2')
    expect(answers(FakeSocket.all[0]).map((m: any) => m.choiceUID)).toEqual(['c1'])
    // the connection stays as it is
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(page.Breadboard.connection).toBe('watching')
  })

  it('is asked again on every new connection, and acts for the player once their page has gone', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(WATCHING)
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('lost')
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeSocket.all.length).toBe(2)
    expect(page.Breadboard.watching).toBe(false)
    FakeSocket.all[1].accept()
    expect(page.Breadboard.connection).toBe('open')
    page.Breadboard.sendChoice('c1')
    expect(answers(FakeSocket.all[1]).map((m: any) => m.choiceUID)).toEqual(['c1'])
  })

  it('acts for the player as soon as the server hands it the seat their page left', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame(WATCHING)
    page.Breadboard.sendChoice('c1')
    expect(answers(FakeSocket.all[0])).toEqual([])
    FakeSocket.all[0].frame({ bbWatching: false })
    expect(page.Breadboard.connection).toBe('open')
    expect(page.Breadboard.watching).toBe(false)
    expect(page.connections.slice(-2)).toEqual(['watching', 'open'])
    page.Breadboard.sendChoice('c2')
    expect(answers(FakeSocket.all[0]).map((m: any) => m.choiceUID)).toEqual(['c2'])
    // on the same connection: nothing reconnects
    expect(FakeSocket.all.length).toBe(1)
    // and it watches again when their page comes back
    FakeSocket.all[0].frame(WATCHING)
    expect(page.Breadboard.connection).toBe('watching')
  })

  it('is told when another preview takes the seat for real, like a participant\'s other tab', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, CONFIG]))
    FakeSocket.all[0].frame({ bbClosed: 'replaced' })
    FakeSocket.all[0].drop()
    expect(page.Breadboard.connection).toBe('elsewhere')
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('is no preview on a participant\'s page', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    expect(page.Breadboard.preview).toBe(false)
    expect(page.Breadboard.watching).toBe(false)
    expect(page.Breadboard.computerPlayer).toBe(false)
  })

  // A preview of a computer player (g.addAI's _1, _2, …) only watches what the computer does: the
  // page's words say so instead of "their own page is connected".
  it('knows when its player is a computer player', async () => {
    asPreview()
    const page = await openPage(stateAnswers([200, { ...CONFIG, clientId: '_1' }]))
    expect(page.Breadboard.computerPlayer).toBe(true)
    FakeSocket.all[0].frame(WATCHING)
    expect(page.Breadboard.connection).toBe('watching')
    await answerForThem(page.Breadboard)
    expect(answers(FakeSocket.all[0])).toEqual([])
  })
})
