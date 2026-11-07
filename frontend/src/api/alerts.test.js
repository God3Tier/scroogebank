import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { getAlert, listAlerts, recordDecision } from './alerts.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

describe('alert API', () => {
  beforeEach(() => {
    authorizationHeader.mockResolvedValue({})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('scopes list filters into the contract query', async () => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ items: [], nextCursor: null }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)

    await listAlerts({ status: 'NEW', minimumScore: 60, cursor: 'next-page', limit: 10 })

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/alerts?limit=10&status=NEW&minimumScore=60&cursor=next-page',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer synthetic-token' }),
      }),
    )
  })

  it('maps problem details and retry guidance onto errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ title: 'Audit persistence unavailable', detail: 'audit outbox is at capacity' }),
      { status: 503, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '5' } },
    )))

    await expect(recordDecision('alert-1', { decision: 'DISMISS', expectedVersion: 0 }, 'decision-key-001'))
      .rejects.toMatchObject({ status: 503, retryAfter: '5', message: 'audit outbox is at capacity' })
  })

  it('requests detail without putting an actor identity in the URL', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ id: 'alert-1' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)

    await getAlert('alert-1')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/alerts/alert-1')
  })

  it('sends no Authorization header when signed out', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ id: 'alert-1' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)

    await getAlert('alert-1')

    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty('Authorization')
  })
})
