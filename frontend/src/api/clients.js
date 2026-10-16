import { authorizationHeader } from '../auth/session.js'

// client-service (Feature 2). Identity documents are evidence for a manual
// verification: uploading one never verifies the client (ADR-0041).
const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

// client-service answers errors with a plain-text message.
async function send(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: { ...(await authorizationHeader()), ...options.headers },
  })
  if (!response.ok) {
    let message = ''
    try {
      message = (await response.text()).trim()
    } catch {
      // No readable body.
    }
    const error = new Error(message || `Request failed (${response.status})`)
    error.status = response.status
    throw error
  }
  return response
}

async function sendJson(path, { body, ...options } = {}) {
  const headers = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await send(path, {
    ...options,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  return response.json()
}

function clientPath(clientId) {
  return `/api/clients/${encodeURIComponent(clientId)}`
}

// Returns { client_id }. The creating agent comes from the access token.
export function createClient(client) {
  return sendJson('/api/clients', { method: 'POST', body: client })
}

export function getClient(clientId) {
  return sendJson(clientPath(clientId))
}

// Clients newest first: an agent's own, or every client for an admin
// (ADR-0043). Returns { content, page, size, totalElements, totalPages }.
export function listClients({ page = 0, size = 20 } = {}) {
  return sendJson(`/api/clients?page=${page}&size=${size}`)
}

// Sends every editable field; the profile is replaced, not merged.
export function updateClient(clientId, client) {
  return sendJson(clientPath(clientId), { method: 'PUT', body: client })
}

// A soft delete (ADR-0036). 409 while the client still has accounts.
export function deleteClient(clientId) {
  return sendJson(clientPath(clientId), { method: 'DELETE' })
}

// The client's documents, newest first, without their content.
export function listDocuments(clientId) {
  return sendJson(`${clientPath(clientId)}/documents`)
}

// Sends the file as the multipart field "file". The browser sets the
// multipart Content-Type with its boundary.
export async function uploadDocument(clientId, file) {
  const form = new FormData()
  form.append('file', file)
  const response = await send(`${clientPath(clientId)}/documents`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: form,
  })
  return response.json()
}

// The document's content, for viewing in the page.
export async function fetchDocument(clientId, documentId) {
  const response = await send(`${clientPath(clientId)}/documents/${encodeURIComponent(documentId)}`)
  return response.blob()
}

// Records that the signed-in user checked these documents and verified the
// client.
export function verifyClient(clientId, documentIds) {
  return sendJson(`${clientPath(clientId)}/verify`, { method: 'POST', body: { document_ids: documentIds } })
}
