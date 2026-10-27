import { ref } from 'vue'
import { Socket } from '@human-nature-lab/breadboard-core/src/socket'

export type ConnectionState = 'idle' | 'connecting' | 'open' | 'retrying' | 'closed'

/**
 * The core Socket, made to stay closed once closed. Between retries the core Socket's close() only
 * drops its listeners: the pending retry would still open a new connection later (and send what it
 * had queued). A closed AdminSocket never connects again and keeps nothing queued.
 */
export class AdminSocket extends Socket {
  private closed = false

  connect () {
    if (this.closed) return
    super.connect()
  }

  close () {
    this.closed = true
    super.close()
    this['_clearBuffer']()
  }
}

/**
 * The console's websocket (/connect, app/models/Breadboard.java). Every message carries the
 * admin's uid; the server answers with a stream of JSON messages, sometimes batched as
 * {queuedMessages: [...]}. Reconnects by itself with back-off; on every (re)connect it logs in
 * again, which makes the server send the full state.
 */
export class AdminConnection {
  state = ref<ConnectionState>('idle')
  retryInMs = ref(0)
  private socket: Socket | null = null
  private listeners: ((m: any) => void)[] = []
  private uid = ''

  start (uid: string, configured: string | undefined) {
    this.stop()
    this.uid = uid
    const socket = new AdminSocket(socketUrl(configured))
    this.socket = socket
    socket.on('connect', () => { this.state.value = this.state.value === 'idle' ? 'connecting' : this.state.value })
    socket.on('open', () => {
      this.state.value = 'open'
      this.retryInMs.value = 0
      // Logging in on every open: after a reconnect the server sends everything again.
      this.rawSend({ action: 'LogIn', uid: this.uid })
    })
    socket.on('retry', (wait: number) => {
      this.state.value = 'retrying'
      this.retryInMs.value = wait
    })
    socket.on('message', (text: string) => {
      let data: any
      try { data = JSON.parse(text) } catch (e) { return }
      if (data && Array.isArray(data.queuedMessages)) {
        for (const m of data.queuedMessages) this.dispatch(m)
      } else {
        this.dispatch(data)
      }
    })
    this.state.value = 'connecting'
    socket.connect()
  }

  stop () {
    if (this.socket) {
      this.socket.close()
      this.socket = null
    }
    this.state.value = 'closed'
  }

  onMessage (fn: (m: any) => void) {
    this.listeners.push(fn)
    return () => { this.listeners = this.listeners.filter(l => l !== fn) }
  }

  /**
   * Send an action (see the catalogue in app/models/Breadboard.java). Only while the connection is
   * open: returns false otherwise and sends nothing. (The socket would queue it and send it after the
   * next reconnect, perhaps minutes after the console said the action had failed.)
   */
  send (action: string, payload: Record<string, any> = {}): boolean {
    if (this.state.value !== 'open' || !this.socket) return false
    this.rawSend({ ...payload, action, uid: this.uid })
    return true
  }

  private rawSend (msg: Record<string, any>) {
    this.socket?.send(JSON.stringify(msg))
  }

  private dispatch (m: any) {
    for (const l of this.listeners) {
      try { l(m) } catch (e) { console.error('Error handling a server message', e, m) }
    }
  }
}

/** The configured socket address, or "auto" for this page's own host (wss on https). */
export function socketUrl (configured: string | undefined): string {
  const secure = window.location.protocol === 'https:'
  if (!configured || configured === 'auto') {
    return (secure ? 'wss://' : 'ws://') + window.location.host + '/connect'
  }
  if (secure && configured.startsWith('ws://')) return 'wss://' + configured.slice('ws://'.length)
  return configured
}
