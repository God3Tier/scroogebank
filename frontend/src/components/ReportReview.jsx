import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { approveReport, discardReportEdit } from '../api/alertEvents.js'
import { createNewsDrafts, isCrmClientId } from '../api/engagement.js'
import { FRESH_NEWS_EXPLAINED, isNewsStale, newsAgeText, SKIP_REASONS, STALE_NEWS_EXPLAINED, stalenessText } from '../utils/newsReports.js'

// Edit, approve, then notify (news-alert-drafts plan 5.2). The agent may change the report's wording; the headline
// and sources stay fixed, and the edited text is checked again. Approval is final and freezes what the client gets.
// After approval the approve button is replaced by "Notify selected clients about <company>", which puts the report on each
// selected client's News Alert draft in Follow-up Emails. Nothing here sends an email.

const TIME = new Intl.DateTimeFormat('en-SG', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore' })
const DATE = new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', timeZone: 'Asia/Singapore' })

/**
 * "Fresh" or "Stale" at the top of a report the agent has not approved: stale when its news is more than 7 days old,
 * fresh until then, with how old it is. The same words are the hover text, so the meaning is there without a hover.
 */
export function NewsFreshnessNotice({ report, review, now: fixedNow }) {
  const [loadedAt] = useState(() => Date.now())
  const now = fixedNow ?? loadedAt
  if (!report || !review) return null
  if (review.approval?.valid) return null
  const newsDate = review.newsDate || report.createdAt
  const age = newsAgeText(newsDate, now)
  if (!age) return null
  const stale = isNewsStale(newsDate, now)
  const explained = stale ? STALE_NEWS_EXPLAINED : FRESH_NEWS_EXPLAINED
  return (
    <p className={stale ? 'stale-notice' : 'stale-notice stale-notice--fresh'} role="note" title={explained}>
      <strong className="stale-notice__chip">{stale ? 'Stale' : 'Fresh'}</strong> {age ? `News ${age}. ` : ''}{explained}
    </p>
  )
}

/**
 * clients: the agent's clients on this report; selected: { alertId: true }. Approval and Notify act on the selected
 * clients in Plan client briefing.
 */
export function ReportReview({ eventId, report, review, clients, selected, company = 'this company', editing = false, onReviewChanged }) {
  const notifyLabel = `Notify selected clients about ${company}`
  const navigate = useNavigate()
  const [now] = useState(() => Date.now())
  const [state, setState] = useState({ busy: false, error: '', message: '' })

  if (!report || !review || review.reportVersion === 0) return null
  const approval = review.approval
  const valid = Boolean(approval?.valid)
  const chosen = clients.filter((c) => selected[c.alertId] && c.status === 'INFORMED' && isCrmClientId(c.clientRef))
  const anyInformed = clients.some((c) => c.status === 'INFORMED')
  const count = chosen.length

  async function approve() {
    if (!window.confirm('Approval is final. You cannot edit this report again after approving. Approve it now?')) return
    setState({ busy: true, error: '', message: '' })
    try {
      await approveReport(eventId, review.reportVersion)
      onReviewChanged()
      setState({ busy: false, error: '', message: `Approved. The button is now "${notifyLabel}".` })
    } catch (e) {
      setState({ busy: false, error: e.status === 409 ? e.message || 'The report changed. Reload and review it again.' : e.message || 'The report could not be approved.', message: '' })
    }
  }

  async function discardEdits() {
    setState({ busy: true, error: '', message: '' })
    try {
      await discardReportEdit(eventId)
      onReviewChanged()
      setState({ busy: false, error: '', message: 'Your edits were removed. The original wording is used.' })
    } catch {
      setState({ busy: false, error: 'Your edits could not be removed.', message: '' })
    }
  }

  async function notify() {
    setState({ busy: true, error: '', message: '' })
    try {
      const result = await createNewsDrafts({ eventId, alertIds: chosen.map((c) => c.alertId) })
      const skipped = result.skipped || []
      if (result.drafts.length === 0) {
        setState({ busy: false, error: `No draft was made: ${skipped.map((s) => SKIP_REASONS[s.reason] || s.reason).join(', ')}.`, message: '' })
        return
      }
      const params = new URLSearchParams({ openDraft: result.drafts[0].draftId, news: String(result.drafts.length) })
      if (skipped.length) params.set('skipped', String(skipped.length))
      navigate(`/agent/drafts?${params}`)
    } catch (e) {
      setState({ busy: false, error: e.status === 503 ? 'Follow-up Emails or event alerts are unavailable. Nothing was made.' : e.message || 'The drafts could not be made.', message: '' })
    }
  }

  return (
    <div className="report-review" aria-label="Approve and notify">
      <div className="visually-hidden" aria-live="polite">{state.message}</div>
      {valid ? (
        <>
          <p className="report-review__approved">
            Approved by you at {TIME.format(new Date(approval.approvedAt))}{approval.edited ? ' with your edits' : ''}.
          </p>
          <p className={approval.stale ? 'staleness staleness--warn' : 'staleness'}>
            News from {DATE.format(new Date(review.newsDate || report.createdAt))} · {stalenessText(approval, now)}
          </p>
          <button type="button" className="button button--accent report-review__notify" onClick={notify}
            disabled={state.busy || count === 0}>
            {state.busy ? 'Making drafts…' : notifyLabel}
          </button>
          {count === 0 && <p className="holdings-note">Select the clients in Plan client briefing to notify.</p>}
          <p className="holdings-note report-review__hint">
            Opens this client&apos;s draft in Follow-up Emails. Working through several tickers? Use &quot;Notify all ready clients&quot;
            at the top of this page to make every draft in one go.
          </p>
        </>
      ) : (
        <>
          {approval && !valid && <p className="staleness staleness--warn">Your approval expired. Review the report and approve it again.</p>}
          {review.editedSections && <p className="holdings-note">You have saved edits. They are what you approve.</p>}
          <div className="report-review__buttons">
            {review.editedSections && !editing && (
              <button type="button" className="button button--secondary" onClick={discardEdits} disabled={state.busy}>Undo my edits</button>
            )}
            <button type="button" className="button button--primary" onClick={approve} disabled={state.busy || editing || count === 0}>
              {approval ? `Approve ${company} report again` : `Approve ${company} report for ${count} selected client${count === 1 ? '' : 's'}`}
            </button>
          </div>
          {editing && <p className="holdings-note">Click Save and preview to save your edits first.</p>}
          <p className="holdings-note">
            {anyInformed
              ? 'Approval is final: you cannot edit the report afterwards. It can be sent for 7 days.'
              : 'Choose Plan client briefing for at least one client first.'}
          </p>
        </>
      )}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {state.message && <p className="success-message" role="status">{state.message}</p>}
    </div>
  )
}
