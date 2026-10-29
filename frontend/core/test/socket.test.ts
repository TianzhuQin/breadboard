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
  /** The server sends a message. */
  message (data: string) { this.fire('message', { data }) }
  /** The server goes away. */
  drop () { this.readyState = 3; this.fire('close') }
  /** The connection can't be made (the server is down). */
  fail () { this.readyState = 3; this.fire('error'); this.fire('close') }
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

  it('keeps the open connection but opens no other once told to stop reconnecting', () => {
    const s = new Socket('ws://lab/connect')
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
    expect(FakeSocket.all.length).toBe(1)
  })

  it('cancels a reconnect that was waiting when told to stop reconnecting', () => {
    const s = new Socket('ws://lab/connect')
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
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const s = new Socket('ws://lab/connect')
    let refused = 0
    s.on('refused', () => refused++)
    s.connect()
    // two silent ones, then a connection that brings something: a short network drop, not a refusal
    FakeSocket.all[0].accept()
    FakeSocket.all[0].drop()
    vi.advanceTimersByTime(10000)
    FakeSocket.all[1].accept()
    FakeSocket.all[1].drop()
    vi.advanceTimersByTime(10000)
    FakeSocket.all[2].accept()
    FakeSocket.all[2].message('{"style":""}')
    FakeSocket.all[2].drop()
    vi.advanceTimersByTime(10000)
    // the server is down: connections never open
    for (let k = 3; k < 8; k++) {
      FakeSocket.all[k].fail()
      vi.advanceTimersByTime(2 * 60 * 1000)
    }
    expect(FakeSocket.all.length).toBe(9)
    expect(refused).toBe(0)
    s.close()
  })
})
