import { defineComponent, h, type VNodeChild } from 'vue'
import { parseMarkdown, type Block, type Inline } from './markdown'
import { guideLink } from './help'

/** Render only known nodes with text children; Markdown can never supply HTML or attributes. */
export default defineComponent({
  props: { source: { type: String, required: true }, file: { type: String, required: true } },
  setup: props => () => {
    const inline = (parts: Inline[]): VNodeChild[] => parts.map(p => {
      if (p.t === 'text') return p.v
      if (p.t === 'code') return h('code', p.v)
      if (p.t === 'br') return h('br')
      if (p.t === 'strong' || p.t === 'em') return h(p.t, inline(p.c))
      const href = guideLink(p.href, props.file)
      return href ? h('a', { href, ...(href.startsWith('/#/help/') ? {} : { target: '_blank', rel: 'noopener noreferrer' }) }, inline(p.c)) : inline(p.c)
    })
    const block = (b: Block): VNodeChild => {
      if (b.t === 'heading') return h(`h${b.level}`, { id: b.id, tabindex: -1 }, inline(b.c))
      if (b.t === 'p') return h('p', inline(b.c))
      if (b.t === 'code') return h('pre', [h('code', b.v)])
      if (b.t === 'hr') return h('hr')
      if (b.t === 'quote') return h('blockquote', b.c.map(block))
      if (b.t === 'list') return h(b.ordered ? 'ol' : 'ul', b.ordered ? { start: b.start } : {}, b.items.map(item => h('li', item.map(block))))
      const cell = (tag: string, c: Inline[], i: number) => h(tag, { style: { textAlign: b.align[i] || undefined } }, inline(c))
      return h('div', { class: 'help-table' }, [h('table', [
        h('thead', [h('tr', b.head.map((c, i) => cell('th', c, i)))]),
        h('tbody', b.rows.map(row => h('tr', row.map((c, i) => cell('td', c, i))))),
      ])])
    }
    return h('article', { class: 'help-doc', lang: props.file === 'zh-CN.md' ? 'zh-CN' : 'en' }, parseMarkdown(props.source).map(block))
  },
})
