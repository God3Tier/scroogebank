import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { addSearchDomain, listSearchSources, removeSearchDomain } from '../api/searchSources.js'
import SearchSources from './SearchSources.jsx'

vi.mock('../api/searchSources.js', () => ({
  listSearchSources: vi.fn(), addSearchDomain: vi.fn(), removeSearchDomain: vi.fn(),
}))

// Synthetic data only.
function company(overrides = {}) {
  return {
    instrumentId: 'SGX-D05', ticker: 'D05', issuerName: 'DBS Group Holdings Ltd',
    official: ['sgx.com', 'dbs.com'], shared: ['reuters.com', 'cnbc.com', 'ft.com'],
    custom: [], canAdd: true, maxCustom: 10, ...overrides,
  }
}

const MINE = { id: 'd1', domain: 'businesstimes.com.sg', addedAt: '2026-11-04T04:00:00Z', addedByYou: true }
const THEIRS = { id: 'd2', domain: 'example.com', addedAt: '2026-11-03T04:00:00Z', addedByYou: false }

describe('search sources', () => {
  afterEach(() => vi.clearAllMocks())

  it('shows official, shared and added sites per company and who added each', async () => {
    listSearchSources.mockResolvedValue([company({ custom: [MINE, THEIRS] })])
    render(<SearchSources />)

    const card = await screen.findByRole('article', { name: 'DBS Group Holdings Ltd search sources' })
    expect(within(card).getByText('sgx.com')).toBeInTheDocument()
    expect(within(card).getByText('ft.com')).toBeInTheDocument()
    expect(within(card).getByText('businesstimes.com.sg')).toBeInTheDocument()
    expect(card).toHaveTextContent('by you')
    expect(card).toHaveTextContent('by another agent')
  })

  it('adds a site, then reloads the list', async () => {
    listSearchSources.mockResolvedValueOnce([company()]).mockResolvedValueOnce([company({ custom: [MINE] })])
    addSearchDomain.mockResolvedValue(MINE)
    render(<SearchSources />)

    const input = await screen.findByLabelText('Add a site for D05')
    expect(screen.getByRole('button', { name: 'Add site' })).toBeDisabled()
    fireEvent.change(input, { target: { value: 'www.BusinessTimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add site' }))

    expect(await screen.findByText('businesstimes.com.sg')).toBeInTheDocument()
    expect(addSearchDomain).toHaveBeenCalledWith('SGX-D05', 'www.BusinessTimes.com.sg')
    expect(screen.getByLabelText('Add a site for D05')).toHaveValue('')
  })

  it('says a search started when adding a site starts one', async () => {
    listSearchSources.mockResolvedValueOnce([company()]).mockResolvedValueOnce([company({ custom: [MINE] })])
    addSearchDomain.mockResolvedValue({ ...MINE, research: 'STARTED', researchLookbackDays: 14 })
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a site for D05'), { target: { value: 'businesstimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add site' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Searching the last 14 days')
  })

  it('says the next scheduled run will pick it up when no instant search started', async () => {
    for (const research of ['COOLDOWN', 'DAILY_LIMIT', 'FAILED']) {
      listSearchSources.mockResolvedValueOnce([company()]).mockResolvedValueOnce([company({ custom: [MINE] })])
      addSearchDomain.mockResolvedValue({ ...MINE, research })
      const { unmount } = render(<SearchSources />)

      fireEvent.change(await screen.findByLabelText('Add a site for D05'), { target: { value: 'businesstimes.com.sg' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add site' }))

      expect(await screen.findByRole('status')).toHaveTextContent('next scheduled run')
      unmount()
    }
  })

  it('shows no search message when the feature is off', async () => {
    listSearchSources.mockResolvedValueOnce([company()]).mockResolvedValueOnce([company({ custom: [MINE] })])
    addSearchDomain.mockResolvedValue({ ...MINE, research: 'OFF' })
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a site for D05'), { target: { value: 'businesstimes.com.sg' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add site' }))

    expect(await screen.findByText('businesstimes.com.sg')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('shows the refusal next to the form and keeps what was typed', async () => {
    listSearchSources.mockResolvedValue([company()])
    addSearchDomain.mockRejectedValue(Object.assign(
      new Error('social, forum and blog platforms cannot be used as news sources'), { status: 400 }))
    render(<SearchSources />)

    fireEvent.change(await screen.findByLabelText('Add a site for D05'), { target: { value: 'reddit.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add site' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('social, forum and blog platforms')
    expect(screen.getByLabelText('Add a site for D05')).toHaveValue('reddit.com')
  })

  it('asks before removing, and removes only after confirming', async () => {
    listSearchSources.mockResolvedValueOnce([company({ custom: [MINE] })]).mockResolvedValueOnce([company()])
    removeSearchDomain.mockResolvedValue(null)
    render(<SearchSources />)

    fireEvent.click(await screen.findByRole('button', { name: 'Remove businesstimes.com.sg' }))
    expect(removeSearchDomain).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(removeSearchDomain).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Remove businesstimes.com.sg' }))
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove' }))

    await waitFor(() => expect(removeSearchDomain).toHaveBeenCalledWith('SGX-D05', 'd1'))
    expect(await screen.findByText('none yet')).toBeInTheDocument()
  })

  it('stops offering to add when the company is at its limit', async () => {
    const many = Array.from({ length: 10 }, (_, index) => ({ ...THEIRS, id: `d${index}`, domain: `site${index}.example.com` }))
    listSearchSources.mockResolvedValue([company({ custom: many })])
    render(<SearchSources />)

    expect(await screen.findByText(/10 added sites/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Add a site for D05')).not.toBeInTheDocument()
  })

  it('lets an admin look and remove but not add', async () => {
    listSearchSources.mockResolvedValue([company({ canAdd: false, custom: [THEIRS] })])
    render(<SearchSources readOnly />)

    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Add a site for/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove example.com' })).toBeInTheDocument()
  })

  it('says so when nothing is held yet, and when the list cannot load', async () => {
    listSearchSources.mockResolvedValueOnce([])
    const { unmount } = render(<SearchSources />)
    expect(await screen.findByText(/No companies to show yet/)).toBeInTheDocument()
    unmount()

    listSearchSources.mockRejectedValueOnce(Object.assign(new Error('x'), { status: 503 }))
    render(<SearchSources />)
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot be verified right now')
  })
})
