import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { AdminConnection, MAX_RETRY_MS } from '../src/lib/connection'

/** A WebSocket that the test opens and closes (events arrive later, as in a browser). */
class FakeSocket {
  static OPEN = 1
  static all: FakeSocket[] = []
  readyState = 0
  sent: any[] = []
  private on: Record<string, ((ev: any) => void)[]> = {}
  constructor (public url: string) { FakeSocket.all.push(this) }
  addEventListener (type: string, fn: (ev: any) => void) { (this.on[type] ||= []).push(fn) }
  send (data: string) { this.sent.push(JSON.parse(data)) }
  close () { this.readyState = 3; setTimeout(() => this.fire('close'), 0) }
  fire (type: string, ev: any = {}) { for (const fn of this.on[type] || []) fn(ev) }
  /** The server accepts the connection. */
  accept () { this.readyState = 1; this.fire('open') }
  /** The server goes away. */
  drop () { this.readyState = 3; this.fire('close') }
}

describe('AdminConnection', () => {
  beforeEach(() => {
    FakeSocket.all = []
    vi.stubGlobal('WebSocket', FakeSocket)
    vi.useFakeTimers()
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.spyOn(console, 'debug').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('sends only while the connection is open, never queueing for a reconnect', () => {
    const conn = new AdminConnection()
    conn.start('u1', 'ws://lab/connect')
    expect(conn.send('ReloadEngine')).toBe(false)
    const ws = FakeSocket.all[0]
    ws.accept()
    expect(conn.state.value).toBe('open')
    expect(conn.send('RunGame')).toBe(true)
    expect(ws.sent).toEqual([{ action: 'LogIn', uid: 'u1' }, { action: 'RunGame', uid: 'u1' }])

    ws.drop()
    expect(conn.state.value).toBe('retrying')
    // clicked while reconnecting: refused now, not run whenever the connection comes back
    expect(conn.send('LaunchGame', { name: 'Pilot' })).toBe(false)
    vi.advanceTimersByTime(10000)
    const again = FakeSocket.all[1]
    again.accept()
    expect(again.sent).toEqual([{ action: 'LogIn', uid: 'u1' }])
    conn.stop()
  })

  it('stays closed when stopped while waiting to reconnect', () => {
    const conn = new AdminConnection()
    conn.start('u1', 'ws://lab/connect')
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    expect(conn.state.value).toBe('retrying')
    conn.stop()
    expect(conn.state.value).toBe('closed')
    // the pending retry must not open a new connection on the abandoned socket
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
  })

  // After a restart of the server, consoles took up to 20 s or more to come back: the core socket's
  // back-off grows to 30 s while the server is down. A console waits at most MAX_RETRY_MS.
  it('waits at most about 5 s between attempts while the server is down', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const conn = new AdminConnection()
    conn.start('u1', 'ws://lab/connect')
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    expect(MAX_RETRY_MS).toBe(5000)
    // the server is down: attempt after attempt fails, and the core socket would wait longer each time
    for (let n = 1; n <= 8; n++) {
      const before = FakeSocket.all.length
      vi.advanceTimersByTime(MAX_RETRY_MS)
      expect(FakeSocket.all.length).toBe(before + 1)
      expect(conn.retryInMs.value).toBeLessThanOrEqual(MAX_RETRY_MS)
      FakeSocket.all[FakeSocket.all.length - 1].drop()
      expect(conn.state.value).toBe('retrying')
    }
    // back: the next attempt opens, and logs in
    vi.advanceTimersByTime(MAX_RETRY_MS)
    const last = FakeSocket.all[FakeSocket.all.length - 1]
    last.accept()
    expect(conn.state.value).toBe('open')
    expect(last.sent).toEqual([{ action: 'LogIn', uid: 'u1' }])
    // and no attempt of the core socket's own comes after it
    const count = FakeSocket.all.length
    vi.advanceTimersByTime(60000)
    expect(FakeSocket.all.length).toBe(count)
    conn.stop()
  })

  it('tries again at once when the window gets the focus, the page is shown again, the network comes back, or Try now is pressed', () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.999)
    const conn = new AdminConnection()
    conn.start('u1', 'ws://lab/connect')
    FakeSocket.all[0].accept()
    // open: nothing to do
    window.dispatchEvent(new Event('focus'))
    expect(FakeSocket.all.length).toBe(1)
    FakeSocket.all[0].drop()
    const wake: [string, () => void][] = [
      ['focus', () => window.dispatchEvent(new Event('focus'))],
      ['visibilitychange', () => document.dispatchEvent(new Event('visibilitychange'))],
      ['online', () => window.dispatchEvent(new Event('online'))],
      ['Try now', () => conn.tryNow()],
    ]
    for (const [what, fire] of wake) {
      // (an attempt that has only just started is left to finish)
      vi.advanceTimersByTime(1500)
      const before = FakeSocket.all.length
      FakeSocket.all[before - 1].drop()
      fire()
      expect(FakeSocket.all.length, what).toBe(before + 1)
    }
    FakeSocket.all[FakeSocket.all.length - 1].accept()
    expect(conn.state.value).toBe('open')
    conn.stop()
    // stopped: the focus no longer reaches it
    window.dispatchEvent(new Event('focus'))
    expect(FakeSocket.all.filter(ws => ws.readyState === 0)).toEqual([])
  })

  it('closes an open connection for good', () => {
    const conn = new AdminConnection()
    conn.start('u1', 'ws://lab/connect')
    FakeSocket.all[0].accept()
    conn.stop()
    vi.advanceTimersByTime(5 * 60 * 1000)
    expect(FakeSocket.all.length).toBe(1)
    expect(conn.send('RunGame')).toBe(false)
  })
})
