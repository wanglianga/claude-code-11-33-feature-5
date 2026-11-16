import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 8081,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/external': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
