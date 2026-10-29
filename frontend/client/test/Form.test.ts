import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

// Form takes window.Breadboard when it is loaded: the stand-in has to be there first.
const bb = vi.hoisted(() => {
  const listeners: Record<string, Function[]> = {}
  const fake: any = {
    connection: 'open',
    send: vi.fn(),
    on: (event: string, fn: Function) => { (listeners[event] ||= []).push(fn) },
    off: (event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) },
    emit (event: string, ...args: any[]) { for (const fn of listeners[event] || []) fn(...args) },
    /** The page's connection changes (Breadboard's CONNECTION_EVENT) */
    become (state: string) {
      fake.connection = state
      fake.emit('bb:connection')
    },
  }
  ;(globalThis as any).Breadboard = fake
  return fake
})

import Form from '../src/components/form/Form.vue'

// A page of answers sent while the connection is down used to wait in a queue and arrive much
// later, or never. Now the form waits for the connection, and says so.

const passThrough = { template: '<div><slot /></div>' }
const stubs = {
  'v-stepper': passThrough,
  'v-stepper-header': passThrough,
  'v-stepper-step': passThrough,
  'v-form': passThrough,
  'v-container': passThrough,
  'v-layout': passThrough,
  'v-flex': { template: '<div />' },
  'v-divider': { template: '<hr />' },
  'v-spacer': { template: '<span />' },
  'v-icon': { template: '<i />' },
  'v-btn': { props: ['disabled', 'loading'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
}

const form = {
  efficient: false,
  showStepper: false,
  nonLinear: false,
  location: { index: 1, size: 3 },
  pages: [{ index: 0, title: 'One' }, { index: 1, title: 'Two' }, { index: 2, title: 'Three' }],
  page: { title: 'Two', sections: [{ blocks: [{ type: 'HTML', name: '', content: '<p>Almost done</p>' }] }] },
}

function button (w: any, label: string) {
  return w.findAll('button').wrappers.find((b: any) => b.text().includes(label))
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(console, 'log').mockImplementation(() => {})
  bb.connection = 'open'
  bb.send.mockClear()
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

/** A form on its second page, ready to go on */
async function mountForm () {
  const w = mount(Form, { propsData: { player: { id: 'me', forms: { intro: form } } }, stubs })
  await w.vm.$nextTick()
  // it asks the server for the form, which answers
  bb.emit('f-fetch', { name: 'intro', form })
  ;(w.vm as any).valid = true
  await w.vm.$nextTick()
  bb.send.mockClear()
  return w
}

describe('Form', () => {
  it('waits for the connection, and says so, while it is down', async () => {
    const w = await mountForm()
    expect((button(w, 'Next').element as HTMLButtonElement).disabled).toBe(false)
    expect(w.find('.bb-reconnecting').exists()).toBe(false)

    bb.become('lost')
    await w.vm.$nextTick()
    expect((button(w, 'Next').element as HTMLButtonElement).disabled).toBe(true)
    expect((button(w, 'Previous').element as HTMLButtonElement).disabled).toBe(true)
    expect(w.find('.bb-reconnecting').text()).toBe('Reconnecting… you can answer once the connection is back.')
    ;(w.vm as any).next()
    ;(w.vm as any).prev()
    expect(bb.send).not.toHaveBeenCalled()

    bb.become('open')
    await w.vm.$nextTick()
    expect(w.find('.bb-reconnecting').exists()).toBe(false)
    await button(w, 'Next').trigger('click')
    expect(bb.send).toHaveBeenCalledWith('f-next', expect.objectContaining({ name: 'intro' }))
    w.destroy()
  })
})
