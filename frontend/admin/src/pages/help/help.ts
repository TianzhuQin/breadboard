import { version } from '../../../package.json'
import { safeHref } from './markdown'

export const guides = [
  { file: 'console.md', label: 'The console’s guide' },
  { file: 'zh-CN.md', label: '中文快速上手' },
  { file: 'install.md', label: 'Install & troubleshooting' },
  { file: 'whats-new.md', label: 'For experiment authors' },
  { file: 'development.md', label: 'Development' },
]
export const helpUrl = (file: string, anchor = '') => `/#/help/${file}${anchor}`
export const githubGuide = (file: string, anchor = '') =>
  `https://github.com/TianzhuQin/breadboard/blob/v${version}/docs/${file}${anchor}`

/** Keep links between shipped guides local; other repository files use this release's tag. */
export function guideLink (href: string, file: string): string | null {
  const safe = safeHref(href)
  if (!safe) return null
  if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(safe)) return safe
  const resolved = new URL(safe, `https://guides.invalid/docs/${file}`)
  const name = resolved.pathname.replace(/^\/docs\//, '')
  if (guides.some(g => g.file === name)) return helpUrl(name, resolved.hash)
  return `https://github.com/TianzhuQin/breadboard/blob/v${version}${resolved.pathname}${resolved.hash}`
}
