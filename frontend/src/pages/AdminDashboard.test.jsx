import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listClients, reassignClients } from '../api/clients.js'
import { listTransactions } from '../api/transactions.js'
import { createUser, disableUser, listUsers, resetPassword } from '../api/users.js'
import AdminDashboard from './AdminDashboard.jsx'

vi.mock('../api/clients.js', () => ({ listClients: vi.fn(), reassignClients: vi.fn() }))
vi.mock('../api/transactions.js', () => ({ listTransactions: vi.fn() }))
vi.mock('../api/users.js', () => ({ createUser: vi.fn(), disableUser: vi.fn(), listUsers: vi.fn(), resetPassword: vi.fn() }))
vi.mock('../api/activities.js', () => ({
  listActivities: vi.fn().mockResolvedValue({ content: [], nextCursor: null }),
}))
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
const AGENT = { id: 'user-2', firstName: 'Synthetic', lastName: 'Agent', email: 'agent@example.test', role: 'AGENT', enabled: true }
const LEAVER = { id: 'user-3', firstName: 'Leaving', lastName: 'Agent', email: 'leaver@example.test', role: 'AGENT', enabled: false }
const OTHER = { id: 'user-4', firstName: 'Other', lastName: 'Agent', email: 'other@example.test', role: 'AGENT', enabled: true }
const TRANSACTION = {
  transaction_id: 'txn-1', client_id: '00000000-0000-0000-0000-000000000101', account_id: 'account-1',
  transaction_type: 'Deposit', amount: '125.75', currency: 'SGD', transaction_date: '2026-10-01T09:00:00Z', status: 'Pending',
}

function page(content, totalElements = content.length) {
  return { content, page: 0, size: 20, totalElements, totalPages: 1 }
}

function renderDashboard() {
  render(<MemoryRouter><AdminDashboard /></MemoryRouter>)
}

describe('admin dashboard', () => {
  beforeEach(() => {
    listUsers.mockImplementation(({ role }) => Promise.resolve(role === 'ADMIN' ? page([], 2) : page([AGENT], 5)))
    listClients.mockResolvedValue(page([], 40))
    listTransactions.mockResolvedValue({ content: [TRANSACTION], nextCursor: null })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows totals from user-service and client-service', async () => {
    renderDashboard()

    expect(await screen.findByText('40')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()
  })

  it('shows the newest ten transactions and links to all of them', async () => {
    listTransactions.mockResolvedValue({ content: [TRANSACTION], nextCursor: 'next-1' })
    renderDashboard()

    expect(await screen.findByText('txn-1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '00000000' })).toHaveAttribute('href', `/clients/${TRANSACTION.client_id}`)
    expect(listTransactions).toHaveBeenCalledTimes(1)
    expect(listTransactions).toHaveBeenCalledWith({ cursor: null, size: 10 })
    expect(screen.getByRole('link', { name: 'View all transactions' })).toHaveAttribute('href', '/admin/transactions')
  })

  it('shows the first ten admins and agents, and links to all of them only when there are more', async () => {
    listUsers.mockImplementation(({ role }) => Promise.resolve(role === 'ADMIN' ? page([]) : { ...page([AGENT], 11), totalPages: 2 }))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    expect(await screen.findByRole('link', { name: 'View all agents' })).toHaveAttribute('href', '/admin/agents')
    expect(listUsers).toHaveBeenCalledWith({ role: 'AGENT', page: 0, size: 10 })

    fireEvent.click(screen.getByRole('button', { name: 'Manage Admins' }))
    expect(await screen.findByText('None yet.')).toBeInTheDocument()
    expect(listUsers).toHaveBeenCalledWith({ role: 'ADMIN', page: 0, size: 10 })
    expect(screen.queryByRole('link', { name: 'View all admins' })).not.toBeInTheDocument()
  })

  it('creates an agent and says Cognito e-mails the password', async () => {
    createUser.mockResolvedValue({ id: 'user-3' })
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Create Admin / Agent' }))

    fireEvent.change(screen.getByLabelText('First Name:'), { target: { value: 'New' } })
    fireEvent.change(screen.getByLabelText('Last Name:'), { target: { value: 'Agent' } })
    fireEvent.change(screen.getByLabelText('Email:'), { target: { value: 'new.agent@example.test' } })
    fireEvent.change(screen.getByLabelText('Role:'), { target: { value: 'AGENT' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create Account' }))

    expect(await screen.findByText(/temporary password/)).toBeInTheDocument()
    expect(createUser).toHaveBeenCalledWith({ firstName: 'New', lastName: 'Agent', email: 'new.agent@example.test', role: 'AGENT' })
  })

  it('disables an agent, and explains when user-service refuses', async () => {
    disableUser.mockRejectedValue(Object.assign(new Error('forbidden'), { status: 403 }))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    const row = (await screen.findByText(/Synthetic Agent/)).closest('li')
    fireEvent.click(within(row).getByRole('button', { name: 'Disable' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be disabled')
    expect(disableUser).toHaveBeenCalledWith('user-2')
  })

  it('requests an agent password reset once while pending, then explains the next step', async () => {
    let finishReset
    resetPassword.mockReturnValue(new Promise((resolve) => { finishReset = resolve }))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    const row = (await screen.findByText(/Synthetic Agent/)).closest('li')
    const button = within(row).getByRole('button', { name: 'Reset password' })
    fireEvent.click(button)
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('Requesting reset')
    fireEvent.click(button)
    expect(resetPassword).toHaveBeenCalledExactlyOnceWith('user-2')

    await act(async () => { finishReset(null) })
    expect(await screen.findByRole('status')).toHaveTextContent('Synthetic Agent: password reset requested. Cognito will email a reset code.')
    expect(button).toBeEnabled()
  })

  it('explains a refused password reset and allows retrying', async () => {
    resetPassword.mockRejectedValueOnce(Object.assign(new Error('forbidden'), { status: 403 }))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    const row = (await screen.findByText(/Synthetic Agent/)).closest('li')
    const button = within(row).getByRole('button', { name: 'Reset password' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission to reset this password.')
    expect(button).toBeEnabled()

    resetPassword.mockResolvedValueOnce(null)
    fireEvent.click(button)
    expect(await screen.findByRole('status')).toHaveTextContent('password reset requested')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // Requirements Q&A UM17, ADR-0060.
  it("moves all of a disabled agent's clients to an enabled agent", async () => {
    listUsers.mockImplementation(({ role }) => Promise.resolve(role === 'ADMIN' ? page([], 2) : page([AGENT, LEAVER, OTHER])))
    reassignClients.mockResolvedValue({ reassigned: 3, client_ids: ['c-1', 'c-2', 'c-3'] })
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    const row = (await screen.findByText(/Leaving Agent/)).closest('li')
    fireEvent.click(within(row).getByRole('button', { name: 'Reassign clients' }))
    const select = await within(row).findByLabelText('New agent')
    // Every enabled agent is asked for, not only the ones on this page.
    expect(listUsers).toHaveBeenLastCalledWith({ role: 'AGENT', enabled: true, size: 100 })
    // Only other enabled agents can receive them.
    expect([...select.options].map((option) => option.value)).toEqual(['', 'user-2', 'user-4'])
    fireEvent.change(select, { target: { value: 'user-4' } })
    fireEvent.click(within(row).getByRole('button', { name: "Move all of Leaving Agent's clients" }))

    expect(await screen.findByRole('status')).toHaveTextContent('Leaving Agent: 3 clients moved to Other Agent, with their accounts.')
    expect(reassignClients).toHaveBeenCalledWith({ toUserId: 'user-4', fromUserId: 'user-3' })
  })

  it('says nothing moved when a service is down', async () => {
    listUsers.mockImplementation(({ role }) => Promise.resolve(role === 'ADMIN' ? page([], 2) : page([AGENT, OTHER])))
    reassignClients.mockRejectedValue(Object.assign(new Error('account service unavailable'), { status: 503 }))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Agents' }))

    const row = (await screen.findByText(/Synthetic Agent/)).closest('li')
    fireEvent.click(within(row).getByRole('button', { name: 'Reassign clients' }))
    fireEvent.change(await within(row).findByLabelText('New agent'), { target: { value: 'user-4' } })
    fireEvent.click(within(row).getByRole('button', { name: "Move all of Synthetic Agent's clients" }))

    expect(await within(row).findByRole('alert')).toHaveTextContent('nothing was moved')
  })

  it('offers no reassignment or password reset for admins', async () => {
    listUsers.mockImplementation(({ role }) => Promise.resolve(role === 'ADMIN' ? page([{ ...OTHER, id: 'admin-1', role: 'ADMIN' }]) : page([AGENT])))
    renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Admins' }))

    const row = (await screen.findByText(/Other Agent/)).closest('li')
    expect(within(row).queryByRole('button', { name: 'Reassign clients' })).not.toBeInTheDocument()
    expect(within(row).queryByRole('button', { name: 'Reset password' })).not.toBeInTheDocument()
  })
})
