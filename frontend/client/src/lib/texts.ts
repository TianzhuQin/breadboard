import Vue from 'vue'
import { LANGUAGE_EVENT } from '@human-nature-lab/breadboard-core'
// Breadboard's own words to participants, as the server has them too (models.SystemTexts): one file
// per language, in the server's conf/i18n, so that each text is written once.
import eng from '../../../../conf/i18n/eng.json'
import zho from '../../../../conf/i18n/zho.json'

/**
 * Breadboard's own words on the participant page (its banners, waiting screen, network, notes and
 * finish screen), in the language of the session's texts: Chinese when the steps chose zho with
 * c.setDefaultLanguage, English for any other. They follow Breadboard.language (frontend/core), which
 * the server keeps up to date. An experiment's own texts (the Content page) are its own business.
 *
 * A text has {name} parameters; one written as { one, other } is chosen by the number n, as English
 * needs ("1 person", "2 people"). A text missing in Chinese is shown in English.
 */

type Text = string | { one?: string, other: string }
const TEXTS: Record<string, Record<string, Text>> = { eng: eng as any, zho: zho as any }

// The language the page's texts are in now: reactive, so what shows them follows a change.
const state = Vue.observable({ language: 'eng' })

/** The language Breadboard's own words are in for a session's texts in `code`: zho or eng. */
export function textsLanguage (code: string | null | undefined): 'zho' | 'eng' {
  const c = typeof code === 'string' ? code.trim().toLowerCase() : ''
  return c === 'zho' || c === 'chi' || c === 'cmn' || c === 'zh' || /^zh[-_]/.test(c) ? 'zho' : 'eng'
}

/** Show Breadboard's own words for a session whose texts are in `code` (e.g. 'zho'). */
export function setTextsLanguage (code: string | null | undefined) {
  const language = textsLanguage(code)
  if (state.language !== language) state.language = language
}

/** The language Breadboard's own words are in now: 'zho' or 'eng'. */
export function currentTextsLanguage (): string {
  follow()
  return state.language
}

let following: any = null
/** Follow the page's Breadboard: its language now, and LANGUAGE_EVENT. */
function follow () {
  const bb = typeof window !== 'undefined' ? (window as any).Breadboard : null
  if (!bb || bb === following || typeof bb.on !== 'function') return
  following = bb
  setTextsLanguage(bb.language)
  bb.on(LANGUAGE_EVENT, (code: any) => setTextsLanguage(code))
}

function template (key: string, params: Record<string, any>): string {
  const language = currentTextsLanguage()
  let text: Text | undefined = TEXTS[language][key]
  let from = language
  if (text === undefined) {
    text = TEXTS.eng[key]
    from = 'eng'
  }
  if (text === undefined) return key
  if (typeof text === 'object' && text !== null) {
    // English has 1 person and 2 people; Chinese has one form
    const form = from === 'eng' && Number(params.n) === 1 ? 'one' : 'other'
    return text[form] ?? text.other ?? key
  }
  return String(text)
}

/** Fill in each {name} of `params`; others are left as they are */
function fill (text: string, params: Record<string, any>): string {
  return text.replace(/\{(\w+)\}/g, (whole, name) => (name in params && params[name] != null ? String(params[name]) : whole))
}

/** The text `key`, with `params` filled in, in the language of the session's texts. */
export function t (key: string, params: Record<string, any> = {}): string {
  return fill(template(key, params), params)
}

/**
 * The text `key` in pieces, so that a page can show a parameter its own way (e.g. a number in bold):
 * the text around the parameters, and each parameter as { text, param }.
 */
export function tParts (key: string, params: Record<string, any> = {}): { text: string, param?: string }[] {
  const parts: { text: string, param?: string }[] = []
  const text = template(key, params)
  let last = 0
  text.replace(/\{(\w+)\}/g, (whole: string, name: string, at: number) => {
    if (!(name in params)) return whole
    if (at > last) parts.push({ text: text.slice(last, at) })
    parts.push({ text: params[name] == null ? '' : String(params[name]), param: name })
    last = at + whole.length
    return whole
  })
  if (last < text.length) parts.push({ text: text.slice(last) })
  return parts
}

/** What goes between two sentences in the language of the session's texts: a space in English, nothing in Chinese. */
export function sentenceGap (): string {
  const gap = TEXTS[currentTextsLanguage()]._sentenceGap
  return typeof gap === 'string' ? gap : ' '
}

/** Sentences one after the other, as the language writes them (a space between them in English). */
export function sentences (...parts: (string | null | undefined)[]): string {
  return parts.filter(p => !!p).join(sentenceGap())
}
