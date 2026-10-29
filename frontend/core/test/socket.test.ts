import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Socket, SocketOptions, SocketState } from '../src/socket'

/** A WebSocket that the test opens and drops. */
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
  /** The server accepts the connection. */
  accept () { this.readyState = 1; this.fire('open') }
  /** The server sends a message. */
  message (data: string) { this.fire('message', { data }) }
  /** The server goes away. */
  drop () { this.readyState = 3; this.fire('close') }
  /** The connection can't be made (the server is down). */
  fail () { this.readyState = 3; this.fire('error'); this.fire('close') }
}

/** The newest connection the socket made */
function latest () {
  return FakeSocket.all[FakeSocket.all.length - 1]
}

/** Let the clock run until the socket makes its next attempt (at most the longest wait), and return it. */
function untilNextAttempt () {
  const n = FakeSocket.all.length
  for (let t = 0; t <= 31000 && FakeSocket.all.length === n; t += 50) vi.advanceTimersByTime(50)
  return latest()
}

/** A socket that records the waits it announces, with the heartbeat a participant page has */
function socketWithWaits (options: SocketOptions = {}) {
  const s = new Socket('ws://lab/connect', undefined, options)
  const waits: number[] = []
  s.on('retry', (wait: number) => waits.push(wait))
  return { s, waits }
}

const HEARTBEAT: SocketOptions = { ping: 'PING', isPong: (d: any) => d === 'PONG' }

let sockets: Socket[] = []

/** A socket closed after the test, so it stops listening to the window */
function track (s: Socket) {
  sockets.push(s)
  return s
}

/** The page comes back from the back/forward cache (or, not persisted, is first loaded) */
function pageShow (persisted = true) {
  window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted }))
}

/** The page is shown or hidden */
function setVisibility (state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('Socket', () => {
  beforeEach(() => {
    FakeSocket.all = []
    sockets = []
    vi.stubGlobal('WebSocket', FakeSocket)
    vi.useFakeTimers()
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'debug').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    for (const s of sockets) s.close()
    delete (document as any).visibilityState
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('reconnects after a drop and sends what was queued meanwhile', () => {
    const s = new Socket('ws://lab/connect')
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    s.send('hello')
    vi.advanceTimersByTime(10000)
    expect(FakeSocket.all.length).toBe(2)
    FakeSocket.all[1].accept()
    expect(FakeSocket.all[1].sent).toEqual(['hello'])
    s.close()
  })

  it('stays closed when closed while waiting to reconnect', () => {
    const s = new Socket('ws://lab/connect')
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    s.send('queued')
    s.close()
    // the pending retry must not open a connection nobody listens to (and send what was queued)
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(FakeSocket.all[0].sent).toEqual([])
    // nor can it be talked into one
    s.connect()
    s.send('later')
    window.dispatchEvent(new Event('online'))
    expect(FakeSocket.all.length).toBe(1)
  })

  it('stays closed when a listener closes it as the retry is announced', () => {
    const s = new Socket('ws://lab/connect')
    s.on('retry', () => s.close())
    s.connect()
    FakeSocket.all[0].drop()
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('drops what was queued when closed before it ever opened', () => {
    const s = new Socket('ws://lab/connect')
    s.connect()
    s.send('first')
    s.close()
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(FakeSocket.all[0].sent).toEqual([])
  })

  it('keeps the open connection but opens no other once told to stop reconnecting', () => {
    const s = track(new Socket('ws://lab/connect'))
    const messages: string[] = []
    s.on('message', (d: string) => messages.push(d))
    s.connect()
    FakeSocket.all[0].accept()
    s.stopReconnecting()
    FakeSocket.all[0].message('still here')
    s.send('reply')
    expect(messages).toEqual(['still here'])
    expect(FakeSocket.all[0].sent).toEqual(['reply'])
    FakeSocket.all[0].drop()
    vi.advanceTimersByTime(5 * 60 * 1000)
    // nor when the network or the page comes back
    window.dispatchEvent(new Event('online'))
    s.reconnectNow()
    expect(FakeSocket.all.length).toBe(1)
  })

  it('cancels a reconnect that was waiting when told to stop reconnecting', () => {
    const s = track(new Socket('ws://lab/connect'))
    const retries: number[] = []
    s.on('retry', (wait: number) => retries.push(wait))
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    expect(retries.length).toBe(1)
    s.stopReconnecting()
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('reconnects at once and as before when resumed after it stopped', () => {
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    FakeSocket.all[0].accept()
    s.stopReconnecting()
    FakeSocket.all[0].drop()
    vi.advanceTimersByTime(60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    s.resume()
    expect(FakeSocket.all.length).toBe(2)
    FakeSocket.all[1].accept()
    FakeSocket.all[1].drop()
    vi.advanceTimersByTime(1000)
    expect(FakeSocket.all.length).toBe(3)
  })

  it('says it is refused when the server closes three connections in a row without a word', () => {
    const s = new Socket('ws://lab/connect')
    let refused = 0
    s.on('refused', () => refused++)
    s.connect()
    for (let k = 0; k < 3; k++) {
      expect(refused).toBe(0)
      FakeSocket.all[k].accept()
      FakeSocket.all[k].drop()
      vi.advanceTimersByTime(10000)
    }
    expect(refused).toBe(1)
    // it goes on trying meanwhile, and counts again from there
    expect(FakeSocket.all.length).toBe(4)
    FakeSocket.all[3].accept()
    FakeSocket.all[3].drop()
    expect(refused).toBe(1)
    s.close()
  })

  it('takes neither a server that answered nor one that can\'t be reached for a refusal', () => {
    const s = new Socket('ws://lab/connect')
    let refused = 0
    s.on('refused', () => refused++)
    s.connect()
    // two silent ones, then a connection that brings something: a short network drop, not a refusal
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    untilNextAttempt().accept()
    FakeSocket.all[1].drop()
    untilNextAttempt().accept()
    FakeSocket.all[2].message('{"style":""}')
    FakeSocket.all[2].drop()
    // the server is down: connections never open
    for (let k = 3; k < 8; k++) {
      untilNextAttempt().fail()
    }
    untilNextAttempt()
    expect(FakeSocket.all.length).toBe(9)
    expect(refused).toBe(0)
    s.close()
  })
})

describe('Socket reconnecting', () => {
  beforeEach(() => {
    FakeSocket.all = []
    sockets = []
    vi.stubGlobal('WebSocket', FakeSocket)
    vi.useFakeTimers()
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'debug').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    for (const s of sockets) s.close()
    delete (document as any).visibilityState
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('tries again within a second, then waits up to twice as long each time, never more than 30 s', () => {
    // the longest waits the randomness allows
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const { s, waits } = socketWithWaits()
    track(s).connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].message('{"style":""}')
    FakeSocket.all[0].drop()
    for (let k = 0; k < 7; k++) {
      const wait = waits[waits.length - 1]
      vi.advanceTimersByTime(wait - 1)
      expect(FakeSocket.all.length).toBe(k + 1)
      vi.advanceTimersByTime(1)
      expect(FakeSocket.all.length).toBe(k + 2)
      latest().fail()
    }
    expect(waits).toEqual([999, 1998, 3996, 7992, 15984, 29970, 29970, 29970])
  })

  it('waits anything from nothing up to the limit', () => {
    const { s, waits } = socketWithWaits()
    track(s).connect()
    vi.spyOn(Math, 'random').mockReturnValue(0)
    FakeSocket.all[0].fail()
    expect(waits).toEqual([0])
    vi.advanceTimersByTime(0)
    expect(FakeSocket.all.length).toBe(2)
  })

  it('starts the waits over only once a connection brings a message', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const { s, waits } = socketWithWaits()
    track(s).connect()
    // a server that takes the connection and closes it again at once is tried less and less often
    for (let k = 0; k < 4; k++) {
      latest().accept()
      latest().drop()
      untilNextAttempt()
    }
    expect(waits).toEqual([999, 1998, 3996, 7992])
    latest().accept()
    latest().message('{"style":""}')
    latest().drop()
    expect(waits[4]).toBe(999)
  })

  it('connects at once when the network comes back, instead of waiting', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const { s, waits } = socketWithWaits()
    track(s).connect()
    // offline for a while: the next attempt is half a minute away
    latest().fail()
    for (let k = 0; k < 6; k++) untilNextAttempt().fail()
    expect(waits[waits.length - 1]).toBe(29970)
    const before = FakeSocket.all.length
    window.dispatchEvent(new Event('online'))
    expect(FakeSocket.all.length).toBe(before + 1)
    latest().accept()
    expect(s.state).toBe(SocketState.OPEN)
    // and the attempt that was waiting doesn't happen as well
    vi.advanceTimersByTime(60 * 1000)
    expect(FakeSocket.all.length).toBe(before + 1)
  })

  it('connects at once when the page is shown again', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].message('{"style":""}')
    FakeSocket.all[0].drop()
    setVisibility('hidden')
    expect(FakeSocket.all.length).toBe(1)
    setVisibility('visible')
    expect(FakeSocket.all.length).toBe(2)
    latest().fail()
    // back from the back/forward cache
    pageShow()
    expect(FakeSocket.all.length).toBe(3)
  })

  it('leaves an open connection, and an attempt that has just started, as they are', () => {
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    // ('pageshow' and 'visibilitychange' come together)
    pageShow()
    setVisibility('visible')
    expect(FakeSocket.all.length).toBe(1)
    // nor is the page first loading reason to start again
    vi.advanceTimersByTime(3000)
    pageShow(false)
    expect(FakeSocket.all.length).toBe(1)
    FakeSocket.all[0].accept()
    window.dispatchEvent(new Event('online'))
    s.reconnectNow()
    expect(FakeSocket.all.length).toBe(1)
    expect(s.state).toBe(SocketState.OPEN)
  })

  it('gives up an attempt that is getting nowhere for a new one when asked to try now', () => {
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    vi.advanceTimersByTime(3000)
    s.reconnectNow()
    expect(FakeSocket.all.length).toBe(2)
    expect(FakeSocket.all[0].readyState).toBe(3)
    // what the one given up does is ignored
    const closes: number[] = []
    s.on('close', () => closes.push(1))
    vi.advanceTimersByTime(10)
    FakeSocket.all[0].fire('open')
    expect(s.state).toBe(SocketState.PENDING)
    expect(closes).toEqual([])
    FakeSocket.all[1].accept()
    expect(s.state).toBe(SocketState.OPEN)
  })

  it('gives up an attempt that doesn\'t open within 15 s, and counts it as failed', () => {
    const { s, waits } = socketWithWaits()
    track(s).connect()
    const closes: number[] = []
    s.on('close', () => closes.push(1))
    vi.advanceTimersByTime(14999)
    expect(waits).toEqual([])
    vi.advanceTimersByTime(1)
    expect(FakeSocket.all[0].readyState).toBe(3)
    expect(waits.length).toBe(1)
    expect(closes).toEqual([1])
    // its own late 'close' doesn't count it twice
    vi.advanceTimersByTime(0)
    expect(waits.length).toBe(1)
    untilNextAttempt().accept()
    expect(s.state).toBe(SocketState.OPEN)
  })

  it('says the network may block it when it has never opened after three attempts', () => {
    const s = track(new Socket('ws://lab/connect'))
    let blocked = 0
    s.on('blocked', () => blocked++)
    s.connect()
    FakeSocket.all[0].fail()
    untilNextAttempt().fail()
    expect(blocked).toBe(0)
    expect(s.blocked).toBe(false)
    // (one that doesn't open in time counts too)
    untilNextAttempt()
    vi.advanceTimersByTime(15000)
    expect(blocked).toBe(1)
    expect(s.blocked).toBe(true)
    // it goes on trying, says so only once, and stops saying so once it opens
    untilNextAttempt().fail()
    expect(blocked).toBe(1)
    untilNextAttempt().accept()
    expect(s.blocked).toBe(false)
  })

  it('takes failures after it has been open for a lost connection, not a blocked one', () => {
    const s = track(new Socket('ws://lab/connect'))
    let blocked = 0
    s.on('blocked', () => blocked++)
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    for (let k = 0; k < 5; k++) untilNextAttempt().fail()
    expect(blocked).toBe(0)
    expect(s.blocked).toBe(false)
  })

  it('drops queued answers that waited longer than they may, and sends the rest', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    s.send('{"action":"LogIn"}')
    s.send('{"action":"MakeChoice","choiceUID":"old"}', 5000)
    vi.advanceTimersByTime(5500)
    s.send('{"action":"MakeChoice","choiceUID":"new"}', 5000)
    FakeSocket.all[1].accept()
    expect(FakeSocket.all[1].sent).toEqual(['{"action":"LogIn"}', '{"action":"MakeChoice","choiceUID":"new"}'])
  })
})

describe('Socket heartbeat', () => {
  beforeEach(() => {
    FakeSocket.all = []
    sockets = []
    vi.stubGlobal('WebSocket', FakeSocket)
    vi.useFakeTimers()
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'debug').mockImplementation(() => {})
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    for (const s of sockets) s.close()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('pings every 5 s while the connection is open, and only then', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    s.connect()
    vi.advanceTimersByTime(10000)
    FakeSocket.all[0].accept()
    vi.advanceTimersByTime(4999)
    expect(FakeSocket.all[0].sent).toEqual([])
    vi.advanceTimersByTime(1)
    expect(FakeSocket.all[0].sent).toEqual(['PING'])
    FakeSocket.all[0].message('PONG')
    vi.advanceTimersByTime(5000)
    expect(FakeSocket.all[0].sent).toEqual(['PING', 'PING'])
    s.close()
    vi.advanceTimersByTime(60000)
    expect(FakeSocket.all[0].sent).toEqual(['PING', 'PING'])
  })

  it('keeps the answers to itself', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    const messages: string[] = []
    s.on('message', (d: string) => messages.push(d))
    s.connect()
    FakeSocket.all[0].accept()
    FakeSocket.all[0].message('PONG')
    FakeSocket.all[0].message('{"player":{}}')
    expect(messages).toEqual(['{"player":{}}'])
  })

  it('drops a connection that went silent for 15 s, once its server has answered a ping, and reconnects at once', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    const events: string[] = []
    s.on('close', () => events.push('close'))
    s.on('message', (d: string) => events.push(d))
    s.connect()
    const first = FakeSocket.all[0]
    first.accept()
    vi.advanceTimersByTime(5000)
    first.message('PONG')
    // the network goes away without closing the connection: nothing comes any more
    vi.advanceTimersByTime(14999)
    expect(FakeSocket.all.length).toBe(1)
    vi.advanceTimersByTime(1)
    expect(FakeSocket.all.length).toBe(2)
    expect(events).toEqual(['close'])
    expect(first.readyState).toBe(3)
    // what the old connection still does is ignored
    first.message('{"player":{}}')
    vi.advanceTimersByTime(10)
    expect(events).toEqual(['close'])
    // the new one goes on in its place
    FakeSocket.all[1].accept()
    FakeSocket.all[1].message('{"player":{}}')
    expect(events).toEqual(['close', '{"player":{}}'])
    expect(s.state).toBe(SocketState.OPEN)
  })

  it('lets a dead connection go without making another once told to stop reconnecting', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    let closes = 0
    s.on('close', () => closes++)
    s.connect()
    FakeSocket.all[0].accept()
    vi.advanceTimersByTime(5000)
    FakeSocket.all[0].message('PONG')
    // e.g. the session is over for the page, but its connection stays open; then it goes silent
    s.stopReconnecting()
    vi.advanceTimersByTime(60 * 1000)
    expect(closes).toBe(1)
    expect(s.state).toBe(SocketState.CLOSED)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('never times out a server that doesn\'t answer pings (before 3.1)', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    s.connect()
    FakeSocket.all[0].accept()
    // a waiting room: nothing for minutes
    vi.advanceTimersByTime(10 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(s.state).toBe(SocketState.OPEN)
  })

  it('keeps a connection over which other messages keep coming', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    s.connect()
    FakeSocket.all[0].accept()
    vi.advanceTimersByTime(5000)
    FakeSocket.all[0].message('PONG')
    // no more answers, but the game's updates keep coming
    for (let k = 0; k < 120; k++) {
      vi.advanceTimersByTime(500)
      FakeSocket.all[0].message('{"graph":{}}')
    }
    expect(FakeSocket.all.length).toBe(1)
  })

  it('is not fooled by timers that a background tab runs late', () => {
    const s = track(new Socket('ws://lab/connect', undefined, HEARTBEAT))
    s.connect()
    FakeSocket.all[0].accept()
    vi.advanceTimersByTime(5000)
    FakeSocket.all[0].message('PONG')
    // the browser runs the next heartbeat a minute late
    vi.setSystemTime(Date.now() + 60 * 1000)
    vi.advanceTimersByTime(5000)
    expect(FakeSocket.all.length).toBe(1)
    expect(FakeSocket.all[0].sent).toEqual(['PING', 'PING'])
    FakeSocket.all[0].message('PONG')
    vi.advanceTimersByTime(5000)
    expect(FakeSocket.all.length).toBe(1)
  })

  it('sends no heartbeat without one in its options (the console\'s socket)', () => {
    const s = track(new Socket('ws://lab/connect'))
    s.connect()
    FakeSocket.all[0].accept()
    vi.advanceTimersByTime(60 * 1000)
    expect(FakeSocket.all[0].sent).toEqual([])
    expect(FakeSocket.all.length).toBe(1)
  })
})
