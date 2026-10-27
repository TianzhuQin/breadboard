import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'

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
    sourcemap: mode === 'development',
    minify: mode !== 'development',
    target: 'es2020',
    rollupOptions: {
      input: fileURLToPath(new URL('./src/main.ts', import.meta.url)),
      output: {
        entryFileNames: 'admin.js',
        // Shared code goes in hashed chunks, never in admin.js itself: the page loads admin.js with
        // a ?v= query, so a chunk importing it would load (and start) a second copy of the app.
        manualChunks (id) {
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
