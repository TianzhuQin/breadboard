import { afterEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import AccountMenu from '../src/shell/AccountMenu.vue'

afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })

it('opens the release’s own guides from every Help menu choice', async () => {
  const open = vi.spyOn(window, 'open').mockReturnValue(null)
  for (const [label, address] of [
    ['The console’s guide', '/#/help/console.md'],
    ['中文快速上手 (Chinese)', '/#/help/zh-CN.md'],
    ['Troubleshooting', '/#/help/install.md#troubleshooting'],
  ]) {
    const w = mount(AccountMenu, { attachTo: document.body })
    await w.find('.acct').trigger('click')
    await nextTick()
    await w.findAll('[role=menuitem]').find(b => b.text().includes(label))!.trigger('click')
    expect(open).toHaveBeenLastCalledWith(address, '_blank', 'noopener')
    w.unmount()
  }
})
