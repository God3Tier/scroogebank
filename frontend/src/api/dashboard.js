import { query, requestJson } from './request.js'

// dashboard-service (docs/api/dashboard-api.yaml). Every response is
// { generatedAt, window, scope, panels }, and each panel is
// { status: 'OK' | 'UNAVAILABLE', data, reason }: a 200 can still hold
// unavailable panels.

// endpoint is 'overview', 'portfolio', 'transactions', 'action-items' or
// 'agents' (admins only). params holds from and to (YYYY-MM-DD), and
// staleDays or sort where the endpoint takes them.
export function getDashboard(endpoint, { from, to, staleDays, sort } = {}) {
  return requestJson(`/api/dashboard/${endpoint}${query({ from, to, staleDays, sort })}`)
}

// The last `days` days, ending today in Singapore, where the bank's
// reporting days are (ADR-0038). The team comparison is only served for
// windows that end today.
export function windowForDays(days, now = new Date()) {
  const to = singaporeDate(now)
  const start = new Date(`${to}T00:00:00Z`)
  start.setUTCDate(start.getUTCDate() - (days - 1))
  return { from: start.toISOString().slice(0, 10), to }
}

function singaporeDate(now) {
  // en-CA formats dates as YYYY-MM-DD.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Singapore' }).format(now)
}

// A panel's data when it resolved, otherwise null.
export function panelData(panel) {
  return panel?.status === 'OK' ? panel.data : null
}
