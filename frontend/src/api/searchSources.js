import { requestJson } from './request.js'

// The sites searched for news about each company (ADR-0079). Official and shared
// sites are fixed; agents add their own per company, and any agent who holds the
// company, or an admin, can remove one. 4xx errors carry a readable message.

export function listSearchSources() {
  return requestJson('/api/alerts/search-sources')
}

export function addSearchDomain(instrumentId, domain) {
  return requestJson(`/api/alerts/search-sources/${encodeURIComponent(instrumentId)}/domains`, {
    method: 'POST',
    headers: { 'X-Correlation-Id': crypto.randomUUID() },
    body: { domain },
  })
}

export function removeSearchDomain(instrumentId, domainId) {
  return requestJson(
    `/api/alerts/search-sources/${encodeURIComponent(instrumentId)}/domains/${encodeURIComponent(domainId)}`,
    { method: 'DELETE', headers: { 'X-Correlation-Id': crypto.randomUUID() } },
  )
}
