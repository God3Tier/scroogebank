import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getEvent } from '../api/alertEvents.js'
import { getAlert, listAlerts, recordDecision, recordFeedback } from '../api/alerts.js'
import { sendAlertReport } from '../api/engagement.js'
import AlertWorkspace from './AlertWorkspace.jsx'

vi.mock('../api/alertEvents.js', () => ({ getEvent: vi.fn() }))
vi.mock('../api/engagement.js', () => ({ isCrmClientId: (value) => /^[0-9a-f-]{36}$/.test(value), sendAlertReport: vi.fn() }))
vi.mock('../utils/reportPdf.js', () => ({ downloadReportPdf: vi.fn().mockResolvedValue() }))
vi.mock('../api/alerts.js', () => ({
  listAlerts: vi.fn(),
  getAlert: vi.fn(),
  recordDecision: vi.fn(),
  recordFeedback: vi.fn(),
}))

const alert = {
  id: '50000000-0000-4000-8000-000000000001',
  clientRef: 'syn-client-001',
  instrumentId: 'SYN-SGX-001',
  headline: 'Synthetic acquisition review',
  status: 'NEW',
  score: 82.4,
  createdAt: '2026-09-28T00:04:00Z',
  version: 0,
}

const EVENT_ID = '10000000-0000-4000-8000-000000000001'

const detail = {
  ...alert,
  eventId: EVENT_ID,
  exposure: { amountSgd: '128450.00', portfolioPercentage: 18.6, valuationAsOf: '2026-09-27T23:00:00Z', freshness: 'FRESH' },
  explanationFactors: [{ code: 'CLIENT_EXPOSURE', contribution: 31.2, description: 'Material synthetic exposure.' }],
  evidence: [{ title: 'Issuer announcement', url: 'https://example.invalid/source', sourceDomain: 'example.invalid', retrievedAt: '2026-09-27T23:55:00Z' }],
  briefing: { summary: 'Review the cited event.', aiGenerated: true, modelVersion: 'fixture-v1', promptVersion: 'brief-v1', uncertainty: 'Terms remain preliminary.' },
  latestDecision: null,
}

describe('AlertWorkspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listAlerts.mockResolvedValue({ items: [alert], nextCursor: null })
    getAlert.mockResolvedValue(detail)
    getEvent.mockResolvedValue({ report: null })
    recordDecision.mockResolvedValue({
      id: 'decision-1', alertId: alert.id, decision: 'MONITOR',
      previousStatus: 'NEW', newStatus: 'MONITORING',
      actorSubject: 'syn-agent-001', decidedAt: '2026-09-28T00:05:00Z', alertVersion: 1,
    })
    recordFeedback.mockResolvedValue({ id: 'feedback-1', rating: 'USEFUL' })
  })

  it('shows assigned evidence, exposure, and explicit action consequences', async () => {
    render(<AlertWorkspace />)

    expect(await screen.findByText('Synthetic acquisition review')).toBeInTheDocument()
    expect(await screen.findByText('SGD 128,450.00')).toBeInTheDocument()
    expect(screen.getByText('[1] Issuer announcement')).toBeInTheDocument()
    expect(screen.getByText('No client contact is triggered')).toBeInTheDocument()
    expect(getAlert).toHaveBeenCalledWith(alert.id)
  })

  it('records the expected version and displays completion', async () => {
    render(<AlertWorkspace />)
    await screen.findByText('[1] Issuer announcement')

    fireEvent.click(screen.getByRole('button', { name: 'Record decision' }))

    await waitFor(() => expect(recordDecision).toHaveBeenCalledWith(
      alert.id,
      { decision: 'MONITOR', expectedVersion: 0, note: null },
    ))
    expect(await screen.findByText('Continue monitoring recorded.')).toBeInTheDocument()
    expect(screen.getByText('Monitoring', { selector: '.status-badge' })).toBeInTheDocument()
    expect(screen.getByLabelText('Latest recorded decision')).toHaveTextContent('Continue monitoring')
    expect(screen.getByLabelText('Latest recorded decision')).toHaveTextContent('alert v1')
  })

  it('prevents invalid terminal decisions while keeping feedback available', async () => {
    getAlert.mockResolvedValue({
      ...detail,
      status: 'INFORMED',
      version: 1,
      latestDecision: {
        decision: 'INFORM_CLIENT',
        actorSubject: 'syn-agent-001',
        decidedAt: '2026-09-28T00:05:00Z',
        alertVersion: 1,
      },
    })

    render(<AlertWorkspace />)

    expect(await screen.findByText('Decision complete.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Yes' })).toBeInTheDocument()
    expect(screen.getByLabelText('Latest recorded decision')).toHaveTextContent('Plan client briefing')
  })

  const report = {
    version: 2, versionCount: 2, schemaVersion: 'alert-client-report-v2', disclaimer: 'For information only.', portfolioLines: {},
    report: {
      headline: 'DBS beats estimates', executiveSummary: [{ text: 'DBS reported higher profit [1][2].' }],
      keyFacts: [], whyItMatters: [{ kind: 'FACT', text: 'Banks are in focus.' }], whatToMonitor: ['Guidance'], uncertainty: 'Two partial excerpts.',
    },
    sources: [
      { url: 'https://a.example/1', title: 'DBS results', domain: 'a.example', publishedAt: '2026-11-02T01:00:00Z', retrievedAt: '2026-11-02T02:00:00Z', usedInReport: true },
      { url: 'https://b.example/2', title: 'Profit up', domain: 'b.example', publishedAt: '2026-11-02T01:30:00Z', retrievedAt: '2026-11-02T02:00:00Z', usedInReport: true },
      { url: 'https://c.example/3', title: 'Other story', domain: 'c.example', publishedAt: null, retrievedAt: '2026-11-02T02:00:00Z', usedInReport: false },
    ],
  }

  it('shows the combined report for the event and lists every source it combines', async () => {
    getEvent.mockResolvedValue({ report })

    render(<AlertWorkspace />)

    expect(await screen.findByText('DBS beats estimates')).toBeInTheDocument()
    expect(getEvent).toHaveBeenCalledWith(EVENT_ID)
    expect(screen.getByText('Combined report')).toBeInTheDocument()
    expect(screen.getByText('DBS reported higher profit [1][2].')).toBeInTheDocument()
    expect(screen.getByText('Sources combined in this report')).toBeInTheDocument()
    expect(screen.getByText('2 of 3 linked sources used in the report.')).toBeInTheDocument()
    expect(screen.getByText('[3] Other story')).toBeInTheDocument()
    expect(screen.getByText('Linked, not used in the summary')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download report as PDF' })).toBeInTheDocument()
  })

  it('lets the agent email the report to this client only after it is approved', async () => {
    const crmId = '14290389-d5fd-4d78-b6ca-780013a65954'
    getEvent.mockResolvedValue({ report })
    getAlert.mockResolvedValue({ ...detail, clientRef: crmId, status: 'INFORMED', version: 1 })
    sendAlertReport.mockResolvedValue({ recipients: [{ alertId: alert.id, status: 'SENT', errorCode: null }] })

    render(<AlertWorkspace />)
    const send = await screen.findByRole('button', { name: 'Send report to this client' })
    expect(send).toBeDisabled()
    fireEvent.click(screen.getByLabelText(/I have reviewed version 2/))
    fireEvent.change(screen.getByLabelText(/Note to the client/), { target: { value: 'Call me' } })
    fireEvent.click(send)

    await waitFor(() => expect(sendAlertReport).toHaveBeenCalledWith(
      { eventId: EVENT_ID, expectedReportVersion: 2, alertIds: [alert.id], agentNote: 'Call me' }, expect.any(String)))
    expect(await screen.findByText('Sent')).toBeInTheDocument()
  })

  it('offers no sending before the client is marked to be informed', async () => {
    getEvent.mockResolvedValue({ report })

    render(<AlertWorkspace />)
    await screen.findByText('DBS beats estimates')

    expect(screen.queryByRole('button', { name: 'Send report to this client' })).not.toBeInTheDocument()
  })

  it('keeps administrator oversight read-only', async () => {
    render(<AlertWorkspace readOnly />)

    expect(await screen.findByText('Read-only oversight.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
  })

  it('explains an empty assigned queue', async () => {
    listAlerts.mockResolvedValue({ items: [], nextCursor: null })

    render(<AlertWorkspace />)

    expect(await screen.findByText('No alerts need review')).toBeInTheDocument()
    expect(screen.getByText('New assigned alerts will appear here after deterministic checks pass.')).toBeInTheDocument()
  })

  it('keeps the queue usable when alert detail fails', async () => {
    getAlert.mockRejectedValue(Object.assign(new Error('Unavailable'), { status: 503 }))

    render(<AlertWorkspace />)

    expect(await screen.findByText('Alert detail could not be loaded')).toBeInTheDocument()
    expect(screen.getByText('Synthetic acquisition review')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
