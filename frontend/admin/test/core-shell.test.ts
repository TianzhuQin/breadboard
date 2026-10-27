import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import AppShell from '../src/shell/AppShell.vue'
import OutputDrawer from '../src/shell/OutputDrawer.vue'
import ExperimentSwitcher from '../src/shell/ExperimentSwitcher.vue'
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
