import { requestJson } from './request.js'

// The websites searched for every company, who holds each company, Search now and the
// automatic schedule (ADR-0081, ADR-0082). 4xx errors carry a readable message.

export function listSearchWebsites() {
  return requestJson('/api/alerts/search-websites')
}

export function addSearchWebsite(domain) {
  return requestJson('/api/alerts/search-websites', {
    method: 'POST',
    headers: { 'X-Correlation-Id': crypto.randomUUID() },
    body: { domain },
  })
}

export function removeSearchWebsite(websiteId) {
  return requestJson(`/api/alerts/search-websites/${encodeURIComponent(websiteId)}`, {
    method: 'DELETE',
    headers: { 'X-Correlation-Id': crypto.randomUUID() },
  })
}

// The companies the caller's clients hold, each with the clients that hold it. Clients are
// anonymous ids; look their names up with getClient.
export function listSearchHoldings() {
  return requestJson('/api/alerts/search-holdings')
}

// Search one company, or every company the caller may search when no company is given.
export function searchNow(instrumentId) {
  return requestJson('/api/alerts/search-now', {
    method: 'POST',
    headers: { 'X-Correlation-Id': crypto.randomUUID() },
    body: instrumentId ? { instrumentId } : {},
  })
}

export function getSearchSchedule() {
  return requestJson('/api/alerts/search-schedule')
}
