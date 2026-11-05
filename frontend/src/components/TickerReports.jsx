import { useCallback, useEffect, useState } from 'react'

import { getEvent, listEvents, recordEventAction } from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { isCrmClientId, sendAlertReport } from '../api/engagement.js'
import { CombinedSources, EventReport } from './EventReport.jsx'

// Event alerts for an agent, by ticker (ADR-0084). Each row on the left is one ticker with the report written from
// its newest articles; the right shows that report, the sources it combines, and the agent's clients who hold the
// stock. The agent decides per client (Inform, Monitor, Dismiss), then sends the report to the clients marked Inform.

function Icon({ name, className = '' }) {
  return <span className={`material-symbols-outlined ${className}`.trim()} aria-hidden="true">{name}</span>
}

const STATUS_LABELS = { NEW: 'New', MONITORING: 'Monitoring', INFORMED: 'Inform planned', DISMISSED: 'Dismissed' }
const DELIVERY_LABELS = { QUEUED: 'Queued', SENT: 'Sent', FAILED: 'Failed', NOT_AUTHORISED: 'Not authorised' }
const OUTCOME_LABELS = {
  APPLIED: 'Done',
  STALE_VERSION: 'Changed since you looked, reload',
  INVALID_TRANSITION: 'Already closed',
  NOT_AUTHORISED: 'No longer yours',
}
const ACTIONS = [
  { value: 'INFORM_CLIENT', label: 'Plan client briefing', help: 'Records that you plan to inform them. No message is sent yet.' },
  { value: 'MONITOR', label: 'Continue monitoring', help: 'Keep these clients on watch for follow-up.' },
  { value: 'DISMISS', label: 'Dismiss', help: 'Close this for the selected clients without contacting them.' },
]
const MAX_CONTINUES = 5

const actionable = (client) => client.status === 'NEW' || client.status === 'MONITORING'

function formatDate(value) {
  if (!value) return 'Not supplied'
  return new Intl.DateTimeFormat('en-SG', {
    day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Singapore',
  }).format(new Date(value))
}

function problemText(error) {
  if (error.status === 404) return 'This is not available to you.'
  if (error.status === 503) return 'Client records cannot be verified right now. Try again shortly.'
  return error.message || 'Something went wrong. Try again.'
}

function clientLabel(client, names) {
  return names[client.clientRef] || `${client.clientRef.slice(0, 8)}`
}

function TickerDetail({ eventId, onChanged }) {
  const [detail, setDetail] = useState(null)
  const [error, setError] = useState('')
  const [names, setNames] = useState({})
  const [selected, setSelected] = useState({})
  const [decision, setDecision] = useState('MONITOR')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [agentNote, setAgentNote] = useState('')
  const [approved, setApproved] = useState(false)
  const [sendState, setSendState] = useState({ sending: false, error: '', deliveries: null })
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let current = true
    getEvent(eventId).then(
      (next) => {
        if (!current) return
        setDetail(next)
        setError('')
      },
      (e) => { if (current) setError(problemText(e)) },
    )
    return () => { current = false }
  }, [eventId, reloadKey])

  // Names come from the CRM, in the browser; the alerts service never sees them.
  useEffect(() => {
    if (!detail) return undefined
    let current = true
    detail.clients.filter((c) => isCrmClientId(c.clientRef)).forEach((c) => {
      getClient(c.clientRef).then(
        (client) => {
          const name = [client.first_name, client.last_name].filter(Boolean).join(' ')
          if (current && name) setNames((existing) => ({ ...existing, [c.clientRef]: name }))
        },
        () => { /* keep the ID */ },
      )
    })
    return () => { current = false }
  }, [detail])

  async function submitDecision(event) {
    event.preventDefault()
    const members = detail.clients
      .filter((client) => selected[client.alertId] && actionable(client))
      .map((client) => ({ alertId: client.alertId, expectedVersion: client.version }))
    if (members.length === 0) return
    setBusy(true)
    try {
      setResult(await recordEventAction(eventId, { decision, note, members }))
      setSelected({})
      setNote('')
      setReloadKey((key) => key + 1)
      onChanged()
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  async function sendReport() {
    const alertIds = detail.clients
      .filter((client) => selected[client.alertId] && client.status === 'INFORMED' && isCrmClientId(client.clientRef))
      .map((client) => client.alertId)
    if (alertIds.length === 0 || !approved) return
    setSendState({ sending: true, error: '', deliveries: null })
    const key = crypto.randomUUID()
    const request = { eventId, expectedReportVersion: detail.report.version, alertIds, agentNote: agentNote.trim() }
    try {
      let outcome = await sendAlertReport(request, key)
      // Recipients not reached within one request stay QUEUED; the same request carries on with them.
      for (let round = 0; round < MAX_CONTINUES && outcome.recipients.some((r) => r.status === 'QUEUED'); round += 1) {
        outcome = await sendAlertReport(request, key)
      }
      setSendState({ sending: false, error: '', deliveries: outcome.recipients })
      setApproved(false)
    } catch (e) {
      setSendState({
        sending: false,
        deliveries: null,
        error: e.status === 409 ? 'The report changed since you looked. Reload and review it again.'
          : e.status === 503 ? 'Sending is switched off or a service is unavailable. Nothing more was sent.'
            : problemText(e),
      })
    }
  }

  if (error && !detail) return <p className="error-text" role="alert">{error}</p>
  if (!detail) return <p className="empty-note" role="status">Loading…</p>

  const { card, report, clients } = detail
  const open = clients.filter(actionable)
  const informed = clients.filter((c) => c.status === 'INFORMED')
  const sendable = clients.filter((c) => selected[c.alertId] && c.status === 'INFORMED' && isCrmClientId(c.clientRef))
  const heading = card.issuer ? `${card.issuer} (${card.ticker})` : card.headline

  return (
    <>
      <header className="detail-header">
        <div className="detail-header__meta">
          <span>{card.ticker || card.instrumentId}</span>
          <span>{report ? `Report version ${report.version}` : 'Report not written yet'}</span>
          <span>{card.sourceCount} source{card.sourceCount === 1 ? '' : 's'}</span>
        </div>
        <h2>{heading}</h2>
        <div className="detail-header__foot">
          <span><Icon name="schedule" /> {formatDate(card.latestAlertAt)} SGT</span>
          <span className="score score--large"><strong>{Number(card.topScore).toFixed(1)}</strong>/100 top client score</span>
          <span>{card.affectedClientCount} client{card.affectedClientCount === 1 ? '' : 's'} affected</span>
        </div>
      </header>

      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="detail-columns">
        <section className="detail-section" aria-labelledby="ticker-report-title">
          <div className="section-heading">
            <div><Icon name="auto_awesome" /><h3 id="ticker-report-title">Report</h3></div>
            <span>Decision support</span>
          </div>
          {report ? <EventReport report={report} /> : (
            <div className="inline-empty"><Icon name="autorenew" /> The report for this ticker is still being written. {card.summary}</div>
          )}
        </section>
        <section className="detail-section" aria-labelledby="ticker-why-title">
          <div className="section-heading">
            <div><Icon name="find_in_page" /><h3 id="ticker-why-title">Why this appeared</h3></div>
          </div>
          <ul className="ticker-why">
            <li>{clients.length} of your client{clients.length === 1 ? '' : 's'} hold {card.issuer || 'this company'}.</li>
            <li>The highest client score is {Number(card.topScore).toFixed(1)} out of 100.</li>
            <li>The report combines what was newly found for this ticker in the latest search run.</li>
          </ul>
          <CombinedSources report={report} evidence={[]} />
        </section>
      </div>

      <section className="detail-section" aria-labelledby="ticker-clients-title">
        <div className="section-heading">
          <div><Icon name="groups" /><h3 id="ticker-clients-title">Affected clients</h3></div>
          <span>{open.length} open · {informed.length} marked Inform</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th /><th>Client</th><th className="num">Share of portfolio</th><th className="num">Exposure (SGD)</th><th className="num">Score</th><th>Status</th></tr>
            </thead>
            <tbody>
              {clients.map((client) => (
                <tr key={client.alertId}>
                  <td>
                    <input type="checkbox" aria-label={`Select ${clientLabel(client, names)}`}
                      disabled={client.status === 'DISMISSED'}
                      checked={Boolean(selected[client.alertId])}
                      onChange={(e) => setSelected((s) => ({ ...s, [client.alertId]: e.target.checked }))} />
                  </td>
                  <td title={client.clientRef}>{clientLabel(client, names)}</td>
                  <td className="num">{client.portfolioPercentage == null ? '—' : `${Number(client.portfolioPercentage).toFixed(1)}%`}</td>
                  <td className="num">{Number(client.amountSgd).toLocaleString('en-SG', { minimumFractionDigits: 2 })}</td>
                  <td className="num">{Number(client.score).toFixed(1)}</td>
                  <td>{STATUS_LABELS[client.status] || client.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {open.length > 0 && (
          <form className="decision-section" onSubmit={submitDecision} aria-label="Decide for the selected clients">
            <fieldset className="decision-options">
              <legend>What would you like to do for the selected clients?</legend>
              {ACTIONS.map((action) => (
                <label key={action.value} className={decision === action.value ? 'decision-option decision-option--selected' : 'decision-option'}>
                  <input type="radio" name={`decision-${eventId}`} value={action.value}
                    checked={decision === action.value} onChange={() => setDecision(action.value)} />
                  <span><strong>{action.label}</strong><small>{action.help}</small></span>
                </label>
              ))}
            </fieldset>
            <label className="decision-note">
              <span>Note <small>Optional · maximum 500 characters</small></span>
              <textarea maxLength={500} rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
            </label>
            <div className="decision-submit">
              <p>Applies to the selected clients that are still New or Monitoring.</p>
              <button className="button button--primary" type="submit"
                disabled={busy || !clients.some((c) => selected[c.alertId] && actionable(c))}>
                {busy ? 'Saving…' : 'Apply to selected clients'}
              </button>
            </div>
          </form>
        )}
        {result && (
          <ul className="event-outcomes" role="status">
            {result.members.map((member) => {
              const client = clients.find((c) => c.alertId === member.alertId)
              return <li key={member.alertId}>{client ? clientLabel(client, names) : member.alertId.slice(0, 8)}: {OUTCOME_LABELS[member.outcome] || member.outcome}</li>
            })}
          </ul>
        )}

        {report && informed.length > 0 && (
          <div className="send-report">
            <h4>Email this report to clients</h4>
            <p className="holdings-note">
              Tick the clients marked Inform, add an optional note, confirm you have reviewed the report, and send. Each client
              gets their own email. The report text cannot be edited; your note is sent under its own label.
            </p>
            <label>Note to the selected clients (optional)
              <textarea maxLength={500} rows={2} value={agentNote} onChange={(e) => setAgentNote(e.target.value)} />
            </label>
            <label className="send-report__confirm">
              <input type="checkbox" checked={approved} onChange={(e) => setApproved(e.target.checked)} />
              I have reviewed version {report.version} of this report and approve sending it
            </label>
            <button className="button button--primary" type="button" onClick={sendReport}
              disabled={sendState.sending || !approved || sendable.length === 0}>
              {sendState.sending ? 'Sending…' : `Send report to ${sendable.length} selected client${sendable.length === 1 ? '' : 's'}`}
            </button>
            {sendState.error && <p className="error-text" role="alert">{sendState.error}</p>}
            {sendState.deliveries && (
              <ul role="status">
                {sendState.deliveries.map((d) => {
                  const client = clients.find((c) => c.alertId === d.alertId)
                  return <li key={d.alertId}>{client ? clientLabel(client, names) : d.alertId.slice(0, 8)}: {DELIVERY_LABELS[d.status] || d.status}{d.errorCode ? ` (${d.errorCode})` : ''}</li>
                })}
              </ul>
            )}
          </div>
        )}
      </section>
    </>
  )
}

function TickerReports() {
  const [onlyOpen, setOnlyOpen] = useState(false)
  const [state, setState] = useState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
  const [selectedId, setSelectedId] = useState(null)

  const load = useCallback(async () => {
    try {
      const page = await listEvents({ onlyOpen })
      setState({ loading: false, error: '', items: page.items, nextCursor: page.nextCursor, loadingMore: false })
      setSelectedId((current) => (page.items.some((item) => item.eventId === current) ? current : page.items[0]?.eventId || null))
    } catch (e) {
      setState({ loading: false, error: problemText(e), items: [], nextCursor: null, loadingMore: false })
    }
  }, [onlyOpen])

  useEffect(() => {
    // The list is server-owned; fetching it is the external synchronization for this view.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load])

  async function loadMore() {
    if (!state.nextCursor || state.loadingMore) return
    const cursor = state.nextCursor
    setState((current) => ({ ...current, loadingMore: true, error: '' }))
    try {
      const page = await listEvents({ onlyOpen, cursor })
      setState((current) => (current.nextCursor === cursor
        ? { ...current, items: [...current.items, ...page.items], nextCursor: page.nextCursor, loadingMore: false }
        : current))
    } catch (e) {
      setState((current) => ({ ...current, error: problemText(e), loadingMore: false }))
    }
  }

  const needDecision = state.items.filter((item) => item.openClientCount > 0).length

  return (
    <div className="alerts-page" id="main-content">
      <header className="alerts-heading">
        <div>
          <h1>Event alerts</h1>
          <p>One report per ticker for what was newly found in each search run, with the clients it may affect.</p>
        </div>
        <div className="queue-summary" aria-label={`${needDecision} tickers need a decision`}>
          <Icon name="notifications_active" />
          <span><strong>{needDecision}</strong> need a decision</span>
        </div>
      </header>

      <section className="alerts-workspace" aria-label="Tickers and their reports">
        <aside className="alert-queue" aria-label="Tickers">
          <div className="queue-toolbar">
            <div className="queue-toolbar__title"><Icon name="filter_list" /><span>Tickers</span></div>
            <button className="icon-button" type="button" onClick={() => load()} aria-label="Refresh tickers"><Icon name="refresh" /></button>
          </div>
          <div className="queue-filters">
            <label>
              <input type="checkbox" checked={onlyOpen} onChange={(e) => {
                setOnlyOpen(e.target.checked)
                setState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
              }} />
              {' '}Only tickers needing a decision
            </label>
          </div>
          {state.error && <div className="queue-error" role="alert">{state.error}</div>}
          {state.loading && <div className="queue-loading" role="status"><Icon name="autorenew" /> Loading tickers…</div>}
          {!state.loading && !state.error && state.items.length === 0 && (
            <div className="queue-empty"><h2>No tickers need review</h2><p>New reports will appear here after the next search run.</p></div>
          )}
          <ol className="alert-list">
            {state.items.map((item) => (
              <li key={item.eventId}>
                <button type="button" className={item.eventId === selectedId ? 'alert-row alert-row--selected' : 'alert-row'}
                  aria-current={item.eventId === selectedId ? 'true' : undefined}
                  onClick={() => setSelectedId(item.eventId)}>
                  <span className="signal-marker" aria-hidden="true" />
                  <span className="alert-row__time">{formatDate(item.latestAlertAt)}</span>
                  <span className="alert-row__headline">{item.issuer ? `${item.issuer} (${item.ticker})` : item.headline}</span>
                  <span className="alert-row__meta">
                    <span className="score"><strong>{Number(item.topScore).toFixed(1)}</strong>/100</span>
                    <span>{item.affectedClientCount} client{item.affectedClientCount === 1 ? '' : 's'} · {item.openClientCount} open</span>
                  </span>
                  <span className="alert-row__client">{item.headline}</span>
                  <Icon name="chevron_right" className="alert-row__chevron" />
                </button>
              </li>
            ))}
          </ol>
          {!state.loading && state.nextCursor && (
            <button className="load-more" type="button" onClick={loadMore} disabled={state.loadingMore}>
              {state.loadingMore ? 'Loading…' : 'Load more tickers'}
            </button>
          )}
        </aside>

        <article className="alert-detail" aria-live="polite">
          {selectedId
            ? <TickerDetail key={selectedId} eventId={selectedId} onChanged={() => load()} />
            : <div className="detail-empty"><Icon name="find_in_page" /><h2>Select a ticker to review</h2><p>Its report, sources and affected clients will open here.</p></div>}
        </article>
      </section>
    </div>
  )
}

export default TickerReports
