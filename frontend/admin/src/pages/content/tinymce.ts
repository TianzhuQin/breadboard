import { PROSE_CSS } from './contentHtml'

/**
 * TinyMCE 4 (the old console's rich-text editor) is already served by the app. It is loaded only
 * when someone opens the visual editor, with a plain script tag, and lives on window.tinymce.
 */
export const TINYMCE_URL = '/assets/lib/tinymce/tinymce.min.js'

declare global {
  interface Window { tinymce?: any }
}

let loading: Promise<any> | null = null

export function loadTinymce (): Promise<any> {
  if (window.tinymce) return Promise.resolve(window.tinymce)
  if (loading) return loading
  loading = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = TINYMCE_URL
    s.async = true
    s.onload = () => {
      if (window.tinymce) resolve(window.tinymce)
      else { loading = null; reject(new Error('The editor script loaded but did not start.')) }
    }
    s.onerror = () => {
      loading = null
      s.remove()
      reject(new Error(`Could not download ${TINYMCE_URL}. Check the connection to the server.`))
    }
    document.head.appendChild(s)
  })
  return loading
}

/** The editing area looks like the participant page (always light: that is what they see). */
const CONTENT_CSS = `
html { background: #ffffff; }
body.mce-content-body { margin: 0; padding: 22px 26px 48px; background: #ffffff; font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif; -webkit-font-smoothing: antialiased; }
body.mce-content-body > :first-child { margin-top: 0; }
body.mce-content-body[contenteditable="false"] { cursor: default; }
${PROSE_CSS.replace(/\.bb-prose /g, 'body.mce-content-body ').replace(/\.bb-prose\s*\{/g, 'body.mce-content-body {')}
`

export interface TinymceOptions {
  selector: string
  readonly: boolean
  imageList: { title: string, value: string }[]
  setup: (editor: any) => void
}

export function tinymceSettings (o: TinymceOptions): Record<string, any> {
  return {
    selector: o.selector,
    theme: 'modern',
    skin: 'lightgray',
    plugins: 'image lists link charmap textcolor table paste',
    // one row at common widths: indent, highlight colour, unlink and special characters are in the
    // menus and shortcuts (Tab indents in lists, the link dialog removes a link)
    toolbar: 'styleselect | bold italic underline | bullist numlist | alignleft aligncenter alignright | forecolor | link image table | removeformat | undo redo',
    menubar: false,
    statusbar: false,
    resize: false,
    width: '100%',
    height: '100%',
    // keep every tag, attribute and URL exactly as written (scripts' {0} placeholders included)
    valid_elements: '*[*]',
    extended_valid_elements: '*[*]',
    verify_html: false,
    convert_urls: false,
    relative_urls: false,
    remove_script_host: false,
    // write characters as themselves, not as &eacute; entities (matters for every language but English)
    entity_encoding: 'raw',
    browser_spellcheck: true,
    image_list: o.imageList,
    content_style: CONTENT_CSS,
    readonly: o.readonly ? 1 : 0,
    setup: o.setup,
  }
}
