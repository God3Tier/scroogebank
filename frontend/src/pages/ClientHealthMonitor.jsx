import { useEffect, useMemo, useState } from 'react'
import { useAuth } from 'react-oidc-context'

import {
  acceptHealthTask,
  completeHealthTask,
  dismissHealthTask,
  getHealthTask,
  getHealthTaskHistory,
  listHealthTasks,
} from '../api/clientHealth.js'
import { ADMIN, inAnyGroup } from '../auth/roles.js'
import StatCard from '../components/StatCard.jsx'

const labelSignal = (signal) => signal.toLowerCase().split('_').map((word) => word[0].toUpperCase() + word.slice(1)).join(' ')
const labelStatus = (status) => status.toLowerCase().replace('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
const EMPTY_FILTERS = { status: '', severity: '', assignedAgentSubject: '' }

function Badge({ type, value }) {
  return <span className={`health-badge health-${type}-${value.toLowerCase().replace('_', '-')}`}>{type === 'status' ? labelStatus(value) : value}</span>
}

function ClientHealthMonitor() {
  const auth = useAuth()
  const readOnly = inAnyGroup(auth.user, [ADMIN])
  const [tasks, setTasks] = useState([])
  const [selectedId, setSelectedId] = useState(null)
  const [detailRequest, setDetailRequest] = useState(0)
  const [detail, setDetail] = useState(null)
  const [history, setHistory] = useState([])
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [nextCursor, setNextCursor] = useState(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')
  const [acting, setActing] = useState(false)
  const [note, setNote] = useState('')

  useEffect(() => {
    let current = true
    listHealthTasks({
      statuses: filters.status ? [filters.status] : [],
      severities: filters.severity ? [filters.severity] : [],
      assignedAgentSubject: readOnly ? filters.assignedAgentSubject : undefined,
    }).then(
      (page) => {
        if (!current) return
        const items = page.items || []
        const nextSelected = items[0]?.taskId || null
        setTasks(items)
        setNextCursor(page.nextCursor || null)
        setSelectedId(nextSelected)
        setDetail(null)
        setHistory([])
        setDetailLoading(Boolean(nextSelected))
        if (nextSelected) setDetailRequest((request) => request + 1)
        setLoading(false)
      },
      (problem) => {
        if (!current) return
        setTasks([])
        setSelectedId(null)
        setDetail(null)
        setHistory([])
        setDetailLoading(false)
        setError(problemMessage(problem))
        setLoading(false)
      },
    )
    return () => { current = false }
  }, [filters, readOnly])

  useEffect(() => {
    if (!selectedId) return undefined
    let current = true
    Promise.all([getHealthTask(selectedId), getHealthTaskHistory(selectedId)]).then(
      ([task, taskHistory]) => {
        if (!current) return
        setDetail(task)
        setHistory(taskHistory.items || [])
        setDetailLoading(false)
      },
      (problem) => {
        if (!current) return
        setDetail(null)
        setHistory([])
        setActionError(problemMessage(problem))
        setDetailLoading(false)
      },
    )
    return () => { current = false }
  }, [selectedId, detailRequest])

  const counts = useMemo(() => ({
    active: tasks.filter((task) => ['OPEN', 'IN_REVIEW'].includes(task.status)).length,
    high: tasks.filter((task) => task.severity === 'HIGH').length,
    review: tasks.filter((task) => task.status === 'IN_REVIEW').length,
    completed: tasks.filter((task) => task.status === 'COMPLETED').length,
  }), [tasks])

  async function loadMore() {
    if (!nextCursor) return
    setLoading(true)
    try {
      const page = await listHealthTasks({
        statuses: filters.status ? [filters.status] : [],
        severities: filters.severity ? [filters.severity] : [],
        assignedAgentSubject: readOnly ? filters.assignedAgentSubject : undefined,
        cursor: nextCursor,
      })
      setTasks((current) => [...current, ...(page.items || [])])
      setNextCursor(page.nextCursor || null)
    } catch (problem) {
      setError(problemMessage(problem))
    } finally {
      setLoading(false)
    }
  }

  function changeFilter(name, value) {
    setLoading(true)
    setError('')
    setFilters((current) => ({ ...current, [name]: value }))
  }

  function selectTask(taskId) {
    setSelectedId(taskId)
    setDetail(null)
    setHistory([])
    setDetailLoading(true)
    setActionError('')
    setDetailRequest((request) => request + 1)
  }

  async function perform(operation) {
    if (!detail || acting) return
    setActing(true)
    setActionError('')
    try {
      let updated
      if (operation === 'accept') {
        updated = await acceptHealthTask(detail.taskId, detail.version)
      } else if (operation === 'complete') {
        updated = await completeHealthTask(
          detail.taskId, detail.version, 'CLIENT_REVIEW_COMPLETED', note || null)
      } else {
        updated = await dismissHealthTask(
          detail.taskId, detail.version, 'EXPECTED_CLIENT_ACTIVITY', note || null)
      }
      const updatedHistory = await getHealthTaskHistory(detail.taskId)
      setDetail(updated)
      setHistory(updatedHistory.items || [])
      setTasks((current) => current.map((task) =>
        task.taskId === updated.taskId ? updated : task))
      setNote('')
    } catch (problem) {
      setActionError(problemMessage(problem))
      if (problem.status === 409) {
        try {
          const current = await getHealthTask(detail.taskId)
          setDetail(current)
          setTasks((items) => items.map((task) =>
            task.taskId === current.taskId ? current : task))
        } catch {
          // Keep the original actionable conflict message.
        }
      }
    } finally {
      setActing(false)
    }
  }

  return (
    <div className="page health-page">
      <header className="health-page-header">
        <div>
          <p className="health-eyebrow">Relationship intelligence</p>
          <h1>Client Health Monitor</h1>
          <p className="page-subtitle">{readOnly
            ? 'Read-only oversight of relationship-health tasks across Agents.'
            : 'Prioritise meaningful changes in client activity and coordinate proactive follow-up.'}</p>
        </div>
      </header>

      <div className="health-context"><span className="material-symbols-outlined" aria-hidden="true">monitoring</span><div><strong>Relationship health review</strong><p>Signals support agent judgement and are not fraud, AML, credit, or hardship decisions.</p></div></div>

      <section className="stat-grid" aria-label="Task summary">
        <StatCard label="Active on page" value={counts.active} icon="assignment" />
        <StatCard label="High on page" value={counts.high} icon="priority_high" />
        <StatCard label="In review on page" value={counts.review} icon="rate_review" />
        <StatCard label="Completed on page" value={counts.completed} icon="task_alt" />
      </section>

      <section className="card health-filters" aria-label="Task filters">
        <label>Status
          <select value={filters.status} onChange={(event) =>
            changeFilter('status', event.target.value)}>
            <option value="">All statuses</option>
            {['OPEN', 'IN_REVIEW', 'COMPLETED', 'DISMISSED'].map((value) =>
              <option key={value} value={value}>{labelStatus(value)}</option>)}
          </select>
        </label>
        <label>Severity
          <select value={filters.severity} onChange={(event) =>
            changeFilter('severity', event.target.value)}>
            <option value="">All severities</option>
            {['LOW', 'MEDIUM', 'HIGH'].map((value) =>
              <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        {readOnly && (
          <label>Assigned Agent subject
            <input value={filters.assignedAgentSubject} maxLength="128"
              onChange={(event) =>
                changeFilter('assignedAgentSubject', event.target.value)} />
          </label>
        )}
      </section>

      {error && <p className="error-text" role="alert">{error}</p>}

      <div className="health-workspace">
        <section className="health-queue" aria-label="Relationship health tasks">
          <div className="health-section-heading"><div><p className="health-eyebrow">{readOnly ? 'Oversight scope' : 'Assigned to you'}</p><h2>Task queue</h2></div><span>{tasks.length} visible</span></div>
          {loading && tasks.length === 0 && <p className="empty-state" role="status">Loading tasks…</p>}
          {!loading && !error && tasks.length === 0 && <p className="empty-state">No tasks match these filters.</p>}
          <div className="health-task-list">
            {tasks.map((task) => (
              <button key={task.taskId} className={`health-task ${selectedId === task.taskId ? 'is-selected' : ''}`} onClick={() => selectTask(task.taskId)} aria-pressed={selectedId === task.taskId}>
                <span className="health-task-top"><strong>{task.clientId}</strong><Badge type="severity" value={task.severity} /></span>
                <span className="health-task-signals">{task.evidence.map((item) => labelSignal(item.type)).join(' · ') || 'No evidence available'}</span>
                <span className="health-task-bottom"><Badge type="status" value={task.status} /><span>{formatTime(task.updatedAt)}</span></span>
                <span className="health-task-agent"><span className="material-symbols-outlined" aria-hidden="true">person</span>{task.assignedAgentSubject}</span>
              </button>
            ))}
          </div>
          {nextCursor && (
            <button type="button" className="health-load-more" disabled={loading} onClick={loadMore}>
              {loading ? 'Loading…' : 'Load more'}
            </button>
          )}
        </section>

        <article className="health-detail">
          {detailLoading && <p className="empty-state" role="status">Loading task detail…</p>}
          {!detailLoading && !detail && !actionError && <p className="empty-state">Select a task to review.</p>}
          {!detailLoading && detail && (
            <>
              <header className="health-detail-header">
                <div><div className="health-detail-badges"><Badge type="severity" value={detail.severity} /><Badge type="status" value={detail.status} /></div><h2>{detail.clientId}</h2><p>{detail.taskId} · Assigned to {detail.assignedAgentSubject}</p></div>
                <div className="health-task-version"><strong>v{detail.version}</strong><span>Task version</span></div>
              </header>

              <section className="health-detail-section">
                <div className="health-section-heading"><div><p className="health-eyebrow">Observed changes</p><h3>Signal evidence</h3></div><span>{detail.evidence.length} signals</span></div>
                <div className="health-evidence-grid">{detail.evidence.map((item) => (
                  <div className="health-evidence" key={`${item.accountId}-${item.type}-${item.ruleVersion}`}>
                    <span className="material-symbols-outlined" aria-hidden="true">monitoring</span>
                    <div><small>{labelSignal(item.type)}</small><strong>{item.severity}</strong><p>Account {item.accountId} · Rule {item.ruleVersion}</p></div>
                  </div>
                ))}</div>
              </section>

              <section className="health-explanation">
                <div className="health-explanation-title"><span className="material-symbols-outlined" aria-hidden="true">lightbulb</span><div><p className="health-eyebrow">Relationship insight</p><h3>Why this task was raised</h3></div></div>
                {detail.explanation ? (
                  <><p>{detail.explanation.text}</p><p className="health-explanation-source">Source: {labelStatus(detail.explanation.source)}</p></>
                ) : <p>Explanation is being prepared. Deterministic evidence remains available above.</p>}
              </section>

              <section className="health-detail-section">
                <div className="health-section-heading"><div><p className="health-eyebrow">Activity log</p><h3>Task history</h3></div></div>
                {history.length === 0 ? <p className="empty-state">No history is available.</p> : (
                  <ol className="health-timeline">{history.map((event) => (
                    <li key={`${event.taskVersion}-${event.action}`}><span></span><div><strong>{labelStatus(event.action)}</strong><p>{historyDetail(event)}</p><time>{formatTime(event.occurredAt)}</time></div></li>
                  ))}</ol>
                )}
              </section>

              {!readOnly && ['OPEN', 'IN_REVIEW'].includes(detail.status) && (
                <section className="health-action-note">
                  <label>Optional review note
                    <textarea value={note} maxLength="500" rows="3"
                      onChange={(event) => setNote(event.target.value)} />
                  </label>
                </section>
              )}

              {actionError && <p className="error-text" role="alert">{actionError}</p>}
              <footer className="health-actions">
                <p>Last updated {formatTime(detail.updatedAt)}</p>
                <div>
                  {!readOnly && ['OPEN', 'IN_REVIEW'].includes(detail.status) && <button type="button" className="health-dismiss" disabled={acting} onClick={() => perform('dismiss')}>Dismiss</button>}
                  {!readOnly && detail.status === 'OPEN' && <button type="button" className="health-primary" disabled={acting} onClick={() => perform('accept')}>Accept task</button>}
                  {!readOnly && detail.status === 'IN_REVIEW' && <button type="button" className="health-primary" disabled={acting} onClick={() => perform('complete')}>Complete task</button>}
                  {readOnly && <span className="health-closed-label"><span className="material-symbols-outlined" aria-hidden="true">visibility</span> Read-only oversight</span>}
                  {['COMPLETED', 'DISMISSED'].includes(detail.status) && <span className="health-closed-label"><span className="material-symbols-outlined" aria-hidden="true">check_circle</span> No further action required</span>}
                </div>
              </footer>
            </>
          )}
        </article>
      </div>
    </div>
  )
}

export default ClientHealthMonitor

function formatTime(value) {
  if (!value) return 'Unknown time'
  return new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Singapore',
  }).format(new Date(value))
}

function historyDetail(event) {
  const changes = []
  if (event.beforeStatus || event.afterStatus) {
    changes.push(`${event.beforeStatus ? labelStatus(event.beforeStatus) : 'Created'} → ${labelStatus(event.afterStatus)}`)
  }
  if (event.evidenceAddedCount) changes.push(`${event.evidenceAddedCount} evidence item(s) added`)
  if (event.dispositionCode) changes.push(labelStatus(event.dispositionCode))
  if (event.note) changes.push(event.note)
  return changes.join(' · ') || `Recorded by ${event.actor}`
}

function problemMessage(problem) {
  switch (problem?.status) {
    case 401: return 'Your session has expired. Please sign in again.'
    case 403: return 'You are not permitted to access this Client Health operation.'
    case 404: return 'This task is no longer available in your assigned scope.'
    case 409: return 'This task changed while you were reviewing it. The latest version has been reloaded.'
    case 503: return 'Client assignment is temporarily unavailable. Please retry shortly.'
    default: return problem?.message || 'Client Health is temporarily unavailable.'
  }
}
