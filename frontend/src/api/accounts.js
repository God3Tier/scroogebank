import { requestJson } from './request.js'

// A client's accounts that are not deleted, from account-service. An agent
// gets only accounts they own.
export function listAccounts(clientId) {
  return requestJson(`/api/accounts?clientId=${encodeURIComponent(clientId)}`)
}

// Opens an account for a client the caller may access. account-service sets
// the opening date and copies the owner from the client, so neither is sent.
// Returns { account_id, status }.
export function createAccount(account) {
  return requestJson('/api/accounts', { method: 'POST', body: account })
}

// Changes an account's status: ACTIVE, INACTIVE, PENDING or CLOSED, in any
// direction. Only the agent who owns the account may, and only once its client
// is verified (ADR-0050). Returns { account_id, status }.
export function updateAccountStatus(accountId, status) {
  return requestJson(`/api/accounts/${encodeURIComponent(accountId)}`, { method: 'PATCH', body: { status } })
}

// A soft delete (ADR-0036): account-service sets deleted_at and keeps the row,
// which is then never listed or returned again. Only the agent who owns the
// account may delete it.
export function deleteAccount(accountId) {
  return requestJson(`/api/accounts/${encodeURIComponent(accountId)}`, { method: 'DELETE' })
}

// One account's transactions, newest first, from transaction-service, which
// checks the caller may read the account first (ADR-0064).
export function listAccountTransactions(accountId) {
  return requestJson(`/api/accounts/${encodeURIComponent(accountId)}/transactions`)
}
