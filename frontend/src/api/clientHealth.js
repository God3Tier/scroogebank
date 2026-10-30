import { requestJson } from './request.js'

const BASE_PATH = '/api/relationship-health/tasks'

export function listHealthTasks({
  statuses = [],
  severities = [],
  assignedAgentSubject,
  cursor,
  limit = 25,
} = {}) {
  const params = new URLSearchParams({ limit: String(limit) })
  statuses.forEach((status) => params.append('status', status))
  severities.forEach((severity) => params.append('severity', severity))
  if (assignedAgentSubject) params.set('assignedAgentSubject', assignedAgentSubject)
  if (cursor) params.set('cursor', cursor)
  return requestJson(`${BASE_PATH}?${params}`)
}

export function getHealthTask(taskId) {
  return requestJson(`${BASE_PATH}/${encodeURIComponent(taskId)}`)
}

export function getHealthTaskHistory(taskId) {
  return requestJson(`${BASE_PATH}/${encodeURIComponent(taskId)}/history`)
}

export function acceptHealthTask(taskId, expectedVersion) {
  return action(taskId, 'accept', { expectedVersion })
}

export function completeHealthTask(
  taskId,
  expectedVersion,
  outcomeCode = 'CLIENT_REVIEW_COMPLETED',
  note = null,
) {
  return action(taskId, 'complete', { expectedVersion, outcomeCode, note })
}

export function dismissHealthTask(
  taskId,
  expectedVersion,
  reasonCode = 'EXPECTED_CLIENT_ACTIVITY',
  note = null,
) {
  return action(taskId, 'dismiss', { expectedVersion, reasonCode, note })
}

function action(taskId, operation, body) {
  return requestJson(`${BASE_PATH}/${encodeURIComponent(taskId)}/${operation}`, {
    method: 'POST',
    headers: { 'X-Correlation-Id': crypto.randomUUID() },
    body,
  })
}
