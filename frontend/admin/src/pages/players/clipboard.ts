import { notify } from '@/ui'

/** Copy text and say so (or say why not). */
export async function copyText (text: string, what = 'Copied') {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      // http on a lab server: the old way still works
      const ta = document.createElement('textarea')
      ta.value = text
      ta.setAttribute('readonly', '')
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      if (!ok) throw new Error('The browser did not allow copying.')
    }
    notify.success(what)
  } catch (e: any) {
    notify.error('Couldn\'t copy', e?.message || 'The browser did not allow copying. Select the text and copy it by hand.')
  }
}
