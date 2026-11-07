import { authorizationHeader } from '../auth/session.js'

const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

// Complete, administrator-only audit trail. This includes reads, failures,
// authentication, background work and transaction-service events.
export async function listAuditLogs({ size = 20, cursor } = {}) {
  const params = new URLSearchParams({ size: String(size) })
  if (cursor) params.set('cursor', cursor)

  const response = await fetch(`${BASE_URL}/api/audit-logs?${params}`, {
    headers: { Accept: 'application/json', ...(await authorizationHeader()) },
  })
  if (!response.ok) {
    let message = ''
    try {
      message = (await response.json()).message || ''
    } catch {
      // No JSON body.
    }
    const error = new Error(message || `Request failed (${response.status})`)
    error.status = response.status
    throw error
  }
  return response.json()
}
