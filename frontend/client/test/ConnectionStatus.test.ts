import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, Wrapper } from '@vue/test-utils'
import ConnectionStatus from '../src/components/ConnectionStatus.vue'
import Timer from '../src/components/Timer.vue'

// The banner a participant sees while the page has lost its connection. It used to say
// "Reconnecting in 9 s…" with the wait from when the attempt was planned, which never counted down,
// and the round timers stayed on as if the time were still true.

const OPEN = 1
const CLOSED = 0

/** Stands in for window.Breadboard: what ConnectionStatus listens to. */
function fakeBreadboard (state = OPEN) {
  const listeners: Record<string, Function[]> = {}
  return {
    ended: false,
    connect: vi.fn(async () => ({ state })),
    on: vi.fn((event: string, fn: Function) => { (listeners[event] ||= []).push(fn) }),
    off: vi.fn((event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) }),
    emit (event: string, ...args: any[]) { for (const fn of listeners[event] || []) fn(...args) },
  }
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
  breadboard.emit('close')
  await settle()
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
    expect(w.text()).toBe('Connection lost. Trying to reconnect… Please keep this page open.')
    vi.advanceTimersByTime(4000)
    await settle()
    expect(w.text()).not.toMatch(/\d/)
    breadboard.emit('open')
    await settle()
    expect(w.find('.bb-connection').exists()).toBe(false)
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
  })

  it('shows on a page whose connection was already lost', async () => {
    breadboard = fakeBreadboard(CLOSED)
    vi.stubGlobal('Breadboard', breadboard)
    const w = await mountStatus()
    expect(w.text()).toContain('Connection lost')
  })

  it('goes away for good once the session is over for the page', async () => {
    const w = await mountStatus()
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(true)
    breadboard.emit('ended')
    await settle()
    expect(w.find('.bb-connection').exists()).toBe(false)
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('is not shown over the screen of a participant who is done', async () => {
    const w = await mountStatus()
    breadboard.emit('player', { finished: true, text: 'Thank you' })
    await drop(5000)
    expect(w.find('.bb-connection').exists()).toBe(false)
  })

  it('dims the timers while it shows', async () => {
    const root = document.documentElement
    const w = await mountStatus()
    expect(root.classList.contains('bb-disconnected')).toBe(false)
    await drop(5000)
    expect(root.classList.contains('bb-disconnected')).toBe(true)
    breadboard.emit('open')
    await settle()
    expect(root.classList.contains('bb-disconnected')).toBe(false)
    await drop(5000)
    w.destroy()
    wrapper = null
    expect(root.classList.contains('bb-disconnected')).toBe(false)

    // what the rule dims
    const timer = mount(Timer as any, {
      propsData: { timer: { id: 't', duration: 60000, elapsed: 1000, direction: 'down', timerText: 'Round ends in', appearance: 'info', type: 'default' } },
      stubs: { 'v-progress-linear': { template: '<div class="v-progress-linear"><slot /></div>' } },
    })
    expect(timer.classes()).toContain('bb-timer')
    timer.destroy()
  })
})
