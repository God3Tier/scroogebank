import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'
import { followUpLink, isCrmClientId } from '../api/engagement.js'

// "This happened; these clients may be affected." One card per public event with
// one shared summary. The agent picks Inform, Monitor or Dismiss for the clients
// they select. Inform records intent only: no message is sent to anyone. Once
// it is planned, the agent can draft the email in Follow-up Emails, where the AI
// sees nothing from the alert (ADR-0068).

const ACTIONS = [
  { value: 'INFORM_CLIENT', label: 'Inform clients', help: 'Records that you plan to inform them. No message is sent.' },
  { value: 'MONITOR', label: 'Monitor', help: 'Keep these clients on watch for follow-up.' },
  { value: 'DISMISS', label: 'Dismiss', help: 'Close this for the selected clients without contacting them.' },
]

const STATUS_LABELS = { NEW: 'New', MONITORING: 'Monitoring', INFORMED: 'Inform planned', DISMISSED: 'Dismissed' }

const OUTCOME_LABELS = {
  APPLIED: 'Done',
  STALE_VERSION: 'Changed since you looked — reload',
  INVALID_TRANSITION: 'Already closed',
  NOT_AUTHORISED: 'No longer yours',
}

function problemText(error) {
  if (error.status === 404) return 'This event is not available to you.'
  if (error.status === 503) return 'Client records cannot be verified right now. Try again shortly.'
  return error.message || 'Something went wrong. Try again.'
}

function formatDate(value) {
  if (!value) return 'Not supplied'
  return new Intl.DateTimeFormat('en-SG', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore' }).format(new Date(value))
}

function ReportPanel({ report }) {
  if (!report) return null
  const body = report.report
  const cited = (list) => (list || []).map((item) => item.text)
  return (
    <section className="event-report" aria-label="Event report">
      <h3>{body.headline}</h3>
      <p className="holdings-note">Version {report.version} of {report.versionCount}. {report.schemaVersion === 'briefing-v1-legacy' ? 'Migrated from an earlier briefing.' : 'AI-written from public sources; checked before it is shown.'}</p>
      {cited(body.executiveSummary).map((text) => <p key={text}>{text}</p>)}
      {(body.keyFacts || []).length > 0 && (
        <>
          <h4>Key facts</h4>
          <ul>{body.keyFacts.map((fact) => <li key={fact.label}><strong>{fact.label}:</strong> {fact.value}. {fact.context}</li>)}</ul>
        </>
      )}
      {(body.whyItMatters || []).length > 0 && (
        <>
          <h4>Why it matters</h4>
          <ul>{body.whyItMatters.map((item) => <li key={item.text}>{item.kind === 'POTENTIAL_IMPLICATION' ? `Potential implication: ${item.text}` : item.text}</li>)}</ul>
        </>
      )}
      {(body.whatToMonitor || []).length > 0 && (
        <>
          <h4>What to monitor</h4>
          <ul>{body.whatToMonitor.map((item) => <li key={item}>{item}</li>)}</ul>
        </>
      )}
      {body.uncertainty && <p><strong>Uncertainty:</strong> {body.uncertainty}</p>}
      <h4>Sources</h4>
      <ul>
        {report.sources.map((source) => (
          <li key={source.url}>
            <a href={source.url} target="_blank" rel="noreferrer">{source.title}</a> ({source.domain}){source.usedInReport ? '' : ' - linked, not used in the summary'}
          </li>
        ))}
      </ul>
      {Object.values(report.portfolioLines || {}).map((line) => <p key={line}>{line}</p>)}
      <p className="holdings-note">{report.disclaimer}</p>
    </section>
  )
}

function EventDetailPanel({ eventId, onChanged }) {
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState({})
  const [decision, setDecision] = useState('MONITOR')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)

  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    getEvent(eventId).then(
      (next) => {
        if (!current) return
        setDetail(next)
        setSelected({})
        setError('')
      },
      (e) => { if (current) setError(problemText(e)) },
    )
    return () => { current = false }
  }, [eventId, reloadKey])

  async function submit(event) {
    event.preventDefault()
    const members = detail.clients
      .filter((client) => selected[client.alertId])
      .map((client) => ({ alertId: client.alertId, expectedVersion: client.version }))
    if (members.length === 0) return
    setBusy(true)
    try {
      setResult(await recordEventAction(eventId, { decision, note, members }))
      setReloadKey((key) => key + 1)
      onChanged()
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  if (error && !detail) return <p className="error-text" role="alert">{error}</p>
  if (!detail) return <p className="empty-note" role="status">Loading clients…</p>

  const open = detail.clients.filter((c) => c.status === 'NEW' || c.status === 'MONITORING')
  return (
    <form className="event-clients" onSubmit={submit} aria-label="Affected clients">
      {error && <p className="error-text" role="alert">{error}</p>}
      <ReportPanel report={detail.report} />
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr><th /><th>Client</th><th>Scoring account</th><th>Holding</th><th className="num">Share of portfolio</th><th>Status</th><th>Follow-up</th></tr>
          </thead>
          <tbody>
            {detail.clients.map((client) => {
              const actionable = client.status === 'NEW' || client.status === 'MONITORING'
              return (
                <tr key={client.alertId}>
                  <td>
                    <input type="checkbox" aria-label={`Select client ${client.clientRef}`} disabled={!actionable}
                      checked={Boolean(selected[client.alertId])}
                      onChange={(e) => setSelected((s) => ({ ...s, [client.alertId]: e.target.checked }))} />
                  </td>
                  <td className="mono">{client.clientRef.slice(0, 8)}</td>
                  <td className="mono" title={client.accountRef}>{client.accountRef?.slice(0, 8) || '—'}</td>
                  <td>{client.instrumentId}</td>
                  <td className="num">{client.portfolioPercentage == null ? '—' : `${Number(client.portfolioPercentage).toFixed(1)}%`}</td>
                  <td>{STATUS_LABELS[client.status] || client.status}</td>
                  <td>
                    {client.status === 'INFORMED' && isCrmClientId(client.clientRef) ? (
                      <Link className="follow-up-link follow-up-link--compact"
                        aria-label={`Draft email to client ${client.clientRef.slice(0, 8)}`}
                        to={followUpLink({ purpose: 'ALERT_FOLLOW_UP', clientId: client.clientRef, alertId: client.alertId })}>
                        <span className="material-symbols-outlined" aria-hidden="true">mail</span> Draft email
                      </Link>
                    ) : '—'}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {open.length > 0 && (
        <>
          <fieldset className="event-actions">
            <legend>What would you like to do for the selected clients?</legend>
            {ACTIONS.map((action) => (
              <label key={action.value} title={action.help}>
                <input type="radio" name={`decision-${eventId}`} value={action.value}
                  checked={decision === action.value} onChange={() => setDecision(action.value)} />
                {action.label}
              </label>
            ))}
            <p className="holdings-note">{ACTIONS.find((a) => a.value === decision).help}</p>
          </fieldset>
          <label>Note (optional)
            <input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button type="submit" disabled={busy || Object.values(selected).every((v) => !v)}>
            {busy ? 'Saving…' : 'Apply to selected clients'}
          </button>
        </>
      )}
      {result && (
        <ul className="event-outcomes" role="status">
          {result.members.map((member) => (
            <li key={member.alertId}>{member.alertId.slice(0, 8)}: {OUTCOME_LABELS[member.outcome] || member.outcome}</li>
          ))}
        </ul>
      )}
    </form>
  )
}

function EventBriefings() {
  const [state, setState] = useState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
  const [openId, setOpenId] = useState(null)
  const [onlyOpen, setOnlyOpen] = useState(true)

  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    listEvents({ onlyOpen }).then(
      (page) => { if (current) setState({ loading: false, error: '', items: page.items, nextCursor: page.nextCursor, loadingMore: false }) },
      (e) => { if (current) setState({ loading: false, error: problemText(e), items: [], nextCursor: null, loadingMore: false }) },
    )
    return () => { current = false }
  }, [onlyOpen, reloadKey])

  async function loadMore() {
    if (!state.nextCursor || state.loadingMore) return
    const cursor = state.nextCursor
    setState((current) => ({ ...current, loadingMore: true, error: '' }))
    try {
      const page = await listEvents({ onlyOpen, cursor })
      setState((current) => current.nextCursor === cursor
        ? { ...current, items: [...current.items, ...page.items], nextCursor: page.nextCursor, loadingMore: false }
        : current)
    } catch (e) {
      setState((current) => current.nextCursor === cursor
        ? { ...current, error: problemText(e), loadingMore: false }
        : current)
    }
  }

  return (
    <section className="event-briefings" aria-labelledby="event-briefings-title">
      <div className="event-briefings__head">
        <h2 id="event-briefings-title">Events that may affect your clients</h2>
        <label>
          <input type="checkbox" checked={onlyOpen} onChange={(e) => {
            setOnlyOpen(e.target.checked)
            setState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
            setOpenId(null)
          }} />
          Only events needing a decision
        </label>
      </div>
      {state.loading && <p className="empty-note" role="status">Loading events…</p>}
      {state.error && <p className="error-text" role="alert">{state.error}</p>}
      {!state.loading && !state.error && state.items.length === 0 && (
        <p className="empty-state">No events need your attention.</p>
      )}
      <ul className="event-cards">
        {state.items.map((card) => (
          <li key={card.eventId} className="card event-card">
            <h3>{card.headline}</h3>
            <p className="event-card__meta">
              {card.evidence.sourceDomain} · {formatDate(card.evidence.publishedAt || card.evidence.retrievedAt)}
            </p>
            {card.summary && <p>{card.summary}</p>}
            {card.summary && card.aiGenerated && <p className="holdings-note">AI-generated summary. {card.uncertainty}</p>}
            <p><a href={card.evidence.url} target="_blank" rel="noreferrer noopener">Source: {card.evidence.sourceDomain}</a></p>
            <p>
              <strong>{card.affectedClientCount}</strong> {card.affectedClientCount === 1 ? 'client' : 'clients'} may be affected
              {card.openClientCount < card.affectedClientCount ? ` (${card.openClientCount} still open)` : ''}.
            </p>
            <div>
              <button type="button" aria-expanded={openId === card.eventId}
                onClick={() => setOpenId(openId === card.eventId ? null : card.eventId)}>
                {openId === card.eventId ? 'Hide clients' : 'Review affected clients'}
              </button>
            </div>
            {openId === card.eventId && <EventDetailPanel eventId={card.eventId} onChanged={() => setReloadKey((key) => key + 1)} />}
          </li>
        ))}
      </ul>
      {!state.loading && state.nextCursor && (
        <button type="button" onClick={loadMore} disabled={state.loadingMore}>
          {state.loadingMore ? 'Loading…' : 'Load more events'}
        </button>
      )}
    </section>
  )
}

export default EventBriefings
