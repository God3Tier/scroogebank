import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { listActivities } from '../api/activities.js'
import ActivityFeed from './ActivityFeed.jsx'

vi.mock('../api/activities.js', () => ({ listActivities: vi.fn() }))

const CLIENT_ID = '3f9c2a1e-0000-4000-8000-000000000101'
const AGENT_SUB = 'agent-sub-0001'
const DAY_MS = 24 * 60 * 60 * 1000

// Synthetic entries, shaped like user-service's ActivityResponse.
const verified = {
  id: '41',
  sourceService: 'CRM',
  action: 'UPDATE',
  actorId: AGENT_SUB,
  actorName: 'Synthetic Agent',
  timestamp: '2026-10-14T02:42:00Z',
  payload: {
    http_method: 'POST', endpoint: '/api/clients/:clientId/verify', client_id: CLIENT_ID, document_ids: ['doc-1', 'doc-2'],
  },
}
const created = {
  id: '40',
  sourceService: 'CRM',
  action: 'CREATE',
  actorId: AGENT_SUB,
  actorName: 'Synthetic Agent',
  timestamp: '2026-10-13T07:47:00Z',
  payload: { http_method: 'POST', endpoint: '/api/clients' },
}
const disabledUser = {
  id: '39',
  sourceService: 'USER_MANAGEMENT',
  action: 'DISABLE',
  actorId: 'admin-sub-0001',
  actorName: 'Synthetic Admin',
  timestamp: '2026-10-12T03:30:00Z',
  payload: { resource_type: 'USER', resource_id: 'user-0001', user_role: 'AGENT' },
}

function page(content, nextCursor = null) {
  return { content, nextCursor }
}

function renderFeed(props = {}) {
  return render(
    <MemoryRouter>
      <ActivityFeed {...props} />
    </MemoryRouter>,
  )
}

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

describe('an agent\'s activity panel', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('lists their own changes, newest first, without naming them', async () => {
    listActivities.mockResolvedValue(page([verified, created]))
    renderFeed()

    const list = await screen.findByRole('list', { name: 'Activities, newest first' })
    const rows = within(list).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Verified a client')
    expect(rows[0]).toHaveTextContent('with 2 documents')
    expect(rows[1]).toHaveTextContent('Created a client profile')
    expect(screen.queryByText('Synthetic Agent')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'My Recent Activities' })).toBeInTheDocument()
  })

  it('never asks for anyone else: user-service takes the agent from the token', async () => {
    listActivities.mockResolvedValue(page([verified]))
    renderFeed()

    await screen.findByText('Verified a client')
    expect(listActivities).toHaveBeenCalledWith(expect.objectContaining({ agentId: undefined, cursor: null }))
    expect(screen.queryByRole('button', { name: 'Synthetic Agent' })).not.toBeInTheDocument()
  })

  it("opens the client an entry is about, by ID only", async () => {
    listActivities.mockResolvedValue(page([verified]))
    renderFeed()

    expect(await screen.findByRole('link', { name: 'Open client' })).toHaveAttribute('href', `/clients/${CLIENT_ID}`)
    expect(screen.getByText('3f9c2a1e')).toHaveAttribute('title', CLIENT_ID)
  })

  it('says when there is nothing yet', async () => {
    listActivities.mockResolvedValue(page([]))
    renderFeed()

    expect(await screen.findByText(/No changes yet\. Clients you create, update or verify appear here\./)).toBeInTheDocument()
  })

  it('loads the next page after the ones shown, and stops on the last', async () => {
    listActivities
      .mockResolvedValueOnce(page([verified], 'cursor-2'))
      .mockResolvedValueOnce(page([created]))
    renderFeed()

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))

    expect(await screen.findByText('Created a client profile')).toBeInTheDocument()
    expect(screen.getByText('Verified a client')).toBeInTheDocument()
    expect(listActivities).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'cursor-2' }))
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('narrows the feed to a period fixed when it is chosen', async () => {
    listActivities.mockResolvedValue(page([verified]))
    renderFeed()
    await screen.findByText('Verified a client')

    const before = Date.now()
    fireEvent.change(screen.getByLabelText('Period'), { target: { value: '7' } })

    await waitFor(() => expect(listActivities).toHaveBeenCalledTimes(2))
    const { from } = listActivities.mock.calls[1][0]
    expect(from).toBeInstanceOf(Date)
    expect(before - 7 * DAY_MS - from.getTime()).toBeLessThanOrEqual(0)
    expect(Date.now() - 7 * DAY_MS - from.getTime()).toBeGreaterThanOrEqual(0)
  })

  it('says the feed is unavailable when the auditor is down, and tries again', async () => {
    listActivities
      .mockRejectedValueOnce(Object.assign(new Error('Activity log is temporarily unavailable'), { status: 503 }))
      .mockResolvedValueOnce(page([verified]))
    renderFeed()

    expect(await screen.findByRole('alert')).toHaveTextContent('Activity is temporarily unavailable.')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Verified a client')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the entries shown when a later page fails, and retries that page', async () => {
    listActivities
      .mockResolvedValueOnce(page([verified], 'cursor-2'))
      .mockRejectedValueOnce(Object.assign(new Error('Request failed (500)'), { status: 500 }))
      .mockResolvedValueOnce(page([created]))
    renderFeed()

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Activities could not be loaded.')
    expect(screen.getByText('Verified a client')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Created a client profile')).toBeInTheDocument()
    expect(listActivities).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'cursor-2' }))
  })
})

describe("an admin's activity panel", () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('names who made each change', async () => {
    listActivities.mockResolvedValue(page([verified, disabledUser]))
    renderFeed({ admin: true })

    const rows = within(await screen.findByRole('list', { name: 'Activities, newest first' })).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Synthetic Agent verified a client')
    expect(rows[1]).toHaveTextContent('Synthetic Admin disabled a user')
    expect(screen.getByRole('heading', { name: 'Recent Activities' })).toBeInTheDocument()
  })

  it("shows only one user's changes when their name is picked, until the filter is removed", async () => {
    listActivities.mockResolvedValue(page([verified, disabledUser]))
    renderFeed({ admin: true })

    fireEvent.click(await screen.findByRole('button', { name: 'Synthetic Agent' }))

    await waitFor(() => expect(listActivities).toHaveBeenLastCalledWith(expect.objectContaining({ agentId: AGENT_SUB })))
    const chip = await screen.findByRole('button', { name: /Showing only Synthetic Agent/ })

    fireEvent.click(chip)
    await waitFor(() => expect(listActivities).toHaveBeenLastCalledWith(expect.objectContaining({ agentId: undefined })))
    expect(screen.queryByRole('button', { name: /Showing only/ })).not.toBeInTheDocument()
  })

  it('cannot filter on work no user started', async () => {
    listActivities.mockResolvedValue(page([{ ...disabledUser, actorId: 'system:root-admin-initializer', actorName: null }]))
    renderFeed({ admin: true })

    expect(await screen.findByText('System')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'System' })).not.toBeInTheDocument()
  })

  it('drops a slow reply for a filter that was replaced', async () => {
    const first = deferred()
    listActivities
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(page([created]))
    renderFeed({ admin: true })

    fireEvent.change(screen.getByLabelText('Period'), { target: { value: '1' } })
    expect(await screen.findByText(/created a client profile/)).toBeInTheDocument()

    await act(async () => {
      first.resolve(page([disabledUser]))
      await first.promise
    })
    expect(screen.queryByText(/disabled a user/)).not.toBeInTheDocument()
    expect(screen.getByText(/created a client profile/)).toBeInTheDocument()
  })
})
