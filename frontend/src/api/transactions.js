import { query, requestJson } from './request.js'

// Every account's transactions, newest first, for admins only (ADR-0043).
// Returns { content, nextCursor }; pass nextCursor back as cursor for the next
// page. It is null on the last page.
export function listTransactions({ cursor, size = 20, status } = {}) {
  return requestJson(`/api/transactions${query({ cursor, size, status })}`)
}
