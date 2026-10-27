// The Vuetify options createDefaultVue (client.ts) uses. They live here, not in client.ts, because
// importing client.ts starts the client.

// Vuetify colours matching the participant theme (bb-theme.css).
export const themeColors = {
  primary: '#4f46e5',
  secondary: '#0f172a',
  accent: '#6366f1',
  error: '#e11d48',
  info: '#0ea5e9',
  success: '#059669',
  warning: '#d97706',
}

// A template opts in to the participant theme with a static class="bb-app", usually on <v-app>.
const optsIn = /(?:^|\s)class\s*=\s*(?:"(?:[^"]*\s)?bb-app[\s"]|'(?:[^']*\s)?bb-app[\s'])/

/**
 * The options for `new Vuetify(...)`. A template that doesn't opt in gets the same options as
 * before the participant theme existed, so existing experiments look unchanged. One that does gets
 * themeColors as its light theme. The caller's vuetifyOptions go on top: their icons, theme and
 * light colours win.
 */
export function vuetifyOptionsFor (template: string, vuetifyOptions?: any): any {
  const options = vuetifyOptions || {}
  const result = Object.assign({}, options, {
    icons: Object.assign({ iconfont: 'mdi' }, options.icons),
  })
  if (typeof template === 'string' && optsIn.test(template)) {
    const theme = options.theme || {}
    const themes = theme.themes || {}
    result.theme = Object.assign({}, theme, {
      themes: Object.assign({}, themes, { light: Object.assign({}, themeColors, themes.light) }),
    })
  }
  return result
}
