import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// During dev, `npm run dev` serves the UI on :5173 and proxies API calls to the
// Spring backend on :8080. `npm run build` emits straight into the backend's
// static resources so the finished app is served from a single origin.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: '../server/src/main/resources/static',
    emptyOutDir: true,
  },
})
