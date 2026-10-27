import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import BSelect from '../src/ui/BSelect.vue'
import BTabs from '../src/ui/BTabs.vue'
import { confirm, confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts, toast, dismiss } from '../src/ui/toast'
import { scaleFor, NEUTRAL } from '../src/network/palette'

describe('BSelect', () => {
  it('keeps values of any type (numbers, objects), not just strings', async () => {
    const a = { id: 1 }
    const b = { id: 2 }
    const w = mount(BSelect, { props: { modelValue: a, options: [{ value: a, label: 'A' }, { value: b, label: 'B' }] } })
    const select = w.find('select')
    expect((select.element as HTMLSelectElement).value).toBe('0')
    await select.setValue('1')
    expect(w.emitted('update:modelValue')![0][0]).toEqual(b)
  })
})

describe('BTabs', () => {
  it('moves between tabs with the arrow keys', async () => {
    const w = mount(BTabs, { props: { modelValue: 'a', tabs: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }] } })
    await w.find('[role=tablist]').trigger('keydown', { key: 'ArrowRight' })
    expect(w.emitted('update:modelValue')![0][0]).toBe('b')
    expect(w.findAll('[role=tab]')[0].attributes('aria-selected')).toBe('true')
  })
})

describe('confirm', () => {
  it('resolves with the answer, and a new question cancels an unanswered one', async () => {
    const first = confirm({ title: 'One?' })
    const second = confirm({ title: 'Two?' })
    expect(await first).toBe(false)
    expect(confirmState.req!.title).toBe('Two?')
    settleConfirm(true)
    expect(await second).toBe(true)
    expect(confirmState.open).toBe(false)
  })
})

describe('toast', () => {
  it('adds and dismisses', async () => {
    const id = toast('info', 'Hello', undefined, { timeout: 0 })
    await nextTick()
    expect(toasts.some(t => t.id === id)).toBe(true)
    dismiss(id)
    expect(toasts.some(t => t.id === id)).toBe(false)
  })
})

describe('network colours', () => {
  it('gives categories distinct colours and missing values a neutral one', () => {
    const s = scaleFor(['red', 'blue', 'red', undefined])
    expect(s.kind).toBe('categorical')
    expect(s.color('red')).not.toBe(s.color('blue'))
    expect(s.color(undefined)).toBe(NEUTRAL)
    expect(s.legend.map(l => l.label)).toEqual(['blue', 'red'])
  })

  it('uses a gradient for many numbers', () => {
    const s = scaleFor(Array.from({ length: 20 }, (_, i) => i))
    expect(s.kind).toBe('numeric')
    expect(s.color(0)).not.toBe(s.color(19))
  })
})
