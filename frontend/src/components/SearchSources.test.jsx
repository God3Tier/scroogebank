import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getClient } from '../api/clients.js'
import {
  addSearchWebsite, getSearchSchedule, listSearchHoldings, listSearchWebsites, removeSearchWebsite, searchNow,
} from '../api/searchSources.js'
import { listUsers } from '../api/users.js'
import EventAlerts from '../pages/EventAlerts.jsx'
import SearchSources from './SearchSources.jsx'

vi.mock('../api/searchSources.js', () => ({
  listSearchWebsites: vi.fn(), addSearchWebsite: vi.fn(), removeSearchWebsite: vi.fn(),
  listSearchHoldings: vi.fn(), searchNow: vi.fn(), getSearchSchedule: vi.fn(),
}))
vi.mock('../api/clients.js', () => ({ getClient: vi.fn() }))
vi.mock('../api/users.js', () => ({ listUsers: vi.fn() }))
vi.mock('../components/AlertWorkspace.jsx', () => ({ default: ({ readOnly }) => <section>Alert workspace {readOnly ? 'read only' : 'editable'}</section> }))
vi.mock('../components/TickerReports.jsx', () => ({ default: () => <section>Ticker reports</section> }))

// Synthetic data only.
const MINE = { id: 'w1', domain: 'businesstimes.com.sg', addedAt: '2026-11-04T04:00:00Z', addedByYou: true }
const THEIRS = { id: 'w2', domain: 'example.com', addedAt: '2026-11-03T04:00:00Z', addedByYou: false }

function websites(overrides = {}) {
  return { shared: ['reuters.com', 'cnbc.com', 'ft.com'], added: [], canAdd: true, max: 20, ...overrides }
}

const DBS = {
  instrumentId: 'SGX-D05', ticker: 'D05', issuerName: 'DBS Group Holdings Ltd', officialDomains: ['sgx.com', 'dbs.com'],
  holders: [{ clientId: 'c1111111-0000-4000-8000-000000000001', accountId: 'a1', quantity: 1000, valueSgd: 36000, sharePercent: 76.3, ownerAgentSubject: null }],
}
const APPLE = {
  instrumentId: 'NASDAQ-AAPL', ticker: 'AAPL', issuerName: 'Apple Inc', officialDomains: ['apple.com'],
  holders: [{ clientId: 'c2222222-0000-4000-8000-000000000002', accountId: 'a2', quantity: 60, valueSgd: 18500, sharePercent: 100, ownerAgentSubject: null }],
}
const SCHEDULE = {
  enabled: true, intervalMinutes: 240, zone: 'Asia/Singapore',
  previousRunAt: '2026-11-04T04:00:00Z', nextRunAt: '2026-11-04T08:00:00Z',
}

describe('search sources', () => {
  beforeEach(() => {
    getSearchSchedule.mockResolvedValue(SCHEDULE)
    listSearchHoldings.mockResolvedValue([DBS, APPLE])
    listSearchWebsites.mockResolvedValue(websites())
    getClient.mockImplementation((id) => Promise.resolve(
      id.startsWith('c1') ? { first_name: 'Chloe', last_name: 'Tan' } : { first_name: 'Daniel', last_name: 'Lim' }))
    listUsers.mockResolvedValue({ content: [] })
  })
  afterEach(() => vi.clearAllMocks())

  it('shows when the next automatic search is and that searching by hand does not change it', async () => {
    render(<SearchSources />)

    expect(await screen.findByText(/Next automatic search:/)).toBeInTheDocument()
    expect(screen.getByText(/every 4 hours/)).toBeInTheDocument()
    expect(screen.getByText(/never changes this/)).toBeInTheDocument()
  })

  it('says so when automatic searches are switched off', async () => {
    getSearchSchedule.mockResolvedValue({ ...SCHEDULE, enabled: false })
    render(<SearchSources />)

    expect(await screen.findByText('Automatic searches are switched off.')).toBeInTheDocument()
  })

  it('shows the shared websites and who added each added one', async () => {
    listSearchWebsites.mockResolvedValue(websites({ added: [MINE, THEIRS] }))
    render(<SearchSources />)

    const card = await screen.findByRole('article', { name: 'Websites searched for every company' })
    expect(within(card).getByText('reuters.com')).toBeInTheDocument()
    expect(within(card).getByText('businesstimes.com.sg')).toBeInTheDocument()
    expect(card).toHaveTextContent('by you')
    expect(card).toHaveTextContent('by another agent')
  })

  it('shows each company with the clients that hold it, their share, quantity and value', async () => {
    render(<SearchSources />)

    const dbs = await screen.findByRole('article', { name: 'DBS Group Holdings Ltd holdings' })
    expect(within(dbs).getByText('sgx.com')).toBeInTheDocument()
    expect(await within(dbs).findByText('Chloe Tan')).toBeInTheDocument()
    expect(within(dbs).getByText('76.3%')).toBeInTheDocument()
    expect(within(dbs).getByText('1,000')).toBeInTheDocument()
    expect(within(dbs).getByText('36,000')).toBeInTheDocument()
    const apple = screen.getByRole('article', { name: 'Apple Inc holdings' })
    expect(await within(apple).findByText('Daniel Lim')).toBeInTheDocument()
  })

  it('shows a short id when a client cannot be read', async () => {
    getClient.mockRejectedValue(Object.assign(new Error('x'), { status: 404 }))
    render(<SearchSources />)

    expect(await screen.findAllByText(/Client c1111111/)).not.toHaveLength(0)
  })

  it('adds a website, tells the agent what is being searched and reloads the list', async () => {
    listSearchWebsites.mockResolvedValueOnce(websites()).mockResolvedValueOnce(websites({ added: [MINE] }))
    addSearchWebsite.mockResolvedValue({
      ...MINE, search: { outcome: 'STARTED', started: ['SGX-D05', 'NASDAQ-AAPL'], cooldown: [], lookbackDays: 14 },
    })
    render(<SearchSources />)

    const input = await screen.findByLabelText('Add a website')
    expect(screen.getByRole('button', { name: 'Add website' })).toBeDisabled()
    fireEvent.change(input, { target: { value: 'www.BusinessTimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add website' }))

    expect(await screen.findByText(/Added businesstimes.com.sg. Searching 2 companies on businesstimes.com.sg over the last 14 days/)).toBeInTheDocument()
    expect(addSearchWebsite).toHaveBeenCalledWith('www.BusinessTimes.com.sg')
    expect(screen.getByLabelText('Add a website')).toHaveValue('')
    expect(await screen.findByLabelText('Remove businesstimes.com.sg')).toBeInTheDocument()
  })

  it('says which companies were skipped because they were searched a moment ago', async () => {
    addSearchWebsite.mockResolvedValue({
      ...MINE, search: { outcome: 'STARTED', started: ['NASDAQ-AAPL'],
        cooldown: [{ instrumentId: 'SGX-D05', availableAt: '2026-11-04T04:15:00Z' }], lookbackDays: 14 },
    })
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a website'), { target: { value: 'businesstimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add website' }))

    expect(await screen.findByText(/skipped: D05 \(again at 12:15\)/)).toBeInTheDocument()
  })

  it('keeps the website added when the search could not start', async () => {
    addSearchWebsite.mockResolvedValue({ ...MINE, search: { outcome: 'FAILED', started: [], cooldown: [], lookbackDays: 14 } })
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a website'), { target: { value: 'businesstimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add website' }))

    expect(await screen.findByText(/Added businesstimes.com.sg. The search could not start/)).toBeInTheDocument()
  })

  it('shows the refusal next to the form and keeps what was typed', async () => {
    addSearchWebsite.mockRejectedValue(Object.assign(
      new Error('social, forum and blog platforms cannot be used as news sources'), { status: 400 }))
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a website'), { target: { value: 'reddit.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add website' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('social, forum and blog platforms')
    expect(screen.getByLabelText('Add a website')).toHaveValue('reddit.com')
  })

  it('asks before removing, and removes only after confirming', async () => {
    listSearchWebsites.mockResolvedValueOnce(websites({ added: [MINE] })).mockResolvedValueOnce(websites())
    removeSearchWebsite.mockResolvedValue(null)
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Remove businesstimes.com.sg' }))
    expect(removeSearchWebsite).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(removeSearchWebsite).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Remove businesstimes.com.sg' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }))

    await waitFor(() => expect(removeSearchWebsite).toHaveBeenCalledWith('w1'))
    expect(await screen.findByText('none yet')).toBeInTheDocument()
  })

  it('stops offering to add when the list is full', async () => {
    const many = Array.from({ length: 20 }, (_, index) => ({ ...THEIRS, id: `w${index}`, domain: `site${index}.example.com` }))
    listSearchWebsites.mockResolvedValue(websites({ added: many }))
    render(<SearchSources />)

    expect(await screen.findByText(/20 websites have been added/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Add a website')).not.toBeInTheDocument()
  })

  it('searches every company at once and says what started', async () => {
    searchNow.mockResolvedValue({ outcome: 'STARTED', started: ['SGX-D05', 'NASDAQ-AAPL'], cooldown: [], lookbackDays: 14 })
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Search now for all my companies' }))

    expect(await screen.findByText(/Searching 2 companies on every website over the last 14 days/)).toBeInTheDocument()
    expect(searchNow).toHaveBeenCalledWith()
  })

  it('searches one company from its own card', async () => {
    searchNow.mockResolvedValue({ outcome: 'STARTED', started: ['SGX-D05'], cooldown: [], lookbackDays: 14 })
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Search now for D05' }))

    expect(await screen.findByText(/Searching 1 company on every website/)).toBeInTheDocument()
    expect(searchNow).toHaveBeenCalledWith('SGX-D05')
  })

  it('says when a company can be searched again', async () => {
    searchNow.mockResolvedValue({ outcome: 'COOLDOWN', started: [],
      cooldown: [{ instrumentId: 'SGX-D05', availableAt: '2026-11-04T04:15:00Z' }], lookbackDays: 14 })
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Search now for D05' }))

    expect(await screen.findByText(/Available again: D05 \(again at 12:15\)/)).toBeInTheDocument()
  })

  it('shows why a search did not start', async () => {
    searchNow.mockRejectedValue(Object.assign(new Error('you can only change the sources of a company one of your clients holds'), { status: 403 }))
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Search now for D05' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('one of your clients holds')
  })

  it('lets an admin look, search and remove but not add, and shows the owning agent', async () => {
    listSearchWebsites.mockResolvedValue(websites({ added: [THEIRS], canAdd: false }))
    listSearchHoldings.mockResolvedValue([{ ...DBS, holders: [{ ...DBS.holders[0], ownerAgentSubject: 'syn-agent-001' }] }])
    listUsers.mockResolvedValue({ content: [{ idpSub: 'syn-agent-001', firstName: 'Alice', lastName: 'Agent' }] })
    render(<SearchSources readOnly />)

    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(screen.queryByLabelText('Add a website')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove example.com' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Search now for all companies' })).toBeInTheDocument()
    expect(await screen.findByText('Alice Agent')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Agent' })).toBeInTheDocument()
  })

  it('does not show an agent column to an agent', async () => {
    render(<SearchSources />)

    await screen.findByRole('article', { name: 'DBS Group Holdings Ltd holdings' })
    expect(screen.queryByRole('columnheader', { name: 'Agent' })).not.toBeInTheDocument()
  })

  it('says so when nothing is held yet, and when the list cannot load', async () => {
    listSearchHoldings.mockResolvedValueOnce([])
    const { unmount } = render(<SearchSources />)
    expect(await screen.findByText(/No companies to show yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Search now for all/ })).not.toBeInTheDocument()
    unmount()

    listSearchHoldings.mockRejectedValueOnce(Object.assign(new Error('x'), { status: 503 }))
    render(<SearchSources />)
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be verified right now')
  })
})

describe('the event alerts page', () => {
  beforeEach(() => {
    getSearchSchedule.mockResolvedValue(SCHEDULE)
    listSearchHoldings.mockResolvedValue([])
    listSearchWebsites.mockResolvedValue(websites())
  })
  afterEach(() => vi.clearAllMocks())

  it('keeps the client alerts open for an agent, with no collapsible section', async () => {
    const { container } = render(<EventAlerts />)

    expect(screen.queryByText('Events that may affect your clients')).not.toBeInTheDocument()
    expect(screen.getByText('Ticker reports')).toBeInTheDocument()
    expect(container.querySelector('details')).toBeNull()
    expect(await screen.findByText('Search sources')).toBeInTheDocument()
  })

  it('keeps the client alerts open for an admin too, read only', async () => {
    const { container } = render(<EventAlerts readOnly />)

    expect(screen.getByText('Alert workspace read only')).toBeInTheDocument()
    expect(container.querySelector('details')).toBeNull()
    expect(await screen.findByText('Search sources')).toBeInTheDocument()
  })
})
