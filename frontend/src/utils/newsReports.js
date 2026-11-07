// Shared by the Event Alerts report panel and Follow-up Emails (news-alert-drafts plan 5.2 and 5.5).

const DAY = 24 * 60 * 60 * 1000

/** Why a report was not put on a client's News Alert draft, in words for the agent. */
export const SKIP_REASONS = {
  NOT_AUTHORISED: 'no longer one of your clients',
  NOT_INFORMED: 'not in Plan client briefing',
  NOT_APPROVED: 'the approval is missing or expired',
  ALREADY_ON_DRAFT: 'already on a news draft',
  DRAFT_BEING_SENT: 'their draft is being sent',
  LIMIT_REACHED: 'their draft is full',
}
export const APPROVAL_DAYS = 7
export const STALE_AFTER_DAYS = 5
/** News older than this is stale: the page says so on a report the agent has not approved. */
export const STALE_NEWS_DAYS = 7
export const STALE_NEWS_EXPLAINED = `This news is more than ${STALE_NEWS_DAYS} days old. Check it is still current before you approve the report.`
export const FRESH_NEWS_EXPLAINED = `This news is ${STALE_NEWS_DAYS} days old or less. It turns stale after ${STALE_NEWS_DAYS} days.`

/** Whole days since the news, never negative; null when the date is missing or unreadable. */
export function newsAgeDays(newsDate, now) {
  const time = newsDate ? new Date(newsDate).getTime() : NaN
  return Number.isFinite(time) ? Math.max(0, Math.floor((now - time) / DAY)) : null
}

/** "from today", "1 day old", "4 days old". */
export function newsAgeText(newsDate, now) {
  const days = newsAgeDays(newsDate, now)
  if (days === null) return ''
  if (days === 0) return 'from today'
  return `${days} day${days === 1 ? '' : 's'} old`
}

/** True when the news is more than STALE_NEWS_DAYS old. A missing or unreadable date is not called stale. */
export function isNewsStale(newsDate, now) {
  const time = newsDate ? new Date(newsDate).getTime() : NaN
  return Number.isFinite(time) && now - time > STALE_NEWS_DAYS * DAY
}

/** The report body with the agent's wording in place of the original's: the same merge the server makes. */
export function mergeSections(body, sections) {
  if (!body || !sections) return body
  return {
    ...body,
    executiveSummary: (body.executiveSummary || []).map((item, i) => ({ ...item, text: sections.executiveSummary?.[i] ?? item.text })),
    keyFacts: (body.keyFacts || []).map((fact, i) => ({ ...fact, ...(sections.keyFacts?.[i] || {}) })),
    whyItMatters: (body.whyItMatters || []).map((item, i) => ({ ...item, text: sections.whyItMatters?.[i] ?? item.text })),
    whatToMonitor: sections.whatToMonitor || body.whatToMonitor,
    uncertainty: sections.uncertainty ?? body.uncertainty,
  }
}

/** "Approved 2 days ago, can be sent for 5 more days", from an approval's times. */
export function stalenessText(approval, now) {
  const age = Math.max(0, Math.floor((now - new Date(approval.approvedAt).getTime()) / DAY))
  const left = Math.max(0, Math.ceil((new Date(approval.validUntil).getTime() - now) / DAY))
  const approved = age === 0 ? 'Approved today' : `Approved ${age} day${age === 1 ? '' : 's'} ago`
  return `${approved}, can be sent for ${left} more day${left === 1 ? '' : 's'}${approval.stale ? '. Send soon or approve again later' : ''}`
}

/** A report on a news draft carries only its approval's end, which is 7 days after approval. */
export function draftReportApproval(report, now) {
  const validUntil = new Date(report.validUntil).getTime()
  const approvedAt = validUntil - APPROVAL_DAYS * DAY
  return { approvedAt, validUntil, stale: now - approvedAt >= STALE_AFTER_DAYS * DAY }
}

/**
 * What to tell the agent when a ticker has no report yet. The agent is never shown why (a draft that failed its checks, or
 * sources with no real facts): the system retries by itself, and the reason is in the worker's log, not on the page.
 */
export function noReportText() {
  return 'The report for this ticker is still being written. This page updates by itself.'
}

/** "Event severity: 18 of 30 (earnings news)": how much the kind of event adds to every client's score. */
export function severityText(severity) {
  const points = Number(severity.points)
  const shown = Number.isInteger(points) ? points : points.toFixed(1)
  const type = String(severity.eventType || '').toLowerCase().replace(/_/g, ' ')
  const negative = severity.direction === 'NEGATIVE' ? ', negative' : ''
  return `Event severity: ${shown} of ${severity.maximum}${type ? ` (${type}${negative})` : ''}. It is the same for every client; their score also counts their holding.`
}
