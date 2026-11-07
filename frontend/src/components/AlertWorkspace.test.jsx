import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getAlert, listAlerts, recordDecision, recordFeedback } from '../api/alerts.js'
import AlertWorkspace from './AlertWorkspace.jsx'

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

const detail = {
  ...alert,
  exposure: { amountSgd: '128450.00', portfolioPercentage: 18.6, valuationAsOf: '2026-09-27T23:00:00Z', freshness: 'FRESH' },
  explanationFactors: [{ code: 'CLIENT_EXPOSURE', contribution: 31.2, description: 'Material synthetic exposure.' },
    { code: 'EVENT_SEVERITY', contribution: 18, description: 'Severity.' }],
  evidence: [{ title: 'Issuer announcement', url: 'https://example.invalid/source', sourceDomain: 'example.invalid', retrievedAt: '2026-09-27T23:55:00Z' }],
  briefing: { summary: 'Review the cited event.', aiGenerated: true, modelVersion: 'fixture-v1', promptVersion: 'brief-v1', uncertainty: 'Terms remain preliminary.' },
  latestDecision: null,
}

describe('AlertWorkspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listAlerts.mockResolvedValue({ items: [alert], nextCursor: null })
    getAlert.mockResolvedValue(detail)
    recordDecision.mockResolvedValue({
      id: 'decision-1', alertId: alert.id, decision: 'INFORM_CLIENT',
      previousStatus: 'NEW', newStatus: 'INFORMED',
      actorSubject: 'syn-agent-001', decidedAt: '2026-09-28T00:05:00Z', alertVersion: 1,
    })
    recordFeedback.mockResolvedValue({ id: 'feedback-1', rating: 'USEFUL' })
  })

  it('shows assigned evidence, exposure, and explicit action consequences', async () => {
    render(<AlertWorkspace />)

    expect(await screen.findByText('Synthetic acquisition review')).toBeInTheDocument()
    expect(await screen.findByText('SGD 128,450.00')).toBeInTheDocument()
    expect(screen.getByText('Issuer announcement')).toBeInTheDocument()
    expect(screen.getByText('No client contact is triggered')).toBeInTheDocument()
    expect(getAlert).toHaveBeenCalledWith(alert.id)
  })

  it('records the expected version and displays completion', async () => {
    render(<AlertWorkspace />)
    await screen.findByText('Issuer announcement')

    fireEvent.click(screen.getByRole('button', { name: 'Record decision' }))

    await waitFor(() => expect(recordDecision).toHaveBeenCalledWith(
      alert.id,
      { decision: 'INFORM_CLIENT', expectedVersion: 0, note: null },
    ))
    expect(await screen.findByText('Decision complete.')).toBeInTheDocument()
    expect(screen.getByText('Plan client briefing', { selector: '.status-badge' })).toBeInTheDocument()
    expect(screen.getByLabelText('Latest recorded decision')).toHaveTextContent('Plan client briefing')
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

  it('keeps administrator oversight read-only', async () => {
    render(<AlertWorkspace readOnly />)

    expect(await screen.findByText('Read-only oversight.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
  })

  it('shows how much of the score each part can add, for the administrator too', async () => {
    render(<AlertWorkspace readOnly />)

    expect(await screen.findByText('of 30')).toBeInTheDocument()
    expect(screen.getByText('EVENT SEVERITY').closest('li')).toHaveTextContent('+18.0 of 30')
  })

  it('shows the same Fresh or Stale label as the agent, from the date of the news, with the valuation labelled apart', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      getAlert.mockResolvedValue({ ...detail, evidence: [{ ...detail.evidence[0], publishedAt: '2026-09-26T00:00:00Z' }] })

      vi.setSystemTime(new Date('2026-09-29T00:00:00Z')) // 3 days after the news
      const { unmount } = render(<AlertWorkspace readOnly />)
      expect(await screen.findByText('Fresh', { selector: '.stale-notice__chip' })).toBeInTheDocument()
      expect(screen.getByRole('note')).toHaveTextContent('News 3 days old.')
      expect(screen.getByText('Valuation freshness')).toBeInTheDocument()
      unmount()

      vi.setSystemTime(new Date('2026-10-08T00:00:00Z')) // 12 days after the news
      render(<AlertWorkspace readOnly />)
      expect(await screen.findByText('Stale', { selector: '.stale-notice__chip' })).toBeInTheDocument()
      expect(screen.getByRole('note')).toHaveTextContent('more than 7 days old')
    } finally {
      vi.useRealTimers()
    }
  })

  it('dates the news by the alert when the sources carry no publication date', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    try {
      vi.setSystemTime(new Date('2026-10-01T00:00:00Z')) // just under 3 days after the alert
      render(<AlertWorkspace readOnly />)
      expect(await screen.findByRole('note')).toHaveTextContent('News 2 days old.')
    } finally {
      vi.useRealTimers()
    }
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
