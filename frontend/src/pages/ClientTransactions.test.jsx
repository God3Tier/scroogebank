import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { listAccountTransactions, listAccounts } from '../api/accounts.js'
import { getClient } from '../api/clients.js'
import ClientTransactions from './ClientTransactions.jsx'

vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/accounts.js', () => ({ listAccounts: vi.fn(), listAccountTransactions: vi.fn() }))

// Synthetic data only.
const CLIENT = { client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client', email: 'client@example.test' }

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
  afterEach(() => {
    vi.clearAllMocks()
  })

  it("shows each account's transactions", async () => {
    getClient.mockResolvedValue(CLIENT)
    listAccounts.mockResolvedValue([{ id: 'account-1234-5678', account_type: 'savings' }])
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
})
