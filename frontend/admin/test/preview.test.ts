import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import {
  DEVICES, MAX_PREVIEWS, frameGeometry, isAIPlayer, isConnected, isTestPlayer, joinUrl, matchesPlayer, naturalWidth,
  playerPageUrl, previewMemory, quickPick, sortPlayers, tileWidth, truthyFlag, widthToFit, wouldDisplace,
} from '../src/pages/preview/preview'

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
vi.mock('vue-router', () => ({ useRouter: () => ({ push }), useRoute: () => ({ query: {} }) }))

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
    expect(isConnected(P('p1', { _system: { connected: true } }))).toBe(true)
    expect(isConnected(P('p1', { _system: { connected: false } }))).toBe(false)
    expect(isConnected(P('p1'))).toBe(false)
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
  it('flags connected players not already shown (a preview would take their connection)', () => {
    const list = [P('a', { _system: { connected: true } }), P('b', { _system: { connected: true } }), P('c')]
    expect(wouldDisplace(list, new Set(['b'])).map(p => p.id)).toEqual(['a'])
  })
})

describe('addresses', () => {
  it('builds the participant page and the sign-up page', () => {
    expect(playerPageUrl(12, 44, 'p1', 'https://lab.org')).toBe('https://lab.org/game/12/44/p1/connected')
    expect(playerPageUrl(12, 44, 'a b/c', 'https://lab.org')).toBe('https://lab.org/game/12/44/a%20b%2Fc/connected')
    expect(joinUrl(12, 44, 'https://lab.org')).toBe('https://lab.org/game/12/44/login')
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

  beforeEach(() => {
    vi.clearAllMocks()
    store.graph.clear()
    Object.assign(previewMemory, { instanceId: null, ids: [], autoPicked: false, device: 'desktop', size: 'M', interactive: false })
    store.admin.experiment = { id: 7, name: 'Trust game', instances: [] }
    store.admin.instance = { id: 44, name: 'pilot', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] }
    try { localStorage.clear() } catch (e) { /* ignore */ }
  })
  afterEach(() => { document.body.innerHTML = '' })

  it('explains that previews need a running session', async () => {
    store.admin.instance = null
    const w = await mountPage()
    expect(w.text()).toContain('No session is running')
    w.unmount()

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

  it('shows the test players at once, but not ones whose own browser is open', async () => {
    addPlayer('p1', { testmode: true })
    addPlayer('p2', { testmode: true, _system: { connected: true } })
    addPlayer('p3')
    await settle()
    const w = await mountPage()
    await settle()
    const frames = w.findAll('iframe')
    expect(frames).toHaveLength(1)
    expect(frames[0].attributes('src')).toBe(`${window.location.origin}/game/7/44/p1/connected`)
    expect(frames[0].attributes('title')).toBe('Participant page of p1')
    // the sandbox keeps a well-behaved page from navigating the console away, and its pop-ups sandboxed
    expect(frames[0].attributes('sandbox')).not.toContain('allow-top-navigation')
    expect(frames[0].attributes('sandbox')).not.toContain('allow-popups-to-escape-sandbox')
    w.unmount()
  })

  it('always says that previews run with the researcher\'s sign-in', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const w = await mountPage()
    await settle()
    // the live-preview notice can be put away; the warning can't
    await w.find('.pv__notice button').trigger('click')
    expect(w.find('.pv__notice').exists()).toBe(false)
    const warn = w.find('.pv__warn')
    expect(warn.text()).toMatch(/run with your researcher sign-in/)
    expect(warn.find('button').exists()).toBe(false)
    w.unmount()
  })

  it('opens a new tab cut off from the console before the participant page loads', async () => {
    addPlayer('p1', { testmode: true })
    await settle()
    const tab = { opener: {} as any, location: { href: '' } }
    const open = vi.spyOn(window, 'open').mockImplementation(() => tab as any)
    try {
      const w = await mountPage()
      await settle()
      const PreviewTile = (await import('../src/pages/preview/PreviewTile.vue')).default
      w.findComponent(PreviewTile).vm.$emit('open-tab')
      await flushPromises()
      expect(open).toHaveBeenCalledWith('about:blank', '_blank')
      expect(tab.opener).toBeNull()
      expect(tab.location.href).toBe(`${window.location.origin}/game/7/44/p1/connected`)
      w.unmount()
    } finally {
      open.mockRestore()
    }
  })

  it('asks before previewing a connected participant, and respects no', async () => {
    addPlayer('p1')
    addPlayer('p2', { _system: { connected: true } })
    await settle()
    const w = await mountPage()
    ui.confirm.mockResolvedValueOnce(false)
    const box = w.findAll('.pp__item').find(li => li.text().includes('p2'))!.find('input[type="checkbox"]')
    await box.setValue(true)
    await flushPromises()
    expect(ui.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'p2 is connected right now', danger: true }))
    expect(previewMemory.ids).toEqual([])

    // a player nobody is using is added without a question
    const box1 = w.findAll('.pp__item').find(li => li.text().includes('p1'))!.find('input[type="checkbox"]')
    await box1.setValue(true)
    await flushPromises()
    expect(ui.confirm).toHaveBeenCalledTimes(1)
    expect(previewMemory.ids).toEqual(['p1'])
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
    await settle()
    await w.find('button.pt__cover').trigger('click')
    await flushPromises()
    // one connection per player: the tile no longer holds a page
    expect(w.find('.pt iframe').exists()).toBe(false)
    expect(w.find('.pt').text()).toContain('Shown full size')
    expect(document.body.querySelector('.pfx iframe')!.getAttribute('src')).toContain('/game/7/44/p1/connected')
    w.unmount()
  })
})
