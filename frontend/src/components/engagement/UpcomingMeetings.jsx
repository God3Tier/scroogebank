import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'

import { listClients } from '../../api/clients.js'
import { listEvents, SINGAPORE, singaporeParts } from '../../api/engagement.js'

const DAYS = 7
const SHOWN = 5
const WEEKDAY = new Intl.DateTimeFormat('en-SG', { weekday: 'short', timeZone: SINGAPORE })
const DAY = new Intl.DateTimeFormat('en-SG', { day: 'numeric', timeZone: SINGAPORE })
const REMINDER_SHORT = { 15: '15 min', 30: '30 min', 60: '1 hour', 1440: '1 day' }

// The reminder sent closest to the meeting, if any.
function nearestReminder(event) {
  const offsets = (event.reminders ?? []).map((reminder) => reminder.offsetMinutes)
  return offsets.length > 0 ? Math.min(...offsets) : null
}

function ClientLine({ clientId, names }) {
  if (!clientId) return <p className="upcoming-meeting__client upcoming-meeting__client--none">No client</p>
  if (!names[clientId]) return <p className="upcoming-meeting__client">Client meeting</p>
  return <p className="upcoming-meeting__client">Client <span>{names[clientId]}</span></p>
}

// The agent's meetings in the next week, on the agent home page. The panel
// is hidden when engagement-service is unavailable, so the rest of the page
// is unaffected (agent-engagement plan §6). Client names come from
// client-service; without them the row just says it is a client meeting.
// Layout: Stitch screen "Agent Dashboard - Upcoming Meetings".
function UpcomingMeetings({ now = () => new Date() }) {
  const [state, setState] = useState({ loading: true, failed: false, events: [] })
  const [names, setNames] = useState({})

  useEffect(() => {
    let current = true
    const from = now()
    const to = new Date(from.getTime() + DAYS * 24 * 60 * 60 * 1000)
    listEvents({ from: from.toISOString(), to: to.toISOString() })
      .then((events) => {
        if (current) setState({ loading: false, failed: false, events: events.filter((event) => new Date(event.endsAt) > from) })
      })
      .catch(() => {
        if (current) setState({ loading: false, failed: true, events: [] })
      })
    listClients({ size: 100 })
      .then((page) => {
        if (current) setNames(Object.fromEntries((page.content ?? []).map((c) => [c.client_id, `${c.first_name} ${c.last_name}`])))
      })
      .catch(() => {})
    return () => { current = false }
    // Loaded once per visit to the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  if (state.loading || state.failed) return null

  const shown = state.events.slice(0, SHOWN)

  return (
    <section className="card upcoming-meetings" aria-labelledby="upcoming-meetings-heading">
      <header className="upcoming-meetings__header">
        <div>
          <div className="upcoming-meetings__title">
            <h2 id="upcoming-meetings-heading">Upcoming meetings</h2>
            <span className="upcoming-meetings__range">Next {DAYS} days</span>
          </div>
          <p className="upcoming-meetings__subtitle">Meetings from your calendar.</p>
        </div>
        <Link to="/agent/calendar" className="upcoming-meetings__link">
          Open calendar
          <span className="material-symbols-outlined" aria-hidden="true">chevron_right</span>
        </Link>
      </header>
      {state.events.length === 0 ? (
        <p className="empty-note upcoming-meetings__empty">No meetings in the next {DAYS} days.</p>
      ) : (
        <ul className="upcoming-meetings__list">
          {shown.map((event, index) => {
            const reminder = nearestReminder(event)
            return (
              <li key={event.id} className="upcoming-meeting">
                <time className={`upcoming-meeting__date${index === 0 ? ' upcoming-meeting__date--next' : ''}`} dateTime={event.startsAt}>
                  <span>{WEEKDAY.format(new Date(event.startsAt))}</span>
                  <strong>{DAY.format(new Date(event.startsAt))}</strong>
                </time>
                <div className="upcoming-meeting__main">
                  <p className="upcoming-meeting__line">
                    <span className="upcoming-meeting__time">{singaporeParts(event.startsAt).time} – {singaporeParts(event.endsAt).time} SGT</span>
                    <span className="upcoming-meeting__dot" aria-hidden="true">·</span>
                    <span className="upcoming-meeting__title">{event.title}</span>
                  </p>
                  <ClientLine clientId={event.clientId} names={names} />
                </div>
                <div className="upcoming-meeting__meta">
                  {event.location && (
                    <span className="upcoming-meeting__location">
                      <span className="material-symbols-outlined" aria-hidden="true">location_on</span>
                      {event.location}
                    </span>
                  )}
                  {reminder !== null && (
                    <span className="upcoming-meeting__reminder" title="Email reminder">
                      <span className="material-symbols-outlined" aria-hidden="true">notifications</span>
                      {REMINDER_SHORT[reminder] ?? `${reminder} min`}
                    </span>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      <footer className="upcoming-meetings__footer">
        <span>
          <span className="material-symbols-outlined" aria-hidden="true">schedule</span>
          All times in SGT (UTC+8).
        </span>
        {state.events.length > 0 && (
          <span>
            {state.events.length > SHOWN
              ? `Showing ${SHOWN} of ${state.events.length} meetings`
              : `Showing ${state.events.length} ${state.events.length === 1 ? 'meeting' : 'meetings'}`}
          </span>
        )}
      </footer>
    </section>
  )
}

export default UpcomingMeetings
