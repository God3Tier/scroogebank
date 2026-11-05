import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { listActivities } from '../api/activities.js'
import { listCorruptedLogs } from '../api/corruptedLogs.js'
import AdminAuditLogs from './AdminAuditLogs.jsx'

vi.mock('../api/activities.js', () => ({ listActivities: vi.fn() }))
vi.mock('../api/corruptedLogs.js', () => ({ listCorruptedLogs: vi.fn() }))

// Synthetic data only.
const activity = {
  id: 'act-1',
  timestamp: '2026-10-01T09:00:00Z',
  actorId: 'synthetic-admin-sub',
  actorName: 'Admin Example',
  sourceService: 'CRM',
  action: 'CREATE',
  payload: { route: 'POST /api/clients' },
}

const finding = {
  id: 'finding-1',
  date_of_detect: '2026-10-02T10:00:00Z',
  reason: 'Hash mismatch',
  payload: { source_service: 'CRM', action: 'DELETE', actor_id: 'synthetic-agent-sub' },
}

afterEach(() => {
  vi.resetAllMocks()
})

describe('AdminAuditLogs', () => {
  it('shows audit activity and corrupted logs as data tables', async () => {
    listActivities.mockResolvedValue({ content: [activity], nextCursor: null })
    listCorruptedLogs.mockResolvedValue([finding])
    render(<AdminAuditLogs />)

    const activityCard = screen.getByRole('region', { name: 'Audit Activity' })
    expect(await within(activityCard).findByText('Admin Example')).toBeInTheDocument()
    expect(within(activityCard).getByText('CREATE')).toHaveClass('badge', 'badge-active')
    expect(within(activityCard).getByRole('table')).toHaveClass('data-table')
    expect(within(activityCard).getByRole('navigation', { name: 'Audit activity pages' })).toBeInTheDocument()

    const corruptedCard = screen.getByRole('region', { name: 'Corrupted Audit Logs' })
    expect(await within(corruptedCard).findByText('Hash mismatch')).toHaveClass('badge-failed')
    expect(within(corruptedCard).getByText('DELETE')).toHaveClass('badge-failed')
  })

  it('offers a retry when audit activity fails to load', async () => {
    listActivities.mockRejectedValueOnce(Object.assign(new Error('down'), { status: 503 }))
    listActivities.mockResolvedValue({ content: [], nextCursor: null })
    listCorruptedLogs.mockResolvedValue([])
    render(<AdminAuditLogs />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Audit activity is temporarily unavailable.')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('No audit activity found.')).toBeInTheDocument()
    expect(await screen.findByText('No corrupted audit logs found.')).toBeInTheDocument()
  })
})
