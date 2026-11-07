import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import EventAlerts from './EventAlerts.jsx'

vi.mock('../components/AlertWorkspace.jsx', () => ({ default: () => <div>workspace</div> }))
vi.mock('../components/SearchSources.jsx', () => ({ default: () => <div>sources</div> }))
vi.mock('../components/SentOutList.jsx', () => ({ default: () => <div>sent out</div> }))
vi.mock('../components/TickerReports.jsx', () => ({ default: () => <div>tickers</div> }))

describe('EventAlerts back link', () => {
  it('takes an agent back to the agent home', () => {
    render(<MemoryRouter><EventAlerts /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Back to Agent Home' })).toHaveAttribute('href', '/agent')
  })

  it('takes the read-only admin view back to the admin home', () => {
    render(<MemoryRouter><EventAlerts readOnly /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Back to Admin Home' })).toHaveAttribute('href', '/admin')
  })
})
