import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import {
  DEVICES, FIRST_LOOK_MAX_MS, TILE_WIDTHS, FIRST_LOOK_QUIET_MS, MAX_PREVIEWS, autoPicked, frameGeometry, heldByPreview, isAIPlayer,
  isComputerPlayer, isConnected, isTestPlayer, joinUrl, matchesPlayer, naturalWidth, ownPageConnected, previewMemory,
  previewPageUrl, previewRole, quickPick, seatText, seatWord, shownReadOnly, sortPlayers, tileWidth, truthyFlag, widthToFit,
  awayText,
} from '../src/pages/preview/preview'
import { fetchPreviewToken } from '../src/pages/preview/previewLink'
import { HttpError } from '../src/lib/http'

// POST /preview-token answers a token naming the player and how many were asked for, so a test
// can tell a fresh token from an old one
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
let minted = 0
function mintToken (_url: string, body: any) {
  minted++
  return Promise.resolve({ token: `tok-${body.clientId}-${minted}`, expiresAt: '2026-10-28T09:00:00Z' })
}
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})

vi.mock('@/store/admin', async () => {
  const { reactive } = await import('vue')
  const { LiveGraph } = await import('../src/lib/graph')
  const { computed, ref } = await import('vue')
  const admin = reactive({ experiment: null as any, instance: null as any, engine: 'ready', events: [] as any[] })
  const actions = { selectInstance: vi.fn(async () => ({ user: {} })) }
  const running = computed(() => admin.instance?.status === 'RUNNING')
  return { admin, graph: new LiveGraph(), actions, running, connectionState: ref('open') }
})
vi.mock('@/ui', async (orig) => {
  const real = await orig<typeof import('../src/ui')>()
  return {
    ...real,
    confirm: vi.fn(async () => true),
    notify: { success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() },
  }
})
const push = vi.fn()
// the page's address (?player=... comes from the player panel's "Preview their screen")
const route = reactive({ query: {} as Record<string, any> })
const replace = vi.fn((to: any) => { route.query = to.query || {} })
vi.mock('vue-router', () => ({ useRouter: () => ({ push, replace }), useRoute: () => route }))

const store = await import('@/store/admin') as any
const ui = await import('@/ui') as any

const P = (id: string, data: Record<string, any> = {}) => ({ id, data })

describe('who a player is', () => {
  it('reads flags the way scripts set them', () => {
    for (const v of [true, 'true', 1, '1', 'yes', 'TRUE']) expect(truthyFlag(v)).toBe(true)
    for (const v of [false, 'false', 0, '0', '', ' ', null, undefined, 'no', 'off']) expect(truthyFlag(v)).toBe(false)
  })

  it('finds test, computer and connected players', () => {
    expect(isTestPlayer(P('a', { testmode: true }))).toBe(true)
    expect(isTestPlayer(P('a', { testmode: 'false' }))).toBe(false)
    expect(isTestPlayer(P('a'))).toBe(false)
    expect(isAIPlayer(P('_bot1'))).toBe(true)
    expect(isAIPlayer(P('p1', { ai: 1 }))).toBe(true)
    expect(isAIPlayer(P('p1', { ai: 0 }))).toBe(false)
    // created as one; a person the computer took over is still a person
    expect(isComputerPlayer(P('_bot1'))).toBe(true)
    expect(isComputerPlayer(P('p1', { ai: 1 }))).toBe(false)
    expect(isConnected(P('p1', { _system: { connected: true } }))).toBe(true)
    expect(isConnected(P('p1', { _system: { connected: false } }))).toBe(false)
    expect(isConnected(P('p1'))).toBe(false)
  })

  it('tells their own page from a preview that holds their seat', () => {
    const own = P('p1', { _system: { connected: true } })
    const preview = P('p2', { _system: { connected: true, preview: true } })
    const away = P('p3', { _system: { connected: false } })
    // a preview that took the seat as their page went: it stands in, and they count as away
    const standIn = P('p4', { _system: { connected: false, preview: true } })
    expect([own, preview, away, standIn].map(ownPageConnected)).toEqual([true, false, false, false])
    expect([own, preview, away, standIn].map(heldByPreview)).toEqual([false, true, false, true])
    expect([own, preview, away, standIn].map(isConnected)).toEqual([true, true, false, false])
  })

  it('says what a preview of each player does', () => {
    const list = [
      P('_1', { ai: 1 }), // a computer player: watched
      P('p1', { _system: { connected: true } }), // their own page: watched
      P('t1', { testmode: true, _system: { connected: true, preview: true } }), // played by a preview
      P('p2', { _system: { connected: false, preview: true } }), // away, a preview stands in
      P('p3', { ai: 1, _system: { connected: false } }), // away, the computer took their seat
      P('t2', { testmode: true }), // nobody on the seat
    ]
    expect(list.map(previewRole)).toEqual(['computer', 'watching', 'plays', 'away', 'away', null])
    expect(list.map(p => seatWord(p))).toEqual(['Watching', 'Watching', '', 'Away', 'Away', ''])
    expect(list.map(p => seatText(p))).toEqual([
      'played by the computer, so a preview is read-only',
      'their own page is connected, so a preview is read-only',
      'a preview plays this seat: their own page hasn’t been in this session',
      'away: a preview stands in for them',
      'away: the computer plays their seat',
      'not connected',
    ])
    // shown here, the reason still comes first
    expect(seatText(list[3], true)).toBe('away: a preview stands in for them, shown here')
  })

  it('says what standing in for someone away means now', () => {
    const away = P('p2', { _system: { connected: false, preview: true } })
    expect(awayText(away, { gameStarted: false, takeoverSeconds: 30 }))
      .toBe('Away: you are standing in, but they count as away, so a waiting room doesn’t count them.')
    expect(awayText(away, { gameStarted: true, takeoverSeconds: 30 }))
      .toBe('Away: you are standing in; the computer takes over their seat after 30 s away.')
    expect(awayText(away, { gameStarted: true, takeoverSeconds: 0 }))
      .toBe('Away: you are standing in; the computer takes over their seat at once.')
    // (the steps decide, if they let the computer play at all)
    expect(awayText(away, { gameStarted: true, takeoverSeconds: null }))
      .toBe('Away: you are standing in, but they count as away: the computer takes their seat after the usual wait, if it plays for people who leave.')
    // Someone whose seat the computer took: that they get it back when they return is said only when
    // the console knows it (the autopilot's own session, with its takeover on: its "Give the seat back
    // when they return"). With that off, the note promised a return that never came.
    const taken = P('p3', { ai: 1, _system: { connected: false, preview: true } })
    expect(awayText(taken, { gameStarted: true, takeoverSeconds: 30, returnToHuman: true }))
      .toBe('Away: you are standing in, and the computer plays their seat until their own page is back.')
    expect(awayText(taken, { gameStarted: true, takeoverSeconds: 45, returnToHuman: false }))
      .toBe('Away: you are standing in, and the computer plays their seat; it doesn’t give it back when they return.')
    for (const opts of [{ gameStarted: true, takeoverSeconds: null, returnToHuman: null }, { gameStarted: true, takeoverSeconds: 30 }, { gameStarted: true }]) {
      expect(awayText(taken, opts))
        .toBe('Away: you are standing in, and the computer plays their seat; it gives it back when they return if the steps say so.')
    }
  })

  it('searches by id, or by "test" and "ai"', () => {
    expect(matchesPlayer(P('Alice'), 'ali')).toBe(true)
    expect(matchesPlayer(P('p1', { testmode: true }), 'test')).toBe(true)
    expect(matchesPlayer(P('_x'), 'ai')).toBe(true)
    expect(matchesPlayer(P('p1'), 'zzz')).toBe(false)
  })
})

describe('quick picks', () => {
  const players = [P('p10'), P('p2', { testmode: true }), P('_ai1'), P('p1', { ai: 1, testmode: 'true' }), P('p3')]

  it('orders players naturally', () => {
    expect(sortPlayers(players).map(p => p.id)).toEqual(['_ai1', 'p1', 'p2', 'p3', 'p10'])
  })
  it('picks test players, computer players or the first N', () => {
    expect(quickPick(players, 'test').map(p => p.id)).toEqual(['p1', 'p2'])
    expect(quickPick(players, 'ai').map(p => p.id)).toEqual(['_ai1', 'p1'])
    expect(quickPick(players, 'first', 3).map(p => p.id)).toEqual(['_ai1', 'p1', 'p2'])
    expect(quickPick(players, 'first', 0)).toEqual([])
    expect(quickPick(players, 'first', 99)).toHaveLength(5)
  })
  it('finds players whose own page is connected, and computer players, not already shown (their previews only watch)', () => {
    const list = [P('a', { _system: { connected: true } }), P('b', { _system: { connected: true } }), P('c'),
      P('d', { _system: { connected: true, preview: true } }), P('e', { _system: { connected: false, preview: true } }),
      P('_1'), P('_2'), P('f', { ai: 1 })]
    expect(shownReadOnly(list, new Set(['b', '_2'])).map(p => p.id)).toEqual(['a', '_1'])
  })
  it('picks the test players first shown, but none whose seat is connected or held by a preview', () => {
    const list = [P('t1', { testmode: true }), P('t2', { testmode: true, _system: { connected: true } }),
      P('t3', { testmode: true, _system: { connected: true, preview: true } }), P('t4', { testmode: true, _system: { connected: false } }),
      P('t5', { testmode: true, _system: { connected: false, preview: true } }), P('p5')]
    expect(autoPicked(list).map(p => p.id)).toEqual(['t1', 't4'])
    const many = Array.from({ length: MAX_PREVIEWS + 3 }, (_, i) => P(`t${i}`, { testmode: true }))
    expect(autoPicked(many)).toHaveLength(MAX_PREVIEWS)
  })
})

describe('addresses', () => {
  it('builds the sandboxed participant page with its preview token, and the sign-up page', () => {
    const t = { experimentId: 12, instanceId: 44, playerId: 'p1' }
    expect(previewPageUrl(t, 'abc', 'https://lab.org')).toBe('https://lab.org/game/12/44/p1/connected?preview=1#bbt=abc')
    expect(previewPageUrl({ ...t, playerId: 'a b/c' }, 'x/y+z=', 'https://lab.org'))
      .toBe('https://lab.org/game/12/44/a%20b%2Fc/connected?preview=1#bbt=x%2Fy%2Bz%3D')
    // the page reads the fragment with URLSearchParams: the token comes back exactly
    expect(new URLSearchParams(previewPageUrl(t, 'x/y+z=&a', 'https://lab.org').split('#')[1]).get('bbt')).toBe('x/y+z=&a')
    // participants' own sign-up link is unchanged
    expect(joinUrl(12, 44, 'https://lab.org')).toBe('https://lab.org/game/12/44/login')
  })

  it('asks the server for a preview token of exactly that player and session', async () => {
    http.post.mockReset()
    http.post.mockResolvedValueOnce({ token: 't1', expiresAt: '2026-10-28T09:00:00Z' })
    expect(await fetchPreviewToken({ experimentId: 12, instanceId: 0, playerId: '_bot' })).toEqual({ token: 't1', expiresAt: '2026-10-28T09:00:00Z' })
    expect(http.post).toHaveBeenCalledWith('/preview-token', { experimentId: 12, experimentInstanceId: 0, clientId: '_bot' })
    // an answer without a token is an error, not a page without one
    http.post.mockResolvedValueOnce({})
    await expect(fetchPreviewToken({ experimentId: 12, instanceId: 0, playerId: 'p1' })).rejects.toThrow(/no preview token/)
  })
})

describe('preview sizes', () => {
  it('scales the desktop page like the old Testing panel (quarter size at 320px)', () => {
    const g = frameGeometry('desktop', 320)
    expect(g.scale).toBe(0.25)
    expect(g.height).toBe(200)
    expect(g.bezel).toBe(0)
    expect([g.deviceWidth, g.deviceHeight]).toEqual([DEVICES.desktop.width, DEVICES.desktop.height])
  })

  it('gives phones a bezel that scales with them', () => {
    const full = frameGeometry('phone', naturalWidth('phone'))
    expect(full.scale).toBe(1)
    expect(full.bezel).toBe(12)
    const half = frameGeometry('phone', naturalWidth('phone') / 2)
    expect(half.scale).toBeCloseTo(0.5, 2)
    expect(half.bezel).toBeCloseTo(6, 1)
    expect(half.height).toBe(Math.round((DEVICES.phone.height + 24) / 2))
  })

  it('fits two Medium tiles in a row beside the player list in a 1280 px window', () => {
    // the window, less the sidebar (248), the page's padding (2 × 28), the list (272) and its gap (20),
    // and a scroll bar (17)
    const room = 1280 - 248 - 2 * 28 - 272 - 20 - 17
    expect(2 * tileWidth('desktop', 'M', room) + 18).toBeLessThanOrEqual(room)
    expect(TILE_WIDTHS.desktop.M).toBeGreaterThanOrEqual(300)
  })

  it('never makes a tile wider than the space there is', () => {
    expect(tileWidth('desktop', 'L')).toBe(540)
    expect(tileWidth('desktop', 'L', 343)).toBe(343)
    expect(tileWidth('desktop', 'S', 1000)).toBe(260)
    expect(tileWidth('phone', 'M', 80)).toBe(140) // a floor, so it stays recognisable
  })

  it('fits the full-size view to the window, never above 100%', () => {
    expect(widthToFit('desktop', 5000, 5000)).toBe(1280)
    expect(widthToFit('desktop', 640, 5000)).toBe(640)
    expect(widthToFit('desktop', 5000, 400)).toBe(640) // 1280×800 → height-bound
    const phone = widthToFit('phone', 2000, 434)
    expect(frameGeometry('phone', phone).height).toBeLessThanOrEqual(434)
  })
})

// ---- the page -------------------------------------------------------------------------------------

describe('PreviewPage', () => {
  async function mountPage () {
    const PreviewPage = (await import('../src/pages/preview/PreviewPage.vue')).default
    const w = mount(PreviewPage, { attachTo: document.body })
    await flushPromises()
    return w
  }
  function addPlayer (id: string, props: Record<string, any> = {}) {
    store.graph.apply({ action: 'addNode', id })
    for (const [key, value] of Object.entries(props)) store.graph.apply({ action: 'nodePropertyChanged', id, key, value })
  }
  async function settle () {
    // graph changes reach views on the next animation frame
    await new Promise(r => setTimeout(r, 40))
    await flushPromises()
  }
  async function firstLook () {
    // the first look waits until the network has been quiet a moment
    await new Promise(r => setTimeout(r, FIRST_LOOK_QUIET_MS + 60))
    await flushPromises()
  }

  beforeEach(() => {
    vi.clearAllMocks()
    http.post.mockReset()
    http.post.mockImplementation(mintToken)
    minted = 0
    store.graph.clear()
    Object.assign(previewMemory, { instanceId: null, ids: [], autoPicked: false, device: 'desktop', size: 'M', interactive: false })
    store.admin.experiment = { id: 7, name: 'Trust game', instances: [] }
    store.admin.instance = { id: 44, name: 'pilot', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] }
    route.query = {}
    try { localStorage.clear() } catch (e) { /* ignore */ }
  })
  afterEach(() => { document.body.innerHTML = '' })

  it('explains that previews need a running session', async () => {
    store.admin.instance = null
    const w = await mountPage()
    expect(w.text()).toContain('No session is running')
    // nothing to resume: no offer to
    expect(w.text()).not.toContain('Resume an earlier one')
    w.unmount()
    store.admin.experiment = { id: 7, name: 'Trust game', instances: [{ id: 40, name: 'pilot 0', status: 'FINISHED' }, { id: 41, name: 'pilot 1', status: 'STOPPED' }] }
    const w1 = await mountPage()
    expect(w1.text()).toContain('Resume an earlier one')
    w1.unmount()

    store.admin.instance = { id: 44, name: 'pilot', status: 'STOPPED', creationTime: '', hitId: '', hits: [], data: [] }
    const w2 = await mountPage()
    expect(w2.text()).toContain('“pilot” is stopped')
    expect(w2.find('iframe').exists()).toBe(false)
    // no dead end: the stopped session can be resumed from here
    await w2.findAll('button').find(b => b.text() === 'Resume “pilot”')!.trigger('click')
    await flushPromises()
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Resume “pilot”?' }))
    expect(store.actions.selectInstance).toHaveBeenCalledWith(44)
    w2.unmount()
  })

  it('offers to join as a newcomer when nobody is there yet', async () => {
    const w = await mountPage()
    expect(w.text()).toContain('Nobody is in the network yet')
    const join = w.findAll('a').find(a => a.text().includes('Join as a new participant'))!
    expect(join.attributes('href')).toBe(`${window.location.origin}/game/7/44/login`)
    expect(join.attributes('target')).toBe('_blank')
    w.unmount()
  })

  // (not those whose seat is connected: their own page, or another console's preview, which a
  // preview from here would take the seat from)
  it('shows the test players at once, but none whose seat is connected', async () => {
    addPlayer('p1', { testmode: true })
    addPlayer('p2', { testmode: true, _system: { connected: true } })
    addPlayer('p3')
    addPlayer('p4', { testmode: true, _system: { connected: true, preview: true } })
    addPlayer('p5', { testmode: true })
    await settle()
    const w = await mountPage()
    await firstLook()
    const frames = w.findAll('iframe')
    expect(frames).toHaveLength(2)
    expect(http.post).toHaveBeenCalledWith('/preview-token', { experimentId: 7, experimentInstanceId: 44, clientId: 'p1' })
    expect(frames[0].attributes('src')).toBe(`${window.location.origin}/game/7/44/p1/connected?preview=1#bbt=tok-p1-1`)
    expect(frames[0].attributes('title')).toBe('Participant page of p1')
    expect(frames[1].attributes('title')).toBe('Participant page of p5')
    expect(ui.confirm).not.toHaveBeenCalled()
    w.unmount()
  })

  // On a direct load (or a reload) the page is there before the network: the server sends each
  // player, then their properties. The first look used to be taken at the first players seen, before
  // their testmode had arrived, and showed no test players, or only some of them.
  it('takes the first look once the network has come in, also on a direct load', async () => {
    const w = await mountPage()
    try {
      // the players first …
      addPlayer('t1')
      addPlayer('t2')
      addPlayer('p3')
      await settle()
      expect(w.find('.pp__count').text()).toBe('0 of 3 shown')
      // … then their properties
      await new Promise(r => setTimeout(r, 60))
      store.graph.apply({ action: 'nodePropertyChanged', id: 't1', key: 'testmode', value: true })
      store.graph.apply({ action: 'nodePropertyChanged', id: 't2', key: 'testmode', value: true })
      await settle()
      await firstLook()
      expect(previewMemory.ids).toEqual(['t1', 't2'])
      expect(w.findAll('.pt iframe')).toHaveLength(2)
    } finally {
      w.unmount()
    }
  })

  it('takes the first look when the first test players appear, and only once', async () => {
    addPlayer('p1')
    await settle()
    const w = await mountPage()
    try {
      await firstLook()
      expect(previewMemory.ids).toEqual([])
      expect(w.text()).toContain('Choose whose page to see')
      // test players made later (in the Script console, say)
      addPlayer('t1', { testmode: true })
      await settle()
      await firstLook()
      expect(previewMemory.ids).toEqual(['t1'])
      // later ones are the researcher's to add
      addPlayer('t2', { testmode: true })
      await settle()
      await firstLook()
      expect(previewMemory.ids).toEqual(['t1'])
    } finally {
      w.unmount()
    }
  })

  it('leaves the first look to the researcher once they have chosen', async () => {
    addPlayer('p1')
    addPlayer('t1', { testmode: true })
    await settle()
    const w = await mountPage()
    try {
      // ticked before the network had been quiet long enough
      await w.findAll('.pp__item').find(li => li.text().includes('p1'))!.find('input[type="checkbox"]').setValue(true)
      await firstLook()
      expect(previewMemory.ids).toEqual(['p1'])
    } finally {
      w.unmount()
    }
  })

  it('takes the first look in a network that keeps changing, after FIRST_LOOK_MAX_MS at most', async () => {
    addPlayer('t1', { testmode: true })
    await settle()
    const started = Date.now()
    const w = await mountPage()
    // a property that changes more often than the network is ever quiet
    const ticker = setInterval(() => store.graph.apply({ action: 'nodePropertyChanged', id: 't1', key: 'clock', value: Date.now() }), 100)
    try {
      while (!previewMemory.ids.length && Date.now() - started < FIRST_LOOK_MAX_MS + 1500) await new Promise(r => setTimeout(r, 50))
      expect(previewMemory.ids).toEqual(['t1'])
      expect(Date.now() - started).toBeGreaterThanOrEqual(FIRST_LOOK_MAX_MS - 100)
    } finally {
      clearInterval(ticker)
      w.unmount()
    }
  })

  it('runs each preview in an opaque origin on its own token, never on the researcher\'s sign-in', async () => {
    addPlayer('p1', { testmode: true })
    addPlayer('p2', { testmode: true })
    await settle()
    const w = await mountPage()
    await firstLook()
    const frames = w.findAll('iframe')
    expect(frames).toHaveLength(2)
    for (const f of frames) {
      // no allow-same-origin: the page can't reach the console, its cookies or its socket; no
      // allow-top-navigation or allow-popups-to-escape-sandbox either
      expect(f.attributes('sandbox')).toBe('allow-scripts allow-forms allow-modals allow-popups')
    }
    // one token per tile, each for its own player
    expect(http.post.mock.calls.map(c => c[1].clientId).sort()).toEqual(['p1', 'p2'])
    expect(frames.map(f => f.attributes('src')).every(src => /\/connected\?preview=1#bbt=tok-p\d-\d$/.test(src!))).toBe(true)
    // with the page isolated, the warning that previews run with the researcher's rights is gone
    expect(w.find('.pv__warn').exists()).toBe(false)
    expect(w.text()).not.toMatch(/researcher sign-in/)
    // what stays true is said once and can be put away: a preview is a live seat, or only watches
    expect(w.find('.pv__notice').text()).toMatch(/Previews are live/)
    expect(w.find('.pv__notice').text()).toMatch(/A player whose own page is connected, and a computer player, are shown read-only/)
    expect(w.find('.pv__notice').text()).toMatch(/A test player, whose own page hasn’t been in the session, is played here/)
    expect(w.find('.pv__notice').text()).toMatch(/Anyone else \(someone who left, or whose seat the computer took\) can be answered for here, but counts as away/)
    expect(w.find('.pv__notice').text()).not.toMatch(/taking the connection/)
    await w.find('.pv__notice button').trigger('click')
    expect(w.find('.pv__notice').exists()).toBe(false)
    w.unmount()
  })

  it('fetches a new token on reload and when the tile\'s session changes', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const w = await mountPage()
    await firstLook()
    expect(w.find('iframe').attributes('src')).toMatch(/\/game\/7\/44\/p1\/connected\?preview=1#bbt=tok-p1-1$/)
    await w.find('.pt__acts button').trigger('click') // Reload
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(2)
    expect(w.find('iframe').attributes('src')).toMatch(/#bbt=tok-p1-2$/)

    // (a new session: its first look is taken anew)
    store.admin.instance = { id: 45, name: 'pilot 2', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] }
    await firstLook()
    expect(http.post).toHaveBeenLastCalledWith('/preview-token', { experimentId: 7, experimentInstanceId: 45, clientId: 'p1' })
    expect(w.find('iframe').attributes('src')).toBe(`${window.location.origin}/game/7/45/p1/connected?preview=1#bbt=tok-p1-3`)
    w.unmount()
  })

  it('shows the server\'s refusal in the tile, with a Retry', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    http.post.mockReset()
    http.post.mockRejectedValueOnce(new HttpError(400, 'That session is not of this experiment.'))
    http.post.mockImplementation(mintToken)
    const w = await mountPage()
    await firstLook()
    expect(w.find('iframe').exists()).toBe(false)
    const err = w.find('.pf__error')
    expect(err.attributes('role')).toBe('alert')
    expect(err.text()).toMatch(/That session is not of this experiment\./)
    await err.find('button').trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(2)
    expect(w.find('.pf__error').exists()).toBe(false)
    expect(w.find('iframe').attributes('src')).toMatch(/\/game\/7\/44\/p1\/connected\?preview=1#bbt=tok-p1-/)
    w.unmount()
  })

  it('opens a new tab at once, cut off from the console, and loads the page there once its token is here', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const tab = { opener: {} as any, location: { href: '' }, closed: false, close: vi.fn() }
    const open = vi.spyOn(window, 'open').mockImplementation(() => tab as any)
    try {
      const w = await mountPage()
      await firstLook()
      let answer!: (v: any) => void
      http.post.mockImplementationOnce(() => new Promise(resolve => { answer = resolve }))
      const PreviewTile = (await import('../src/pages/preview/PreviewTile.vue')).default
      w.findComponent(PreviewTile).vm.$emit('open-tab')
      // opened in the same turn as the click, so pop-up blockers let it through
      expect(open).toHaveBeenCalledWith('about:blank', '_blank')
      expect(tab.opener).toBeNull()
      expect(tab.location.href).toBe('')
      answer({ token: 'tab-token', expiresAt: '2026-10-28T09:00:00Z' })
      await flushPromises()
      expect(http.post).toHaveBeenLastCalledWith('/preview-token', { experimentId: 7, experimentInstanceId: 44, clientId: 'p1' })
      expect(tab.location.href).toBe(`${window.location.origin}/game/7/44/p1/connected?preview=1#bbt=tab-token`)
      // one connection per player: the tile steps aside
      expect(w.find('.pt iframe').exists()).toBe(false)
      expect(w.find('.pt').text()).toContain('Open in another tab')
      w.unmount()
    } finally {
      open.mockRestore()
    }
  })

  it('closes the new tab and says why when no token comes', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const tab = { opener: {} as any, location: { href: '' }, closed: false, close: vi.fn() }
    const open = vi.spyOn(window, 'open').mockImplementation(() => tab as any)
    try {
      const w = await mountPage()
      await firstLook()
      http.post.mockRejectedValueOnce(new HttpError(400, 'Unknown session'))
      const PreviewTile = (await import('../src/pages/preview/PreviewTile.vue')).default
      w.findComponent(PreviewTile).vm.$emit('open-tab')
      await flushPromises()
      await settle()
      expect(tab.close).toHaveBeenCalled()
      expect(tab.location.href).toBe('')
      expect(ui.notify.error).toHaveBeenCalledWith('Couldn’t open p1’s page in a new tab', 'Unknown session')
      // the tile shows the page again
      expect(w.find('.pt iframe').exists()).toBe(true)
      w.unmount()
    } finally {
      open.mockRestore()
    }
  })

  // A preview of a connected participant used to take their seat, after a warning; the server now
  // lets it only watch, so there is nothing to ask.
  it('previews a connected participant without asking, and says the preview is read-only', async () => {
    addPlayer('p1')
    addPlayer('p2', { _system: { connected: true } })
    await settle()
    const w = await mountPage()
    const box = w.findAll('.pp__item').find(li => li.text().includes('p2'))!.find('input[type="checkbox"]')
    await box.setValue(true)
    await flushPromises()
    expect(ui.confirm).not.toHaveBeenCalled()
    expect(previewMemory.ids).toEqual(['p2'])
    expect(ui.notify.info).toHaveBeenCalledWith('p2 is connected right now', expect.stringMatching(/^Their preview is read-only: you see what they see, but can’t answer for them\./))

    // a player nobody is using is added without a word
    const box1 = w.findAll('.pp__item').find(li => li.text().includes('p1'))!.find('input[type="checkbox"]')
    await box1.setValue(true)
    await flushPromises()
    expect(ui.notify.info).toHaveBeenCalledTimes(1)
    expect(previewMemory.ids).toEqual(['p2', 'p1'])
    w.unmount()
  })

  // _system.preview: a researcher's preview has the seat (another console's), not their own page
  it('tells a seat a preview holds from the player\'s own page', async () => {
    addPlayer('p1', { _system: { connected: true } })
    addPlayer('p2', { _system: { connected: true, preview: true } })
    addPlayer('p3', { _system: { connected: false } })
    // their own page has been in the session and isn't now: the preview stands in, and they count as away
    addPlayer('p4', { _system: { connected: false, preview: true } })
    addPlayer('_1', { ai: 1 })
    await settle()
    const w = await mountPage()
    const dot = (id: string) => w.findAll('.pp__item').find(li => li.text().includes(id))!.find('.pp__dot')
    expect(dot('p1').attributes('title')).toBe('their own page is connected, so a preview is read-only')
    expect(dot('p2').attributes('title')).toBe('a preview plays this seat: their own page hasn’t been in this session')
    expect(dot('p3').attributes('title')).toBe('not connected')
    expect(dot('p2').classes()).toContain('is-on')
    expect(dot('p4').attributes('title')).toBe('away: a preview stands in for them')
    expect(dot('p4').classes()).not.toContain('is-on')
    expect(dot('_1').attributes('title')).toBe('played by the computer, so a preview is read-only')
    // not read-only: what the preview does counts as theirs, so there is no note
    await w.findAll('.pp__item').find(li => li.text().includes('p2'))!.find('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(previewMemory.ids).toEqual(['p2'])
    expect(ui.notify.info).not.toHaveBeenCalled()
    w.unmount()
  })

  // At tile size the participant page's own "Watching only" banner is a few pixels high.
  it('tags the tiles of a connected participant and of a computer player "Watching"', async () => {
    addPlayer('_1', { ai: 1 })
    addPlayer('p1', { _system: { connected: true } })
    addPlayer('t1', { testmode: true })
    await settle()
    previewMemory.instanceId = 44
    previewMemory.ids = ['_1', 'p1', 't1']
    const w = await mountPage()
    try {
      await settle()
      const tile = (name: string) => w.findAll('.pt').find(t => t.find('.pt__id').text() === name)!
      const tag = (name: string) => tile(name).find('.pt__tag.is-text')
      expect(tag('AI 1 (_1)').text()).toBe('Watching')
      expect(tag('AI 1 (_1)').attributes('title')).toBe('Watching: the computer plays this seat, so you see what it does but can’t answer for it')
      expect(tag('p1').text()).toBe('Watching')
      expect(tag('p1').attributes('title')).toBe('Watching: their own page is connected, so you see their screen but can’t answer for them')
      expect(tag('t1').exists()).toBe(false)
      // a small tile keeps the word for screen readers and the tooltip
      previewMemory.size = 'S'
      previewMemory.device = 'phone'
      await settle()
      expect(tag('p1').find('.sr-only').text()).toBe('Watching')
    } finally {
      w.unmount()
    }
  })

  it('says in the list, in a word anyone can see, which previews only watch and who is away', async () => {
    addPlayer('_1', { ai: 1 })
    addPlayer('p1', { _system: { connected: true } })
    addPlayer('p2', { _system: { connected: false, preview: true } })
    addPlayer('t1', { testmode: true })
    await settle()
    const w = await mountPage()
    try {
      const row = (id: string) => w.findAll('.pp__item').find(li => li.find('.pp__id').text() === id)!
      const word = (id: string) => row(id).find('.pp__word')
      expect(word('AI 1 (_1)').text()).toBe('Watching')
      expect(word('p1').text()).toBe('Watching')
      expect(word('p1').attributes('title')).toBe('their own page is connected, so a preview is read-only')
      expect(word('p2').text()).toBe('Away')
      expect(word('t1').exists()).toBe(false)
      // shown here: the reason is still there, before "shown here"
      await row('p2').find('input[type="checkbox"]').setValue(true)
      await flushPromises()
      expect(row('p2').find('.pp__dot').attributes('title')).toBe('away: a preview stands in for them, shown here')
      expect(row('p2').find('.sr-only:last-child').text()).toBe(', away: a preview stands in for them, shown here')
      expect(w.find('.pp__legend').text()).toContain('watching only')
    } finally {
      w.unmount()
    }
  })

  // A preview standing in for someone who left looked like their live page, with nothing on the tile
  // to say they were away.
  it('says on the tile of someone away that the preview stands in, and when the computer takes over', async () => {
    addPlayer('p2', { _system: { connected: false, preview: true } })
    addPlayer('p3', { _system: { connected: true } })
    await settle()
    store.admin.instance = { ...store.admin.instance, hasStarted: true }
    // the autopilot's own session, with its takeover after 30 s
    http.get.mockImplementation(async (url: string) => url === '/autopilot'
      ? { settings: { enabled: true, experimentId: 7, replaceDisconnected: { enabled: true, afterSeconds: 30, returnToHuman: true } }, source: 'admin', status: { on: true, experimentId: 7, instance: { id: 44, name: 'pilot', status: 'RUNNING' } } }
      : undefined)
    previewMemory.instanceId = 44
    previewMemory.ids = ['p2', 'p3']
    const w = await mountPage()
    try {
      await settle()
      const tile = (id: string) => w.findAll('.pt').find(t => t.find('.pt__id').text() === id)!
      expect(tile('p2').find('.pt__tag.is-away').text()).toBe('Away')
      expect(tile('p2').find('.pt__note').text()).toBe('Away: you are standing in; the computer takes over their seat after 30 s away.')
      expect(tile('p3').find('.pt__tag.is-away').exists()).toBe(false)
      expect(tile('p3').find('.pt__note').exists()).toBe(false)
      expect(http.get).toHaveBeenCalledWith('/autopilot')
    } finally {
      w.unmount()
      http.get.mockReset()
    }
  })

  // The autopilot's own session, with "Give the seat back when they return" off: the note said the
  // computer played the seat "until their own page is back", and it kept it when they came back.
  const autopilotTakingOver = (returnToHuman: boolean) => async (url: string) => url === '/autopilot'
    ? { settings: { enabled: true, experimentId: 7, replaceDisconnected: { enabled: true, afterSeconds: 45, returnToHuman } }, source: 'admin', status: { on: true, experimentId: 7, instance: { id: 44, name: 'pilot', status: 'RUNNING' } } }
    : undefined

  it('says the computer keeps the seat of someone who returns when the autopilot doesn\'t give it back', async () => {
    addPlayer('p3', { ai: 1, _system: { connected: false, preview: true } })
    await settle()
    store.admin.instance = { ...store.admin.instance, hasStarted: true }
    http.get.mockImplementation(autopilotTakingOver(false))
    previewMemory.instanceId = 44
    previewMemory.ids = ['p3']
    const w = await mountPage()
    try {
      await settle()
      expect(http.get).toHaveBeenCalledWith('/autopilot')
      expect(w.find('.pt__tag.is-away').text()).toBe('Away')
      expect(w.find('.pt__note').text()).toBe('Away: you are standing in, and the computer plays their seat; it doesn’t give it back when they return.')
      await w.find('button.pt__cover').trigger('click')
      await settle()
      expect(document.body.querySelector('.b-dialog__subtitle')!.textContent).toBe('Away: you are standing in, and the computer plays their seat; it doesn’t give it back when they return. Clicks here are real choices for this player, and are recorded.')
    } finally {
      w.unmount()
      http.get.mockReset()
    }
  })

  it('says the computer gives the seat back only when the autopilot says so, and otherwise that the steps decide', async () => {
    addPlayer('p3', { ai: 1, _system: { connected: false, preview: true } })
    await settle()
    store.admin.instance = { ...store.admin.instance, hasStarted: true }
    previewMemory.instanceId = 44
    previewMemory.ids = ['p3']
    const noteWith = async (get: (url: string) => Promise<any>) => {
      http.get.mockImplementation(get)
      const w = await mountPage()
      try {
        await settle()
        return w.find('.pt__note').text()
      } finally {
        w.unmount()
        http.get.mockReset()
      }
    }
    expect(await noteWith(autopilotTakingOver(true)))
      .toBe('Away: you are standing in, and the computer plays their seat until their own page is back.')
    // a session that isn't the autopilot's, or one where the steps' own takeover is used
    const steps = 'Away: you are standing in, and the computer plays their seat; it gives it back when they return if the steps say so.'
    expect(await noteWith(async () => ({ settings: { enabled: false }, source: 'off', status: { on: false } }))).toBe(steps)
    expect(await noteWith(async () => ({
      settings: { enabled: true, experimentId: 7, replaceDisconnected: { enabled: false, afterSeconds: 45, returnToHuman: true } },
      source: 'admin',
      status: { on: true, experimentId: 7, instance: { id: 44, name: 'pilot', status: 'RUNNING' } },
    }))).toBe(steps)
    // the autopilot's own session is another one
    expect(await noteWith(async () => ({
      settings: { enabled: true, experimentId: 7, replaceDisconnected: { enabled: true, afterSeconds: 45, returnToHuman: true } },
      source: 'admin',
      status: { on: true, experimentId: 7, instance: { id: 45, name: 'next', status: 'RUNNING' } },
    }))).toBe(steps)
    // no answer from the server
    expect(await noteWith(async () => { throw new Error('offline') })).toBe(steps)
  })

  it('says full size, too, that the preview stands in for someone away', async () => {
    addPlayer('p2', { _system: { connected: false, preview: true } })
    await settle()
    store.admin.instance = { ...store.admin.instance, hasStarted: true }
    previewMemory.instanceId = 44
    previewMemory.ids = ['p2']
    const w = await mountPage()
    try {
      await settle()
      await w.find('button.pt__cover').trigger('click')
      await settle()
      expect(document.body.querySelector('.b-dialog__subtitle')!.textContent).toBe('Away: you are standing in, but they count as away: the computer takes their seat after the usual wait, if it plays for people who leave. Clicks here are real choices for this player, and are recorded.')
    } finally {
      w.unmount()
    }
  })

  it('says the steps decide when the session isn\'t the autopilot\'s, and that a waiting room doesn\'t count someone away', async () => {
    addPlayer('p2', { _system: { connected: false, preview: true } })
    await settle()
    http.get.mockResolvedValue({ settings: { enabled: false }, source: 'off', status: { on: false } })
    previewMemory.instanceId = 44
    previewMemory.ids = ['p2']
    const w = await mountPage()
    try {
      await settle()
      expect(w.find('.pt__note').text()).toBe('Away: you are standing in, but they count as away, so a waiting room doesn’t count them.')
      store.admin.instance = { ...store.admin.instance, hasStarted: true }
      await settle()
      expect(w.find('.pt__note').text()).toBe('Away: you are standing in, but they count as away: the computer takes their seat after the usual wait, if it plays for people who leave.')
    } finally {
      w.unmount()
      http.get.mockReset()
    }
  })

  it('previews a computer player read-only, and says so', async () => {
    addPlayer('_1', { ai: 1 })
    addPlayer('_2', { ai: 1 })
    addPlayer('p1', { ai: 1 })
    await settle()
    const w = await mountPage()
    await w.findAll('.pp__picks button').find(b => b.text().startsWith('AI players'))!.trigger('click')
    await flushPromises()
    expect(previewMemory.ids).toEqual(['_1', '_2', 'p1'])
    // (a person the computer took over is still theirs to answer for)
    expect(ui.notify.info).toHaveBeenCalledTimes(1)
    expect(ui.notify.info).toHaveBeenCalledWith('2 of these are computer players',
      'Their previews are read-only: you see what the computer does, and it goes on playing.')
    w.unmount()
  })

  // A tile kept across a Stop and Resume (or a reload of the engine), of someone who hasn't joined
  // again: the server adds nobody for a preview of a real session, and the page says they aren't in
  // the game. Once they are back in the network, their page is loaded again.
  it('says a player who isn\'t in the game isn\'t, and loads their page again once they join', async () => {
    addPlayer('p2')
    await settle()
    previewMemory.instanceId = 44
    previewMemory.ids = ['p1', 'p2']
    const w = await mountPage()
    try {
      await settle()
      const tile = (id: string) => w.findAll('.pt').find(t => t.find('.pt__id').text() === id)!
      expect(tile('p1').find('.pt__note').text()).toBe('Not in the game right now: a preview adds nobody. Their page shows here once they join.')
      expect(tile('p2').find('.pt__note').exists()).toBe(false)
      const first = tile('p1').find('iframe').attributes('src')
      expect(first).toMatch(/\/game\/7\/44\/p1\/connected\?preview=1#bbt=tok-p1-\d$/)
      const p2Src = tile('p2').find('iframe').attributes('src')

      addPlayer('p1')
      await settle()
      expect(tile('p1').find('.pt__note').exists()).toBe(false)
      const again = tile('p1').find('iframe').attributes('src')
      expect(again).toMatch(/#bbt=tok-p1-\d$/)
      expect(again).not.toBe(first)
      expect(tile('p2').find('iframe').attributes('src')).toBe(p2Src)

      // the same in the full-size view: shown while they are away, loaded again as they come back
      store.graph.clear()
      addPlayer('p2')
      await settle()
      await tile('p1').find('button.pt__cover').trigger('click')
      await settle()
      const shown = document.body.querySelector('.pfx iframe')!.getAttribute('src')
      addPlayer('p1')
      await settle()
      expect(document.body.querySelector('.pfx iframe')!.getAttribute('src')).not.toBe(shown)
    } finally {
      w.unmount()
    }
  })

  // (named as the console names it everywhere else: "AI 1 (_1)", not the bare id)
  it('says that a single computer player is one, by its name', async () => {
    addPlayer('_1', { ai: 1 })
    addPlayer('p1')
    await settle()
    const w = await mountPage()
    await w.findAll('.pp__item').find(li => li.text().includes('_1'))!.find('input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(previewMemory.ids).toEqual(['_1'])
    expect(ui.notify.info).toHaveBeenCalledTimes(1)
    expect(ui.notify.info).toHaveBeenCalledWith('AI 1 (_1) is a computer player',
      'Its preview is read-only: you see what the computer does, and it goes on playing.')
    w.unmount()
  })

  it('names a computer player in the full-size view as on its tile', async () => {
    addPlayer('_6', { ai: 1 })
    await settle()
    previewMemory.instanceId = 44
    previewMemory.ids = ['_6']
    const w = await mountPage()
    await settle()
    expect(w.find('.pt__id').text()).toBe('AI 6 (_6)')
    await w.find('button.pt__cover').trigger('click')
    await settle()
    expect(document.body.querySelector('.b-dialog__title')!.textContent).toBe('What AI 6 (_6) sees')
    expect(document.body.querySelector('.pfx iframe')!.getAttribute('title')).toBe('Participant page of AI 6 (_6)')
    // what a click does, for this player: nothing, the computer plays it
    expect(document.body.querySelector('.b-dialog__subtitle')!.textContent).toBe('Watching: the computer plays this seat, so you see what it does but can’t answer for it.')
    w.unmount()
  })

  it('names a computer player when its page can\'t be opened in a new tab', async () => {
    addPlayer('_6', { ai: 1 })
    await settle()
    previewMemory.instanceId = 44
    previewMemory.ids = ['_6']
    const tab = { opener: {} as any, location: { href: '' }, closed: false, close: vi.fn() }
    const open = vi.spyOn(window, 'open').mockImplementation(() => tab as any)
    const w = await mountPage()
    try {
      await settle()
      http.post.mockRejectedValueOnce(new HttpError(400, 'Unknown session'))
      const PreviewTile = (await import('../src/pages/preview/PreviewTile.vue')).default
      w.findComponent(PreviewTile).vm.$emit('open-tab')
      await flushPromises()
      expect(tab.close).toHaveBeenCalled()
      expect(ui.notify.error).toHaveBeenCalledWith('Couldn’t open AI 6 (_6)’s page in a new tab', 'Unknown session')
    } finally {
      w.unmount()
      open.mockRestore()
    }
  })

  it('says once which players of a quick pick are shown read-only', async () => {
    addPlayer('p1', { _system: { connected: true } })
    addPlayer('p2', { _system: { connected: true } })
    addPlayer('p3')
    await settle()
    const w = await mountPage()
    await w.find('.pp__n-input input').setValue(3)
    await w.findAll('.pp__first button')[0].trigger('click')
    await flushPromises()
    expect(previewMemory.ids).toEqual(['p1', 'p2', 'p3'])
    expect(ui.confirm).not.toHaveBeenCalled()
    expect(ui.notify.info).toHaveBeenCalledTimes(1)
    expect(ui.notify.info).toHaveBeenCalledWith('2 of these players are connected right now', expect.stringMatching(/^Their previews are read-only/))
    w.unmount()
  })

  // It used to be a single look at a connected participant, ended (and their seat given back) as
  // the full-size view closed.
  it('opens a player full size from the player panel, and keeps their tile afterwards', async () => {
    addPlayer('p1')
    addPlayer('p2', { _system: { connected: true } })
    await settle()
    route.query = { player: 'p2' }
    const w = await mountPage()
    await settle()
    expect(replace).toHaveBeenCalled()
    expect(route.query.player).toBeUndefined()
    expect(previewMemory.ids).toEqual(['p2'])
    expect(document.body.querySelector('.pfx iframe')!.getAttribute('src')).toContain('/game/7/44/p2/connected')
    expect(document.body.querySelector('.b-dialog__subtitle')!.textContent).toBe('Watching: their own page is connected, so you see their screen but can’t answer for them.')
    expect(ui.notify.info).toHaveBeenCalledWith('p2 is connected right now', expect.any(String))
    const PreviewFocus = (await import('../src/pages/preview/PreviewFocus.vue')).default
    w.findComponent(PreviewFocus).vm.$emit('close')
    await settle()
    expect(previewMemory.ids).toEqual(['p2'])
    expect(w.find('.pt iframe').exists()).toBe(true)
    expect(ui.notify.info).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('quick-picks computer players and the first N', async () => {
    addPlayer('_ai1')
    addPlayer('p1')
    addPlayer('p2')
    addPlayer('p3')
    await settle()
    const w = await mountPage()
    await w.findAll('button').find(b => b.text().startsWith('AI players'))!.trigger('click')
    await flushPromises()
    expect(previewMemory.ids).toEqual(['_ai1'])
    await w.find('.pp__n-input input').setValue(2)
    await w.findAll('.pp__first button')[0].trigger('click')
    await flushPromises()
    expect(previewMemory.ids).toEqual(['_ai1', 'p1'])
    w.unmount()
  })

  it('keeps to the preview limit', async () => {
    for (let i = 0; i < MAX_PREVIEWS + 3; i++) addPlayer(`p${i}`)
    await settle()
    const w = await mountPage()
    await w.find('.pp__n-input input').setValue(MAX_PREVIEWS + 3)
    await w.findAll('.pp__first button')[0].trigger('click')
    await flushPromises()
    expect(previewMemory.ids).toHaveLength(MAX_PREVIEWS)
    expect(ui.notify.warning).toHaveBeenCalled()
    w.unmount()
  })

  it('opens a preview full size and lets the tile step aside meanwhile', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const w = await mountPage()
    await firstLook()
    await w.find('button.pt__cover').trigger('click')
    await flushPromises()
    // one connection per player: the tile no longer holds a page
    expect(w.find('.pt iframe').exists()).toBe(false)
    expect(w.find('.pt').text()).toContain('Shown full size')
    expect(document.body.querySelector('.pfx iframe')!.getAttribute('src')).toContain('/game/7/44/p1/connected')
    expect(document.body.querySelector('.b-dialog__subtitle')!.textContent).toBe('Live: clicks here are real choices for this player, and are recorded.')
    w.unmount()
  })
})
