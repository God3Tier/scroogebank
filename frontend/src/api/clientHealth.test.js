import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authorizationHeader } from '../auth/session.js'
import {
  acceptHealthTask,
  completeHealthTask,
  dismissHealthTask,
  getHealthTask,
  getHealthTaskHistory,
  listHealthTasks,
} from './clientHealth.js'

vi.mock('../auth/session.js', () => ({ authorizationHeader: vi.fn() }))

function response(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}

describe('Client Health API', () => {
  let fetchMock

  beforeEach(() => {
    authorizationHeader.mockResolvedValue({ Authorization: 'Bearer synthetic-token' })
    fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(response({ items: [], nextCursor: null })))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'correlation-1' })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('lists with repeated filters, assignment scope, and cursor', async () => {
    await listHealthTasks({
      statuses: ['OPEN', 'IN_REVIEW'],
      severities: ['HIGH'],
      assignedAgentSubject: 'agent-1',
      cursor: 'opaque',
      limit: 10,
    })

    const [url, options] = fetchMock.mock.calls[0]
    const parsed = new URL(url, 'https://example.test')
    expect(parsed.pathname).toBe('/api/relationship-health/tasks')
    expect(parsed.searchParams.getAll('status')).toEqual(['OPEN', 'IN_REVIEW'])
    expect(parsed.searchParams.getAll('severity')).toEqual(['HIGH'])
    expect(parsed.searchParams.get('assignedAgentSubject')).toBe('agent-1')
    expect(parsed.searchParams.get('cursor')).toBe('opaque')
    expect(options.headers.Authorization).toBe('Bearer synthetic-token')
  })

  it('loads task detail and history', async () => {
    await getHealthTask('task 1')
    await getHealthTaskHistory('task 1')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/relationship-health/tasks/task%201')
    expect(fetchMock.mock.calls[1][0]).toBe('/api/relationship-health/tasks/task%201/history')
  })

  it('posts versioned actions with correlation IDs and bounded data', async () => {
    await acceptHealthTask('task-1', 1)
    await completeHealthTask('task-1', 2, 'CLIENT_REVIEW_COMPLETED', 'Reviewed')
    await dismissHealthTask('task-2', 1, 'EXPECTED_CLIENT_ACTIVITY', null)

    const calls = fetchMock.mock.calls
    expect(calls.map(([url]) => url)).toEqual([
      '/api/relationship-health/tasks/task-1/accept',
      '/api/relationship-health/tasks/task-1/complete',
      '/api/relationship-health/tasks/task-2/dismiss',
    ])
    expect(calls[0][1]).toMatchObject({
      method: 'POST',
      headers: expect.objectContaining({
        Authorization: 'Bearer synthetic-token',
        'Content-Type': 'application/json',
        'X-Correlation-Id': 'correlation-1',
      }),
    })
    expect(JSON.parse(calls[1][1].body)).toEqual({
      expectedVersion: 2,
      outcomeCode: 'CLIENT_REVIEW_COMPLETED',
      note: 'Reviewed',
    })
  })

  it('preserves RFC problem status and detail', async () => {
    fetchMock.mockResolvedValueOnce(response({ detail: 'stale version' }, 409))

    await expect(acceptHealthTask('task-1', 1)).rejects.toMatchObject({
      message: 'stale version',
      status: 409,
    })
  })
})
