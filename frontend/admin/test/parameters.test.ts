import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { Parameter } from '../src/store/types'
import {
  changeType, draftFrom, emptyDraft, findParameterUsage, hasErrors, nameProblem, parameterIssues, parseNumber,
  scriptLiteral, toPayload, validateParameter, type ParameterDraft,
} from '../src/pages/parameters/parameters'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('@/lib/http', () => ({
  http,
  HttpError: class HttpError extends Error {
    constructor (public status: number, message: string, public body?: any) { super(message) }
  },
}))

// the Parameters page reads the selected experiment from the store
const store = vi.hoisted(() => ({ admin: null as any, refresh: null as any }))
vi.mock('@/store/admin', async () => {
  const { reactive } = await import('vue')
  store.admin = reactive({ experiment: null as any, instance: null as any })
  store.refresh = vi.fn(() => Promise.resolve({}))
  return { admin: store.admin, actions: { refresh: store.refresh } }
})

const P = (over: Partial<Parameter>): Parameter => ({
  id: 1, name: 'nRounds', type: 'Integer', minVal: '1', maxVal: '20', defaultVal: '10', description: '', ...over,
})
const D = (over: Partial<ParameterDraft>): ParameterDraft => ({ ...emptyDraft(), ...over })

describe('parameter names', () => {
  it('accepts names scripts can use', () => {
    for (const n of ['nRounds', '_x', 'payPerPoint2', 'password']) expect(nameProblem(n)).toBeNull()
  })
  it('rejects names scripts cannot use, saying why', () => {
    expect(nameProblem('')).toMatch(/name/)
    expect(nameProblem('n rounds')).toMatch(/spaces/)
    expect(nameProblem('1st')).toMatch(/digit/)
    expect(nameProblem('n-rounds')).toMatch(/letters, digits and _/)
    expect(nameProblem('class')).toMatch(/reserved/)
    expect(nameProblem('in')).toMatch(/reserved/)
  })
  it('rejects names the engine already uses (a parameter g would replace the network)', () => {
    expect(nameProblem('g')).toMatch(/the network/)
    expect(nameProblem('initStep')).toMatch(/Breadboard/)
    expect(nameProblem('stepFactory')).toMatch(/Breadboard/)
  })
})

describe('validateParameter', () => {
  const existing = [P({ id: 1, name: 'nRounds' }), P({ id: 2, name: 'showNetwork', type: 'Boolean', minVal: '', maxVal: '', defaultVal: 'true' })]

  it('requires a unique name, ignoring capitals', () => {
    const d = D({ name: 'nrounds', type: 'Text' })
    expect(validateParameter(d, existing).name).toMatch(/nRounds/)
    expect(validateParameter(D({ name: 'nRounds', type: 'Text' }), existing).name).toMatch(/already a parameter/)
  })
  it('lets a parameter keep its own name when edited', () => {
    const e = validateParameter(draftFrom(existing[0]), existing, existing[0])
    expect(e).toEqual({})
  })
  it('asks for a type', () => {
    expect(validateParameter(D({ name: 'x' }), []).type).toBeTruthy()
  })
  it('checks whole numbers', () => {
    const e = validateParameter(D({ name: 'x', type: 'Integer', minVal: '1', maxVal: '10', defaultVal: '2.5' }), [])
    expect(e.defaultVal).toMatch(/whole number/)
    expect(validateParameter(D({ name: 'x', type: 'Integer', minVal: '1', maxVal: '99999999999', defaultVal: '2' }), []).maxVal).toMatch(/too large/)
    expect(validateParameter(D({ name: 'x', type: 'Integer', minVal: '', maxVal: '10', defaultVal: '2' }), []).minVal).toMatch(/smallest/)
  })
  it('checks min ≤ default ≤ max', () => {
    expect(validateParameter(D({ name: 'x', type: 'Integer', minVal: '10', maxVal: '1', defaultVal: '5' }), []).maxVal).toMatch(/smaller/)
    expect(validateParameter(D({ name: 'x', type: 'Integer', minVal: '1', maxVal: '10', defaultVal: '11' }), []).defaultVal).toMatch(/at most 10/)
    expect(validateParameter(D({ name: 'x', type: 'Decimal', minVal: '0.5', maxVal: '1', defaultVal: '0.25' }), []).defaultVal).toMatch(/at least 0.5/)
    expect(hasErrors(validateParameter(D({ name: 'x', type: 'Decimal', minVal: '0', maxVal: '1', defaultVal: '1' }), []))).toBe(false)
    expect(hasErrors(validateParameter(D({ name: 'x', type: 'Integer', minVal: '-5', maxVal: '-5', defaultVal: '-5' }), []))).toBe(false)
  })
  it('explains a decimal comma', () => {
    expect(validateParameter(D({ name: 'x', type: 'Decimal', minVal: '0', maxVal: '1', defaultVal: '0,5' }), []).defaultVal).toMatch(/point/)
  })
  it('wants true or false for a Boolean default', () => {
    expect(validateParameter(D({ name: 'x', type: 'Boolean', defaultVal: '' }), []).defaultVal).toBeTruthy()
    expect(validateParameter(D({ name: 'x', type: 'Boolean', defaultVal: 'false' }), [])).toEqual({})
  })
  it('keeps text within the database columns (varchar 255)', () => {
    expect(validateParameter(D({ name: 'x', type: 'Text', defaultVal: 'a'.repeat(256) }), []).defaultVal).toBeTruthy()
    expect(validateParameter(D({ name: 'x', type: 'Text', description: 'a'.repeat(256) }), []).description).toBeTruthy()
    expect(validateParameter(D({ name: 'x', type: 'Text', defaultVal: '' }), [])).toEqual({})
  })
})

describe('toPayload', () => {
  it('sends a Boolean default as the string "true" or "false" (the server read a JSON boolean as null)', () => {
    const t = toPayload(D({ name: 'show', type: 'Boolean', defaultVal: 'true' }))
    expect(t.defaultVal).toBe('true')
    expect(typeof t.defaultVal).toBe('string')
    expect(toPayload(D({ name: 'show', type: 'Boolean', defaultVal: 'false' })).defaultVal).toBe('false')
  })
  it('sends every field as a string, with no range for Boolean and Text', () => {
    const t = toPayload(D({ name: ' cond ', type: 'Text', minVal: '3', maxVal: '4', defaultVal: 'a b', description: ' d ' }))
    expect(t).toEqual({ name: 'cond', type: 'Text', minVal: '', maxVal: '', defaultVal: 'a b', description: 'd' })
    for (const v of Object.values(t)) expect(typeof v).toBe('string')
  })
  it('writes numbers the way the engine parses them', () => {
    expect(toPayload(D({ name: 'n', type: 'Integer', minVal: '+1', maxVal: '007', defaultVal: ' 5 ' }))).toMatchObject({ minVal: '1', maxVal: '7', defaultVal: '5' })
    expect(toPayload(D({ name: 'p', type: 'Decimal', minVal: '0.50', maxVal: '2', defaultVal: '.25' }))).toMatchObject({ minVal: '0.5', maxVal: '2', defaultVal: '0.25' })
  })
})

describe('helpers', () => {
  it('parses numbers strictly', () => {
    expect(parseNumber('12', 'Integer')).toBe(12)
    expect(parseNumber('1.5', 'Integer')).toBeNull()
    expect(parseNumber('2147483648', 'Integer')).toBeNull()
    expect(parseNumber('1e3', 'Decimal')).toBe(1000)
    expect(parseNumber('abc', 'Decimal')).toBeNull()
    expect(parseNumber('', 'Decimal')).toBeNull()
  })
  it('keeps what fits when the type changes', () => {
    const d = D({ name: 'x', type: 'Integer', minVal: '1', maxVal: '5', defaultVal: '3' })
    expect(changeType(d, 'Decimal')).toMatchObject({ minVal: '1', maxVal: '5', defaultVal: '3' })
    expect(changeType(d, 'Boolean')).toMatchObject({ minVal: '', maxVal: '', defaultVal: 'false' })
    expect(changeType(D({ type: 'Boolean', defaultVal: 'true' }), 'Text').defaultVal).toBe('')
  })
  it('reads an old Boolean with no default as false when editing', () => {
    expect(draftFrom(P({ type: 'Boolean', defaultVal: null as any })).defaultVal).toBe('false')
    expect(draftFrom(P({ type: 'Boolean', defaultVal: 'TRUE' })).defaultVal).toBe('true')
  })
  it('points out problems the old console let through', () => {
    const bad = P({ id: 3, name: 'n rounds', type: 'Integer', defaultVal: '30' })
    const issues = parameterIssues(bad, [bad])
    expect(issues.join(' ')).toMatch(/can’t use this name/)
    expect(issues.join(' ')).toMatch(/outside the allowed range/)
    expect(parameterIssues(P({ type: 'Boolean', defaultVal: null as any }), [])[0]).toMatch(/No default/)
    const a = P({ id: 1 })
    const b = P({ id: 2 })
    expect(parameterIssues(a, [a, b]).join(' ')).toMatch(/same name/)
    expect(parameterIssues(P({}), [])).toEqual([])
  })
  it('finds the steps that use a parameter', () => {
    const steps = [
      { name: 'InitStep', source: 'nRounds.times { }' },
      { name: 'Other', source: 'player.nRounds = 3; nRoundsMax = 2' },
      { name: 'Text', source: 'a.add(p, [name: "Round ${nRounds}"])' },
    ]
    expect(findParameterUsage('nRounds', steps)).toEqual(['InitStep', 'Text'])
    expect(findParameterUsage('n-rounds', steps)).toEqual([])
  })
  it('shows the value a script gets', () => {
    expect(scriptLiteral('Decimal', '2')).toBe('2.0')
    expect(scriptLiteral('Integer', '7')).toBe('7')
    expect(scriptLiteral('Boolean', 'true')).toBe('true')
    expect(scriptLiteral('Text', 'it\'s')).toBe('\'it\\\'s\'')
  })
})

describe('ParameterDialog', () => {
  beforeEach(() => {
    http.put.mockReset()
    http.post.mockReset()
    http.del.mockReset()
  })

  async function open (props: Record<string, unknown> = {}) {
    const { default: ParameterDialog } = await import('../src/pages/parameters/ParameterDialog.vue')
    const w = mount(ParameterDialog, {
      props: { open: true, experimentId: 12, existing: [P({ id: 1, name: 'nRounds' })], editing: null, ...props },
      attachTo: document.body,
    })
    await nextTick()
    return w
  }
  const q = <T extends Element = HTMLElement>(sel: string) => document.body.querySelector(sel) as T | null
  async function type (sel: string, value: string) {
    const el = q<HTMLInputElement>(sel)!
    el.value = value
    el.dispatchEvent(new Event('input'))
    await nextTick()
  }
  async function submit () {
    q<HTMLFormElement>('form.prmd')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()
  }

  it('creates a Boolean parameter with a string default', async () => {
    http.put.mockResolvedValue({ parameter: { id: 9, name: 'showNetwork', type: 'Boolean', minVal: '', maxVal: '', defaultVal: 'false', description: '' } })
    const w = await open()
    await type('[data-field="name"]', 'showNetwork')
    const radio = q<HTMLInputElement>('.prmd__types input[value="Boolean"]')!
    radio.checked = true
    radio.dispatchEvent(new Event('change'))
    await nextTick()
    await submit()
    expect(http.put).toHaveBeenCalledTimes(1)
    const [url, body] = http.put.mock.calls[0]
    expect(url).toBe('/parameters/12')
    expect(body).toEqual({ name: 'showNetwork', type: 'Boolean', minVal: '', maxVal: '', defaultVal: 'false', description: '' })
    expect(w.emitted('saved')![0][0]).toMatchObject({ id: 9 })
    w.unmount()
  })

  it('does not send a parameter that fails the checks, and says why', async () => {
    const w = await open()
    await type('[data-field="name"]', 'nRounds')
    await submit()
    expect(http.put).not.toHaveBeenCalled()
    expect(document.body.textContent).toMatch(/already a parameter called “nRounds”/)
    expect(document.body.textContent).toMatch(/Choose what kind of value/)
    w.unmount()
  })

  it('edits in place, keeping the name, without creating or deleting anything', async () => {
    http.post.mockResolvedValue({ parameter: P({ id: 1, defaultVal: '12' }) })
    const old = P({ id: 1 })
    const w = await open({ existing: [old], editing: old })
    await type('[data-field="defaultVal"]', '12')
    await submit()
    expect(http.post).toHaveBeenCalledTimes(1)
    const [url, body] = http.post.mock.calls[0]
    // POST /parameters/:experimentId/:parameterId, with the same body as a create (every value a string)
    expect(url).toBe('/parameters/12/1')
    expect(body).toEqual({ name: 'nRounds', type: 'Integer', minVal: '1', maxVal: '20', defaultVal: '12', description: '' })
    expect(http.put).not.toHaveBeenCalled()
    expect(http.del).not.toHaveBeenCalled()
    expect(w.emitted('saved')![0]).toEqual([expect.objectContaining({ id: 1, defaultVal: '12' })])
    expect(w.emitted('update:open')![0]).toEqual([false])
    w.unmount()
  })

  it('sends no range when an edit makes it a Text parameter', async () => {
    http.post.mockResolvedValue({ parameter: P({ id: 1, type: 'Text', minVal: '', maxVal: '', defaultVal: '10' }) })
    const old = P({ id: 1 })
    const w = await open({ existing: [old], editing: old })
    const radio = q<HTMLInputElement>('.prmd__types input[value="Text"]')!
    radio.checked = true
    radio.dispatchEvent(new Event('change'))
    await nextTick()
    await submit()
    const [url, body] = http.post.mock.calls[0]
    expect(url).toBe('/parameters/12/1')
    expect(body).toMatchObject({ type: 'Text', minVal: '', maxVal: '' })
    w.unmount()
  })

  it('keeps the dialog open when an edit is refused, and loses nothing', async () => {
    const { HttpError } = await import('@/lib/http')
    // a 400 {"message": …}, as the HTTP layer hands it on
    http.post.mockRejectedValue(new (HttpError as any)(400, 'There is already a parameter named "rounds".', { message: 'There is already a parameter named "rounds".' }))
    const old = P({ id: 1 })
    const w = await open({ existing: [old], editing: old })
    await type('[data-field="defaultVal"]', '12')
    await submit()
    expect(document.body.textContent).toMatch(/already a parameter named "rounds"/)
    expect(http.del).not.toHaveBeenCalled()
    expect(w.emitted('saved')).toBeUndefined()
    w.unmount()
  })

  it('shows a server error in words and keeps the dialog open', async () => {
    const { HttpError } = await import('@/lib/http')
    http.put.mockRejectedValue(new (HttpError as any)(400, 'Invalid experiment ID'))
    const w = await open()
    await type('[data-field="name"]', 'cond')
    const radio = q<HTMLInputElement>('.prmd__types input[value="Text"]')!
    radio.checked = true
    radio.dispatchEvent(new Event('change'))
    await nextTick()
    await submit()
    expect(document.body.textContent).toMatch(/Invalid experiment ID/)
    expect(w.emitted('saved')).toBeUndefined()
    w.unmount()
  })

  it('asks before throwing away what was typed, inside the dialog', async () => {
    const w = await open()
    const cancel = () => [...document.body.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Cancel')!
    cancel().click()
    await nextTick()
    expect(w.emitted('update:open')![0]).toEqual([false]) // nothing typed: closes at once
    await type('[data-field="name"]', 'draftName')
    cancel().click()
    await nextTick()
    expect(document.body.textContent).toMatch(/Discard what you entered\?/)
    expect(w.emitted('update:open')).toHaveLength(1)
    ;[...document.body.querySelectorAll('button')].find(b => b.textContent?.trim() === 'Discard')!.click()
    await nextTick()
    expect(w.emitted('update:open')).toHaveLength(2)
    w.unmount()
  })
})

describe('ParametersPage', () => {
  it('shows an edit at once, in the same row, until the server sends it', async () => {
    // jsdom has no scrolling; the page scrolls the saved row into view
    if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => {}
    const { default: ParametersPage } = await import('../src/pages/parameters/ParametersPage.vue')
    const { default: ParameterDialog } = await import('../src/pages/parameters/ParameterDialog.vue')
    store.admin.experiment = { id: 12, name: 'Trust game', fileMode: false, steps: [], parameters: [P({ id: 1 }), P({ id: 2, name: 'bonus', type: 'Decimal' })] }
    const w = mount(ParametersPage, { attachTo: document.body })
    await nextTick()
    const names = () => w.findAll('tbody th code').map(c => c.text())
    const firstDefault = () => w.find('tbody tr td.pp-default code').text()
    expect(names()).toEqual(['nRounds', 'bonus'])
    w.findComponent(ParameterDialog).vm.$emit('saved', P({ id: 1, defaultVal: '12' }))
    await nextTick()
    expect(names()).toEqual(['nRounds', 'bonus'])
    expect(firstDefault()).toBe('12')
    expect(store.refresh).toHaveBeenCalled()
    // the server's push shows the same change
    store.admin.experiment.parameters = [P({ id: 1, defaultVal: '12' }), P({ id: 2, name: 'bonus', type: 'Decimal' })]
    await nextTick()
    expect(names()).toEqual(['nRounds', 'bonus'])
    expect(firstDefault()).toBe('12')
    w.unmount()
  })
})
