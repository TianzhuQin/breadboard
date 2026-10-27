import { describe, it, expect } from 'vitest'
import { themeColors, vuetifyOptionsFor } from '../src/theme/vuetify'

// client.ts starts the client when it is imported, so the helper it uses is tested on its own.
describe('vuetifyOptionsFor', () => {
  it('gives a template without bb-app exactly the options from before the theme', () => {
    expect(vuetifyOptionsFor('<v-app><BBMain :player="player" /></v-app>')).toStrictEqual({ icons: { iconfont: 'mdi' } })
    expect(vuetifyOptionsFor('<div class="bb-application my-bb-app"></div>')).toStrictEqual({ icons: { iconfont: 'mdi' } })
  })

  it('uses the participant theme colours when the template has class="bb-app"', () => {
    for (const template of ['<v-app class="bb-app">', '<v-app id="app" class="pa-0 bb-app wide">', "<v-app class='bb-app'>"]) {
      const options = vuetifyOptionsFor(template)
      expect(options.icons).toEqual({ iconfont: 'mdi' })
      expect(options.theme.themes.light).toEqual(themeColors)
      expect(options.theme.themes.light.primary).toBe('#4f46e5')
    }
  })

  it('lets the caller\'s options win', () => {
    const caller = {
      icons: { iconfont: 'mdiSvg' },
      theme: { dark: false, themes: { light: { primary: '#000000' }, dark: { primary: '#ffffff' } } },
      rtl: true,
    }
    const options = vuetifyOptionsFor('<v-app class="bb-app">', caller)
    expect(options.icons).toEqual({ iconfont: 'mdiSvg' })
    expect(options.rtl).toBe(true)
    expect(options.theme.dark).toBe(false)
    expect(options.theme.themes.light).toEqual(Object.assign({}, themeColors, { primary: '#000000' }))
    expect(options.theme.themes.dark).toEqual({ primary: '#ffffff' })
    // the caller's object is left alone
    expect(caller.theme.themes.light).toEqual({ primary: '#000000' })
  })

  it('passes the caller\'s options through without the theme for a template without bb-app', () => {
    const options = vuetifyOptionsFor('<v-app>', { theme: { themes: { light: { primary: '#000000' } } } })
    expect(options).toStrictEqual({ icons: { iconfont: 'mdi' }, theme: { themes: { light: { primary: '#000000' } } } })
  })
})
