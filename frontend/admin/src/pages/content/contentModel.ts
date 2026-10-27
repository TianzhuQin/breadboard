import type { Content, Language } from '@/store/types'
import { hasText, isOldPlaceholder, sameText } from './contentHtml'

/**
 * Bookkeeping for the Content page: the texts as the server has them, plus the researcher's unsaved
 * edits ("drafts") on top. Drafts hold whole values, never diffs, so a fresh copy from the server
 * can arrive at any time: an edit that now matches the server simply stops counting as a change.
 *
 * The rules the old console got wrong (INVENTORY §3.2.4):
 * - Looking at a language a text has no translation for changes nothing. There is no placeholder;
 *   the translation exists only once something is typed, and typing it away again undoes that.
 * - Only translations that changed are sent when saving.
 */

export interface DraftText { language: Language, html: string }
export interface Draft {
  /** A text created here and never saved. */
  isNew?: boolean
  /** The new name; absent when unchanged. */
  name?: string
  /** Edited translations by language key; absent languages are unchanged. */
  html: Record<string, DraftText>
  /** The text's name when the draft was started (for messages if it disappears). */
  label: string
}
export type Drafts = Record<string, Draft>

export interface TranslationView {
  language: Language
  /** The server's id for it; null when it doesn't exist there yet (or in file mode). */
  id: number | null
  /** The saved text; null when the server has no translation in this language. */
  saved: string | null
  /** What the editor shows: the draft if any, else the saved text. */
  text: string
  modified: boolean
}

export interface ContentItem {
  /** Stable key: "id:12" saved, "new:3" unsaved, "file:Name" in file mode. */
  key: string
  id: number | null
  name: string
  savedName: string | null
  isNew: boolean
  renamed: boolean
  /** Every language that has a saved or drafted translation, by language key. */
  translations: Record<string, TranslationView>
  modified: boolean
}

/** missing: no translation · empty: it shows nothing · placeholder: the old editor's filler text. */
export type TranslationStatus = 'present' | 'missing' | 'empty' | 'placeholder'

export function langKey (l: Pick<Language, 'id' | 'code'>): string {
  return l.id != null ? `#${l.id}` : `@${l.code}`
}

export function itemKey (c: Pick<Content, 'id' | 'name'>): string {
  return c.id != null ? `id:${c.id}` : `file:${c.name}`
}

let newSeq = 0
export function newKey (): string {
  newSeq += 1
  return `new:${Date.now().toString(36)}${newSeq}`
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
export function byName<T extends { name: string }> (a: T, b: T): number {
  return collator.compare(a.name, b.name)
}

/**
 * In file mode every Content/<language>/<name>.html file arrives as its own content item (DL-17),
 * so one text in two languages shows up twice. Read-only there, so it is safe to show them as one.
 */
export function mergeByName (content: Content[]): Content[] {
  const byNameMap = new Map<string, Content>()
  for (const c of content) {
    const hit = byNameMap.get(c.name)
    if (!hit) {
      byNameMap.set(c.name, { ...c, translations: [...(c.translations || [])] })
    } else {
      for (const t of c.translations || []) {
        if (t.language && !hit.translations.some(x => x.language && langKey(x.language) === langKey(t.language))) hit.translations.push(t)
      }
    }
  }
  return [...byNameMap.values()]
}

/** The server's texts with the drafts applied, sorted by name. */
export function buildItems (server: Content[], drafts: Drafts, fileMode = false): ContentItem[] {
  const list = fileMode ? mergeByName(server) : server
  const items: ContentItem[] = []
  for (const c of list) {
    const key = itemKey(c)
    const d = drafts[key]
    const translations: Record<string, TranslationView> = {}
    for (const t of c.translations || []) {
      if (!t.language) continue
      const k = langKey(t.language)
      // a duplicate translation in one language: scripts get one of them, show the first
      if (translations[k]) continue
      const saved = t.html ?? ''
      const draft = d?.html[k]
      translations[k] = {
        language: t.language,
        id: t.id ?? null,
        saved,
        text: draft ? draft.html : saved,
        modified: !!draft && !sameText(draft.html, saved),
      }
    }
    if (d) {
      for (const [k, dt] of Object.entries(d.html)) {
        if (translations[k]) continue
        translations[k] = { language: dt.language, id: null, saved: null, text: dt.html, modified: dt.html.trim() !== '' }
      }
    }
    const renamed = d?.name !== undefined && d.name !== c.name
    items.push({
      key,
      id: c.id ?? null,
      name: renamed ? d!.name! : c.name,
      savedName: c.name,
      isNew: false,
      renamed,
      translations,
      modified: renamed || Object.values(translations).some(t => t.modified),
    })
  }
  for (const [key, d] of Object.entries(drafts)) {
    if (!d.isNew) continue
    const translations: Record<string, TranslationView> = {}
    for (const [k, dt] of Object.entries(d.html)) {
      translations[k] = { language: dt.language, id: null, saved: null, text: dt.html, modified: dt.html.trim() !== '' }
    }
    items.push({ key, id: null, name: d.name ?? '', savedName: null, isNew: true, renamed: false, translations, modified: true })
  }
  return items.sort((a, b) => byName(a, b) || (a.key < b.key ? -1 : 1))
}

export function translationOf (item: ContentItem, lang: Language): TranslationView | undefined {
  return item.translations[langKey(lang)]
}

/** What the editor shows for a language: the draft, the saved text, or nothing yet. */
export function textOf (item: ContentItem, lang: Language): string {
  return translationOf(item, lang)?.text ?? ''
}

export function statusOf (item: ContentItem, lang: Language): TranslationStatus {
  const t = translationOf(item, lang)
  if (!t || (t.saved === null && t.text.trim() === '')) return 'missing'
  if (isOldPlaceholder(t.text)) return 'placeholder'
  if (!hasText(t.text)) return 'empty'
  return 'present'
}

/** Texts that have nothing to show in a language (missing, empty or the old placeholder). */
export function lacking (items: ContentItem[], lang: Language): ContentItem[] {
  return items.filter(i => statusOf(i, lang) !== 'present')
}

function tidy (drafts: Drafts, key: string) {
  const d = drafts[key]
  if (d && !d.isNew && d.name === undefined && Object.keys(d.html).length === 0) delete drafts[key]
}

/**
 * Record what the editor now holds for one language. A value equal to the saved one (or nothing at
 * all, for a translation that doesn't exist yet) removes the draft, so an editor echoing back the
 * text it was given, or the researcher typing back the original, leaves the text unmodified.
 */
export function setText (drafts: Drafts, item: ContentItem, lang: Language, value: string): void {
  const k = langKey(lang)
  const saved = item.translations[k]?.saved ?? null
  const unchanged = saved === null ? value.trim() === '' : sameText(value, saved)
  const d = drafts[item.key]
  if (unchanged) {
    if (d && d.html[k]) {
      delete d.html[k]
      tidy(drafts, item.key)
    }
    return
  }
  const target = d || (drafts[item.key] = { html: {}, label: item.name })
  target.html[k] = { language: lang, html: value }
}

export function setName (drafts: Drafts, item: ContentItem, name: string): void {
  const n = name.trim()
  const d = drafts[item.key]
  if (item.isNew) {
    if (d) d.name = n
    return
  }
  if (n === item.savedName) {
    if (d && d.name !== undefined) {
      delete d.name
      tidy(drafts, item.key)
    }
    return
  }
  const target = d || (drafts[item.key] = { html: {}, label: item.savedName ?? item.name })
  target.name = n
}

export function createDraft (drafts: Drafts, name: string): string {
  const key = newKey()
  drafts[key] = { isNew: true, name: name.trim(), html: {}, label: name.trim() }
  return key
}

/**
 * After the server's copy changed: forget edits that now match it, and drop drafts of texts that no
 * longer exist (deleted elsewhere, or replaced by turning file mode on). Returns the dropped names.
 */
export function rebase (drafts: Drafts, items: ContentItem[]): string[] {
  const byKey = new Map(items.map(i => [i.key, i]))
  const dropped: string[] = []
  for (const [key, d] of Object.entries(drafts)) {
    if (d.isNew) continue
    const item = byKey.get(key)
    if (!item) {
      dropped.push(d.name ?? d.label)
      delete drafts[key]
      continue
    }
    for (const [k, dt] of Object.entries(d.html)) {
      const saved = item.translations[k]?.saved ?? null
      if (saved === null ? dt.html.trim() === '' : sameText(dt.html, saved)) delete d.html[k]
    }
    if (d.name !== undefined && d.name === item.savedName) delete d.name
    tidy(drafts, key)
  }
  return dropped
}

// ---- saving -----------------------------------------------------------------------------------

export interface SaveBody {
  experimentId: number
  contentId: number
  name: string
  translations: { id: number, html: string, language: Language }[]
}
export interface SavePayload { url: string, body: SaveBody }

/**
 * POST /content/:id (-1 = new). Only translations that changed are sent: a new one as id -1, an
 * existing one with its id. A translation typed and then cleared again is not sent at all.
 */
export function buildSavePayload (experimentId: number, item: ContentItem): SavePayload {
  const contentId = item.isNew || item.id == null ? -1 : item.id
  const translations = Object.values(item.translations)
    .filter(t => t.modified && !(t.saved === null && t.text.trim() === ''))
    .sort((a, b) => byName(a.language, b.language))
    .map(t => ({
      id: t.saved === null || t.id == null ? -1 : t.id,
      html: t.text,
      language: { id: t.language.id, code: t.language.code, name: t.language.name },
    }))
  return {
    url: `/content/${contentId}`,
    body: { experimentId, contentId, name: item.name.trim(), translations },
  }
}

/**
 * The server saved `payload`. Forget the drafts it contained, unless the researcher kept typing
 * while it was on its way (those edits stay); a new text's leftovers move to its real key.
 * Returns the item's key from now on.
 */
export function afterSave (drafts: Drafts, item: Pick<ContentItem, 'key'>, payload: SavePayload, saved: Pick<Content, 'id' | 'name'>): string {
  const key = itemKey(saved)
  const d = drafts[item.key]
  if (!d) return key
  for (const t of payload.body.translations) {
    const k = langKey(t.language)
    if (d.html[k] && d.html[k].html === t.html) delete d.html[k]
  }
  if (d.name !== undefined && d.name.trim() === payload.body.name) delete d.name
  delete drafts[item.key]
  if (Object.keys(d.html).length || d.name !== undefined) {
    drafts[key] = { html: d.html, label: saved.name, ...(d.name !== undefined ? { name: d.name } : {}) }
  }
  return key
}

/** Replace (or add) one text in the server list after a save. */
export function upsertContent (list: Content[], previousId: number | null, saved: Content): Content[] {
  const id = previousId ?? saved.id
  const i = list.findIndex(c => c.id != null && c.id === id)
  if (i < 0) return [...list, saved]
  const next = list.slice()
  next[i] = saved
  return next
}

// ---- names ------------------------------------------------------------------------------------

const FILE_UNSAFE = /[/\\:*?"<>|]/

/**
 * Scripts fetch a text by its name (c.get("Name")), and export writes it to Content/<lang>/<name>.html,
 * so a name must be present, unique (ignoring case, as some databases do) and file-name safe.
 */
export function validateName (name: string, items: Pick<ContentItem, 'key' | 'name'>[], selfKey?: string): string | null {
  const n = name.trim()
  if (!n) return 'Give the text a name.'
  if (n.length > 200) return 'Keep the name under 200 characters.'
  if (FILE_UNSAFE.test(n)) return 'A name can’t contain / \\ : * ? " < > | because it becomes a file name when the experiment is exported.'
  const lower = n.toLocaleLowerCase()
  const clash = items.find(i => i.key !== selfKey && i.name.trim().toLocaleLowerCase() === lower)
  if (clash) return `There is already a text called “${clash.name}”. Scripts find texts by name, so each name must be unique.`
  return null
}

/**
 * Names used by more than one saved text (the old import could create these, §3.6.4). Exact
 * matches only: whether "Intro" and "intro" clash depends on the database, so new names avoid both
 * (validateName) but existing ones are only flagged when scripts surely can't tell them apart.
 */
export function duplicateNames (items: ContentItem[]): Set<string> {
  const seen = new Map<string, number>()
  for (const i of items) {
    const k = i.name.trim()
    seen.set(k, (seen.get(k) || 0) + 1)
  }
  return new Set([...seen].filter(([, n]) => n > 1).map(([k]) => k))
}

// ---- languages --------------------------------------------------------------------------------

/**
 * The experiment's languages, plus any language some text has a translation in that is not one of
 * them (a removed language keeps its translations; file mode reads languages from folder names).
 */
export function languageSets (experimentLanguages: Language[], server: Content[]): { languages: Language[], extra: Language[] } {
  const own = new Map<string, Language>()
  for (const l of experimentLanguages || []) own.set(langKey(l), l)
  const extra = new Map<string, Language>()
  for (const c of server || []) {
    for (const t of c.translations || []) {
      if (!t.language) continue
      const k = langKey(t.language)
      if (!own.has(k) && !extra.has(k)) extra.set(k, t.language)
    }
  }
  return { languages: [...own.values()], extra: [...extra.values()].sort(byName) }
}
