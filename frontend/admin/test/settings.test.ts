import { describe, it, expect, vi, beforeEach } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})

const logout = vi.hoisted(() => vi.fn(async () => {}))
const adoptUid = vi.hoisted(() => vi.fn())
vi.mock('@/store/admin', async () => {
  const { computed, reactive, ref } = await import('vue')
  return {
    admin: reactive({ email: 'r@lab.org' }),
    connectionState: ref('open'),
    experiments: computed(() => []),
    logout,
    adoptUid,
  }
})

import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { changePassword, friendlyPasswordError, validatePasswordChange } from '../src/pages/settings/settings'
import SettingsPage from '../src/pages/settings/SettingsPage.vue'
import { theme } from '../src/lib/theme'
import { registerUnsaved } from '../src/shell/unsaved'
import { confirmState, settleConfirm } from '../src/ui/confirm'

describe('password change checks', () => {
  it('asks for every field', () => {
    expect(validatePasswordChange({ current: '', next: '', confirm: '' })).toMatchObject({ current: expect.any(String), next: expect.any(String) })
  })
  it('wants 8 characters, no outer spaces, and something new', () => {
    expect(validatePasswordChange({ current: 'old-pass', next: 'short', confirm: 'short' }).next).toMatch(/8 characters/)
    expect(validatePasswordChange({ current: 'old-pass', next: ' spaced-out', confirm: ' spaced-out' }).next).toMatch(/space/)
    expect(validatePasswordChange({ current: 'same-pass', next: 'same-pass', confirm: 'same-pass' }).next).toMatch(/different/)
  })
  it('wants the repeat to match', () => {
    expect(validatePasswordChange({ current: 'old-pass', next: 'new-password', confirm: 'new-passw0rd' }).confirm).toMatch(/match/)
    expect(validatePasswordChange({ current: 'old-pass', next: 'new-password', confirm: 'new-password' })).toEqual({})
  })
})

describe('the server\'s answers', () => {
  it('puts a wrong current password on that field', () => {
    expect(friendlyPasswordError('Current password doesn\'t match user\'s password')).toEqual({ field: 'current', text: 'That isn\'t your current password.' })
  })
  it('keeps unknown messages as they are', () => {
    expect(friendlyPasswordError('Something odd')).toEqual({ text: 'Something odd' })
  })
})

describe('saving a new password', () => {
  beforeEach(() => http.post.mockReset())
  it('posts the form-encoded fields models.UserSettings expects', async () => {
    http.post.mockResolvedValue({ success: true })
    const res = await changePassword('r@lab.org', { current: 'old-pass', next: 'new-password', confirm: 'new-password' })
    expect(res.success).toBe(true)
    expect(http.post).toHaveBeenCalledWith('/saveUserSettings', {
      email: 'r@lab.org', currentPassword: 'old-pass', newPassword: 'new-password', confirmPassword: 'new-password',
    }, { form: true })
  })
  it('passes the new uid on', async () => {
    http.post.mockResolvedValue({ success: true, uid: 'new-uid' })
    expect(await changePassword('r@lab.org', { current: 'old-pass', next: 'new-password', confirm: 'new-password' })).toEqual({ success: true, uid: 'new-uid' })
  })
  it('passes the server\'s error back', async () => {
    http.post.mockResolvedValue({ success: false, error: 'Current password is missing' })
    expect(await changePassword('r@lab.org', { current: '', next: 'x', confirm: 'x' })).toEqual({ success: false, error: 'Current password is missing' })
  })
})

describe('Settings page', () => {
  async function mountPage () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: SettingsPage }] })
    router.push('/')
    await router.isReady()
    const w = mount({ template: '<RouterView />' }, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return w
  }
  beforeEach(() => { http.post.mockReset(); logout.mockClear(); adoptUid.mockClear(); document.body.innerHTML = '' })

  it('shows the account and signs out', async () => {
    const w = await mountPage()
    expect(w.text()).toMatch(/r@lab\.org/)
    await w.findAll('button').find(b => /Sign out/.test(b.text()))!.trigger('click')
    await flushPromises()
    expect(logout).toHaveBeenCalled()
    w.unmount()
  })

  it('asks before signing out while work elsewhere is unsaved', async () => {
    const unregister = registerUnsaved('steps', () => 2, 'Steps')
    try {
      const w = await mountPage()
      const signOut = w.findAll('button').find(b => /Sign out/.test(b.text()))!
      await signOut.trigger('click')
      await flushPromises()
      expect(confirmState.open).toBe(true)
      expect(confirmState.req).toMatchObject({ danger: true, confirmLabel: 'Sign out anyway' })
      expect(confirmState.req!.message).toMatch(/Steps \(2\)/)
      settleConfirm(false)
      await flushPromises()
      expect(logout).not.toHaveBeenCalled()

      await signOut.trigger('click')
      await flushPromises()
      settleConfirm(true)
      await flushPromises()
      expect(logout).toHaveBeenCalledTimes(1)
      w.unmount()
    } finally {
      unregister()
    }
  })

  it('keeps the connection working after a password change by taking the new uid', async () => {
    http.post.mockResolvedValue({ success: true, uid: 'fresh-uid' })
    const w = await mountPage()
    const [cur, next, rep] = w.findAll('.set-pw input.b-input__el')
    await cur.setValue('old-pass')
    await next.setValue('long-enough-1')
    await rep.setValue('long-enough-1')
    await w.find('form.set-pw').trigger('submit')
    await flushPromises()
    expect(adoptUid).toHaveBeenCalledWith('fresh-uid')
    w.unmount()
  })

  it('no longer tells researchers to use one console at a time', async () => {
    const w = await mountPage()
    expect(w.text()).not.toMatch(/whichever console connected last|one console at a time/)
    w.unmount()
  })

  it('checks the new password before sending, then shows the server\'s answer on the right field', async () => {
    const w = await mountPage()
    const [cur, next, rep] = w.findAll('.set-pw input.b-input__el')
    await cur.setValue('old-pass')
    await next.setValue('short')
    await rep.setValue('short')
    await w.find('form.set-pw').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/at least 8 characters/i)

    http.post.mockResolvedValue({ success: false, error: 'Current password doesn\'t match user\'s password' })
    await next.setValue('long-enough-1')
    await rep.setValue('long-enough-1')
    await w.find('form.set-pw').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledTimes(1)
    expect(w.text()).toMatch(/That isn't your current password/)
    w.unmount()
  })

  it('switches the theme', async () => {
    const w = await mountPage()
    const dark = w.findAll('input[name="set-theme"]').find(i => (i.element as HTMLInputElement).value === 'dark')!
    await dark.setValue(true)
    expect(theme.value).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    const system = w.findAll('input[name="set-theme"]').find(i => (i.element as HTMLInputElement).value === 'system')!
    await system.setValue(true)
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
    w.unmount()
  })
})
