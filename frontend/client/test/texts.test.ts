import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { LANGUAGE_EVENT } from '@human-nature-lab/breadboard-core'
import { t, tParts, sentences, setTextsLanguage, textsLanguage, currentTextsLanguage } from '../src/lib/texts'
import BBWaiting from '../src/components/BBWaiting.vue'
import BBGame from '../src/components/BBGame.vue'
import BBNetwork from '../src/components/BBNetwork.vue'
import ConnectionStatus from '../src/components/ConnectionStatus.vue'
import LinkNote from '../src/components/LinkNote.vue'
import FinishProlific from '../src/components/recruitment/FinishProlific.vue'
import Tutorial from '../src/components/tutorial/Tutorial.vue'
import { isRequired, minText } from '../src/components/form/blocks/rules'

// Breadboard's own words on the participant page follow the language of the session's texts, which the
// steps choose with c.setDefaultLanguage: Chinese for zho, English for any other. A study written in
// Chinese used to show its participants English around its own texts: "Waiting for the others",
// "YOUR NETWORK", "Cut the tie between you and Person A?", "Finish and return to Prolific". The words
// are in the server's conf/i18n, which it reads too.

const here = dirname(fileURLToPath(import.meta.url))
const file = (language: string) => JSON.parse(readFileSync(join(here, '../../../conf/i18n', language + '.json'), 'utf8'))

/** Stands in for window.Breadboard: its language, and what the components ask of it. */
function fakeBreadboard (language: string | null, more: any = {}) {
  const listeners: Record<string, Function[]> = {}
  const bb = {
    language,
    connection: 'open',
    preview: false,
    connect: vi.fn(async () => ({})),
    reconnectNow: vi.fn(),
    useHere: vi.fn(),
    send: vi.fn(),
    sendEdgeEdit: vi.fn(),
    on: vi.fn((event: string, fn: Function) => { (listeners[event] ||= []).push(fn) }),
    off: vi.fn((event: string, fn: Function) => { listeners[event] = (listeners[event] || []).filter(f => f !== fn) }),
    emit (event: string, ...args: any[]) { for (const fn of listeners[event] || []) fn(...args) },
    ...more,
  }
  ;(window as any).Breadboard = bb
  return bb
}

async function settle (w?: any) {
  for (let k = 0; k < 10; k++) await Promise.resolve()
  if (w) await w.vm.$nextTick()
}

afterEach(() => {
  delete (window as any).Breadboard
  setTextsLanguage('eng')
  vi.restoreAllMocks()
})

describe('the texts files', () => {
  it('have every text in both languages, with the same parameters', () => {
    const eng = file('eng')
    const zho = file('zho')
    expect(Object.keys(zho).sort()).toEqual(Object.keys(eng).sort())
    const params = (text: any) => {
      const all = typeof text === 'string' ? [text] : Object.values(text) as string[]
      return [...new Set(all.join(' ').match(/\{\w+\}/g) || [])].sort()
    }
    for (const key of Object.keys(eng)) {
      if (key.startsWith('_')) continue
      expect(params(zho[key]), key).toEqual(params(eng[key]))
      // (plural forms: English needs them where a number is counted; Chinese has one)
      if (typeof eng[key] === 'object') expect(Object.keys(eng[key]).sort(), key).toEqual(['one', 'other'])
    }
  })
})

describe('t', () => {
  it('fills in a text in English, with its plural chosen by n', () => {
    expect(t('waiting.here', { n: 1 })).toBe('1 person is here.')
    expect(t('waiting.here', { n: 2 })).toBe('2 people are here.')
    expect(t('network.confirmCut', { a: 'you', b: 'Person B' })).toBe('Cut the tie between you and Person B?')
    expect(t('no.such.text')).toBe('no.such.text')
  })

  it('gives the Chinese text for a session in Chinese, and English for any other language', () => {
    setTextsLanguage('zho')
    expect(t('waiting.here', { n: 1 })).toBe('已有 1 人到达。')
    expect(t('network.confirmCut', { a: '你', b: '参与者 B' })).toBe('切断你和参与者 B之间的连接？')
    expect(sentences('第一句。', '第二句。')).toBe('第一句。第二句。')
    setTextsLanguage('spa')
    expect(t('waiting.here', { n: 2 })).toBe('2 people are here.')
    expect(sentences('One.', 'Two.')).toBe('One. Two.')
    for (const code of ['zho', 'zh', 'zh-CN', 'chi', 'cmn']) expect(textsLanguage(code)).toBe('zho')
    for (const code of ['eng', 'spa', '', null, undefined]) expect(textsLanguage(code)).toBe('eng')
  })

  it('gives a text in pieces, for a page that shows a parameter its own way', () => {
    expect(tParts('waiting.here', { n: 3 })).toEqual([{ text: '3', param: 'n' }, { text: ' people are here.' }])
    setTextsLanguage('zho')
    expect(tParts('waiting.here', { n: 3 })).toEqual([{ text: '已有 ' }, { text: '3', param: 'n' }, { text: ' 人到达。' }])
  })

  it('follows the language of the page\'s Breadboard as the server changes it', () => {
    const bb = fakeBreadboard('zho')
    expect(t('game.youreIn')).toBe('你已加入')
    expect(currentTextsLanguage()).toBe('zho')
    bb.emit(LANGUAGE_EVENT, 'eng')
    expect(t('game.youreIn')).toBe('You\'re in')
    bb.emit(LANGUAGE_EVENT, 'zho')
    expect(t('game.youreIn')).toBe('你已加入')
  })
})

describe('the participant page in Chinese', () => {
  it('waits in Chinese, with the number of people in bold', () => {
    fakeBreadboard('zho')
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 1, min: 3, max: 8 } } } } })
    expect(w.find('.bb-waiting__title').text()).toBe('正在等待其他人')
    expect(w.find('.bb-waiting__count').text()).toBe('已有 1 人到达。人数达到 3–8 人后，实验就会开始。')
    expect(w.find('.bb-waiting__count strong').text()).toBe('1')
    expect(w.find('.bb-waiting__note').text()).toBe('请保持这个页面打开。实验开始时，页面会自动切换。')
    expect(w.find('.bb-waiting__bar').attributes('aria-label')).toBe('3 人中已到 1 人')
    const soon = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 3, min: 3, max: 8, startsIn: 15 } } } } })
    expect(soon.find('.bb-waiting__title').text()).toBe('即将开始')
    expect(soon.find('.bb-waiting__count').text()).toBe('已有 3 人到达。人数已经够了：大约 15 秒后开始；如果先到齐 8 人，就立即开始。')
    const starting = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'starting', seconds: 5, here: 3, min: 3 } } } } })
    expect(starting.find('.bb-waiting__title').text()).toBe('5 秒后开始…')
    expect(starting.find('.bb-waiting__sr').text()).toBe('5 秒后开始')
    expect(document.title).toContain('马上开始 ·')
    starting.destroy()
  })

  it('names the network and its ties in Chinese, and says the participant is in', () => {
    fakeBreadboard('zho')
    const me = { id: 'me', x: 0, y: 0, data: {} }
    const ana = { id: 'na', x: 0, y: 0, data: {} }
    const ben = { id: 'nb', x: 0, y: 0, data: {} }
    const edge = (id: number, a: any, b: any) => ({ id, source: a, target: b, data: {} })
    const stubs = { BBNetwork: { template: '<div />' }, PlayerTimers: true, PlayerChoices: true, Form: true }
    const w = mount(BBGame, { propsData: { player: { id: 'me', _system: {} }, graph: { nodes: [me, ana, ben], edges: [edge(1, me, ana), edge(2, ana, ben)] } }, stubs })
    expect(w.find('.bb-game__eyebrow').text()).toBe('你的网络')
    expect(w.find('.bb-game__meta').text().replace(/\s+/g, ' ')).toBe('1 条连接 · 邻居之间 1 条')
    expect(w.findAll('.bb-game__legend span').wrappers.map(s => s.text())).toEqual(['你的连接', '其他人之间'])
    expect(w.find('.bb-game__network').attributes('aria-label')).toBe('网络')
    const idle = mount(BBGame, { propsData: { player: { id: 'me', _system: {} }, graph: { nodes: [], edges: [] } }, stubs })
    expect(idle.find('.bb-game__idle-title').text()).toBe('你已加入')
    expect(idle.find('.bb-game__idle-text').text()).toBe('研究马上就要开始。这个页面会自动更新。请保持页面打开。')
    const loading = mount(BBGame, { propsData: { player: { id: 'me' }, graph: { nodes: [], edges: [] } }, stubs })
    expect(loading.find('.bb-game__loading').text()).toBe('正在连接…')
  })

  it('offers, confirms and answers tie changes in Chinese, with Chinese stand-in names', async () => {
    const bb = fakeBreadboard('zho')
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), x = node('nx'), y = node('ny')
    const edge = (id: number, a: any, b: any) => ({ id, source: a, target: b, data: {} })
    const w = mount(BBNetwork, {
      propsData: {
        player: { id: 'me', edgeEdits: { enabled: true, remove: true, add: false, scope: 'own', remainingRemoves: 1, turn: 1, confirm: true } },
        graph: { nodes: [me, x, y], edges: [edge(1, me, x), edge(2, me, y)] },
      },
    })
    await settle(w)
    const vm = w.vm as any
    expect(Object.values(vm.fallbackNames).sort()).toEqual(['参与者 A', '参与者 B'])
    // (the letter on the disc, as in English)
    expect(vm.drawnNodes.filter((n: any) => !n.isEgo).map((n: any) => n.initials).sort()).toEqual(['A', 'B'])
    expect(w.find('.bb-network__ego-label').text()).toBe('你')
    expect(w.find('.bb-network__hint').text()).toMatch(/^你可以切断 1 条连接。(点击|点)一条连接，就能切断它。$/)
    expect(w.find('.bb-network__moves-toggle').text()).toBe('以列表显示可以做的修改（2 项）')

    const top = vm.ring[0]
    await w.findAll('.bb-network__edge-hit[role="button"]').at(vm.drawnEdges.findIndex((e: any) => e.edge.target.id === top)).trigger('click')
    expect(w.find('.bb-network__confirm-text').text()).toBe('切断你和参与者 A之间的连接？')
    const buttons = w.findAll('.bb-network__confirm button')
    expect(buttons.at(0).text()).toBe('取消')
    expect(buttons.at(1).text()).toBe('切断连接')
    await buttons.at(1).trigger('click')
    expect(bb.sendEdgeEdit).toHaveBeenCalled()

    // the server's answer, and one of its refusals as it sends them (in the session's language)
    bb.emit('bb:edge-edit-result', { ok: true, applied: true, op: 'remove', a: 'me', b: top })
    await settle(w)
    expect(w.find('.bb-network__toast').text()).toBe('连接已切断。')
    bb.emit('bb:edge-edit-result', { ok: false, op: 'remove', a: 'me', b: top, reason: '他们之间没有连接。' })
    await settle(w)
    expect(w.find('.bb-network__toast').text()).toBe('他们之间没有连接。')
  })

  it('says what the connection is doing in Chinese, with buttons in Chinese', async () => {
    const bb = fakeBreadboard('zho', { connection: 'lost' })
    const stubs = { 'v-alert': { props: ['value'], template: '<div v-if="value" class="bb-connection"><slot /></div>' } }
    const w = mount(ConnectionStatus as any, { stubs })
    await settle(w)
    expect(w.find('.bb-connection__text').text()).toBe('与服务器的连接断开了，正在重新连接…请保持这个页面打开。')
    expect(w.find('.bb-connection__btn').text()).toBe('立即重试')
    bb.connection = 'elsewhere'
    bb.emit('bb:connection')
    await settle(w)
    expect(w.find('.bb-connection__text').text()).toBe('这项研究已经在另一个标签页或窗口中打开了。')
    expect(w.find('.bb-connection__btn').text()).toBe('在这里继续')
    bb.connection = 'ended'
    bb.emit('bb:connection')
    await settle(w)
    expect(w.find('.bb-connection__text').text()).toBe('本场实验已结束。现在可以关闭这个页面了。')
    w.destroy()
  })

  it('keeps a researcher\'s preview notes in English', async () => {
    const bb = fakeBreadboard('zho', { connection: 'watching', preview: true })
    const stubs = { 'v-alert': { props: ['value'], template: '<div v-if="value" class="bb-connection"><slot /></div>' } }
    const w = mount(ConnectionStatus as any, { stubs })
    await settle(w)
    expect(w.find('.bb-connection__text').text()).toContain('Watching only')
    bb.connection = 'elsewhere'
    bb.emit('bb:connection')
    await settle(w)
    expect(w.find('.bb-connection__text').text()).toBe('This player is open in another preview or tab.')
    expect(w.find('.bb-connection__btn').text()).toBe('Use it here')
    w.destroy()
    expect(mount(LinkNote, { propsData: { link: 'watching' } }).text()).toContain('Watching only')
    expect(mount(LinkNote, { propsData: { link: 'reconnecting' } }).text()).toBe('正在重新连接服务器…连上之后才能作答。')
  })

  it('finishes a Prolific participant in Chinese', async () => {
    fakeBreadboard('zho')
    vi.stubGlobal('fetch', vi.fn(async () => ({ status: 200, json: async () => ({ token: null }) })))
    const player = { id: 'p1', text: '<p>谢谢！</p>', _system: { recruitment: { source: 'prolific', completed: true, completionCode: 'C7K2M9QX' } } }
    const w = mount(FinishProlific as any, { propsData: { player } })
    await settle(w)
    const labels = w.findAll('.bb-finish__label')
    expect(labels.at(0).text()).toBe('你的完成码')
    expect(labels.at(1).text()).toBe('给研究者的留言（选填）')
    expect(w.find('.bb-finish__btn').text()).toBe('完成，回到 Prolific')
    expect(w.find('.bb-finish__note').text()).toBe('如果页面没有跳转，请打开 https://app.prolific.com/submissions/complete?cc=C7K2M9QX，或者在 Prolific 上输入上面的完成码。')
    expect(w.find('.bb-finish__note a').attributes('href')).toBe('https://app.prolific.com/submissions/complete?cc=C7K2M9QX')
    w.destroy()
    vi.unstubAllGlobals()
  })

  it('keeps the words a page gives in place of the built-in ones, even empty ones', async () => {
    fakeBreadboard('zho')
    const node = (id: string) => ({ id, x: 0, y: 0, data: {} })
    const me = node('me'), x = node('nx')
    const graph = { nodes: [me, x], edges: [{ id: 1, source: me, target: x, data: {} }] }
    const blank = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph, egoLabel: '', ariaLabel: '' } })
    await settle(blank)
    expect(blank.find('.bb-network__ego-label').text()).toBe('')
    expect(blank.find('.bb-network__svg').attributes('aria-label')).toBe('')
    const own = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph, egoLabel: '我', ariaLabel: '我的小组' } })
    await settle(own)
    expect(own.find('.bb-network__ego-label').text()).toBe('我')
    expect(own.find('.bb-network__svg').attributes('aria-label')).toBe('我的小组')
    const builtIn = mount(BBNetwork, { propsData: { player: { id: 'me' }, graph } })
    await settle(builtIn)
    expect(builtIn.find('.bb-network__ego-label').text()).toBe('你')
    expect(builtIn.find('.bb-network__svg').attributes('aria-label')).toBe('你的网络')

    const stubs = { BBNetwork: { template: '<div />' }, PlayerTimers: true, PlayerChoices: true, Form: true }
    const untitled = mount(BBGame, { propsData: { player: { id: 'me', _system: {} }, graph, networkTitle: '' }, stubs })
    expect(untitled.find('.bb-game__eyebrow').text()).toBe('')
    const titled = mount(BBGame, { propsData: { player: { id: 'me', _system: {} }, graph, networkTitle: '我们的小组' }, stubs })
    expect(titled.find('.bb-game__eyebrow').text()).toBe('我们的小组')
  })

  it('gives the built-in form and tutorial buttons and checks in Chinese', () => {
    fakeBreadboard('zho')
    const w = mount(Tutorial, { propsData: { player: { tutorial: { index: 0, maxSteps: 2, titles: ['一', '二'] } } }, stubs: { 'v-btn': { template: '<button><slot /></button>' }, 'v-icon': { template: '<i />' }, 'v-stepper-header': true, 'v-stepper-step': true, 'v-divider': true } })
    expect(w.findAll('button').at(0).text()).toBe('上一页')
    expect(w.findAll('button').at(1).text()).toBe('下一页')
    expect(isRequired('年龄')('')).toBe('年龄 是必填项')
    expect(minText(10)('短')).toBe('请至少输入 10 个字')
  })
})

describe('the participant page in English', () => {
  it('is as it was for a session in English, or in a language Breadboard has no words in', async () => {
    fakeBreadboard('spa')
    const w = mount(BBWaiting, { propsData: { player: { _system: { autopilot: { state: 'waiting', here: 1, min: 3, max: 8 } } } } })
    expect(w.find('.bb-waiting__count').text()).toBe('1 person is here. The session starts when 3–8 people are here.')
    expect(w.find('.bb-waiting__note').text()).toBe('Please keep this page open. It will change by itself when the session starts.')
    expect(isRequired('age')('')).toBe('age is required')
  })
})
