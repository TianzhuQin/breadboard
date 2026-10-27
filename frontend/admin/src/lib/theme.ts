import { ref } from 'vue'

export type ThemeChoice = 'system' | 'light' | 'dark'
const KEY = 'bb-admin-theme'

function read (): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch (e) {
    return 'system'
  }
}

/** The researcher's light/dark choice (app/views/admin.scala.html applies it before first paint). */
export const theme = ref<ThemeChoice>(read())

export function setTheme (t: ThemeChoice) {
  theme.value = t
  try {
    if (t === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, t)
  } catch (e) { /* private mode */ }
  if (t === 'system') document.documentElement.removeAttribute('data-theme')
  else document.documentElement.setAttribute('data-theme', t)
}
