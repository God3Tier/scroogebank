import { requestBlob, requestJson } from './request.js'

// One card per public event for the signed-in agent, with the affected clients
// on drill-down. "Plan client briefing" and Dismiss apply to an explicit client set.
// The main view leaves out events whose clients are all Sent Out or Dismissed.

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

// The agent's edit and approval of an event's latest report (news-alert-drafts plan 5.2).
export function getReportReview(eventId) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/report/review`)
}

// sections: { executiveSummary: [text], keyFacts: [{label, value, context}], whyItMatters: [text],
// whatToMonitor: [text], uncertainty }. A refused edit is a 422 naming the section and item.
export function saveReportEdit(eventId, expectedReportVersion, sections) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/report/edit`, {
    method: 'PUT',
    body: { expectedReportVersion, sections },
  })
}

export function discardReportEdit(eventId) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/report/edit`, { method: 'DELETE' })
}

// Approval is final: the text the client receives is frozen.
export function approveReport(eventId, expectedReportVersion) {
  return requestJson(`/api/alerts/events/${encodeURIComponent(eventId)}/report/approval`, {
    method: 'POST',
    body: { expectedReportVersion },
  })
}

// One report as the server-rendered PDF, the same layout the client receives.
export function downloadReportPdf(eventId, version) {
  const suffix = version ? `?version=${encodeURIComponent(version)}` : ''
  return requestBlob(`/api/alerts/events/${encodeURIComponent(eventId)}/report/pdf${suffix}`)
}

// The caller's approved, unsent reports in Plan client briefing, for one client or all.
export function listApprovedReports(clientRef) {
  const suffix = clientRef ? `?clientRef=${encodeURIComponent(clientRef)}` : ''
  return requestJson(`/api/alerts/approved-reports${suffix}`)
}

// The Sent out view: the agent's clients, or everyone's for an admin.
export function listSentOut(limit = 100) {
  return requestJson(`/api/alerts/sent-out?limit=${limit}`)
}
