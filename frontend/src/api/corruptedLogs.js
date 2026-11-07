import { requestJson } from './request.js'

export function listCorruptedLogs() {
  return requestJson('/api/corrupted-logs')
}