import { fireEvent, render as renderInto, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'
import { sendAlertReport } from '../api/engagement.js'
import EventBriefings from './EventBriefings.jsx'

vi.mock('../api/alertEvents.js', () => ({
  listEvents: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn(),
}))
vi.mock('../api/engagement.js', async (original) => ({ ...(await original()), sendAlertReport: vi.fn() }))

// The briefings link to Follow-up Emails, so they need a router.
const render = (ui) => renderInto(<MemoryRouter>{ui}</MemoryRouter>)

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

  it('shows the event report with every linked source and the fixed text', async () => {
    listEvents.mockResolvedValue({ items: [CARD] })
    getEvent.mockResolvedValue({
      ...DETAIL,
      report: {
        reportId: 'r1', version: 2, versionCount: 2, schemaVersion: 'alert-client-report-v2',
        report: {
          headline: 'Synthetic report headline',
          executiveSummary: [{ text: 'First cited sentence.', sourceRefs: ['s1'] }, { text: 'Second cited sentence.', sourceRefs: ['s1'] }],
          keyFacts: [{ label: 'Revenue', value: '$10 million', context: 'Up on the year', sourceRefs: ['s1'] }],
          whyItMatters: [{ text: 'Demand may grow.', kind: 'POTENTIAL_IMPLICATION', sourceRefs: [] }],
          marketContext: null, whatToMonitor: ['Next quarter', 'Guidance'], uncertainty: 'One source.', sourceRefs: ['s1'],
        },
        sources: [
          { title: 'Used source', url: 'https://a.invalid/x', domain: 'a.invalid', publishedAt: null, usedInReport: true },
          { title: 'Extra source', url: 'https://b.invalid/y', domain: 'b.invalid', publishedAt: null, usedInReport: false },
        ],
        portfolioLines: { 'SYN-SGX-001': 'Your portfolio has exposure to Synthetic Ltd, which may be affected by this event.' },
        disclaimer: 'This report is for information only.', createdAt: '2026-10-30T02:00:00Z',
      },
    })
    render(<EventBriefings />)

    fireEvent.click(await screen.findByText('Review affected clients'))

    expect(await screen.findByText('Synthetic report headline')).toBeInTheDocument()
    expect(screen.getByText('Potential implication: Demand may grow.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Extra source' })).toHaveAttribute('href', 'https://b.invalid/y')
    expect(screen.getByText(/Your portfolio has exposure to Synthetic Ltd/)).toBeInTheDocument()
    expect(screen.getByText('This report is for information only.')).toBeInTheDocument()
  })

  it('sends the report only to selected INFORMED CRM clients after the agent confirms, and carries on with queued ones', async () => {
    const crm = '11111111-2222-4333-8444-555555555555'
    const other = '99999999-2222-4333-8444-555555555555'
    const report = {
      reportId: 'r1', version: 3, versionCount: 3, schemaVersion: 'alert-client-report-v2',
      report: { headline: 'H', executiveSummary: [{ text: 'A.', sourceRefs: [] }, { text: 'B.', sourceRefs: [] }], keyFacts: [], whyItMatters: [], whatToMonitor: [], uncertainty: null },
      sources: [], portfolioLines: {}, disclaimer: 'D', createdAt: '2026-10-30T02:00:00Z',
    }
    const clients = [
      { ...DETAIL.clients[0], clientRef: crm, status: 'INFORMED' },
      { ...DETAIL.clients[1], clientRef: other, status: 'NEW' },
    ]
    listEvents.mockResolvedValue({ items: [CARD] })
    getEvent.mockResolvedValue({ ...DETAIL, clients, report })
    sendAlertReport
      .mockResolvedValueOnce({ recipients: [{ alertId: 'alert-aaaaaaaa', status: 'QUEUED', errorCode: null }] })
      .mockResolvedValueOnce({ recipients: [{ alertId: 'alert-aaaaaaaa', status: 'SENT', errorCode: null }] })
    render(<EventBriefings />)

    fireEvent.click(await screen.findByText('Review affected clients'))
    fireEvent.click(await screen.findByLabelText(`Select client ${crm}`))
    const send = screen.getByRole('button', { name: 'Send report to selected clients' })
    expect(send).toBeDisabled()
    fireEvent.change(screen.getByLabelText(/Note to the selected clients/), { target: { value: 'Call me' } })
    fireEvent.click(screen.getByLabelText(/I have reviewed version 3/))
    fireEvent.click(send)

    await waitFor(() => expect(sendAlertReport).toHaveBeenCalledTimes(2))
    const [request, key] = sendAlertReport.mock.calls[0]
    expect(request).toEqual({ eventId: 'event-1', expectedReportVersion: 3, alertIds: ['alert-aaaaaaaa'], agentNote: 'Call me' })
    expect(sendAlertReport.mock.calls[1][1]).toBe(key)
    expect(await screen.findByText(/Sent/)).toBeInTheDocument()
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

  it('offers a follow-up email only for an informed client the CRM knows', async () => {
    const crmClient = '55555555-5555-4555-8555-555555555555'
    listEvents.mockResolvedValue({ items: [CARD] })
    getEvent.mockResolvedValue({
      ...DETAIL,
      clients: [
        { ...DETAIL.clients[0], alertId: '50000000-0000-4000-8000-000000000001', clientRef: crmClient, status: 'INFORMED' },
        { ...DETAIL.clients[1], status: 'INFORMED' },
      ],
    })
    render(<EventBriefings />)

    fireEvent.click(await screen.findByText('Review affected clients'))

    const links = await screen.findAllByRole('link', { name: /Draft email/ })
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href',
      `/agent/drafts?purpose=ALERT_FOLLOW_UP&clientId=${crmClient}&alertId=50000000-0000-4000-8000-000000000001`)
  })
})
