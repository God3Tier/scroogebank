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

// One account's transactions, newest first, through user-service, which
// checks the caller may read the account first.
export function listAccountTransactions(accountId) {
  return requestJson(`/api/accounts/${encodeURIComponent(accountId)}/transactions`)
}
