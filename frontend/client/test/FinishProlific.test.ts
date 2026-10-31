import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, Wrapper } from '@vue/test-utils'

// The seat's token, as the page keeps it (participantToken in frontend/core).
vi.mock('@human-nature-lab/breadboard-core', async (importOriginal) => {
  const actual = await importOriginal<any>()
  return { ...actual, participantToken: () => 'seat-token' }
})

import FinishProlific from '../src/components/recruitment/FinishProlific.vue'

// The Prolific finish screen's "Comments for the researcher": the comment goes to the server on its own
// (POST 'comment', next to the page's address, with the seat's token and the comment token the screen
// asked for as it appeared: GET 'comment'), which keeps it in the participant's session's data. It used
// to go only to the game, which nothing listened to, and the game is over by the time this screen
// shows. When the server doesn't keep it, the screen says so, and goes to Prolific only when asked.

const player = {
  text: '<p>Thank you!</p>',
  _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C0DE42' } },
}

let wrapper: Wrapper<any> | null = null
let fetchMock: ReturnType<typeof vi.fn>
/** What POST 'comment' answers (a status), or 'never' */
let saved: number | 'never'
let errors: ReturnType<typeof vi.spyOn>

/** Whether the page has gone to Prolific: jsdom can't, and says so ("Not implemented: navigation"). */
function wentToProlific (): boolean {
  return errors.mock.calls.some((args: any[]) => args.some(a => String(a && (a.message || a)).includes('navigation')))
}

async function settle () {
  for (let k = 0; k < 10; k++) await Promise.resolve()
}

/** The requests the page made: [method, url, init] */
const requests = () => fetchMock.mock.calls.map(([url, init]: any[]) => [(init && init.method) || 'GET', url, init])
const posts = () => requests().filter(r => r[0] === 'POST')

beforeEach(() => {
  vi.useFakeTimers()
  saved = 204
  fetchMock = vi.fn((_url: string, init?: any) => {
    if (!init || !init.method || init.method === 'GET') {
      return Promise.resolve({ status: 200, json: () => Promise.resolve({ token: 'comment-token', expiresAt: '2026-11-07T12:00:00.000Z' }) })
    }
    return saved === 'never' ? new Promise(() => {}) : Promise.resolve({ status: saved })
  })
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
  it('asks for a comment token as it appears, and saves the comment with it and the seat token, and still tells the game', async () => {
    wrapper = mount(FinishProlific, { propsData: { player } })
    await settle()
    expect(requests().map(r => [r[0], r[1]])).toEqual([['GET', 'comment']])
    expect(requests()[0][2].headers['X-Breadboard-Token']).toBe('seat-token')
    await wrapper.find('textarea').setValue('  The second round was confusing.  ')
    await wrapper.find('button').trigger('click')
    await settle()

    expect(posts().length).toBe(1)
    const [, url, init] = posts()[0]
    expect(url).toBe('comment')
    expect(init.credentials).toBe('same-origin')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(init.headers['X-Breadboard-Token']).toBe('seat-token')
    expect(JSON.parse(init.body)).toEqual({ text: 'The second round was confusing.', token: 'comment-token' })
    expect(Breadboard.send).toHaveBeenCalledWith('exiting', { feedback: '  The second round was confusing.  ' })
    expect(wrapper.find('button').text()).toBe('Returning to Prolific…')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    // then to Prolific, a second after the click as before (the game's message goes out meanwhile)
    expect(wentToProlific()).toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    expect(wentToProlific()).toBe(true)
  })

  it('sends no comment to the server without one', async () => {
    wrapper = mount(FinishProlific, { propsData: { player } })
    await wrapper.find('textarea').setValue('   ')
    await wrapper.find('button').trigger('click')
    await settle()
    expect(posts().length).toBe(0)
    await vi.advanceTimersByTimeAsync(1000)
    expect(wentToProlific()).toBe(true)
  })

  it('says so when the comment can\'t be saved, and goes to Prolific only when asked', async () => {
    saved = 403
    wrapper = mount(FinishProlific, { propsData: { player } })
    await settle()
    await wrapper.find('textarea').setValue('Hello')
    await wrapper.find('button').trigger('click')
    await settle()
    await vi.advanceTimersByTimeAsync(60000)
    expect(wentToProlific()).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('Your comment couldn’t be saved, so the researcher won’t get it this way.')
    expect(wrapper.find('textarea').element.value).toBe('Hello')
    const buttons = wrapper.findAll('button')
    expect(buttons.at(0).text()).toBe('Try again')
    expect(buttons.at(1).text()).toBe('Return to Prolific without it')
    // tried again: saved this time, then to Prolific; the game was told once
    saved = 204
    await buttons.at(0).trigger('click')
    await settle()
    expect(posts().length).toBe(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(1000)
    expect(wentToProlific()).toBe(true)
    expect(Breadboard.send).toHaveBeenCalledTimes(1)
  })

  it('says so when the server doesn\'t answer, and goes to Prolific without the comment when asked', async () => {
    saved = 'never'
    wrapper = mount(FinishProlific, { propsData: { player } })
    await settle()
    await wrapper.find('textarea').setValue('Hello')
    await wrapper.find('button').trigger('click')
    await settle()
    expect(wrapper.find('button').text()).toBe('Saving your comment…')
    await vi.advanceTimersByTimeAsync(9900)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(200)
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wentToProlific()).toBe(false)
    await wrapper.findAll('button').at(1).trigger('click')
    await vi.advanceTimersByTimeAsync(1000)
    expect(wentToProlific()).toBe(true)
  })

  it('asks for the comment token again as it sends the comment, when it has none yet', async () => {
    let first = true
    const answers = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((url: string, init?: any) => {
      if (first && (!init || !init.method)) {
        first = false
        return Promise.reject(new Error('offline'))
      }
      return answers(url, init)
    })
    wrapper = mount(FinishProlific, { propsData: { player } })
    await settle()
    await wrapper.find('textarea').setValue('Hello')
    await wrapper.find('button').trigger('click')
    await settle()
    expect(requests().map(r => [r[0], r[1]])).toEqual([['GET', 'comment'], ['GET', 'comment'], ['POST', 'comment']])
    expect(JSON.parse(posts()[0][2].body)).toEqual({ text: 'Hello', token: 'comment-token' })
  })

  it('asks for a new comment token every hour, and keeps the one it has when the server gives none', async () => {
    // (the server gives one for as long as it knows the seat, which it forgets once the session has ended)
    let given = 0
    const answers = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((url: string, init?: any) => {
      if (!init || !init.method) {
        given++
        if (given === 3) return Promise.resolve({ status: 403, json: () => Promise.resolve({ error: 'forgotten' }) })
        return Promise.resolve({ status: 200, json: () => Promise.resolve({ token: 'comment-token-' + given, expiresAt: '2026-11-07T12:00:00.000Z' }) })
      }
      return answers(url, init)
    })
    wrapper = mount(FinishProlific, { propsData: { player } })
    await settle()
    expect(given).toBe(1)
    await vi.advanceTimersByTimeAsync(59 * 60 * 1000)
    expect(given).toBe(1)
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(given).toBe(2)
    // the seat is forgotten by the next hour: the token it has stays
    await vi.advanceTimersByTimeAsync(60 * 60 * 1000)
    expect(given).toBe(3)
    await wrapper.find('textarea').setValue('Late, but kept')
    await wrapper.find('button').trigger('click')
    await settle()
    expect(JSON.parse(posts()[0][2].body)).toEqual({ text: 'Late, but kept', token: 'comment-token-2' })
    // and it stops asking once the screen has gone
    wrapper.destroy()
    wrapper = null
    await vi.advanceTimersByTimeAsync(3 * 60 * 60 * 1000)
    expect(given).toBe(3)
  })

  it('has no comment box, and asks for no token, when the experiment asks for no feedback', async () => {
    const quiet = { ...player, _system: { recruitment: { ...player._system.recruitment, noFeedback: true } } }
    wrapper = mount(FinishProlific, { propsData: { player: quiet } })
    await settle()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
