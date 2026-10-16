import { requestJson } from './request.js'

// A client's accounts that are not deleted, from account-service. An agent
// gets only accounts they own.
export function listAccounts(clientId) {
  return requestJson(`/api/accounts?clientId=${encodeURIComponent(clientId)}`)
}

// One account's transactions, newest first, through user-service, which
// checks the caller may read the account first.
export function listAccountTransactions(accountId) {
  return requestJson(`/api/accounts/${encodeURIComponent(accountId)}/transactions`)
}
