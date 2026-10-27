import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  acceptHealthTask,
  completeHealthTask,
  dismissHealthTask,
  getHealthTask,
  getHealthTaskHistory,
  listHealthTasks,
} from '../api/clientHealth.js'
import ClientHealthMonitor from './ClientHealthMonitor.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../api/clientHealth.js', () => ({
  acceptHealthTask: vi.fn(),
  completeHealthTask: vi.fn(),
  dismissHealthTask: vi.fn(),
  getHealthTask: vi.fn(),
  getHealthTaskHistory: vi.fn(),
  listHealthTasks: vi.fn(),
}))

const TASK = {
  schemaVersion: '1',
  taskId: 'task-1',
  clientId: 'client-1',
  assignedAgentSubject: 'agent-1',
  status: 'OPEN',
  severity: 'HIGH',
  explanation: {
    text: 'Verified activity changed. Human review required.',
    source: 'TEMPLATE_FALLBACK',
    generatedAt: '2026-10-27T01:00:00Z',
  },
  evidence: [{
    type: 'TRANSACTION_ACTIVITY_DECLINE',
    accountId: 'account-1',
    ruleVersion: 'activity-v1',
    severity: 'HIGH',
  }],
  version: 1,
  createdAt: '2026-10-27T00:00:00Z',
  updatedAt: '2026-10-27T01:00:00Z',
}

const HISTORY = {
  schemaVersion: '1',
  taskId: 'task-1',
  items: [{
    taskVersion: 1,
    action: 'CREATED',
    actor: 'SYSTEM',
    occurredAt: '2026-10-27T00:00:00Z',
    beforeStatus: null,
    afterStatus: 'OPEN',
    beforeSeverity: null,
    afterSeverity: 'HIGH',
    evidenceAddedCount: 1,
    dispositionCode: null,
    note: null,
  }],
}

beforeEach(() => {
  vi.clearAllMocks()
  useAuth.mockReturnValue({
    user: { profile: { sub: 'agent-1', 'cognito:groups': ['Agent'] } },
  })
  listHealthTasks.mockResolvedValue({ schemaVersion: '1', items: [TASK], nextCursor: null })
  getHealthTask.mockResolvedValue(TASK)
  getHealthTaskHistory.mockResolvedValue(HISTORY)
})

function renderPage() {
  render(<MemoryRouter><ClientHealthMonitor /></MemoryRouter>)
}

describe('Client Health UI integration', () => {
  it('loads task evidence, explanation, history, and performs a versioned action', async () => {
    const accepted = { ...TASK, status: 'IN_REVIEW', version: 2 }
    acceptHealthTask.mockResolvedValue(accepted)
    renderPage()

    expect((await screen.findAllByText('client-1')).length).toBeGreaterThan(0)
    const evidenceHeading = await screen.findByRole('heading', { name: 'Signal evidence' })
    const evidenceSection = evidenceHeading.closest('section')
    expect(evidenceSection).not.toBeNull()
    expect(within(evidenceSection).getByText('Transaction Activity Decline')).toBeInTheDocument()
    expect(await screen.findByText(/Verified activity changed/)).toBeInTheDocument()
    expect(await screen.findByText('Source: Template Fallback')).toBeInTheDocument()
    expect(await screen.findByText(/Created → Open/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Accept task' }))

    await waitFor(() => expect(acceptHealthTask).toHaveBeenCalledWith('task-1', 1))
    expect((await screen.findAllByText('In Review')).length).toBeGreaterThan(0)
  })

  it('makes the Admin view read-only and supports assignment filtering', async () => {
    useAuth.mockReturnValue({
      user: { profile: { sub: 'admin-1', 'cognito:groups': ['Admin'] } },
    })
    renderPage()

    expect((await screen.findAllByText(/Read-only oversight/)).length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Accept task' })).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Assigned Agent subject'), {
      target: { value: 'agent-2' },
    })
    await waitFor(() => expect(listHealthTasks).toHaveBeenLastCalledWith(
      expect.objectContaining({ assignedAgentSubject: 'agent-2' }),
    ))
  })

  it('loads additional cursor pages without replacing visible tasks', async () => {
    listHealthTasks
      .mockResolvedValueOnce({ items: [TASK], nextCursor: 'next-page' })
      .mockResolvedValueOnce({
        items: [{ ...TASK, taskId: 'task-2', clientId: 'client-2' }],
        nextCursor: null,
      })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: 'Load more' }))

    expect(await screen.findByText('client-2')).toBeInTheDocument()
    expect(screen.getAllByText('client-1').length).toBeGreaterThan(0)
  })

  it('shows dependency failures without exposing mutation controls', async () => {
    listHealthTasks.mockRejectedValue(
      Object.assign(new Error('assignment unavailable'), { status: 503 }),
    )
    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Client assignment is temporarily unavailable',
    )
    expect(screen.queryByRole('button', { name: 'Accept task' })).not.toBeInTheDocument()
  })

  it('exposes complete and dismiss actions only in valid states', async () => {
    getHealthTask.mockResolvedValue({ ...TASK, status: 'IN_REVIEW', version: 2 })
    completeHealthTask.mockResolvedValue({ ...TASK, status: 'COMPLETED', version: 3 })
    dismissHealthTask.mockResolvedValue({ ...TASK, status: 'DISMISSED', version: 3 })
    renderPage()

    const note = await screen.findByLabelText('Optional review note')
    fireEvent.change(note, { target: { value: 'Synthetic review complete' } })
    fireEvent.click(screen.getByRole('button', { name: 'Complete task' }))

    await waitFor(() => expect(completeHealthTask).toHaveBeenCalledWith(
      'task-1', 2, 'CLIENT_REVIEW_COMPLETED', 'Synthetic review complete',
    ))
  })
})
