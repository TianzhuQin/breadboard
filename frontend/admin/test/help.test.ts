import { describe, expect, it, vi, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MarkdownDoc from '../src/pages/help/MarkdownDoc'
import HelpPage from '../src/pages/help/HelpPage.vue'
import { guides, guideLink, githubGuide } from '../src/pages/help/help'
import { headings, linkTargets, parseMarkdown } from '../src/pages/help/markdown'
import { http } from '../src/lib/http'

afterEach(() => { vi.restoreAllMocks(); document.body.innerHTML = '' })

describe('the shipped guides', () => {
  it('renders Markdown as text and safe elements, never as executable HTML', () => {
    const w = mount(MarkdownDoc, { props: { file: 'console.md', source: `# Guide
<script>alert(1)</script><img src=x onerror=alert(2)>

[unsafe](javascript:alert(1)) [also unsafe](data:text/html,bad) [good](https://example.org)

\`<iframe src=bad>\` **strong** *emphasis*

1. First
   - Nested

| Name | Code |
| --- | --- |
| table | \`a|b\` |

~~~html
<img onerror=alert(3)>
~~~` } })
    expect(w.findAll('script, img, iframe, [onerror]').length).toBe(0)
    expect(w.text()).toContain('<script>alert(1)</script>')
    expect(w.findAll('a').map(a => a.attributes('href'))).toEqual(['https://example.org'])
    expect(w.find('strong').text()).toBe('strong')
    expect(w.find('em').text()).toBe('emphasis')
    expect(w.find('ol > li > ul > li').text()).toBe('Nested')
    expect(w.findAll('td')[1].text()).toBe('a|b')
    expect(w.find('pre').text()).toBe('<img onerror=alert(3)>')
    w.unmount()
  })

  it('keeps every shipped guide link and anchor working locally', () => {
    const docs = new Map(guides.map(g => [g.file, parseMarkdown(readFileSync(resolve('../../docs', g.file), 'utf8'))]))
    for (const [file, doc] of docs) {
      const ids = headings(doc).map(h => h.id)
      expect(new Set(ids).size, file).toBe(ids.length)
      for (const href of linkTargets(doc)) {
        const link = guideLink(href, file)
        expect(link, `${file}: ${href}`).not.toBeNull()
        if (!link?.startsWith('/#/help/')) continue
        const [target, anchor] = link.slice('/#/help/'.length).split('#')
        expect(docs.has(target), `${file}: ${href}`).toBe(true)
        if (anchor) expect(headings(docs.get(target)!).map(h => h.id), `${file}: ${href}`).toContain(decodeURIComponent(anchor))
      }
    }
    expect(githubGuide('console.md')).toContain('/blob/v3.1.0/docs/console.md')
    expect(guideLink('../README.md', 'console.md')).toContain('/blob/v3.1.0/README.md')
    expect(guideLink('java\tscript:alert(1)', 'console.md')).toBeNull()
  })

  it('loads Chinese locally, moves between guides, follows anchors and recovers from a failed request', async () => {
    const get = vi.spyOn(http, 'get').mockResolvedValue('# 中文指南\n\n[Install](install.md#troubleshooting)')
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/help/:file', component: HelpPage }] })
    await router.push('/help/zh-CN.md')
    const w = mount(HelpPage, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/docs/zh-CN.md', { raw: true })
    expect(w.find('article').attributes('lang')).toBe('zh-CN')
    expect(w.find('article a').attributes('href')).toBe('/#/help/install.md#troubleshooting')
    get.mockRejectedValueOnce(new Error('offline'))
    await router.push('/help/install.md#troubleshooting')
    await flushPromises()
    expect(w.find('[role=alert]').text()).toContain('Try again')
    get.mockResolvedValueOnce('# Install\n\n## Troubleshooting\n\nTry this.')
    await w.find('[role=alert] button').trigger('click')
    await flushPromises()
    expect(w.find('[role=alert]').exists()).toBe(false)
    expect(document.activeElement?.id).toBe('troubleshooting')
    expect(w.find('.help-secondary a').attributes('href')).toBe(githubGuide('install.md', '#troubleshooting'))
    w.unmount()
  })
})
