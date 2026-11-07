import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { listAccountTransactions, listAccounts } from '../api/accounts.js'
import { deleteClient, getClient, listClients } from '../api/clients.js'
import { getDashboard } from '../api/dashboard.js'
import { listTransactions } from '../api/transactions.js'
import { listUsers } from '../api/users.js'
import ClientList from '../components/ClientList.jsx'
import TransactionsTable from '../components/TransactionsTable.jsx'
import UserList from '../components/UserList.jsx'
import AccountTransactions from './AccountTransactions.jsx'
import AgentPerformance from './AgentPerformance.jsx'
import ListPage from './ListPage.jsx'

vi.mock('../api/accounts.js', () => ({ listAccounts: vi.fn(), listAccountTransactions: vi.fn() }))
vi.mock('../api/clients.js', () => ({ deleteClient: vi.fn(), getClient: vi.fn(), listClients: vi.fn(), reassignClients: vi.fn() }))
vi.mock('../api/dashboard.js', async (importOriginal) => ({ ...(await importOriginal()), getDashboard: vi.fn() }))
vi.mock('../api/transactions.js', () => ({ listTransactions: vi.fn() }))
vi.mock('../api/users.js', () => ({ disableUser: vi.fn(), listUsers: vi.fn() }))
vi.mock('react-oidc-context', () => ({
  useAuth: () => ({
    isAuthenticated: true,
    isLoading: false,
    user: {
      profile: {
        sub: 'synthetic-admin-sub',
        'cognito:groups': ['ADMIN'],
      },
    },
  }),
}))

// Synthetic data only.
const agent = (n) => ({ id: `user-${n}`, firstName: 'Agent', lastName: `Number${n}`, email: `agent${n}@example.test`, enabled: true })
const client = (n) => ({ client_id: `client-${n}`, first_name: 'Client', last_name: `Number${n}` })
const transaction = (n) => ({
  transaction_id: `txn-${n}`, client_id: null, transaction_type: 'Deposit', amount: '10.00', currency: 'SGD',
  transaction_date: '2026-10-01T09:00:00Z', status: 'Completed',
})

// A user-service or client-service page of ten from a list of total rows.
function numberedPage(make, total) {
  return ({ page, size }) => {
    const first = page * size + 1
    const count = Math.max(0, Math.min(size, total - page * size))
    return Promise.resolve({
      content: Array.from({ length: count }, (_, i) => make(first + i)),
      page, size, totalElements: total, totalPages: Math.ceil(total / size),
    })
  }
}

function renderPage(list, title = 'All rows') {
  render(<MemoryRouter><ListPage title={title} subtitle="Synthetic" back="/admin">{list}</ListPage></MemoryRouter>)
}

afterEach(() => {
  vi.resetAllMocks()
  vi.restoreAllMocks()
})

describe('a whole list on its own page', () => {
  it('pages the agents ten at a time, alphabetically as user-service sends them, with first and last', async () => {
    listUsers.mockImplementation(numberedPage(agent, 23))
    renderPage(<UserList role="AGENT" title="Manage Agents" paged />, 'All agents')

    expect(screen.getByRole('heading', { name: 'All agents' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to Home' })).toHaveAttribute('href', '/admin')
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText(/Agent Number1 /)).toBeInTheDocument()
    expect(screen.getByText(/Agent Number10 /)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(await screen.findByText('Page 3 of 3')).toBeInTheDocument()
    expect(listUsers).toHaveBeenLastCalledWith({ role: 'AGENT', page: 2, size: 10 })
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Previous page' }))
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    expect(screen.getByText(/Agent Number11 /)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'First page' }))
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'View all agents' })).not.toBeInTheDocument()
  })

  it('stays on the page after deleting a client, and moves back when it empties', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    let total = 11
    listClients.mockImplementation((params) => numberedPage(client, total)(params))
    deleteClient.mockImplementation(() => { total -= 1; return Promise.resolve({}) })
    renderPage(<ClientList paged />)

    fireEvent.click(await screen.findByRole('button', { name: 'Last page' }))
    const row = (await screen.findByText('Client Number11')).closest('li')
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))

    expect(await screen.findByText('Page 1 of 1')).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(10)
    expect(deleteClient).toHaveBeenCalledWith('client-11')
  })

  it('pages every transaction, following the cursor a hundred at a time', async () => {
    listTransactions
      .mockResolvedValueOnce({ content: Array.from({ length: 100 }, (_, i) => transaction(i + 1)), nextCursor: 'next-1' })
      .mockResolvedValueOnce({ content: [transaction(101)], nextCursor: null })
    renderPage(<TransactionsTable paged />)

    expect(await screen.findByText('txn-1')).toBeInTheDocument()
    expect(screen.getByText('Page 1')).toBeInTheDocument()
    expect(listTransactions).toHaveBeenCalledWith({ cursor: null, size: 100 })

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(await screen.findByText('txn-11')).toBeInTheDocument()
    expect(screen.queryByText('txn-1')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(await screen.findByText('Page 11 of 11')).toBeInTheDocument()
    expect(screen.getByText('txn-101')).toBeInTheDocument()
    expect(listTransactions).toHaveBeenLastCalledWith({ cursor: 'next-1', size: 100 })
  })
})

describe("one account's transactions on their own page", () => {
  const ACCOUNT = { id: 'account-1234-5678', account_type: 'savings', status: 'ACTIVE' }

  function renderAccount(path) {
    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/agent/client/:clientId/accounts/:accountId/transactions" element={<AccountTransactions />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('pages them ten at a time, and links back to the accounts', async () => {
    getClient.mockResolvedValue({ client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' })
    listAccounts.mockResolvedValue([ACCOUNT])
    listAccountTransactions.mockResolvedValue(Array.from({ length: 23 }, (_, i) => transaction(i + 1)))
    renderAccount('/agent/client/client-1/accounts/account-1234-5678/transactions')

    expect(await screen.findByRole('heading', { name: 'savings account account-' })).toBeInTheDocument()
    expect(screen.getByText("Synthetic Client's transactions, newest first")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to accounts' })).toHaveAttribute('href', '/agent/client/client-1/transactions')
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('txn-10')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(await screen.findByText('Page 3 of 3')).toBeInTheDocument()
    expect(screen.getByText('txn-23')).toBeInTheDocument()
    expect(screen.queryByText('txn-10')).not.toBeInTheDocument()
  })

  it("says not found for an account that is not the client's", async () => {
    getClient.mockResolvedValue({ client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' })
    listAccounts.mockResolvedValue([ACCOUNT])
    listAccountTransactions.mockResolvedValue([transaction(1)])
    renderAccount('/agent/client/client-1/accounts/someone-elses/transactions')

    expect(await screen.findByRole('alert')).toHaveTextContent('Account not found.')
    expect(screen.queryByText('txn-1')).not.toBeInTheDocument()
  })
})

describe('every agent on its own page', () => {
  const row = (n) => ({
    agentId: `sub-${n}`, name: `Agent Number${n}`, enabled: true, clientsOnboarded: 30 - n, verified: 1, unverified: 0,
    verificationRate: 1, accountsOpened: 0, bookedInitialDeposits: {},
  })

  it('continues the ranking from the link, ten a page', async () => {
    getDashboard.mockImplementation((endpoint, params) => Promise.resolve({
      generatedAt: '2026-10-16T03:00:00Z', window: { from: params.from, to: params.to }, scope: 'ADMIN',
      panels: { agents: { status: 'OK', reason: null, data: { rows: Array.from({ length: 12 }, (_, i) => row(i + 1)), sortedBy: params.sort } } },
    }))
    render(
      <MemoryRouter initialEntries={['/insights/admin/agents?days=90&sort=accountsOpened']}>
        <Routes><Route path="/insights/admin/agents" element={<AgentPerformance />} /></Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(getDashboard).toHaveBeenCalledWith('agents', expect.objectContaining({ sort: 'accountsOpened' }))
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Agent Number10')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(await screen.findByText('Agent Number12')).toBeInTheDocument()
    expect(screen.queryByText('Agent Number1')).not.toBeInTheDocument()

    // A new window starts the ranking again from the top.
    fireEvent.click(screen.getByRole('button', { name: '7 days' }))
    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
  })

  it('ranks deposits in a chosen currency when the window has more than one', async () => {
    getDashboard.mockImplementation((endpoint, params) => Promise.resolve({
      generatedAt: '2026-10-16T03:00:00Z', window: { from: params.from, to: params.to }, scope: 'ADMIN',
      panels: { agents: { status: 'OK', reason: null, data: {
        rows: [{ ...row(1), bookedInitialDeposits: { SGD: '700.00', USD: '50.00' } }],
        sortedBy: params.sort, currencies: ['SGD', 'USD'], sortCurrency: params.currency ?? 'SGD',
      } } },
    }))
    render(
      <MemoryRouter initialEntries={['/insights/admin/agents?days=30&sort=accountsOpened']}>
        <Routes><Route path="/insights/admin/agents" element={<AgentPerformance />} /></Routes>
      </MemoryRouter>,
    )

    // Another metric takes no currency, so none is offered or sent.
    expect(await screen.findByTitle('SGD 700 · USD 50')).toHaveTextContent('SGD 700USD 50')
    expect(screen.queryByLabelText('In')).not.toBeInTheDocument()
    expect(getDashboard).toHaveBeenLastCalledWith('agents', expect.not.objectContaining({ currency: expect.anything() }))

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'bookedInitialDeposits' } })
    fireEvent.change(await screen.findByLabelText('In'), { target: { value: 'USD' } })

    expect(await screen.findByText('Booked deposits (USD)')).toBeInTheDocument()
    expect(getDashboard).toHaveBeenLastCalledWith('agents', expect.objectContaining({ sort: 'bookedInitialDeposits', currency: 'USD' }))
  })
})
