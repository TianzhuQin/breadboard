import { describe, it, expect, vi, beforeEach } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', async (orig) => {
  const real = await orig<typeof import('../src/lib/http')>()
  return { ...real, http }
})

const createFirstUser = vi.hoisted(() => vi.fn(async () => {}))
vi.mock('@/store/admin', async () => {
  const { reactive } = await import('vue')
  return { admin: reactive({ setupCodeRequired: false }), boot: vi.fn(), createFirstUser }
})

import { flushPromises, mount } from '@vue/test-utils'
import SetupPage from '../src/pages/auth/SetupPage.vue'
import { admin } from '@/store/admin'

async function fill (w: any, code?: string) {
  const inputs = w.findAll('input.b-input__el')
  const byLabel = (label: string) => {
    const field = w.findAll('.b-field').find((f: any) => f.find('label').exists() && f.find('label').text().startsWith(label))
    return field.find('input')
  }
  if (code !== undefined) await byLabel('Setup code').setValue(code)
  await byLabel('Email').setValue('first@lab.org')
  await byLabel('Password').setValue('long-enough-1')
  await byLabel('Confirm password').setValue('long-enough-1')
  return inputs
}

describe('the first account', () => {
  beforeEach(() => {
    createFirstUser.mockClear()
    http.get.mockResolvedValue({ languages: [{ id: 1, code: 'eng', name: 'English' }] })
  })

  it('asks for no setup code on the server\'s own computer', async () => {
    ;(admin as any).setupCodeRequired = false
    const w = mount(SetupPage)
    await flushPromises()
    expect(w.text()).not.toMatch(/Setup code/)
    await fill(w)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(createFirstUser).toHaveBeenCalledWith('first@lab.org', 'long-enough-1', 1, undefined)
    w.unmount()
  })

  it('asks for the code the server printed when opened from another computer', async () => {
    ;(admin as any).setupCodeRequired = true
    const w = mount(SetupPage)
    await flushPromises()
    expect(w.text()).toMatch(/setup code the server\s+printed/)
    await fill(w, '')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(createFirstUser).not.toHaveBeenCalled()
    expect(w.text()).toMatch(/Enter the setup code/)

    await fill(w, ' ABCD-EFGH ')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(createFirstUser).toHaveBeenCalledWith('first@lab.org', 'long-enough-1', 1, 'ABCD-EFGH')
    w.unmount()
  })
})
