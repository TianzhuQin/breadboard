import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppShell from '../src/shell/AppShell.vue'
import OutputDrawer from '../src/shell/OutputDrawer.vue'
import ExperimentSwitcher from '../src/shell/ExperimentSwitcher.vue'
import EngineNotice from '../src/shell/EngineNotice.vue'
import AccountMenu from '../src/shell/AccountMenu.vue'
import { registerUnsaved, unsavedTotal, confirmSignOut } from '../src/shell/unsaved'
import { admin, actions, connection, graph, __receive } from '../src/store/admin'
import { isStaleBuildError, onStaleBuild } from '../src/router'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts } from '../src/ui/toast'

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

  afterEach(() => { connection.state.value = 'idle' })

  it('says what the engine runs, and that opening it here ends that session', async () => {
    admin.email = 'r@lab'
    graph.clear()
    state(engine(2, 'B', 'Pilot 3'))
    const w = mount(EngineNotice, { attachTo: document.body })
    await nextTick()
    expect(text(w)).toContain('The engine is running B’s session Pilot 3 (other@lab). Live controls here act on nothing. Opening B here stops that session for everyone in it.')
    expect(w.attributes('role')).toBe('status')
    const btn = w.find('button')
    expect(btn.text()).toBe('End that session and open B')

    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await btn.trigger('click')
    // switching rebuilds the engine, which ends the session it runs
    await vi.waitFor(() => expect(confirmState.open).toBe(true))
    expect(confirmState.req).toMatchObject({ title: 'End “Pilot 3” and open “B”?', confirmLabel: 'End it and open', danger: true })
    expect(confirmState.req!.message).toContain('other@lab has session “Pilot 3” of “B” running.')
    expect(confirmState.req!.message).toContain('Session “Pilot 3” is stopped: the people in it can’t rejoin it, and its links stop working.')
    settleConfirm(true)
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 }))
    // the switch done: the reply, the rebuild, and what the engine runs now
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine: engine(2, 'B', 'Pilot 3') })
    __receive({ notify: { ScriptEngineReloading: 3 } })
    __receive({ notify: { ScriptEngineReloaded: 4 } })
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(2, 'B'), experimentInstance: '', engine: engine(2, 'B', null, 'r@lab') })
    await nextTick()
    expect(w.find('.engine-notice').exists()).toBe(false)
    w.unmount()
  })

  it('switches without asking when nobody uses the engine', async () => {
    graph.clear()
    state(engine(2, 'B', null, 'r@lab'))
    const w = mount(EngineNotice, { attachTo: document.body })
    await nextTick()
    expect(text(w)).toContain('The engine has B loaded, with no session running (your account). Live controls here act on nothing until you switch.')
    expect(w.find('button').text()).toBe('Switch to B')
    connection.state.value = 'open'
    const send = vi.spyOn(connection, 'send').mockReturnValue(true)
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
    await w.find('button').trigger('click')
    await vi.waitFor(() => expect(send).toHaveBeenCalledWith('SelectExperiment', { experimentId: 2 }))
    expect(confirmState.open).toBe(false)
    w.unmount()
  })

  it('just switches when the session there is this account\'s own, which is taken up again', async () => {
    state(engine(2, 'B', 'Pilot 3', 'r@lab'))
    // the account's selected session is the one the engine runs (ChangeExperiment resumes it)
    __receive({ user: { email: 'r@lab', experiments: [exp(1, 'A'), exp(2, 'B')], currentScript: '' }, experiment: exp(1, 'A'), engine: engine(2, 'B', 'Pilot 3', 'r@lab'),
      experimentInstance: { id: 40, name: 'Pilot 3', status: 'RUNNING', creationTime: '', hitId: '', hits: [], data: [] } })
    const w = mount(EngineNotice, { attachTo: document.body })
    await nextTick()
    expect(text(w)).toContain('Live controls here act on nothing until you switch.')
    expect(w.find('button').text()).toBe('Switch to B')
    w.unmount()
  })

  it('offers no switch to another account\'s experiment', async () => {
    state(engine(9, 'Their study', 'Wave 2'))
    const w = mount(EngineNotice, { attachTo: document.body })
    await nextTick()
    expect(text(w)).toContain('The engine is running Their study’s session Wave 2 (other@lab). Live controls here act on nothing for now.')
    expect(w.find('button').exists()).toBe(false)
    w.unmount()
  })

  it('stays out of the way when the engine runs the selected experiment, or is reloading', async () => {
    state(engine(1, 'A', 'Pilot'))
    const w = mount(EngineNotice, { attachTo: document.body })
    await nextTick()
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
