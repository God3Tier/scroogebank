import { useEffect, useState } from 'react'

import { getSearchSchedule } from '../api/searchSources.js'

// When the next automatic search is (ADR-0081). A manual search never changes it.

function formatTime(value, zone) {
  return new Intl.DateTimeFormat('en-SG', {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: zone,
  }).format(new Date(value))
}

function countdown(target, now) {
  const minutes = Math.max(0, Math.round((new Date(target).getTime() - now) / 60000))
  const hours = Math.floor(minutes / 60)
  if (hours === 0) return `in ${minutes} min`
  return `in ${hours} h ${String(minutes % 60).padStart(2, '0')} min`
}

function everyText(minutes) {
  if (minutes % 60 === 0) return minutes === 60 ? 'every hour' : `every ${minutes / 60} hours`
  return `every ${minutes} minutes`
}

function SearchSchedule() {
  const [state, setState] = useState({ loading: true, error: false, schedule: null })
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    let current = true
    getSearchSchedule().then(
      (schedule) => { if (current) setState({ loading: false, error: false, schedule }) },
      () => { if (current) setState({ loading: false, error: true, schedule: null }) },
    )
    return () => { current = false }
  }, [])

  // The clock moves on, and once the shown time has passed the next one is fetched.
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(timer)
  }, [])
  const nextAt = state.schedule ? new Date(state.schedule.nextRunAt).getTime() : null
  useEffect(() => {
    if (nextAt === null || now < nextAt) return undefined
    let current = true
    getSearchSchedule().then((schedule) => { if (current) setState({ loading: false, error: false, schedule }) }, () => {})
    return () => { current = false }
  }, [now, nextAt])

  if (state.loading || state.error || !state.schedule) return null
  const { schedule } = state
  if (!schedule.enabled) {
    return <p className="search-schedule" role="status">Automatic searches are switched off.</p>
  }
  return (
    <p className="search-schedule" role="status">
      <strong>Next automatic search:</strong> {formatTime(schedule.nextRunAt, schedule.zone)}{' '}
      <span className="muted">({countdown(schedule.nextRunAt, now)})</span>
      <br />
      <span className="muted">
        Runs {everyText(schedule.intervalMinutes)} at fixed times ({schedule.zone.replace('_', ' ')}). Searching by hand never changes this.
      </span>
    </p>
  )
}

export default SearchSchedule
