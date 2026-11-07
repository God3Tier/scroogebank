import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  approveReport, getEvent, getReportReview, listApprovedReports, listEvents, listSentOut, recordEventAction, saveReportEdit,
} from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { createNewsDrafts, listOpenNewsAlerts } from '../api/engagement.js'
import TickerReports, { ON_DRAFT_MESSAGE } from './TickerReports.jsx'

vi.mock('../api/alertEvents.js', () => ({
  listEvents: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn(), getReportReview: vi.fn(), saveReportEdit: vi.fn(),
  discardReportEdit: vi.fn(), approveReport: vi.fn(), downloadReportPdf: vi.fn(), listSentOut: vi.fn(), listApprovedReports: vi.fn(),
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
const severity = { points: 18, maximum: 30, eventType: 'EARNINGS', direction: 'POSITIVE' }
const appleDetail = (clients) => ({ card: appleCard, clients, latestAction: null, report, severity })

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
    listApprovedReports.mockResolvedValue([])
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
    expect(screen.getAllByRole('checkbox', { name: /^Select (?!all)/ })).toHaveLength(2)
    expect(screen.queryByText('Continue monitoring')).not.toBeInTheDocument()
  })

  it('shows the event severity in why this appeared', async () => {
    renderPage()

    expect(await screen.findByText(/Event severity: 18 of 30 \(earnings\)/)).toBeInTheDocument()
    // The Fresh or Stale label depends on today's date, so NewsFreshnessNotice.test.jsx covers it with a fixed clock.
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

  it('says All done in one line when every client was changed, instead of listing each one', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Chloe Tan' }))

    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected clients' }))

    expect(await screen.findByText('All done')).toBeInTheDocument()
    expect(screen.getByText(/for 1 client\./)).toBeInTheDocument()
    expect(screen.queryByText('Chloe Tan: Done')).not.toBeInTheDocument()
  })

  it('names only the clients that could not be changed when some could not', async () => {
    recordEventAction.mockResolvedValue({ members: [{ alertId: 'a1', outcome: 'APPLIED' }, { alertId: 'a2', outcome: 'STALE_VERSION' }] })
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Chloe Tan' }))
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Daniel Lim' }))

    fireEvent.click(screen.getByRole('button', { name: 'Apply to selected clients' }))

    expect(await screen.findByText('1 done.')).toBeInTheDocument()
    expect(screen.getByText('Daniel Lim: Changed since you looked, reload')).toBeInTheDocument()
    expect(screen.queryByText('Chloe Tan: Done')).not.toBeInTheDocument()
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

  it('edits the report in one text box in place, then previews it, checking and saving the edit', async () => {
    saveReportEdit.mockResolvedValue(review(null, { editedSections: { ...sections, uncertainty: 'Changed.' } }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))

    const box = screen.getByRole('textbox', { name: 'Report text' })
    expect(screen.queryByLabelText('Sentence 1')).not.toBeInTheDocument()
    expect(box.value).toContain('EXECUTIVE SUMMARY')
    expect(screen.getByRole('heading', { name: 'Apple beats revenue estimates' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Approve Apple Inc report/ })).toBeDisabled()
    expect(screen.getByText('Click Save and preview to save your edits first.')).toBeInTheDocument()

    fireEvent.change(box, { target: { value: box.value.replace('Partial excerpts.', 'Changed.') } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and preview' }))

    await waitFor(() => expect(saveReportEdit).toHaveBeenCalledWith('e1', 1, { ...sections, uncertainty: 'Changed.' }))
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Report text' })).not.toBeInTheDocument()
  })

  it('asks before saving a changed edit, and No keeps the agent in Edit with nothing saved', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    const box = screen.getByRole('textbox', { name: 'Report text' })
    fireEvent.change(box, { target: { value: box.value.replace('Partial excerpts.', 'Changed.') } })
    window.confirm.mockReturnValue(false)

    fireEvent.click(screen.getByRole('button', { name: 'Save and preview' }))

    expect(window.confirm).toHaveBeenCalledWith('Save your changes and preview the report?')
    expect(saveReportEdit).not.toHaveBeenCalled()
    expect(screen.getByRole('textbox', { name: 'Report text' }).value).toContain('Changed.')
  })

  it('goes straight back to the preview, without asking, when nothing was changed', async () => {
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    window.confirm.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'Save and preview' }))

    expect(window.confirm).not.toHaveBeenCalled()
    expect(saveReportEdit).not.toHaveBeenCalled()
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeInTheDocument()
  })

  it('stays in Edit and names the line when the text or the server refuses an edit', async () => {
    saveReportEdit.mockRejectedValueOnce(Object.assign(new Error('The figure 42 is not in the sources this sentence cites'), {
      status: 422, problem: { section: 'executiveSummary', index: 0 },
    }))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    const box = screen.getByRole('textbox', { name: 'Report text' })
    const original = box.value

    fireEvent.change(box, { target: { value: original.replace('2. Services grew.\n', '') } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and preview' }))
    expect(await screen.findByText(/Line 1: Executive summary must keep exactly 2 items; there is 1/)).toBeInTheDocument()
    expect(saveReportEdit).not.toHaveBeenCalled()

    fireEvent.change(box, { target: { value: original.replace('Apple reported higher revenue.', 'Revenue rose 42%.') } })
    fireEvent.click(screen.getByRole('button', { name: 'Save and preview' }))
    expect(await screen.findByText(/Line 2: Executive summary, item 1: The figure 42 is not in the sources/)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Report text' })).toBeInTheDocument()
  })

  it('warns that approval is final, then replaces the approve button with Notify', async () => {
    approveReport.mockResolvedValue(approval())
    getReportReview.mockResolvedValueOnce(review()).mockResolvedValue(review(approval()))
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))

    fireEvent.click(screen.getByRole('button', { name: 'Approve Apple Inc report for 1 selected client' }))

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('Approval is final'))
    await waitFor(() => expect(approveReport).toHaveBeenCalledWith('e1', 1))
    expect(await screen.findByRole('button', { name: 'Notify selected clients about Apple Inc' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Approve Apple Inc report/ })).not.toBeInTheDocument()
    expect(screen.getByText(/Approved by you at/)).toBeInTheDocument()
    expect(screen.getByText(/Approved 2 days ago, can be sent for 5 more days/)).toBeInTheDocument()
  })

  it('does not approve when the agent declines the warning', async () => {
    window.confirm.mockReturnValue(false)
    renderPage()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve Apple Inc report for 1 selected client' }))
    expect(approveReport).not.toHaveBeenCalled()
  })

  it('notifies the selected clients and opens their draft in Follow-up Emails', async () => {
    getReportReview.mockResolvedValue(review(approval({ edited: true })))
    createNewsDrafts.mockResolvedValue({ drafts: [{ draftId: 'd1', clientId: DANIEL, created: true, reportCount: 1, added: ['a2'] }], skipped: [] })
    renderPage()
    expect(await screen.findByText(/Approved version 1, with your edits/)).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Chloe Tan' }))

    fireEvent.click(screen.getByRole('button', { name: 'Notify selected clients about Apple Inc' }))

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
    expect(screen.getByRole('button', { name: 'Approve Apple Inc report again' })).toBeEnabled()
  })

  it('opens another ticker when its row is chosen, and says when a report is not written yet', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Apple Inc (AAPL)' })

    fireEvent.click(screen.getByRole('button', { name: /DBS Group \(D05\)/ }))

    expect(await screen.findByRole('heading', { name: 'DBS Group (D05)' })).toBeInTheDocument()
    expect(screen.getByText(/still being written/)).toBeInTheDocument()
  })

  describe('notifying several tickers', () => {
    const READY = [
      { alertId: 'a2', clientRef: DANIEL, eventId: 'e1', approvalId: 'p1', issuer: 'Apple Inc' },
      { alertId: 'a3', clientRef: CHLOE, eventId: 'e2', approvalId: 'p2', issuer: 'DBS Group' },
    ]

    it('makes drafts for every ready client across tickers, then opens the Follow-up Emails list', async () => {
      listApprovedReports.mockResolvedValue(READY)
      createNewsDrafts.mockResolvedValueOnce({ drafts: [{ draftId: 'd1' }], skipped: [] })
        .mockResolvedValueOnce({ drafts: [{ draftId: 'd2' }], skipped: [{ alertId: 'x', reason: 'ALREADY_ON_DRAFT' }] })
      renderPage()

      fireEvent.click(await screen.findByRole('button', { name: 'Notify all ready clients (2 clients, 2 reports)' }))

      await waitFor(() => expect(createNewsDrafts).toHaveBeenCalledTimes(2))
      expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e1', alertIds: ['a2'] })
      expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e2', alertIds: ['a3'] })
      expect(await screen.findByText('Follow-up Emails page')).toBeInTheDocument()
    })

    it('describes what each button does', async () => {
      renderPage()
      expect(await screen.findByText(/Makes a draft for every client who is in Plan client briefing and whose report you have approved/)).toBeInTheDocument()
      expect(screen.getByText(/Your ticks are kept when you switch ticker/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Notify all ready clients (0 clients, 0 reports)' })).toBeDisabled()
    })

    it('keeps the ticks when switching ticker and notifies only the ticked clients', async () => {
      listApprovedReports.mockResolvedValue(READY)
      createNewsDrafts.mockResolvedValue({ drafts: [{ draftId: 'd1' }], skipped: [] })
      renderPage()
      expect(await screen.findByRole('button', { name: 'Notify ticked clients (0 clients, 0 reports)' })).toBeDisabled()

      fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' }))
      fireEvent.click(screen.getByRole('button', { name: /DBS Group \(D05\)/ }))
      fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Chloe Tan' }))
      fireEvent.click(screen.getByRole('button', { name: /Apple Inc \(AAPL\)/ }))
      expect(await screen.findByRole('checkbox', { name: 'Select Daniel Lim' })).toBeChecked()

      fireEvent.click(screen.getByRole('button', { name: 'Notify ticked clients (2 clients, 2 reports)' }))

      await waitFor(() => expect(createNewsDrafts).toHaveBeenCalledTimes(2))
      expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e1', alertIds: ['a2'] })
      expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e2', alertIds: ['a3'] })
    })

    it('says why no draft was made', async () => {
      listApprovedReports.mockResolvedValue(READY.slice(0, 1))
      createNewsDrafts.mockResolvedValue({ drafts: [], skipped: [{ alertId: 'a2', reason: 'DRAFT_BEING_SENT' }] })
      renderPage()
      fireEvent.click(await screen.findByRole('button', { name: /Notify all ready clients/ }))
      expect(await screen.findByText('No draft was made: their draft is being sent.')).toBeInTheDocument()
    })

    it('explains the per-ticker Notify, which still opens that client draft', async () => {
      getReportReview.mockResolvedValue(review(approval()))
      renderPage()
      expect(await screen.findByText(/Working through several tickers\? Use "Notify all ready clients" at the top of this page/)).toBeInTheDocument()
    })

    describe('the floating helper', () => {
      let observe
      beforeEach(() => {
        observe = null
        vi.stubGlobal('IntersectionObserver', class {
          constructor(callback) { observe = callback }
          observe() {}
          disconnect() {}
        })
        Element.prototype.scrollIntoView = vi.fn()
      })
      afterEach(() => vi.unstubAllGlobals())

      it('appears only when the buttons are out of view and two reports are ready, and scrolls back to them', async () => {
        listApprovedReports.mockResolvedValue(READY)
        renderPage()
        await screen.findByRole('button', { name: /Notify all ready clients \(2/ })
        expect(screen.queryByRole('button', { name: /Several tickers\? Notify them all at once/ })).not.toBeInTheDocument()

        act(() => observe([{ isIntersecting: false }]))
        const hint = await screen.findByRole('button', { name: /Several tickers\? Notify them all at once/ })
        expect(hint).toHaveAttribute('title', expect.stringContaining('Click to jump to the buttons at the top'))
        fireEvent.click(hint)
        expect(Element.prototype.scrollIntoView).toHaveBeenCalled()

        act(() => observe([{ isIntersecting: true }]))
        expect(screen.queryByRole('button', { name: /Several tickers\? Notify them all at once/ })).not.toBeInTheDocument()
      })

      it('stays hidden with fewer than two ready reports', async () => {
        listApprovedReports.mockResolvedValue(READY.slice(0, 1))
        renderPage()
        await screen.findByRole('button', { name: /Notify all ready clients \(1/ })
        act(() => observe([{ isIntersecting: false }]))
        expect(screen.queryByRole('button', { name: /Several tickers\? Notify them all at once/ })).not.toBeInTheDocument()
      })
    })
  })

  it('reads an old Monitoring status as history, not a choice', async () => {
    getEvent.mockImplementation(async () => appleDetail([client('a1', CHLOE, 'MONITORING')]))
    renderPage()
    expect(await screen.findByText('Monitoring (old option)', { selector: 'td' })).toBeInTheDocument()
  })

  it('looks for new tickers and reports by itself, and keeps what is on screen', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const shell = { ...appleCard, eventId: 'e9', headline: 'Shell lifts dividend', issuer: 'Shell plc', ticker: 'SHEL', instrumentId: 'LSE-SHEL' }
      renderPage()
      await screen.findByRole('heading', { name: 'Apple Inc (AAPL)' })
      expect(screen.getByText(/Checks for new reports by itself every 20 seconds/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Shell plc/ })).not.toBeInTheDocument()
      listEvents.mockResolvedValue({ items: [shell, appleCard, dbsCard], nextCursor: null })

      await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })

      expect(await screen.findByRole('button', { name: /Shell plc \(SHEL\)/ })).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Apple Inc (AAPL)' })).toBeInTheDocument()
      listEvents.mockRejectedValue(new Error('down'))
      await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
      expect(screen.getByRole('button', { name: /Shell plc \(SHEL\)/ })).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows a report by itself once it is written', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      getEvent.mockImplementation(async () => ({ card: appleCard, clients: [client('a1', CHLOE, 'NEW')], latestAction: null, report: null }))
      renderPage()
      expect(await screen.findByText(/still being written. This page updates by itself/)).toBeInTheDocument()
      getEvent.mockImplementation(async () => appleDetail([client('a1', CHLOE, 'NEW')]))

      await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })

      expect(await screen.findByText('Apple reported higher revenue. [1] [2]')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('never shows the agent why a report is missing: a refused draft reads the same as one in progress', async () => {
    const refused = { ...appleCard, reportFailure: 'INVALID_MODEL_SCHEMA', summary: 'The AI wrote one sentence.' }
    listEvents.mockResolvedValue({ items: [refused], nextCursor: null })
    getEvent.mockImplementation(async () => ({ card: refused, clients: [client('a1', CHLOE, 'NEW')], latestAction: null, report: null }))
    renderPage()

    expect(await screen.findByText(/The report for this ticker is still being written/)).toBeInTheDocument()
    expect(screen.queryByText(/did not pass the checks/)).not.toBeInTheDocument()
    expect(screen.queryByText(/invalid model schema/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/What the AI said/)).not.toBeInTheDocument()
    expect(screen.queryByText(/The AI could not write a report/)).not.toBeInTheDocument()
  })

  it('says, highlighted, that nothing was found in the past 4 hours when no ticker is listed', async () => {
    listEvents.mockResolvedValue({ items: [], nextCursor: null })
    renderPage()

    const notice = await screen.findByText('No news found in the past 4 hours.')
    expect(notice.closest('.queue-empty__notice')).toBeInTheDocument()
    expect(screen.getByText('No tickers need review')).toBeInTheDocument()
  })

  it('keeps Download off while editing, so the PDF always has the saved edit', async () => {
    renderPage()
    const download = await screen.findByRole('button', { name: 'Download report as PDF' })
    expect(download).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))

    expect(screen.getByRole('button', { name: 'Save and preview to download' })).toBeDisabled()
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
