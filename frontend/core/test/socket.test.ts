import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Socket } from '../src/socket'

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
  /** The server goes away. */
  drop () { this.readyState = 3; this.fire('close') }
}

describe('Socket', () => {
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
})
