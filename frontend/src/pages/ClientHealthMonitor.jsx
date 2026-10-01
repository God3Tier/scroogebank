import { useMemo, useState } from 'react'
import StatCard from '../components/StatCard.jsx'
import { freshClientHealthTasks } from '../data/clientHealthTasks.js'

const labelSignal = (signal) => signal.toLowerCase().split('_').map((word) => word[0].toUpperCase() + word.slice(1)).join(' ')
const labelStatus = (status) => status.toLowerCase().replace('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())

function Badge({ type, value }) {
  return <span className={`health-badge health-${type}-${value.toLowerCase().replace('_', '-')}`}>{type === 'status' ? labelStatus(value) : value}</span>
}

function ClientHealthMonitor() {
  const [tasks, setTasks] = useState(freshClientHealthTasks)
  const [selectedId, setSelectedId] = useState('RH-2048')
  const selected = tasks.find((task) => task.id === selectedId)
  const counts = useMemo(() => ({
    active: tasks.filter((task) => ['OPEN', 'IN_REVIEW'].includes(task.status)).length,
    high: tasks.filter((task) => task.severity === 'HIGH').length,
    review: tasks.filter((task) => task.status === 'IN_REVIEW').length,
    completed: tasks.filter((task) => task.status === 'COMPLETED').length,
  }), [tasks])

  const transition = (status, title, detail) => {
    setTasks((current) => current.map((task) => task.id === selectedId ? {
      ...task,
      status,
      updated: 'Just now',
      history: [{ title, detail, time: 'Just now' }, ...task.history],
    } : task))
  }

  const resetDemo = () => {
    setTasks(freshClientHealthTasks())
    setSelectedId('RH-2048')
  }

  return (
    <div className="page health-page">
      <header className="health-page-header">
        <div>
          <p className="health-eyebrow">Relationship intelligence</p>
          <h1>Client Health Monitor</h1>
          <p className="page-subtitle">Prioritise meaningful changes in client activity and coordinate proactive follow-up.</p>
        </div>
        <button onClick={resetDemo}><span className="material-symbols-outlined" aria-hidden="true">restart_alt</span> Reset demo</button>
      </header>

      <div className="health-context"><span className="material-symbols-outlined" aria-hidden="true">monitoring</span><div><strong>Relationship health review</strong><p>Signals support agent judgement and are not fraud, AML, credit, or hardship decisions.</p></div></div>

      <section className="stat-grid" aria-label="Task summary">
        <StatCard label="Active tasks" value={counts.active} icon="assignment" />
        <StatCard label="High priority" value={counts.high} icon="priority_high" />
        <StatCard label="In review" value={counts.review} icon="rate_review" />
        <StatCard label="Completed" value={counts.completed} icon="task_alt" />
      </section>

      <div className="health-workspace">
        <section className="health-queue" aria-label="Relationship health tasks">
          <div className="health-section-heading"><div><p className="health-eyebrow">Assigned to your team</p><h2>Task queue</h2></div><span>{tasks.length} tasks</span></div>
          <div className="health-task-list">
            {tasks.map((task) => (
              <button key={task.id} className={`health-task ${selectedId === task.id ? 'is-selected' : ''}`} onClick={() => setSelectedId(task.id)} aria-pressed={selectedId === task.id}>
                <span className="health-task-top"><strong>{task.client}</strong><Badge type="severity" value={task.severity} /></span>
                <span className="health-task-signals">{task.signals.map(labelSignal).join(' · ')}</span>
                <span className="health-task-bottom"><Badge type="status" value={task.status} /><span>{task.updated}</span></span>
                <span className="health-task-agent"><span className="material-symbols-outlined" aria-hidden="true">person</span>{task.agent}</span>
              </button>
            ))}
          </div>
        </section>

        <article className="health-detail">
          <header className="health-detail-header">
            <div><div className="health-detail-badges"><Badge type="severity" value={selected.severity} /><Badge type="status" value={selected.status} /></div><h2>{selected.client}</h2><p>{selected.id} · Assigned to {selected.agent}</p></div>
            <div className={`health-score health-score-${selected.severity.toLowerCase()}`}><strong>{selected.score}</strong><span>Health score</span></div>
          </header>

          <section className="health-summary">
            <div><p className="health-eyebrow">Client summary</p><p>{selected.summary}</p></div>
            <dl><div><dt>Segment</dt><dd>{selected.segment}</dd></div><div><dt>Relationship since</dt><dd>{selected.relationshipSince}</dd></div><div><dt>Analysis window</dt><dd>{selected.analysisWindow}</dd></div><div><dt>Confidence</dt><dd>{selected.confidence}%</dd></div></dl>
          </section>

          <section className="health-detail-section">
            <div className="health-section-heading"><div><p className="health-eyebrow">Observed changes</p><h3>Signal evidence</h3></div><span>{selected.signals.length} signals</span></div>
            <div className="health-signal-chips">{selected.signals.map((signal) => <span key={signal}>{labelSignal(signal)}</span>)}</div>
            <div className="health-evidence-grid">{selected.evidence.map((item) => <div className="health-evidence" key={item.label}><span className="material-symbols-outlined" aria-hidden="true">{item.icon}</span><div><small>{item.label}</small><strong>{item.value}</strong><p>{item.detail}</p></div></div>)}</div>
          </section>

          <section className="health-explanation">
            <div className="health-explanation-title"><span className="material-symbols-outlined" aria-hidden="true">lightbulb</span><div><p className="health-eyebrow">Relationship insight</p><h3>Why this task was raised</h3></div></div>
            <p>{selected.explanation}</p>
            <div className="health-next-action"><span className="material-symbols-outlined" aria-hidden="true">arrow_forward</span><div><strong>Recommended next action</strong><p>{selected.nextAction}</p></div></div>
          </section>

          <section className="health-detail-section">
            <div className="health-section-heading"><div><p className="health-eyebrow">Activity log</p><h3>Task history</h3></div></div>
            <ol className="health-timeline">{selected.history.map((event, index) => <li key={`${event.title}-${event.time}-${index}`}><span></span><div><strong>{event.title}</strong><p>{event.detail}</p><time>{event.time}</time></div></li>)}</ol>
          </section>

          <footer className="health-actions">
            <p>Last updated {selected.updated}</p>
            <div>
              {selected.status === 'OPEN' && <button className="health-dismiss" onClick={() => transition('DISMISSED', 'Task dismissed', 'No relationship follow-up required')}>Dismiss</button>}
              {selected.status === 'OPEN' && <button type="submit" onClick={() => transition('IN_REVIEW', 'Task accepted', `${selected.agent} began relationship review`)}>Accept task</button>}
              {selected.status === 'IN_REVIEW' && <button type="submit" onClick={() => transition('COMPLETED', 'Task completed', 'Relationship review completed')}>Complete task</button>}
              {['COMPLETED', 'DISMISSED'].includes(selected.status) && <span className="health-closed-label"><span className="material-symbols-outlined" aria-hidden="true">check_circle</span> No further action required</span>}
            </div>
          </footer>
        </article>
      </div>
    </div>
  )
}

export default ClientHealthMonitor
