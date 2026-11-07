import { authorizationHeader } from '../auth/session.js'

// Shared by the API modules for services that answer errors as JSON with a
// message (user-service), a detail (RFC 9457) or an error field (the
// receiver behind user-service).
const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')

export async function requestJson(path, { body, headers: extraHeaders, ...options } = {}) {
  const headers = {
    Accept: 'application/json',
    ...(await authorizationHeader()),
    ...extraHeaders,
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    let message = ''
    let problem = null
    try {
      problem = await response.json()
      message = problem.message || problem.detail || problem.error || ''
    } catch {
      // No JSON body.
    }
    const error = new Error(message || `Request failed (${response.status})`)
    error.status = response.status
    error.problem = problem
    throw error
  }
  if (response.status === 204) return null
  return response.json()
}

// A file such as a PDF, as a Blob with the file name the server gave it.
export async function requestBlob(path, { headers: extraHeaders, ...options } = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: { Accept: 'application/pdf, application/json', ...(await authorizationHeader()), ...extraHeaders },
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
  const disposition = response.headers.get('Content-Disposition') || ''
  const named = /filename="?([^";]+)"?/i.exec(disposition)
  return { blob: await response.blob(), fileName: named ? named[1] : 'report.pdf' }
}

// Saves a Blob in the browser under the given name.
export function saveBlob({ blob, fileName }) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
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
