import { http } from './http'
import { Mutex } from 'async-mutex'
import { Emitter } from 'goodish'
import { BreadboardConfig, BreadboardMessages, Loadable, SimpleMap } from './breadboard.types'
import { Socket, SocketState } from './socket'

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
// A choice or tie change still waiting for the connection after this long (ms) is dropped instead
// of sent: the participant has moved on, and the screen may no longer ask for it.
const ANSWER_MAX_AGE_MS = 5000
// The heartbeat of a participant's socket: the server answers {"action":"Ping"} with {"pong": <time>}
// (servers before 3.1 ignore it).
const PING = JSON.stringify({ action: 'Ping' })
// A participant's page that another page of theirs took the seat from ('elsewhere') takes it back by
// itself once that page has gone and this one is on screen. The page that holds the seat tells the
// participant's other pages in this browser as it goes (a BroadcastChannel per seat), and they ask
// the server whether a page of theirs still holds it: at once (after RELEASED_CHECK_MS, by when a
// reload of that page has taken the seat again), and every SEAT_CHECK_MS while they are on screen
// (a page in another browser, or one that crashed, tells nobody).
const SEAT_CHECK_MS = 20 * 1000
const RELEASED_CHECK_MS = 2 * 1000
const SEAT_CHECK_TIMEOUT_MS = 10 * 1000
// A participant's page whose connection had opened, and then can't open it again this many times
// in a row, asks the server over plain HTTP whether it is there (see checkServer)
const BLOCKED_AFTER_DROP = 5
// How long that question may take
const SERVER_CHECK_TIMEOUT_MS = 10 * 1000

/** Whether a frame is the server's answer to PING (every frame passes here: most are not parsed) */
function isPong (text: any): boolean {
  if (typeof text !== 'string' || text.length > 100 || text.indexOf('"pong"') < 0) return false
  try {
    const data = JSON.parse(text)
    return !!data && typeof data === 'object' && 'pong' in data
  } catch (e) {
    return false
  }
}

// Participant credentials. After login the server redirects to the participant page with the
// seat token in the URL fragment (it is also set as an HttpOnly cookie). Keep it in session
// storage and send it explicitly, because cookies are often blocked when the page is embedded
// in another site's iframe. Must stay in sync with security.ParticipantAuth.
// The researcher console opens the page the same way with a preview token, and with ?preview=1,
// in an opaque origin (a sandboxed iframe, or a tab the server sandboxes): there session storage
// and cookies throw, as they also do where a browser blocks storage. So the token is kept in
// memory as well, and in the history entry, which a reload keeps where storage is blocked (but
// not in an opaque origin, where browsers drop it: a reloaded preview has to be opened again).
// A participant's token is also kept in local storage for a day, as long as the server's cookie
// lasts, so a browser that was closed or crashed finds the seat again: session storage ends with
// the tab. Every page of the origin can read local storage, so it is forgotten as soon as it is no
// longer needed: the participant is done, or the session is over. Not a preview's, which isn't a
// participant's to keep.
const TOKEN_HEADER = 'X-Breadboard-Token'
const TOKEN_FRAGMENT_KEY = 'bbt'
const PREVIEW_QUERY = 'preview'
const KEEP_TOKEN_MS = 24 * 60 * 60 * 1000

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

/** Where this page's token is stored: one per experiment, session and participant */
function tokenKey (): string {
  return 'breadboard-token:' + window.location.pathname
}

export function participantToken (): string | null {
  if (typeof window === 'undefined') return null
  const storageKey = tokenKey()
  const fromFragment = takeFragmentToken(storageKey)
  if (fromFragment) {
    pageToken = fromFragment
    try {
      window.sessionStorage.setItem(storageKey, fromFragment)
    } catch (e) {}
    keepToken(storageKey, fromFragment)
    return fromFragment
  }
  if (!pageToken) {
    pageToken = sessionToken(storageKey) || historyToken(storageKey) || keptToken(storageKey)
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

/** Keep a participant's token in local storage (see KEEP_TOKEN_MS) */
function keepToken (storageKey: string, token: string) {
  if (isPreview()) return
  try {
    window.localStorage.setItem(storageKey, JSON.stringify({ token, until: Date.now() + KEEP_TOKEN_MS }))
  } catch (e) {}
}

/** The token kept in local storage, unless it has expired (then it is removed) */
function keptToken (storageKey: string): string | null {
  if (isPreview()) return null
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) return null
    const kept = JSON.parse(raw)
    if (kept && typeof kept.token === 'string' && kept.token && Date.now() < kept.until) return kept.token
    window.localStorage.removeItem(storageKey)
  } catch (e) {}
  return null
}

/**
 * Forget the token kept in local storage: the participant is done, the session is over for this
 * page, or the server no longer takes it. Not on a preview, which keeps none, and must leave a
 * participant's alone.
 */
function forgetToken () {
  if (isPreview()) return
  try {
    window.localStorage.removeItem(tokenKey())
  } catch (e) {}
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

// What a participant's page adds to its socket's address (controllers.ClientController): when the
// page was loaded, in the server's time (its state's loadedAt), and, when the participant asked for
// the seat on this page ("Use it here"), that it takes it. Of two pages of a participant's, the one
// they opened last keeps the seat: an older one that reconnects by itself is turned away.
const PAGE_LOADED_QUERY = 'bbPage'
const TAKE_SEAT_QUERY = 'bbTake'

/** The socket's address with these added (see PAGE_LOADED_QUERY) */
function withPageQuery (url: string, loadedAt: any, takeSeat: boolean): string {
  const added: string[] = []
  if (typeof loadedAt === 'number' && loadedAt > 0) added.push(PAGE_LOADED_QUERY + '=' + loadedAt)
  if (takeSeat) added.push(TAKE_SEAT_QUERY + '=1')
  if (!added.length) return url
  return url + (url.indexOf('?') >= 0 ? '&' : '?') + added.join('&')
}

/**
 * Ask for this page's state (/state), with its token; given up after `timeoutMs`, if set. With
 * `query` 'seat=1', only whether a page of the participant's holds their seat ({seatHeld}).
 */
function requestState (timeoutMs?: number, query?: string) {
  const token = participantToken()
  const init: any = token ? { headers: { [TOKEN_HEADER]: token } } : {}
  const url = query ? 'state?' + query : 'state'
  if (!timeoutMs || typeof AbortController === 'undefined') return http.get<BreadboardConfig>(url, init)
  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), timeoutMs)
  init.signal = abort.signal
  // (an answer is never the browser's copy of an earlier one)
  init.cache = 'no-store'
  const answer = http.get<BreadboardConfig>(url, init)
  answer.then(() => clearTimeout(timer), () => clearTimeout(timer))
  return answer
}

/** Whether the page is on screen (not a tab in the background, nor a minimized window) */
function onScreen (): boolean {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden'
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
  forgetToken()
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

/**
 * The page's live connection, as the participant should hear of it:
 * - 'connecting': the first attempt is under way
 * - 'open'
 * - 'lost': it dropped, or an attempt failed; it is reconnecting
 * - 'blocked': it can't open: it never has, after several attempts, or it had and then can't again
 *   although the server answers over plain HTTP. The network may not let it through; it goes on
 *   trying.
 * - 'elsewhere': another page took the seat: the study was opened in another tab or window (or, on
 *   a preview, the researcher opened another preview of this player); see useHere
 * - 'ended': the session is over for this page; it no longer reconnects
 * - 'watching': open, but only to watch: a researcher's preview of a player whose own page is
 *   connected, or of a computer player, sees what they see, and can't answer for them (see `watching`)
 */
export type ConnectionState = 'connecting' | 'open' | 'lost' | 'blocked' | 'elsewhere' | 'ended' | 'watching'

/** Emitted whenever `connection` may have changed */
export const CONNECTION_EVENT = 'bb:connection'

export class BreadboardClass extends Emitter implements BreadboardMessages {

  /** The session is over for this page (stopped, not running, ...): it no longer reconnects */
  public ended = false
  /** Another page took the seat (see 'elsewhere' in ConnectionState): this one no longer reconnects */
  public replaced = false
  private socket!: Socket
  private config!: BreadboardConfig | null
  private socketMutex = new Mutex()
  private configMutex = new Mutex()
  /** The page shows the participant's own end (see isOwnEnd) */
  private ownEnd = false
  /** recheckSeat() is waiting for the server */
  private checkingSeat = false
  /** The connection has dropped (or an attempt failed) at least once */
  private dropped = false
  /** The server said the current connection only watches; every new connection starts without */
  private watchingNow = false
  /**
   * The connection had opened, and now can't again although the server answers over plain HTTP:
   * the network lets the page reach the server but not open its live connection (see checkServer)
   */
  private liveBlocked = false
  /** checkServer found the server answering over HTTP and tried the connection again at once */
  private serverAnswered = false
  /** checkServer is waiting for the server */
  private checkingServer = false
  /** The participant asked for the seat on this page (useHere): the next connection takes it */
  private takingSeat = false
  /** Where the participant's pages of this seat in this browser tell each other that one goes */
  private seatChannel: BroadcastChannel | null = null
  /** Asks the server, while 'elsewhere', whether the page that took the seat still holds it */
  private seatChecks: ReturnType<typeof setInterval> | null = null
  /** The check after another page said it was going */
  private releasedCheck: ReturnType<typeof setTimeout> | null = null
  /** checkSeatFree is waiting for the server */
  private checkingSeatFree = false
  /** login() was called: the page signs in on its connection (see sendLogIn) */
  private signedIn = false
  /** How many connections have opened, and on which of them the page last signed in (-1: none) */
  private connections = 0
  private loginSentOn = -1
  /** When the page last heard from the server (Date.now()), over any connection; 0 before it has */
  private heardAt = 0
  /** When it last heard from the server before its connection dropped; null while it hasn't since */
  private outOfTouchSince: number | null = null

  constructor () {
    super()
    this.onPageHide = this.onPageHide.bind(this)
    this.onSeatVisibility = this.onSeatVisibility.bind(this)
  }

  /** Whether the live connection is open: what the page sends now reaches the server now */
  get isOpen (): boolean {
    return !!this.socket && this.socket.state === SocketState.OPEN
  }

  /** See ConnectionState */
  get connection (): ConnectionState {
    if (this.ended) return 'ended'
    if (this.replaced) return 'elsewhere'
    if (this.isOpen) return this.watchingNow ? 'watching' : 'open'
    if (this.socket && (this.socket.blocked || this.liveBlocked)) return 'blocked'
    return this.dropped ? 'lost' : 'connecting'
  }

  /** Whether the researcher console opened this page as a preview, rather than a participant */
  get preview (): boolean {
    return typeof window !== 'undefined' && isPreview()
  }

  /**
   * Whether this page only watches its player: a researcher's preview of a player whose own page
   * is connected, or of a computer player (see computerPlayer). The server sends it what the
   * player's page gets, and ignores what it sends; it sends no choices, tie changes or other
   * messages for them. The server decides again for every new connection, and hands the seat to a
   * preview that watches when the player's page goes ({"bbWatching": false}).
   */
  get watching (): boolean {
    return this.watchingNow
  }

  /**
   * Whether this page's player is a computer player (g.addAI's _1, _2, …; nobody signs in with an id
   * starting with "_"). A researcher's preview of one only watches what the computer does.
   */
  get computerPlayer (): boolean {
    const id = this.config && this.config.clientId
    return typeof id === 'string' && id.charAt(0) === '_'
  }

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
      // A participant's socket has a heartbeat; the console's (the previous console uses this class
      // too) would answer the ping with an error.
      const url = socketUrl(config.connectSocket)
      this.socket = new Socket(
        config.clientId ? () => withPageQuery(url, config.loadedAt, this.takingSeat) : url,
        undefined,
        config.clientId ? { ping: PING, isPong } : {})
      this.socket.on('close', () => { this.dropped = true })
      // (before CONNECTION_EVENT below: a new connection is the player's own until the server says)
      this.socket.on('connect', () => { this.watchingNow = false })
      this.socket.on('open', () => {
        this.liveBlocked = false
        this.serverAnswered = false
        // (the server has given it the seat)
        this.takingSeat = false
        this.connections++
        // Signed in on the first connection, and again on one that follows a drop, saying how long
        // the page was out of touch (before anything it queued meanwhile).
        if (this.signedIn && (this.loginSentOn < 0 || this.outOfTouchSince !== null)) this.sendLogIn()
      })
      this.socket.on('message', () => { this.heardAt = Date.now() })
      this.socket.on('pong', () => { this.heardAt = Date.now() })
      this.socket.on('close', () => {
        // (not the server closing a page another took the seat from, or whose session is over)
        if (this.heardAt > 0 && this.outOfTouchSince === null && !this.replaced && !this.ended) {
          this.outOfTouchSince = this.heardAt
        }
      })
      // A participant's page that had connected and can't again: the network, or the server?
      if (config.clientId) this.socket.on('unopened', (inARow: number) => this.onUnopened(inARow))
      // Propagate all socket events out to the Breadboard object
      for (const event of ['connect', 'open', 'close', 'error', 'retry']) {
        this.socket.on(event, (...args: any) => this.emit(event, ...args))
      }
      for (const event of ['connect', 'open', 'close', 'blocked']) {
        this.socket.on(event, () => this.emit(CONNECTION_EVENT))
      }
      // Taken and closed again without a word, time after time: ask what is going on.
      this.socket.on('refused', () => this.recheckSeat())
      this.attachParser()
      if (config.clientId && !this.preview) this.watchSeat(config)
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
    this.unwatchSeat()
    if (!this.socket) return
    this.socket.close()
    this.removeListeners()
  }

  /**
   * Connect again now instead of waiting for the next attempt (the participant's "Try now")
   */
  reconnectNow () {
    if (this.socket && !this.ended && !this.replaced) this.socket.reconnectNow()
  }

  /**
   * Take the seat back after another page took it (connection 'elsewhere'): connect again, once
   * (the other page is then told so), and go on as before. The connection asks for the seat, so
   * the server gives it even when the other page was opened after this one.
   */
  useHere () {
    if (!this.socket || this.ended || !this.replaced) return
    this.replaced = false
    this.stopSeatChecks()
    this.takingSeat = true
    this.socket.resume()
    this.emit(CONNECTION_EVENT)
  }

  /**
   * Send data to breadboard server
   * @param action
   * @param data
   * @param maxAge if the connection is down: how long (ms) it may wait for it before it is dropped
   * instead of sent; by default it waits as long as it takes
   */
  sendType (action: string, data: SimpleMap<any> = {}, maxAge?: number) {
    // A page that only watches answers nothing for its player (the server would ignore it anyway).
    if (this.watchingNow) {
      console.debug(`Not sent while watching: ${action}`)
      return
    }
    const d = Object.assign(data, {
      action
    })
    this.socket.send(JSON.stringify(d), maxAge)
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
    return this.sendType(MAKE_CHOICE, data, ANSWER_MAX_AGE_MS)
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
    return this.send(GROUP_CHOICE_EVENT, data, ANSWER_MAX_AGE_MS)
  }

  /**
   * Ask the server to add or cut the tie between two people in this player's view, using the
   * node ids from the graph. What is allowed is in player.edgeEdits; the server answers with an
   * EDGE_EDIT_RESULT_EVENT ({ ok, op, a, b, reason }). `turn` is player.edgeEdits.turn when the
   * change was offered: the server refuses it once the experiment has moved on to another turn.
   */
  sendEdgeEdit (op: 'add' | 'remove', a: string, b: string, turn?: number) {
    return this.send(EDGE_EDIT_EVENT, turn == null ? { op, a, b } : { op, a, b, turn }, ANSWER_MAX_AGE_MS)
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
   * @param maxAge see sendType
   */
  sendCustom (params?: SimpleMap<any>, maxAge?: number) {
    return this.sendType(CUSTOM_EVENT, params, maxAge)
  }

  /**
   * Send a custom event scoped to the current player. This will also trigger any global custom event handlers.
   * @param params
   * @param maxAge see sendType
   */
  async send (eventName: string, data: SimpleMap<any> = {}, maxAge?: number) {
    const config = await this.loadConfig()
    return this.sendCustom({
      playerId: config.clientId,
      eventName: eventName,
      data: data
    }, maxAge)
  }

  /**
   * Sign in on the live connection: now if it is open, else as it opens. From then on the page signs
   * in again on each connection that follows a drop (see sendLogIn). Once per connection, however
   * often it is called.
   */
  async login () {
    await this.loadConfig()
    await this.connect()
    this.signedIn = true
    if (this.isOpen && this.loginSentOn !== this.connections) this.sendLogIn()
  }

  /**
   * {"action": "LogIn", "clientId": ...}: only the id, as the server no longer stores the referer,
   * user agent and other headers this used to send, which were personal data and couldn't be
   * trusted anyway. On a connection that follows a drop, with `awayMs`: how long the page was out
   * of touch, from when it last heard from the server (the server records it as connectionGap).
   */
  private sendLogIn () {
    if (!this.config) return
    const login: SimpleMap<any> = { action: 'LogIn', clientId: this.config.clientId }
    if (this.outOfTouchSince !== null) {
      login.awayMs = Math.max(0, Date.now() - this.outOfTouchSince)
      this.outOfTouchSince = null
    }
    this.loginSentOn = this.connections
    this.socket.send(JSON.stringify(login))
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
   * The session is over for this page: it stops reconnecting, forgets the token it kept for a
   * browser that comes back, and tells its listeners (ENDED_EVENT)
   */
  private end () {
    this.socket.stopReconnecting()
    this.stopSeatChecks()
    forgetToken()
    if (!this.ended) {
      this.ended = true
      this.emit(ENDED_EVENT)
      this.emit(CONNECTION_EVENT)
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
   * A participant's page (not a preview: the console looks after those) listens for the other pages
   * of this seat in this browser (see SEAT_CHECK_MS), and tells them when it goes while it holds the
   * seat. A browser without BroadcastChannel, or one that won't let the page use it, leaves only the
   * checks every SEAT_CHECK_MS.
   */
  private watchSeat (config: BreadboardConfig) {
    if (typeof window === 'undefined') return
    try {
      if (typeof BroadcastChannel !== 'undefined') {
        const channel = new BroadcastChannel(`bb-seat:${config.experimentId}:${config.experimentInstanceId}:${config.clientId}`)
        channel.onmessage = (ev: MessageEvent) => {
          if (ev.data && ev.data.bb === 'released') this.otherPageWent()
        }
        this.seatChannel = channel
      }
    } catch (e) {
      this.seatChannel = null
    }
    window.addEventListener('pagehide', this.onPageHide)
    document.addEventListener('visibilitychange', this.onSeatVisibility)
  }

  private unwatchSeat () {
    this.stopSeatChecks()
    if (this.seatChannel) {
      try {
        this.seatChannel.close()
      } catch (e) {}
      this.seatChannel = null
    }
    if (typeof window !== 'undefined') {
      window.removeEventListener('pagehide', this.onPageHide)
      document.removeEventListener('visibilitychange', this.onSeatVisibility)
    }
  }

  /** The page goes (closed, reloaded, left): if it held the seat, the participant's other pages hear so */
  private onPageHide () {
    if (this.replaced || this.ended || !this.socket || !this.socket.hasOpened || !this.seatChannel) return
    try {
      this.seatChannel.postMessage({ bb: 'released' })
    } catch (e) {}
  }

  /** The participant's page that held the seat went: is the seat free now? */
  private otherPageWent () {
    if (!this.replaced || this.ended) return
    if (this.releasedCheck !== null) clearTimeout(this.releasedCheck)
    this.releasedCheck = setTimeout(() => {
      this.releasedCheck = null
      this.checkSeatFree()
    }, RELEASED_CHECK_MS)
  }

  /** Coming on screen while 'elsewhere': is the seat free now? */
  private onSeatVisibility () {
    if (this.replaced && !this.ended && onScreen()) this.checkSeatFree()
  }

  private startSeatChecks () {
    if (this.seatChecks === null && !this.preview && this.config && this.config.clientId) {
      this.seatChecks = setInterval(() => this.checkSeatFree(), SEAT_CHECK_MS)
    }
  }

  private stopSeatChecks () {
    if (this.seatChecks !== null) clearInterval(this.seatChecks)
    this.seatChecks = null
    if (this.releasedCheck !== null) clearTimeout(this.releasedCheck)
    this.releasedCheck = null
  }

  /**
   * While 'elsewhere' and on screen: ask the server whether a page of the participant's still holds
   * the seat, and take it back if none does (the other page was closed, or lost its connection for
   * good). Not while the page is in the background: a page nobody looks at would keep the seat from
   * the computer, which takes it after the usual wait; the page asks again as it comes on screen.
   */
  private async checkSeatFree () {
    if (!this.replaced || this.ended || this.checkingSeatFree || !onScreen()) return
    this.checkingSeatFree = true
    try {
      const res = await requestState(SEAT_CHECK_TIMEOUT_MS, 'seat=1')
      if (res.status !== 200) return
      const body: any = await res.json()
      if (body && body.seatHeld === false && this.replaced && !this.ended && onScreen()) this.useHere()
    } catch (e) {
      // (the server can't be reached: the next check)
    } finally {
      this.checkingSeatFree = false
    }
  }

  /**
   * An attempt to connect again didn't open, `inARow` in a row. From the BLOCKED_AFTER_DROP-th on,
   * the page asks the server over plain HTTP (checkServer) after each one. When the server answered
   * and the attempt made right after that didn't open either, the network lets the page reach the
   * server but not open its live connection: 'blocked', which tells the participant to try another
   * network. A page that has never connected is the socket's own 'blocked'.
   */
  private onUnopened (inARow: number) {
    if (!this.socket.hasOpened || inARow < BLOCKED_AFTER_DROP) return
    if (this.serverAnswered) {
      this.serverAnswered = false
      this.setLiveBlocked(true)
    }
    this.checkServer()
  }

  /**
   * Ask for the page's state over plain HTTP. When the server doesn't answer (or answers that it
   * can't serve: a proxy's 502), it is down, and 'lost' is what the participant should hear. When it
   * answers, the live connection is tried again at once, unless the page already says it is
   * 'blocked' (a server that has just come back takes it).
   */
  private async checkServer () {
    if (this.checkingServer || this.ended || this.replaced) return
    this.checkingServer = true
    let answers = false
    try {
      const res = await requestState(SERVER_CHECK_TIMEOUT_MS)
      answers = res.status < 500
    } catch (e) {
      // (it can't be reached)
    } finally {
      this.checkingServer = false
    }
    if (this.isOpen || this.ended || this.replaced) return
    if (!answers) {
      this.serverAnswered = false
      this.setLiveBlocked(false)
    } else if (!this.liveBlocked) {
      this.serverAnswered = true
      this.socket.reconnectNow()
    }
  }

  private setLiveBlocked (blocked: boolean) {
    if (this.liveBlocked === blocked) return
    this.liveBlocked = blocked
    this.emit(CONNECTION_EVENT)
  }

  /**
   * Handle parsing breadboard socket events
   */
  private attachParser () {
    this.socket.on('message', (d: string) => {
      this.emit('message', d)
      const data = JSON.parse(d)
      if (data && data.bbClosed === 'replaced') {
        // Another page took the seat: the participant's own, in another tab or window, or another
        // preview of the researcher's; or this page reconnected by itself while one the participant
        // opened after it holds the seat. Taking it back by reconnecting would have that page take it
        // back in turn, for ever: only useHere().
        this.socket.stopReconnecting()
        if (!this.replaced && !this.ended) {
          this.replaced = true
          this.startSeatChecks()
          this.emit(CONNECTION_EVENT)
        }
        return
      }
      if (data && data.bbWatching === true) {
        // A preview of a player whose own page is connected: the server mirrors what that page gets
        // and leaves it alone. Sent first, or once the player's page comes back and takes the seat
        // from a preview that had it.
        if (!this.watchingNow) {
          this.watchingNow = true
          this.emit(CONNECTION_EVENT)
        }
        return
      }
      if (data && data.bbWatching === false) {
        // The player's own page has gone, and the seat passed to this preview: it acts for them
        // until their page is back.
        if (this.watchingNow) {
          this.watchingNow = false
          this.emit(CONNECTION_EVENT)
        }
        return
      }
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
        const ownEnd = isOwnEnd(player)
        // Done: a browser that comes back has no seat to find (this tab keeps its own copy).
        if (ownEnd && !this.ownEnd) forgetToken()
        this.ownEnd = ownEnd
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
