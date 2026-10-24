import { useId, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { listActivities } from '../api/activities.js'
import { Pager, ViewAll } from './ListFooter.jsx'
import { actorLabel, describeActivity, isUserActor, sentenceCase, shortId } from './activityText.js'
import { PAGE_SIZE, cursorSource, usePagedList } from './paging.js'

// The dashboards' activity panel (ADR-0021, spec Appendix 3): "Agents can only
// see their own client activities. Admins can see the activities of all
// agents." user-service decides whose activities come back from the access
// token; admin only changes what the panel offers. Only changes are listed,
// never views or sign-ins. On a dashboard the newest ten show; paged, the
// whole feed does, ten at a time.
const DAY_MS = 24 * 60 * 60 * 1000
const PERIODS = [
  { days: '', label: 'Any time' },
  { days: '1', label: 'Last 24 hours' },
  { days: '7', label: 'Last 7 days' },
  { days: '30', label: 'Last 30 days' },
]

function loadProblem(error) {
  if (error.status === 503) return 'Activity is temporarily unavailable.'
  if (error.status === 401) return 'Your session has ended. Sign in again to see activities.'
  return 'Activities could not be loaded.'
}

function emptyText(admin, period, only) {
  if (only) return `No changes by ${only.name}${period.days ? ' in this period' : ''}.`
  if (period.days) return 'No changes in this period.'
  return admin ? 'No changes yet.' : 'No changes yet. Clients you create, update or verify appear here.'
}

function formatDateTime(value) {
  return new Date(value).toLocaleString('en-SG', { dateStyle: 'medium', timeStyle: 'short' })
}

function ActivityRow({ entry, showActor, onShowOnly }) {
  const activity = describeActivity(entry)
  const actor = actorLabel(entry)

  let sentence = sentenceCase(activity.text)
  if (showActor) {
    const name = onShowOnly && isUserActor(entry)
      ? (
        <button type="button" className="activity-row__actor" title={`Show only ${actor}'s activities`}
          onClick={() => onShowOnly({ id: entry.actorId, name: actor })}>
          {actor}
        </button>
      )
      : <strong>{actor}</strong>
    sentence = <>{name} {activity.text}</>
  }

  return (
    <li className="activity-row">
      <span className={`activity-row__icon activity-row__icon--${activity.tone}`} aria-hidden="true">
        <span className="material-symbols-outlined">{activity.icon}</span>
      </span>
      <div className="activity-row__body">
        <p className="activity-row__text">{sentence}</p>
        <p className="activity-row__meta">
          {activity.record && (
            <span>
              {activity.record.label} <code title={activity.record.id}>{shortId(activity.record.id)}</code>
            </span>
          )}
          {activity.note && <span>{activity.note}</span>}
          <time dateTime={entry.timestamp}>{formatDateTime(entry.timestamp)}</time>
          {activity.badge && <span className="status-badge status-badge--attention">{activity.badge}</span>}
        </p>
      </div>
      {activity.clientId && (
        <Link className="activity-row__link" to={`/clients/${encodeURIComponent(activity.clientId)}`}>
          Open client
        </Link>
      )}
    </li>
  )
}

// A period fixed now, so every page of the feed covers the same window.
function periodOf(days) {
  return { days, from: days ? new Date(Date.now() - Number(days) * DAY_MS) : null }
}

// The filters a View all link carries, so the whole feed opens filtered the
// same way as the panel it came from.
function filterQuery(period, only) {
  const params = new URLSearchParams()
  if (period.days) params.set('days', period.days)
  if (only) {
    params.set('user', only.id)
    params.set('name', only.name)
  }
  const text = params.toString()
  return text ? `?${text}` : ''
}

function ActivityFeed({ admin = false, paged = false }) {
  const headingId = useId()
  const [params] = useSearchParams()
  // The whole feed starts with the filters its link carried.
  const [period, setPeriod] = useState(() => {
    const days = paged ? params.get('days') : null
    return periodOf(PERIODS.some((option) => option.days && option.days === days) ? days : '')
  })
  // Admin only: one user's activities, picked from an entry.
  const [only, setOnly] = useState(() => (
    paged && admin && params.get('user') ? { id: params.get('user'), name: params.get('name') || 'this user' } : null
  ))
  // A new filter is a new source, so a slow answer for an old filter never
  // overwrites the current one.
  const source = useMemo(() => cursorSource(
    ({ cursor, size }) => listActivities({ agentId: only?.id, from: period.from, size, cursor }),
    paged ? {} : { chunkSize: PAGE_SIZE },
  ), [only, period, paged])
  const list = usePagedList(source)
  const entries = list.rows

  function choosePeriod(event) {
    setPeriod(periodOf(event.target.value))
  }

  return (
    <section className="card card-flush activity-feed" aria-labelledby={headingId}>
      <header className="activity-feed__header">
        <h2 id={headingId}>{admin ? 'Recent Activities' : 'My Recent Activities'}</h2>
        <label className="activity-feed__period">
          Period
          <select value={period.days} onChange={choosePeriod}>
            {PERIODS.map((option) => <option key={option.days} value={option.days}>{option.label}</option>)}
          </select>
        </label>
      </header>

      <div className="activity-feed__context">
        {only && (
          <button type="button" className="activity-feed__chip" onClick={() => setOnly(null)}
            aria-label={`Showing only ${only.name}. Show everyone's activities`}>
            Only {only.name}
            <span className="material-symbols-outlined" aria-hidden="true">close</span>
          </button>
        )}
        <p>
          {admin
            ? 'Changes CRM users made. Select a name to see only that user. Views and sign-ins are not listed.'
            : 'Changes you made to client records and accounts. Views and sign-ins are not listed.'}
        </p>
      </div>

      {entries.length > 0 && (
        <ol className="activity-list" aria-label="Activities, newest first">
          {entries.map((entry) => (
            <ActivityRow key={entry.id} entry={entry} showActor={admin} onShowOnly={admin ? setOnly : null} />
          ))}
        </ol>
      )}

      {list.loading && (
        <p className="activity-feed__status" role="status">
          <span className="material-symbols-outlined" aria-hidden="true">progress_activity</span>
          Loading activities…
        </p>
      )}

      {list.error && (
        <div className="activity-feed__problem" role="alert">
          <span className="material-symbols-outlined" aria-hidden="true">warning</span>
          <p>{loadProblem(list.error)}</p>
          <button type="button" onClick={list.reload}>Try again</button>
        </div>
      )}

      {!list.loading && !list.error && entries.length === 0 && (
        <p className="activity-feed__empty">
          <span className="material-symbols-outlined" aria-hidden="true">history</span>
          {emptyText(admin, period, only)}
        </p>
      )}

      {entries.length > 0 && paged && <Pager list={list} label="Activity pages" />}
      {!paged && list.hasNext && (
        <ViewAll to={`${admin ? '/admin/activities' : '/agent/activities'}${filterQuery(period, only)}`} label="View all activities" />
      )}
    </section>
  )
}

export default ActivityFeed
