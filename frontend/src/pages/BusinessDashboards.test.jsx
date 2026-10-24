import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { getDashboard } from '../api/dashboard.js'
import AdminBusinessDashboard from './AdminBusinessDashboard.jsx'
import AgentBusinessDashboard from './AgentBusinessDashboard.jsx'

vi.mock('../api/dashboard.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getDashboard: vi.fn(),
}))
// Recharts measures its container, which jsdom cannot do.
vi.mock('../components/dashboard/TrendChart.jsx', () => ({ default: () => null }))
vi.mock('../components/dashboard/BreakdownChart.jsx', () => ({ default: () => null }))

const ok = (data) => ({ status: 'OK', data, reason: null })
const unavailable = (reason) => ({ status: 'UNAVAILABLE', data: null, reason })

// Shaped like dashboard-service's responses (docs/api/dashboard-api.yaml).
const CLIENTS = { total: 40, createdInWindow: 6, byVerificationStatus: { VERIFIED: 30, UNVERIFIED: 10 }, dailyCreated: [] }
const ACCOUNTS = {
  total: 55, openedInWindow: 4, bookedInitialDeposits: { SGD: '125000.00' }, currencies: ['SGD'],
  byType: {}, byStatus: {}, byCurrency: { SGD: 55 }, byBranch: {},
}
const TRANSACTIONS = {
  total: 20, byStatus: { Completed: 18, Failed: 2 }, byType: { Deposit: 20 }, currencies: ['SGD'], totalValue: '5000.00',
  daily: [], pendingBacklog: 1,
}
const STALE = {
  staleUnverified: [{ clientId: 'client-9', firstName: 'Stale', lastName: 'Client', createdAt: '2026-09-01T00:00:00Z' }],
  staleUnverifiedTotal: 3,
}

function respond(scope, panelsByEndpoint) {
  getDashboard.mockImplementation((endpoint, params) => Promise.resolve({
    generatedAt: '2026-10-16T03:00:00Z',
    window: { from: params.from, to: params.to },
    scope,
    panels: panelsByEndpoint[endpoint] ?? {},
  }))
}

function renderPage(Page) {
  render(<MemoryRouter><Page /></MemoryRouter>)
}

describe('business dashboards', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows the admin figures, the roster count and the stale-client queue', async () => {
    respond('ADMIN', {
      overview: { clients: ok(CLIENTS), accounts: ok(ACCOUNTS), transactions: ok(TRANSACTIONS), agents: ok({ total: 8, active: 7 }) },
      portfolio: { clients: ok(CLIENTS), accounts: ok(ACCOUNTS), accountsPerClient: ok({ ratio: 1.375 }) },
      transactions: { transactions: ok(TRANSACTIONS) },
      'action-items': { clients: ok(STALE), transactions: ok(TRANSACTIONS) },
      agents: { agents: ok({ rows: [{ agentId: 'sub-1', name: null, enabled: null, clientsOnboarded: 2, verified: 1, unverified: 1, verificationRate: null, accountsOpened: 0, bookedInitialDeposits: {} }], sortedBy: 'clientsOnboarded', currencies: [], sortCurrency: 'SGD' }) },
    })
    renderPage(AdminBusinessDashboard)

    expect(await screen.findByText('75.0%')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('8 on roster')).toBeInTheDocument()
    expect(screen.getByText('10.0%')).toBeInTheDocument()
    expect(screen.getByText('1.4')).toBeInTheDocument()
    expect(screen.getByText('Unknown agent')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'View all agents' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open client' })).toHaveAttribute('href', '/clients/client-9')
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.queryByText(/Ingestion freshness/)).not.toBeInTheDocument()
    expect(getDashboard).toHaveBeenCalledWith('action-items', expect.objectContaining({ staleDays: 7 }))
  })

  it('shows the top ten agents and links to all of them, keeping the window and sort', async () => {
    const row = (n) => ({ agentId: `sub-${n}`, name: `Agent Number${n}`, enabled: true, clientsOnboarded: n, verified: 0, unverified: 0, verificationRate: null, accountsOpened: 0, bookedInitialDeposits: {} })
    respond('ADMIN', { agents: { agents: ok({ rows: Array.from({ length: 11 }, (_, i) => row(i + 1)), sortedBy: 'clientsOnboarded' }) } })
    renderPage(AdminBusinessDashboard)

    expect(await screen.findByRole('link', { name: 'View all agents' })).toHaveAttribute('href', '/insights/admin/agents?days=30&sort=clientsOnboarded')
    expect(screen.getByText('Agent Number10')).toBeInTheDocument()
    expect(screen.queryByText('Agent Number11')).not.toBeInTheDocument()
  })

  it('shows amounts in their currencies and never adds them across currencies', async () => {
    const accounts = { ...ACCOUNTS, bookedInitialDeposits: { USD: '5000.00', SGD: '125000.00' }, currencies: ['SGD', 'USD'] }
    const transactions = { ...TRANSACTIONS, currencies: ['SGD', 'USD'], totalValue: undefined }
    respond('AGENT', {
      overview: { clients: ok(CLIENTS), accounts: ok(accounts), transactions: ok(transactions) },
      transactions: { transactions: ok(transactions) },
    })
    renderPage(AgentBusinessDashboard)

    const deposits = await screen.findByRole('list', { name: 'Booked initial deposits by currency' })
    expect([...deposits.querySelectorAll('li')].map((line) => line.textContent)).toEqual(['SGD 125.0K', 'USD 5,000'])
    expect(screen.getByText('In SGD, USD; not added')).toBeInTheDocument()
  })

  it('labels a transaction value with its one currency', async () => {
    respond('AGENT', { transactions: { transactions: ok(TRANSACTIONS) } })
    renderPage(AgentBusinessDashboard)

    expect(await screen.findByText('SGD 5,000')).toBeInTheDocument()
  })

  it('shows an unavailable panel without failing the page', async () => {
    respond('AGENT', {
      overview: { clients: ok(CLIENTS), accounts: ok(ACCOUNTS), transactions: ok(TRANSACTIONS), teamComparison: unavailable('unsupported_window') },
      portfolio: { clients: ok(CLIENTS), accounts: ok(ACCOUNTS), accountsPerClient: ok({ ratio: null }) },
      transactions: { transactions: unavailable('upstream_timeout') },
      'action-items': { clients: ok({ staleUnverified: [], staleUnverifiedTotal: 0 }) },
    })
    renderPage(AgentBusinessDashboard)

    expect(await screen.findByText('Only shown for the last 7, 30 or 90 days.')).toBeInTheDocument()
    expect(screen.getByText('The source service did not answer in time.')).toBeInTheDocument()
    expect(screen.getByText('40')).toBeInTheDocument()
    expect(screen.getByText(/no clients waiting on verification/)).toBeInTheDocument()
  })

  it('compares the agent with the team median only when it is sent', async () => {
    respond('AGENT', {
      overview: { clients: ok(CLIENTS), accounts: ok(ACCOUNTS), teamComparison: ok({ days: 30, clientsOnboarded: 6, teamMedian: null, cohortSize: 3 }) },
    })
    renderPage(AgentBusinessDashboard)

    expect(await screen.findByText(/team is too small/)).toBeInTheDocument()
  })

  it('says when the figures could not be loaded at all', async () => {
    getDashboard.mockRejectedValue(Object.assign(new Error('down'), { status: 503 }))
    renderPage(AgentBusinessDashboard)

    expect(await screen.findByText('Figures could not be loaded.')).toBeInTheDocument()
  })

  it('shows a spinner in every panel and tile while the figures load', async () => {
    getDashboard.mockReturnValue(new Promise(() => {}))
    renderPage(AdminBusinessDashboard)

    // The header and the five panels, and each of the six tiles.
    expect(screen.getAllByText('Loading figures…')).toHaveLength(6)
    expect(screen.getByText('Loading Total clients')).toBeInTheDocument()
    expect(screen.getAllByText(/^Loading /)).toHaveLength(12)
  })

  it('shows the spinners again, not the old figures, when the window changes', async () => {
    respond('AGENT', { overview: { clients: ok(CLIENTS) } })
    renderPage(AgentBusinessDashboard)
    expect(await screen.findByText('40')).toBeInTheDocument()

    getDashboard.mockReturnValue(new Promise(() => {}))
    fireEvent.click(screen.getByRole('button', { name: '7 days' }))

    expect(screen.queryByText('40')).not.toBeInTheDocument()
    expect(screen.getByText('Loading My clients')).toBeInTheDocument()
  })
})
