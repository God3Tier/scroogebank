import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The first matching rule wins. Locally, user-service listens on 8081
    // (sign-out calls /api/users/me/logout; the dashboards' activity panels
    // call /api/activities) and client-profile-management-service on 8080.
    proxy: {
      '/api/users': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/activities': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    css: true,
  },
})
