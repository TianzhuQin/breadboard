import { Emitter } from 'goodish'

export enum SocketState {
  CLOSED,
  OPEN,
  CLOSING,
  PENDING
}

// Reconnecting: the first attempt within a second, then after a random wait of up to 2, 4, 8, 16
// and at most 30 seconds ("full jitter": pages whose server comes back don't all knock at once).
const FIRST_WAIT_MS = 1000
const MAX_WAIT_MS = 30 * 1000
// An attempt that hasn't opened by then is given up and counts as failed: a network that swallows
// the connection would otherwise keep it waiting for minutes.
const OPEN_TIMEOUT_MS = 15 * 1000
// Attempts in a row that never opened, on a page whose connection never has, that make 'blocked'
const BLOCKED_AFTER = 3
// The heartbeat (SocketOptions)
const PING_EVERY_MS = 5 * 1000
const SILENCE_MS = 15 * 1000
// reconnectNow() leaves an attempt this young alone ('pageshow' and 'visibilitychange' come together)
const FRESH_ATTEMPT_MS = 1000
// How many unanswered pings it keeps the times of (a server that never answers would have it keep
// them all); a pong is paired with the oldest kept, so it is never taken for the answer to a later one
const PINGS_KEPT = 20

export interface SocketOptions {
  /** A heartbeat, sent every 5 s while the connection is open, e.g. '{"action":"Ping"}' */
  ping?: string
  /**
   * Whether a message is the server's answer to `ping` (it isn't passed on). Once one has come,
   * 15 s without any message, with a ping unanswered, means the connection died without closing
   * (the network went away, or swallows everything): it is dropped and made again at once. A
   * server that never answers is never timed out.
   */
  isPong?: (data: any) => boolean
}

/** A message waiting for the connection (see send) */
type Queued = { message: any, at: number, maxAge?: number }

/**
 * A WebSocket wrapper that reconnects by itself, with exponential back-off, and queues what is sent
 * while it isn't connected. Once closed with close(), it stays closed: it never reconnects and sends
 * nothing more.
 *
 * Events: 'connect' (an attempt starts), 'open', 'message' (data, event), 'pong' (data, and when the
 * ping it answers was sent, by Date.now(): the server's answer to the heartbeat, which is no
 * 'message'), 'close', 'error', 'retry' (the wait in ms before the next attempt), 'unopened',
 * 'refused' and 'blocked' (below).
 *
 * After each attempt that didn't open, it emits 'unopened' with the number of such attempts in a row.
 * When the server takes the connection and closes it again without sending anything, several
 * times in a row, it emits 'refused': reconnecting alone is not going to change that. When the
 * connection has never opened after several attempts, it emits 'blocked': the network may not let
 * it through. It goes on trying in all these cases, and at once when the browser says the network
 * is back, or the page is shown again (from the background, or the back/forward cache).
 */
export class Socket extends Emitter {
  public state: SocketState = SocketState.CLOSED
  public socket!: WebSocket
  /** The connection has never opened, after several attempts (see 'blocked') */
  public blocked = false
  /** Failed attempts since a connection last brought a message: the wait before the next grows with it */
  private nTries = 0
  private buffer: Queued[] = []
  /** The reconnect waiting to happen after the connection dropped */
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  /** Gives up the attempt under way if it doesn't open */
  private openTimer: ReturnType<typeof setTimeout> | null = null
  /** The next heartbeat */
  private pingTimer: ReturnType<typeof setTimeout> | null = null
  /** close() was called */
  private closed = false
  /** stopReconnecting() was called */
  private reconnectStopped = false
  /** A message came over the current connection */
  private heard = false
  /** The server answered a ping over the current connection: a silence now means it is dead */
  private ponged = false
  /** When the last message came (or the connection opened) */
  private lastHeard = 0
  /** When the first ping that nothing has come after was sent; 0 when there is none */
  private unansweredSince = 0
  /** When each ping over the current connection that hasn't been answered yet was sent, oldest first */
  private pingsSent: number[] = []
  /** When the current attempt started */
  private attemptAt = 0
  /** Stops listening to the current connection (see abandon) */
  private detach = () => {}
  /** Connections in a row that the server closed before sending anything */
  private refusals = 0
  /** How many of those make 'refused' */
  private maxRefusals = 3
  /** The connection has opened at least once */
  private everOpened = false
  /** Attempts in a row that never opened (since the connection last opened) */
  private unopened = 0
  /** Listening for the network or the page coming back */
  private watchingPage = false

  /**
   * @param url where to connect, or what says so for each attempt (e.g. with what the page asks of
   * the server this time)
   */
  constructor (private url: string | (() => string), private protocols?: string | string[], private options: SocketOptions = {}) {
    super()
    this.connect = this.connect.bind(this)
    this.wake = this.wake.bind(this)
    this.onVisibility = this.onVisibility.bind(this)
    this.onPageShow = this.onPageShow.bind(this)
    this.beat = this.beat.bind(this)
  }

  /** Whether the connection has opened at least once (one that never has may be 'blocked') */
  get hasOpened (): boolean {
    return this.everOpened
  }

  /**
   * Open the WebSocket connection
   */
  public connect () {
    // (a retry scheduled before close() must not open a connection nobody listens to)
    if (this.closed) return
    // (nor a second one next to the one there is)
    if (this.state === SocketState.OPEN || this.state === SocketState.PENDING) return
    this.clearRetry()
    this.watchPage()
    console.log('socket.connect')
    this.state = SocketState.PENDING
    this.attemptAt = Date.now()
    this.emit('connect')
    const url = typeof this.url === 'function' ? this.url() : this.url
    const ws = this.protocols ? new WebSocket(url, this.protocols) : new WebSocket(url)
    this.socket = ws
    // Only the current connection counts: once given up (abandon), whatever it does is ignored.
    let attached = true
    this.detach = () => { attached = false }
    const listen = (type: string, handler: (ev: any) => void) => {
      ws.addEventListener(type, (ev: any) => { if (attached) handler.call(this, ev) })
    }
    listen('close', this.onClose)
    listen('error', this.onError)
    listen('message', this.onMessage)
    listen('open', this.onOpen)
    this.openTimer = setTimeout(() => this.giveUpOpening(ws), OPEN_TIMEOUT_MS)
  }

  /**
   * Close the connection for good: no reconnect follows, also when one was waiting to happen, and
   * nothing queued is sent
   */
  public close () {
    this.closed = true
    this.clearRetry()
    this.clearOpenTimer()
    this.clearPing()
    this.unwatchPage()
    if (this.state !== SocketState.CLOSING && this.state !== SocketState.CLOSED) {
      this.socket.close()
      this.state = SocketState.CLOSING
    }
    this._clearBuffer()
    this.removeListeners()
  }

  /**
   * Don't reconnect once the connection there is now closes (e.g. the server said the session is
   * over for this page). Unlike close(), what is open stays open, and its messages still arrive.
   */
  public stopReconnecting () {
    this.reconnectStopped = true
    this.clearRetry()
  }

  /**
   * Connect again at once, also after stopReconnecting(), and go on reconnecting as before (e.g. the
   * participant takes their seat back from another tab). Whatever connection there is is given up.
   * Not after close().
   */
  public resume () {
    if (this.closed) return
    this.reconnectStopped = false
    this.restart()
  }

  /**
   * Connect again now instead of waiting for the next attempt (the participant asked, or the network
   * or the page is back). An open connection stays as it is; an attempt under way is given up for a
   * new one, unless it has only just started.
   */
  public reconnectNow () {
    if (this.closed || this.reconnectStopped || this.state === SocketState.OPEN) return
    if (this.state === SocketState.PENDING && Date.now() - this.attemptAt < FRESH_ATTEMPT_MS) return
    this.restart()
  }

  /**
   * Give up the connection there is (open or opening) and make a new one at once, unless told to
   * stop reconnecting (then a dead one is only let go)
   */
  private restart () {
    const wasOpen = this.state === SocketState.OPEN
    this.abandon()
    if (wasOpen) this.emit('close')
    if (!this.reconnectStopped) this.connect()
  }

  /**
   * Stop listening to the current connection, and close it if it is open or opening
   */
  private abandon () {
    this.detach()
    this.clearOpenTimer()
    this.clearPing()
    if (this.state === SocketState.OPEN || this.state === SocketState.PENDING) {
      try {
        this.socket.close()
      } catch (e) {}
    }
    this.state = SocketState.CLOSED
  }

  /**
   * An attempt that hasn't opened in time fails, as if it had closed (its own 'close' may come late,
   * and is ignored)
   */
  private giveUpOpening (ws: WebSocket) {
    this.openTimer = null
    if (this.socket !== ws || this.state !== SocketState.PENDING) return
    console.debug(`The connection didn't open within ${OPEN_TIMEOUT_MS}ms`)
    this.detach()
    try {
      ws.close()
    } catch (e) {}
    this.onClose()
  }

  /**
   * The network is back, or the page is shown again: a connection that isn't open is made at once
   * (one that is open may have died meanwhile: the heartbeat finds out)
   */
  private wake () {
    this.reconnectNow()
  }

  private onVisibility () {
    if (document.visibilityState === 'visible') this.wake()
  }

  private onPageShow (ev: PageTransitionEvent) {
    // (only a page back from the back/forward cache: on first loading it, the page connects anyway)
    if (ev.persisted) this.wake()
  }

  private watchPage () {
    if (this.watchingPage || typeof window === 'undefined' || typeof document === 'undefined') return
    this.watchingPage = true
    window.addEventListener('online', this.wake)
    window.addEventListener('pageshow', this.onPageShow)
    document.addEventListener('visibilitychange', this.onVisibility)
  }

  private unwatchPage () {
    if (!this.watchingPage) return
    this.watchingPage = false
    window.removeEventListener('online', this.wake)
    window.removeEventListener('pageshow', this.onPageShow)
    document.removeEventListener('visibilitychange', this.onVisibility)
  }

  /**
   * The next heartbeat, PING_EVERY_MS from now
   */
  private schedulePing () {
    this.clearPing()
    if (this.options.ping) this.pingTimer = setTimeout(this.beat, PING_EVERY_MS)
  }

  /**
   * The heartbeat: a connection whose server answers pings, over which nothing has come for
   * SILENCE_MS although a ping has had time to be answered, is dead; otherwise ping it. (Both: a
   * background tab may run this timer only once a minute, and the server's answer to a ping that
   * went out late has had no time to come.)
   */
  private beat () {
    this.pingTimer = null
    if (this.state !== SocketState.OPEN || !this.options.ping) return
    const now = Date.now()
    if (this.ponged && this.unansweredSince && now - this.lastHeard >= SILENCE_MS && now - this.unansweredSince >= PING_EVERY_MS) {
      console.debug(`Nothing came over the connection for ${now - this.lastHeard}ms: reconnecting`)
      this.restart()
      return
    }
    if (!this.unansweredSince) this.unansweredSince = now
    if (this.pingsSent.length < PINGS_KEPT) this.pingsSent.push(now)
    this._send(this.options.ping)
    this.schedulePing()
  }

  /**
   * Cancel a pending reconnect
   */
  private clearRetry () {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
  }

  private clearOpenTimer () {
    if (this.openTimer !== null) {
      clearTimeout(this.openTimer)
      this.openTimer = null
    }
  }

  private clearPing () {
    if (this.pingTimer !== null) {
      clearTimeout(this.pingTimer)
      this.pingTimer = null
    }
  }

  /**
   * Send what was queued while the connection was down, except what waited longer than it may
   */
  private flush () {
    if (this.socket.readyState !== WebSocket.OPEN) return
    const now = Date.now()
    for (const queued of this.buffer) {
      if (queued.maxAge != null && now - queued.at > queued.maxAge) {
        console.debug(`Dropped a message that waited ${now - queued.at}ms for the connection`)
        continue
      }
      this._send(queued.message)
    }
    this._clearBuffer()
  }

  /**
   * Actually send data through the WebSocket
   * @param message
   */
  private _send (message: any) {
    this.socket.send(message)
  }

  /**
   * Clear the message buffer
   */
  private _clearBuffer () {
    this.buffer = []
  }

  /**
   * Send a message via this socket. If the WebSocket has not connected yet, the message will be queued
   * and sent once a connection is made. With `maxAge` (ms), it is dropped instead if it has waited
   * longer than that by then (e.g. a participant's answer, which the screen may no longer ask for).
   * @param message
   * @param maxAge
   */
  public send (message: any, maxAge?: number) {
    if (this.closed) return
    if (this.state === SocketState.OPEN) {
      this._send(message)
    } else {
      this.buffer.push({ message, at: Date.now(), maxAge })
    }
  }

  /**
   * Handle the WebSocket.onopen event internally
   */
  private onOpen () {
    this.clearOpenTimer()
    this.state = SocketState.OPEN
    this.heard = false
    this.everOpened = true
    this.unopened = 0
    this.blocked = false
    this.ponged = false
    this.lastHeard = Date.now()
    this.unansweredSince = 0
    this.pingsSent = []
    this.emit('open')
    this.flush()
    // The first heartbeat at once: the server (and this page) only time a connection out once a
    // ping has gone through, so one that died in its first 5 s used to look alive for good.
    this.beat()
  }

  /**
   * Handle the WebSocket.onmessage event internally
   * @param data
   */
  private onMessage (ev: MessageEvent) {
    if (!this.heard) {
      this.heard = true
      // Only a connection that brings something counts as working: one the server takes and closes
      // again at once doesn't start the waits from the beginning.
      this.nTries = 0
    }
    this.refusals = 0
    this.lastHeard = Date.now()
    this.unansweredSince = 0
    if (this.options.isPong && this.options.isPong(ev.data)) {
      this.ponged = true
      // Timed from the answer: a timer set from a network event isn't slowed down to once a minute
      // in a background tab, as a long chain of timers is.
      this.schedulePing()
      // (the server answers the pings in turn: this one answers the oldest still unanswered)
      this.emit('pong', ev.data, this.pingsSent.shift())
      return
    }
    this.emit('message', ev.data, ev)
  }

  /**
   * Handle the WebSocket.onerror event internally
   * @param event
   */
  private onError (event: Event) {
    this.emit('error', event)
    console.error(event)
  }

  /**
   * Handle the WebSocket.onclose event internally
   */
  private onClose () {
    this.clearOpenTimer()
    this.clearPing()
    const dropped = this.state !== SocketState.CLOSING && !this.closed
    // Taken and closed again without a word (not a server that can't be reached: that never opens)
    const refused = dropped && this.state === SocketState.OPEN && !this.heard
    // Never opened: the server can't be reached, or the network doesn't let the connection through
    const unopened = dropped && this.state === SocketState.PENDING
    if (dropped && !this.reconnectStopped) {
      // Exponential back-off with randomness, so the server can come back without a huge, immediate traffic spike
      const wait = Math.round(Math.random() * Math.min(MAX_WAIT_MS, FIRST_WAIT_MS * Math.pow(2, this.nTries)))
      this.nTries++
      console.debug(`Connection closed. Retrying in ${wait}ms....`)
      this.clearRetry()
      // (before telling the listeners, so one that closes the socket cancels it)
      this.retryTimer = setTimeout(this.connect, wait)
      this.emit('retry', wait)
    }
    this.state = SocketState.CLOSED
    this.emit('close')
    if (refused && ++this.refusals >= this.maxRefusals) {
      this.refusals = 0
      this.emit('refused')
    }
    if (unopened) {
      this.unopened++
      if (!this.everOpened && this.unopened >= BLOCKED_AFTER && !this.blocked) {
        this.blocked = true
        this.emit('blocked')
      }
      this.emit('unopened', this.unopened)
    }
  }

}
