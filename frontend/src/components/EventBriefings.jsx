import { useEffect, useState } from 'react'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'

// "This happened; these clients may be affected." One card per public event with
// one shared summary. The agent picks Inform, Monitor or Dismiss for the clients
// they select. Inform records intent only: no message is sent to anyone.

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
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr><th /><th>Client</th><th>Holding</th><th className="num">Share of portfolio</th><th>Status</th></tr>
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
                  <td>{client.instrumentId}</td>
                  <td className="num">{client.portfolioPercentage == null ? '—' : `${Number(client.portfolioPercentage).toFixed(1)}%`}</td>
                  <td>{STATUS_LABELS[client.status] || client.status}</td>
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
  const [state, setState] = useState({ loading: true, error: '', items: [] })
  const [openId, setOpenId] = useState(null)
  const [onlyOpen, setOnlyOpen] = useState(true)

  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    listEvents({ onlyOpen }).then(
      (page) => { if (current) setState({ loading: false, error: '', items: page.items }) },
      (e) => { if (current) setState({ loading: false, error: problemText(e), items: [] }) },
    )
    return () => { current = false }
  }, [onlyOpen, reloadKey])

  return (
    <section className="event-briefings" aria-labelledby="event-briefings-title">
      <div className="event-briefings__head">
        <h2 id="event-briefings-title">Events that may affect your clients</h2>
        <label>
          <input type="checkbox" checked={onlyOpen} onChange={(e) => setOnlyOpen(e.target.checked)} />
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
            <p>
              <strong>{card.affectedClientCount}</strong> {card.affectedClientCount === 1 ? 'client' : 'clients'} may be affected
              {card.openClientCount < card.affectedClientCount ? ` (${card.openClientCount} still open)` : ''}.
            </p>
            <a href={card.evidence.url} target="_blank" rel="noreferrer noopener">Read the source</a>
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
    </section>
  )
}

export default EventBriefings
