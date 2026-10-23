import { useCallback, useEffect, useMemo, useState } from 'react'

import { listClients } from '../api/clients.js'
import {
  createDraft,
  discardDraft,
  formatWhen,
  getDraft,
  listDrafts,
  listEvents,
  PURPOSES,
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
const PURPOSE_LABELS = Object.fromEntries(PURPOSES.map((purpose) => [purpose.value, purpose.label]))
const PAST_MEETING_DAYS = 92

function generateProblem(error) {
  if (error.status === 404) return 'That client is not one of yours.'
  // The service says which: a country without holiday data, or no holiday soon.
  if (error.status === 422) return error.message || "There is no public holiday in the next 30 days for this client's country. Choose another purpose."
  if (error.status === 400) return `Check the request: ${error.message}`
  if (error.status === 503) return 'Client details are unavailable right now. Try again.'
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

function GenerateForm({ clients, now, onCreated }) {
  const [values, setValues] = useState({ clientId: '', purpose: 'CHECK_IN', tone: 'FORMAL', eventId: '' })
  const [state, setState] = useState({ saving: false, error: '' })
  const needsMeeting = values.purpose === 'MEETING_FOLLOW_UP'
  const meetings = usePastMeetings(values.clientId, needsMeeting, now)

  function setField(name) {
    return (e) => setValues((current) => ({ ...current, [name]: e.target.value, ...(name === 'clientId' ? { eventId: '' } : {}) }))
  }

  async function handleSubmit(e) {
    e.preventDefault()
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
      const draft = await createDraft({ ...values, eventId: needsMeeting ? values.eventId : null })
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
        <label className="draft-generate__full"><span className="label--required">Client</span>
          <select value={values.clientId} onChange={setField('clientId')}>
            <option value="">Choose a client</option>
            {clients.map((client) => (
              <option key={client.client_id} value={client.client_id}>{client.first_name} {client.last_name}</option>
            ))}
          </select>
        </label>
        <label><span className="label--required">Purpose</span>
          <select value={values.purpose} onChange={setField('purpose')}>
            {PURPOSES.map((purpose) => <option key={purpose.value} value={purpose.value}>{purpose.label}</option>)}
          </select>
        </label>
        <label><span className="label--required">Tone</span>
          <select value={values.tone} onChange={setField('tone')}>
            <option value="FORMAL">Formal</option>
            <option value="WARM">Warm</option>
          </select>
        </label>
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
        <p className="draft-generate__full engagement-info engagement-info--privacy">
          <span className="material-symbols-outlined" aria-hidden="true">verified_user</span>
          <span>The AI sees only the purpose, the tone and upcoming public holidays, never the client&apos;s name or details. You review every email before it is sent.</span>
        </p>
        {state.error && <p className="form-error draft-generate__full" role="alert">{state.error}</p>}
        <button type="submit" className="draft-generate__full draft-generate__submit" disabled={state.saving}>
          <span className="material-symbols-outlined" aria-hidden="true">auto_awesome</span>
          {state.saving ? 'Writing…' : 'Write draft'}
        </button>
      </div>
    </form>
  )
}

// The agent edits plain text with the client's and their own names written
// out; the service turns the names back into placeholders when it saves, so
// no names are stored (ADR-0056).
function DraftEditor({ draftId, clientName, onChanged, onClose }) {
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
  }, [draftId])

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
  // Without the names (client-service or the agent's profile unavailable) the
  // text cannot be shown or saved without placeholders.
  const named = draft.subject != null && draft.body != null
  const changed = named && (values.subject !== draft.subject || values.body !== draft.body)
  const badge = STATUS_BADGES[draft.status]

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
    if (!window.confirm(`Send this email to ${clientName}?`)) return
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
          {draft.source === 'AI' ? (
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
        </dl>
        {named ? (
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
        {draft.status === 'SENT' && <p className="success-message" role="status">Sent {formatWhen(draft.sentAt)}.</p>}
        {state.error && <p className="form-error" role="alert">{state.error}</p>}
        {state.message && draft.status !== 'SENT' && <p className="success-message" role="status">{state.message}</p>}
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
  const [clients, setClients] = useState([])
  const [status, setStatus] = useState('DRAFT')
  const [list, setList] = useState({ loading: true, error: '', drafts: [] })
  const [openId, setOpenId] = useState(null)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let current = true
    listClients({ size: 100 })
      .then((page) => current && setClients(page.content ?? []))
      .catch(() => current && setClients([]))
    return () => { current = false }
  }, [])

  const load = useCallback(async () => {
    try {
      setList({ loading: false, error: '', drafts: await listDrafts({ status }) })
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

  return (
    <div className="page engagement-page">
      <header className="page-header page-header--ruled">
        <h1>Follow-up Emails</h1>
        <p className="page-subtitle">AI helps write the first draft. Nothing is sent until you press Send.</p>
      </header>

      <div className="drafts-layout">
        <div className="drafts-layout__side">
          <GenerateForm clients={clients} now={now} onCreated={(draft) => { setStatus('DRAFT'); setOpenId(draft.id); setVersion((v) => v + 1) }} />

          <section className="card card-flush drafts-list" aria-labelledby="drafts-heading">
            <div className="drafts-list__header">
              <h2 id="drafts-heading">Your emails</h2>
              <div className="drafts-tabs" role="tablist">
                {STATUS_TABS.map((tab) => (
                  <button key={tab.value} type="button" role="tab" aria-selected={status === tab.value}
                    onClick={() => setStatus(tab.value)}>
                    {tab.label}
                  </button>
                ))}
              </div>
            </div>
            {list.error && <p className="form-error drafts-list__note" role="alert">{list.error}</p>}
            {!list.loading && !list.error && list.drafts.length === 0 && <p className="empty-note drafts-list__note">Nothing here yet.</p>}
            {list.drafts.length > 0 && (
              <div className="table-wrap">
                <table className="data-table drafts-table">
                  <thead>
                    <tr>
                      <th scope="col">Client</th>
                      <th scope="col">Purpose</th>
                      <th scope="col">Source</th>
                      <th scope="col">Updated</th>
                      <th scope="col" className="drafts-table__action">Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.drafts.map((draft) => (
                      <tr key={draft.id} className={draft.id === openId ? 'drafts-table__row--open' : undefined}>
                        <td>
                          <span className="drafts-table__client">{nameOf(draft.clientId)}</span>
                        </td>
                        <td className="drafts-table__purpose">{PURPOSE_LABELS[draft.purpose] ?? draft.purpose}</td>
                        <td>
                          <span className={`source-badge${draft.source === 'AI' ? ' source-badge--ai' : ''}`}>
                            {draft.source === 'AI' ? 'AI' : 'Template'}
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
              <p className="drafts-list__footer">
                Showing {list.drafts.length} {list.drafts.length === 1 ? 'email' : 'emails'}
              </p>
            )}
          </section>
        </div>

        <div className="drafts-layout__main">
          {openId ? (
            <DraftEditor
              key={openId}
              draftId={openId}
              clientName={nameOf(open?.clientId)}
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
