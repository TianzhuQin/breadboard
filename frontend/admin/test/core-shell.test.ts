import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppShell from '../src/shell/AppShell.vue'
import OutputDrawer from '../src/shell/OutputDrawer.vue'
import ExperimentSwitcher from '../src/shell/ExperimentSwitcher.vue'
import EngineNotice from '../src/shell/EngineNotice.vue'
import AccountMenu from '../src/shell/AccountMenu.vue'
import TopBar from '../src/shell/TopBar.vue'
import { registerUnsaved, unsavedTotal, confirmSignOut } from '../src/shell/unsaved'
import { admin, actions, connection, graph, __receive } from '../src/store/admin'
import { isStaleBuildError, onStaleBuild, routes } from '../src/router'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts } from '../src/ui/toast'
import { buckets, ensureBucket } from '../src/pages/steps/stepsStore'
import { draftFromStep } from '../src/pages/steps/stepsModel'

const tick = () => new Promise(r => setTimeout(r, 0))

async function mountShell () {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/steps', component: { render: () => h('p', { class: 'page' }, 'Steps') }, meta: { title: 'Steps' } },
      { path: '/parameters', component: { render: () => h('p', { class: 'page' }, 'Parameters') }, meta: { title: 'Parameters' } },
      // the sidebar's other pages
      { path: '/:other(.*)*', component: { render: () => h('p', 'Other') } },
    ],
  })
  admin.ready = true
  await router.push('/steps')
  await router.isReady()
  const w = mount(AppShell, { global: { plugins: [router] }, attachTo: document.body })
  await nextTick()
  return { w, router, main: w.find('main').element as HTMLElement }
}

/** Pretend the window is phone-sized (below 900px). */
function narrowWindow () {
  vi.stubGlobal('matchMedia', (q: string) => ({ matches: true, media: q, addEventListener () {}, removeEventListener () {} }))
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('the shell', () => {
  it('skips to the content without leaving the page', async () => {
    const { w, router, main } = await mountShell()
    await w.find('.shell__skip').trigger('click')
    await tick()
    expect(router.currentRoute.value.path).toBe('/steps')
    expect(document.activeElement).toBe(main)
    w.unmount()
  })

  it('opens each page at its top (pages scroll inside main, not the window)', async () => {
    const { w, router, main } = await mountShell()
    Object.defineProperty(main, 'scrollTop', { value: 900, writable: true, configurable: true })
    await router.push('/parameters')
    await nextTick()
    expect(main.scrollTop).toBe(0)
    // the same page with another query (e.g. a dialog's ?action=) keeps its place
    main.scrollTop = 300
    await router.push('/parameters?action=new')
    await nextTick()
    expect(main.scrollTop).toBe(300)
    w.unmount()
  })

  it('keeps the closed phone navigation out of reach, and handles focus and Escape', async () => {
    narrowWindow()
    const { w } = await mountShell()
    const nav = w.find('#shell-nav')
    const menu = w.find('.topbar__menu')
    expect(nav.attributes('inert')).toBeDefined()
    expect(menu.attributes('aria-expanded')).toBe('false')
    expect(menu.attributes('aria-controls')).toBe('shell-nav')

    await menu.trigger('click')
    await tick()
    expect(nav.attributes('inert')).toBeUndefined()
    expect(menu.attributes('aria-expanded')).toBe('true')
    expect(nav.element.contains(document.activeElement)).toBe(true)
    expect(document.activeElement!.tagName).toBe('A')

    await nav.trigger('keydown', { key: 'Escape' })
    await nextTick()
    expect(nav.attributes('inert')).toBeDefined()
    expect(document.activeElement).toBe(menu.element)
    w.unmount()
  })

  it('leaves the navigation alone on wide screens', async () => {
    const { w } = await mountShell()
    expect(w.find('#shell-nav').attributes('inert')).toBeUndefined()
    w.unmount()
  })
})

describe('the output panel', () => {
  it('keeps following new output once the store keeps its maximum', async () => {
    actions.clearOutput()
    for (let i = 0; i < 520; i++) actions.note(`line ${i}`)
    expect(admin.output.length).toBe(500)
    const w = mount(OutputDrawer, { attachTo: document.body })
    await nextTick()
    const body = w.find('.outd__body').element as HTMLElement
    let height = 10000
    Object.defineProperty(body, 'scrollHeight', { get: () => height, configurable: true })
    Object.defineProperty(body, 'clientHeight', { get: () => 200, configurable: true })
    Object.defineProperty(body, 'scrollTop', { value: 9800, writable: true, configurable: true })
    height = 10400
    actions.note('a long stack trace')
    expect(admin.output.length).toBe(500)
    await nextTick()
    await nextTick()
    expect(body.scrollTop).toBe(10400)
    w.unmount()
  })
})

describe('after a server update', () => {
  it('recognises a page whose code is gone, in each browser\'s words', () => {
    expect(isStaleBuildError(new TypeError('Failed to fetch dynamically imported module: http://lab/admin/chunks/RecruitPage-1a2b.js'))).toBe(true)
    expect(isStaleBuildError(new TypeError('Importing a module script failed.'))).toBe(true)
    expect(isStaleBuildError(new TypeError('error loading dynamically imported module: http://lab/x.js'))).toBe(true)
    expect(isStaleBuildError(new Error('Cannot read properties of undefined'))).toBe(false)
  })

  it('offers one Reload that opens the page that failed', () => {
    toasts.splice(0, toasts.length)
    const reload = vi.fn()
    onStaleBuild('/recruit', reload)
    // reported a second time (Vite, then the router): still one message
    onStaleBuild('/recruit', reload)
    expect(toasts.length).toBe(1)
    expect(toasts[0].timeout).toBe(0)
    expect(reload).not.toHaveBeenCalled()
    toasts[0].action!.run()
    expect(reload).toHaveBeenCalledTimes(1)
    expect(window.location.hash).toBe('#/recruit')
  })
})

describe('switching experiments on the shared engine', () => {
  const exp = (id: number, name: string) => ({ id, name, uid: 'u' + id, fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 })

  async function pickB () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
    const w = mount(ExperimentSwitcher, { global: { plugins: [router] }, attachTo: document.body })
    await w.find('.xsw__btn').trigger('click')
    await nextTick()
    await w.findAll('.xsw__opt').find(o => o.text().includes('B'))!.trigger('click')
    return w
  }

  it('asks first while someone else\'s players are in the network, and sends nothing when declined', async () => {
    __receive({ user: { email: 'r', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(1, 'A'), experimentInstance: '' })
    __receive({ action: 'addNode', id: 'p1' })
    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))

    const w = await pickB()
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.title).toBe('Switch to “B”?')
    expect(confirmState.req!.message).toContain('1 player is in the network right now')
    expect(confirmState.req!.danger).toBe(true)
    settleConfirm(false)
    await tick()
    expect(send).not.toHaveBeenCalled()

    // nobody left: it switches without asking
    graph.clear()
    await pickB()
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 }))
    expect(confirmState.open).toBe(false)
    connection.state.value = 'idle'
    w.unmount()
  })

  it('switches without asking while a session is live in the engine, and says it keeps running', async () => {
    admin.email = 'r@lab'
    graph.clear()
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    const engine = { experimentId: 1, experimentName: 'A', sessionId: 40, sessionName: 'Pilot', owner: 'boss@lab' }
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(1, 'A'), experimentInstance: '', engine })
    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    toasts.splice(0, toasts.length)

    const w = await pickB()
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 }))
    expect(confirmState.open).toBe(false)
    // the server's answer: only the selection changed
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine, engineKept: true })
    await vi.waitFor(() => expect(toasts.some(t => t.title === 'Switched to B')).toBe(true))
    expect(toasts.find(t => t.title === 'Switched to B')!.detail).toBe('The engine keeps running “A” (session “Pilot”), untouched. Edit this experiment and see its data here; to run it, end that session first.')
    connection.state.value = 'idle'
    w.unmount()
  })

  it('says it is not connected instead of asking', async () => {
    __receive({ user: { email: 'r', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(1, 'A'), experimentInstance: '' })
    toasts.splice(0, toasts.length)
    const send = vi.spyOn(connection, 'send')
    await pickB()
    await tick()
    expect(confirmState.open).toBe(false)
    expect(send).not.toHaveBeenCalled()
    expect(toasts.some(t => t.title === 'Not connected to the server')).toBe(true)
  })
})

describe('reloading the engine from the top bar', () => {
  async function askToReload () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
    const w = mount(TopBar, { props: { outputOpen: false }, global: { plugins: [router] }, attachTo: document.body })
    await w.find('.topbar__chip').trigger('click')
    await nextTick()
    await w.findAll('[role="menuitem"]').find(o => o.text().includes('Reload engine'))!.trigger('click')
    return w
  }

  it('says that it pauses the autopilot when the engine runs the autopilot\'s session', async () => {
    __receive({ user: { email: 'r', experiments: [], currentScript: '' }, experimentInstance: '' })
    graph.clear()
    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    const status = { on: true, paused: false, instance: { id: 9, name: 'Auto 3', status: 'RUNNING' } }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ status })))
    let w = await askToReload()
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('The autopilot has session “Auto 3” open.')
    expect(confirmState.req!.message).toMatch(/\n\nThis also pauses the autopilot: .*Resume on the Autopilot page, so .*\.$/)
    settleConfirm(false)
    await tick()
    expect(send).not.toHaveBeenCalled()
    w.unmount()

    // paused already: nothing more to pause
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ status: { ...status, paused: true } })))
    w = await askToReload()
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req!.message).toContain('The autopilot has session “Auto 3” open.')
    expect(confirmState.req!.message).not.toContain('pauses the autopilot')
    settleConfirm(true)
    await tick()
    expect(send).toHaveBeenCalledWith('ReloadEngine', {})
    connection.state.value = 'idle'
    w.unmount()
  })
})

describe('the top bar\'s engine chip', () => {
  const exp = (id: number, name: string, instances: any[] = []) => ({ id, name, uid: 'u' + id, fileMode: false, steps: [], languages: [], content: [], parameters: [], instances, images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 })
  const session = { id: 9, name: 'Auto 3', status: 'RUNNING' }

  it('says "Reload needed" as the server says, and that the autopilot\'s next session uses the saved steps while its session is open', async () => {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    admin.engine = 'ready'
    let status: any = { on: true, experimentId: 1, paused: false, instance: session }
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ status, settings: { continuous: true } })))
    __receive({
      user: { email: 'r@lab', experiments: [exp(1, 'A', [session])], currentScript: '' },
      experiment: exp(1, 'A', [session]),
      experimentInstance: session,
      engine: { experimentId: 1, experimentName: 'A', sessionId: 9, sessionName: 'Auto 3', owner: 'r@lab', stepsChanged: true },
    })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
    let w = mount(TopBar, { props: { outputOpen: false }, global: { plugins: [router] }, attachTo: document.body })
    const chip = () => w.find('.topbar__chip')
    await vi.waitFor(() => expect(chip().text()).toBe('Steps saved'))
    expect(chip().attributes('title')).toMatch(/^Saved: the autopilot’s next session uses it\./)
    // (it runs as this account: a reload carries its session on; as another, it would end it)
    expect(chip().attributes('title')).toContain('Reload the engine only to use the saved steps in its session now, whose game then starts again.')
    __receive({ engine: { experimentId: 1, experimentName: 'A', sessionId: 9, sessionName: 'Auto 3', owner: 'other@lab', stepsChanged: true } })
    await vi.waitFor(() => expect(chip().attributes('title')).toContain('Reloading the engine from your account would end its session and pause the autopilot.'))
    __receive({ engine: { experimentId: 1, experimentName: 'A', sessionId: 9, sessionName: 'Auto 3', owner: 'r@lab', stepsChanged: true } })
    await chip().trigger('click')
    await nextTick()
    expect(w.text()).toContain('Saved: the autopilot’s next session uses it')
    w.unmount()

    // a session someone runs by hand (the autopilot paused): the saved steps need a reload to reach it
    status = { ...status, paused: true }
    w = mount(TopBar, { props: { outputOpen: false }, global: { plugins: [router] }, attachTo: document.body })
    await vi.waitFor(() => expect(chip().text()).toBe('Reload needed'))
    // and once the server says the engine runs the saved steps, nothing is needed
    __receive({ engine: { experimentId: 1, experimentName: 'A', sessionId: 9, sessionName: 'Auto 3', owner: 'r@lab', stepsChanged: false } })
    await vi.waitFor(() => expect(chip().text()).toBe('Engine'))
    w.unmount()
  })
})

describe('the engine running another experiment', () => {
  const exp = (id: number, name: string) => ({ id, name, uid: 'u' + id, fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 })
  const engine = (experimentId: number, experimentName: string, sessionName: string | null, owner = 'other@lab') =>
    ({ experimentId, experimentName, sessionId: sessionName ? 40 : null, sessionName, owner })
  /** The account has A and B, with A selected; the engine runs `eng` (as known after a rebuild ended). */
  function state (eng: any, list = [exp(1, 'A'), exp(2, 'B')]) {
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    __receive({ user: { email: 'r@lab', experiments: list, currentScript: '' }, experiment: exp(1, 'A'), experimentInstance: '', engine: eng })
  }
  const text = (w: any) => w.text().replace(/\s+/g, ' ').trim()
  async function notice () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
    await router.push('/session')
    const w = mount(EngineNotice, { global: { plugins: [router] }, attachTo: document.body })
    await nextTick()
    return { w, router }
  }
  const button = (w: any, label: string) => w.findAll('button').find((b: any) => b.text() === label)

  afterEach(() => { connection.state.value = 'idle' })

  it('says what the engine runs and what that means here, and switching to it rebuilds nothing', async () => {
    admin.email = 'r@lab'
    graph.clear()
    state(engine(2, 'B', 'Pilot 3'))
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    const { w, router } = await notice()
    expect(text(w)).toBe('The engine is running “B” (other@lab’s session “Pilot 3”). You are working on “A”: its Build pages and data are here; to run it, end that session first. Work on “B”')
    expect(w.attributes('role')).toBe('status')
    // the links to what works here
    const links = w.findAll('a')
    expect(links.map((a: any) => a.text())).toEqual(['Build pages', 'data'])
    await links[1].trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/sessions'))

    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    await button(w, 'Work on “B”')!.trigger('click')
    // a session is live there: selecting changes only what this console works on, so nothing is asked
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 }))
    expect(confirmState.open).toBe(false)
    // the server's answer: B selected, the engine left as it was
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine: engine(2, 'B', 'Pilot 3'), engineKept: true })
    await nextTick()
    expect(w.find('.engine-notice').exists()).toBe(false)
    w.unmount()
  })

  it('names the autopilot\'s session, says ending it pauses the autopilot, and links to it', async () => {
    admin.email = 'r@lab'
    graph.clear()
    state(engine(2, 'B', 'Auto 4'))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify({ status: { on: true, paused: false, instance: { id: 40, name: 'Auto 4', status: 'RUNNING' } } })))
    const { w, router } = await notice()
    await vi.waitFor(() => expect(text(w)).toContain('(the autopilot’s session “Auto 4”)'))
    expect(text(w)).toContain('to run it, end that session first (that pauses the autopilot).')
    await button(w, 'Autopilot')!.trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/autopilot'))
    w.unmount()
  })

  it('names this account\'s own session', async () => {
    admin.email = 'r@lab'
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    state(engine(2, 'B', 'Pilot 3', 'R@lab'))
    const { w } = await notice()
    expect(text(w)).toContain('The engine is running “B” (your session “Pilot 3”).')
    expect(button(w, 'Autopilot')).toBeUndefined()
    w.unmount()
  })

  it('offers to load the selected experiment when the engine only has another one loaded', async () => {
    admin.email = 'r@lab'
    graph.clear()
    state(engine(2, 'B', null, 'r@lab'))
    const { w } = await notice()
    expect(text(w)).toContain('The engine has “B” loaded, with no session running (loaded for your account). You are working on “A”: load it into the engine to run it here.')
    expect(button(w, 'Work on “B”')).toBeDefined()
    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await button(w, 'Load “A”')!.trigger('click')
    // nobody is using the engine: it reloads without asking
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('ReloadEngine', {}))
    expect(confirmState.open).toBe(false)
    w.unmount()
  })

  it('offers no switch to an experiment that is not in the list', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    state(engine(9, 'Their study', 'Wave 2'))
    const { w } = await notice()
    expect(text(w)).toContain('The engine is running “Their study” (other@lab’s session “Wave 2”).')
    expect(w.findAll('button').length).toBe(0)
    w.unmount()
  })

  it('stays out of the way when the engine runs the selected experiment, or is reloading', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    state(engine(1, 'A', 'Pilot'))
    const { w } = await notice()
    expect(w.find('.engine-notice').exists()).toBe(false)
    state(engine(2, 'B', 'Pilot'))
    await nextTick()
    expect(w.find('.engine-notice').exists()).toBe(true)
    __receive({ notify: { ScriptEngineReloading: 5 } })
    await nextTick()
    expect(w.find('.engine-notice').exists()).toBe(false)
    __receive({ notify: { ScriptEngineReloaded: 6 } })
    w.unmount()
  })

  it('shows on the Run pages only, as the console\'s routes mark them', async () => {
    const RUN = ['/session', '/network', '/players', '/console', '/preview']
    const BUILD = ['/steps', '/content', '/parameters', '/page', '/images']
    const OTHER = ['/sessions', '/autopilot', '/recruit', '/experiments', '/settings']
    const meta = (path: string) => (routes.find(r => r.path === path) as any)?.meta || null
    for (const p of RUN) expect(meta(p), p).toMatchObject({ usesEngine: true })
    for (const p of [...BUILD, ...OTHER]) expect(meta(p)?.usesEngine, p).toBeFalsy()

    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    state(engine(2, 'B', 'Pilot 3'))
    // the console's own routes, with stand-ins for the pages
    const router = createRouter({
      history: createMemoryHistory(),
      routes: routes.map(r => ('redirect' in r && r.redirect)
        ? { path: r.path, redirect: r.redirect } as any
        : { path: r.path, meta: r.meta, component: { render: () => h('p', String(r.name)) } }),
    })
    admin.ready = true
    await router.push('/session')
    await router.isReady()
    const w = mount(AppShell, { global: { plugins: [router] }, attachTo: document.body })
    for (const p of RUN) {
      await router.push(p)
      await nextTick()
      expect(w.find('.engine-notice').exists(), p).toBe(true)
    }
    for (const p of [...BUILD, ...OTHER]) {
      await router.push(p)
      await nextTick()
      expect(w.find('.engine-notice').exists(), p).toBe(false)
    }
    w.unmount()
  })

  it('doesn\'t count the engine\'s players in the sidebar while it runs another experiment', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    graph.clear()
    state(engine(2, 'B', 'Pilot 3'))
    const seen = graph.version.value
    graph.apply({ action: 'addNode', id: 'p1' })
    graph.apply({ action: 'addNode', id: 'p2' })
    await vi.waitFor(() => expect(graph.version.value).toBeGreaterThan(seen))
    const { w } = await mountShell()
    const players = () => w.findAll('.sidenav__link').find(l => l.text().startsWith('Players'))!
    // B's players, while A is the experiment worked on here
    expect(players().find('.sidenav__count').exists()).toBe(false)
    // the engine runs A now (as the server says, with no rebuild): they are A's
    __receive({ engine: engine(1, 'A', 'Pilot') })
    await nextTick()
    expect(players().find('.sidenav__count').text()).toBe('2')
    graph.clear()
    w.unmount()
  })
})

describe('"Reload needed" while working on another experiment', () => {
  const exp = (id: number, name: string, instances: any[] = []) => ({ id, name, uid: 'u' + id, fileMode: false, steps: [], languages: [], content: [], parameters: [], instances, images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 })
  const S = { id: 9, name: 'Auto 1', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] }
  // the autopilot's session of A runs in the engine
  const engine = { experimentId: 1, experimentName: 'A', sessionId: 9, sessionName: 'Auto 1', owner: 'auto@lab' }
  const list = () => [exp(1, 'A', [S]), exp(2, 'B')]
  const onA = { experiment: exp(1, 'A', [S]), experimentInstance: S }
  const onB = { experiment: exp(2, 'B'), experimentInstance: '' }
  /** Select an experiment, and the server's answer: only the selection changed (a live session kept the engine). */
  async function select (id: number, picture: any) {
    void actions.selectExperiment(id)
    __receive({ user: { email: 'r@lab', experiments: list(), currentScript: '' }, ...picture, engine, engineKept: true })
    await nextTick()
  }
  async function chip () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] })
    const w = mount(TopBar, { props: { outputOpen: false }, global: { plugins: [router] }, attachTo: document.body })
    await nextTick()
    return w
  }

  afterEach(() => {
    connection.state.value = 'idle'
    admin.engine = 'ready'
    buckets.clear()
  })

  it('isn\'t left on A by a step of B saved meanwhile, and is worked out again for the experiment selected', async () => {
    admin.email = 'r@lab'
    graph.clear()
    __receive({ notify: { ScriptEngineReloading: 1 } })
    __receive({ notify: { ScriptEngineReloaded: 2 } })
    __receive({ user: { email: 'r@lab', experiments: list(), currentScript: '' }, ...onA, engine })
    admin.engine = 'ready'
    connection.state.value = 'open'
    vi.spyOn(connection, 'send').mockReturnValue(true)
    const w = await chip()

    // On B, one of its steps is saved (as the Steps page marks it): the engine doesn't run B.
    await select(2, onB)
    const stepOfB = draftFromStep({ id: 21, name: 'InitStep', source: 'b = 2' })
    ensureBucket(2, false).drafts.push(stepOfB)
    stepOfB.engineBehind = true
    actions.markEngineStale(2)
    expect(admin.engine).toBe('ready')

    // Back on A (its session kept the engine): A's steps didn't change, the engine runs them.
    await select(1, onA)
    expect(admin.instance?.id).toBe(9)
    expect(admin.engine).toBe('ready')
    expect(w.find('.topbar__chip').text()).not.toContain('Reload needed')

    // A step of A saved and not run: marked, off on B, and marked again back on A.
    const stepOfA = draftFromStep({ id: 11, name: 'InitStep', source: 'a = 2' })
    ensureBucket(1, false).drafts.push(stepOfA)
    stepOfA.engineBehind = true
    actions.markEngineStale(1)
    await nextTick()
    expect(w.find('.topbar__chip').text()).toContain('Reload needed')
    await select(2, onB)
    expect(admin.engine).toBe('ready')
    expect(w.find('.topbar__chip').text()).not.toContain('Reload needed')
    await select(1, onA)
    expect(admin.engine).toBe('stale')
    expect(w.find('.topbar__chip').text()).toContain('Reload needed')

    // A rebuild loads every saved step (of the experiment it loads; others aren't in it at all).
    __receive({ notify: { ScriptEngineReloading: 3 } })
    __receive({ notify: { ScriptEngineReloaded: 4 } })
    await nextTick()
    expect(stepOfA.engineBehind).toBe(false)
    expect(stepOfB.engineBehind).toBe(false)
    w.unmount()
  })
})

describe('signing out with unsaved work', () => {
  it('asks only while there is unsaved work, and says what it is', async () => {
    let drafts = 2
    const offSteps = registerUnsaved('steps', () => drafts, 'Steps')
    const offContent = registerUnsaved('content', () => 0, 'Content')
    const offBroken = registerUnsaved('broken', () => { throw new Error('boom') })
    expect(unsavedTotal()).toBe(2)

    const answer = confirmSignOut()
    expect(confirmState.open).toBe(true)
    expect(confirmState.req).toMatchObject({ title: 'Sign out with 2 unsaved changes?', confirmLabel: 'Sign out anyway', danger: true })
    expect(confirmState.req!.message).toContain('Not saved yet: Steps (2).')
    settleConfirm(false)
    expect(await answer).toBe(false)

    // the same key again replaces it; the old one's unregister then does nothing
    const offSteps2 = registerUnsaved('steps', () => 1, 'Steps')
    offSteps()
    expect(unsavedTotal()).toBe(1)
    offSteps2()
    drafts = 5
    expect(unsavedTotal()).toBe(0)
    expect(await confirmSignOut()).toBe(true)
    expect(confirmState.open).toBe(false)
    offContent()
    offBroken()
  })

  it('says which requests are still running, apart from unsaved changes', async () => {
    let jobs = 2
    const offJobs = registerUnsaved('mturk', () => jobs, 'Mechanical Turk requests', { running: true })
    expect(unsavedTotal()).toBe(0)
    let answer = confirmSignOut()
    expect(confirmState.req!.title).toBe('Sign out while requests are still running?')
    expect(confirmState.req!.message).toContain('Still running: Mechanical Turk requests (2).')
    expect(confirmState.req!.message).not.toContain('Not saved yet')
    settleConfirm(false)
    expect(await answer).toBe(false)

    const offSteps = registerUnsaved('steps', () => 1, 'Steps')
    answer = confirmSignOut()
    expect(confirmState.req!.title).toBe('Sign out with an unsaved change?')
    expect(confirmState.req!.message).toContain('Not saved yet: Steps (1). Still running: Mechanical Turk requests (2).')
    settleConfirm(true)
    expect(await answer).toBe(true)
    offSteps()
    jobs = 0
    expect(await confirmSignOut()).toBe(true)
    offJobs()
  })

  it('the account menu signs out only once the researcher agrees', async () => {
    const off = registerUnsaved('content', () => 1, 'Content')
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    const signOut = async () => {
      const w = mount(AccountMenu, { attachTo: document.body })
      await w.find('.acct').trigger('click')
      await nextTick()
      await w.findAll('[role=menuitem]').find(b => b.text().includes('Sign out'))!.trigger('click')
      await vi.waitFor(() => expect(confirmState.open).toBe(true))
      expect(confirmState.req!.title).toBe('Sign out with an unsaved change?')
      return w
    }
    let w = await signOut()
    settleConfirm(false)
    await tick()
    expect(fetch.mock.calls.some(c => String(c[0]).includes('/logout'))).toBe(false)
    w.unmount()

    w = await signOut()
    settleConfirm(true)
    await vi.waitFor(() => expect(fetch.mock.calls.some(c => String(c[0]).includes('/logout'))).toBe(true))
    w.unmount()
    off()
  })
})
