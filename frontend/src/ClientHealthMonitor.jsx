import { useEffect, useMemo, useRef, useState } from 'react'
import { freshTasks } from './mockHealthData.js'

const signalLabels = {
  RECURRING_CREDIT_INTERRUPTION: 'Recurring credit interruption',
  TRANSACTION_ACTIVITY_DECLINE: 'Transaction activity decline',
  SUSTAINED_NET_OUTFLOW: 'Sustained net outflow',
}

function Badge({ children, type }) {
  return <span className={`badge badge-${type.toLowerCase()}`}>{children}</span>
}

function ClientHealthMonitor() {
  const [tasks, setTasks] = useState(freshTasks)
  const [selectedId, setSelectedId] = useState('RH-2048')
  const [analysisPhase, setAnalysisPhase] = useState('idle')
  const timer = useRef(null)
  const selected = tasks.find((task) => task.id === selectedId) ?? tasks[0]

  useEffect(() => () => clearTimeout(timer.current), [])

  const counts = useMemo(() => ({
    open: tasks.filter((task) => task.status === 'OPEN').length,
    review: tasks.filter((task) => task.status === 'IN_REVIEW').length,
    resolved: tasks.filter((task) => ['COMPLETED', 'DISMISSED'].includes(task.status)).length,
  }), [tasks])

  function updateSelected(status, event) {
    setTasks((current) => current.map((task) => task.id !== selectedId ? task : {
      ...task,
      status,
      updated: 'Just now',
      history: [{ ...event, time: 'Just now', kind: 'action' }, ...task.history],
    }))
  }

  function runAnalysis() {
    setAnalysisPhase('running')
    clearTimeout(timer.current)
    timer.current = setTimeout(() => {
      setAnalysisPhase('complete')
      setSelectedId('RH-2048')
    }, 900)
  }

  function resetDemo() {
    clearTimeout(timer.current)
    setTasks(freshTasks())
    setSelectedId('RH-2048')
    setAnalysisPhase('idle')
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">S</div>
          <div><strong>Scrooge Global</strong><span>Staff Portal</span></div>
        </div>
        <nav aria-label="Primary navigation">
          <a href="#overview"><span>⌂</span>Overview</a>
          <a className="active" href="#health"><span>◇</span>Client Health</a>
          <a href="#clients"><span>♙</span>My Clients</a>
          <a href="#activity"><span>↔</span>Transactions</a>
        </nav>
        <div className="sidebar-note">
          <span className="live-dot" /> Demo workspace
          <small>Synthetic data only</small>
        </div>
        <div className="agent-card">
          <div className="avatar small">MC</div>
          <div><strong>Maya Chen</strong><span>Relationship Agent</span></div>
        </div>
      </aside>

      <main>
        <header className="topbar">
          <div>
            <div className="eyebrow">Relationship intelligence</div>
            <h1>Client Health Monitor</h1>
            <p>Prioritize proactive human review using explainable activity signals.</p>
          </div>
          <div className="top-actions">
            <button className="button secondary" onClick={resetDemo}>↻ Reset demo</button>
            <button className="button primary" onClick={runAnalysis} disabled={analysisPhase === 'running'}>
              {analysisPhase === 'running' ? <><span className="spinner" />Analyzing…</> : '▶ Run analysis'}
            </button>
          </div>
        </header>

        <div className="boundary-note">
          <strong>Relationship health review</strong>
          <span>Human review required · This is not a fraud, AML, credit, or hardship alert.</span>
        </div>

        {analysisPhase === 'complete' && (
          <div className="success-banner" role="status">
            <span>✓</span><div><strong>Analysis complete</strong><p>Finding CH-F-204 generated and linked to task RH-2048.</p></div>
          </div>
        )}

        <section className="stats" aria-label="Task summary">
          <div><span>Active tasks</span><strong>{counts.open + counts.review}</strong><small>Across assigned clients</small></div>
          <div><span>High priority</span><strong className="high-text">1</strong><small>Recommended for review</small></div>
          <div><span>In review</span><strong>{counts.review}</strong><small>Accepted by agent</small></div>
          <div><span>Resolved</span><strong>{counts.resolved}</strong><small>Completed or dismissed</small></div>
        </section>

        <div className="workspace" id="health">
          <section className="queue-panel">
            <div className="section-heading">
              <div><h2>Relationship tasks</h2><p>Sorted by priority</p></div>
              <span className="count-pill">{tasks.length}</span>
            </div>
            <div className="task-list">
              {tasks.map((task) => (
                <button
                  key={task.id}
                  className={`task-row ${task.id === selectedId ? 'selected' : ''}`}
                  onClick={() => setSelectedId(task.id)}
                >
                  <div className="task-row-top">
                    <strong>{task.client}</strong>
                    <Badge type={task.severity}>{task.severity}</Badge>
                  </div>
                  <div className="signal-summary">{task.signals.map((signal) => signalLabels[signal]).join(' · ')}</div>
                  <div className="task-meta">
                    <Badge type={task.status}>{task.status.replace('_', ' ')}</Badge>
                    <span>{task.assignedAgent}</span><span>{task.updated}</span>
                  </div>
                </button>
              ))}
            </div>
          </section>

          <TaskDetail task={selected} onAccept={() => updateSelected('IN_REVIEW', {
            label: 'Task accepted', detail: 'Maya Chen started relationship review',
          })} onComplete={() => updateSelected('COMPLETED', {
            label: 'Task completed', detail: 'Client relationship review completed',
          })} onDismiss={() => updateSelected('DISMISSED', {
            label: 'Task dismissed', detail: 'Activity reviewed and marked as expected',
          })} />
        </div>
      </main>
    </div>
  )
}

function TaskDetail({ task, onAccept, onComplete, onDismiss }) {
  const hasDetails = Boolean(task.evidence)
  return (
    <section className="detail-panel">
      <div className="detail-header">
        <div className="client-identity">
          <div className="avatar">{task.initials}</div>
          <div><div className="eyebrow">{task.id}</div><h2>{task.client}</h2><p>{task.segment ?? 'Personal Banking'} · Relationship since {task.relationshipSince ?? '2021'}</p></div>
        </div>
        <div className="detail-status"><Badge type={task.severity}>{task.severity}</Badge><Badge type={task.status}>{task.status.replace('_', ' ')}</Badge></div>
      </div>

      {!hasDetails ? (
        <div className="compact-empty"><span>◇</span><h3>Mock task summary</h3><p>Select the high-priority task to view the complete demo evidence and workflow.</p></div>
      ) : (
        <>
          <div className="health-overview">
            <div className="score-ring" style={{ '--score': `${task.score * 3.6}deg` }}><div><strong>{task.score}</strong><span>/ 100</span></div></div>
            <div><span className="eyebrow">Relationship health score</span><h3>Review recommended</h3><p>Multiple changes from the client’s established activity pattern were detected.</p></div>
            <dl><div><dt>Confidence</dt><dd>{task.confidence}</dd></div><div><dt>Analysis window</dt><dd>{task.analysisWindow}</dd></div><div><dt>Assigned agent</dt><dd>{task.assignedAgent}</dd></div></dl>
          </div>

          <section className="detail-section">
            <div className="section-heading"><div><h3>Detected signals</h3><p>Verified, deterministic evidence from synthetic activity</p></div><span className="count-pill">{task.signals.length}</span></div>
            <div className="evidence-grid">
              {task.evidence.map((item) => <article className="evidence-card" key={item.title}>
                <div className={`evidence-icon ${item.tone}`}>{item.icon}</div>
                <div><h4>{item.title}</h4><strong>{item.metric}</strong><p>{item.detail}</p><small>{item.context}</small></div>
              </article>)}
            </div>
          </section>

          <section className="explanation-panel">
            <div className="explanation-title"><span>✦</span><div><h3>Relationship health explanation</h3><p>Plain-language summary of verified facts</p></div><Badge type="template">TEMPLATE</Badge></div>
            <blockquote>{task.explanation}</blockquote>
            <div className="next-action"><span>→</span><div><strong>Recommended next action</strong><p>{task.nextAction}</p></div></div>
          </section>

          <section className="detail-section timeline-section">
            <div className="section-heading"><div><h3>Task history</h3><p>Every status and evidence change is retained</p></div></div>
            <ol className="timeline">{task.history.map((item, index) => <li key={`${item.label}-${index}`}>
              <span className={`timeline-dot ${item.kind}`} /><div><strong>{item.label}</strong><p>{item.detail}</p></div><time>{item.time}</time>
            </li>)}</ol>
          </section>

          <footer className="action-footer">
            <div><span>Current status</span><strong>{task.status.replace('_', ' ')}</strong></div>
            <div className="action-buttons">
              {task.status === 'OPEN' && <><button className="button text" onClick={onDismiss}>Dismiss</button><button className="button primary" onClick={onAccept}>Accept task</button></>}
              {task.status === 'IN_REVIEW' && <><button className="button text" onClick={onDismiss}>Dismiss</button><button className="button primary" onClick={onComplete}>✓ Complete review</button></>}
              {['COMPLETED', 'DISMISSED'].includes(task.status) && <span className="resolved-message">✓ This task is resolved</span>}
            </div>
          </footer>
        </>
      )}
    </section>
  )
}

export default ClientHealthMonitor
