import { useCallback, useEffect, useMemo, useState } from 'react'

import { getAlert, listAlerts, recordDecision, recordFeedback } from '../api/alerts.js'

function Icon({ name, className = '' }) {
  return <span className={`material-symbols-outlined ${className}`.trim()} aria-hidden="true">{name}</span>
}

const STATUS_LABELS = {
  NEW: 'New',
  MONITORING: 'Monitoring (earlier)',
  INFORMED: 'Plan client briefing',
  DISMISSED: 'Dismissed',
  SENT_OUT: 'Sent Out',
}

const DECISIONS = [
  { value: 'INFORM_CLIENT', label: 'Plan client briefing', help: 'Records intent only. No message is sent.' },
  { value: 'DISMISS', label: 'Dismiss alert', help: 'Close this alert without contacting the client.' },
]

function formatDate(value, withTime = true) {
  if (!value) return 'Not supplied'
  return new Intl.DateTimeFormat('en-SG', {
    day: '2-digit',
    month: 'short',
    ...(withTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}),
    timeZone: 'Asia/Singapore',
  }).format(new Date(value))
}

function statusTone(status) {
  if (status === 'NEW') return 'attention'
  if (status === 'MONITORING' || status === 'SENT_OUT') return 'monitoring'
  if (status === 'INFORMED') return 'verified'
  return 'quiet'
}

function StatusBadge({ status }) {
  return <span className={`status-badge status-badge--${statusTone(status)}`}>{STATUS_LABELS[status] || status}</span>
}

function LoadingDetail() {
  return (
    <div className="detail-loading" aria-label="Loading alert detail" role="status">
      <div className="skeleton skeleton--title" />
      <div className="skeleton skeleton--line" />
      <div className="skeleton skeleton--panel" />
      <span>Loading evidence and exposure…</span>
    </div>
  )
}

function EmptyQueue({ filtered }) {
  return (
    <div className="queue-empty">
      <Icon name="verified_user" />
      <h2>{filtered ? 'No alerts match these filters' : 'No alerts need review'}</h2>
      <p>{filtered ? 'Change the status or minimum score to widen the queue.' : 'New assigned alerts will appear here after deterministic checks pass.'}</p>
    </div>
  )
}

function DetailError({ error, onRetry }) {
  const hidden = error?.status === 404
  return (
    <div className="state-notice state-notice--error" role="alert">
      <Icon name="error" />
      <div>
        <h2>{hidden ? 'Alert not available' : 'Alert detail could not be loaded'}</h2>
        <p>{hidden ? 'It may have moved outside your assigned scope.' : 'The queue is still available. Retry the detail request when ready.'}</p>
      </div>
      <button className="button button--secondary" type="button" onClick={onRetry}>
        <Icon name="refresh" /> Retry
      </button>
    </div>
  )
}

function LatestDecision({ decision }) {
  if (!decision) return null
  const label = DECISIONS.find((item) => item.value === decision.decision)?.label || decision.decision
  return (
    <div className="decision-record" aria-label="Latest recorded decision">
      <Icon name="check" />
      <div>
        <span>Latest recorded decision</span>
        <strong>{label}</strong>
        <small>{formatDate(decision.decidedAt)} SGT · {decision.actorSubject} · alert v{decision.alertVersion}</small>
      </div>
    </div>
  )
}

function AlertWorkspace({ readOnly = false }) {
  const [alerts, setAlerts] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [status, setStatus] = useState('')
  const [minimumScore, setMinimumScore] = useState('')
  const [nextCursor, setNextCursor] = useState(null)
  const [listState, setListState] = useState({ loading: true, error: null })
  const [detailState, setDetailState] = useState({ loading: false, error: null })
  const [decision, setDecision] = useState('INFORM_CLIENT')
  const [note, setNote] = useState('')
  const [decisionState, setDecisionState] = useState({ saving: false, error: null, message: '' })
  const [feedbackState, setFeedbackState] = useState({ saving: false, message: '', error: null })

  const loadQueue = useCallback(async (cursor = '') => {
    setListState({ loading: true, error: null })
    try {
      const page = await listAlerts({ status, minimumScore, cursor, limit: 25 })
      setAlerts((current) => cursor ? [...current, ...page.items] : page.items)
      setNextCursor(page.nextCursor)
      if (!cursor) {
        setSelectedId((current) => page.items.some((item) => item.id === current) ? current : page.items[0]?.id || null)
      }
      setListState({ loading: false, error: null })
    } catch (error) {
      setListState({ loading: false, error })
    }
  }, [minimumScore, status])

  const loadDetail = useCallback(async (alertId) => {
    if (!alertId) {
      setDetail(null)
      return
    }
    setDetailState({ loading: true, error: null })
    setDecisionState({ saving: false, error: null, message: '' })
    setFeedbackState({ saving: false, error: null, message: '' })
    try {
      const response = await getAlert(alertId)
      setDetail(response)
      setDetailState({ loading: false, error: null })
    } catch (error) {
      setDetail(null)
      setDetailState({ loading: false, error })
    }
  }, [])

  useEffect(() => {
    // Fetching the server-owned queue is the external synchronization for this view.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadQueue()
  }, [loadQueue])

  useEffect(() => {
    // Keep the evidence panel synchronized with the selected server resource.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void loadDetail(selectedId)
  }, [loadDetail, selectedId])

  const activeCount = useMemo(
    () => alerts.filter((alert) => alert.status === 'NEW').length,
    [alerts],
  )

  async function submitDecision(event) {
    event.preventDefault()
    if (!detail) return
    setDecisionState({ saving: true, error: null, message: '' })
    try {
      const result = await recordDecision(detail.id, {
        decision,
        expectedVersion: detail.version,
        note: note.trim() || null,
      })
      setDetail((current) => ({
        ...current,
        status: result.newStatus,
        version: result.alertVersion,
        latestDecision: result,
      }))
      setDecisionState({ saving: false, error: null, message: `${DECISIONS.find((item) => item.value === decision)?.label} recorded.` })
      setNote('')
      await loadQueue()
    } catch (error) {
      setDecisionState({ saving: false, error, message: '' })
    }
  }

  async function submitFeedback(rating) {
    if (!detail) return
    setFeedbackState({ saving: true, error: null, message: '' })
    try {
      await recordFeedback(detail.id, {
        rating,
        reasonCode: null,
        comment: null,
        expectedVersion: detail.version,
      })
      setFeedbackState({ saving: false, error: null, message: 'Feedback recorded.' })
    } catch (error) {
      setFeedbackState({ saving: false, error, message: '' })
    }
  }

  const selectedDecision = DECISIONS.find((item) => item.value === decision)
  const filtered = Boolean(status || minimumScore)
  const canRecordDecision = detail?.status === 'NEW'

  return (
    <div className="alerts-page" id="main-content">
      <header className="alerts-heading">
        <div>
          <h1>Event alerts</h1>
          <p>Review public evidence against assigned synthetic portfolio exposure.</p>
        </div>
        <div className="queue-summary" aria-label={`${activeCount} active alerts in this page`}>
          <Icon name="notifications" />
          <span><strong>{activeCount}</strong> active in this view</span>
        </div>
      </header>

      <section className="alerts-workspace" aria-label="Alert review workspace">
        <aside className="alert-queue" aria-label="Alert queue">
          <div className="queue-toolbar">
            <div className="queue-toolbar__title">
              <Icon name="filter_list" />
              <span>Review queue</span>
            </div>
            <button className="icon-button" type="button" onClick={() => loadQueue()} aria-label="Refresh alert queue">
              <Icon name="refresh" />
            </button>
          </div>

          <div className="queue-filters">
            <label>
              <span>Status</span>
              <select value={status} onChange={(event) => setStatus(event.target.value)}>
                <option value="">All statuses</option>
                <option value="NEW">New</option>
                <option value="INFORMED">Plan client briefing</option>
                <option value="DISMISSED">Dismissed</option>
                <option value="SENT_OUT">Sent Out</option>
              </select>
            </label>
            <label>
              <span>Minimum score</span>
              <input
                type="number"
                min="0"
                max="100"
                step="5"
                inputMode="decimal"
                placeholder="Any"
                value={minimumScore}
                onChange={(event) => setMinimumScore(event.target.value)}
              />
            </label>
          </div>

          {listState.error && (
            <div className="queue-error" role="alert">
              <Icon name="error" />
              <p>Queue unavailable. Check the API and retry.</p>
              <button type="button" onClick={() => loadQueue()}>Retry</button>
            </div>
          )}

          {listState.loading && alerts.length === 0 ? (
            <div className="queue-loading" role="status"><Icon name="autorenew" /> Loading assigned alerts…</div>
          ) : alerts.length === 0 && !listState.error ? (
            <EmptyQueue filtered={filtered} />
          ) : (
            <ol className="alert-list">
              {alerts.map((alert) => (
                <li key={alert.id}>
                  <button
                    type="button"
                    className={`alert-row${selectedId === alert.id ? ' alert-row--selected' : ''}`}
                    aria-current={selectedId === alert.id ? 'true' : undefined}
                    onClick={() => setSelectedId(alert.id)}
                  >
                    <span className="signal-marker" aria-hidden="true" />
                    <span className="alert-row__time">{formatDate(alert.createdAt)}</span>
                    <span className="alert-row__headline">{alert.headline}</span>
                    <span className="alert-row__meta">
                      <StatusBadge status={alert.status} />
                      <span className="score"><strong>{Number(alert.score).toFixed(1)}</strong>/100</span>
                    </span>
                    <span className="alert-row__client">{alert.clientRef} · {alert.instrumentId}</span>
                    <Icon name="chevron_right" className="alert-row__chevron" />
                  </button>
                </li>
              ))}
            </ol>
          )}

          {nextCursor && (
            <button className="load-more" type="button" disabled={listState.loading} onClick={() => loadQueue(nextCursor)}>
              {listState.loading ? 'Loading…' : 'Load older alerts'}
            </button>
          )}
        </aside>

        <article className="alert-detail" aria-live="polite">
          {detailState.loading ? <LoadingDetail /> : detailState.error ? (
            <DetailError error={detailState.error} onRetry={() => loadDetail(selectedId)} />
          ) : detail ? (
            <>
              <header className="detail-header">
                <div className="detail-header__meta">
                  <StatusBadge status={detail.status} />
                  <span>{detail.clientRef}</span>
                  <span>{detail.instrumentId}</span>
                </div>
                <h2>{detail.headline}</h2>
                <div className="detail-header__foot">
                  <span><Icon name="schedule" /> {formatDate(detail.createdAt)} SGT</span>
                  <span className="score score--large"><strong>{Number(detail.score).toFixed(1)}</strong>/100 materiality</span>
                  <span>v{detail.version}</span>
                </div>
              </header>

              <section className="exposure-strip" aria-labelledby="exposure-title">
                <div>
                  <h3 id="exposure-title">Synthetic exposure</h3>
                  <strong>SGD {Number(detail.exposure.amountSgd).toLocaleString('en-SG', { minimumFractionDigits: 2 })}</strong>
                </div>
                <dl>
                  <div><dt>Portfolio share</dt><dd>{Number(detail.exposure.portfolioPercentage).toFixed(1)}%</dd></div>
                  <div><dt>Valued</dt><dd>{formatDate(detail.exposure.valuationAsOf)}</dd></div>
                  <div><dt>Freshness</dt><dd><span className={`freshness freshness--${detail.exposure.freshness.toLowerCase()}`}>{detail.exposure.freshness}</span></dd></div>
                </dl>
              </section>

              <div className="detail-columns">
                <section className="detail-section" aria-labelledby="brief-title">
                  <div className="section-heading">
                    <div><Icon name="auto_awesome" /><h3 id="brief-title">AI-generated briefing</h3></div>
                    <span>Decision support</span>
                  </div>
                  {detail.briefing ? (
                    <div className="briefing">
                      <p>{detail.briefing.summary}</p>
                      {detail.briefing.uncertainty && (
                        <div className="uncertainty"><Icon name="error" /><span><strong>Uncertainty:</strong> {detail.briefing.uncertainty}</span></div>
                      )}
                      <p className="model-note">{detail.briefing.modelVersion} · {detail.briefing.promptVersion}</p>
                    </div>
                  ) : (
                    <div className="inline-empty"><Icon name="autorenew" /> Structured briefing is still being prepared.</div>
                  )}
                </section>

                <section className="detail-section" aria-labelledby="factors-title">
                  <div className="section-heading">
                    <div><Icon name="find_in_page" /><h3 id="factors-title">Why this appeared</h3></div>
                    <span>Rule materiality-v1</span>
                  </div>
                  <ol className="factor-list">
                    {detail.explanationFactors.map((factor) => (
                      <li key={factor.code}>
                        <div><strong>{factor.code.replaceAll('_', ' ')}</strong><span>{factor.description}</span></div>
                        <b>{Number(factor.contribution) >= 0 ? '+' : ''}{Number(factor.contribution).toFixed(1)}</b>
                      </li>
                    ))}
                  </ol>
                </section>
              </div>

              <section className="detail-section evidence-section" aria-labelledby="evidence-title">
                <div className="section-heading">
                  <div><Icon name="verified_user" /><h3 id="evidence-title">Public evidence</h3></div>
                  <span>{detail.evidence.length} cited source{detail.evidence.length === 1 ? '' : 's'}</span>
                </div>
                <ul className="evidence-list">
                  {detail.evidence.map((item) => (
                    <li key={item.url}>
                      <div><strong>{item.title}</strong><span>{item.sourceDomain} · retrieved {formatDate(item.retrievedAt)}</span></div>
                      <a href={item.url} target="_blank" rel="noreferrer">Open source <Icon name="arrow_outward" /></a>
                    </li>
                  ))}
                </ul>
              </section>

              {readOnly ? (
                <div className="read-only-notice"><Icon name="verified_user" /><span><strong>Read-only oversight.</strong> Only the assigned agent can record a decision or feedback.</span></div>
              ) : (
                <section className="decision-section" aria-labelledby="decision-title">
                  <div className="section-heading">
                    <div><Icon name="check" /><h3 id="decision-title">Record your decision</h3></div>
                    <span>No client contact is triggered</span>
                  </div>
                  <LatestDecision decision={detail.latestDecision} />
                  {canRecordDecision ? <form onSubmit={submitDecision}>
                    <fieldset className="decision-options">
                      <legend>Choose the next step</legend>
                      {DECISIONS.map((item) => (
                        <label key={item.value} className={decision === item.value ? 'decision-option decision-option--selected' : 'decision-option'}>
                          <input type="radio" name="decision" value={item.value} checked={decision === item.value} onChange={(event) => setDecision(event.target.value)} />
                          <span><strong>{item.label}</strong><small>{item.help}</small></span>
                        </label>
                      ))}
                    </fieldset>
                    <label className="decision-note">
                      <span>Review note <small>Optional · maximum 500 characters</small></span>
                      <textarea maxLength="500" rows="3" value={note} onChange={(event) => setNote(event.target.value)} placeholder="Record context for the audit trail." />
                    </label>
                    {decisionState.error && (
                      <div className="form-error" role="alert">
                        <Icon name="error" />
                        <span>{decisionState.error.status === 409 ? 'This alert changed. Refresh it before recording a decision.' : decisionState.error.status === 503 ? `Audit persistence is unavailable. Retry${decisionState.error.retryAfter ? ` after ${decisionState.error.retryAfter} seconds` : ' shortly'}.` : decisionState.error.message}</span>
                      </div>
                    )}
                    <div className="decision-submit">
                      <p>{selectedDecision.help}</p>
                      <button className="button button--primary" type="submit" disabled={decisionState.saving}>
                        <Icon name={decisionState.saving ? 'autorenew' : 'check'} className={decisionState.saving ? 'spin' : ''} />
                        {decisionState.saving ? 'Recording…' : 'Record decision'}
                      </button>
                    </div>
                    <p className="success-message" aria-live="polite">{decisionState.message}</p>
                  </form> : (
                    <div className="decision-closed">
                      <Icon name="verified_user" />
                      <span><strong>Decision complete.</strong> This alert is closed for further decisions; feedback remains available.</span>
                    </div>
                  )}

                  <div className="feedback-row">
                    <div><strong>Was this alert useful?</strong><span>Quality feedback does not change the decision.</span></div>
                    <div>
                      <button type="button" disabled={feedbackState.saving} onClick={() => submitFeedback('USEFUL')}><Icon name="thumb_up" /> Yes</button>
                      <button type="button" disabled={feedbackState.saving} onClick={() => submitFeedback('NOT_USEFUL')}><Icon name="thumb_down" /> No</button>
                    </div>
                    <span className={feedbackState.error ? 'feedback-message feedback-message--error' : 'feedback-message'} aria-live="polite">
                      {feedbackState.error?.message || feedbackState.message}
                    </span>
                  </div>
                </section>
              )}
            </>
          ) : (
            <div className="detail-empty"><Icon name="find_in_page" /><h2>Select an alert to review</h2><p>Evidence, exposure, and decision controls will open here.</p></div>
          )}
        </article>
      </section>
    </div>
  )
}

export default AlertWorkspace
