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
import { backups, changePassword, fileSize, friendlyPasswordError, looksLikeEmail, researchers, validatePasswordChange } from '../src/pages/settings/settings'
import SettingsPage from '../src/pages/settings/SettingsPage.vue'
import { theme } from '../src/lib/theme'
import { registerUnsaved, unsavedTotal } from '../src/shell/unsaved'
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

  it('counts a typed new password as unsaved when signing out, while the page is open', async () => {
    const w = await mountPage()
    expect(unsavedTotal()).toBe(0)
    const [, next] = w.findAll('.set-pw input.b-input__el')
    await next.setValue('long-enough-1')
    expect(unsavedTotal()).toBe(1)
    await w.findAll('button').find(b => /Sign out/.test(b.text()))!.trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    expect(confirmState.req!.message).toMatch(/New password \(1\)/)
    settleConfirm(false)
    await flushPromises()
    expect(logout).not.toHaveBeenCalled()
    w.unmount()
    expect(unsavedTotal()).toBe(0)
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

describe('researcher accounts', () => {
  const rows = [
    { email: 'r@lab.org', name: 'r@lab.org', you: true },
    { email: 'ra@lab.org', name: 'Research assistant', you: false },
  ]
  async function mountPage () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: SettingsPage }] })
    router.push('/')
    await router.isReady()
    const w = mount({ template: '<RouterView />' }, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return w
  }
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
    http.get.mockImplementation(async (url: string) => url === '/researchers' ? { researchers: rows } : undefined)
    document.body.innerHTML = ''
  })

  it('knows an email address when it sees one', () => {
    expect(looksLikeEmail(' name@uni.edu ')).toBe(true)
    expect(looksLikeEmail('name')).toBe(false)
    expect(looksLikeEmail('a b@uni.edu')).toBe(false)
  })

  it('adds an account with the trimmed address and hands back its first password', async () => {
    http.post.mockResolvedValue({ researchers: rows, email: 'new@lab.org', password: 'Abc23456789xyz' })
    expect(await researchers.add('  new@lab.org ')).toEqual({ list: rows, email: 'new@lab.org', password: 'Abc23456789xyz' })
    expect(http.post).toHaveBeenCalledWith('/researchers', { email: 'new@lab.org' })
  })

  it('lists the accounts, with actions on the others only', async () => {
    const w = await mountPage()
    const people = w.findAll('.set-people__row')
    expect(people).toHaveLength(2)
    expect(people[0].text()).toMatch(/r@lab\.org.*You/)
    expect(people[0].find('.set-people__actions').exists()).toBe(false)
    expect(people[1].text()).toMatch(/Research assistant/)
    expect(people[1].text()).toMatch(/New password.*Remove/)
    w.unmount()
  })

  it('checks the address before adding, then shows the new password once', async () => {
    const w = await mountPage()
    const input = w.find('.set-people__add input')
    await input.setValue('not an email')
    await w.find('form.set-people__add').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/Enter their email address/)

    await input.setValue('RA@lab.org')
    await w.find('form.set-people__add').trigger('submit')
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/already has an account/)

    http.post.mockResolvedValue({ researchers: [...rows, { email: 'new@lab.org', name: 'new@lab.org', you: false }], email: 'new@lab.org', password: 'Abc23456789xyz' })
    await input.setValue('new@lab.org')
    await w.find('form.set-people__add').trigger('submit')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/researchers', { email: 'new@lab.org' })
    expect(w.findAll('.set-people__row')).toHaveLength(3)
    expect(w.find('.set-people__handout').text()).toMatch(/Account added for new@lab\.org/)
    expect(w.find('.set-people__code').text()).toBe('Abc23456789xyz')
    expect((input.element as HTMLInputElement).value).toBe('')
    w.unmount()
  })

  it('gives a colleague the server\'s address on the network, not localhost, with the sign-in details', async () => {
    const { admin } = await import('@/store/admin') as any
    const written: string[] = []
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (t: string) => { written.push(t) } }, configurable: true })
    http.post.mockResolvedValue({ researchers: rows, email: 'new@lab.org', password: 'Abc23456789xyz' })
    const add = async (w: any) => {
      await w.find('.set-people__add input').setValue('new@lab.org')
      await w.find('form.set-people__add').trigger('submit')
      await flushPromises()
    }
    const copy = async (w: any) => {
      await w.findAll('.set-people__handout button').find((b: any) => b.text() === 'Copy sign-in details')!.trigger('click')
      await flushPromises()
      return written[written.length - 1]
    }
    // (the tests' page is at localhost)
    admin.lanAddresses = ['http://10.254.125.201:19801']
    let w = await mountPage()
    await add(w)
    expect(w.find('.set-people__handout').text()).toContain('the server\'s address on this network, http://10.254.125.201:19801/')
    expect(await copy(w)).toBe('Breadboard: http://10.254.125.201:19801/\nEmail: new@lab.org\nPassword: Abc23456789xyz')
    expect(w.find('.local-note').exists()).toBe(false)
    w.unmount()

    // no address on a network known: the details say localhost, and the note says it only works here
    admin.lanAddresses = []
    w = await mountPage()
    await add(w)
    expect(await copy(w)).toBe(`Breadboard: ${window.location.origin}/\nEmail: new@lab.org\nPassword: Abc23456789xyz`)
    expect(w.find('.set-people__handout .local-note').text().replace(/\s+/g, ' ')).toContain(`The copied details use ${window.location.host}, which only works on this computer.`)
    w.unmount()
    delete admin.lanAddresses
  })

  it('asks before removing an account', async () => {
    const w = await mountPage()
    const remove = w.findAll('.set-people__row')[1].findAll('button').find(b => /Remove/.test(b.text()))!
    await remove.trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    expect(confirmState.req).toMatchObject({ danger: true, confirmLabel: 'Remove account' })
    settleConfirm(false)
    await flushPromises()
    expect(http.post).not.toHaveBeenCalled()

    http.post.mockResolvedValue({ researchers: [rows[0]] })
    await remove.trigger('click')
    await flushPromises()
    settleConfirm(true)
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/researchers/remove', { email: 'ra@lab.org' })
    expect(w.findAll('.set-people__row')).toHaveLength(1)
    w.unmount()
  })

  it('gives another account a new password after asking', async () => {
    const w = await mountPage()
    http.post.mockResolvedValue({ email: 'ra@lab.org', password: 'Zyx98765432abc' })
    await w.findAll('.set-people__row')[1].findAll('button').find(b => /New password/.test(b.text()))!.trigger('click')
    await flushPromises()
    expect(confirmState.open).toBe(true)
    settleConfirm(true)
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/researchers/password', { email: 'ra@lab.org' })
    expect(w.find('.set-people__handout').text()).toMatch(/New password for ra@lab\.org/)
    expect(w.find('.set-people__code').text()).toBe('Zyx98765432abc')
    w.unmount()
  })
})

describe('backups', () => {
  const status = {
    enabled: true,
    folder: '/srv/breadboard/backups',
    keep: 14,
    everyHours: 24,
    problem: null,
    backups: [
      { name: 'breadboard-2026-10-28-120000-manual.zip', size: 5_400_000, createdAt: '2026-10-28T12:00:00Z' },
      { name: 'breadboard-2026-10-27-120000.zip', size: 5_300_000, createdAt: '2026-10-27T12:00:00Z' },
    ],
  }
  async function mountPage () {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: SettingsPage }] })
    router.push('/')
    await router.isReady()
    const w = mount({ template: '<RouterView />' }, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    return w
  }
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
    http.get.mockImplementation(async (url: string) => url === '/backups' ? status : undefined)
    document.body.innerHTML = ''
  })

  it('sizes files the way people read them', () => {
    expect(fileSize(900)).toBe('900 B')
    expect(fileSize(1536)).toBe('1.5 KB')
    expect(fileSize(5_400_000)).toBe('5.1 MB')
  })

  it('links each backup for download', () => {
    expect(backups.url('breadboard-2026-10-28-120000.zip')).toBe('/backups/breadboard-2026-10-28-120000.zip')
  })

  it('shows the latest backup, where they are, and how to go back to one', async () => {
    const w = await mountPage()
    const section = w.find('.set-backups')
    expect(section.text()).toMatch(/Last backup/)
    expect(section.text()).toMatch(/A new one every day; the newest 14 are kept/)
    expect(section.text()).toMatch(/Made by hand/)
    expect(section.text()).toMatch(/\/srv\/breadboard\/backups/)
    expect(section.findAll('a').map(a => a.attributes('href'))).toContain('/backups/breadboard-2026-10-27-120000.zip')
    w.unmount()
  })

  it('backs up when asked', async () => {
    http.post.mockResolvedValue({ ...status, backups: [{ name: 'breadboard-2026-10-28-130000-manual.zip', size: 10, createdAt: '2026-10-28T13:00:00Z' }, ...status.backups] })
    const w = await mountPage()
    await w.find('.set-backups').findAll('button').find(b => /Back up now/.test(b.text()))!.trigger('click')
    await flushPromises()
    expect(http.post).toHaveBeenCalledWith('/backups')
    expect(w.findAll('.set-backups__row')).toHaveLength(3)
    w.unmount()
  })

  it('says when the server doesn\'t back up', async () => {
    http.get.mockImplementation(async (url: string) => url === '/backups' ? { ...status, enabled: false, backups: [] } : undefined)
    const w = await mountPage()
    expect(w.find('.set-backups').text()).toMatch(/doesn't back up its database by itself/)
    expect(w.find('.set-backups').findAll('button').some(b => /Back up now/.test(b.text()))).toBe(false)
    w.unmount()
  })
})
