/**
 * A small Markdown reader for the guides in the release's docs/ (the Help page): the Markdown those
 * files use, and no more. It makes a tree of blocks and inline parts, never HTML, and the Help page
 * builds the page from that tree with Vue, which puts every text in as text: HTML in a file shows as
 * written, and nothing in a file can add an element, an attribute or a script. Links keep only safe
 * addresses (see safeHref).
 *
 * Blocks: headings (# to ######, with ids as GitHub makes them, so links to #anchors work), paragraphs,
 * fenced code (``` or ~~~), lists (- * + or 1. 1), nested by indenting, with code, tables and paragraphs
 * inside), tables (with a | --- | row), block quotes (>) and rules (---). Inline: `code`, **strong**,
 * *emphasis*, [links](address), <https://autolinks>, backslash escapes, and line breaks (two spaces or
 * a backslash at the end of a line).
 */

export type Inline =
  | { t: 'text', v: string }
  | { t: 'code', v: string }
  | { t: 'strong', c: Inline[] }
  | { t: 'em', c: Inline[] }
  | { t: 'link', href: string, c: Inline[] }
  | { t: 'br' }

export type Align = 'left' | 'center' | 'right' | null

export type Block =
  | { t: 'heading', level: number, id: string, c: Inline[] }
  | { t: 'p', c: Inline[] }
  | { t: 'code', lang: string, v: string }
  | { t: 'list', ordered: boolean, start: number, items: Block[][] }
  | { t: 'table', align: Align[], head: Inline[][], rows: Inline[][][] }
  | { t: 'quote', c: Block[] }
  | { t: 'hr' }

/** The guide's blocks, with every heading's id unique in it. */
export function parseMarkdown (src: string): Block[] {
  const lines = String(src ?? '').replace(/\r\n?/g, '\n').replace(/\t/g, '    ').split('\n')
  return blocks(lines, new Map())
}

// ---- blocks --------------------------------------------------------------------------------------

const FENCE = /^( {0,3})(`{3,}|~{3,})\s*([^\s`]*)[^`]*$/
const HEADING = /^ {0,3}(#{1,6})(?:\s+|$)(.*)$/
const RULE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/
const QUOTE = /^ {0,3}> ?(.*)$/
const LIST_ITEM = /^( {0,3})([-*+]|\d{1,9}[.)])( +|$)(.*)$/
const DELIMITER_CELL = /^:?-+:?$/

function indentOf (line: string): number {
  return line.length - line.trimStart().length
}

/** Whether a line starts a block of its own (and so ends a paragraph). */
function startsBlock (line: string): boolean {
  return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || QUOTE.test(line) || LIST_ITEM.test(line)
}

function blocks (lines: string[], slugs: Map<string, number>): Block[] {
  const out: Block[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) { i++; continue }

    const fence = FENCE.exec(line)
    if (fence) {
      const indent = fence[1].length
      const close = new RegExp(`^ {0,3}${fence[2][0] === '`' ? '`' : '~'}{${fence[2].length},}\\s*$`)
      const body: string[] = []
      i++
      while (i < lines.length && !close.test(lines[i])) {
        // (the fence's own indent comes off each line, as far as it goes)
        body.push(lines[i].slice(Math.min(indent, indentOf(lines[i]))))
        i++
      }
      i++
      out.push({ t: 'code', lang: fence[3], v: body.join('\n') })
      continue
    }

    const heading = HEADING.exec(line)
    if (heading) {
      const c = inlines(heading[2].replace(/\s+#+\s*$/, '').replace(/^#+\s*$/, '').trim())
      out.push({ t: 'heading', level: heading[1].length, id: uniqueSlug(slugify(plainText(c)), slugs), c })
      i++
      continue
    }

    if (RULE.test(line)) {
      out.push({ t: 'hr' })
      i++
      continue
    }

    if (line.includes('|') && i + 1 < lines.length && isDelimiterRow(lines[i + 1])) {
      const head = cells(line)
      const align = cells(lines[i + 1]).map(alignOf)
      const rows: Inline[][][] = []
      i += 2
      while (i < lines.length && lines[i].trim() && lines[i].includes('|')) {
        const row = cells(lines[i])
        // (as many cells as the header: missing ones are empty, extra ones dropped)
        rows.push(head.map((_, k) => inlines(row[k] ?? '')))
        i++
      }
      out.push({ t: 'table', align: head.map((_, k) => align[k] ?? null), head: head.map(h => inlines(h)), rows })
      continue
    }

    if (QUOTE.test(line)) {
      const inner: string[] = []
      while (i < lines.length && lines[i].trim()) {
        const q = QUOTE.exec(lines[i])
        inner.push(q ? q[1] : lines[i])
        i++
      }
      out.push({ t: 'quote', c: blocks(inner, slugs) })
      continue
    }

    if (LIST_ITEM.test(line)) {
      i = list(lines, i, slugs, out)
      continue
    }

    // a paragraph: up to a blank line, or a line that starts another block
    const text: string[] = [line]
    i++
    while (i < lines.length && lines[i].trim() && !startsBlock(lines[i]) &&
      !(lines[i].includes('|') && i + 1 < lines.length && isDelimiterRow(lines[i + 1]))) {
      text.push(lines[i])
      i++
    }
    out.push({ t: 'p', c: paragraph(text) })
  }
  return out
}

/** A list from line `i` (an item's line), added to `out`; returns the line after it. */
function list (lines: string[], i: number, slugs: Map<string, number>, out: Block[]): number {
  const first = LIST_ITEM.exec(lines[i])!
  const ordered = /\d/.test(first[2])
  const indent = first[1].length
  const sameList = (m: RegExpExecArray | null) => !!m && /\d/.test(m[2]) === ordered && m[1].length === indent
  const items: Block[][] = []
  while (i < lines.length) {
    const m = LIST_ITEM.exec(lines[i])
    if (!sameList(m)) break
    // the item's text starts after the marker and one to four spaces
    const spaces = m![3].length
    const content = m![1].length + m![2].length + (spaces >= 1 && spaces <= 4 ? spaces : 1)
    const item: string[] = [m![4] ? ' '.repeat(Math.max(0, spaces - (content - m![1].length - m![2].length))) + m![4] : '']
    i++
    while (i < lines.length) {
      const l = lines[i]
      if (!l.trim()) {
        // a blank line belongs to the item when what follows is indented as its text is
        let j = i + 1
        while (j < lines.length && !lines[j].trim()) j++
        if (j < lines.length && indentOf(lines[j]) >= content) {
          for (; i < j; i++) item.push('')
          continue
        }
        break
      }
      if (indentOf(l) >= content) {
        item.push(l.slice(content))
        i++
        continue
      }
      // a paragraph's line that isn't indented goes on with it ("lazy" continuation)
      if (!startsBlock(l) && item.length && item[item.length - 1].trim() && !FENCE.test(item[0])) {
        item.push(l.trim())
        i++
        continue
      }
      break
    }
    items.push(blocks(item, slugs))
    // items may be apart by blank lines
    let j = i
    while (j < lines.length && !lines[j].trim()) j++
    if (j < lines.length && sameList(LIST_ITEM.exec(lines[j]))) i = j
    else break
  }
  out.push({ t: 'list', ordered, start: ordered ? Number(/\d+/.exec(first[2])![0]) : 1, items })
  return i
}

function isDelimiterRow (line: string): boolean {
  if (!line.includes('-')) return false
  const c = cells(line)
  return c.length > 0 && c.every(x => DELIMITER_CELL.test(x.trim()))
}

function alignOf (cell: string): Align {
  const c = cell.trim()
  const left = c.startsWith(':')
  const right = c.endsWith(':')
  return left && right ? 'center' : right ? 'right' : left ? 'left' : null
}

/** A table row's cells: split at the | that are neither escaped nor in `code`. */
function cells (line: string): string[] {
  let s = line.trim()
  if (s.startsWith('|')) s = s.slice(1)
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1)
  const out: string[] = []
  let cur = ''
  let ticks = 0
  for (let k = 0; k < s.length; k++) {
    const ch = s[k]
    if (ch === '\\' && s[k + 1] === '|' && !ticks) {
      cur += '|'
      k++
      continue
    }
    if (ch === '`') {
      let n = 1
      while (s[k + n] === '`') n++
      ticks = ticks === n ? 0 : ticks || n
      cur += '`'.repeat(n)
      k += n - 1
      continue
    }
    if (ch === '|' && !ticks) {
      out.push(cur.trim())
      cur = ''
      continue
    }
    cur += ch
  }
  out.push(cur.trim())
  return out
}

/** A paragraph's lines: joined with spaces, or a line break where a line ends in two spaces or a backslash. */
function paragraph (text: string[]): Inline[] {
  const out: Inline[] = []
  text.forEach((line, k) => {
    const last = k === text.length - 1
    const hard = !last && (/ {2,}$/.test(line) || /(^|[^\\])\\$/.test(line))
    const body = hard ? line.replace(/(?: {2,}|\\)$/, '') : line
    out.push(...inlines(body.trim()))
    if (!last) out.push(hard ? { t: 'br' } : { t: 'text', v: ' ' })
  })
  return merge(out)
}

// ---- inline --------------------------------------------------------------------------------------

const PUNCTUATION = '!"#$%&\'()*+,-./:;<=>?@[\\]^_`{|}~'

/** The inline parts of a line of text. */
export function inlines (src: string): Inline[] {
  const out: Inline[] = []
  let text = ''
  const flush = () => {
    if (text) out.push({ t: 'text', v: text })
    text = ''
  }
  let i = 0
  while (i < src.length) {
    const ch = src[i]
    if (ch === '\\' && i + 1 < src.length && PUNCTUATION.includes(src[i + 1])) {
      text += src[i + 1]
      i += 2
      continue
    }
    if (ch === '`') {
      const span = codeSpan(src, i)
      if (span) {
        flush()
        out.push({ t: 'code', v: span.v })
        i = span.end
        continue
      }
      // (backticks that close nothing are text)
      let n = 1
      while (src[i + n] === '`') n++
      text += '`'.repeat(n)
      i += n
      continue
    }
    if (ch === '<') {
      const auto = /^<((?:https?:\/\/|mailto:)[^\s<>]+)>/i.exec(src.slice(i))
      if (auto) {
        flush()
        out.push({ t: 'link', href: auto[1], c: [{ t: 'text', v: auto[1].replace(/^mailto:/i, '') }] })
        i += auto[0].length
        continue
      }
    }
    if (ch === '[' || (ch === '!' && src[i + 1] === '[')) {
      const link = linkAt(src, ch === '!' ? i + 1 : i)
      if (link) {
        flush()
        // (an image is shown as its text, linked to the picture: the guides have none)
        out.push({ t: 'link', href: link.href, c: inlines(link.text) })
        i = link.end
        continue
      }
    }
    if (ch === '*') {
      const strong = src.startsWith('**', i)
      const marker = strong ? '**' : '*'
      const after = src[i + marker.length]
      if (after && after !== ' ' && !(strong && after === '*')) {
        const close = closing(src, i + marker.length, marker)
        if (close > i + marker.length) {
          flush()
          const inner = inlines(src.slice(i + marker.length, close))
          out.push(strong ? { t: 'strong', c: inner } : { t: 'em', c: inner })
          i = close + marker.length
          continue
        }
      }
    }
    text += ch
    i++
  }
  flush()
  return merge(out)
}

/** A code span starting at the backticks at `i`: its text, and where it ends; null if nothing closes it. */
function codeSpan (src: string, i: number): { v: string, end: number } | null {
  let n = 0
  while (src[i + n] === '`') n++
  let k = i + n
  while (k < src.length) {
    const at = src.indexOf('`'.repeat(n), k)
    if (at < 0) return null
    let m = 0
    while (src[at + m] === '`') m++
    if (m === n) {
      let v = src.slice(i + n, at)
      if (v.length > 1 && v.startsWith(' ') && v.endsWith(' ') && v.trim()) v = v.slice(1, -1)
      return { v, end: at + n }
    }
    k = at + m
  }
  return null
}

/**
 * Where the `marker` (** or *) that closes an emphasis opened before `from` is: not in `code`, not
 * escaped, after a character that isn't a space (and a * not part of a **); -1 when there is none.
 */
function closing (src: string, from: number, marker: string): number {
  let k = from
  while (k < src.length) {
    const ch = src[k]
    if (ch === '\\') { k += 2; continue }
    if (ch === '`') {
      const span = codeSpan(src, k)
      if (span) { k = span.end; continue }
    }
    if (ch === '[') {
      const link = linkAt(src, k)
      if (link) { k = link.end; continue }
    }
    if (src.startsWith(marker, k) && src[k - 1] !== ' ') {
      if (marker === '*' && (src[k + 1] === '*' || src[k - 1] === '*')) {
        // (part of a ** inside: skip it whole)
        k += src[k + 1] === '*' ? 2 : 1
        continue
      }
      return k
    }
    if (marker === '*' && src.startsWith('**', k)) {
      const inner = closing(src, k + 2, '**')
      if (inner > 0) { k = inner + 2; continue }
    }
    k++
  }
  return -1
}

/** A [text](address) at `i`: its text, its address, and where it ends; null when it isn't one. */
function linkAt (src: string, i: number): { text: string, href: string, end: number } | null {
  if (src[i] !== '[') return null
  let depth = 0
  let k = i
  for (; k < src.length; k++) {
    const ch = src[k]
    if (ch === '\\') { k++; continue }
    if (ch === '`') {
      const span = codeSpan(src, k)
      if (span) { k = span.end - 1; continue }
    }
    if (ch === '[') depth++
    else if (ch === ']' && --depth === 0) break
  }
  if (k >= src.length || src[k + 1] !== '(') return null
  const rest = /^\(\s*(<[^<>\n]*>|[^\s()<>]+(?:\([^\s()<>]*\)[^\s()<>]*)*)?(?:\s+"[^"]*")?\s*\)/.exec(src.slice(k + 1))
  if (!rest) return null
  const href = (rest[1] || '').replace(/^<|>$/g, '')
  return { text: src.slice(i + 1, k), href, end: k + 1 + rest[0].length }
}

/** Neighbouring text parts as one. */
function merge (parts: Inline[]): Inline[] {
  const out: Inline[] = []
  for (const p of parts) {
    const last = out[out.length - 1]
    if (p.t === 'text' && last && last.t === 'text') last.v += p.v
    else out.push(p)
  }
  return out
}

// ---- headings' ids and links -----------------------------------------------------------------------

/** The text of inline parts, without the marks. */
export function plainText (parts: Inline[]): string {
  return parts.map(p => p.t === 'text' || p.t === 'code' ? p.v : p.t === 'br' ? ' ' : plainText(p.c)).join('')
}

/**
 * A heading's id as GitHub makes it, so that links to its #anchor work here as there: lower case,
 * without punctuation (letters of every script, digits, _ and - stay), a dash for each space.
 * "Running without anyone at the admin console (autopilot)" is
 * "running-without-anyone-at-the-admin-console-autopilot", "用 Prolific 招募" "用-prolific-招募".
 */
export function slugify (text: string): string {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{M}\p{N}\p{Pc}\- ]/gu, '').replace(/ /g, '-')
}

function uniqueSlug (slug: string, seen: Map<string, number>): string {
  const n = seen.get(slug)
  seen.set(slug, (n ?? -1) + 1)
  return n === undefined ? slug : `${slug}-${n + 1}`
}

/**
 * An address a link may have: http, https and mailto, and addresses without a scheme (a guide, an
 * #anchor, a path); null for anything else (javascript:, data:, …), which stays text.
 */
export function safeHref (href: string): string | null {
  const h = href.trim()
  if (!h) return null
  // (the scheme as a browser reads it, ignoring what it ignores: spaces and control characters)
  const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(h.replace(/[\u0000- \u007f]/g, ''))
  if (!scheme) return h
  return ['http', 'https', 'mailto'].includes(scheme[1].toLowerCase()) ? h : null
}

/** The ids of every heading, in order, from the top (for links to them, and a table of contents). */
export function headings (doc: Block[]): { level: number, id: string, text: string }[] {
  const out: { level: number, id: string, text: string }[] = []
  const walk = (list: Block[]) => {
    for (const b of list) {
      if (b.t === 'heading') out.push({ level: b.level, id: b.id, text: plainText(b.c) })
      else if (b.t === 'list') b.items.forEach(walk)
      else if (b.t === 'quote') walk(b.c)
    }
  }
  walk(doc)
  return out
}

/** Every link's address, in order (for checking that the guides' links lead somewhere). */
export function linkTargets (doc: Block[]): string[] {
  const out: string[] = []
  const fromInlines = (parts: Inline[]) => {
    for (const p of parts) {
      if (p.t === 'link') { out.push(p.href); fromInlines(p.c) } else if (p.t === 'strong' || p.t === 'em') fromInlines(p.c)
    }
  }
  const walk = (list: Block[]) => {
    for (const b of list) {
      if (b.t === 'heading' || b.t === 'p') fromInlines(b.c)
      else if (b.t === 'list') b.items.forEach(walk)
      else if (b.t === 'quote') walk(b.c)
      else if (b.t === 'table') { b.head.forEach(fromInlines); b.rows.forEach(r => r.forEach(fromInlines)) }
    }
  }
  walk(doc)
  return out
}
