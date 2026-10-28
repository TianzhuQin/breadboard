import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// A participant page that was open while the server restarted holds a seat token the server has
// forgotten. It used to retry its websocket for ever, saying "Reconnecting…". Now the server says
// why it turns the page away (ClientController.connectClient) and the page acts on it; a short
// network drop still ends in the same seat.

const PAGE = '/game/1/2/p1/connected'
const SOCKET = 'ws://lab.example/game/1/2/p1/connect'
const CONFIG = { clientId: 'p1', connectSocket: SOCKET }
const SIGN_IN = { error: 'not-logged-in', login: '/game/1/2/login' }
/** What the server sends a page whose session it doesn't run (any more) */
const NOT_RUNNING = { player: { _system: {}, finished: true, ended: 'notRunning', text: '<h3>This session isn\'t running right now</h3>' } }

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

beforeEach(() => {
  FakeSocket.all = []
  vi.stubGlobal('WebSocket', FakeSocket)
  location = { pathname: PAGE, search: '', hash: '', href: 'http://lab.example' + PAGE, protocol: 'http:', host: 'lab.example' }
  vi.stubGlobal('location', location)
  document.body.innerHTML = '<div id="app">Loading…</div>'
  vi.useFakeTimers()
  vi.spyOn(console, 'log').mockImplementation(() => {})
  vi.spyOn(console, 'debug').mockImplementation(() => {})
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** A newly loaded participant page: it gets its state, opens its socket, and the server takes it. */
async function openPage (fetch: ReturnType<typeof stateAnswers>) {
  vi.stubGlobal('fetch', fetch)
  vi.resetModules()
  const { Breadboard } = await import('../src/breadboard')
  const players: any[] = []
  let ended = 0
  Breadboard.on('player', (p: any) => players.push(p))
  Breadboard.on('ended', () => ended++)
  await Breadboard.connect()
  FakeSocket.all[0].accept()
  return { Breadboard, players, ended: () => ended }
}

/** The server goes away and comes back: the page's next attempt is taken. */
async function reconnect (k: number) {
  FakeSocket.all[k - 1].drop()
  await vi.advanceTimersByTimeAsync(10000)
  FakeSocket.all[k].accept()
}

describe('a page whose session is over', () => {
  it('shows what the server says and never reconnects again', async () => {
    const page = await openPage(stateAnswers([200, CONFIG]))
    await reconnect(1)
    FakeSocket.all[1].frame(NOT_RUNNING)
    FakeSocket.all[1].drop()
    expect(page.players).toEqual([NOT_RUNNING.player])
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
