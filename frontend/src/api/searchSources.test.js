import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import { addSearchDomain, listSearchSources, removeSearchDomain } from './searchSources.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function reply(status, body) {
  return new Response(body === undefined ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } })
}

describe('search sources API', () => {
  beforeEach(() => authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' }))
  afterEach(() => vi.unstubAllGlobals())

  it('lists the companies', async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, []))
    vi.stubGlobal('fetch', fetchMock)

    await listSearchSources()

    expect(fetchMock).toHaveBeenCalledWith('/api/alerts/search-sources', expect.objectContaining({
      headers: expect.objectContaining({ Authorization: 'Bearer synthetic-token' }),
    }))
  })

  it('posts only the domain, with a correlation id, to the company path', async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(201, { id: 'd1', domain: 'example.com' }))
    vi.stubGlobal('fetch', fetchMock)

    await addSearchDomain('SGX-D05', 'example.com')

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/alerts/search-sources/SGX-D05/domains')
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ domain: 'example.com' })
    expect(options.headers['X-Correlation-Id']).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('deletes by company and domain id and returns nothing', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(removeSearchDomain('SGX-D05', 'd1')).resolves.toBeNull()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/alerts/search-sources/SGX-D05/domains/d1')
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
  })

  it('surfaces the refusal text and status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(400,
      { title: 'Search source refused', detail: 'social, forum and blog platforms cannot be used as news sources' })))

    await expect(addSearchDomain('SGX-D05', 'reddit.com')).rejects.toMatchObject({
      status: 400, message: 'social, forum and blog platforms cannot be used as news sources',
    })
  })
})
