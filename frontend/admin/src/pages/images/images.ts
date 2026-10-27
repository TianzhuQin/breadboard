import type { Experiment, Image } from '@/store/types'

/**
 * Helpers for the Images page (controllers/ImagesController.java).
 *
 * POST /images/upload answers 200 with `{"id","fileName"}` on success but also answers 200, with
 * the plain text "Error uploading", when it fails (no file, bad experiment id, unreadable upload).
 * The old console only recognised that exact string, so any other error body ended up in the
 * image list as if it were an image. Here an upload counts only when the answer is an object with
 * an id and a file name.
 */

export type UploadCheck =
  | { ok: true, image: { id: number, fileName: string } }
  | { ok: false, reason: string }

export function checkUploadResponse (body: unknown): UploadCheck {
  if (typeof body === 'string') {
    const text = body.trim()
    if (/^error uploading$/i.test(text)) {
      return { ok: false, reason: 'The server couldn’t store this file. Check that it is an image and try again.' }
    }
    if (!text) return { ok: false, reason: 'The server sent an empty answer, so the image was probably not saved.' }
    // an HTML error page or other text: show a readable piece of it
    const plain = text.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 160)
    return { ok: false, reason: `The server answered unexpectedly${plain ? `: “${plain}”` : ''}.` }
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return { ok: false, reason: 'The server sent an empty answer, so the image was probably not saved.' }
  }
  const b = body as Record<string, unknown>
  const id = typeof b.id === 'number' ? b.id : (typeof b.id === 'string' && /^\d+$/.test(b.id) ? Number(b.id) : NaN)
  const fileName = typeof b.fileName === 'string' ? b.fileName : ''
  if (!Number.isFinite(id) || !fileName) {
    const msg = typeof b.message === 'string' ? b.message : (typeof b.error === 'string' ? b.error : '')
    return { ok: false, reason: msg ? `The server said: ${msg}` : 'The server’s answer had no image in it, so it was probably not saved.' }
  }
  return { ok: true, image: { id, fileName } }
}

/** The file types the server serves from an experiment folder in file mode (ExperimentController). */
export const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'svg', 'webp', 'bmp']
export const ACCEPT = 'image/*'
/** images.file_name is varchar(255). */
export const MAX_NAME = 255

export function extensionOf (name: string): string {
  const m = /\.([^./]+)$/.exec(name)
  return m ? m[1].toLowerCase() : ''
}

/** Why this file can't be uploaded, or null. */
export function fileProblem (file: { name: string, type: string, size: number }): string | null {
  const isImage = (file.type || '').startsWith('image/') || IMAGE_EXTENSIONS.includes(extensionOf(file.name))
  if (!isImage) return 'This is not an image. Upload JPG, PNG, GIF, SVG or WebP files.'
  if (!file.size) return 'The file is empty.'
  if (file.name.length > MAX_NAME) return `The file name is too long (over ${MAX_NAME} characters). Rename it and try again.`
  return null
}

/** A file this big makes the participant page slow to load. */
export const LARGE_FILE = 2 * 1024 * 1024

export function formatBytes (n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`
  return `${(n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)} MB`
}

export interface ImageLinks {
  /** /images/<experimentId>/<fileName>: survives export, import and file mode */
  byName: string
  /** /images/<id>: only while the image is in this database (null in file mode) */
  byId: string | null
  /** an <img> tag with the by-name link */
  tag: string
}

/** Absolute links to an image. File names are encoded, so spaces, # and ? don't break them. */
export function imageLinks (origin: string, experimentId: number, img: Image): ImageLinks {
  const byName = `${origin}/images/${experimentId}/${encodeURIComponent(img.fileName)}`
  const byId = img.id != null ? `${origin}/images/${img.id}` : null
  return { byName, byId, tag: imgTag(byName, img.fileName) }
}

/** The path the console itself loads the picture from (same origin). */
export function imageSrc (experimentId: number, img: Image): string {
  return img.id != null ? `/images/${img.id}` : `/images/${experimentId}/${encodeURIComponent(img.fileName)}`
}

export function escapeAttr (s: string): string {
  return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** "team-photo_2.png" → "team photo 2": a starting point for the alt text. */
export function altFromName (fileName: string): string {
  return fileName.replace(/\.[^.]+$/, '').replace(/[-_]+/g, ' ').replace(/\s+/g, ' ').trim()
}

export function imgTag (src: string, fileName: string): string {
  return `<img src="${escapeAttr(src)}" alt="${escapeAttr(altFromName(fileName))}">`
}

function escapeRegExp (s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Where an image is referenced, from what the console has: steps, content and the style sheet.
 * (Client HTML and client graph aren't sent with the experiment, so they aren't checked.)
 */
export function findImageUsage (exp: Pick<Experiment, 'id' | 'steps' | 'content' | 'style'>, img: Image): string[] {
  const names = new Set([img.fileName, encodeURIComponent(img.fileName), encodeURI(img.fileName)])
  const alts = [...names].map(n => `images/${exp.id}/${escapeRegExp(n)}(?![^"'\\s)<>?#])`)
  if (img.id != null) alts.push(`images/${img.id}(?![0-9/])`)
  const re = new RegExp(alts.join('|'))
  const out: string[] = []
  for (const s of exp.steps || []) {
    if (typeof s.source === 'string' && re.test(s.source)) out.push(`the step ${s.name}`)
  }
  for (const c of exp.content || []) {
    const hit = (c.translations || []).some(t => typeof t.html === 'string' && re.test(t.html))
    if (hit) out.push(`the content “${c.name}”`)
  }
  if (typeof exp.style === 'string' && re.test(exp.style)) out.push('the participant page style')
  return out
}

/** Copy text, also where the async clipboard isn't allowed (plain http on another host). */
export async function copyText (text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (e) { /* fall back below */ }
  const active = document.activeElement as HTMLElement | null
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '0'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  } catch (e) {
    return false
  } finally {
    active?.focus?.()
  }
}

/** A clipboard image has no useful name ("image.png"); give it a dated one. */
export function pastedName (type: string, now = new Date()): string {
  const ext = (type.split('/')[1] || 'png').replace('jpeg', 'jpg').replace(/\+.*$/, '')
  const p = (n: number) => String(n).padStart(2, '0')
  return `pasted-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}.${ext}`
}
