import { syntheticAlerts, syntheticDetail } from '../data/alerts.js'

const BASE_URL = (import.meta.env.VITE_API_URL || '').replace(/\/$/, '')
const DEMO_MODE = import.meta.env.VITE_USE_SYNTHETIC_ALERTS === 'true'

function headers(extra = {}) {
  const token = window.localStorage.getItem('access_token')
  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

async function request(path, options = {}) {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: headers(options.headers),
  })

  if (!response.ok) {
    let problem = null
    try {
      problem = await response.json()
    } catch {
      // Some upstream failures do not return an RFC 9457 response body.
    }
    const error = new Error(problem?.detail || problem?.title || `Request failed (${response.status})`)
    error.status = response.status
    error.retryAfter = response.headers.get('Retry-After')
    error.problem = problem
    throw error
  }
  return response.json()
}

export async function listAlerts({ status = '', minimumScore = '', cursor = '', limit = 25 } = {}) {
  if (DEMO_MODE) {
    const visible = syntheticAlerts.filter((alert) =>
      (!status || alert.status === status) &&
      (minimumScore === '' || alert.score >= Number(minimumScore)))
    return { items: visible, nextCursor: null }
  }

  const params = new URLSearchParams({ limit: String(limit) })
  if (status) params.set('status', status)
  if (minimumScore !== '') params.set('minimumScore', String(minimumScore))
  if (cursor) params.set('cursor', cursor)
  return request(`/api/alerts?${params}`)
}

export async function getAlert(alertId) {
  if (DEMO_MODE) {
    const alert = syntheticAlerts.find((item) => item.id === alertId)
    if (!alert) throw Object.assign(new Error('Alert is not available in your assigned scope.'), { status: 404 })
    return syntheticDetail(alert)
  }
  return request(`/api/alerts/${alertId}`)
}

export async function recordDecision(alertId, body, idempotencyKey = crypto.randomUUID()) {
  if (DEMO_MODE) {
    return {
      id: crypto.randomUUID(),
      alertId,
      decision: body.decision,
      actorSubject: 'syn-agent-001',
      decidedAt: new Date().toISOString(),
      alertVersion: body.expectedVersion + 1,
    }
  }
  return request(`/api/alerts/${alertId}/decisions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Correlation-Id': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  })
}

export async function recordFeedback(alertId, body, idempotencyKey = crypto.randomUUID()) {
  if (DEMO_MODE) {
    return {
      id: crypto.randomUUID(),
      alertId,
      rating: body.rating,
      reasonCode: body.reasonCode || null,
      actorSubject: 'syn-agent-001',
      recordedAt: new Date().toISOString(),
      alertVersion: body.expectedVersion,
    }
  }
  return request(`/api/alerts/${alertId}/feedback`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Correlation-Id': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  })
}
