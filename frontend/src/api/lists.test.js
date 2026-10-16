import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { listAccountTransactions, listAccounts } from './accounts.js'
import { listClients } from './clients.js'
import { getDashboard, panelData, windowForDays } from './dashboard.js'
import { listTransactions } from './transactions.js'
import { createUser, disableUser, listUsers } from './users.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('list and admin APIs', () => {
  let fetchMock

  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
    fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse({ content: [] })))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const lastCall = () => fetchMock.mock.calls.at(-1)

  it('pages the client list', async () => {
    await listClients({ page: 2, size: 5 })

    expect(lastCall()[0]).toBe('/api/clients?page=2&size=5')
    expect(lastCall()[1].headers).toMatchObject({ Authorization: 'Bearer synthetic-token' })
  })

  it('leaves out empty transaction-list parameters', async () => {
    await listTransactions({ size: 20 })
    expect(lastCall()[0]).toBe('/api/transactions?size=20')

    await listTransactions({ cursor: 'abc', size: 10, status: 'Pending' })
    expect(lastCall()[0]).toBe('/api/transactions?cursor=abc&size=10&status=Pending')
  })

  it("lists a client's accounts and an account's transactions", async () => {
    await listAccounts('client 1')
    expect(lastCall()[0]).toBe('/api/accounts?clientId=client%201')

    await listAccountTransactions('account-1')
    expect(lastCall()[0]).toBe('/api/accounts/account-1/transactions')
  })

  it('filters users by role and creates one as JSON', async () => {
    await listUsers({ role: 'AGENT', size: 1 })
    expect(lastCall()[0]).toBe('/api/users?role=AGENT&page=0&size=1')

    await createUser({ firstName: 'Synthetic', lastName: 'Agent', email: 'agent@example.test', role: 'AGENT' })
    const [url, options] = lastCall()
    expect(url).toBe('/api/users')
    expect(options.method).toBe('POST')
    expect(options.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(options.body)).toMatchObject({ role: 'AGENT' })
  })

  it("passes on user-service's message and status", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 403, error: 'Forbidden', message: 'root admin cannot be disabled' }, 403))

    await expect(disableUser('user-1')).rejects.toMatchObject({ status: 403, message: 'root admin cannot be disabled' })
    expect(lastCall()[1].method).toBe('PATCH')
  })

  it('treats an empty 204 as no content', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(disableUser('user-1')).resolves.toBeNull()
  })

  it('asks the dashboard for a window and its options', async () => {
    await getDashboard('action-items', { from: '2026-10-01', to: '2026-10-16', staleDays: 7 })

    expect(lastCall()[0]).toBe('/api/dashboard/action-items?from=2026-10-01&to=2026-10-16&staleDays=7')
  })
})

describe('dashboard helpers', () => {
  it('ends the window today in Singapore', () => {
    // 20:00 UTC on 15 October is already 16 October in Singapore.
    expect(windowForDays(7, new Date('2026-10-15T20:00:00Z'))).toEqual({ from: '2026-10-10', to: '2026-10-16' })
    expect(windowForDays(1, new Date('2026-10-15T20:00:00Z'))).toEqual({ from: '2026-10-16', to: '2026-10-16' })
  })

  it("reads a panel's data only when it resolved", () => {
    expect(panelData({ status: 'OK', data: { total: 1 } })).toEqual({ total: 1 })
    expect(panelData({ status: 'UNAVAILABLE', data: null, reason: 'upstream_timeout' })).toBeNull()
    expect(panelData(undefined)).toBeNull()
  })
})
