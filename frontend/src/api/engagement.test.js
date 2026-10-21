import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import {
  cancelEvent,
  createDraft,
  createEvent,
  fromLocalInput,
  listEvents,
  listHolidays,
  monthWindow,
  sendDraft,
  singaporeParts,
  toLocalInput,
  updateDraft,
  updatePreferences,
} from './engagement.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('engagement API', () => {
  let fetchMock

  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
    fetchMock = vi.fn().mockImplementation(async () => jsonResponse([]))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lists events in a window with the access token', async () => {
    await listEvents({ from: '2026-10-01T00:00:00+08:00', to: '2026-11-01T00:00:00+08:00' })

    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/engagement/events?from=2026-10-01T00%3A00%3A00%2B08%3A00&to=2026-11-01T00%3A00%3A00%2B08%3A00')
    expect(options.headers).toMatchObject({ Authorization: 'Bearer synthetic-token' })
  })

  it('creates an event as JSON and cancels it with DELETE', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ id: 'event-1' }, 201))
    await createEvent({ title: 'Review', reminderOffsetsMinutes: [60] })
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))
    await expect(cancelEvent('event-1')).resolves.toBeNull()

    expect(fetchMock.mock.calls[0][1].method).toBe('POST')
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ title: 'Review', reminderOffsetsMinutes: [60] })
    expect(fetchMock.mock.calls[1][0]).toBe('/api/engagement/events/event-1')
    expect(fetchMock.mock.calls[1][1].method).toBe('DELETE')
  })

  it('asks for Singapore holidays by default', async () => {
    await listHolidays({ from: '2026-11-01', to: '2026-11-30' })

    expect(fetchMock.mock.calls[0][0]).toBe('/api/engagement/holidays?country=SG&from=2026-11-01&to=2026-11-30')
  })

  it('sends drafts with only the fields the contract takes', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ id: 'draft-1' }, 201))
    await createDraft({ clientId: 'client-1', purpose: 'CHECK_IN', tone: 'WARM', extra: 'ignored' })
    await updateDraft('draft-1', { subject: 'S', body: 'B', version: 2, status: 'SENT' })
    await sendDraft('draft-1', 3)

    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ clientId: 'client-1', purpose: 'CHECK_IN', tone: 'WARM', eventId: null })
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ subject: 'S', body: 'B', version: 2 })
    expect(fetchMock.mock.calls[2][0]).toBe('/api/engagement/drafts/draft-1/send')
    expect(JSON.parse(fetchMock.mock.calls[2][1].body)).toEqual({ version: 3 })
  })

  it('reports the status and message of an error', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 409, error: 'Conflict', message: 'Already sent' }, 409))

    await expect(updatePreferences({ invitesEnabled: true, remindersEnabled: false }))
      .rejects.toMatchObject({ status: 409, message: 'Already sent' })
  })
})

describe('Singapore times', () => {
  it('reads and writes datetime-local values as Singapore time', () => {
    expect(toLocalInput('2026-10-22T06:00:00Z')).toBe('2026-10-22T14:00')
    expect(fromLocalInput('2026-10-22T14:00')).toBe('2026-10-22T14:00:00+08:00')
    expect(singaporeParts('2026-10-21T16:30:00Z')).toEqual({ date: '2026-10-22', time: '00:30' })
  })

  it('gives the window of a month, across the end of a year', () => {
    expect(monthWindow(2026, 12)).toEqual({ from: '2026-12-01T00:00:00+08:00', to: '2027-01-01T00:00:00+08:00' })
  })
})
