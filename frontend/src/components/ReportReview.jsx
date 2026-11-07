import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { approveReport, discardReportEdit, saveReportEdit } from '../api/alertEvents.js'
import { createNewsDrafts, isCrmClientId } from '../api/engagement.js'
import { stalenessText } from '../utils/newsReports.js'

// Edit, approve, then notify (news-alert-drafts plan 5.2). The agent may change the report's wording; the headline
// and sources stay fixed, and the edited text is checked again. Approval is final and freezes what the client gets.
// After approval the approve button is replaced by "Notify selected clients of news", which puts the report on each
// selected client's News Alert draft in Follow-up Emails. Nothing here sends an email.

const SECTION_NAMES = {
  executiveSummary: 'Executive summary',
  keyFacts: 'Key facts',
  whyItMatters: 'Why it matters',
  whatToMonitor: 'What to monitor',
  uncertainty: 'Uncertainty',
}

const SKIP_REASONS = {
  NOT_AUTHORISED: 'no longer one of your clients',
  NOT_INFORMED: 'not in Plan client briefing',
  NOT_APPROVED: 'the approval is missing or expired',
  ALREADY_ON_DRAFT: 'already on a news draft',
  LIMIT_REACHED: 'their draft is full',
}

const TIME = new Intl.DateTimeFormat('en-SG', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore' })
const DATE = new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', timeZone: 'Asia/Singapore' })

function Editor({ eventId, version, start, onSaved, onCancel }) {
  const [sections, setSections] = useState(() => structuredClone(start))
  const [state, setState] = useState({ saving: false, error: '' })

  const setItem = (section, index, value) => setSections((s) => ({ ...s, [section]: s[section].map((v, i) => (i === index ? value : v)) }))
  const setFact = (index, field, value) => setSections((s) => ({ ...s, keyFacts: s.keyFacts.map((f, i) => (i === index ? { ...f, [field]: value } : f)) }))

  async function save(event) {
    event.preventDefault()
    setState({ saving: true, error: '' })
    try {
      onSaved(await saveReportEdit(eventId, version, sections))
    } catch (e) {
      if (e.status === 422) {
        const where = e.problem?.section ? `${SECTION_NAMES[e.problem.section] || e.problem.section}${e.problem.index >= 0 ? `, item ${e.problem.index + 1}` : ''}: ` : ''
        setState({ saving: false, error: `${where}${e.message || 'This edit did not pass the checks.'} Nothing was saved.` })
      } else if (e.status === 409) {
        setState({ saving: false, error: e.message || 'The report changed or was approved. Reload it.' })
      } else {
        setState({ saving: false, error: 'The edit could not be saved. Try again.' })
      }
    }
  }

  return (
    <form className="report-editor" onSubmit={save} aria-label="Edit the report">
      <p className="holdings-note">
        Change the wording only. The headline and sources stay as they are. Your edit is checked again: a figure you add
        must appear in the sources, and advice wording is refused. Other agents still see the original.
      </p>
      <fieldset>
        <legend>{SECTION_NAMES.executiveSummary}</legend>
        {sections.executiveSummary.map((text, i) => (
          <label key={`s${i}`}>Sentence {i + 1}
            <textarea rows={3} maxLength={600} value={text} onChange={(e) => setItem('executiveSummary', i, e.target.value)} />
          </label>
        ))}
      </fieldset>
      {sections.keyFacts.length > 0 && (
        <fieldset>
          <legend>{SECTION_NAMES.keyFacts}</legend>
          {sections.keyFacts.map((fact, i) => (
            <div key={`f${i}`} className="report-editor__fact">
              <label>Label<input maxLength={100} value={fact.label} onChange={(e) => setFact(i, 'label', e.target.value)} /></label>
              <label>Value<input maxLength={200} value={fact.value} onChange={(e) => setFact(i, 'value', e.target.value)} /></label>
              <label>Context<textarea rows={2} maxLength={600} value={fact.context} onChange={(e) => setFact(i, 'context', e.target.value)} /></label>
            </div>
          ))}
        </fieldset>
      )}
      <fieldset>
        <legend>{SECTION_NAMES.whyItMatters}</legend>
        {sections.whyItMatters.map((text, i) => (
          <label key={`w${i}`}>Point {i + 1}
            <textarea rows={2} maxLength={600} value={text} onChange={(e) => setItem('whyItMatters', i, e.target.value)} />
          </label>
        ))}
      </fieldset>
      <fieldset>
        <legend>{SECTION_NAMES.whatToMonitor}</legend>
        {sections.whatToMonitor.map((text, i) => (
          <label key={`m${i}`}>Item {i + 1}
            <textarea rows={2} maxLength={300} value={text} onChange={(e) => setItem('whatToMonitor', i, e.target.value)} />
          </label>
        ))}
      </fieldset>
      <fieldset>
        <legend>{SECTION_NAMES.uncertainty}</legend>
        <label>Uncertainty
          <textarea rows={2} maxLength={500} value={sections.uncertainty}
            onChange={(e) => setSections((s) => ({ ...s, uncertainty: e.target.value }))} />
        </label>
      </fieldset>
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      <div className="report-editor__actions">
        <button type="button" className="button button--secondary" onClick={onCancel} disabled={state.saving}>Cancel</button>
        <button type="submit" className="button button--primary" disabled={state.saving}>{state.saving ? 'Checking…' : 'Save edits'}</button>
      </div>
    </form>
  )
}

/**
 * clients: the agent's clients on this report; selected: { alertId: true }. Approval and Notify act on the selected
 * clients in Plan client briefing.
 */
export function ReportReview({ eventId, report, review, clients, selected, onReviewChanged }) {
  const navigate = useNavigate()
  const [now] = useState(() => Date.now())
  const [editing, setEditing] = useState(false)
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
      setState({ busy: false, error: '', message: 'Approved. The button is now "Notify selected clients of news".' })
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

  if (editing) {
    return (
      <Editor eventId={eventId} version={review.reportVersion}
        start={review.editedSections || review.originalSections}
        onSaved={() => { setEditing(false); onReviewChanged(); setState({ busy: false, error: '', message: 'Edits saved. They are checked again when you approve.' }) }}
        onCancel={() => setEditing(false)} />
    )
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
            {state.busy ? 'Making drafts…' : 'Notify selected clients of news'}
          </button>
          {count === 0 && <p className="holdings-note">Select the clients in Plan client briefing to notify.</p>}
        </>
      ) : (
        <>
          {approval && !valid && <p className="staleness staleness--warn">Your approval expired. Review the report and approve it again.</p>}
          {review.editedSections && <p className="holdings-note">You have saved edits. They are what you approve.</p>}
          <div className="report-review__buttons">
            {review.editable && (
              <button type="button" className="button button--secondary" onClick={() => setEditing(true)} disabled={state.busy}>
                {review.editedSections ? 'Edit again' : 'Edit report'}
              </button>
            )}
            {review.editedSections && (
              <button type="button" className="button button--secondary" onClick={discardEdits} disabled={state.busy}>Undo my edits</button>
            )}
            <button type="button" className="button button--primary" onClick={approve} disabled={state.busy || count === 0}>
              {approval ? 'Approve again' : `Approve report for ${count} selected client${count === 1 ? '' : 's'}`}
            </button>
          </div>
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
