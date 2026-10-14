import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { listActivities } from './activities.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('activity API', () => {
  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('asks user-service for one page with the access token', async () => {
    const page = { content: [], nextCursor: null }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(page))
    vi.stubGlobal('fetch', fetchMock)

    await expect(listActivities({ size: 10 })).resolves.toEqual(page)

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/activities?size=10')
    expect(options.headers).toMatchObject({ Authorization: 'Bearer synthetic-token', Accept: 'application/json' })
  })

  it('sends the filters and the cursor, with times to the second in UTC', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ content: [], nextCursor: null }))
    vi.stubGlobal('fetch', fetchMock)

    await listActivities({
      agentId: 'agent-sub-0001',
      from: new Date('2026-10-07T02:03:04.567Z'),
      to: new Date('2026-10-14T00:00:00Z'),
      size: 10,
      cursor: 'page-2',
    })

    const params = new URL(fetchMock.mock.calls[0][0], 'http://localhost').searchParams
    expect(Object.fromEntries(params)).toEqual({
      size: '10',
      agentId: 'agent-sub-0001',
      from: '2026-10-07T02:03:04Z',
      to: '2026-10-14T00:00:00Z',
      cursor: 'page-2',
    })
  })

  it("rejects with user-service's message and the status", async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      status: 503, error: 'Service Unavailable', message: 'Activity log is temporarily unavailable',
    }, 503)))

    await expect(listActivities()).rejects.toMatchObject({
      status: 503,
      message: 'Activity log is temporarily unavailable',
    })
  })

  it('rejects with the status when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))

    await expect(listActivities()).rejects.toMatchObject({ status: 502, message: 'Request failed (502)' })
  })
})
