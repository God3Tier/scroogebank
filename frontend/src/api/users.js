import { query, requestJson } from './request.js'

// user-service (Feature 1). Every call here is admin-only.

// Returns { content, page, size, totalElements, totalPages }.
export function listUsers({ role, enabled, page = 0, size = 20 } = {}) {
  return requestJson(`/api/users${query({ role, enabled, page, size })}`)
}

// user is { firstName, lastName, email, role: 'ADMIN' | 'AGENT' }. Cognito
// e-mails the new user a temporary password.
export function createUser(user) {
  return requestJson('/api/users', { method: 'POST', body: user })
}

export function updateUser(id, { firstName, lastName }) {
  return requestJson(`/api/users/${encodeURIComponent(id)}`, { method: 'PUT', body: { firstName, lastName } })
}

export function changeRole(id, role) {
  return requestJson(`/api/users/${encodeURIComponent(id)}/role`, { method: 'PATCH', body: { role } })
}

// Users are disabled, never deleted. The root admin cannot be disabled (403).
export function disableUser(id) {
  return requestJson(`/api/users/${encodeURIComponent(id)}/disable`, { method: 'PATCH' })
}

export function resetPassword(id) {
  return requestJson(`/api/users/${encodeURIComponent(id)}/reset-password`, { method: 'POST', expectJson: false })
}
