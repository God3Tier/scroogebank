import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The first matching rule wins, in the same order as the deployed ALB's
    // rules. Locally, transaction-service (the SFTP stack's receiver-api)
    // listens on 8082, user-service on 8081 (users and activities),
    // account-service on 8083, dashboard-service on 8085, engagement-service
    // on 8086 and client-profile-management-service on 8080.
    proxy: {
      '^/api/accounts/[^/]+/transactions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/users': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/activities': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/transactions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/accounts': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/api/dashboard': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
      '/api/engagement': {
        target: 'http://localhost:8086',
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
