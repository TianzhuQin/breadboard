import type { CodeLanguage } from '@/ui'

/**
 * The three parts of the participant page an experiment can customise (CustomizeController):
 * GET/POST /customize/{clientHtml,clientGraph,style}/:experimentId, each as {"<key>": "<text>"}.
 */
export type PartKey = 'clientHtml' | 'clientGraph' | 'style'

export interface PartInfo {
  key: PartKey
  label: string
  /** the language, for people who know it by that name */
  tech: string
  language: CodeLanguage
  /** the file it lives in when the experiment is in file mode or exported */
  file: string
  what: string
}

export const PARTS: PartInfo[] = [
  {
    key: 'clientHtml',
    label: 'Client HTML',
    tech: 'HTML',
    language: 'html',
    file: 'client-html.html',
    what: 'The layout of the page: a Vue template that places the network, the experiment’s text, choices and forms. The new participant page (BBGame) does all of this; add your own HTML around it.',
  },
  {
    key: 'clientGraph',
    label: 'Client graph',
    tech: 'JavaScript',
    language: 'javascript',
    file: 'client-graph.js',
    what: 'The script that starts the page. The standard one loads Breadboard’s components and shows the Client HTML; change it only to build a page of your own.',
  },
  {
    key: 'style',
    label: 'Style',
    tech: 'CSS',
    language: 'css',
    file: 'style.css',
    what: 'CSS for the participant page. It applies to participants only, never to this console. On the new page (class="bb-app"), change the theme through its variables, e.g. .bb-app { --bb-accent: #c2410c; --bb-text: #1f2937; } (also --bb-bg, --bb-surface, --bb-muted, --bb-radius, --bb-font), or start selectors with .bb-app .bb-prose to restyle the experiment\'s text, e.g. .bb-app .bb-prose h2 { color: #c2410c; }.',
  },
]
export const partInfo = (k: PartKey) => PARTS.find(p => p.key === k)!
export const isPartKey = (v: unknown): v is PartKey => PARTS.some(p => p.key === v)

export interface PartState {
  /** idle = never asked for; error = the load failed, so saving is off (the old console saved "" then) */
  status: 'idle' | 'loading' | 'ready' | 'error'
  error: string
  /** the server's text as last loaded or saved */
  original: string
  /** the editor's text */
  value: string
  saving: boolean
  /** the text a save in progress is sending */
  pending: string | null
  saveError: string
  /** the server's copy changed elsewhere while this one has unsaved edits */
  remoteChanged: boolean
  savedAt: number
}

export function newPart (): PartState {
  return { status: 'idle', error: '', original: '', value: '', saving: false, pending: null, saveError: '', remoteChanged: false, savedAt: 0 }
}

/** The editor turns \r\n into \n; that alone is not an edit. */
export const normalize = (s: string) => s.replace(/\r\n?/g, '\n')
export const isDirty = (p: PartState) => p.status === 'ready' && normalize(p.value) !== normalize(p.original)
/** Only a part that loaded can be saved: a failed load must never overwrite the stored page. */
export const canSave = (p: PartState) => p.status === 'ready' && !p.saving

/** Java's String.hashCode(), which the server sends as clientHtmlHash / clientGraphHash. */
export function javaHashCode (s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h
}

/**
 * Whether the server's copy differs from what this part last loaded or is saving. `server` is the
 * hash for HTML and graph (the bodies aren't in User JSON) and the text itself for the style.
 */
export function serverDiffers (key: PartKey, p: PartState, server: number | string | null | undefined): boolean {
  if (server == null) return false
  const same = (text: string) => key === 'style'
    ? normalize(String(server)) === normalize(text)
    : javaHashCode(text) === server
  if (same(p.original)) return false
  if (p.pending != null && same(p.pending)) return false
  return true
}

/** The text in a GET /customize answer; null when the answer has none. */
export function readPart (key: PartKey, body: unknown): string | null {
  if (!body || typeof body !== 'object') return null
  const v = (body as Record<string, unknown>)[key]
  if (v === null) return '' // never set: an empty page
  return typeof v === 'string' ? v : null
}

/** A page made before BBGame (the SVGGraph layout), which can move to the new one. */
export function isLegacyPage (html: string): boolean {
  return /<SVGGraph\b/i.test(html) && !/<BB(Game|Network)\b/.test(html)
}

/** A client graph that doesn't render the Client HTML the standard way. */
export function isCustomClientGraph (js: string): boolean {
  return !/createDefaultVue\s*\(/.test(js)
}
