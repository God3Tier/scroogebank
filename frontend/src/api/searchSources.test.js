import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import {
  addSearchWebsite, getSearchSchedule, listSearchHoldings, listSearchWebsites, removeSearchWebsite, searchNow,
} from './searchSources.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function reply(status, body) {
  return new Response(body === undefined ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } })
}

describe('search sources API', () => {
  beforeEach(() => authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' }))
  afterEach(() => vi.unstubAllGlobals())

  it('lists the websites, the holdings and the schedule with the caller\'s token', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(reply(200, [])))
    vi.stubGlobal('fetch', fetchMock)

    await listSearchWebsites()
    await listSearchHoldings()
    await getSearchSchedule()

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/alerts/search-websites', '/api/alerts/search-holdings', '/api/alerts/search-schedule'])
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer synthetic-token')
  })

  it('posts only the website, with a correlation id', async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(201, { id: 'w1', domain: 'example.com', search: { outcome: 'OFF' } }))
    vi.stubGlobal('fetch', fetchMock)

    await addSearchWebsite('example.com')

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/alerts/search-websites')
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ domain: 'example.com' })
    expect(options.headers['X-Correlation-Id']).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('deletes by website id and returns nothing', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(removeSearchWebsite('w1')).resolves.toBeNull()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/alerts/search-websites/w1')
    expect(fetchMock.mock.calls[0][1].method).toBe('DELETE')
  })

  it('searches every company, or one when it is named', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(reply(200, { outcome: 'STARTED', started: [], cooldown: [], lookbackDays: 14 })))
    vi.stubGlobal('fetch', fetchMock)

    await searchNow()
    await searchNow('SGX-D05')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/alerts/search-now')
    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({})
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ instrumentId: 'SGX-D05' })
    expect(fetchMock.mock.calls[1][1].headers['X-Correlation-Id']).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('surfaces the refusal text and status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(reply(400,
      { title: 'Search source refused', detail: 'social, forum and blog platforms cannot be used as news sources' })))

    await expect(addSearchWebsite('reddit.com')).rejects.toMatchObject({
      status: 400, message: 'social, forum and blog platforms cannot be used as news sources',
    })
  })
})
