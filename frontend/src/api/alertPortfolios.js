import { requestJson } from './request.js'

// The dummy securities positions an agent records against one CRM account.
// Saving is bookkeeping only: it never places a trade.

// 404 means nothing has been recorded yet (or the account is not yours).
export function getPortfolio(accountId) {
  return requestJson(`/api/alert-portfolios/${encodeURIComponent(accountId)}`)
}

// Replaces the whole recorded portfolio. expectedRevision is 0 for a first save.
// Reuse idempotencyKey when retrying the same save.
export function replacePortfolio(accountId, body, idempotencyKey = crypto.randomUUID()) {
  return requestJson(`/api/alert-portfolios/${encodeURIComponent(accountId)}`, {
    method: 'PUT',
    headers: { 'Idempotency-Key': idempotencyKey, 'X-Correlation-Id': crypto.randomUUID() },
    body,
  })
}
