import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getClient } from '../api/clients.js'
import { listUsers } from '../api/users.js'
import HeldCompanies from './HeldCompanies.jsx'

vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/searchSources.js', () => ({ searchNow: vi.fn() }))
vi.mock('../api/users.js', () => ({ listUsers: vi.fn() }))

const NAMES = ['Aaron Teo', 'Bella Ng', 'Caleb Lee', 'Diana Koh', 'Ethan Chua', 'Fiona Tay', 'Gareth Ong', 'Hannah Sim', 'Ivan Goh', 'Jasmine Yeo', 'Kelvin Ho', 'Lydia Chan']
const holder = (i) => ({ clientId: `c${i}`, accountId: `a${i}`, sharePercent: 40, quantity: 10 + i, valueSgd: 1000 + i, ownerAgentSubject: 'agent' })
const company = (count, id = 'NASDAQ-AAPL') => ({
  instrumentId: id, ticker: id === 'NASDAQ-AAPL' ? 'AAPL' : 'SHEL', issuerName: id === 'NASDAQ-AAPL' ? 'Apple Inc' : 'Shell plc',
  officialDomains: [], holders: Array.from({ length: count }, (_, i) => holder(i)),
})

beforeEach(() => {
  getClient.mockImplementation(async (id) => {
    const [first, ...rest] = NAMES[Number(id.slice(1))].split(' ')
    return { first_name: first, last_name: rest.join(' ') }
  })
})
afterEach(() => vi.clearAllMocks())

describe('HeldCompanies', () => {
  it('shows every holder with no search or pages when a company has five or fewer', async () => {
    render(<HeldCompanies holdings={[company(5)]} admin={false} tickers={{}} />)
    expect(await screen.findByText('Aaron Teo')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Search the clients holding/)).toBeNull()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.getAllByRole('row').slice(1)).toHaveLength(5)
  })

  it('shows five at a time with pages, for each company on its own', async () => {
    render(<HeldCompanies holdings={[company(12), company(7, 'LSE-SHEL')]} admin={false} tickers={{}} />)
    const apple = within(await screen.findByRole('article', { name: 'Apple Inc holdings' }))
    const shell = within(screen.getByRole('article', { name: 'Shell plc holdings' }))
    await waitFor(() => expect(apple.getByText('Aaron Teo')).toBeInTheDocument())
    expect(apple.getAllByRole('row').slice(1)).toHaveLength(5)
    expect(apple.getByText('Page 1 of 3')).toBeInTheDocument()
    expect(shell.getByText('Page 1 of 2')).toBeInTheDocument()

    fireEvent.click(apple.getByRole('button', { name: 'Next' }))
    expect(apple.getByText('Page 2 of 3')).toBeInTheDocument()
    expect(apple.queryByText('Aaron Teo')).toBeNull()
    expect(apple.getByText('Fiona Tay')).toBeInTheDocument()
    expect(shell.getByText('Page 1 of 2')).toBeInTheDocument() // the other company did not move
  })

  it('pages and searches the same way for an administrator, with the Agent column', async () => {
    listUsers.mockResolvedValue({ content: [{ idpSub: 'agent', firstName: 'Agent', lastName: 'One' }] })
    render(<HeldCompanies holdings={[company(12)]} admin tickers={{}} />)
    await screen.findByText('Aaron Teo')
    expect(screen.getByRole('columnheader', { name: 'Agent' })).toBeInTheDocument()
    expect(screen.getAllByRole('row').slice(1)).toHaveLength(5)
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search the clients holding Apple Inc'), { target: { value: 'zzz' } })
    expect(screen.getByText('No client matches "zzz".').closest('td')).toHaveAttribute('colspan', '5')
  })

  it('searches the holders of one company by name and says when nothing matches', async () => {
    render(<HeldCompanies holdings={[company(12)]} admin={false} tickers={{}} />)
    await screen.findByText('Aaron Teo')
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.change(screen.getByLabelText('Search the clients holding Apple Inc'), { target: { value: 'kelvin' } })
    expect(screen.getByText('Page 1 of 1')).toBeInTheDocument()
    expect(screen.getAllByRole('row').slice(1)).toHaveLength(1)
    expect(screen.getByText('Kelvin Ho')).toBeInTheDocument()
    expect(screen.getByText('1 of 12 clients')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search the clients holding Apple Inc'), { target: { value: 'zzz' } })
    expect(screen.getByText('No client matches "zzz".')).toBeInTheDocument()
  })
})
