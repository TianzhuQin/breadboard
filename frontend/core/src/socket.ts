import { Emitter, clamp, randomInt } from 'goodish'

export enum SocketState {
  CLOSED,
  OPEN,
  CLOSING,
  PENDING
}

/**
 * A WebSocket wrapper with automatic reconnection with exponential backoff and message queuing.
 * Once closed with close(), it stays closed: it never reconnects and sends nothing more.
 */
export class Socket extends Emitter {
  public state: SocketState = SocketState.CLOSED
  public socket!: WebSocket
  private nTries = 0
  private maxWait = 2 * 1000 * 60
  private buffer: any[] = []
  /** The reconnect waiting to happen after the connection dropped */
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  /** close() was called */
  private closed = false

  constructor (private url: string, private protocols?: string | string[]) {
    super()
    this.connect = this.connect.bind(this)
  }

  /**
   * Open the WebSocket connection
   */
  public connect () {
    // (a retry scheduled before close() must not open a connection nobody listens to)
    if (this.closed) return
    this.clearRetry()
    console.log('socket.connect')
    this.state = SocketState.PENDING
    this.emit('connect')
    if (this.protocols) {
      this.socket = new WebSocket(this.url, this.protocols)
    } else {
      this.socket = new WebSocket(this.url)
    }
    this.socket.addEventListener('close', this.onClose.bind(this))
    this.socket.addEventListener('error', this.onError.bind(this))
    this.socket.addEventListener('message', this.onMessage.bind(this))
    this.socket.addEventListener('open', this.onOpen.bind(this))
  }

  /**
   * Close the connection for good: no reconnect follows, also when one was waiting to happen, and
   * nothing queued is sent
   */
  public close () {
    this.closed = true
    this.clearRetry()
    if (this.state !== SocketState.CLOSING && this.state !== SocketState.CLOSED) {
      this.socket.close()
      this.state = SocketState.CLOSING
    }
    this._clearBuffer()
    this.removeListeners()
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

  /**
   * Flush the messages buffer
   */
  private flush () {
    if (this.socket.readyState !== WebSocket.OPEN) return
    for (const message of this.buffer) {
      this._send(message)
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
   * and sent once a connection is made.
   * @param message 
   */
  public send (message: any) {
    if (this.closed) return
    if (this.state === SocketState.OPEN) {
      this._send(message)
    } else {
      this.buffer.push(message)
    }
  }

  /**
   * Handle the WebSocket.onopen event internally
   */
  private onOpen () {
    this.state = SocketState.OPEN
    this.emit('open')
    this.nTries = 1
    this.flush()
  }

  /**
   * Handle the WebSocket.onmessage event internally
   * @param data 
   */
  private onMessage (ev: MessageEvent) {
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
    if (this.state !== SocketState.CLOSING && !this.closed) {
      this.nTries++
      // Exponential back-off w/ randomness to make it easier for the server to come back online w/out a huge, immediate traffic spike
      const wait = clamp(this.nTries * 2000 + randomInt(500, 4000), 1000, this.maxWait)
      console.debug(`Connection closed. Retrying in ${wait}ms....`)
      this.clearRetry()
      // (before telling the listeners, so one that closes the socket cancels it)
      this.retryTimer = setTimeout(this.connect, wait)
      this.emit('retry', wait)
    }
    this.state = SocketState.CLOSED
    this.emit('close')
  }

}