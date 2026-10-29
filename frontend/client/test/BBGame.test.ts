import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import BBGame from '../src/components/BBGame.vue'
import BBWaiting from '../src/components/BBWaiting.vue'

const stubs = {
  BBNetwork: { template: '<div class="network-stub" />' },
  BBWaiting: { template: '<div class="waiting-stub" />' },
  PlayerTimers: { template: '<div class="timers-stub" />' },
  PlayerChoices: { template: '<div class="choices-stub" />' },
  Form: { template: '<div class="form-stub" />' },
}

const me = { id: 'me', x: 0, y: 0, data: {} }
const ana = { id: 'na', x: 0, y: 0, data: {} }
const ben = { id: 'nb', x: 0, y: 0, data: {} }

function mountGame (player: any, edges: any[] = []) {
  return mount(BBGame, {
    propsData: { player, graph: { nodes: [me, ana, ben], edges } },
    stubs,
  })
}

describe('BBGame', () => {
  it('shows a spinner until the first update from the server', () => {
    const w = mountGame({ id: 'me', text: 'Loading...', choices: [] })
    expect(w.find('.bb-game__loading').exists()).toBe(true)
    expect(w.find('.bb-game__text').exists()).toBe(false)
  })

  it('shows the waiting screen while the autopilot is filling the session', () => {
    const w = mountGame({ id: 'me', text: 'Waiting', _system: { autopilot: { state: 'waiting', here: 1, min: 3 } } })
    expect(w.find('.waiting-stub').exists()).toBe(true)
    // BBWaiting shows the experiment's own text itself
    expect(w.find('.bb-game__text').exists()).toBe(false)
  })

  it('hides the panel while waiting when the experiment shows nothing in it', () => {
    const waitingWith = (more: any) => mountGame({ id: 'me', text: 'Waiting', ...more, _system: { autopilot: { state: 'waiting', here: 1, min: 3 } } })
    expect(waitingWith({ choices: [], forms: {} }).find('.bb-game__panel').isVisible()).toBe(false)
    expect(waitingWith({ forms: { intro: {} } }).find('.bb-game__panel').isVisible()).toBe(true)
  })

  it('shows the experiment\'s choices under the waiting screen', () => {
    const w = mountGame({ id: 'me', choices: [{ uid: 'c1', name: 'Ready' }], _system: { autopilot: { state: 'waiting', here: 1, min: 3 } } })
    expect(w.find('.waiting-stub').exists()).toBe(true)
    expect(w.find('.bb-game__panel').isVisible()).toBe(true)
    expect(w.find('.choices-stub').exists()).toBe(true)
    expect(w.find('.timers-stub').exists()).toBe(false)
  })

  it('keeps the form the participant began while waiting when the session starts', async () => {
    // stands in for Form, holding what the participant typed
    const TypedForm = { name: 'TypedForm', data: () => ({ typed: '' }), template: '<input class="form-stub" v-model="typed" />' }
    const forms = { intro: {} }
    const choices = [{ uid: 'c1', name: 'Ready' }]
    const w = mount(BBGame, {
      propsData: {
        player: { id: 'me', forms, choices, _system: { autopilot: { state: 'waiting', here: 2, min: 3 } } },
        graph: { nodes: [me], edges: [] },
      },
      stubs: { ...stubs, Form: TypedForm },
    })
    const form = w.findComponent({ name: 'TypedForm' })
    const panel = w.find('.bb-game__panel').element
    const choicesEl = w.find('.choices-stub').element
    await w.find('.form-stub').setValue('half an answer')

    await w.setProps({ player: { id: 'me', text: 'Go', forms, choices, _system: {} } })
    expect(w.find('.waiting-stub').exists()).toBe(false)
    expect(w.find('.bb-game__text').exists()).toBe(true)
    expect(w.find('.bb-game__panel').element).toBe(panel)
    expect(w.find('.choices-stub').element).toBe(choicesEl)
    const after = w.findComponent({ name: 'TypedForm' })
    expect(after.vm).toBe(form.vm)
    expect((after.element as HTMLInputElement).value).toBe('half an answer')
  })

  it('lets the experiment replace the waiting card', () => {
    const w = mount(BBGame, {
      propsData: { player: { id: 'me', _system: { autopilot: { state: 'waiting', here: 1, min: 3 } } }, graph: { nodes: [me], edges: [] } },
      stubs,
      scopedSlots: { waiting: '<p class="own-waiting">{{ props.info.here }} of {{ props.info.min }}</p>' },
    })
    expect(w.find('.waiting-stub').exists()).toBe(false)
    expect(w.find('.own-waiting').text()).toBe('1 of 3')
  })

  it('shows the text and choices, without a network, before the player has ties', () => {
    const w = mountGame({ id: 'me', text: '<p>Welcome</p>', _system: {} })
    expect(w.find('.network-stub').exists()).toBe(false)
    expect(w.find('.bb-game__text').html()).toContain('<p>Welcome</p>')
    expect(w.find('.choices-stub').exists()).toBe(true)
  })

  it('shows the network with a count of own ties and ties among neighbors', () => {
    const edges = [
      { id: 1, source: me, target: ana, data: {} },
      { id: 2, source: ben, target: me, data: {} },
      { id: 3, source: ana, target: ben, data: {} },
    ]
    const w = mountGame({ id: 'me', text: 'Go', _system: {} }, edges)
    expect(w.find('.network-stub').exists()).toBe(true)
    expect(w.find('.bb-game__meta').text().replace(/\s+/g, ' ')).toBe('2 ties · 1 between your neighbors')
  })

  it('shows the network before any ties when the player may edit them', () => {
    const w = mountGame({ id: 'me', edgeEdits: { enabled: true }, _system: {} })
    expect(w.find('.network-stub').exists()).toBe(true)
  })

  it('never shows a blank page: says the participant is in until the experiment shows something', async () => {
    const player: any = { id: 'me', _system: {} }
    const w = mountGame(player)
    expect(w.find('.bb-game__idle-title').text()).toBe('You\'re in')
    await w.setProps({ player: { ...player, text: '<p>Round 1</p>' } })
    expect(w.find('.bb-game__idle').exists()).toBe(false)
    // between two screens
    await w.setProps({ player: { ...player, text: '' } })
    expect(w.find('.bb-game__idle-title').text()).toBe('Please wait')
  })

  it('shows no waiting card while the experiment shows choices or a network', () => {
    expect(mountGame({ id: 'me', choices: [{ name: 'A' }], _system: {} }).find('.bb-game__idle').exists()).toBe(false)
    expect(mountGame({ id: 'me', edgeEdits: { enabled: true }, _system: {} }).find('.bb-game__idle').exists()).toBe(false)
  })

  it('ends on one card without the network once the experiment says the player is finished', () => {
    const edges = [{ id: 1, source: me, target: ana, data: {} }]
    const w = mountGame({ id: 'me', text: '<h2>Thank you!</h2>', finished: true, _system: {} }, edges)
    expect(w.find('.network-stub').exists()).toBe(false)
    expect(w.find('.bb-game__done').exists()).toBe(true)
    expect(w.find('.bb-game__grid').classes()).toContain('is-finished')
  })

  it('keeps the network after the player loses their last tie', async () => {
    const graph = { nodes: [me, ana], edges: [{ id: 1, source: me, target: ana, data: {} }] }
    const w = mount(BBGame, { propsData: { player: { id: 'me', _system: {} }, graph }, stubs })
    expect(w.find('.network-stub').exists()).toBe(true)
    graph.edges.splice(0, 1)
    await w.vm.$nextTick()
    expect(w.find('.network-stub').exists()).toBe(true)
  })
})

describe('BBWaiting', () => {
  afterEach(() => { vi.useRealTimers() })

  it('says how many people are here and how many are needed', () => {
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 2, min: 3, max: 5 } } } } })
    expect(w.find('.bb-waiting__title').text()).toBe('Waiting for the others')
    expect(w.find('.bb-waiting__count').text().replace(/\s+/g, ' ')).toBe('2 people are here. The session starts when 3–5 people are here.')
    expect(w.findAll('.bb-waiting__dot.is-here').length).toBe(2)
  })

  it('says the session starts soon once enough people are here, and when', () => {
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 3, min: 3, max: 5 } } } } })
    expect(w.find('.bb-waiting__title').text()).toBe('Starting soon')
    expect(w.find('.bb-waiting__count').text().replace(/\s+/g, ' ')).toBe('3 people are here. That\'s enough to begin: the session starts shortly, or as soon as 5 are here.')
    // the bar now fills towards the maximum
    expect(w.find('.bb-waiting__bar span').attributes('style')).toContain('width: 60%')
    const timed = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 2, min: 2, max: 8, startsIn: 18 } } } } })
    expect(timed.find('.bb-waiting__count').text().replace(/\s+/g, ' ')).toBe('2 people are here. That\'s enough to begin: the session starts in about 18 s, or as soon as 8 are here.')
  })

  it('does not add a second when the clock ticks while the page is set up', () => {
    let t = 1_000_000
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => t++)
    try {
      const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 2, min: 2, max: 8, startsIn: 18 } } } } })
      expect(w.find('.bb-waiting__count').text()).toContain('in about 18 s')
    } finally {
      spy.mockRestore()
    }
  })

  it('counts down before the start', () => {
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'starting', seconds: 5, here: 3, min: 3 } } } } })
    expect(w.find('.bb-waiting__title').text()).toBe('Starting in 5…')
  })

  it('starts the countdown again when the server sends the seconds left', async () => {
    vi.useFakeTimers()
    const starting = (seconds: number) => ({ _system: { autopilot: { state: 'starting', seconds, here: 3, min: 3 } } })
    const w = mount(BBWaiting, { propsData: { player: starting(10) } })
    vi.advanceTimersByTime(3000)
    await w.vm.$nextTick()
    expect(w.find('.bb-waiting__title').text()).toBe('Starting in 7…')
    // e.g. after a reconnect
    await w.setProps({ player: starting(4) })
    expect(w.find('.bb-waiting__title').text()).toBe('Starting in 4…')
    w.destroy()
  })

  it('tells screen readers about the countdown when it begins and at 3, 2 and 1 only', async () => {
    vi.useFakeTimers()
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 2, min: 3 } } } } })
    const live = () => w.find('.bb-waiting__sr')
    expect(w.attributes('role')).toBeUndefined()
    expect(w.attributes('aria-live')).toBeUndefined()
    expect(live().attributes('aria-live')).toBe('polite')
    expect(live().text()).toBe('Waiting for the others')

    await w.setProps({ player: { _system: { autopilot: { state: 'starting', seconds: 5, here: 3, min: 3 } } } })
    const heard = [live().text()]
    for (let i = 0; i < 5; i++) {
      vi.advanceTimersByTime(1000)
      await w.vm.$nextTick()
      if (live().text() !== heard[heard.length - 1]) heard.push(live().text())
    }
    expect(heard).toEqual(['Starting in 5 seconds', '3', '2', '1'])
    expect(w.find('.bb-waiting__title').text()).toBe('Starting…')
    w.destroy()
  })

  it('shows the experiment\'s own waiting text only when it set one', () => {
    const own = mount(BBWaiting, { propsData: { player: { text: '<p>Read this</p>', _system: { autopilot: { state: 'waiting', here: 1, min: 2, customText: true } } } } })
    expect(own.find('.bb-waiting__text').exists()).toBe(true)
    const builtIn = mount(BBWaiting, { propsData: { player: { text: '<p>2 joined</p>', _system: { autopilot: { state: 'waiting', here: 1, min: 2 } } } } })
    expect(builtIn.find('.bb-waiting__text').exists()).toBe(false)
  })
})
