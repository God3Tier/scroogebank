import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { sendAlertReport } from '../api/engagement.js'
import TickerReports from './TickerReports.jsx'

vi.mock('../api/alertEvents.js', () => ({ listEvents: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn() }))
vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/engagement.js', () => ({
  isCrmClientId: (value) => /^[0-9a-f]{8}-[0-9a-f]{4}-/.test(value),
  sendAlertReport: vi.fn(),
}))
vi.mock('react-oidc-context', () => ({ useAuth: () => ({ user: { profile: { name: 'Alice Agent', email: 'agent1@example.test' } } }) }))
vi.mock('../utils/reportPdf.js', async (importOriginal) => ({ ...(await importOriginal()), downloadReportPdf: vi.fn().mockResolvedValue() }))

const CHLOE = '14290389-d5fd-4d78-b6ca-780013a65954'
const DANIEL = '1c6a56c3-097e-4b9c-8939-bd9b20fed5dd'

const evidence = { title: 'x', url: 'https://a.example/1', sourceDomain: 'a.example', publishedAt: null, retrievedAt: '2026-11-05T09:43:00Z' }
const appleCard = {
  eventId: 'e1', headline: 'Apple beats revenue estimates', summary: 'Apple reported higher revenue.', aiGenerated: true, uncertainty: null,
  evidence, affectedClientCount: 2, openClientCount: 1, topScore: 72.5, latestAlertAt: '2026-11-05T09:43:00Z',
  instrumentId: 'NASDAQ-AAPL', ticker: 'AAPL', issuer: 'Apple Inc', sourceCount: 3, reportVersion: 1,
}
const dbsCard = { ...appleCard, eventId: 'e2', headline: 'DBS profit rises', issuer: 'DBS Group', ticker: 'D05', instrumentId: 'SGX-D05', sourceCount: 1, affectedClientCount: 1, openClientCount: 1, topScore: 65 }

const report = {
  version: 1, versionCount: 1, schemaVersion: 'alert-client-report-v2', disclaimer: 'For information only.', portfolioLines: {},
  report: {
    headline: 'Apple beats revenue estimates', executiveSummary: [{ text: 'Apple reported higher revenue.', sourceRefs: ['s1', 's2'] }],
    keyFacts: [], whyItMatters: [{ kind: 'FACT', text: 'Apple is widely held.', sourceRefs: ['s1'] }], whatToMonitor: ['Guidance', 'Analyst targets'], uncertainty: 'Partial excerpts.',
  },
  sources: [
    { id: 's1', url: 'https://a.example/1', title: 'Apple results', domain: 'a.example', publishedAt: '2026-11-02T01:00:00Z', retrievedAt: '2026-11-05T09:43:00Z', usedInReport: true },
    { id: 's2', url: 'https://b.example/2', title: 'Revenue beat', domain: 'b.example', publishedAt: '2026-11-02T02:00:00Z', retrievedAt: '2026-11-05T09:43:00Z', usedInReport: true },
    { id: 's3', url: 'https://c.example/3', title: 'Other story', domain: 'c.example', publishedAt: null, retrievedAt: '2026-11-05T09:43:00Z', usedInReport: false },
  ],
}

const client = (alertId, clientRef, status, version = 0) => ({
  alertId, clientRef, accountRef: 'acc-1', instrumentId: 'NASDAQ-AAPL', status, score: 70, portfolioPercentage: 30, amountSgd: '36000.00', version,
})
const appleDetail = (clients) => ({ card: appleCard, clients, latestAction: null, report })

describe('TickerReports', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listEvents.mockResolvedValue({ items: [appleCard, dbsCard], nextCursor: null })
    getEvent.mockImplementation(async (id) => (id === 'e1'
      ? appleDetail([client('a1', CHLOE, 'NEW'), client('a2', DANIEL, 'INFORMED', 1)])
      : { card: dbsCard, clients: [client('a3', CHLOE, 'NEW')], latestAction: null, report: null }))
    getClient.mockImplementation(async (id) => (id === CHLOE ? { first_name: 'Chloe', last_name: 'Tan' } : { first_name: 'Daniel', last_name: 'Lim' }))
    recordEventAction.mockResolvedValue({ members: [{ alertId: 'a1', outcome: 'APPLIED' }] })
    sendAlertReport.mockResolvedValue({ recipients: [{ alertId: 'a2', status: 'SENT', errorCode: null }] })
  })

  it('lists one row per ticker and shows the selected ticker\'s report, sources and clients', async () => {
    render(<TickerReports />)

    const queue = await screen.findByRole('complementary', { name: 'Tickers' })
    expect(within(queue).getByText('Apple Inc (AAPL)')).toBeInTheDocument()
    expect(within(queue).getByText('DBS Group (D05)')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Apple Inc (AAPL)', level: 2 })).toBeInTheDocument()
    expect(getEvent).toHaveBeenCalledWith('e1')
    expect(await screen.findByText('Apple reported higher revenue. [1] [2]')).toBeInTheDocument()
    expect(screen.getByText('Sources combined in this report')).toBeInTheDocument()
    expect(screen.getByText('2 of 3 linked sources used in the report.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download report as PDF' })).toBeInTheDocument()
    expect(await screen.findByText('Chloe Tan')).toBeInTheDocument()
    expect(await screen.findByText('Daniel Lim')).toBeInTheDocument()
  })

  it('opens another ticker when its row is chosen, and says when a report is not written yet', async () => {
    render(<TickerReports />)
    await screen.findByText('Chloe Tan')

    fireEvent.click(screen.getByRole('button', { name: /DBS Group \(D05\)/ }))

    await waitFor(() => expect(getEvent).toHaveBeenCalledWith('e2'))
    expect(await screen.findByText(/still being written/)).toBeInTheDocument()
  })

  it('applies a decision to the selected clients that are still open, and nobody else', async () => {
    render(<TickerReports />)
    await screen.findByText('Chloe Tan')

    fireEvent.click(screen.getByLabelText('Select Chloe Tan'))
    fireEvent.click(screen.getByLabelText('Select Daniel Lim'))
    fireEvent.click(screen.getByLabelText(/Plan client briefing/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected clients' }))

    await waitFor(() => expect(recordEventAction).toHaveBeenCalledWith(
      'e1', { decision: 'INFORM_CLIENT', note: '', members: [{ alertId: 'a1', expectedVersion: 0 }] }))
    expect(await screen.findByText('Chloe Tan: Done')).toBeInTheDocument()
  })

  it('sends the report only to selected clients marked Inform, and only after it is approved', async () => {
    render(<TickerReports />)
    await screen.findByText('Daniel Lim')
    const send = screen.getByRole('button', { name: /Send report to 0 selected clients/ })
    expect(send).toBeDisabled()

    fireEvent.click(screen.getByLabelText('Select Daniel Lim'))
    fireEvent.change(screen.getByLabelText(/Note to the selected clients/), { target: { value: 'Call me' } })
    expect(screen.getByRole('button', { name: 'Send report to 1 selected client' })).toBeDisabled()
    fireEvent.click(screen.getByLabelText(/I have reviewed version 1/))
    fireEvent.click(screen.getByRole('button', { name: 'Send report to 1 selected client' }))

    await waitFor(() => expect(sendAlertReport).toHaveBeenCalledWith(
      { eventId: 'e1', expectedReportVersion: 1, alertIds: ['a2'], agentNote: 'Call me' }, expect.any(String)))
    expect(await screen.findByText('Daniel Lim: Sent')).toBeInTheDocument()
  })

  it('offers no sending while nobody is marked Inform', async () => {
    getEvent.mockResolvedValue(appleDetail([client('a1', CHLOE, 'NEW')]))
    render(<TickerReports />)
    await screen.findByText('Chloe Tan')

    expect(screen.queryByText('Email this report to clients')).not.toBeInTheDocument()
  })

  it('shows a client\'s short ID when the CRM name cannot be read', async () => {
    getClient.mockRejectedValue(Object.assign(new Error('x'), { status: 503 }))
    render(<TickerReports />)

    expect(await screen.findAllByText(CHLOE.slice(0, 8))).not.toHaveLength(0)
  })

  it('explains an empty list and a list that cannot load', async () => {
    listEvents.mockResolvedValueOnce({ items: [], nextCursor: null })
    const { unmount } = render(<TickerReports />)
    expect(await screen.findByText('No tickers need review')).toBeInTheDocument()
    unmount()

    listEvents.mockRejectedValueOnce(Object.assign(new Error('x'), { status: 503 }))
    render(<TickerReports />)
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be verified right now')
  })
})
