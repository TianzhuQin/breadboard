import { loadAllVueComponents } from './util'
import 'vuetify/dist/vuetify.min.css'
import '@mdi/font/css/materialdesignicons.css'
import 'typeface-roboto/index.css'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import './theme/bb-theme.css'
import Vue from 'vue'

loadAllVueComponents(Vue, require.context('./components', true, /\.vue$/))
