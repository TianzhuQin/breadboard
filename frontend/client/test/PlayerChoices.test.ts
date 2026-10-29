import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayerChoices from '../src/components/PlayerChoices.vue'

// Choices clicked while the connection was down used to be queued without a word, sent much later
// (when the screen had moved on) and ignored. Now they wait for the connection, and say so.

/** Stands in for window.Breadboard: the page's connection, and where choices go. */
function fakeBreadboard (connection = 'open') {
  const listeners: Record<string, Function[]> = {}
  const bb = {
    connection,
    sendChoice: vi.fn(),
    sendGroupChoice: vi.fn(),
    on: vi.fn((event: string, fn: Function) => { (listeners[event] ||= []).push(fn) }),
    off: vi.fn((event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) }),
    /** The page's connection changes (Breadboard's CONNECTION_EVENT) */
    become (state: string) {
      bb.connection = state
      for (const fn of listeners['bb:connection'] || []) fn()
    },
  }
  return bb
}

const stubs = {
  'v-col': { template: '<div><slot /></div>' },
  'v-row': { template: '<div><slot /></div>' },
  // a click from the participant (Choice only passes on trusted clicks)
  'v-btn': { props: ['disabled'], template: '<button :disabled="disabled" @click="$emit(\'click\', { isTrusted: true })"><slot /></button>' },
}

const player = { id: 'me', choices: [{ uid: 'c1', name: 'Cooperate' }, { uid: 'c2', name: 'Defect' }] }

let bb: ReturnType<typeof fakeBreadboard>

beforeEach(() => {
  bb = fakeBreadboard()
  vi.stubGlobal('Breadboard', bb)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function enabled (w: any) {
  return w.findAll('button').wrappers.map((b: any) => !(b.element as HTMLButtonElement).disabled)
}

describe('PlayerChoices', () => {
  it('waits for the connection, and says so, while it is down', async () => {
    const w = mount(PlayerChoices, { propsData: { player }, stubs })
    expect(enabled(w)).toEqual([true, true])
    expect(w.find('.bb-reconnecting').exists()).toBe(false)

    bb.become('lost')
    await w.vm.$nextTick()
    expect(enabled(w)).toEqual([false, false])
    expect(w.find('.bb-reconnecting').text()).toBe('Reconnecting… you can answer once the connection is back.')
    await w.findAll('button').at(0).trigger('click')
    // (nor does a click that gets through anyway send anything)
    ;(w.vm as any).submit(player.choices[0])
    expect(bb.sendChoice).not.toHaveBeenCalled()

    bb.become('open')
    await w.vm.$nextTick()
    expect(enabled(w)).toEqual([true, true])
    expect(w.find('.bb-reconnecting').exists()).toBe(false)
    await w.findAll('button').at(1).trigger('click')
    expect(bb.sendChoice).toHaveBeenCalledWith('c2')
  })

  it('also before the page has ever connected', async () => {
    bb = fakeBreadboard('blocked')
    vi.stubGlobal('Breadboard', bb)
    const w = mount(PlayerChoices, { propsData: { player }, stubs })
    expect(enabled(w)).toEqual([false, false])
    expect(w.find('.bb-reconnecting').exists()).toBe(true)
  })

  it('says nothing of reconnecting when the page won\'t (another tab has the seat)', async () => {
    const w = mount(PlayerChoices, { propsData: { player }, stubs })
    bb.become('elsewhere')
    await w.vm.$nextTick()
    expect(enabled(w)).toEqual([false, false])
    expect(w.find('.bb-reconnecting').exists()).toBe(false)
  })

  it('tells a page\'s own choices whether to wait', async () => {
    const w = mount(PlayerChoices, {
      propsData: { player },
      stubs,
      scopedSlots: { choice: '<a class="own" :data-disabled="String(props.disabled)" @click="props.submit(props.choice)">{{ props.choice.name }}</a>' },
    })
    expect(w.findAll('.own').wrappers.map((a: any) => a.attributes('data-disabled'))).toEqual(['false', 'false'])
    bb.become('lost')
    await w.vm.$nextTick()
    expect(w.findAll('.own').wrappers.map((a: any) => a.attributes('data-disabled'))).toEqual(['true', 'true'])
    await w.find('.own').trigger('click')
    expect(bb.sendChoice).not.toHaveBeenCalled()
  })

  it('stops following the connection when it goes', () => {
    const w = mount(PlayerChoices, { propsData: { player }, stubs })
    w.destroy()
    expect(bb.off).toHaveBeenCalledWith('bb:connection', expect.any(Function))
  })

  it('works as before where no page connection says otherwise', async () => {
    vi.stubGlobal('Breadboard', { sendChoice: bb.sendChoice, on: vi.fn(), off: vi.fn() })
    const w = mount(PlayerChoices, { propsData: { player }, stubs })
    expect(enabled(w)).toEqual([true, true])
    await w.findAll('button').at(0).trigger('click')
    expect(bb.sendChoice).toHaveBeenCalledWith('c1')
    w.destroy()
  })
})
