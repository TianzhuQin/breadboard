import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { h, nextTick } from 'vue'
import BSelect from '../src/ui/BSelect.vue'
import BTabs from '../src/ui/BTabs.vue'
import BDialog from '../src/ui/BDialog.vue'
import BToasts from '../src/ui/BToasts.vue'
import BMenu from '../src/ui/BMenu.vue'
import { confirm, confirmState, settleConfirm } from '../src/ui/confirm'
import { toasts, toast, dismiss, holdToast, releaseToast } from '../src/ui/toast'
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
  afterEach(() => { vi.useRealTimers() })
  const shown = (id: number) => toasts.some(t => t.id === id)

  it('adds and dismisses', async () => {
    const id = toast('info', 'Hello', undefined, { timeout: 0 })
    await nextTick()
    expect(toasts.some(t => t.id === id)).toBe(true)
    dismiss(id)
    expect(toasts.some(t => t.id === id)).toBe(false)
  })

  it('leaves time to reach an Undo button', () => {
    vi.useFakeTimers()
    const plain = toast('info', 'Saved')
    const undo = toast('info', 'Reverted', undefined, { action: { label: 'Undo', run: () => {} } })
    const short = toast('success', 'Deleted', undefined, { timeout: 8000, action: { label: 'Undo', run: () => {} } })
    vi.advanceTimersByTime(4000)
    expect(shown(plain)).toBe(false)
    expect(shown(undo)).toBe(true)
    vi.advanceTimersByTime(5000)
    expect(shown(short)).toBe(true)
    vi.advanceTimersByTime(1100)
    expect(shown(undo)).toBe(false)
    expect(shown(short)).toBe(false)
  })

  it('waits while the pointer is over it or focus is in it', () => {
    vi.useFakeTimers()
    const id = toast('info', 'Reverted', undefined, { timeout: 3000 })
    vi.advanceTimersByTime(2000)
    holdToast(id, 'pointer')
    holdToast(id, 'focus')
    vi.advanceTimersByTime(60000)
    expect(shown(id)).toBe(true)
    releaseToast(id, 'pointer')
    vi.advanceTimersByTime(60000)
    expect(shown(id)).toBe(true)
    releaseToast(id, 'focus')
    // what was left (1 s), but at least a moment to move away
    vi.advanceTimersByTime(1400)
    expect(shown(id)).toBe(true)
    vi.advanceTimersByTime(200)
    expect(shown(id)).toBe(false)
  })

  it('holds a toast on hover and focus in the corner', async () => {
    vi.useFakeTimers()
    const w = mount(BToasts, { attachTo: document.body })
    const id = toast('info', 'Reverted', undefined, { timeout: 3000, action: { label: 'Undo', run: () => {} } })
    await nextTick()
    const el = w.find('.b-toast')
    await el.trigger('pointerenter', { pointerType: 'mouse' })
    vi.advanceTimersByTime(30000)
    expect(shown(id)).toBe(true)
    await el.trigger('pointerleave', { pointerType: 'mouse' })
    await el.find('.b-toast__action').trigger('focusin')
    vi.advanceTimersByTime(30000)
    expect(shown(id)).toBe(true)
    // moving to its own close button keeps it held
    await el.find('.b-toast__action').trigger('focusout', { relatedTarget: el.find('.b-toast__close').element })
    vi.advanceTimersByTime(30000)
    expect(shown(id)).toBe(true)
    await el.find('.b-toast__close').trigger('focusout', { relatedTarget: document.body })
    vi.advanceTimersByTime(10000)
    expect(shown(id)).toBe(false)
    w.unmount()
  })

  it('is not held by a tap, which only leaves with a tap somewhere else', async () => {
    vi.useFakeTimers()
    const w = mount(BToasts, { attachTo: document.body })
    const id = toast('info', 'Switched to B', undefined, { timeout: 3000 })
    await nextTick()
    const el = w.find('.b-toast')
    await el.trigger('pointerenter', { pointerType: 'touch' })
    // (touch screens also send mouseenter after a tap; the toast no longer listens to it)
    await el.trigger('mouseenter')
    vi.advanceTimersByTime(3100)
    expect(shown(id)).toBe(false)
    w.unmount()
  })
})

describe('BDialog', () => {
  // jsdom lays nothing out, so every element would count as hidden
  const offsetParent = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetParent')
  afterEach(() => {
    if (offsetParent) Object.defineProperty(HTMLElement.prototype, 'offsetParent', offsetParent)
    document.body.innerHTML = ''
  })

  async function openDialog () {
    Object.defineProperty(HTMLElement.prototype, 'offsetParent', { configurable: true, get () { return document.body } })
    const behind = document.createElement('button')
    behind.textContent = 'Behind'
    document.body.appendChild(behind)
    const w = mount(BDialog, {
      props: { open: true, title: 'Import' },
      slots: { default: () => [h('input', { id: 'a' }), h('button', { id: 'b' }, 'Go')] },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()
    const panel = document.querySelector<HTMLElement>('.b-dialog__panel')!
    return { w, panel, behind }
  }
  const press = (el: Element, shiftKey = false) => {
    const ev = new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true })
    el.dispatchEvent(ev)
    return ev
  }

  it('keeps Shift+Tab inside when the panel itself has focus', async () => {
    const { w, panel } = await openDialog()
    // nothing asked for focus, so the panel has it
    expect(document.activeElement).toBe(panel)
    const ev = press(panel, true)
    expect(ev.defaultPrevented).toBe(true)
    expect(document.activeElement!.id).toBe('b')
    // and Tab from the last goes round to the first (the close button)
    press(document.activeElement!)
    expect(document.activeElement!.getAttribute('aria-label')).toBe('Close')
    w.unmount()
  })

  it('brings focus back when it gets out while open', async () => {
    const { w, panel, behind } = await openDialog()
    behind.focus()
    expect(panel.contains(document.activeElement)).toBe(true)
    await w.setProps({ open: false })
    behind.focus()
    expect(document.activeElement).toBe(behind)
    w.unmount()
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

describe('BMenu', () => {
  afterEach(() => { document.body.innerHTML = '' })

  /** A menu whose second item runs `act` with a field outside the menu (like Steps > New, which focuses the new step's name). */
  async function openMenu (act: (field: HTMLInputElement) => void) {
    const field = document.createElement('input')
    document.body.appendChild(field)
    const w = mount(BMenu, {
      attachTo: document.body,
      props: { items: [{ label: 'Plain', run: () => {} }, { label: 'Name it', run: () => act(field) }] },
      slots: { trigger: (p: any) => h('button', { type: 'button', class: 'trig', ...p.attrs, onClick: p.toggle }, 'New') },
    })
    const trigger = w.find('.trig').element as HTMLElement
    const open = async () => {
      await w.find('.trig').trigger('click')
      await nextTick()
      await nextTick()
      // (opening focuses the first item)
      expect(w.find('[role=menu]').element.contains(document.activeElement)).toBe(true)
    }
    const choose = (i: number) => w.findAll('[role=menuitem]')[i].trigger('click')
    return { w, field, trigger, open, choose }
  }

  it('gives focus back to its trigger after an item that does not move it', async () => {
    const { w, trigger, open, choose } = await openMenu(() => {})
    await open()
    await choose(0)
    expect(w.find('[role=menu]').exists()).toBe(false)
    expect(document.activeElement).toBe(trigger)
    await open()
    await w.find('.b-menu').trigger('keydown', { key: 'Escape' })
    expect(document.activeElement).toBe(trigger)
    w.unmount()
  })

  it('leaves focus where the chosen item\'s action put it, so Enter there does not reopen the menu', async () => {
    const now = await openMenu(f => f.focus())
    await now.open()
    await now.choose(1)
    expect(document.activeElement).toBe(now.field)
    expect(now.w.find('[role=menu]').exists()).toBe(false)
    now.w.unmount()

    // also when the field only appears (and takes focus) a moment later
    const later = await openMenu(f => { void nextTick(() => nextTick(() => f.focus())) })
    await later.open()
    await later.choose(1)
    await nextTick()
    await nextTick()
    expect(document.activeElement).toBe(later.field)
    later.w.unmount()
  })

  it('does not take focus back from elsewhere when closed', async () => {
    const { w, field, open } = await openMenu(() => {})
    await open()
    field.focus()
    ;(w.vm as any).close(true)
    await nextTick()
    expect(w.find('[role=menu]').exists()).toBe(false)
    expect(document.activeElement).toBe(field)
    w.unmount()
  })
})
