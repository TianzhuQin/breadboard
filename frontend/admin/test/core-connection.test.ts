import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { AdminConnection } from '../src/lib/connection'

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
