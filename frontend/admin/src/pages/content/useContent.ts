import { computed, inject, reactive, ref, shallowRef, watch, type InjectionKey } from 'vue'
import { admin, actions, experiments } from '@/store/admin'
import { http, HttpError } from '@/lib/http'
import { confirm, notify } from '@/ui'
import type { Content, Language } from '@/store/types'
import {
  afterSave, buildItems, buildSavePayload, byName, createDraft, duplicateNames, lacking, langKey, languageSets,
  rebase, setName, setText, statusOf, textOf, upsertContent, validateName,
  type ContentItem, type Drafts,
} from './contentModel'

/** Turn a failed request into a sentence a researcher can act on. */
export function explain (e: unknown): string {
  if (e instanceof HttpError) {
    const m = (e.message || '').trim()
    if (/invalid content id/i.test(m)) return 'This text no longer exists on the server (it may have been deleted in another window). Copy what you wrote, then reload the page.'
    if (/invalid translation id/i.test(m)) return 'One of its translations no longer exists on the server. Copy what you wrote, then reload the page.'
    if (/invalid experiment/i.test(m)) return 'The experiment no longer exists on the server.'
    if (e.status === 401) return 'You were signed out. Sign in again and retry.'
    if (e.status >= 500) return `The server ran into a problem (error ${e.status}). ${m}`.trim()
    return m || `The server refused the change (error ${e.status}).`
  }
  if (e instanceof TypeError) return 'The server could not be reached. Check the connection and try again.'
  return (e as any)?.message || String(e)
}

// ---- unsaved edits, kept outside the page ----
/**
 * Unsaved edits per experiment. They live here, not in the page, so they survive the page being
 * taken down without a route change: a 401 or Sign out swaps the whole console for the sign-in
 * form, and after signing in again the page comes back with them. They belong to one account:
 * signing in as someone else drops them. Leaving the page with "Leave without saving" drops them too.
 */
const keptDrafts = reactive<Record<number, Drafts>>({})
let keptFor: string | undefined
function claimKeptDrafts () {
  if (admin.email === keptFor) return
  if (keptFor !== undefined) dropKeptDrafts()
  keptFor = admin.email
}
/** Throw away every unsaved edit (all experiments). */
export function dropKeptDrafts () {
  for (const id of Object.keys(keptDrafts)) delete keptDrafts[Number(id)]
}
watch(() => admin.email, claimKeptDrafts)

/** How many texts have unsaved edits, in any experiment, whether or not the page is open. */
export const unsavedContentCount = computed(() => Object.values(keptDrafts).reduce((n, d) => n + Object.keys(d).length, 0))

function readPref (key: string): string | null {
  try { return localStorage.getItem(key) } catch (e) { return null }
}
function writePref (key: string, value: string) {
  try { localStorage.setItem(key, value) } catch (e) { /* private mode */ }
}

/**
 * The Content page's state: the server's texts, the researcher's unsaved edits per experiment
 * (switching experiments keeps them), the selected text and language, and the actions.
 */
export function useContent () {
  const expId = computed(() => admin.experiment?.id ?? null)
  // read live, so a file-mode toggle locks or unlocks everything at once (§3.4.15)
  const readOnly = computed(() => !!admin.experiment?.fileMode)

  // ---- the server's copy ----
  const server = shallowRef<Content[]>([])
  /** Bumped on every change to `server`, so a slow GET can't overwrite something newer. */
  let version = 0
  const loadError = ref<string | null>(null)
  const loading = ref(false)

  watch(() => admin.experiment?.content, (c) => {
    server.value = Array.isArray(c) ? c : []
    version++
  }, { immediate: true })

  async function fetchContent () {
    const id = expId.value
    if (id == null || readOnly.value) { loadError.value = null; return }
    const v = version
    loading.value = true
    try {
      const res = await http.get<{ content: Content[] }>(`/content/${id}`)
      if (expId.value === id && v === version && res && Array.isArray(res.content)) {
        server.value = res.content
        version++
      }
      loadError.value = null
    } catch (e) {
      if (expId.value === id) loadError.value = explain(e)
    } finally {
      loading.value = false
    }
  }

  // ---- drafts ----
  claimKeptDrafts()
  const draftsByExp = keptDrafts
  const EMPTY: Drafts = {}
  const drafts = computed<Drafts>(() => (expId.value != null && draftsByExp[expId.value]) || EMPTY)
  function ensureDrafts (): Drafts | null {
    const id = expId.value
    if (id == null) return null
    if (!draftsByExp[id]) draftsByExp[id] = {}
    return draftsByExp[id]
  }

  const items = computed(() => buildItems(server.value, drafts.value, readOnly.value))
  const dirty = computed(() => items.value.filter(i => i.modified))
  const duplicates = computed(() => duplicateNames(items.value.filter(i => !i.isNew && !i.renamed)))

  watch(server, () => {
    const id = expId.value
    if (id == null || !draftsByExp[id]) return
    const dropped = rebase(draftsByExp[id], buildItems(server.value, {}, readOnly.value))
    if (dropped.length) {
      notify.warning(
        dropped.length === 1 ? `Unsaved changes to “${dropped[0]}” were dropped` : `Unsaved changes to ${dropped.length} texts were dropped`,
        'The text is no longer on the server: it was deleted elsewhere, or file mode replaced the content.',
      )
    }
  })

  /** Unsaved texts in other experiments (kept while this page is open). */
  const otherDirty = computed(() => {
    const out: { id: number, name: string, count: number }[] = []
    for (const [id, d] of Object.entries(draftsByExp)) {
      const n = Object.keys(d).length
      if (!n || Number(id) === expId.value) continue
      const exp = experiments.value.find(e => e.id === Number(id))
      if (exp) out.push({ id: exp.id, name: exp.name, count: n })
    }
    return out
  })
  const totalDirty = computed(() => dirty.value.length + otherDirty.value.reduce((s, x) => s + x.count, 0))

  // ---- keys that survive a new text's first save ----
  const rekeyed: Record<string, string> = {}
  const aliases: Record<string, string> = {}
  function resolveKey (key: string): string {
    let k = key
    for (let i = 0; i < 10 && rekeyed[k]; i++) k = rekeyed[k]
    return k
  }
  /** Identifies one translation in the editors; unchanged when a new text gets its server id. */
  function docKey (item: ContentItem, lang: Language): string {
    return `${aliases[item.key] ?? item.key}|${langKey(lang)}`
  }

  // ---- languages ----
  const langOverride = shallowRef<Language[] | null>(null)
  // the server's list wins as soon as it arrives
  watch(() => admin.experiment?.languages, () => { langOverride.value = null })
  const sets = computed(() => languageSets(langOverride.value ?? admin.experiment?.languages ?? [], server.value))
  const languages = computed(() => sets.value.languages)
  const extraLanguages = computed(() => sets.value.extra)
  const allLanguages = computed(() => [...languages.value, ...extraLanguages.value])

  const activeKey = ref<string | null>(null)
  const activeLanguage = computed<Language | null>(() => {
    const all = allLanguages.value
    const hit = all.find(l => langKey(l) === activeKey.value)
    if (hit) return hit
    const def = admin.user?.defaultLanguage
    return (def && all.find(l => langKey(l) === langKey(def))) || all[0] || null
  })
  function setActiveLanguage (l: Language) {
    activeKey.value = langKey(l)
    if (expId.value != null) writePref(`bb-content-lang:${expId.value}`, activeKey.value)
  }
  const lackingByLang = computed(() => {
    const out: Record<string, ContentItem[]> = {}
    for (const l of allLanguages.value) out[langKey(l)] = lacking(items.value, l)
    return out
  })

  const catalog = ref<Language[] | null>(null)
  const catalogError = ref<string | null>(null)
  const catalogLoading = ref(false)
  async function loadCatalog (force = false) {
    if (catalogLoading.value || (catalog.value && !force)) return
    catalogLoading.value = true
    catalogError.value = null
    try {
      const res = await http.get<{ languages: Language[] }>('/languages')
      catalog.value = (res?.languages || []).filter(l => l && l.name).slice().sort(byName)
    } catch (e) {
      catalogError.value = explain(e)
    } finally {
      catalogLoading.value = false
    }
  }

  const langBusy = ref<string | null>(null)
  async function addLanguage (l: Language): Promise<boolean> {
    const id = expId.value
    if (id == null || readOnly.value) return false
    langBusy.value = langKey(l)
    try {
      const res = await http.post<Language>('/languages', { experimentId: id, languageId: l.id })
      const lang = res && typeof res === 'object' && res.id != null ? res : l
      if (expId.value === id) {
        langOverride.value = [...languages.value.filter(x => langKey(x) !== langKey(lang)), lang]
        setActiveLanguage(lang)
      }
      const need = items.value.filter(i => statusOf(i, lang) !== 'present').length
      notify.success(`Added ${lang.name}`, need ? `${need} ${need === 1 ? 'text needs' : 'texts need'} a ${lang.name} version; they are marked in the list.` : undefined)
      actions.refresh()
      return true
    } catch (e) {
      notify.error(`Couldn’t add ${l.name}`, explain(e))
      return false
    } finally {
      langBusy.value = null
    }
  }

  /** What removing a language does, in words (it never deletes translations). */
  function removalConsequence (l: Language): string {
    const written = items.value.filter(i => (i.translations[langKey(l)]?.saved ?? null) !== null).length
    return written
      ? `This takes effect right away. The ${written} ${l.name} ${written === 1 ? 'translation' : 'translations'} already written ${written === 1 ? 'is' : 'are'} not deleted: scripts can still read them, and they stay listed here (greyed out) until you add ${l.name} again.`
      : `This takes effect right away. No text has a ${l.name} version yet, so nothing is lost.`
  }

  /** `confirmed`: the caller already asked (the Languages dialog asks inline). */
  async function removeLanguage (l: Language, opts: { confirmed?: boolean } = {}): Promise<boolean> {
    const id = expId.value
    if (id == null || readOnly.value) return false
    const ok = opts.confirmed || await confirm({
      title: `Remove ${l.name} from this experiment?`,
      message: removalConsequence(l),
      confirmLabel: `Remove ${l.name}`,
      danger: true,
    })
    if (!ok) return false
    langBusy.value = langKey(l)
    try {
      await http.post('/languages/remove', { experimentId: id, languageId: l.id })
      if (expId.value === id) {
        langOverride.value = languages.value.filter(x => langKey(x) !== langKey(l))
        if (activeKey.value === langKey(l)) {
          const next = langOverride.value[0]
          activeKey.value = next ? langKey(next) : null
        }
      }
      notify.success(`Removed ${l.name}`)
      actions.refresh()
      return true
    } catch (e) {
      notify.error(`Couldn’t remove ${l.name}`, explain(e))
      return false
    } finally {
      langBusy.value = null
    }
  }

  // ---- selection ----
  const selectedKey = ref<string | null>(null)
  const selected = computed(() => items.value.find(i => i.key === selectedKey.value) ?? null)
  const selectionByExp: Record<number, string> = {}
  let lastIndex = 0
  watch(items, (list) => {
    const i = list.findIndex(x => x.key === selectedKey.value)
    if (i >= 0) { lastIndex = i; return }
    const k = selectedKey.value && rekeyed[selectedKey.value] ? resolveKey(selectedKey.value) : null
    if (k && list.some(x => x.key === k)) { selectedKey.value = k; return }
    // the selected text went away: stay at the same place in the list
    selectedKey.value = list.length ? list[Math.min(lastIndex, list.length - 1)].key : null
  })
  watch(selectedKey, (k) => { if (k && expId.value != null) selectionByExp[expId.value] = k })

  watch(expId, (id) => {
    if (id == null) return
    selectedKey.value = (selectionByExp[id] && items.value.some(i => i.key === selectionByExp[id])) ? selectionByExp[id] : (items.value[0]?.key ?? null)
    lastIndex = 0
    activeKey.value = readPref(`bb-content-lang:${id}`)
    loadError.value = null
    fetchContent()
  }, { immediate: true })

  function select (key: string) { selectedKey.value = key }

  // ---- editing ----
  function updateText (item: ContentItem, lang: Language, value: string) {
    if (readOnly.value) return
    const d = ensureDrafts()
    if (d) setText(d, item, lang, value)
  }
  /** An editor reporting text for the translation it was showing (it may no longer be selected). */
  function updateDoc (key: string, value: string) {
    const cut = key.lastIndexOf('|')
    const stable = key.slice(0, cut)
    const lk = key.slice(cut + 1)
    const current = resolveKey(stable)
    const item = items.value.find(i => i.key === current || (aliases[i.key] ?? i.key) === stable)
    const lang = allLanguages.value.find(l => langKey(l) === lk) ?? (item && item.translations[lk]?.language)
    if (item && lang) updateText(item, lang, value)
  }
  function rename (item: ContentItem, name: string): string | null {
    if (readOnly.value) return 'File mode is on, so nothing can be renamed here.'
    const err = validateName(name, items.value, item.key)
    if (err) return err
    const d = ensureDrafts()
    if (d) setName(d, item, name)
    return null
  }
  function create (name: string): string | null {
    if (readOnly.value) return null
    const d = ensureDrafts()
    if (!d) return null
    const key = createDraft(d, name)
    selectedKey.value = key
    return key
  }
  function copyFrom (item: ContentItem, from: Language, to: Language) {
    updateText(item, to, textOf(item, from))
  }

  async function discard (item: ContentItem) {
    const d = drafts.value[item.key]
    if (!d) return
    const ok = await confirm({
      title: item.isNew ? `Throw away “${item.name}”?` : `Discard changes to “${item.savedName}”?`,
      message: item.isNew
        ? 'This new text was never saved. Everything typed into it is thrown away.'
        : 'Your unsaved edits to this text are thrown away and the saved version comes back.',
      confirmLabel: item.isNew ? 'Throw away' : 'Discard changes',
      danger: true,
    })
    if (!ok) return
    delete drafts.value[item.key]
    delete errors[item.key]
  }

  // ---- saving and deleting ----
  const saving = reactive(new Set<string>())
  const deleting = reactive(new Set<string>())
  const errors = reactive<Record<string, string>>({})

  async function save (keys?: string[]): Promise<boolean> {
    if (readOnly.value) {
      notify.warning('Nothing can be saved in file mode', 'Turn file mode off on the Experiments page to edit content here.')
      return false
    }
    const id = expId.value
    if (id == null) return false
    const targets = items.value.filter(i => i.modified && (!keys || keys.includes(i.key)) && !saving.has(i.key))
    if (!targets.length) {
      if (!saving.size) notify.info('Everything is saved')
      return true
    }
    for (const it of targets) {
      if (!it.isNew && !it.renamed) continue
      const err = validateName(it.name, items.value, it.key)
      if (err) {
        selectedKey.value = it.key
        errors[it.key] = err
        notify.error(`“${it.name || 'Untitled'}” can’t be saved yet`, err)
        return false
      }
    }
    const done: string[] = []
    const failed: { name: string, message: string }[] = []
    for (const it of targets) {
      const payload = buildSavePayload(id, it)
      saving.add(it.key)
      try {
        const res = await http.post<Content>(payload.url, payload.body)
        if (!res || typeof res !== 'object' || res.id == null) throw new Error('The server answered without the saved text.')
        const key = afterSave(draftsByExp[id] || {}, it, payload, res)
        if (key !== it.key) {
          rekeyed[it.key] = key
          aliases[key] = aliases[it.key] ?? it.key
          if (selectedKey.value === it.key) selectedKey.value = key
        }
        if (expId.value === id) {
          server.value = upsertContent(server.value, it.id, res)
          version++
        }
        delete errors[it.key]
        delete errors[key]
        done.push(res.name)
      } catch (e) {
        errors[it.key] = explain(e)
        failed.push({ name: it.name || 'Untitled', message: errors[it.key] })
      } finally {
        saving.delete(it.key)
      }
    }
    // the server doesn't push content changes made over HTTP; ask for the new picture
    if (done.length) actions.refresh()
    if (!failed.length) {
      notify.success(done.length === 1 ? `Saved “${done[0]}”` : `Saved ${done.length} texts`, 'Scripts get the new text from their next c.get().')
    } else if (failed.length === 1 && targets.length === 1) {
      notify.error(`Couldn’t save “${failed[0].name}”`, failed[0].message)
    } else {
      notify.error(`${failed.length} of ${targets.length} texts weren’t saved`, `${failed.map(f => `“${f.name}”`).join(', ')}: the reason is shown on each.`)
    }
    return !failed.length
  }

  async function remove (item: ContentItem): Promise<boolean> {
    if (readOnly.value) return false
    const id = expId.value
    if (id == null) return false
    if (item.isNew) {
      const typed = Object.values(item.translations).some(t => t.text.trim())
      if (typed) {
        const ok = await confirm({
          title: `Throw away “${item.name}”?`,
          message: 'This new text was never saved. Everything typed into it is thrown away.',
          confirmLabel: 'Throw away',
          danger: true,
        })
        if (!ok) return false
      }
      delete draftsByExp[id]?.[item.key]
      delete errors[item.key]
      return true
    }
    const name = item.savedName ?? item.name
    const langs = Object.values(item.translations).filter(t => t.saved !== null).map(t => t.language.name)
    const what = langs.length
      ? `“${name}” and its ${langs.length === 1 ? `${langs[0]} text` : `${langs.length} translations (${langs.join(', ')})`}`
      : `“${name}”`
    const ok = await confirm({
      title: `Delete “${name}”?`,
      message: `This deletes ${what} for good. Scripts that call c.get("${name}") will get an empty text.`
        + (item.modified ? '\n\nIts unsaved changes are thrown away too.' : ''),
      confirmLabel: 'Delete text',
      danger: true,
      typeToConfirm: name.trim() || undefined,
    })
    if (!ok) return false
    deleting.add(item.key)
    try {
      await http.del(`/content/${item.id}`)
      if (expId.value === id) {
        server.value = server.value.filter(c => c.id !== item.id)
        version++
      }
      delete draftsByExp[id]?.[item.key]
      delete errors[item.key]
      notify.success(`Deleted “${name}”`)
      actions.refresh()
      return true
    } catch (e) {
      notify.error(`Couldn’t delete “${name}”`, explain(e))
      return false
    } finally {
      deleting.delete(item.key)
    }
  }

  return {
    expId, readOnly, server, loading, loadError, fetchContent,
    drafts, items, dirty, duplicates, otherDirty, totalDirty,
    languages, extraLanguages, allLanguages, activeLanguage, setActiveLanguage, lackingByLang,
    catalog, catalogError, catalogLoading, loadCatalog, langBusy, addLanguage, removeLanguage, removalConsequence,
    selectedKey, selected, select, docKey, updateText, updateDoc, rename, create, copyFrom, discard,
    saving, deleting, errors, save, remove,
  }
}

export type ContentState = ReturnType<typeof useContent>
export const CONTENT: InjectionKey<ContentState> = Symbol('content')
export function useContentState (): ContentState {
  const s = inject(CONTENT)
  if (!s) throw new Error('useContentState() outside the Content page')
  return s
}
