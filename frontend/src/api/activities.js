import { authorizationHeader } from '../auth/session.js'

// The activity feed in user-service (ADR-0021): changes CRM users made,
// newest first, never reads. Who the caller is comes from the access token:
// an agent always gets only their own activities, and an admin gets
// everyone's, or one user's when agentId names their Cognito sub.
const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

// Returns { content, nextCursor }. Pass nextCursor back as cursor for the next
// page; it is null on the last page. from and to are Dates; from is inclusive,
// to exclusive.
export async function listActivities({ agentId, from, to, size = 20, cursor } = {}) {
  const params = new URLSearchParams({ size: String(size) })
  if (agentId) params.set('agentId', agentId)
  if (from) params.set('from', isoSeconds(from))
  if (to) params.set('to', isoSeconds(to))
  if (cursor) params.set('cursor', cursor)

  const response = await fetch(`${BASE_URL}/api/activities?${params}`, {
    headers: { Accept: 'application/json', ...(await authorizationHeader()) },
  })
  if (!response.ok) {
    // user-service answers errors with { status, error, message }.
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

function isoSeconds(date) {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}
