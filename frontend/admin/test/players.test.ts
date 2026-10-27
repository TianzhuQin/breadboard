import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { actions, graph, __receive } from '../src/store/admin'
import { toasts } from '../src/ui/toast'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { pendingEdits, editHistory } from '../src/network/tieEdits'
import { sortNodes, quickMatch, toCsv } from '../src/pages/players/table'
import { submitChoice, shortError } from '../src/pages/players/choices'
import PlayerDetails from '../src/pages/players/PlayerDetails.vue'
import PropertyRow from '../src/pages/players/PropertyRow.vue'
import TiePicker from '../src/pages/players/TiePicker.vue'
import type { GraphNode } from '../src/lib/graph'

const tick = () => new Promise(r => setTimeout(r, 30))
const node = (id: string, data: Record<string, any> = {}): GraphNode => ({ id, data })
const exp = { id: 1, name: 'E', uid: 'u', fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '.x{color:red}', clientGraphHash: 0, clientHtmlHash: 0 }

function session (status = 'RUNNING') {
  __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 9, name: 's', status } })
}
function seed () {
  graph.clear()
  for (const id of ['p1', 'p2', 'p3', '_1']) __receive({ action: 'addNode', id })
  const set = (id: string, key: string, value: any) => __receive({ action: 'nodePropertyChanged', id, key, value })
  set('p1', 'text', '<p>Hello <b>p1</b></p><script>window.__ran = true</script>')
  set('p1', 'choices', [{ uid: 'c1', name: 'Cooperate' }, { uid: 'c2', name: 'Group pick', _route: 'group' }])
  set('p1', '_system', { connected: true, groupId: 2, recruitment: { source: 'prolific', prolificPid: '5f3c9a1b2d4e6f7a', studyId: 's', sessionId: 'x' } })
  set('p1', 'score', 7)
  set('p1', 'name', 'Ann')
  set('p2', '_system', { connected: false })
  set('_1', 'ai', 1)
  __receive({ action: 'addLink', id: 'e1', source: 'p1', target: 'p2', value: 'connected' })
}

// ---- table --------------------------------------------------------------------------------------

describe('players table', () => {
  const people = [
    node('p2', { score: 3 }),
    node('p10', { score: 12 }),
    node('_1', { ai: 1 }),
    node('p1', { score: '5', choices: [{ uid: 'u', name: 'x' }], _system: { connected: false } }),
  ]
  const degree = (id: string) => ({ p1: 3, p2: 1, p10: 2 } as Record<string, number>)[id] || 0

  it('sorts by any column, both ways, with people lacking a value last', () => {
    expect(sortNodes(people, { key: 'id', dir: 1 }, degree).map(n => n.id)).toEqual(['p1', 'p2', 'p10', '_1'])
    expect(sortNodes(people, { key: 'score', dir: 1 }, degree).map(n => n.id)).toEqual(['p2', 'p1', 'p10', '_1'])
    expect(sortNodes(people, { key: 'score', dir: -1 }, degree).map(n => n.id)).toEqual(['p10', 'p1', 'p2', '_1'])
    expect(sortNodes(people, { key: '@ties', dir: -1 }, degree).map(n => n.id)).toEqual(['p1', 'p10', 'p2', '_1'])
    expect(sortNodes(people, { key: '@choice', dir: 1 }, degree)[0].id).toBe('p1')
  })

  it('has quick filters for people, AI, disconnected and waiting', () => {
    expect(people.filter(n => quickMatch(n, 'ai')).map(n => n.id)).toEqual(['_1'])
    expect(people.filter(n => quickMatch(n, 'people')).length).toBe(3)
    expect(people.filter(n => quickMatch(n, 'disconnected')).map(n => n.id)).toEqual(['p1'])
    expect(people.filter(n => quickMatch(n, 'choice')).map(n => n.id)).toEqual(['p1'])
  })

  it('writes CSV with quotes, commas and line breaks escaped', () => {
    const csv = toCsv([node('p1', { note: 'say "hi", then\nleave', obj: { a: 1 } })], ['note', 'obj'], () => 2)
    expect(csv).toBe('id,status,ties,choices waiting,note,obj\r\np1,,2,0,"say ""hi"", then\nleave","{""a"":1}"\r\n')
  })
})

// ---- choosing for a player ---------------------------------------------------------------------

describe('choosing for a player', () => {
  beforeEach(() => { vi.restoreAllMocks(); seed() })

  it('succeeds when the choice leaves their screen', async () => {
    const spy = vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' }), 5)
    })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    expect(spy).toHaveBeenCalledWith('c1')
  })

  it('reports the engine\'s error', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ error: 'Caught error: java.lang.IllegalStateException: no such round\n at x.groovy:3' }), 5)
    })
    const r = await submitChoice('p1', 'c1', 2000)
    expect(r).toMatchObject({ ok: false })
    expect(shortError((r as any).error)).toBe('no such round')
  })

  it('says so when nothing happens (an expired choice is a silent no-op on the server)', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {})
    expect(await submitChoice('p1', 'c1', 60)).toEqual({ ok: false, timeout: true })
  })
})

// ---- the details panel --------------------------------------------------------------------------

describe('PlayerDetails', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    session()
    seed()
    toasts.splice(0, toasts.length)
    pendingEdits.value = []
    editHistory.value = []
    await tick()
  })

  it('shows who they are, live badges, and their screen in a sandbox', async () => {
    const w = mount(PlayerDetails, { props: { id: 'p1', labelBy: 'name' }, attachTo: document.body })
    expect(w.find('h2').text()).toBe('Ann')
    const badges = w.find('.pd__badges').text()
    expect(badges).toContain('Connected')
    expect(badges).toContain('Has a choice to make')
    expect(badges).toContain('Group 2')
    expect(badges).toContain('Prolific · 5f3c9a1b…')
    const frame = w.find('iframe')
    expect(frame.attributes('sandbox')).toBe('')
    expect(frame.attributes('srcdoc')).toContain('<p>Hello <b>p1</b></p>')
    // the experiment's style goes into the frame only
    expect(frame.attributes('srcdoc')).toContain('.x{color:red}')
    // nothing from the participant's page runs in the console
    expect(w.findAll('script').length).toBe(0)
    expect((window as any).__ran).toBeUndefined()
    // properties update while you watch
    __receive({ action: 'nodePropertyChanged', id: 'p1', key: 'mood', value: 'happy' })
    await tick()
    expect(w.find('.pd__props').text()).toContain('mood')
    w.unmount()
  })

  it('asks before choosing for them, then says it worked', async () => {
    const spy = vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' }), 5)
    })
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    const coop = w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!
    await coop.trigger('click')
    expect(confirmState.open).toBe(true)
    expect(confirmState.req!.title).toBe('Choose “Cooperate” for p1?')
    expect(spy).not.toHaveBeenCalled()
    settleConfirm(true)
    await vi.waitFor(() => expect(toasts.some(t => t.title === 'Chose “Cooperate” for p1')).toBe(true))
    expect(spy).toHaveBeenCalledWith('c1')
    w.unmount()
  })

  it('does nothing when the confirmation is cancelled, and blocks group choices', async () => {
    const spy = vi.spyOn(actions, 'makeChoice')
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    const buttons = w.findAll('.pd__choice-list button')
    expect(buttons.find(b => b.text() === 'Group pick')!.attributes('disabled')).toBeDefined()
    await buttons.find(b => b.text() === 'Cooperate')!.trigger('click')
    settleConfirm(false)
    await flushPromises()
    expect(spy).not.toHaveBeenCalled()
    w.unmount()
  })

  it('cuts a tie and ties to someone new', async () => {
    const spy = vi.spyOn(actions, 'editTie').mockImplementation(async (op, a, b) => ({ ok: true, op, a, b }))
    const w = mount(PlayerDetails, { props: { id: 'p1' }, attachTo: document.body })
    expect(w.findAll('.pd__tie').map(t => t.find('.pd__tie-name').text())).toEqual(['p2'])
    await w.find('[aria-label="Cut the tie between p1 and p2"]').trigger('click')
    expect(spy).toHaveBeenCalledWith('remove', 'p1', 'p2')
    await flushPromises()
    const input = w.find('input[role="combobox"]')
    await input.setValue('p3')
    await input.trigger('keydown', { key: 'Enter' })
    expect(spy).toHaveBeenLastCalledWith('add', 'p1', 'p3')
    w.unmount()
  })

  it('locks tie changes while the session isn\'t running', async () => {
    session('STOPPED')
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    expect(w.find('[aria-label="Cut the tie between p1 and p2"]').attributes('disabled')).toBeDefined()
    expect(w.find('input[role="combobox"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('says so when the player is gone', () => {
    const w = mount(PlayerDetails, { props: { id: 'nobody' } })
    expect(w.text()).toContain('Not in this session')
    w.unmount()
  })
})

describe('PropertyRow and TiePicker', () => {
  it('shortens long values with a way to show them all', async () => {
    const long = 'x'.repeat(400)
    const w = mount(PropertyRow, { props: { name: 'note', value: long, path: 'note' } })
    expect(w.find('.prow__text').text().length).toBeLessThan(200)
    const more = w.find('.prow__more')
    expect(more.text()).toBe('Show all (400 characters)')
    await more.trigger('click')
    expect(w.find('.prow__text').text()).toBe(long)
  })

  it('folds nested values and opens them on request', async () => {
    const w = mount(PropertyRow, { props: { name: 'bag', value: { a: 1, b: [1, 2], c: 3, d: 4, e: 5 }, path: 'bag' } })
    expect(w.find('.prow__children').exists()).toBe(false)
    expect(w.find('.prow__preview').text()).toContain('"a":1')
    await w.find('.prow__toggle').trigger('click')
    expect(w.findAll('.prow__children > .prow').length).toBe(5)
  })

  it('picks with the keyboard and filters by name or id', async () => {
    const w = mount(TiePicker, { props: { candidates: [{ id: 'p1', label: 'Ann' }, { id: 'p2', label: 'Bob' }, { id: 'p3', label: 'Cy' }] } })
    const input = w.find('input')
    await input.trigger('focus')
    expect(w.findAll('[role="option"]').length).toBe(3)
    await input.setValue('bo')
    expect(w.findAll('[role="option"]').map(o => o.text())).toEqual(['Bobp2'])
    await input.trigger('keydown', { key: 'Enter' })
    expect(w.emitted('pick')![0]).toEqual(['p2'])
    await input.setValue('')
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toMatch(/-1$/)
  })
})
