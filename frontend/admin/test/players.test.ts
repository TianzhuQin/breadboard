import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { actions, connection, graph, __receive } from '../src/store/admin'
import { toasts } from '../src/ui/toast'
import { confirmState, settleConfirm } from '../src/ui/confirm'
import { pendingEdits, editHistory } from '../src/network/tieEdits'
import { sortNodes, quickMatch, toCsv, csvCell, csvKeys } from '../src/pages/players/table'
import { submitChoice, shortError } from '../src/pages/players/choices'
import PlayerDetails from '../src/pages/players/PlayerDetails.vue'
import PropertyRow from '../src/pages/players/PropertyRow.vue'
import TiePicker from '../src/pages/players/TiePicker.vue'
import type { GraphNode } from '../src/lib/graph'

const tick = () => new Promise(r => setTimeout(r, 30))
const node = (id: string, data: Record<string, any> = {}): GraphNode => ({ id, data })
const exp = { id: 1, name: 'E', uid: 'u', fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '.x{color:red}', clientGraphHash: 0, clientHtmlHash: 0 }

/** `engine`: what the server says the shared engine runs (none when left out). */
function session (status = 'RUNNING', engine: any = null) {
  __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 9, name: 's', status }, engine })
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

  it('writes CSV with quotes, commas and line breaks escaped, and no byte order mark (like the server\'s data CSV)', () => {
    const csv = toCsv([node('p1', { note: 'say "hi", then\nleave', obj: { a: 1 } })], ['note', 'obj'], () => 2)
    expect(csv).toBe('id,status,ties,choices waiting,note,obj\r\np1,,2,0,"say ""hi"", then\nleave","{""a"":1}"\r\n')
    expect(csv.charCodeAt(0)).not.toBe(0xFEFF)
  })

  it('writes values exactly as they are, like the session\'s event CSV, so the files join on id', () => {
    expect(csvCell('-k7')).toBe('-k7')
    expect(csvCell('@maria')).toBe('@maria')
    expect(csvCell('+1 555 0100')).toBe('+1 555 0100')
    expect(csvCell('=1+1')).toBe('=1+1')
    expect(csvCell('-')).toBe('-')
    expect(csvCell(-2)).toBe('-2')
    expect(csvCell('=HYPERLINK("http://x","click")')).toBe('"=HYPERLINK(""http://x"",""click"")"')
    // leading or trailing white space is kept by quoting the field
    expect(csvCell('\tlead')).toBe('"\tlead"')
    const csv = toCsv([node('-k7', { '@k': '-x' })], ['@k'], () => 0)
    expect(csv.split('\r\n')[0]).toBe('id,status,ties,choices waiting,@k')
    expect(csv.split('\r\n')[1]).toBe('-k7,,0,0,-x')
  })

  it('puts every property in the CSV, maps and lists too, with the engine group and Prolific ids', () => {
    const nodes = [
      node('p1', { answers: { q1: 'yes' }, x: 3, score: 1, text: '<p>big</p>', choices: [], timers: {}, _system: { connected: true, groupId: 2, recruitment: { source: 'prolific', prolificPid: 'PID1', studyId: 'S', sessionId: 'X' } } }),
      node('p2', { tags: ['a', 'b'], _system: { connected: false } }),
    ]
    const keys = csvKeys(nodes)
    expect(keys).toEqual(['answers', 'score', 'tags', 'x', '_system.groupId', '_system.recruitment.source', '_system.recruitment.prolificPid', '_system.recruitment.studyId', '_system.recruitment.sessionId'])
    const rows = toCsv(nodes, keys, () => 0).split('\r\n')
    expect(rows[1]).toBe('p1,Connected,0,0,"{""q1"":""yes""}",1,,3,2,prolific,PID1,S,X')
    expect(rows[2]).toBe('p2,Disconnected,0,0,,,"[""a"",""b""]",,,,,,')
    expect(csvKeys([node('p1', { score: 1 })])).toEqual(['score'])
  })
})

// ---- choosing for a player ---------------------------------------------------------------------

/** The engine making the choice: it leaves their screen, then the server answers the console. */
function chooseOnServer () {
  setTimeout(() => {
    __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' })
    __receive({ request: 'MakeChoice', choice: 'made' })
  }, 5)
  return true
}

describe('choosing for a player', () => {
  beforeEach(() => { vi.restoreAllMocks(); seed() })

  it('succeeds when the server answers and the choice leaves their screen', async () => {
    const spy = vi.spyOn(actions, 'makeChoice').mockImplementation(chooseOnServer)
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    expect(spy).toHaveBeenCalledWith('c1')
  })

  it('sends nothing when the choice has already left their screen', async () => {
    const spy = vi.spyOn(actions, 'makeChoice')
    expect(await submitChoice('p1', 'gone-uid', 2000)).toEqual({ ok: false, gone: true, sent: false })
    __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, gone: true, sent: false })
    expect(spy).not.toHaveBeenCalled()
  })

  it('takes a tagged answer from the server as final', async () => {
    let reply: any = { request: 'MakeChoice', choice: 'made' }
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => { setTimeout(() => __receive(reply), 5); return true })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    // the participant answered first: the server says so, even though the console still lists it
    reply = { request: 'MakeChoice', choice: 'gone' }
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, gone: true, sent: true })
    reply = { request: 'MakeChoice', error: 'Caught error: java.lang.IllegalStateException: no such round' }
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, error: reply.error })
  })

  it('ignores untagged engine errors and other requests\' answers while it waits', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      // a step file saved in file mode, or a timer, fails while the game is busy with the choice
      setTimeout(() => __receive({ error: 'Caught error: java.lang.IllegalStateException: no such round\n at x.groovy:3' }), 2)
      setTimeout(() => __receive({ request: 'SendScript', error: 'Boom' }), 4)
      setTimeout(() => __receive({ request: 'MakeChoice', choice: 'made' }), 60)
      return true
    })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    expect(shortError('Caught error: java.lang.IllegalStateException: no such round\n at x.groovy:3')).toBe('no such round')
  })

  it('does not take the choice leaving their screen for its own success', async () => {
    // the participant answered it themselves while the console's request waited, and no answer came
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' }), 2)
      return true
    })
    expect(await submitChoice('p1', 'c1', 80)).toEqual({ ok: false, timeout: true, onScreen: false })
  })

  it('does not take the answer about another choice for its own, when the server names the choice', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ request: 'MakeChoice', choice: 'gone', choiceUID: 'other' }), 2)
      setTimeout(() => __receive({ request: 'MakeChoice', choice: 'made', choiceUID: 'c1' }), 20)
      return true
    })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
  })

  it('takes the answers of a server that names the choice on every reply, and of one that names none', async () => {
    let replies: any[] = []
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      replies.forEach((m, i) => setTimeout(() => __receive(m), 2 + i * 5))
      return true
    })
    replies = [{ request: 'MakeChoice', choice: 'made', choiceUID: 'c1' }]
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    replies = [{ request: 'MakeChoice', choice: 'gone', choiceUID: 'c1' }]
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, gone: true, sent: true })
    // the error names the choice too; one about another choice is not this one's
    replies = [
      { request: 'MakeChoice', error: 'Caught error: not this one', choiceUID: 'c2' },
      { request: 'MakeChoice', error: 'Caught error: no such round', choiceUID: 'c1' },
    ]
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, error: 'Caught error: no such round' })
    // an older server's answers, or a refusal made before the request was read, name none
    replies = [{ request: 'MakeChoice', choice: 'made', choiceUID: null }]
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
    replies = [{ request: 'MakeChoice', error: 'You are not signed in (or your sign-in has expired). Sign in again.' }]
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, error: replies[0].error })
  })

  it('says so at once when there is no connection', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => false)
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: false, offline: true })
  })

  it('never takes an error from OnJoinStep or OnLeaveStep (tagged with its source) for the choice\'s', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ source: 'OnJoinStep', error: 'Caught error: onJoinStep failed for p7' }), 2)
      setTimeout(() => __receive({ request: 'MakeChoice', choice: 'made' }), 30)
      return true
    })
    expect(await submitChoice('p1', 'c1', 2000)).toEqual({ ok: true })
  })

  it('says so when nothing happens', async () => {
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => true)
    expect(await submitChoice('p1', 'c1', 60)).toEqual({ ok: false, timeout: true, onScreen: true })
  })
})

// ---- the details panel --------------------------------------------------------------------------

describe('PlayerDetails', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    connection.state.value = 'open'
    session()
    seed()
    toasts.splice(0, toasts.length)
    pendingEdits.value = []
    editHistory.value = []
    await tick()
  })
  afterEach(() => { connection.state.value = 'idle' })

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
    const spy = vi.spyOn(actions, 'makeChoice').mockImplementation(chooseOnServer)
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

  it('sends nothing when the choice went while the confirmation was open', async () => {
    const spy = vi.spyOn(actions, 'makeChoice')
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    await w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!.trigger('click')
    // the participant answers (or the round times out) while the researcher reads the dialog
    __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' })
    settleConfirm(true)
    await vi.waitFor(() => expect(toasts.some(t => t.title === 'Nothing chosen for p1')).toBe(true))
    expect(toasts.find(t => t.title === 'Nothing chosen for p1')!.detail).toMatch(/no longer on their screen.*Nothing was sent/)
    expect(toasts.some(t => /^Chose/.test(t.title))).toBe(false)
    expect(spy).not.toHaveBeenCalled()
    w.unmount()
  })

  it('never says it chose when the server did not answer, though the choice left their screen', async () => {
    // the participant answered it themselves while the console's request waited behind a long step
    vi.spyOn(actions, 'makeChoice').mockImplementation(() => {
      setTimeout(() => __receive({ action: 'nodePropertyRemoved', id: 'p1', key: 'choices' }), 5)
      return true
    })
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    await w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!.trigger('click')
    vi.useFakeTimers()
    try {
      settleConfirm(true)
      await vi.advanceTimersByTimeAsync(6100)
    } finally {
      vi.useRealTimers()
    }
    await vi.waitFor(() => expect(toasts.some(t => t.title === 'No answer from the server yet')).toBe(true))
    expect(toasts.find(t => t.title === 'No answer from the server yet')!.detail).toMatch(/no longer on p1's screen, but the server hasn't said whether yours was made/)
    expect(toasts.some(t => /^Chose/.test(t.title))).toBe(false)
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

  it('offers Cut only for ties, and shows each tie\'s properties on request', async () => {
    __receive({ action: 'addLink', id: 'e2', source: 'p1', target: 'p3', value: 'partner' })
    __receive({ action: 'linkPropertyChanged', id: 'e1', key: 'weight', value: '3' })
    __receive({ action: 'linkPropertyChanged', id: 'e1', key: 'inProps', value: '{}' })
    await tick()
    const w = mount(PlayerDetails, { props: { id: 'p1' }, attachTo: document.body })
    const rows = w.findAll('.pd__tie')
    expect(rows.map(t => t.find('.pd__tie-name').text())).toEqual(['p2', 'p3'])
    // p3 is a "partner": shown with its kind, no Cut (EditTie only cuts "connected" ties)
    expect(rows[1].find('.pd__tie-kind').text()).toBe('partner')
    expect(w.find('[aria-label="Cut the tie between p1 and p3"]').exists()).toBe(false)
    expect(w.find('[aria-label="Cut the tie between p1 and p2"]').exists()).toBe(true)
    // ...but they can still be tied
    const input = w.find('input[role="combobox"]')
    await input.setValue('p3')
    expect(w.findAll('[role="option"]').map(o => o.text())).toEqual(['p3'])
    // the tie's own properties, without the engine's empty private maps
    const more = w.find('[aria-label="Show the properties of the tie with p2"]')
    expect(more.text()).toBe('1')
    await more.trigger('click')
    expect(w.find('.pd__tie-props').text()).toContain('weight')
    expect(w.find('.pd__tie-props').text()).toContain('3')
    expect(w.find('.pd__tie-props').text()).not.toContain('inProps')
    w.unmount()
  })

  it('locks tie changes while the session isn\'t running', async () => {
    session('STOPPED')
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    expect(w.find('[aria-label="Cut the tie between p1 and p2"]').attributes('disabled')).toBeDefined()
    expect(w.find('input[role="combobox"]').attributes('disabled')).toBeDefined()
    w.unmount()
  })

  it('does not ask to choose while offline, and says so when the connection drops meanwhile', async () => {
    const spy = vi.spyOn(actions, 'makeChoice').mockImplementation(() => false)
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    const coop = () => w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!
    connection.state.value = 'retrying'
    await coop().trigger('click')
    expect(confirmState.open).toBe(false)
    expect(toasts[toasts.length - 1].title).toBe('Not connected to the server')
    expect(spy).not.toHaveBeenCalled()

    connection.state.value = 'open'
    await coop().trigger('click')
    settleConfirm(true)
    await vi.waitFor(() => expect(toasts[toasts.length - 1].detail).toMatch(/Nothing was sent, so “Cooperate” is still waiting on p1's screen/))
    expect(toasts.some(t => /failed/.test(t.title))).toBe(false)
    w.unmount()
  })

  it('locks ties and choices while the engine runs another experiment, and says why', async () => {
    const tieSpy = vi.spyOn(actions, 'editTie')
    session('RUNNING', { experimentId: 2, experimentName: 'Other study', sessionId: 4, sessionName: 'S', owner: 'bob@lab.org' })
    const w = mount(PlayerDetails, { props: { id: 'p1' } })
    const cut = w.find('[aria-label="Cut the tie between p1 and p2"]')
    expect(cut.attributes('disabled')).toBeDefined()
    expect(cut.attributes('title')).toBe('The engine is running “Other study” right now, so ties can\'t be changed here')
    const picker = w.find('input[role="combobox"]')
    expect(picker.attributes('disabled')).toBeDefined()
    expect(picker.attributes('placeholder')).toMatch(/running “Other study”/)
    const coop = w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!
    expect(coop.attributes('disabled')).toBeDefined()
    expect(w.find('.pd__note').text()).toMatch(/running “Other study” right now, so choices can't be made from here/)
    await cut.trigger('click')
    expect(tieSpy).not.toHaveBeenCalled()
    // the engine runs this experiment again
    session('RUNNING', { experimentId: 1, experimentName: 'E', sessionId: 9, sessionName: 's', owner: 'r' })
    await tick()
    expect(w.find('[aria-label="Cut the tie between p1 and p2"]').attributes('disabled')).toBeUndefined()
    expect(w.findAll('.pd__choice-list button').find(b => b.text() === 'Cooperate')!.attributes('disabled')).toBeUndefined()
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
