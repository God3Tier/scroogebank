import { fireEvent, render, screen } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listAccountTransactions, listAccounts, updateAccountStatus } from '../api/accounts.js'
import { getClient } from '../api/clients.js'
import ClientTransactions from './ClientTransactions.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/accounts.js', () => ({ listAccounts: vi.fn(), listAccountTransactions: vi.fn(), updateAccountStatus: vi.fn() }))

// Synthetic data only.
const CLIENT = { client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client', email: 'client@example.test' }
const VERIFIED_CLIENT = { ...CLIENT, verification_status: 'VERIFIED' }
const ACCOUNT = { id: 'account-1234-5678', account_type: 'savings', status: 'pending' }

function signedInAs(group) {
  useAuth.mockReturnValue({ isAuthenticated: true, user: { profile: { sub: `${group}-sub`, 'cognito:groups': [group] } } })
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/agent/client/client-1/transactions']}>
      <Routes>
        <Route path="/agent/client/:clientId/transactions" element={<ClientTransactions />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('client transactions', () => {
  beforeEach(() => {
    signedInAs('Agent')
    listAccountTransactions.mockResolvedValue([])
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it("shows each account's transactions", async () => {
    getClient.mockResolvedValue(CLIENT)
    listAccounts.mockResolvedValue([ACCOUNT])
    listAccountTransactions.mockResolvedValue([{
      transaction_id: 'txn-1', transaction_type: 'Deposit', amount: '10.00', currency: 'SGD',
      transaction_date: '2026-10-01T09:00:00Z', status: 'Completed',
    }])
    renderPage()

    expect(await screen.findByText('txn-1')).toBeInTheDocument()
    expect(screen.getByText('Synthetic Client')).toBeInTheDocument()
    expect(screen.getByText('SGD 10.00')).toBeInTheDocument()
    expect(listAccountTransactions).toHaveBeenCalledWith('account-1234-5678')
  })

  it('says when the client has no accounts', async () => {
    getClient.mockResolvedValue(CLIENT)
    listAccounts.mockResolvedValue([])
    renderPage()

    expect(await screen.findByText(/no accounts/)).toBeInTheDocument()
    expect(listAccountTransactions).not.toHaveBeenCalled()
  })

  it("treats another agent's client as not found", async () => {
    getClient.mockRejectedValue(Object.assign(new Error('client not found'), { status: 404 }))
    listAccounts.mockResolvedValue([])
    renderPage()

    expect(await screen.findByText('Client not found.')).toBeInTheDocument()
  })

  it("lets an agent change a verified client's account to any status", async () => {
    getClient.mockResolvedValue(VERIFIED_CLIENT)
    listAccounts.mockResolvedValue([ACCOUNT])
    updateAccountStatus.mockResolvedValue({ account_id: ACCOUNT.id, status: 'CLOSED' })
    renderPage()

    const select = await screen.findByRole('combobox', { name: 'New status for account account-' })
    expect(screen.getByText('Pending', { selector: '.badge' })).toBeInTheDocument()
    expect([...select.options].map((option) => option.value)).toEqual(['ACTIVE', 'INACTIVE', 'PENDING', 'CLOSED'])
    expect(screen.getByRole('button', { name: 'Save status' })).toBeDisabled()

    fireEvent.change(select, { target: { value: 'CLOSED' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save status' }))

    expect(await screen.findByText('Closed', { selector: '.badge' })).toBeInTheDocument()
    expect(updateAccountStatus).toHaveBeenCalledWith(ACCOUNT.id, 'CLOSED')
  })

  it('explains a refused change', async () => {
    getClient.mockResolvedValue(VERIFIED_CLIENT)
    listAccounts.mockResolvedValue([ACCOUNT])
    updateAccountStatus.mockRejectedValue(Object.assign(new Error('Request failed (403)'), { status: 403 }))
    renderPage()

    fireEvent.change(await screen.findByRole('combobox'), { target: { value: 'ACTIVE' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save status' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("This client isn't verified")
    expect(screen.getByText('Pending', { selector: '.badge' })).toBeInTheDocument()
  })

  it("keeps an unverified client's account statuses as they are", async () => {
    getClient.mockResolvedValue(CLIENT)
    listAccounts.mockResolvedValue([ACCOUNT])
    renderPage()

    expect(await screen.findByText('Verify this client to change account statuses.')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('shows an admin the status without a control', async () => {
    signedInAs('Admin')
    getClient.mockResolvedValue(VERIFIED_CLIENT)
    listAccounts.mockResolvedValue([ACCOUNT])
    renderPage()

    expect(await screen.findByText('Pending', { selector: '.badge' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    expect(screen.queryByText('Verify this client to change account statuses.')).not.toBeInTheDocument()
  })
})
