import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { aiName, displayName, playerName } from '../src/network/players'
import { matchesPlayer } from '../src/pages/preview/preview'
import { graph, __receive } from '../src/store/admin'
import { LiveGraph, type GraphNode } from '../src/lib/graph'
import PlayerPicker from '../src/pages/preview/PlayerPicker.vue'
import PreviewTile from '../src/pages/preview/PreviewTile.vue'
import PlayerDetails from '../src/pages/players/PlayerDetails.vue'

/**
 * A computer player has one name in the console: g.addAI's `_6` is "AI 6 (_6)" on the network, in
 * the Players table and on the Preview page, and "AI 6" beside its id in the details panel. (Each
 * participant's page gives the people around them its own letters, so the console has none to show.)
 */

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }), useRoute: () => ({ query: {} }) }))

const node = (id: string, data: Record<string, any> = {}): GraphNode => ({ id, data })
const tick = () => new Promise(r => setTimeout(r, 40))
const exp = { id: 1, name: 'E', uid: 'u', fileMode: false, steps: [], languages: [], content: [], parameters: [], instances: [], images: [], style: '', clientGraphHash: 0, clientHtmlHash: 0 }

describe('what the console calls a computer player', () => {
  it('names one that g.addAI added "AI 6 (_6)", and anyone else by their id', () => {
    expect(aiName('_6')).toBe('AI 6')
    expect(playerName('_6')).toBe('AI 6 (_6)')
    expect(playerName('p1')).toBe('p1')
    // other ids that start with "_" are not g.addAI's, and a person the computer plays for keeps their id
    expect(playerName('_bot')).toBe('_bot')
    expect(aiName('p1')).toBeNull()
    // a property chosen to name people wins where they have it
    expect(displayName(node('_6'), 'name')).toBe('AI 6 (_6)')
    expect(displayName(node('_6', { name: 'Robo' }), 'name')).toBe('Robo')
    expect(displayName(node('p1', { name: 'Ann' }), 'name')).toBe('Ann')
    expect(displayName(node('p1'), null)).toBe('p1')
  })

  it('finds them on the Preview page by the name the list shows', () => {
    expect(matchesPlayer(node('_6'), 'AI 6')).toBe(true)
    expect(matchesPlayer(node('_6'), '_6')).toBe(true)
    expect(matchesPlayer(node('_16'), 'ai 6')).toBe(false)
    expect(matchesPlayer(node('p1'), 'AI 6')).toBe(false)
  })

  it('shows that name in the Preview picker and on the preview tile', () => {
    const picker = mount(PlayerPicker, { props: { players: [node('p1'), node('_6')], selected: new Set<string>(), max: 12, listKey: 0 } })
    expect(picker.findAll('.pp__id').map(s => s.text())).toEqual(['p1', 'AI 6 (_6)'])
    expect(picker.findAll('.pp__id').map(s => s.attributes('title'))).toEqual(['p1', '_6'])
    picker.unmount()

    const tile = mount(PreviewTile, {
      props: { playerId: '_6', target: null, device: 'desktop', width: 320, interactive: false, test: false, ai: true, frameKey: 'k', paused: 'focus' },
    })
    expect(tile.find('.pt__id').text()).toBe('AI 6 (_6)')
    expect(tile.attributes('aria-label')).toBe('Preview of AI 6 (_6)')
    tile.unmount()
  })
})

describe('computer players in the Players and Network views', () => {
  beforeEach(async () => {
    __receive({ user: { email: 'r', experiments: [exp], currentScript: '' }, experiment: exp, experimentInstance: { id: 9, name: 's', status: 'RUNNING' }, engine: null })
    graph.clear()
    for (const id of ['p1', 'p2', '_1', '_6']) __receive({ action: 'addNode', id })
    __receive({ action: 'nodePropertyChanged', id: '_6', key: 'ai', value: 1 })
    for (const [i, [a, b]] of [['_6', 'p2'], ['_6', '_1'], ['_6', 'p1']].entries()) {
      __receive({ action: 'addLink', id: `e${i}`, source: a, target: b, value: 'connected' })
    }
    await tick()
  })

  it('lists them by name in the Players table', async () => {
    const PlayersPage = (await import('../src/pages/players/PlayersPage.vue')).default
    const w = mount(PlayersPage, { attachTo: document.body })
    await tick()
    await flushPromises()
    expect(w.findAll('.plist__id-text').map(s => s.text())).toEqual(['p1', 'p2', 'AI 1 (_1)', 'AI 6 (_6)'])
    w.unmount()
  })

  it('names them in the details panel beside their id, after the people they are tied to', async () => {
    const w = mount(PlayerDetails, { props: { id: '_6' }, attachTo: document.body })
    expect(w.find('h2').text()).toBe('AI 6')
    expect(w.find('.pd__id').text()).toBe('_6')
    expect(w.findAll('.pd__tie-name').map(b => b.text())).toEqual(['p1', 'p2', 'AI 1_1'])
    w.unmount()
  })

  it('says their name on the network', async () => {
    // jsdom has no canvas; the view still works without drawing
    HTMLCanvasElement.prototype.getContext = (() => null) as any
    const AdminNetwork = (await import('../src/network/AdminNetwork.vue')).default
    const g = new LiveGraph()
    g.apply({ action: 'addNode', id: '_6' })
    const w = mount(AdminNetwork, { props: { graph: g }, attachTo: document.body })
    await tick()
    await w.trigger('keydown', { key: 'ArrowRight' })
    expect(w.find('[aria-live="polite"]').text()).toMatch(/^AI 6 \(_6\)\. 0 ties\./)
    w.unmount()
  })
})
