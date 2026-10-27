import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { requestNotificationPermission, useNotificationPermission } from '../src/lib/notify'

// A preview from the researcher console runs in an opaque origin (a sandboxed iframe without
// allow-same-origin), where browsers refuse the notification permission outright and some
// reject the calls that ask about it.

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete (navigator as any).permissions
})

describe('notifications in a sandboxed page', () => {
  it('answers "not allowed" when asking for permission is refused', async () => {
    vi.spyOn(console, 'log').mockImplementation(() => {})
    vi.stubGlobal('Notification', {
      permission: 'default',
      requestPermission: vi.fn(() => Promise.reject(new DOMException('sandboxed', 'SecurityError'))),
    })
    await expect(requestNotificationPermission()).resolves.toBe(false)
  })

  it('stays at "prompt" when the permission can\'t be looked up', async () => {
    vi.spyOn(console, 'log').mockImplementation(() => {})
    Object.defineProperty(navigator, 'permissions', {
      configurable: true,
      value: { query: vi.fn(() => Promise.reject(new DOMException('sandboxed', 'SecurityError'))) },
    })
    const unhandled: unknown[] = []
    const onUnhandled = (reason: unknown) => { unhandled.push(reason) }
    process.on('unhandledRejection', onUnhandled)
    try {
      let state: any
      const Probe = defineComponent({
        setup () {
          state = useNotificationPermission()
          return {}
        },
        render: (h: any) => h('div'),
      })
      const w = mount(Probe)
      await new Promise(resolve => setTimeout(resolve, 0))
      expect(state.value).toBe('prompt')
      expect(unhandled).toEqual([])
      w.destroy()
    } finally {
      process.off('unhandledRejection', onUnhandled)
    }
  })
})
