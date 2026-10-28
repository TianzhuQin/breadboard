import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

const EDITOR = /\/node_modules\/(\.pnpm\/[^/]+\/node_modules\/)?(@codemirror|@lezer|@marijn|codemirror|crelt|style-mod|w3c-keyname)\/|\/src\/ui\/BCodeEditor\.vue/

// The console is served by the Play app (app/views/admin.scala.html) from {assetsRoot}/admin/.
// admin.js and admin.css keep fixed names so the page can link to them (browsers revalidate them,
// see assets.cache in conf/application.conf); everything they load carries a content hash.
export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  // Relative, so it also works when breadboard.assetsRoot points the page at a CDN.
  base: './',
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    outDir: fileURLToPath(new URL('../../public/admin', import.meta.url)),
    emptyOutDir: true,
    cssCodeSplit: false,
    // BB_SOURCEMAPS=1 adds separate .map files to a production build.
    sourcemap: mode === 'development' || /^(1|true|yes)$/i.test(process.env.BB_SOURCEMAPS || ''),
    minify: mode !== 'development',
    target: 'es2020',
    // The editor chunk (CodeMirror with its HTML, CSS and JavaScript modes) is about 560 kB.
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      input: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      output: {
        entryFileNames: 'admin.js',
        // Shared code goes in hashed chunks, never in admin.js itself: the page loads admin.js with
        // a ?v= query, so a chunk importing it would load (and start) a second copy of the app.
        manualChunks (id) {
          // The code editor (CodeMirror) is only loaded by the pages that have one.
          if (EDITOR.test(id)) return 'editor'
          if (id.includes('/node_modules/')) return 'vendor'
          if (id.endsWith('/src/main.ts') || (id.includes('/src/pages/') && !id.includes('/src/pages/auth/'))) return undefined
          return 'app'
        },
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (asset) => (asset.names || []).some(n => n.endsWith('.css'))
          ? 'admin.css'
          : 'assets/[name]-[hash][extname]',
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['test/**/*.test.ts'],
  },
}))
