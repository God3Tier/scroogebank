import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { getPortfolio, replacePortfolio } from '../api/alertPortfolios.js'
import HoldingsEditor from './HoldingsEditor.jsx'

vi.mock('../api/alertPortfolios.js', () => ({ getPortfolio: vi.fn(), replacePortfolio: vi.fn() }))

// Synthetic data only.
const SAVED = {
  accountId: 'account-1', revision: 1, totalSgd: '10.0000',
  positions: [{ instrumentId: 'SYN-SGX-001', quantity: 10, unitPrice: 1, valuationCurrency: 'SGD', fxToSgd: 1 }],
}

describe('holdings editor', () => {
  afterEach(() => vi.clearAllMocks())

  it('starts empty when nothing has been recorded and saves with revision 0', async () => {
    getPortfolio.mockRejectedValue(Object.assign(new Error('not found'), { status: 404 }))
    replacePortfolio.mockResolvedValue(SAVED)
    render(<HoldingsEditor accountId="account-1" />)

    expect(await screen.findByText('No holdings recorded yet.')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Add position'))
    fireEvent.change(screen.getByLabelText('Instrument 1'), { target: { value: 'SYN-SGX-001' } })
    fireEvent.change(screen.getByLabelText('Quantity 1'), { target: { value: '10' } })
    fireEvent.change(screen.getByLabelText('Unit price 1'), { target: { value: '1' } })
    fireEvent.click(screen.getByText('Save holdings'))

    expect(await screen.findByText('Holdings saved (revision 1).')).toBeInTheDocument()
    const [accountId, body] = replacePortfolio.mock.calls[0]
    expect(accountId).toBe('account-1')
    expect(body).toMatchObject({
      expectedRevision: 0,
      positions: [{ instrumentId: 'SYN-SGX-001', quantity: '10', unitPrice: '1', valuationCurrency: 'SGD' }],
    })
  })

  it('sends the loaded revision and explains a conflict', async () => {
    getPortfolio.mockResolvedValue(SAVED)
    replacePortfolio.mockRejectedValue(Object.assign(new Error('stale'), { status: 409 }))
    render(<HoldingsEditor accountId="account-1" />)

    expect(await screen.findByDisplayValue('SYN-SGX-001')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Save holdings'))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Reload and try again'))
    expect(replacePortfolio.mock.calls[0][1].expectedRevision).toBe(1)
  })

  it('says it never trades', async () => {
    getPortfolio.mockResolvedValue(SAVED)
    render(<HoldingsEditor accountId="account-1" />)
    expect(await screen.findByText(/does not buy or sell anything/)).toBeInTheDocument()
  })
})
