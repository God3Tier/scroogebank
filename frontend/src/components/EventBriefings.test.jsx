import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'
import EventBriefings from './EventBriefings.jsx'

vi.mock('../api/alertEvents.js', () => ({ listEvents: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn() }))

// Synthetic data only.
const CARD = {
  eventId: 'event-1', headline: 'Synthetic issuer earnings', summary: 'The issuer reported synthetic earnings.',
  aiGenerated: true, uncertainty: 'Synthetic fixture.', affectedClientCount: 2, openClientCount: 2,
  evidence: {
    url: 'https://example.invalid/a', sourceDomain: 'example.invalid',
    publishedAt: '2026-10-30T00:00:00Z', retrievedAt: '2026-10-30T01:00:00Z',
  },
}
const DETAIL = {
  card: CARD,
  clients: [
    { alertId: 'alert-aaaaaaaa', clientRef: 'client-11111111', instrumentId: 'SYN-SGX-001', accountRef: 'a1111111-1111-4111-8111-111111111111', status: 'NEW', portfolioPercentage: 90, version: 0 },
    { alertId: 'alert-bbbbbbbb', clientRef: 'client-22222222', instrumentId: 'SYN-SGX-001', accountRef: 'a2222222-2222-4222-8222-222222222222', status: 'NEW', portfolioPercentage: 80, version: 3 },
  ],
  latestAction: null,
}

describe('event briefings', () => {
  afterEach(() => vi.clearAllMocks())

  it('shows one card per event with the affected-client count', async () => {
    listEvents.mockResolvedValue({ items: [CARD] })
    render(<EventBriefings />)

    expect(await screen.findByText('Synthetic issuer earnings')).toBeInTheDocument()
    expect(screen.getByText(/may be affected/)).toHaveTextContent('2 clients may be affected')
    expect(screen.getByText('The issuer reported synthetic earnings.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Source: example.invalid' }))
      .toHaveAttribute('href', CARD.evidence.url)
  })

  it('loads the next event page without replacing the first', async () => {
    const older = { ...CARD, eventId: 'event-2', headline: 'Older synthetic event' }
    listEvents.mockResolvedValueOnce({ items: [CARD], nextCursor: 'cursor-1' })
      .mockResolvedValueOnce({ items: [older], nextCursor: null })
    render(<EventBriefings />)

    fireEvent.click(await screen.findByRole('button', { name: 'Load more events' }))

    expect(await screen.findByText('Older synthetic event')).toBeInTheDocument()
    expect(screen.getByText('Synthetic issuer earnings')).toBeInTheDocument()
    expect(listEvents).toHaveBeenLastCalledWith({ onlyOpen: true, cursor: 'cursor-1' })
    expect(screen.queryByRole('button', { name: 'Load more events' })).not.toBeInTheDocument()
  })

  it('applies the chosen action only to the clients the agent selected', async () => {
    listEvents.mockResolvedValue({ items: [CARD] })
    getEvent.mockResolvedValue(DETAIL)
    recordEventAction.mockResolvedValue({
      actionId: 'a1', members: [{ alertId: 'alert-bbbbbbbb', outcome: 'APPLIED', newStatus: 'INFORMED' }],
    })
    render(<EventBriefings />)

    fireEvent.click(await screen.findByText('Review affected clients'))
    expect(await screen.findByText('a1111111')).toHaveAttribute('title', 'a1111111-1111-4111-8111-111111111111')
    fireEvent.click(await screen.findByLabelText('Select client client-22222222'))
    fireEvent.click(screen.getByLabelText('Inform clients'))
    fireEvent.click(screen.getByText('Apply to selected clients'))

    await waitFor(() => expect(recordEventAction).toHaveBeenCalled())
    expect(recordEventAction.mock.calls[0][0]).toBe('event-1')
    expect(recordEventAction.mock.calls[0][1]).toMatchObject({
      decision: 'INFORM_CLIENT',
      members: [{ alertId: 'alert-bbbbbbbb', expectedVersion: 3 }],
    })
    expect(await screen.findByText(/Done/)).toBeInTheDocument()
  })

  it('sends nothing until a client is selected, and says inform sends no message', async () => {
    listEvents.mockResolvedValue({ items: [CARD] })
    getEvent.mockResolvedValue(DETAIL)
    render(<EventBriefings />)

    fireEvent.click(await screen.findByText('Review affected clients'))
    fireEvent.click(await screen.findByLabelText('Inform clients'))

    expect(screen.getByText('Apply to selected clients')).toBeDisabled()
    expect(screen.getByText(/No message is sent/)).toBeInTheDocument()
  })
})
