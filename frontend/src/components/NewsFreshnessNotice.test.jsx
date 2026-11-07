import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { isNewsStale, newsAgeText } from '../utils/newsReports.js'
import { NewsFreshnessNotice } from './ReportReview.jsx'

const NOW = new Date('2026-11-10T00:00:00Z').getTime()
const report = { createdAt: '2026-11-09T00:00:00Z' }

describe('newsAgeText', () => {
  it('reads as today, 1 day or n days', () => {
    expect(newsAgeText('2026-11-10T00:00:00Z', NOW)).toBe('from today')
    expect(newsAgeText('2026-11-09T00:00:00Z', NOW)).toBe('1 day old')
    expect(newsAgeText('2026-11-06T00:00:00Z', NOW)).toBe('4 days old')
    expect(newsAgeText(null, NOW)).toBe('')
  })
})

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

describe('NewsFreshnessNotice', () => {
  it('shows Stale with its meaning on an unapproved report with old news', () => {
    render(<NewsFreshnessNotice report={report} review={{ newsDate: '2026-10-20T00:00:00Z', approval: null }} now={NOW} />)
    expect(screen.getByText('Stale')).toBeInTheDocument()
    const note = screen.getByRole('note')
    expect(note).toHaveTextContent('more than 7 days old')
    expect(note).toHaveAttribute('title', expect.stringContaining('more than 7 days old'))
  })

  it('says Fresh, with how old the news is, until it is more than seven days old', () => {
    render(<NewsFreshnessNotice report={report} review={{ newsDate: '2026-11-06T00:00:00Z', approval: null }} now={NOW} />)
    expect(screen.getByText('Fresh')).toBeInTheDocument()
    expect(screen.queryByText('Stale')).toBeNull()
    expect(screen.getByRole('note')).toHaveTextContent('News 4 days old.')
    expect(screen.getByRole('note')).toHaveAttribute('title', expect.stringContaining('turns stale after 7 days'))
  })

  it('turns from Fresh to Stale as the days pass', () => {
    const review = { newsDate: '2026-11-03T00:00:00Z', approval: null }
    const { rerender } = render(<NewsFreshnessNotice report={report} review={review} now={new Date('2026-11-10T00:00:00Z').getTime()} />)
    expect(screen.getByText('Fresh')).toBeInTheDocument()
    rerender(<NewsFreshnessNotice report={report} review={review} now={new Date('2026-11-10T01:00:00Z').getTime()} />)
    expect(screen.getByText('Stale')).toBeInTheDocument()
    expect(screen.queryByText('Fresh')).toBeNull()
  })

  it('shows nothing once the agent has approved the report', () => {
    render(<NewsFreshnessNotice report={report} review={{ newsDate: '2026-10-20T00:00:00Z', approval: { valid: true } }} now={NOW} />)
    expect(screen.queryByText('Stale')).toBeNull()
    expect(screen.queryByText('Fresh')).toBeNull()
  })

  it('falls back to the report date when the review has no news date', () => {
    render(<NewsFreshnessNotice report={{ createdAt: '2026-10-01T00:00:00Z' }} review={{ approval: null }} now={NOW} />)
    expect(screen.getByText('Stale')).toBeInTheDocument()
  })
})
