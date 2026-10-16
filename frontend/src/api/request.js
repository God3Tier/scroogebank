import { authorizationHeader } from '../auth/session.js'

// Shared by the API modules for services that answer errors as JSON with a
// message (user-service), a detail (RFC 9457) or an error field (the
// receiver behind user-service).
const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

export async function requestJson(path, { body, ...options } = {}) {
  const headers = { Accept: 'application/json', ...(await authorizationHeader()) }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    let message = ''
    try {
      const problem = await response.json()
      message = problem.message || problem.detail || problem.error || ''
    } catch {
      // No JSON body.
    }
    const error = new Error(message || `Request failed (${response.status})`)
    error.status = response.status
    throw error
  }
  if (response.status === 204) return null
  return response.json()
}

// Builds a query string from the given values, leaving out empty ones.
export function query(values) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}
