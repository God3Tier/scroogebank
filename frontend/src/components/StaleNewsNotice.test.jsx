import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { isNewsStale } from '../utils/newsReports.js'
import { StaleNewsNotice } from './ReportReview.jsx'

const NOW = new Date('2026-11-10T00:00:00Z').getTime()
const report = { createdAt: '2026-11-09T00:00:00Z' }

describe('isNewsStale', () => {
  it('is stale only when the news is more than seven days old', () => {
    expect(isNewsStale('2026-11-03T00:00:00Z', NOW)).toBe(false) // exactly 7 days
    expect(isNewsStale('2026-11-02T23:59:00Z', NOW)).toBe(true)
    expect(isNewsStale('2026-11-09T00:00:00Z', NOW)).toBe(false)
  })

  it('does not call a missing or unreadable date stale', () => {
    expect(isNewsStale(null, NOW)).toBe(false)
    expect(isNewsStale('not a date', NOW)).toBe(false)
  })
})

describe('StaleNewsNotice', () => {
  it('shows Stale with its meaning on an unapproved report with old news', () => {
    render(<StaleNewsNotice report={report} review={{ newsDate: '2026-10-20T00:00:00Z', approval: null }} now={NOW} />)
    expect(screen.getByText('Stale')).toBeInTheDocument()
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('more than 7 days old')
    expect(note).toHaveAttribute('title', expect.stringContaining('more than 7 days old'))
  })

  it('shows nothing for fresh news', () => {
    render(<StaleNewsNotice report={report} review={{ newsDate: '2026-11-08T00:00:00Z', approval: null }} now={NOW} />)
    expect(screen.queryByText('Stale')).toBeNull()
  })

  it('shows nothing once the agent has approved the report', () => {
    render(<StaleNewsNotice report={report} review={{ newsDate: '2026-10-20T00:00:00Z', approval: { valid: true } }} now={NOW} />)
    expect(screen.queryByText('Stale')).toBeNull()
  })

  it('falls back to the report date when the review has no news date', () => {
    render(<StaleNewsNotice report={{ createdAt: '2026-10-01T00:00:00Z' }} review={{ approval: null }} now={NOW} />)
    expect(screen.getByText('Stale')).toBeInTheDocument()
  })
})
