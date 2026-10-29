import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, Wrapper } from '@vue/test-utils'
import ConnectionStatus from '../src/components/ConnectionStatus.vue'
import Timer from '../src/components/Timer.vue'

// The banner a participant sees while the page has lost its connection. It used to say
// "Reconnecting in 9 s…" with the wait from when the attempt was planned, which never counted down,
// and the round timers stayed on as if the time were still true. It now also offers to try again at
// once, and says so when reconnecting is not going to help.

/** Stands in for window.Breadboard: what ConnectionStatus listens to and asks. */
function fakeBreadboard (connection = 'open') {
  const listeners: Record<string, Function[]> = {}
  const bb = {
    connection,
    connect: vi.fn(async () => ({})),
    reconnectNow: vi.fn(),
    useHere: vi.fn(),
    on: vi.fn((event: string, fn: Function) => { (listeners[event] ||= []).push(fn) }),
    off: vi.fn((event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) }),
    emit (event: string, ...args: any[]) { for (const fn of listeners[event] || []) fn(...args) },
    /** The page's connection changes (Breadboard's CONNECTION_EVENT) */
    become (state: string) {
      bb.connection = state
      bb.emit('bb:connection')
    },
  }
  return bb
}

// Vuetify's alert shows its content only while its value is true.
const stubs = { 'v-alert': { props: ['value'], template: '<div v-if="value" class="bb-connection"><slot /></div>' } }

let breadboard: ReturnType<typeof fakeBreadboard>
let wrapper: Wrapper<any> | null = null

async function settle () {
  for (let k = 0; k < 10; k++) await Promise.resolve()
  if (wrapper) await wrapper.vm.$nextTick()
}

async function mountStatus (options: any = {}) {
  wrapper = mount(ConnectionStatus as any, { stubs, ...options })
  await settle()
  return wrapper
}

/** The connection drops: the socket plans its next attempt in `wait` ms, then says it closed. */
async function drop (wait: number) {
  breadboard.emit('retry', wait)
  breadboard.become('lost')
  await settle()
}

async function become (state: string) {
  breadboard.become(state)
  await settle()
}

function text (w: Wrapper<any>) {
  return w.find('.bb-connection__text').text()
}

function buttons (w: Wrapper<any>) {
  return w.findAll('.bb-connection__btn').wrappers.map(b => b.text())
}

beforeEach(() => {
  vi.useFakeTimers()
  breadboard = fakeBreadboard()
  vi.stubGlobal('Breadboard', breadboard)
})

afterEach(() => {
  if (wrapper) wrapper.destroy()
  wrapper = null
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ConnectionStatus', () => {
  it('says the connection is lost without a wait that has gone stale', async () => {
    const w = await mountStatus()
    expect(w.find('.bb-connection').exists()).toBe(false)
    await drop(9000)
    expect(text(w)).toBe('Connection lost. Trying to reconnect… Please keep this page open.')
    vi.advanceTimersByTime(4000)
    await settle()
    expect(w.text()).not.toMatch(/\d/)
    await become('open')
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('is not shown while the page makes its first connection', async () => {
    breadboard = fakeBreadboard('connecting')
    vi.stubGlobal('Breadboard', breadboard)
    const w = await mountStatus()
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('tries again at once when asked', async () => {
    const w = await mountStatus()
    await drop(20000)
    expect(buttons(w)).toEqual(['Try now'])
    await w.find('.bb-connection__btn').trigger('click')
    expect(breadboard.reconnectNow).toHaveBeenCalledTimes(1)
  })

  it('counts down to the next attempt for a page that shows its own message', async () => {
    const w = await mountStatus({ scopedSlots: { default: '<span>next try in {{ Math.ceil(props.time / 1000) }} s</span>' } })
    await drop(9000)
    expect(w.text()).toBe('next try in 9 s')
    vi.advanceTimersByTime(1000)
    await settle()
    expect(w.text()).toBe('next try in 8 s')
    vi.advanceTimersByTime(9000)
    await settle()
    expect(w.text()).toBe('next try in 0 s')
    // the attempt fails: the next one is planned
    await drop(12000)
    expect(w.text()).toBe('next try in 12 s')
    // the participant tries again at once: an attempt is under way
    breadboard.emit('connect')
    await settle()
    expect(w.text()).toBe('next try in 0 s')
  })

  it('gives a page\'s own message what it needs to say why, and to act', async () => {
    const w = await mountStatus({
      scopedSlots: { default: '<button class="own" @click="props.state === \'elsewhere\' ? props.useHere() : props.tryNow()">{{ props.state }}</button>' },
    })
    await become('blocked')
    expect(w.find('.own').text()).toBe('blocked')
    await w.find('.own').trigger('click')
    expect(breadboard.reconnectNow).toHaveBeenCalledTimes(1)
    await become('elsewhere')
    await w.find('.own').trigger('click')
    expect(breadboard.useHere).toHaveBeenCalledTimes(1)
  })

  it('shows on a page whose connection was already lost', async () => {
    breadboard = fakeBreadboard('lost')
    vi.stubGlobal('Breadboard', breadboard)
    const w = await mountStatus()
    expect(w.text()).toContain('Connection lost')
  })

  it('says when the network may not let the connection through', async () => {
    const w = await mountStatus()
    await drop(1000)
    await become('blocked')
    expect(text(w)).toBe('This page can’t open its live connection to the study; the network may block it. Please try another network (e.g. mobile data) or browser.')
    expect(buttons(w)).toEqual(['Try now'])
    await become('open')
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('says the study is open in another tab, and takes the seat back here when asked', async () => {
    const w = await mountStatus()
    await become('elsewhere')
    expect(text(w)).toBe('This study is open in another tab or window.')
    expect(buttons(w)).toEqual(['Use it here'])
    await w.find('.bb-connection__btn').trigger('click')
    expect(breadboard.useHere).toHaveBeenCalledTimes(1)
    expect(breadboard.reconnectNow).not.toHaveBeenCalled()
    // the page connects again
    await become('lost')
    expect(buttons(w)).toEqual(['Try now'])
    await become('open')
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('says the session is over, without asking to keep the page open, when the page shows no end of its own', async () => {
    const w = await mountStatus()
    await drop(5000)
    await become('ended')
    expect(text(w)).toBe('This session is over. You can close this page.')
    expect(buttons(w)).toEqual([])
  })

  it('goes away for good once the session is over and the page shows its end', async () => {
    const w = await mountStatus()
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(true)
    // what the server sends a page whose session is over
    breadboard.become('ended')
    breadboard.emit('player', { _system: {}, finished: true, ended: 'notRunning', text: 'This session isn\'t running' })
    await settle()
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('is not shown over the screen of a participant who is done', async () => {
    const w = await mountStatus()
    breadboard.emit('player', { finished: true, text: 'Thank you' })
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(false)
    await become('ended')
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('is not shown over a completion code either', async () => {
    const w = await mountStatus()
    breadboard.emit('player', { _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C0DE' } } })
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(false)
    await become('ended')
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('dims what the page shows while it shows', async () => {
    const root = document.documentElement
    const w = await mountStatus()
    expect(root.classList.contains('bb-disconnected')).toBe(false)
    await drop(5000)
    expect(root.classList.contains('bb-disconnected')).toBe(true)
    await become('open')
    expect(root.classList.contains('bb-disconnected')).toBe(false)
    await become('elsewhere')
    expect(root.classList.contains('bb-disconnected')).toBe(true)
    w.destroy()
    wrapper = null
    expect(root.classList.contains('bb-disconnected')).toBe(false)

    // what the rules dim
    const timer = mount(Timer as any, {
      propsData: { timer: { id: 't', duration: 60000, elapsed: 1000, direction: 'down', timerText: 'Round ends in', appearance: 'info', type: 'default' } },
      stubs: { 'v-progress-linear': { template: '<div class="v-progress-linear"><slot /></div>' } },
    })
    expect(timer.classes()).toContain('bb-timer')
    timer.destroy()
  })

  it('stops listening when it goes', async () => {
    const w = await mountStatus()
    w.destroy()
    wrapper = null
    for (const event of ['bb:connection', 'retry', 'connect', 'player']) {
      expect(breadboard.off).toHaveBeenCalledWith(event, expect.any(Function))
    }
    // and a change afterwards changes nothing
    breadboard.become('lost')
    expect(document.documentElement.classList.contains('bb-disconnected')).toBe(false)
  })
})
