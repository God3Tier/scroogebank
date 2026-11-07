// TEMPORARY: the usual dev server, with every API call going to a local service. Not committed.
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const alerts = { target: 'http://localhost:8084', changeOrigin: true }
const crm = { target: 'http://localhost:9100', changeOrigin: true }
const engagement = { target: 'http://localhost:8086', changeOrigin: true }

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/alerts': alerts,
      '/api/alert-portfolios': alerts,
      '/api/engagement': engagement,
      '/api': crm,
    },
  },
})
