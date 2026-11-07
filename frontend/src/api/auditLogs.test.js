import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.hoisted(() => {
  vi.stubEnv('VITE_API_URL', '')
})

import { authorizationHeader } from '../auth/session.js'
import { listAuditLogs } from './auditLogs.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('complete audit-log API', () => {
  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  afterAll(() => {
    vi.unstubAllEnvs()
  })

  it('requests an administrator audit-log page with its cursor', async () => {
    const page = { content: [], nextCursor: null }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(page))
    vi.stubGlobal('fetch', fetchMock)

    await expect(listAuditLogs({ size: 20, cursor: '41' })).resolves.toEqual(page)

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/audit-logs?size=20&cursor=41')
    expect(options.headers).toMatchObject({
      Authorization: 'Bearer synthetic-token',
      Accept: 'application/json',
    })
  })

  it('passes through the response status and message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ message: 'Forbidden' }, 403)))

    await expect(listAuditLogs()).rejects.toMatchObject({ status: 403, message: 'Forbidden' })
  })
})
