import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { recordEventAction } from './alertEvents.js'
import { getPortfolio, replacePortfolio } from './alertPortfolios.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function ok(body) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('holdings and event APIs', () => {
  beforeEach(() => authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' }))
  afterEach(() => vi.unstubAllGlobals())

  it('reads a portfolio by account ID only', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ revision: 1 }))
    vi.stubGlobal('fetch', fetchMock)
    await getPortfolio('account-1')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/alert-portfolios/account-1')
  })

  it('replaces a portfolio with an idempotency key and no client or agent in the body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ revision: 2 }))
    vi.stubGlobal('fetch', fetchMock)
    await replacePortfolio('account-1', { expectedRevision: 1, sourceLabel: 'x', positions: [] }, 'idem-key-0001')
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/alert-portfolios/account-1')
    expect(options.method).toBe('PUT')
    expect(options.headers).toMatchObject({ 'Idempotency-Key': 'idem-key-0001', Authorization: 'Bearer synthetic-token' })
    expect(JSON.parse(options.body)).not.toHaveProperty('clientId')
  })

  it('posts an event action with an explicit member list', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ members: [] }))
    vi.stubGlobal('fetch', fetchMock)
    await recordEventAction('event-1', { decision: 'DISMISS', members: [{ alertId: 'a', expectedVersion: 0 }] }, 'idem-key-0002')
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/alerts/events/event-1/actions')
    expect(options.headers['Idempotency-Key']).toBe('idem-key-0002')
    expect(JSON.parse(options.body).members).toEqual([{ alertId: 'a', expectedVersion: 0 }])
  })
})
