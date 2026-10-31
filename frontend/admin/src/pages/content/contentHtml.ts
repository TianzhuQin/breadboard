/**
 * HTML helpers for the Content page: what counts as "no text", the old editor's placeholder, the
 * check that keeps the visual editor from silently changing markup, and the sandboxed preview.
 * Everything here is pure (no store, no network) so it can be tested on its own.
 */

/** The paragraph the old console saved when a researcher only *looked* at a missing translation. */
const OLD_PLACEHOLDER = /^\s*<p>\s*Type your content for the [^<]{1,80} language here\.\s*<\/p>\s*$/i

export function isOldPlaceholder (html: string | null | undefined): boolean {
  return !!html && OLD_PLACEHOLDER.test(html)
}

// Markup that shows something even without any text (an image, a video, a form control …).
const VISIBLE_WITHOUT_TEXT = /<(img|video|audio|iframe|svg|object|embed|input|select|textarea|button|canvas|picture|hr)\b/i

/** Whether a translation shows anything (whitespace, empty paragraphs and comments don't count). */
export function hasText (html: string | null | undefined): boolean {
  if (!html) return false
  const s = html.replace(/<!--[\s\S]*?-->/g, '')
  if (VISIBLE_WITHOUT_TEXT.test(s)) return true
  return plainText(s) !== ''
}

// Tags that sit inside a line of text, as <strong>{0}</strong>. does: dropped without a space, as a
// browser shows them ("You picked {0}.", not "You picked {0} ."). Any other tag (a paragraph, a
// line break, a list item, an image …) separates words.
const INLINE_TAGS = new Set([
  'a', 'abbr', 'b', 'bdi', 'bdo', 'big', 'cite', 'code', 'data', 'del', 'dfn', 'em', 'font', 'i', 'ins', 'kbd', 'mark',
  'q', 's', 'samp', 'small', 'span', 'strike', 'strong', 'sub', 'sup', 'time', 'tt', 'u', 'var', 'wbr',
])

/** The words of an HTML string as a browser shows them, for list snippets and search (tags and scripts dropped). */
export function plainText (html: string | null | undefined): string {
  if (!html) return ''
  const s = html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style|template)\b[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<\/?([a-zA-Z][^\s/>]*)[^>]*>/g, (_tag, name: string) => (INLINE_TAGS.has(name.toLowerCase()) ? '' : ' '))
    .replace(/<[^>]*>/g, ' ')
  return decodeEntities(s).replace(/[\s ]+/g, ' ').trim()
}

/** Same text, apart from line endings (CodeMirror always hands back "\n"). */
export function sameText (a: string, b: string): boolean {
  return a === b || a.replace(/\r\n?/g, '\n') === b.replace(/\r\n?/g, '\n')
}

let decoder: HTMLTextAreaElement | null = null
const BASIC: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', '#39': "'" }

/** Decode character references. A textarea parses its content as plain text, so nothing runs. */
export function decodeEntities (s: string): string {
  if (s.indexOf('&') < 0) return s
  if (typeof document !== 'undefined') {
    decoder = decoder || document.createElement('textarea')
    decoder.innerHTML = s
    return decoder.value
  }
  return s.replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e: string) => {
    if (e[0] === '#') {
      const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)
      return Number.isFinite(code) ? String.fromCodePoint(code) : m
    }
    return BASIC[e.toLowerCase()] ?? m
  })
}

// ---- the visual editor's safety check ---------------------------------------------------------

export type HtmlToken =
  | { t: 'open', name: string, attrs: Record<string, string> }
  | { t: 'close', name: string }
  | { t: 'text', v: string }
  | { t: 'comment', v: string }

const RAW_TEXT = new Set(['script', 'style', 'textarea', 'title', 'xmp'])
const ATTR = /([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g

function hex2 (n: number) { return Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0') }

/** One way of writing a style attribute, so "color:RED" and "color: red;" compare equal. */
export function normalizeStyle (style: string): string {
  return style
    .split(';')
    .map(d => {
      const i = d.indexOf(':')
      if (i < 0) return ''
      const prop = d.slice(0, i).trim().toLowerCase()
      let value = d.slice(i + 1).trim().replace(/\s+/g, ' ').replace(/\s*,\s*/g, ',')
      if (!/url\(/i.test(value)) value = value.toLowerCase()
      value = value.replace(/rgb\((\d+),(\d+),(\d+)\)/g, (_m, r, g, b) => `#${hex2(+r)}${hex2(+g)}${hex2(+b)}`)
      return prop ? `${prop}:${value}` : ''
    })
    .filter(Boolean)
    .sort()
    .join(';')
}

function parseAttrs (src: string): Record<string, string> {
  const attrs: Record<string, string> = {}
  ATTR.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = ATTR.exec(src))) {
    const name = m[1].toLowerCase()
    // the editor's own bookkeeping attributes never reach the saved text
    if (name.startsWith('data-mce-')) continue
    let value = decodeEntities(m[2] ?? m[3] ?? m[4] ?? '')
    if (name === 'style') value = normalizeStyle(value)
    else if (name === 'class') value = value.trim().replace(/\s+/g, ' ')
    else if (value.toLowerCase() === name) value = '' // checked="checked" is the same as checked
    attrs[name] = value
  }
  return attrs
}

/**
 * Split HTML into tags, words and comments. Not a full HTML parser: it only needs to see the same
 * things in the same order that a reader of the source would (so it never "repairs" markup).
 */
export function tokenize (html: string): HtmlToken[] {
  const out: HtmlToken[] = []
  const lower = html.toLowerCase()
  const n = html.length
  const words = (s: string) => {
    for (const w of decodeEntities(s).split(/[\s ]+/)) if (w) out.push({ t: 'text', v: w })
  }
  let i = 0
  while (i < n) {
    const lt = html.indexOf('<', i)
    if (lt < 0) { words(html.slice(i)); break }
    if (lt > i) words(html.slice(i, lt))
    if (html.startsWith('<!--', lt)) {
      const end = html.indexOf('-->', lt + 4)
      out.push({ t: 'comment', v: html.slice(lt + 4, end < 0 ? n : end).replace(/\s+/g, ' ').trim() })
      i = end < 0 ? n : end + 3
      continue
    }
    if (html.startsWith('<!', lt) || html.startsWith('<?', lt)) {
      // a doctype or processing instruction: not content
      const end = html.indexOf('>', lt)
      i = end < 0 ? n : end + 1
      continue
    }
    const m = /^<(\/?)([a-zA-Z][^\s/>]*)/.exec(html.slice(lt, lt + 128))
    if (!m) { words('<'); i = lt + 1; continue }
    const closing = m[1] === '/'
    const name = m[2].toLowerCase()
    // the tag ends at the first ">" outside quotes
    let j = lt + m[0].length
    let quote = ''
    while (j < n) {
      const ch = html[j]
      if (quote) { if (ch === quote) quote = '' } else if (ch === '"' || ch === "'") quote = ch
      else if (ch === '>') break
      j++
    }
    const inner = html.slice(lt + m[0].length, j)
    i = j < n ? j + 1 : n
    if (closing) { out.push({ t: 'close', name }); continue }
    out.push({ t: 'open', name, attrs: parseAttrs(inner) })
    if (RAW_TEXT.has(name)) {
      const end = lower.indexOf(`</${name}`, i)
      words(html.slice(i, end < 0 ? n : end))
      i = end < 0 ? n : end
    }
  }
  return out
}

/** Whether `out` (the editor's version) still has everything `orig` had. */
function covers (out: HtmlToken, orig: HtmlToken): boolean {
  if (out.t !== orig.t) return false
  if (orig.t === 'open') {
    const o = out as Extract<HtmlToken, { t: 'open' }>
    if (o.name !== orig.name) return false
    for (const k of Object.keys(orig.attrs)) if (o.attrs[k] !== orig.attrs[k]) return false
    return true
  }
  if (orig.t === 'close') return (out as Extract<HtmlToken, { t: 'close' }>).name === orig.name
  return (out as { v: string }).v === orig.v
}

function describe (tok: HtmlToken): string {
  const clip = (s: string) => (s.length > 40 ? `${s.slice(0, 40)}…` : s)
  switch (tok.t) {
    case 'open': {
      const extra = Object.keys(tok.attrs).length ? ' …' : ''
      return `the <${tok.name}${extra}> tag`
    }
    case 'close': return `the end of a <${tok.name}>`
    case 'comment': return 'an HTML comment'
    default: return `“${clip(tok.v)}”`
  }
}

/**
 * Compare a text with what the visual editor would save for it. The editor may *add* things (it
 * wraps loose text in paragraphs, adds <tbody>, reformats styles), which is harmless; but if any
 * tag, attribute, word, placeholder or comment of the original is missing or out of order, the
 * visual editor must not be used on it. Returns a description of the first such change, or null.
 */
export function roundTripLoss (original: string, output: string): string | null {
  const a = tokenize(original)
  const b = tokenize(output)
  let j = 0
  for (const tok of a) {
    while (j < b.length && !covers(b[j], tok)) j++
    if (j >= b.length) return describe(tok)
    j++
  }
  return null
}

/**
 * Markup that would run code if the visual editor showed it: TinyMCE's editing area is a
 * same-origin frame, so an onerror="…" there runs with the console's rights (TinyMCE disarms
 * <script> tags itself). Deliberately over-cautious: a false alarm only means "edit it as HTML".
 * Returns what was found, in words, or null.
 */
export function activeContent (html: string): string | null {
  const handler = /[\s"'/]on([a-z]+)\s*=/i.exec(html)
  if (handler) return `an on${handler[1].toLowerCase()} event handler`
  if (/(java|vb)\s*script\s*:/i.test(decodeEntities(html))) return 'a javascript: link'
  if (/\ssrcdoc\s*=/i.test(html)) return 'a frame with a page of its own (srcdoc)'
  if (/<meta\b[^>]*http-equiv/i.test(html)) return 'a <meta http-equiv> tag'
  return null
}

// ---- preview ----------------------------------------------------------------------------------

const PLACEHOLDER = /\{\d+\}/

/**
 * Wrap {0}-style placeholders (filled in by c.get("Name", value)) in a <mark>, so the preview shows
 * where values go. Parsed with DOMParser, which never runs scripts or loads anything.
 */
export function markPlaceholders (html: string): string {
  if (!PLACEHOLDER.test(html) || typeof DOMParser === 'undefined') return html
  const doc = new DOMParser().parseFromString(`<!DOCTYPE html><html><head></head><body>${html}</body></html>`, 'text/html')
  const walker = doc.createTreeWalker(doc.body, 4 /* NodeFilter.SHOW_TEXT */)
  const hits: Text[] = []
  while (walker.nextNode()) {
    const node = walker.currentNode as Text
    if (PLACEHOLDER.test(node.data) && !node.parentElement?.closest('script,style,textarea,title')) hits.push(node)
  }
  for (const node of hits) {
    const frag = doc.createDocumentFragment()
    for (const part of node.data.split(/(\{\d+\})/)) {
      if (!part) continue
      if (/^\{\d+\}$/.test(part)) {
        const mark = doc.createElement('mark')
        mark.className = 'bb-ph'
        mark.textContent = part
        frag.appendChild(mark)
      } else {
        frag.appendChild(doc.createTextNode(part))
      }
    }
    node.replaceWith(frag)
  }
  return doc.body.innerHTML
}

/** The participant page's look (frontend/client/src/theme/bb-theme.css, always light). */
export const PROSE_CSS = `
.bb-prose { font-size: 16px; line-height: 1.65; color: #334155; overflow-wrap: break-word; }
.bb-prose h1, .bb-prose h2, .bb-prose h3, .bb-prose h4 { color: #0f172a; font-weight: 700; letter-spacing: -0.015em; line-height: 1.25; margin: 0 0 0.6em; }
.bb-prose h1 { font-size: 28px; } .bb-prose h2 { font-size: 23px; } .bb-prose h3 { font-size: 19px; } .bb-prose h4 { font-size: 16px; }
.bb-prose p { margin: 0 0 0.9em; }
.bb-prose ul, .bb-prose ol { margin: 0 0 0.9em; padding-left: 1.3em; }
.bb-prose li { margin: 0.25em 0; }
.bb-prose strong, .bb-prose b { color: #0f172a; font-weight: 650; }
.bb-prose a { color: #4f46e5; }
.bb-prose table { border-collapse: collapse; width: 100%; margin: 0 0 1em; font-size: 15px; }
.bb-prose th, .bb-prose td { border-bottom: 1px solid #e7e9f1; padding: 8px 10px; text-align: left; }
.bb-prose th { color: #64748b; font-weight: 600; font-size: 13px; text-transform: uppercase; letter-spacing: 0.04em; }
.bb-prose img { max-width: 100%; height: auto; border-radius: 12px; }
`

const PREVIEW_CSS = `
html { background: #f5f6fa; }
body { margin: 0; padding: 20px; font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; -webkit-font-smoothing: antialiased; }
.bb-card { box-sizing: border-box; max-width: 760px; margin: 0 auto; padding: 24px 26px; background: #fff; border: 1px solid #e7e9f1; border-radius: 20px; box-shadow: 0 1px 2px rgba(15,23,42,.04), 0 10px 30px rgba(15,23,42,.06); }
.bb-prose > :first-child { margin-top: 0; }
.bb-prose > :last-child { margin-bottom: 0; }
mark.bb-ph { padding: 0 4px; border-radius: 5px; background: #eef2ff; color: #4338ca; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 0.88em; }
.bb-nothing { color: #94a3b8; font-style: italic; }
@media (max-width: 480px) { body { padding: 10px; } .bb-card { padding: 18px 16px; border-radius: 14px; } }
` + PROSE_CSS

/**
 * The document shown in the preview iframe (which is sandboxed: no scripts, no access to the
 * console). The experiment's own participant CSS is included here, never in the console page.
 */
export function previewDocument (html: string, opts: { style?: string, emptyText?: string, markPlaceholders?: boolean } = {}): string {
  const body = hasText(html)
    ? (opts.markPlaceholders === false ? html : markPlaceholders(html))
    : `<p class="bb-nothing">${escapeHtml(opts.emptyText || 'Nothing to show yet.')}</p>${html}`
  const own = (opts.style || '').replace(/<\/style/gi, '<\\/style')
  return '<!DOCTYPE html><html><head><meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width, initial-scale=1">'
    // links can't open anything from the sandbox; this keeps them from replacing the preview
    + '<base target="_blank">'
    + `<style>${PREVIEW_CSS}</style>`
    + (own ? `<style>${own}</style>` : '')
    + `</head><body class="bb-app"><main class="bb-card bb-prose">${body}</main></body></html>`
}

export function escapeHtml (s: string): string {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!))
}
