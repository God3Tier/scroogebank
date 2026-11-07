import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { getEvent as getAlertEvent, listApprovedReports, recordEventAction } from '../api/alertEvents.js'
import { listClients } from '../api/clients.js'
import { saveBlob } from '../api/request.js'
import { Pager } from '../components/ListFooter.jsx'
import { useArrayPages } from '../components/paging.js'
import { draftReportApproval, SKIP_REASONS, stalenessText } from '../utils/newsReports.js'
import {
  createDraft,
  createNewsDrafts,
  DETAILS_PROMPT,
  discardDraft,
  formatWhen,
  getDraft,
  listDrafts,
  listEvents,
  previewNewsPdf,
  PURPOSE_LABELS,
  PURPOSES,
  rebuildNewsSummary,
  removeNewsReport,
  retryNewsStatus,
  sendDraft,
  SINGAPORE,
  SOURCE_REASONS,
  updateDraft,
} from '../api/engagement.js'

const STATUS_TABS = [
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'SENT', label: 'Sent' },
  { value: 'DISCARDED', label: 'Discarded' },
]
const STATUS_BADGES = {
  DRAFT: { label: 'Draft', className: 'state-badge--draft' },
  SENDING: { label: 'Sending', className: 'state-badge--sending' },
  SENT: { label: 'Sent', className: 'state-badge--sent' },
  DISCARDED: { label: 'Discarded', className: 'state-badge--discarded' },
}
const SHORT_DATE = new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', timeZone: SINGAPORE })
// A holiday's date is a calendar date (YYYY-MM-DD), so it is formatted in UTC.
const HOLIDAY_DATE = new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', timeZone: 'UTC' })
const wordCount = (text) => (text.trim() ? text.trim().split(/\s+/).length : 0)
const PAST_MEETING_DAYS = 92
// Why a report could not be marked Sent Out after the email went out (it never can be, so there is no retry).
const UNMARKED_REASONS = {
  NOT_AUTHORISED: 'the client is no longer yours',
  INVALID_STATUS: 'the client is not in Plan client briefing',
}
const NEWS_DATE = new Intl.DateTimeFormat('en-SG', { day: 'numeric', month: 'short', timeZone: SINGAPORE })
// engagement-service's largest draft list (docs/api/engagement-api.yaml).
const DRAFT_LIMIT = 100

function generateProblem(error) {
  if (error.status === 404) return 'That client is not one of yours.'
  // The service says which: a country without holiday data, or no holiday soon.
  if (error.status === 422) return error.message || "There is no public holiday in the next 30 days for this client's country. Choose another purpose."
  if (error.status === 400) return `Check the request: ${error.message}`
  if (error.status === 503) return error.message || 'Client details are unavailable right now. Try again.'
  return 'The draft could not be created. Try again.'
}

function saveProblem(error) {
  if (error.status === 409) return 'This draft was changed elsewhere. Reload it.'
  if (error.status === 404) return 'This client is no longer yours, so the draft was not saved.'
  if (error.status === 422) return error.message || 'The subject or body is too long.'
  if (error.status === 503) return 'Names could not be looked up right now, so the draft was not saved. Try again.'
  return 'The draft could not be saved.'
}

function sendProblem(error) {
  if (error.status === 409) return 'This draft was changed or already sent. Reload it and try again.'
  if (error.status === 422) return error.message || 'The subject or body is empty, or has text in double braces that cannot be filled in.'
  if (error.status === 404) return 'This client is no longer yours, so the email was not sent.'
  if (error.status === 502) return 'The email service did not accept the message. Nothing was sent; try again later.'
  if (error.status === 503) return error.message || 'Sending is unavailable right now. Nothing was sent.'
  return 'The email could not be sent. Try again.'
}

// The past meetings with one client that a follow-up can refer to.
function usePastMeetings(clientId, enabled, now) {
  const [meetings, setMeetings] = useState([])
  useEffect(() => {
    if (!enabled || !clientId) return undefined
    let current = true
    const from = new Date(now.getTime() - PAST_MEETING_DAYS * 24 * 60 * 60 * 1000)
    listEvents({ from: from.toISOString(), to: now.toISOString() })
      .then((events) => current && setMeetings(events.filter((event) => event.clientId === clientId && new Date(event.endsAt) <= now)))
      .catch(() => current && setMeetings([]))
    return () => { current = false }
  }, [clientId, enabled, now])
  return enabled && clientId ? meetings : []
}

// A link from a Client Health task or an alert fills the form in: purpose,
// client and the task or alert the follow-up comes from (ADR-0068).
function linkedValues(params) {
  const purpose = PURPOSES.find((option) => option.value === params.get('purpose'))
  const source = purpose?.source
  if (!source || !params.get(source) || !params.get('clientId')) return null
  return { purpose: purpose.value, clientId: params.get('clientId'), [source]: params.get(source) }
}

// The News Alert entry (plan 5.5): a client with approved, unsent reports in Plan client briefing, and those reports.
function NewsEntry({ clients, onCreated }) {
  const [approved, setApproved] = useState({ loading: true, error: '', items: [] })
  const [clientRef, setClientRef] = useState('')
  const [ticked, setTicked] = useState({})
  const [state, setState] = useState({ saving: false, error: '', message: '' })

  useEffect(() => {
    let current = true
    listApprovedReports().then(
      (items) => { if (current) setApproved({ loading: false, error: '', items }) },
      () => { if (current) setApproved({ loading: false, error: 'Approved reports cannot be loaded right now.', items: [] }) },
    )
    return () => { current = false }
  }, [])

  const names = Object.fromEntries(clients.map((client) => [client.client_id, `${client.first_name} ${client.last_name}`]))
  const withReports = [...new Set(approved.items.map((item) => item.clientRef))]
  const reports = approved.items.filter((item) => item.clientRef === clientRef)
  const chosen = reports.filter((item) => ticked[item.alertId] !== false)

  async function create() {
    if (chosen.length === 0) return
    setState({ saving: true, error: '', message: '' })
    const byEvent = new Map()
    chosen.forEach((item) => byEvent.set(item.eventId, [...(byEvent.get(item.eventId) || []), item.alertId]))
    let first = null
    const skipped = []
    try {
      for (const [eventId, alertIds] of byEvent) {
        const result = await createNewsDrafts({ eventId, alertIds })
        first = first || result.drafts[0]?.draftId || null
        skipped.push(...(result.skipped || []))
      }
    } catch (e) {
      setState({ saving: false, error: e.status === 503 ? 'Event alerts are unavailable. No draft was made.' : e.message || 'The draft could not be made.', message: '' })
      return
    }
    if (!first) {
      setState({ saving: false, error: `No draft was made: ${skipped.map((s) => SKIP_REASONS[s.reason] || s.reason).join(', ')}.`, message: '' })
      return
    }
    setState({ saving: false, error: '', message: skipped.length ? `${skipped.length} report${skipped.length === 1 ? ' was' : 's were'} not added.` : '' })
    onCreated({ id: first })
  }

  return (
    <div className="draft-generate__full draft-generate__context news-entry">
      <label><span className="label--required">Client</span>
        <select value={clientRef} onChange={(e) => { setClientRef(e.target.value); setTicked({}) }}>
          <option value="">{approved.loading ? 'Loading…' : 'Choose a client with approved reports'}</option>
          {withReports.map((ref) => <option key={ref} value={ref}>{names[ref] || ref.slice(0, 8)}</option>)}
        </select>
      </label>
      {reports.length > 0 && (
        <fieldset className="news-entry__reports">
          <legend>Reports to include</legend>
          {reports.map((item) => (
            <label key={item.alertId} className="news-entry__report">
              <input type="checkbox" checked={ticked[item.alertId] !== false}
                onChange={(e) => setTicked((t) => ({ ...t, [item.alertId]: e.target.checked }))} />
              <span><strong>{item.issuer}</strong>: {item.headline}<small>News from {NEWS_DATE.format(new Date(item.newsDate))}{item.edited ? ' · with your edits' : ''}</small></span>
            </label>
          ))}
        </fieldset>
      )}
      <p className="draft-generate__hint">
        Can&apos;t find the report you wanted? It must be set with a status of &apos;Plan client briefing&apos; and be approved by you.{' '}
        <a href="/agent/alerts">See all reports</a>
      </p>
      {approved.error && <p className="form-error" role="alert">{approved.error}</p>}
      {state.error && <p className="form-error" role="alert">{state.error}</p>}
      {state.message && <p className="success-message" role="status">{state.message}</p>}
      <button type="button" className="draft-generate__submit" onClick={create} disabled={state.saving || chosen.length === 0}>
        <span className="material-symbols-outlined" aria-hidden="true">attach_file</span>
        {state.saving ? 'Making draft…' : 'Create draft'}
      </button>
    </div>
  )
}

function GenerateForm({ clients, now, linked, onCreated, draftOpen = false }) {
  const [values, setValues] = useState(() => ({
    clientId: '', purpose: 'CHECK_IN', tone: 'FORMAL', eventId: '', healthTaskId: '', alertId: '', ...linked,
  }))
  const [state, setState] = useState({ saving: false, error: '' })
  const needsMeeting = values.purpose === 'MEETING_FOLLOW_UP'
  const meetings = usePastMeetings(values.clientId, needsMeeting, now)
  // A Client Health follow-up is offered only for the task it was opened from.
  const purposes = PURPOSES.filter((purpose) => !purpose.source || values[purpose.source])
  const source = PURPOSES.find((purpose) => purpose.value === values.purpose)?.source
  const news = values.purpose === 'NEWS_ALERT'

  function setField(name) {
    return (e) => setValues((current) => ({ ...current, [name]: e.target.value, ...(name === 'clientId' ? { eventId: '' } : {}) }))
  }

  async function handleSubmit(e) {
    e.preventDefault()
    if (news) return
    if (!values.clientId) {
      setState({ saving: false, error: 'Choose a client.' })
      return
    }
    if (needsMeeting && !values.eventId) {
      setState({ saving: false, error: 'Choose the meeting to follow up on.' })
      return
    }
    setState({ saving: true, error: '' })
    try {
      const draft = await createDraft({
        ...values,
        eventId: needsMeeting ? values.eventId : null,
        healthTaskId: source === 'healthTaskId' ? values.healthTaskId : null,
        alertId: source === 'alertId' ? values.alertId : null,
      })
      setState({ saving: false, error: '' })
      onCreated(draft)
    } catch (error) {
      setState({ saving: false, error: generateProblem(error) })
    }
  }

  return (
    <form className="card draft-generate" onSubmit={handleSubmit} noValidate>
      <div className="engagement-card-header engagement-card-header--ruled">
        <span className="material-symbols-outlined" aria-hidden="true">auto_fix_high</span>
        <h2>Write a follow-up email</h2>
      </div>
      <div className="draft-generate__fields">
        <label><span className="label--required">Purpose</span>
          <select value={values.purpose} onChange={setField('purpose')}>
            {purposes.map((purpose) => <option key={purpose.value} value={purpose.value}>{purpose.label}</option>)}
          </select>
        </label>
        {news ? (
          <label><span>Tone</span>
            <select value="NONE" disabled aria-describedby="news-tone-note"><option value="NONE">None</option></select>
            <small id="news-tone-note" className="draft-generate__hint">The text comes from the approved reports.</small>
          </label>
        ) : (
          <label><span className="label--required">Tone</span>
            <select value={values.tone} onChange={setField('tone')}>
              <option value="FORMAL">Formal</option>
              <option value="WARM">Warm</option>
            </select>
          </label>
        )}
        {news ? <NewsEntry clients={clients} onCreated={onCreated} /> : (
          <label className="draft-generate__full"><span className="label--required">Client</span>
            <select value={values.clientId} onChange={setField('clientId')}>
              <option value="">Choose a client</option>
              {clients.map((client) => (
                <option key={client.client_id} value={client.client_id}>{client.first_name} {client.last_name}</option>
              ))}
            </select>
          </label>
        )}
        {needsMeeting && (
          <div className="draft-generate__full draft-generate__context">
            <span className="draft-generate__hint">From your calendar</span>
            <label><span className="label--required">Meeting</span>
              <select value={values.eventId} onChange={setField('eventId')}>
                <option value="">Choose a past meeting</option>
                {meetings.map((meeting) => (
                  <option key={meeting.id} value={meeting.id}>{formatWhen(meeting.startsAt)} · {meeting.title}</option>
                ))}
              </select>
            </label>
          </div>
        )}
        {source && (
          <div className="draft-generate__full draft-generate__context">
            <span className="draft-generate__hint">From Client Health task {values.healthTaskId}</span>
            <p className="draft-generate__hint">
              The AI writes a general note. You add the details where it says {DETAILS_PROMPT}.
            </p>
          </div>
        )}
        {!news && (
          <>
            <p className="draft-generate__full engagement-info engagement-info--privacy">
              <span className="material-symbols-outlined" aria-hidden="true">verified_user</span>
              <span>The AI sees only the purpose, the tone and upcoming public holidays, never the client&apos;s name or details. You review every email before it is sent.</span>
            </p>
            {state.error && <p className="form-error draft-generate__full" role="alert">{state.error}</p>}
            <button type="submit" className="draft-generate__full draft-generate__submit" disabled={state.saving || draftOpen}
              title={draftOpen ? 'Close the open email to write a new one' : undefined}>
              <span className="material-symbols-outlined" aria-hidden="true">auto_awesome</span>
              {state.saving ? 'Writing…' : 'Write draft'}
            </button>
            {draftOpen && <p className="draft-generate__hint draft-generate__full">You are working on an email. Close it to write a new one.</p>}
          </>
        )}
      </div>
    </form>
  )
}

// The agent edits plain text with the client's and their own names written
// out; the service turns the names back into placeholders when it saves, so
// no names are stored (ADR-0056).
function DraftEditor({ draftId, clientName, now: today, onChanged, onClose }) {
  const now = today.getTime()
  const [draft, setDraft] = useState(null)
  const [values, setValues] = useState({ subject: '', body: '' })
  const [state, setState] = useState({ busy: false, error: '', message: '' })

  const load = useCallback(async () => {
    try {
      const loaded = await getDraft(draftId)
      setDraft(loaded)
      setValues({ subject: loaded.subject ?? '', body: loaded.body ?? '' })
    } catch {
      setState({ busy: false, error: 'The draft could not be loaded.', message: '' })
    }
  }, [draftId, setValues])

  useEffect(() => {
    // The draft is server-owned; fetching it is this panel's external synchronization.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load])

  if (!draft) {
    return (
      <section className="card draft-editor">
        {state.error ? <p className="form-error" role="alert">{state.error}</p> : <p className="empty-note">Loading…</p>}
      </section>
    )
  }

  const editable = draft.status === 'DRAFT'
  // A sent or discarded draft keeps no text (ADR-0068).
  const closed = draft.status === 'SENT' || draft.status === 'DISCARDED'
  // Without the names (client-service or the agent's profile unavailable) the
  // text cannot be shown or saved without placeholders.
  const named = draft.subject != null && draft.body != null
  const needsDetails = editable && named && `${values.subject}\n${values.body}`.includes(DETAILS_PROMPT)
  const changed = named && (values.subject !== draft.subject || values.body !== draft.body)
  const badge = STATUS_BADGES[draft.status]
  const news = draft.purpose === 'NEWS_ALERT' ? draft.news : null

  function showDraft(next) {
    setDraft(next)
    setValues({ subject: next.subject ?? '', body: next.body ?? '' })
  }

  async function handleRemove(report) {
    if (!window.confirm(`Take the ${report.issuer} report off this email? The client stays in Plan client briefing.`)) return
    setState({ busy: true, error: '', message: '' })
    try {
      const next = await removeNewsReport(draft.id, report.alertId)
      onChanged()
      if (next.status === 'DISCARDED') {
        onClose()
        return
      }
      showDraft(next)
      setState({ busy: false, error: '', message: `${report.issuer} was taken off.` })
    } catch (error) {
      setState({ busy: false, error: error.message || 'The report could not be taken off.', message: '' })
    }
  }

  async function handleRebuild(keep) {
    setState({ busy: true, error: '', message: '' })
    try {
      showDraft(await rebuildNewsSummary(draft.id, { keep }))
      setState({ busy: false, error: '', message: keep ? 'Your text is kept.' : 'The summary was built again from the reports.' })
      onChanged()
    } catch (error) {
      setState({ busy: false, error: error.message || 'The summary could not be rebuilt.', message: '' })
    }
  }

  async function handlePreview() {
    setState({ busy: true, error: '', message: '' })
    try {
      const file = await previewNewsPdf(draft.id)
      const url = URL.createObjectURL(file.blob)
      if (!window.open(url, '_blank', 'noopener')) saveBlob(file)
      setState({ busy: false, error: '', message: '' })
    } catch (error) {
      setState({ busy: false, error: error.message || 'The PDF could not be made right now.', message: '' })
    }
  }

  async function handleRetry() {
    setState({ busy: true, error: '', message: '' })
    try {
      showDraft(await retryNewsStatus(draft.id))
      setState({ busy: false, error: '', message: 'The clients are now Sent Out.' })
    } catch (error) {
      setState({ busy: false, error: error.message || 'The status could not be updated. Try again later.', message: '' })
    }
  }

  async function handleSave() {
    setState({ busy: true, error: '', message: '' })
    try {
      await updateDraft(draft.id, { ...values, version: draft.version })
      await load()
      setState({ busy: false, error: '', message: 'Saved.' })
      onChanged()
    } catch (error) {
      setState({ busy: false, error: saveProblem(error), message: '' })
    }
  }

  async function handleSend() {
    const question = news
      ? `Send this email to ${clientName} with ${news.reports.length} report${news.reports.length === 1 ? '' : 's'} in the attached PDF?`
      : `Send this email to ${clientName}?`
    if (!window.confirm(question)) return
    setState({ busy: true, error: '', message: '' })
    // Unsaved edits are saved first, so the email sent is the one on screen.
    let version = draft.version
    if (changed) {
      try {
        const saved = await updateDraft(draft.id, { ...values, version })
        setDraft(saved)
        setValues({ subject: saved.subject ?? '', body: saved.body ?? '' })
        version = saved.version
        onChanged()
      } catch (error) {
        setState({ busy: false, error: saveProblem(error), message: '' })
        return
      }
    }
    try {
      const sent = await sendDraft(draft.id, version)
      setDraft(sent)
      setState({ busy: false, error: '', message: 'Sent.' })
      onChanged()
    } catch (error) {
      setState({ busy: false, error: sendProblem(error), message: '' })
    }
  }

  async function handleDiscard() {
    if (!window.confirm('Discard this draft?')) return
    setState({ busy: true, error: '', message: '' })
    try {
      await discardDraft(draft.id)
      onChanged()
      onClose()
    } catch {
      setState({ busy: false, error: 'The draft could not be discarded.', message: '' })
    }
  }

  return (
    <section className="card card-flush draft-editor" aria-labelledby="draft-editor-heading">
      <header className="draft-editor__header">
        <div className="draft-editor__title">
          <h2 id="draft-editor-heading">Email to {clientName}</h2>
          {badge && <span className={`state-badge ${badge.className}`}>{badge.label}</span>}
        </div>
        <p className="draft-editor__source">
          <span className={`material-symbols-outlined draft-editor__source-icon${draft.source === 'AI' ? ' draft-editor__source-icon--ai' : ''}`} aria-hidden="true">
            {draft.source === 'AI' ? 'smart_toy' : 'article'}
          </span>
          {news ? (
            <span>News Alert: built from the reports you approved</span>
          ) : draft.source === 'AI' ? (
            <span>AI-generated: review before sending</span>
          ) : (
            <span>Template{draft.sourceReason && SOURCE_REASONS[draft.sourceReason] ? `, because ${SOURCE_REASONS[draft.sourceReason]}` : ''}</span>
          )}
          {draft.holiday && (
            <>
              <span className="draft-editor__dot" aria-hidden="true">·</span>
              <span className="draft-editor__holiday">
                Holiday used: {draft.holiday.name} ({HOLIDAY_DATE.format(new Date(`${draft.holiday.date}T00:00:00Z`))})
              </span>
            </>
          )}
        </p>
      </header>
      <div className="draft-editor__body">
        <dl className="draft-editor__meta">
          <dt>To:</dt>
          <dd>the client&apos;s email on file</dd>
          <dt>Reply-To:</dt>
          <dd>your work email</dd>
          <dt>Bcc:</dt>
          <dd>you, as your copy</dd>
          {news && (
            <>
              <dt>Purpose:</dt>
              <dd>News Alert (fixed)</dd>
              <dt>Tone:</dt>
              <dd>None (fixed)</dd>
              <dt>Attached:</dt>
              <dd>one PDF with {news.reports.length} report{news.reports.length === 1 ? '' : 's'}</dd>
            </>
          )}
        </dl>
        {news && (
          <section className="news-reports" aria-labelledby="news-reports-heading">
            <div className="news-reports__head">
              <h3 id="news-reports-heading">Reports in the PDF</h3>
              {editable && <button type="button" onClick={handlePreview} disabled={state.busy}>Preview PDF</button>}
            </div>
            <ul>
              {news.reports.map((report) => {
                const approval = draftReportApproval(report, now)
                return (
                  <li key={report.alertId}>
                    <span><strong>{report.issuer}</strong>: {report.headline}</span>
                    <small className={approval.stale && editable ? 'staleness staleness--warn' : 'staleness'}>
                      News from {NEWS_DATE.format(new Date(report.newsDate))}{editable ? ` · ${stalenessText(approval, now)}` : ''}
                    </small>
                    {editable && (
                      <button type="button" className="button--danger" onClick={() => handleRemove(report)} disabled={state.busy}
                        aria-label={`Remove the ${report.issuer} report`}>Remove</button>
                    )}
                  </li>
                )
              })}
            </ul>
            {editable && news.rebuildSuggested && (
              <div className="engagement-hint news-reports__rebuild" role="status">
                <span>The reports on this draft changed. Rebuild summary?</span>
                <button type="button" onClick={() => handleRebuild(false)} disabled={state.busy}>Rebuild summary</button>
                <button type="button" onClick={() => handleRebuild(true)} disabled={state.busy}>Keep my text</button>
              </div>
            )}
            {draft.status === 'SENT' && (news.unmarkedReports || []).length > 0 && (
              <div className="engagement-hint" role="status">
                <span>
                  The email was sent, but these reports could not be marked Sent Out:{' '}
                  {news.unmarkedReports.map((report) => `${report.issuer} (${UNMARKED_REASONS[report.reason] || report.reason})`).join('; ')}.
                </span>
              </div>
            )}
            {draft.status === 'SENT' && !news.sentOutSynced && (
              <div className="engagement-hint" role="status">
                <span>The email was sent, but the clients could not be marked Sent Out.</span>
                <button type="button" onClick={handleRetry} disabled={state.busy}>Retry status update</button>
              </div>
            )}
          </section>
        )}
        {closed ? (
          <p className="engagement-hint" role="status">
            <span className="material-symbols-outlined" aria-hidden="true">inventory_2</span>
            <span>
              {draft.status === 'SENT'
                ? "The text isn't kept once an email is sent. Your copy is in your mailbox."
                : "The text of a discarded email isn't kept."}
            </span>
          </p>
        ) : named ? (
          <div className="stack-form">
            <label>Subject
              <input type="text" maxLength={120} value={values.subject} disabled={!editable || state.busy}
                onChange={(e) => setValues((v) => ({ ...v, subject: e.target.value }))} />
            </label>
            <label>
              <span className="draft-editor__body-label">
                Body
                <span className="draft-editor__count" aria-hidden="true">{wordCount(values.body)} words</span>
              </span>
              <textarea rows={12} maxLength={5000} value={values.body} disabled={!editable || state.busy}
                onChange={(e) => setValues((v) => ({ ...v, body: e.target.value }))} />
            </label>
          </div>
        ) : (
          <p className="engagement-hint" role="status">
            <span className="material-symbols-outlined" aria-hidden="true">info</span>
            <span>The client&apos;s or your name could not be looked up, so this email cannot be shown right now. Try again later.</span>
          </p>
        )}
        {news && editable && (
          <div className="news-fixed" aria-label="Added when the email is sent">
            <p className="news-fixed__label">Added from the PDF. Updates automatically.</p>
            <pre>{news.guide}</pre>
            <p className="news-fixed__label">Added to every email. You cannot change this.</p>
            <pre>{news.fixedText}</pre>
          </div>
        )}
        {needsDetails && (
          <p className="engagement-hint" role="status">
            <span className="material-symbols-outlined" aria-hidden="true">edit_note</span>
            <span>Replace {DETAILS_PROMPT} with your reason for getting in touch, or delete it.</span>
          </p>
        )}
        {editable && named && (
          <p className="engagement-hint">
            <span className="material-symbols-outlined" aria-hidden="true">person_check</span>
            <span>Anything you add is sent as written and is not checked. You are responsible for it.</span>
          </p>
        )}
        {draft.status === 'SENT' && <p className="success-message" role="status">Sent {formatWhen(draft.sentAt)}.</p>}
        {state.error && <p className="form-error" role="alert">{state.error}</p>}
        {state.message && (draft.status !== 'SENT' || news) && <p className="success-message" role="status">{state.message}</p>}
      </div>
      <div className="draft-editor__actions">
        {editable && (
          <p className="draft-editor__warning">
            <span className="material-symbols-outlined" aria-hidden="true">lock</span>
            You will be asked to confirm before sending.
          </p>
        )}
        <div className="draft-editor__buttons">
          <button type="button" onClick={onClose} disabled={state.busy}>Close</button>
          {editable && <button type="button" className="button--danger" onClick={handleDiscard} disabled={state.busy}>Discard</button>}
          {editable && named && <button type="button" onClick={handleSave} disabled={state.busy || !changed}>Save changes</button>}
          {editable && named && (
            <button type="button" className="button--accent" onClick={handleSend} disabled={state.busy}
              title={changed ? 'Saves your changes, then sends' : undefined}>
              Send
              <span className="material-symbols-outlined" aria-hidden="true">send</span>
            </button>
          )}
        </div>
      </div>
    </section>
  )
}

// Follow-up emails: the agent asks for a draft, edits it and sends it
// (ADR-0054). Nothing is sent without the agent pressing Send.
// Layout: Stitch screen "Follow-up Emails" (Scrooge Global Bank CRM).
function AgentDrafts({ now: fixedNow }) {
  // One instant per visit, so the past-meetings lookup does not repeat.
  const [now] = useState(() => fixedNow ?? new Date())
  const [searchParams, setSearchParams] = useSearchParams()
  const [linked] = useState(() => linkedValues(searchParams))
  const [clients, setClients] = useState([])
  const [status, setStatus] = useState('DRAFT')
  const [list, setList] = useState({ loading: true, error: '', drafts: [] })
  // "Notify selected clients about <company>" on Event Alerts opens the client's News Alert draft here.
  const [openId, setOpenId] = useState(() => searchParams.get('openDraft'))
  const [notice] = useState(() => {
    const made = Number(searchParams.get('news') || 0)
    const skipped = Number(searchParams.get('skipped') || 0)
    if (!made) return ''
    return `${made} News Alert draft${made === 1 ? ' was' : 's were'} made or updated.${skipped ? ` ${skipped} report${skipped === 1 ? ' was' : 's were'} not added.` : ''}`
  })
  const [version, setVersion] = useState(0)
  const [picked, setPicked] = useState({})
  const [bulk, setBulk] = useState({ busy: false, error: '', message: '', failed: {} })
  const pages = useArrayPages(list.drafts)

  useEffect(() => {
    // The link has done its job; a reload should not reopen it.
    if (searchParams.get('openDraft')) setSearchParams({}, { replace: true })
  }, [searchParams, setSearchParams])

  useEffect(() => {
    let current = true
    listClients({ size: 100 })
      .then((page) => current && setClients(page.content ?? []))
      .catch(() => current && setClients([]))
    return () => { current = false }
  }, [])

  const load = useCallback(async () => {
    try {
      // engagement-service sends the newest first, at most DRAFT_LIMIT, unpaged.
      setList({ loading: false, error: '', drafts: await listDrafts({ status, limit: DRAFT_LIMIT }) })
    } catch {
      setList({ loading: false, error: 'Follow-up emails are temporarily unavailable.', drafts: [] })
    }
  }, [status])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load, version])

  const names = useMemo(() => Object.fromEntries(clients.map((client) => [client.client_id, `${client.first_name} ${client.last_name}`])), [clients])
  const nameOf = (clientId) => names[clientId] || 'your client'

  const open = list.drafts.find((draft) => draft.id === openId)
  const deletable = status === 'DRAFT' ? list.drafts.filter((draft) => draft.status === 'DRAFT') : []
  const pickedIds = deletable.filter((draft) => picked[draft.id]).map((draft) => draft.id)
  const allPicked = deletable.length > 0 && pickedIds.length === deletable.length

  // Deletes the selected drafts one by one; a failure is reported against its row and the others still go (plan 5.7).
  async function deleteSelected() {
    if (pickedIds.length === 0) return
    if (!window.confirm(`Delete ${pickedIds.length} selected draft${pickedIds.length === 1 ? '' : 's'}?`)) return
    setBulk({ busy: true, error: '', message: '', failed: {} })
    const failed = {}
    const deletedNews = []
    for (const id of pickedIds) {
      const draft = list.drafts.find((d) => d.id === id)
      try {
        await discardDraft(id)
        if (draft?.purpose === 'NEWS_ALERT') deletedNews.push(draft)
      } catch {
        failed[id] = 'Could not be deleted'
      }
    }
    setPicked({})
    if (openId && pickedIds.includes(openId) && !failed[openId]) setOpenId(null)
    let message = `${pickedIds.length - Object.keys(failed).length} deleted.`
    if (deletedNews.length > 0 && window.confirm(
      `Do you also want to dismiss the related reports for the ${deletedNews.length} deleted draft${deletedNews.length === 1 ? '' : 's'}?`)) {
      message += ` ${await dismissReports(deletedNews)}`
    }
    setBulk({ busy: false, error: Object.keys(failed).length ? 'Some drafts could not be deleted.' : '', message, failed })
    setVersion((v) => v + 1)
  }

  // Sets every report on the deleted news drafts to Dismiss for that client. Sent Out or Dismissed ones are skipped.
  async function dismissReports(drafts) {
    const byEvent = new Map()
    drafts.flatMap((draft) => draft.news?.reports || []).forEach((report) => {
      byEvent.set(report.eventId, [...(byEvent.get(report.eventId) || []), report.alertId])
    })
    let dismissed = 0
    let skipped = 0
    for (const [eventId, alertIds] of byEvent) {
      try {
        const event = await getAlertEvent(eventId)
        const members = event.clients
          .filter((client) => alertIds.includes(client.alertId) && (client.status === 'NEW' || client.status === 'INFORMED'))
          .map((client) => ({ alertId: client.alertId, expectedVersion: client.version }))
        skipped += alertIds.length - members.length
        if (members.length > 0) {
          const result = await recordEventAction(eventId, { decision: 'DISMISS', note: null, members })
          dismissed += result.members.filter((member) => member.outcome === 'APPLIED').length
        }
      } catch {
        skipped += alertIds.length
      }
    }
    return `${dismissed} report${dismissed === 1 ? '' : 's'} dismissed.${skipped ? ` ${skipped} skipped because they were already Sent Out or Dismissed, or could not be checked.` : ''}`
  }

  return (
    <div className="page engagement-page">
      <div className="back-row"><Link to="/agent/alerts">Back to Event Alerts</Link></div>
      <header className="page-header page-header--ruled">
        <h1>Follow-up Emails</h1>
        <p className="page-subtitle">AI helps write the first draft. Nothing is sent until you press Send.</p>
      </header>
      {notice && <p className="success-message" role="status">{notice}</p>}

      <div className="drafts-layout">
        <div className="drafts-layout__side">
          <GenerateForm clients={clients} now={now} linked={linked} draftOpen={Boolean(openId)} onCreated={(draft) => {
            // The link has done its job; a reload should not offer the same follow-up again.
            if (linked) setSearchParams({}, { replace: true })
            setStatus('DRAFT'); pages.go(0); setOpenId(draft.id); setVersion((v) => v + 1)
          }} />

          <section className="card card-flush drafts-list" aria-labelledby="drafts-heading">
            <div className="drafts-list__header">
              <h2 id="drafts-heading">Your emails</h2>
              <div className="drafts-tabs" role="tablist">
                {STATUS_TABS.map((tab) => (
                  <button key={tab.value} type="button" role="tab" aria-selected={status === tab.value}
                    onClick={() => { setStatus(tab.value); pages.go(0) }}>
                    {tab.label}
                  </button>
                ))}
              </div>
            </div>
            {list.error && <p className="form-error drafts-list__note" role="alert">{list.error}</p>}
            {deletable.length > 0 && (
              <div className="drafts-bulk">
                <label>
                  <input type="checkbox" checked={allPicked} aria-label="Select all drafts"
                    onChange={(e) => setPicked(e.target.checked ? Object.fromEntries(deletable.map((d) => [d.id, true])) : {})} />
                  {' '}Select all
                </label>
                {pickedIds.length > 0 && (
                  <button type="button" className="button--danger" onClick={deleteSelected} disabled={bulk.busy}>
                    {bulk.busy ? 'Deleting…' : `Delete selected (${pickedIds.length})`}
                  </button>
                )}
              </div>
            )}
            {bulk.error && <p className="form-error drafts-list__note" role="alert">{bulk.error}</p>}
            {bulk.message && <p className="success-message drafts-list__note" role="status">{bulk.message}</p>}
            {!list.loading && !list.error && list.drafts.length === 0 && <p className="empty-note drafts-list__note">Nothing here yet.</p>}
            {list.drafts.length > 0 && (
              <div className="table-wrap">
                <table className="data-table drafts-table">
                  <thead>
                    <tr>
                      {status === 'DRAFT' && <th scope="col"><span className="visually-hidden">Select</span></th>}
                      <th scope="col">Client</th>
                      <th scope="col">Purpose</th>
                      <th scope="col">Source</th>
                      <th scope="col">Updated</th>
                      <th scope="col" className="drafts-table__action">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pages.rows.map((draft) => (
                      <tr key={draft.id} className={draft.id === openId ? 'drafts-table__row--open' : undefined}>
                        {status === 'DRAFT' && (
                          <td>
                            <input type="checkbox" aria-label={`Select the draft to ${nameOf(draft.clientId)}`}
                              disabled={draft.status !== 'DRAFT'} checked={Boolean(picked[draft.id])}
                              onChange={(e) => setPicked((p) => ({ ...p, [draft.id]: e.target.checked }))} />
                          </td>
                        )}
                        <td>
                          <span className="drafts-table__client">{nameOf(draft.clientId)}</span>
                          {bulk.failed[draft.id] && <span className="form-error"> {bulk.failed[draft.id]}</span>}
                        </td>
                        <td className="drafts-table__purpose">{PURPOSE_LABELS[draft.purpose] ?? draft.purpose}</td>
                        <td>
                          <span className={`source-badge${draft.source === 'AI' ? ' source-badge--ai' : ''}${draft.source === 'NEWS' ? ' source-badge--news' : ''}`}>
                            {draft.source === 'AI' ? 'AI' : draft.source === 'NEWS' ? 'News' : 'Template'}
                          </span>
                        </td>
                        <td><time dateTime={draft.updatedAt ?? draft.createdAt} title={formatWhen(draft.updatedAt ?? draft.createdAt)}>{SHORT_DATE.format(new Date(draft.updatedAt ?? draft.createdAt))}</time></td>
                        <td className="drafts-table__action">
                          <button type="button" onClick={() => setOpenId(draft.id)}>Open</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {list.drafts.length > 0 && (
              <>
                <Pager list={pages} label="Email pages" />
                <p className="drafts-list__footer">
                  {list.drafts.length === DRAFT_LIMIT
                    ? `Showing your newest ${DRAFT_LIMIT} emails`
                    : `${list.drafts.length} ${list.drafts.length === 1 ? 'email' : 'emails'}`}
                </p>
              </>
            )}
          </section>
        </div>

        <div className="drafts-layout__main">
          {openId ? (
            <DraftEditor
              key={openId}
              draftId={openId}
              clientName={nameOf(open?.clientId)}
              now={now}
              onChanged={() => setVersion((v) => v + 1)}
              onClose={() => setOpenId(null)}
            />
          ) : (
            <section className="card drafts-empty">
              <span className="material-symbols-outlined" aria-hidden="true">drafts</span>
              <p>Open an email from the list, or write a new one.</p>
            </section>
          )}
        </div>
      </div>
    </div>
  )
}

export default AgentDrafts
