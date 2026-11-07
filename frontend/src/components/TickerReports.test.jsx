import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  approveReport, getEvent, getReportReview, listEvents, listSentOut, recordEventAction, saveReportEdit,
} from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { createNewsDrafts, listOpenNewsAlerts } from '../api/engagement.js'
import TickerReports, { ON_DRAFT_MESSAGE } from './TickerReports.jsx'

vi.mock('../api/alertEvents.js', () => ({
  listEvents: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn(), getReportReview: vi.fn(), saveReportEdit: vi.fn(),
  discardReportEdit: vi.fn(), approveReport: vi.fn(), downloadReportPdf: vi.fn(), listSentOut: vi.fn(),
}))
vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/engagement.js', () => ({
  isCrmClientId: (value) => /^[0-9a-f]{8}-[0-9a-f]{4}-/.test(value),
  createNewsDrafts: vi.fn(),
  listOpenNewsAlerts: vi.fn(),
}))

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
  createdAt: '2026-11-05T09:43:00Z',
  report: {
    headline: 'Apple beats revenue estimates',
    executiveSummary: [{ text: 'Apple reported higher revenue.', sourceRefs: ['s1', 's2'] }, { text: 'Services grew.', sourceRefs: ['s1'] }],
    keyFacts: [], whyItMatters: [{ kind: 'FACT', text: 'Apple is widely held.', sourceRefs: ['s1'] }], whatToMonitor: ['Guidance', 'Analyst targets'], uncertainty: 'Partial excerpts.',
  },
  sources: [
    { id: 's1', url: 'https://a.example/1', title: 'Apple results', domain: 'a.example', publishedAt: '2026-11-02T01:00:00Z', retrievedAt: '2026-11-05T09:43:00Z', usedInReport: true },
    { id: 's2', url: 'https://b.example/2', title: 'Revenue beat', domain: 'b.example', publishedAt: '2026-11-02T02:00:00Z', retrievedAt: '2026-11-05T09:43:00Z', usedInReport: true },
    { id: 's3', url: 'https://c.example/3', title: 'Other story', domain: 'c.example', publishedAt: null, retrievedAt: '2026-11-05T09:43:00Z', usedInReport: false },
  ],
}
const sections = {
  executiveSummary: ['Apple reported higher revenue.', 'Services grew.'], keyFacts: [], whyItMatters: ['Apple is widely held.'],
  whatToMonitor: ['Guidance', 'Analyst targets'], uncertainty: 'Partial excerpts.',
}
const review = (approval = null, extra = {}) => ({
  eventId: 'e1', reportVersion: 1, editable: true, originalSections: sections, editedSections: null, approval,
  approvedSections: approval ? sections : null, newsDate: '2026-11-02T02:00:00Z', ...extra,
})
const approval = (overrides = {}) => ({
  approvalId: 'ap1', eventId: 'e1', version: 1, edited: false, approvedAt: new Date(Date.now() - 2 * 86400000).toISOString(),
  validUntil: new Date(Date.now() + 5 * 86400000).toISOString(), valid: true, stale: false, ...overrides,
})

const client = (alertId, clientRef, status, version = 0) => ({
  alertId, clientRef, accountRef: 'acc-1', instrumentId: 'NASDAQ-AAPL', status, score: 70, portfolioPercentage: 30, amountSgd: '36000.00', version,
})
const appleDetail = (clients) => ({ card: appleCard, clients, latestAction: null, report })

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/agent/alerts']}>
      <Routes>
        <Route path="/agent/alerts" element={<TickerReports />} />
        <Route path="/agent/drafts" element={<p>Follow-up Emails page</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('TickerReports', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listEvents.mockResolvedValue({ items: [appleCard, dbsCard], nextCursor: null })
    getEvent.mockImplementation(async (id) => (id === 'e1'
      ? appleDetail([client('a1', CHLOE, 'NEW'), client('a2', DANIEL, 'INFORMED', 1), client('a4', CHLOE, 'SENT_OUT', 2)])
      : { card: dbsCard, clients: [client('a3', CHLOE, 'NEW')], latestAction: null, report: null }))
    getReportReview.mockResolvedValue(review())
    getClient.mockImplementation(async (id) => (id === CHLOE ? { first_name: 'Chloe', last_name: 'Tan' } : { first_name: 'Daniel', last_name: 'Lim' }))
    recordEventAction.mockResolvedValue({ members: [{ alertId: 'a1', outcome: 'APPLIED' }] })
    listOpenNewsAlerts.mockResolvedValue({ alertIds: [] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => vi.restoreAllMocks())

  it('lists one row per ticker and shows the report, sources and clients, leaving Sent Out clients out', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Apple Inc (AAPL)' })).toBeInTheDocument()
    expect(screen.getByText('Apple reported higher revenue. [1] [2]')).toBeInTheDocument()
    expect(screen.getByText('2 of 3 linked sources used in the report.')).toBeInTheDocument()
    expect(await screen.findByText('Chloe Tan')).toBeInTheDocument()
    expect(screen.getByText('Daniel Lim')).toBeInTheDocument()
    expect(screen.getByText('Plan client briefing', { selector: 'td' })).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox', { name: /Select/ })).toHaveLength(2)
    expect(screen.queryByText('Continue monitoring')).not.toBeInTheDocument()
  })

  it('offers only Plan client briefing and Dismiss, keeps the clients selected and focuses the report', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Chloe Tan' }))
    expect(screen.getByText(/takes you to review and approve the report/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected clients' }))

    await waitFor(() => expect(recordEventAction).toHaveBeenCalledWith('e1', {
      decision: 'INFORM_CLIENT', note: '', members: [{ alertId: 'a1', expectedVersion: 0 }],
    }))
    expect(await screen.findByText('Saved. Review and approve the report next.')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Select Chloe Tan' })).toBeChecked()
    expect(document.activeElement).toHaveAttribute('aria-labelledby', 'ticker-report-title')
  })

  it('does not dismiss a client who is on an open news draft', async () => {
    listOpenNewsAlerts.mockResolvedValue({ alertIds: ['a2'] })
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
    fireEvent.click(screen.getByRole('radio', { name: /Dismiss/ }))

    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected clients' }))

    expect(await screen.findByText(`Daniel Lim: ${ON_DRAFT_MESSAGE}`)).toBeInTheDocument()
    expect(recordEventAction).not.toHaveBeenCalled()
  })

  it('saves an edit and names the section an edit failed in', async () => {
    saveReportEdit.mockRejectedValueOnce(Object.assign(new Error('The figure 42 is not in the sources this sentence cites'), {
      status: 422, problem: { section: 'executiveSummary', index: 0 },
    }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit report' }))
    fireEvent.change(screen.getByLabelText('Sentence 1'), { target: { value: 'Revenue rose 42%.' } })

    fireEvent.click(screen.getByRole('button', { name: 'Save edits' }))

    expect(await screen.findByText(/Executive summary, item 1: The figure 42 is not in the sources/)).toBeInTheDocument()
    expect(saveReportEdit).toHaveBeenCalledWith('e1', 1, expect.objectContaining({
      executiveSummary: ['Revenue rose 42%.', 'Services grew.'],
    }))
  })

  it('warns that approval is final, then replaces the approve button with Notify', async () => {
    approveReport.mockResolvedValue(approval())
    getReportReview.mockResolvedValueOnce(review()).mockResolvedValue(review(approval()))
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))

    fireEvent.click(screen.getByRole('button', { name: 'Approve report for 1 selected client' }))

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Approval is final'))
    await waitFor(() => expect(approveReport).toHaveBeenCalledWith('e1', 1))
    expect(await screen.findByRole('button', { name: 'Notify selected clients of news' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Approve report/ })).not.toBeInTheDocument()
    expect(screen.getByText(/Approved by you at/)).toBeInTheDocument()
    expect(screen.getByText(/Approved 2 days ago, can be sent for 5 more days/)).toBeInTheDocument()
  })

  it('does not approve when the agent declines the warning', async () => {
    window.confirm.mockReturnValue(false)
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve report for 1 selected client' }))
    expect(approveReport).not.toHaveBeenCalled()
  })

  it('notifies the selected clients and opens their draft in Follow-up Emails', async () => {
    getReportReview.mockResolvedValue(review(approval({ edited: true })))
    createNewsDrafts.mockResolvedValue({ drafts: [{ draftId: 'd1', clientId: DANIEL, created: true, reportCount: 1, added: ['a2'] }], skipped: [] })
    renderPage()
    expect(await screen.findByText(/Approved version 1, with your edits/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Daniel Lim' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Chloe Tan' }))

    fireEvent.click(screen.getByRole('button', { name: 'Notify selected clients of news' }))

    await waitFor(() => expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e1', alertIds: ['a2'] }))
    expect(await screen.findByText('Follow-up Emails page')).toBeInTheDocument()
  })

  it('highlights an approval about to expire and offers Approve again once it has', async () => {
    getReportReview.mockResolvedValue(review(approval({ stale: true })))
    const { unmount } = renderPage()
    expect(await screen.findByText(/Send soon or approve again later/)).toHaveClass('staleness--warn')
    unmount()

    getReportReview.mockResolvedValue(review(approval({ valid: false })))
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
    expect(screen.getByText(/Your approval expired/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve again' })).toBeEnabled()
  })

  it('opens another ticker when its row is chosen, and says when a report is not written yet', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Apple Inc (AAPL)' })

    fireEvent.click(screen.getByRole('button', { name: /DBS Group \(D05\)/ }))

    expect(await screen.findByRole('heading', { name: 'DBS Group (D05)' })).toBeInTheDocument()
    expect(screen.getByText(/still being written/)).toBeInTheDocument()
  })

  it('lists sent reports, read-only, in the Sent out tab', async () => {
    listSentOut.mockResolvedValue([{ alertId: 'a4', eventId: 'e1', clientRef: CHLOE, instrumentId: 'NASDAQ-AAPL', ticker: 'AAPL',
      issuer: 'Apple Inc', headline: 'Apple beats revenue estimates', reportVersion: 1, edited: true, sentAt: '2026-11-06T02:00:00Z' }])
    renderPage()
    fireEvent.click(await screen.findByRole('tab', { name: 'Sent out' }))

    const table = await screen.findByRole('table')
    expect(within(table).getByText('Apple beats revenue estimates')).toBeInTheDocument()
    expect(await within(table).findByText('Chloe Tan')).toBeInTheDocument()
    expect(within(table).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it("shows a client's short ID when the CRM name cannot be read", async () => {
    getClient.mockRejectedValue(new Error('down'))
    renderPage()
    expect(await screen.findByText(CHLOE.slice(0, 8))).toBeInTheDocument()
  })
})
