import { query, requestBlob, requestJson } from './request.js'

// engagement-service (docs/api/engagement-api.yaml): the agent's calendar,
// reminders, follow-up drafts and notification preferences. Agents only; the
// service takes the agent from the access token, never from the request.

export function listEvents({ from, to, includeCancelled } = {}) {
  return requestJson(`/api/engagement/events${query({ from, to, includeCancelled })}`)
}

export function getEvent(eventId) {
  return requestJson(`/api/engagement/events/${encodeURIComponent(eventId)}`)
}

export function createEvent(event) {
  return requestJson('/api/engagement/events', { method: 'POST', body: event })
}

// event includes the version it replaces (optimistic locking).
export function updateEvent(eventId, event) {
  return requestJson(`/api/engagement/events/${encodeURIComponent(eventId)}`, { method: 'PUT', body: event })
}

export function cancelEvent(eventId) {
  return requestJson(`/api/engagement/events/${encodeURIComponent(eventId)}`, { method: 'DELETE' })
}

// from and to are YYYY-MM-DD, both inclusive.
export function listHolidays({ country = 'SG', from, to }) {
  return requestJson(`/api/engagement/holidays${query({ country, from, to })}`)
}

// The last day the holiday list covers (coveredThrough, or null), so the
// calendar does not offer months without holidays (ADR-0057).
export function getHolidayCoverage({ country = 'SG' } = {}) {
  return requestJson(`/api/engagement/holidays/coverage${query({ country })}`)
}

export function listDrafts({ status, clientId, limit } = {}) {
  return requestJson(`/api/engagement/drafts${query({ status, clientId, limit })}`)
}

// subject and body have the client's and agent's names filled in; both are
// null when the names cannot be looked up.
export function getDraft(draftId) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}`)
}

export function createDraft({ clientId, purpose, tone, eventId, healthTaskId, alertId }) {
  return requestJson('/api/engagement/drafts', {
    method: 'POST',
    body: { clientId, purpose, tone, eventId: eventId || null, healthTaskId: healthTaskId || null, alertId: alertId || null },
  })
}

// The Follow-up Emails page, opened from a Client Health task or an alert
// with the form filled in (ADR-0068). engagement-service checks that the task
// or alert is the agent's and about this client.
export function followUpLink({ purpose, clientId, healthTaskId, alertId }) {
  const params = new URLSearchParams({ purpose, clientId })
  if (healthTaskId) params.set('healthTaskId', healthTaskId)
  if (alertId) params.set('alertId', alertId)
  return `/agent/drafts?${params}`
}

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

// Alerts on CRM-linked holdings name the CRM client (ADR-0066); older ones
// carry a synthetic reference that no draft can be for.
export function isCrmClientId(value) {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

// Plain text with the names written out; the service stores them as
// placeholders (ADR-0056).
export function updateDraft(draftId, { subject, body, version }) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}`, {
    method: 'PUT',
    body: { subject, body, version },
  })
}

export function discardDraft(draftId) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}`, { method: 'DELETE' })
}

// version is the one the agent last saw, so they send what they reviewed.
export function sendDraft(draftId, version) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}/send`, { method: 'POST', body: { version } })
}

export function getPreferences() {
  return requestJson('/api/engagement/preferences')
}

export function updatePreferences({ invitesEnabled, remindersEnabled }) {
  return requestJson('/api/engagement/preferences', { method: 'PUT', body: { invitesEnabled, remindersEnabled } })
}

// Times. Agents work in Singapore time, which has no daylight saving, so a
// local time is always +08:00.
export const SINGAPORE = 'Asia/Singapore'

const PARTS = new Intl.DateTimeFormat('en-CA', {
  timeZone: SINGAPORE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

// { date: 'YYYY-MM-DD', time: 'HH:mm' } in Singapore.
export function singaporeParts(value) {
  const parts = Object.fromEntries(PARTS.formatToParts(new Date(value)).map((part) => [part.type, part.value]))
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` }
}

// The value of a datetime-local input for an instant, in Singapore time.
export function toLocalInput(value) {
  const { date, time } = singaporeParts(value)
  return `${date}T${time}`
}

// An ISO time with an offset, from a datetime-local input read as Singapore time.
export function fromLocalInput(value) {
  return `${value}:00+08:00`
}

// The first instant of a month and of the next one, as the API takes them.
// month is 1 to 12.
export function monthWindow(year, month) {
  const next = month === 12 ? { year: year + 1, month: 1 } : { year, month: month + 1 }
  const pad = (n) => String(n).padStart(2, '0')
  return {
    from: `${year}-${pad(month)}-01T00:00:00+08:00`,
    to: `${next.year}-${pad(next.month)}-01T00:00:00+08:00`,
  }
}

export function formatWhen(value) {
  return new Date(value).toLocaleString('en-SG', { timeZone: SINGAPORE, dateStyle: 'medium', timeStyle: 'short' })
}

export function formatTime(value) {
  return new Date(value).toLocaleTimeString('en-SG', { timeZone: SINGAPORE, timeStyle: 'short' })
}

export const REMINDER_OPTIONS = [
  { minutes: 1440, label: '1 day before' },
  { minutes: 60, label: '1 hour before' },
  { minutes: 30, label: '30 minutes before' },
  { minutes: 15, label: '15 minutes before' },
]

export const PURPOSES = [
  { value: 'CHECK_IN', label: 'Check-in' },
  { value: 'MEETING_FOLLOW_UP', label: 'Meeting follow-up' },
  { value: 'HOLIDAY_GREETING', label: 'Holiday greeting' },
  { value: 'PRE_HOLIDAY_REVIEW', label: 'Catch-up before a holiday' },
  // Offered only when the page is opened from a Client Health task (ADR-0068).
  { value: 'HEALTH_CHECK_IN', label: 'Client Health follow-up', source: 'healthTaskId' },
  // Made from the reports the agent approved on Event Alerts (news-alert-drafts plan 5.5).
  { value: 'NEWS_ALERT', label: 'News Alert', news: true },
]

// Older drafts may still show the purpose News Alert replaced.
export const PURPOSE_LABELS = {
  ...Object.fromEntries(PURPOSES.map((purpose) => [purpose.value, purpose.label])),
  ALERT_FOLLOW_UP: 'Alert follow-up',
}

// Shown where the agent adds their own details to a follow-up from another
// feature; sending is refused until it is replaced or deleted (ADR-0068).
export const DETAILS_PROMPT = '[Add your details here]'

// Why a template was used instead of the model (Draft.sourceReason).
export const SOURCE_REASONS = {
  ai_disabled: 'AI drafting is turned off',
  daily_cap_reached: 'your daily AI draft limit is reached',
  monthly_quota_reached: 'the monthly AI quota is reached',
  model_timeout: 'the AI model took too long',
  model_error: 'the AI model was unavailable',
  screen_rejected: 'the AI draft did not pass the safety checks',
}

export const MAIL_STATUS_LABELS = {
  PENDING: 'Queued',
  SENT: 'Sent',
  FAILED: 'Failed',
  MISSED: 'Missed',
  CANCELLED: 'Cancelled',
  DISABLED: 'Off',
}

// "Notify selected clients about <company>": each chosen client's approved report goes on that client's
// News Alert draft. Returns { drafts: [{draftId, clientId, created, reportCount, added}], skipped }.
export function createNewsDrafts({ eventId, alertIds }) {
  return requestJson('/api/engagement/drafts/news', { method: 'POST', body: { eventId, alertIds } })
}

// The alerts on the agent's open News Alert drafts: those clients cannot be dismissed on Event Alerts.
export function listOpenNewsAlerts() {
  return requestJson('/api/engagement/drafts/news/open-alerts')
}

export function removeNewsReport(draftId, alertId) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}/reports/${encodeURIComponent(alertId)}`, {
    method: 'DELETE',
  })
}

// keep: keep the agent's text and clear the "Rebuild summary?" prompt.
export function rebuildNewsSummary(draftId, { keep = false } = {}) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}/summary/rebuild${keep ? '?keep=true' : ''}`, {
    method: 'POST',
  })
}

// Exactly the combined PDF the client would get.
export function previewNewsPdf(draftId) {
  return requestBlob(`/api/engagement/drafts/${encodeURIComponent(draftId)}/pdf`)
}

export function retryNewsStatus(draftId) {
  return requestJson(`/api/engagement/drafts/${encodeURIComponent(draftId)}/news-status`, { method: 'POST' })
}
