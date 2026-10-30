import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, Wrapper } from '@vue/test-utils'

// The seat's token, as the page keeps it (participantToken in frontend/core).
vi.mock('@human-nature-lab/breadboard-core', async (importOriginal) => {
  const actual = await importOriginal<any>()
  return { ...actual, participantToken: () => 'seat-token' }
})

import FinishProlific from '../src/components/recruitment/FinishProlific.vue'

// The Prolific finish screen's "Comments for the researcher": the comment goes to the server on its own
// (POST 'comment', next to the page's address, with the seat's token), which keeps it in the
// participant's session's data. It used to go only to the game, which nothing listened to, and the
// game is over by the time this screen shows.

const player = {
  text: '<p>Thank you!</p>',
  _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C0DE42' } },
}

let wrapper: Wrapper<any> | null = null
let fetchMock: ReturnType<typeof vi.fn>
let errors: ReturnType<typeof vi.spyOn>

/** Whether the page has gone to Prolific: jsdom can't, and says so ("Not implemented: navigation"). */
function wentToProlific (): boolean {
  return errors.mock.calls.some((args: any[]) => args.some(a => String(a && (a.message || a)).includes('navigation')))
}

async function settle () {
  for (let k = 0; k < 10; k++) await Promise.resolve()
}

beforeEach(() => {
  vi.useFakeTimers()
  fetchMock = vi.fn(() => Promise.resolve({ status: 204 }))
  ;(globalThis as any).fetch = fetchMock
  ;(globalThis as any).Breadboard = { send: vi.fn() }
  // (jsdom can't go to Prolific: it only says so)
  errors = vi.spyOn(console, 'error').mockImplementation(() => {})
})

afterEach(() => {
  wrapper?.destroy()
  wrapper = null
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('FinishProlific', () => {
  it('saves the comment on the server with the seat token, and still tells the game', async () => {
    wrapper = mount(FinishProlific, { propsData: { player } })
    await wrapper.find('textarea').setValue('  The second round was confusing.  ')
    await wrapper.find('button').trigger('click')
    await settle()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, any]
    expect(url).toBe('comment')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.headers['X-Breadboard-Token']).toBe('seat-token')
    expect(JSON.parse(init.body)).toEqual({ text: 'The second round was confusing.' })
    expect(Breadboard.send).toHaveBeenCalledWith('exiting', { feedback: '  The second round was confusing.  ' })
    expect(wrapper.find('button').text()).toBe('Returning to Prolific…')
    // then to Prolific, a second after the click as before (the game's message goes out meanwhile)
    expect(wentToProlific()).toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    expect(wentToProlific()).toBe(true)
  })

  it('sends nothing to the server without a comment', async () => {
    wrapper = mount(FinishProlific, { propsData: { player } })
    await wrapper.find('textarea').setValue('   ')
    await wrapper.find('button').trigger('click')
    await settle()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('goes back to Prolific even when the server doesn\'t answer', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}))
    wrapper = mount(FinishProlific, { propsData: { player } })
    await wrapper.find('textarea').setValue('Hello')
    await wrapper.find('button').trigger('click')
    await vi.advanceTimersByTimeAsync(2900)
    expect(wentToProlific()).toBe(false)
    await vi.advanceTimersByTimeAsync(200)
    expect(wentToProlific()).toBe(true)
  })

  it('has no comment box when the experiment asks for no feedback', () => {
    const quiet = { ...player, _system: { recruitment: { ...player._system.recruitment, noFeedback: true } } }
    wrapper = mount(FinishProlific, { propsData: { player: quiet } })
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
})
