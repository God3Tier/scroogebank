import { useCallback, useEffect, useRef, useState } from 'react'

import { getEvent, getReportReview, listApprovedReports, listEvents, recordEventAction } from '../api/alertEvents.js'
import { getClient } from '../api/clients.js'
import { isCrmClientId, listOpenNewsAlerts } from '../api/engagement.js'
import { mergeSections, noReportText, severityText } from '../utils/newsReports.js'
import { useReportEditor } from '../utils/useReportEditor.js'
import { BatchNotify, FloatingBatchHint } from './BatchNotify.jsx'
import ClientsTable from './ClientsTable.jsx'
import { CombinedSources, EventReport } from './EventReport.jsx'
import { NewsFreshnessNotice, ReportReview } from './ReportReview.jsx'
import SentOutList from './SentOutList.jsx'

// Event alerts for an agent, by ticker (ADR-0084, news-alert-drafts plan). Each row on the left is one ticker with
// the report written from its newest articles; the right shows that report, its sources and the agent's clients who
// hold the stock. The agent chooses Plan client briefing or Dismiss per client, may edit the report, approves it,
// then notifies the chosen clients: that makes News Alert drafts in Follow-up Emails. Nothing here sends an email.

function Icon({ name, className = '' }) {
  return <span className={`material-symbols-outlined ${className}`.trim()} aria-hidden="true">{name}</span>
}

const STATUS_LABELS = {
  NEW: 'New', MONITORING: 'Monitoring (old option)', INFORMED: 'Plan client briefing', DISMISSED: 'Dismissed', SENT_OUT: 'Sent Out',
}
const OUTCOME_LABELS = {
  APPLIED: 'Done',
  STALE_VERSION: 'Changed since you looked, reload',
  INVALID_TRANSITION: 'Already sent out',
  NOT_AUTHORISED: 'No longer yours',
}
const ACTIONS = [
  { value: 'INFORM_CLIENT', label: 'Plan client briefing', help: 'Records that you plan to brief them, then takes you to review and approve the report. No message is sent yet.' },
  { value: 'DISMISS', label: 'Dismiss', help: 'Close this for the selected clients without contacting them. You can change it back until a report is sent.' },
]
export const ON_DRAFT_MESSAGE = 'This client is on an open news draft. Remove the report from that draft in Follow-up Emails first.'

// Every client except Sent Out can still be chosen: the agent can change their mind until the email is sent.
const selectable = (client) => client.status !== 'SENT_OUT'

// How often the page looks for new reports by itself, only while it is open and visible.
export const POLL_MS = 20_000
const pageVisible = () => typeof document === 'undefined' || document.visibilityState !== 'hidden'

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

/** The newest publication date among the report's sources, or null. */
function newestSourceDate(report) {
  const dates = (report?.sources || []).map((source) => source.publishedAt).filter(Boolean).sort()
  return dates.length > 0 ? dates[dates.length - 1] : null
}

function clientLabel(client, names) {
  return names[client.clientRef] || `${client.clientRef.slice(0, 8)}`
}

// selected: { alertId: true } for the ticked clients, kept by the page so the ticks survive switching ticker.
// readOnly (an administrator): the same view, with no ticks, decisions, approval or notifying.
function TickerDetail({ eventId, selected, setSelected, onChanged, readOnly = false }) {
  const [detail, setDetail] = useState(null)
  const [review, setReview] = useState(null)
  const [error, setError] = useState('')
  const [names, setNames] = useState({})
  const [decision, setDecision] = useState('INFORM_CLIENT')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState(null)
  const [announce, setAnnounce] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const reportPanel = useRef(null)
  const reload = () => setReloadKey((key) => key + 1)
  const editor = useReportEditor({ eventId, review, onSaved: reload })

  // Until the report is written, look again every few seconds so it appears by itself.
  const waiting = Boolean(detail) && !detail.report
  useEffect(() => {
    if (!waiting) return undefined
    const timer = setInterval(() => { if (pageVisible()) setReloadKey((key) => key + 1) }, POLL_MS)
    return () => clearInterval(timer)
  }, [waiting])

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
    // The edits and approvals belong to one agent, so an administrator is shown the report as it was written.
    if (!readOnly) {
      getReportReview(eventId).then(
        (next) => { if (current) setReview(next) },
        () => { if (current) setReview(null) },
      )
    }
    return () => { current = false }
  }, [eventId, reloadKey, readOnly])

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
    const chosen = detail.clients.filter((client) => selected[client.alertId] && selectable(client))
    if (chosen.length === 0) return
    setBusy(true)
    setError('')
    try {
      if (decision === 'DISMISS') {
        // A client on an open news draft is dismissed only from Follow-up Emails, so the two pages agree (plan 5.1).
        let open
        try {
          open = new Set((await listOpenNewsAlerts()).alertIds)
        } catch {
          setError('Open news drafts could not be checked, so nothing was dismissed. Try again.')
          return
        }
        const blocked = chosen.filter((client) => open.has(client.alertId))
        if (blocked.length > 0) {
          setError(`${blocked.map((client) => clientLabel(client, names)).join(', ')}: ${ON_DRAFT_MESSAGE}`)
          return
        }
      }
      const members = chosen.map((client) => ({ alertId: client.alertId, expectedVersion: client.version }))
      setResult(await recordEventAction(eventId, { decision, note, members }))
      setNote('')
      setReloadKey((key) => key + 1)
      onChanged()
      if (decision === 'INFORM_CLIENT') {
        // The same clients stay selected; the agent goes on to review and approve the report.
        setAnnounce('Saved. Review and approve the report next.')
        reportPanel.current?.focus()
      } else {
        setSelected((s) => Object.fromEntries(Object.entries(s).filter(([id]) => !chosen.some((client) => client.alertId === id))))
        setAnnounce('Saved.')
      }
    } catch (e) {
      setError(problemText(e))
    } finally {
      setBusy(false)
    }
  }

  if (error && !detail) return <p className="error-text" role="alert">{error}</p>
  if (!detail) return <p className="empty-note" role="status">Loading…</p>

  const { card, report, severity } = detail
  // Sent Out clients are listed in the Sent out tab, not here.
  const clients = detail.clients.filter((client) => client.status !== 'SENT_OUT')
  const open = clients.filter((c) => c.status === 'NEW')
  const informed = clients.filter((c) => c.status === 'INFORMED')
  const heading = card.issuer ? `${card.issuer} (${card.ticker})` : card.headline
  const shownSections = review?.approval?.valid ? review.approvedSections : review?.editedSections
  const shownBody = report && shownSections ? mergeSections(report.report, shownSections) : null
  const shownLabel = review?.approval?.valid
    ? `Approved version ${review.approval.version}${review.approval.edited ? ', with your edits' : ''}`
    : review?.editedSections ? 'Your edited version, not approved yet' : null

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

      <div className="visually-hidden" aria-live="polite">{announce}</div>
      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="detail-columns">
        <section className="detail-section" aria-labelledby="ticker-report-title" ref={reportPanel} tabIndex={-1}>
          <div className="section-heading">
            <div><Icon name="auto_awesome" /><h3 id="ticker-report-title">Report</h3></div>
            <span>{readOnly ? 'Read only' : 'Review, edit and approve'}</span>
          </div>
          {report ? (
            <>
              <NewsFreshnessNotice report={report} review={review || { newsDate: newestSourceDate(report) || card.latestAlertAt, approval: null }} />
              <EventReport report={report} eventId={eventId} body={shownBody} label={shownLabel} editor={editor} />
              {!readOnly && (
                <ReportReview eventId={eventId} report={report} review={review} clients={clients} selected={selected}
                  company={card.issuer || card.ticker}
                  editing={editor.editing} onReviewChanged={() => { reload(); onChanged() }} />
              )}
            </>
          ) : (
            <div className="inline-empty">
              <Icon name="autorenew" /> {noReportText()}
            </div>
          )}
        </section>
        <section className="detail-section" aria-labelledby="ticker-why-title">
          <div className="section-heading">
            <div><Icon name="find_in_page" /><h3 id="ticker-why-title">Why this appeared</h3></div>
          </div>
          <ul className="ticker-why">
            <li>{clients.length} {readOnly ? '' : 'of your '}client{clients.length === 1 ? '' : 's'} hold{readOnly && clients.length === 1 ? 's' : ''} {card.issuer || 'this company'}.</li>
            <li>The highest client score is {Number(card.topScore).toFixed(1)} out of 100.</li>
            {severity && <li>{severityText(severity)}</li>}
            <li>The report combines what was newly found for this ticker in the latest search run.</li>
          </ul>
          <CombinedSources report={report} evidence={[]} />
        </section>
      </div>

      <section className="detail-section" aria-labelledby="ticker-clients-title">
        <div className="section-heading">
          <div><Icon name="groups" /><h3 id="ticker-clients-title">Affected clients</h3></div>
          <span>{open.length} new · {informed.length} in Plan client briefing</span>
        </div>
        <ClientsTable clients={clients} names={names} selected={selected} setSelected={setSelected}
          selectable={selectable} statusLabels={STATUS_LABELS} clientLabel={clientLabel} readOnly={readOnly} />

        {!readOnly && clients.length > 0 && (
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
              <p>Applies to the selected clients that are not Sent Out.</p>
              <button className="button button--primary" type="submit"
                disabled={busy || !clients.some((c) => selected[c.alertId] && selectable(c))}>
                {busy ? 'Saving…' : 'Apply to selected clients'}
              </button>
            </div>
          </form>
        )}
        {result && (() => {
          const notDone = result.members.filter((member) => member.outcome !== 'APPLIED')
          const done = result.members.length - notDone.length
          return (
            <div className="event-outcomes" role="status">
              {notDone.length === 0 ? (
                <p className="event-outcomes__done"><Icon name="check_circle" /> <strong>All done</strong> for {done} client{done === 1 ? '' : 's'}.</p>
              ) : (
                <>
                  <p className="event-outcomes__done"><Icon name="check_circle" /> <strong>{done} done.</strong> {notDone.length} could not be changed:</p>
                  <ul>
                    {notDone.map((member) => {
                      const client = detail.clients.find((c) => c.alertId === member.alertId)
                      return <li key={member.alertId}>{client ? clientLabel(client, names) : member.alertId.slice(0, 8)}: {OUTCOME_LABELS[member.outcome] || member.outcome}</li>
                    })}
                  </ul>
                </>
              )}
            </div>
          )
        })()}
      </section>
    </>
  )
}

function TickerReports({ readOnly = false }) {
  const [view, setView] = useState('open')
  const [selected, setSelected] = useState({})
  const [ready, setReady] = useState([])
  const panelRef = useRef(null)
  const [onlyOpen, setOnlyOpen] = useState(false)
  const [state, setState] = useState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
  const [selectedId, setSelectedId] = useState(null)

  // quiet: a background check. It keeps the pages and selection on screen, adds new tickers at the top, updates the
  // ones it knows, and says nothing if it fails.
  const load = useCallback(async (quiet = false) => {
    try {
      const page = await listEvents({ onlyOpen })
      if (quiet) {
        setState((current) => {
          const fresh = new Map(page.items.map((item) => [item.eventId, item]))
          const known = new Set(current.items.map((item) => item.eventId))
          const added = page.items.filter((item) => !known.has(item.eventId))
          return { ...current, items: [...added, ...current.items.map((item) => fresh.get(item.eventId) || item)] }
        })
        setSelectedId((current) => current || page.items[0]?.eventId || null)
        return
      }
      setState({ loading: false, error: '', items: page.items, nextCursor: page.nextCursor, loadingMore: false })
      setSelectedId((current) => (page.items.some((item) => item.eventId === current) ? current : page.items[0]?.eventId || null))
    } catch (e) {
      if (!quiet) setState({ loading: false, error: problemText(e), items: [], nextCursor: null, loadingMore: false })
    }
  }, [onlyOpen])

  // The agent's approved reports for clients in Plan client briefing: what the batch buttons would make drafts for.
  const loadReady = useCallback(async () => {
    if (readOnly) return
    try {
      setReady(await listApprovedReports())
    } catch {
      setReady([])
    }
  }, [readOnly])

  useEffect(() => {
    // The list is server-owned; fetching it is the external synchronization for this view.
    void load()
    void loadReady()
  }, [load, loadReady])

  const refresh = useCallback(() => {
    void load()
    void loadReady()
  }, [load, loadReady])

  // New tickers and reports show up by themselves, while the page is open and visible.
  useEffect(() => {
    if (view !== 'open') return undefined
    const timer = setInterval(() => {
      if (!pageVisible()) return
      void load(true)
      void loadReady()
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [view, load, loadReady])

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
        {!readOnly && (
          <div className="queue-summary" aria-label={`${needDecision} tickers need a decision`}>
            <Icon name="notifications_active" />
            <span><strong>{needDecision}</strong> need a decision</span>
          </div>
        )}
      </header>

      {!readOnly && view === 'open' && <BatchNotify ready={ready} selected={selected} panelRef={panelRef} />}
      {!readOnly && view === 'open' && <FloatingBatchHint panelRef={panelRef} readyCount={ready.length} />}

      <div className="drafts-tabs alerts-tabs" role="tablist" aria-label="Open reports or sent out">
        <button type="button" role="tab" aria-selected={view === 'open'} onClick={() => setView('open')}>Open reports</button>
        <button type="button" role="tab" aria-selected={view === 'sent'} onClick={() => setView('sent')}>Sent out</button>
      </div>

      {view === 'sent' ? <SentOutList readOnly={readOnly} /> : (
        <section className="alerts-workspace" aria-label="Tickers and their reports">
          <aside className="alert-queue" aria-label="Tickers">
            <div className="queue-toolbar">
              <div className="queue-toolbar__title"><Icon name="filter_list" /><span>Tickers</span></div>
              <button className="icon-button" type="button" onClick={() => refresh()} aria-label="Refresh tickers"><Icon name="refresh" /></button>
            </div>
            <p className="queue-auto">Checks for new reports by itself every {POLL_MS / 1000} seconds.</p>
            {!readOnly && (
              <div className="queue-filters">
                <label>
                  <input type="checkbox" checked={onlyOpen} onChange={(e) => {
                    setOnlyOpen(e.target.checked)
                    setState({ loading: true, error: '', items: [], nextCursor: null, loadingMore: false })
                  }} />
                  {' '}Only tickers needing a decision
                </label>
              </div>
            )}
            {state.error && <div className="queue-error" role="alert">{state.error}</div>}
            {state.loading && <div className="queue-loading" role="status"><Icon name="autorenew" /> Loading tickers…</div>}
            {!state.loading && !state.error && state.items.length === 0 && (
              <div className="queue-empty">
                <p className="queue-empty__notice" role="status">
                  <Icon name="search_off" /> <strong>No news found in the past 4 hours.</strong>
                </p>
                <h2>No tickers need review</h2>
                <p>New reports will appear here after the next search run.</p>
              </div>
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
                      <span>{item.affectedClientCount} client{item.affectedClientCount === 1 ? '' : 's'} · {item.openClientCount} new</span>
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
              ? <TickerDetail key={selectedId} eventId={selectedId} selected={selected} setSelected={setSelected} onChanged={refresh} readOnly={readOnly} />
              : <div className="detail-empty"><Icon name="find_in_page" /><h2>Select a ticker to review</h2><p>Its report, sources and affected clients will open here.</p></div>}
          </article>
        </section>
      )}
    </div>
  )
}

export default TickerReports
