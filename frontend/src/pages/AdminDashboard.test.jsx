import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listClients } from '../api/clients.js'
import { listTransactions } from '../api/transactions.js'
import { createUser, disableUser, listUsers } from '../api/users.js'
import AdminDashboard from './AdminDashboard.jsx'

vi.mock('../api/clients.js', () => ({ listClients: vi.fn() }))
vi.mock('../api/transactions.js', () => ({ listTransactions: vi.fn() }))
vi.mock('../api/users.js', () => ({ createUser: vi.fn(), disableUser: vi.fn(), listUsers: vi.fn() }))
vi.mock('../api/activities.js', () => ({
  listActivities: vi.fn().mockResolvedValue({ content: [], nextCursor: null }),
}))

// Synthetic data only.
const AGENT = { id: 'user-2', firstName: 'Synthetic', lastName: 'Agent', email: 'agent@example.test', role: 'AGENT', enabled: true }
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

  it('lists every transaction and pages with the cursor', async () => {
    listTransactions
      .mockResolvedValueOnce({ content: [TRANSACTION], nextCursor: 'next-1' })
      .mockResolvedValueOnce({ content: [{ ...TRANSACTION, transaction_id: 'txn-2' }], nextCursor: null })
    renderDashboard()

    expect(await screen.findByText('txn-1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '00000000' })).toHaveAttribute('href', `/clients/${TRANSACTION.client_id}`)
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))

    expect(await screen.findByText('txn-2')).toBeInTheDocument()
    expect(listTransactions).toHaveBeenLastCalledWith({ cursor: 'next-1', size: 20 })
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
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
})
