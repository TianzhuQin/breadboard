import { http } from './http'
import { Mutex } from 'async-mutex'
import { Emitter } from 'goodish'
import { BreadboardConfig, BreadboardMessages, Loadable, SimpleMap } from './breadboard.types'
import { Socket } from './socket'

const MAKE_CHOICE = 'MakeChoice'
const CUSTOM_EVENT = 'CustomEvent'
// Group-scoped choices don't exist in the platform's global action map, so they
// can't be resolved via MAKE_CHOICE / a.choose(uid). Instead the click is sent as
// a custom event that the group's own handler listens for. Must stay in sync with
// Games.SUBMIT_EVENT in groovy/groups.groovy.
const GROUP_CHOICE_EVENT = 'group-action-submit'
// Editing ties from the network view. Must stay in sync with groovy/edge_edits.groovy.
const EDGE_EDIT_EVENT = 'bb:edge-edit'
export const EDGE_EDIT_RESULT_EVENT = 'bb:edge-edit-result'

// Participant credentials. After login the server redirects to the participant page with the
// seat token in the URL fragment (it is also set as an HttpOnly cookie). Keep it in session
// storage and send it explicitly, because cookies are often blocked when the page is embedded
// in another site's iframe. Must stay in sync with security.ParticipantAuth.
// The researcher console opens the page the same way with a preview token, and with ?preview=1,
// in an opaque origin (a sandboxed iframe, or a tab the server sandboxes): there session storage
// and cookies throw, as they also do where a browser blocks storage. So the token is kept in
// memory as well, and in the history entry, which a reload keeps where storage is blocked (but
// not in an opaque origin, where browsers drop it: a reloaded preview has to be opened again).
const TOKEN_HEADER = 'X-Breadboard-Token'
const TOKEN_FRAGMENT_KEY = 'bbt'
const PREVIEW_QUERY = 'preview'

/** The token this page was given, once found */
let pageToken: string | null = null

// The admin websocket address may be configured as "auto": use the page's own host. A ws://
// address is upgraded to wss:// on https pages, where browsers refuse insecure sockets.
export function socketUrl (configured: string | undefined): string {
  const secure = typeof window !== 'undefined' && window.location.protocol === 'https:'
  if (!configured || configured === 'auto') {
    return (secure ? 'wss://' : 'ws://') + window.location.host + '/connect'
  }
  if (secure && configured.startsWith('ws://')) {
    return 'wss://' + configured.slice('ws://'.length)
  }
  return configured
}

export function participantToken (): string | null {
  if (typeof window === 'undefined') return null
  const storageKey = 'breadboard-token:' + window.location.pathname
  const fromFragment = takeFragmentToken(storageKey)
  if (fromFragment) {
    pageToken = fromFragment
    try {
      window.sessionStorage.setItem(storageKey, fromFragment)
    } catch (e) {}
    return fromFragment
  }
  if (!pageToken) {
    pageToken = sessionToken(storageKey) || historyToken(storageKey)
  }
  return pageToken
}

/**
 * The token in the address's fragment (#bbt=...), if there is one. It is then dropped from the
 * address bar so it isn't bookmarked, shared or kept in history, and put in the state of this
 * history entry instead, where a reload of the page finds it again (if the browser kept it).
 */
function takeFragmentToken (storageKey: string): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ''))
  const token = params.get(TOKEN_FRAGMENT_KEY)
  if (!token) return null
  params.delete(TOKEN_FRAGMENT_KEY)
  const rest = params.toString()
  try {
    const state = window.history.state
    const withToken = Object.assign({}, state && typeof state === 'object' ? state : {}, { [storageKey]: token })
    window.history.replaceState(withToken, '', window.location.pathname + window.location.search + (rest ? '#' + rest : ''))
  } catch (e) {
    // (a browser that won't rewrite a sandboxed page's address leaves the token in it)
  }
  return token
}

function sessionToken (storageKey: string): string | null {
  try {
    return window.sessionStorage.getItem(storageKey)
  } catch (e) {
    return null
  }
}

function historyToken (storageKey: string): string | null {
  try {
    const state = window.history.state
    const token = state && typeof state === 'object' ? state[storageKey] : null
    return typeof token === 'string' && token ? token : null
  } catch (e) {
    return null
  }
}

/** Whether the researcher console opened this page as a preview */
function isPreview (): boolean {
  return new URLSearchParams(window.location.search).get(PREVIEW_QUERY) === '1'
}

/**
 * Shown in place of the page when a preview has no token the server accepts: it ran out, the
 * researcher signed out, or the page was reloaded. The participant login the page would go to
 * instead offers a real seat, which a preview must not take.
 */
function showPreviewEnded () {
  const app = document.getElementById('app')
  if (!app) return
  const page = document.createElement('div')
  page.className = 'bb-page'
  const panel = document.createElement('main')
  panel.className = 'bb-panel'
  const text = document.createElement('p')
  text.textContent = 'This preview can’t continue: its link has expired or the page was reloaded. Open it again from the researcher console.'
  panel.appendChild(text)
  page.appendChild(panel)
  app.textContent = ''
  app.appendChild(page)
}

/** Ask for this page's state (/state), with its token */
function requestState () {
  const token = participantToken()
  return http.get<BreadboardConfig>('state', token ? { headers: { [TOKEN_HEADER]: token } } : {})
}

/** Where /state's answer sends a page whose credentials the server doesn't take; else null */
function loginOf (status: number, body: any): string | null {
  return status === 401 && body && body.error === 'not-logged-in' && body.login ? body.login : null
}

/**
 * Leave a page the server doesn't let act for its participant: a preview says it can't continue,
 * a participant goes to the sign-in (which says so when the session isn't running). Returns why.
 */
function leavePage (login: string): string {
  if (isPreview()) {
    showPreviewEnded()
    return 'Preview token not accepted'
  }
  // A participant page opened without this seat's credentials (e.g. someone else's URL), or whose
  // seat the server has forgotten since (e.g. in a restart).
  window.location.href = login
  return 'Not logged in'
}

/**
 * Whether a player update is the server's own end of the session for this page: it was stopped,
 * isn't running, or started without them. The server marks those finished and ended.
 */
function endsSession (player: any): boolean {
  return !!player && typeof player === 'object' && !!player.finished && !!player.ended
}

/**
 * Whether a player update is the participant's own end: the experiment finished them, or gave them a
 * completion code (_system.recruitment). That screen matters more than a later notice from the server
 * that the session has stopped or isn't running.
 */
function isOwnEnd (player: any): boolean {
  if (!player || typeof player !== 'object' || player.ended) return false
  const recruitment = player._system && player._system.recruitment
  return !!player.finished || !!(recruitment && recruitment.completed)
}

/**
 * Emitted once the session is over for this page and it stopped reconnecting. Prefixed like the
 * other events of Breadboard's own ('bb:edge-edit-result'), so an experiment's custom events
 * (player.send(name, ...)) can't be taken for it.
 */
export const ENDED_EVENT = 'bb:ended'

export class BreadboardClass extends Emitter implements BreadboardMessages {

  /** The session is over for this page (stopped, not running, ...): it no longer reconnects */
  public ended = false
  private socket!: Socket
  private config!: BreadboardConfig | null
  private socketMutex = new Mutex()
  private configMutex = new Mutex()
  /** The page shows the participant's own end (see isOwnEnd) */
  private ownEnd = false
  /** recheckSeat() is waiting for the server */
  private checkingSeat = false

  /**
   * Load the client config.
   * @returns {Promise<object>}
   */
  async loadConfig (): Promise<BreadboardConfig> {
    const release = await this.configMutex.acquire()
    if (this.config) {
      release()
      return this.config
    }
    try {
      const res = await requestState()
      const body = await res.json()
      const login = loginOf(res.status, body)
      if (login) {
        throw new Error(leavePage(login))
      }
      this.config = body
      return this.config!
    } finally {
      release()
    }
  }

  /**
   * Returns the connected Socket instance.
   * @returns {Promise<Socket>}
   */
  async connect (): Promise<Socket> {
    const release = await this.socketMutex.acquire()
    if (this.socket) {
      release()
      return this.socket
    }
    try {
      const config = await this.loadConfig()
      this.socket = new Socket(socketUrl(config.connectSocket))
      // Propagate all socket events out to the Breadboard object
      for (const event of ['connect', 'open', 'close', 'error', 'retry']) {
        this.socket.on(event, (...args: any) => this.emit(event, ...args))
      }
      // Taken and closed again without a word, time after time: ask what is going on.
      this.socket.on('refused', () => this.recheckSeat())
      this.attachParser()
      this.socket.connect()
    } finally {
      release()
    }
    return this.socket
  }

  /**
   * Disconnect the WebSocket
   */
  disconnect () {
    this.config = null
    if (!this.socket) return
    this.socket.close()
    this.removeListeners()
  }

  /**
   * Send data to breadboard server
   * @param action
   * @param data
   */
  sendType (action: string, data: SimpleMap<any> = {}) {
    const d = Object.assign(data, {
      action
    })
    this.socket.send(JSON.stringify(d))
  }

  /**
   * Shortcut for sending a choice via breadboard. Helps keeps params from throwing silent bugs.
   * @param uuid 
   * @param params 
   */
  sendChoice (uuid: string, params: SimpleMap<any> = {}) {
    const data: any = {
      choiceUID: uuid
    }
    const customParams = this.getCustomParams()
    params = Object.assign(params, customParams)
    if (params && typeof params !== 'string') {
      data.params = JSON.stringify(params)
    }
    return this.sendType(MAKE_CHOICE, data)
  }

  /**
   * Shortcut for submitting a group-scoped choice. Unlike sendChoice (which the
   * server resolves against the global action map via a.choose(uid)), this routes
   * the click through the custom-event bus to the group's own handler. The server
   * delivers `data` to the player-scoped GROUP_CHOICE_EVENT listener, which matches
   * on `uid`. See the per-ask listener installed by Game.ask in groovy/groups.groovy.
   * @param uuid the uid of the group choice the player picked
   * @param params optional extra payload forwarded to the group handler
   */
  sendGroupChoice (uuid: string, params?: SimpleMap<any>) {
    const data: SimpleMap<any> = { uid: uuid }
    if (params) {
      data.params = params
    }
    return this.send(GROUP_CHOICE_EVENT, data)
  }

  /**
   * Ask the server to add or cut the tie between two people in this player's view, using the
   * node ids from the graph. What is allowed is in player.edgeEdits; the server answers with an
   * EDGE_EDIT_RESULT_EVENT ({ ok, op, a, b, reason }). `turn` is player.edgeEdits.turn when the
   * change was offered: the server refuses it once the experiment has moved on to another turn.
   */
  sendEdgeEdit (op: 'add' | 'remove', a: string, b: string, turn?: number) {
    return this.send(EDGE_EDIT_EVENT, turn == null ? { op, a, b } : { op, a, b, turn })
  }

  getCustomParams () {
    const map: { [key: string]: any } = {}
    const customInputs = Array.from(document.querySelectorAll('.param'))
    for (const inp of customInputs) {
      const name = inp.getAttribute('name')
      if (name) {
        const type = inp.getAttribute('type')
        if (inp instanceof HTMLInputElement) {
          if (type === 'checkbox') {
            if (!map.hasOwnProperty(name)) {
              map[name] = []
            }
            if (inp.checked!) {
              map[name].push(inp.value)
            }
          } else {
            map[name] = inp.value
          }
        } else if (inp instanceof HTMLTextAreaElement) {
          map[name] = inp.value
        } else {
          console.log(`.param class isn't on an input field`, inp)
        }
      } else {
        console.log('skipped field without a name', inp)
      }
    }
    return map
  }

  /**
   * Shortcut for sending a custom event via breadboard. 
   * @param params 
   */
  sendCustom (params?: SimpleMap<any>) {
    return this.sendType(CUSTOM_EVENT, params)
  }

  /**
   * Send a custom event scoped to the current player. This will also trigger any global custom event handlers.
   * @param params 
   */
  async send (eventName: string, data: SimpleMap<any> = {}) {
    const config = await this.loadConfig()
    return this.sendCustom({
      playerId: config.clientId,
      eventName: eventName,
      data: data
    })
  }

  /**
   * Login method
   */
  async login () {
    const config = await this.loadConfig()
    await this.connect()
    // Only the id: the server no longer stores the referer, user agent and other headers this
    // used to send, which were personal data and couldn't be trusted anyway.
    this.socket.send(JSON.stringify({
      action: 'LogIn',
      clientId: config.clientId
    }))
  }

  /**
   * Load a script from a URL
   * @param url
   * @returns {Promise<boolean>}
   */
  addScriptFromURL (url: string): Promise<void> {
    if (url.substr(0, 4) !== 'http') {
      if (url[0] !== '/') {
        url = '/' + url
      }
      url = this.getCoreRoot() + url
    }
    return new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = url
      script.onload = () => setTimeout(resolve)
      script.onerror = reject
      document.body.appendChild(script)
    })
  }

  /**
   * Load a script from text
   * @param contents
   */
  addScriptFromString (contents: string): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        var geval = eval
        geval(contents)
        setTimeout(resolve)
      } catch (err) {
        reject(err)
      }
    })
  }

  /**
   * Inject styles from a string
   * @param contents
   */
  addStyleFromString (contents: string): Promise<void> {
    return new Promise(resolve => {
      const style = document.createElement('style')
      const text = document.createTextNode(contents)
      style.appendChild(text)
      document.body.appendChild(style)
    })
  }

  /**
   * Inject CSS from a url using a "link" node
   * @param href
   */
  addStyleFromURL (href: string, type = 'text/css'): Promise<void> {
    if (href.substr(0, 4) !== 'http') {
      href = this.getCoreRoot() + href
    }
    return new Promise((resolve, reject) => {
      const link = document.createElement('link')
      link.href = href
      link.rel = 'stylesheet'
      link.type = type
      link.onload = () => resolve()
      link.onerror = reject
      document.head.appendChild(link)
    })
  }

  // Load a module
  async load (loader: Loadable) {
    const config = await this.loadConfig()
    return loader(this, config)
  }
   
  /**
   * Get an instance of the client graph
   */
  public async getGraph () {
    const v = (await import('./graph'))
    const Graph = v.Graph
    const graph = new Graph()
    graph.attachToBreadboard(this)
    return graph
  }

  /**
   * The session is over for this page: it stops reconnecting, and tells its listeners (ENDED_EVENT)
   */
  private end () {
    this.socket.stopReconnecting()
    if (!this.ended) {
      this.ended = true
      this.emit(ENDED_EVENT)
    }
  }

  /**
   * Ask for the page's state again, as loading the page does, after the server turned its socket
   * away. If the server no longer takes the page's credentials (it forgot the seat, e.g. in a
   * restart), go where loading the page would go: the sign-in, or the notice that the session isn't
   * running. A participant who had come to their own end keeps that screen instead. Otherwise (the
   * server can't be reached, or takes them after all) the socket goes on reconnecting.
   */
  private async recheckSeat () {
    if (this.checkingSeat || this.ended) return
    this.checkingSeat = true
    try {
      const res = await requestState()
      const login = loginOf(res.status, await res.json())
      if (!login) return
      if (this.ownEnd) {
        this.end()
        return
      }
      this.socket.close()
      leavePage(login)
    } catch (e) {
      // (the server can't be reached: the socket keeps trying)
    } finally {
      this.checkingSeat = false
    }
  }

  /**
   * Handle parsing breadboard socket events
   */
  private attachParser () {
    this.socket.on('message', (d: string) => {
      this.emit('message', d)
      const data = JSON.parse(d)
      if (data && data.reauth) {
        // The session runs, but the server has forgotten this page's seat (e.g. it restarted since):
        // find out where to sign in again.
        this.recheckSeat()
        return
      }
      if (data && data.sessionOver && typeof data.sessionOver === 'object') {
        // The server turned the socket away: the session isn't running (any more). Pages from before
        // 3.1 ignore this key; this one shows the screen inside, unless the participant's own end
        // (a completion code) is on the screen.
        this.end()
        if (!this.ownEnd && data.sessionOver.player) {
          this.emit('data', data.sessionOver)
          this.emit('player', data.sessionOver.player)
        }
        return
      }
      const player = data && data.player
      if (endsSession(player)) {
        // Reconnecting can't change that, and the screen says what to do now.
        this.end()
        // Someone who had come to their own end keeps it on the screen, with any completion code.
        if (this.ownEnd) return
      } else if (player && typeof player === 'object') {
        this.ownEnd = isOwnEnd(player)
      }
      this.emit('data', data)
      for (const key of ['graph', 'player', 'style']) {
        if (data[key]) {
          this.emit(key, data[key])
        }
      }
      if (data.eventName && data.data) {
        this.emit(data.eventName, ...data.data)
      }
    })
  }

  /**
   * Return the path of the breadboard core script
   */
  private getCoreRoot (): String {
    const scriptTag = Array.from(document.querySelectorAll('script')).find(s => /breadboard.*\.js$/.test(s.src))
    if (!scriptTag) {
      return window.location.origin
    }
    return new URL(scriptTag.src).origin
  }
}


export const Breadboard = new BreadboardClass()
// window.Breadboard = Breadboard
