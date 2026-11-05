import { requestJson } from './request.js'

// One card per public event for the signed-in agent, with the affected clients
// on drill-down. Inform / Monitor / Dismiss apply to an explicit client set.

export function listEvents({ onlyOpen = false, limit = 25, cursor } = {}) {
  const next = cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''
  return requestJson(`/api/alerts/events?onlyOpen=${onlyOpen}&limit=${limit}${next}`)
}

export function getEvent(eventId) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}`)
}

// members: [{ alertId, expectedVersion }]. Recording "inform" never contacts a client.
export function recordEventAction(eventId, { decision, note, members }, idempotencyKey = crypto.randomUUID()) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/actions`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey, 'X-Correlation-Id': crypto.randomUUID() },
    body: { decision, note: note || null, members },
  })
}

// The assigned agent approves one report version for the chosen clients. Nothing is sent here (ADR-0084).
export function approveEventReport(eventId, reportId, alertIds) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/report/approval`, {
    method: 'POST',
    body: { reportId, alertIds },
  })
}
