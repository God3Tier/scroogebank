import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listClients } from '../../api/clients.js'
import { listEvents } from '../../api/engagement.js'
import UpcomingMeetings from './UpcomingMeetings.jsx'

vi.mock('../../api/clients.js', () => ({ listClients: vi.fn() }))
vi.mock('../../api/engagement.js', async (importOriginal) => ({
  ...(await importOriginal()),
  listEvents: vi.fn(),
}))

const NOW = new Date('2026-10-21T02:00:00Z')

function renderPanel() {
  return render(<MemoryRouter><UpcomingMeetings now={() => NOW} /></MemoryRouter>)
}

describe('UpcomingMeetings', () => {
  beforeEach(() => {
    listClients.mockResolvedValue({ content: [{ client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' }] })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it("lists the next week's meetings and links to the calendar", async () => {
    listEvents.mockResolvedValue([
      {
        id: 'e1',
        title: 'Synthetic review',
        clientId: 'client-1',
        location: 'Level 3',
        startsAt: '2026-10-22T06:00:00Z',
        endsAt: '2026-10-22T07:00:00Z',
        reminders: [{ offsetMinutes: 60 }, { offsetMinutes: 15 }],
      },
    ])

    renderPanel()

    expect(await screen.findByRole('heading', { name: 'Upcoming meetings' })).toBeInTheDocument()
    expect(screen.getByText('Synthetic review')).toBeInTheDocument()
    expect(await screen.findByText('Synthetic Client')).toBeInTheDocument()
    expect(screen.getByText('Level 3')).toBeInTheDocument()
    expect(screen.getByText('15 min')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open calendar' })).toHaveAttribute('href', '/agent/calendar')
    expect(listEvents).toHaveBeenCalledWith({ from: '2026-10-21T02:00:00.000Z', to: '2026-10-28T02:00:00.000Z' })
  })

  it('still lists meetings when client names are unavailable', async () => {
    listClients.mockRejectedValue(Object.assign(new Error('Unavailable'), { status: 503 }))
    listEvents.mockResolvedValue([
      { id: 'e1', title: 'Synthetic review', clientId: 'client-1', startsAt: '2026-10-22T06:00:00Z', endsAt: '2026-10-22T07:00:00Z' },
      { id: 'e2', title: 'Internal sync', clientId: null, startsAt: '2026-10-23T06:00:00Z', endsAt: '2026-10-23T07:00:00Z' },
    ])

    renderPanel()

    expect(await screen.findByText('Client meeting')).toBeInTheDocument()
    expect(screen.getByText('No client')).toBeInTheDocument()
  })

  it('says when there are no meetings', async () => {
    listEvents.mockResolvedValue([])

    renderPanel()

    expect(await screen.findByText('No meetings in the next 7 days.')).toBeInTheDocument()
  })

  it('is hidden when engagement-service is unavailable', async () => {
    listEvents.mockRejectedValue(Object.assign(new Error('Unavailable'), { status: 503 }))

    const { container } = renderPanel()

    await waitFor(() => expect(listEvents).toHaveBeenCalled())
    await waitFor(() => expect(container).toBeEmptyDOMElement())
    expect(screen.queryByText('Upcoming meetings')).not.toBeInTheDocument()
  })
})
