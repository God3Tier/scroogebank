import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import { listClients } from '../api/clients.js'
import {
  cancelEvent,
  createEvent,
  getPreferences,
  listEvents,
  listHolidays,
  monthWindow,
  singaporeParts,
  updateEvent,
  updatePreferences,
} from '../api/engagement.js'
import EventForm from '../components/engagement/EventForm.jsx'

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const MONTH_NAME = new Intl.DateTimeFormat('en-SG', { month: 'long', year: 'numeric', timeZone: 'UTC' })
const CHIPS_PER_DAY = 3
const pad = (n) => String(n).padStart(2, '0')

function currentMonth(now) {
  const [year, month] = singaporeParts(now).date.split('-').map(Number)
  return { year, month }
}

function shiftMonth({ year, month }, by) {
  const index = year * 12 + (month - 1) + by
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

// The weeks shown for a month, Monday first. Days of the months either side
// fill the first and last weeks; they are shown greyed and hold no meetings.
function monthCells({ year, month }) {
  const first = new Date(Date.UTC(year, month - 1, 1))
  const before = (first.getUTCDay() + 6) % 7
  const count = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const previousCount = new Date(Date.UTC(year, month - 1, 0)).getUTCDate()
  const cells = []
  for (let i = before; i > 0; i -= 1) cells.push({ outside: true, number: previousCount - i + 1 })
  for (let day = 1; day <= count; day += 1) cells.push({ date: `${year}-${pad(month)}-${pad(day)}`, number: day })
  for (let day = 1; cells.length % 7 !== 0; day += 1) cells.push({ outside: true, number: day })
  return cells
}

// A new meeting starts at 10:00 on the chosen day, or tomorrow.
function defaultStartFor(date, now) {
  if (date) return `${date}T10:00`
  const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000)
  return `${singaporeParts(tomorrow).date}T10:00`
}

function Preferences() {
  const [state, setState] = useState({ loading: true, values: null, message: '', error: '' })

  useEffect(() => {
    let current = true
    getPreferences()
      .then((values) => current && setState({ loading: false, values, message: '', error: '' }))
      .catch(() => current && setState({ loading: false, values: null, message: '', error: 'Preferences could not be loaded.' }))
    return () => { current = false }
  }, [])

  async function toggle(name) {
    const next = { ...state.values, [name]: !state.values[name] }
    setState((current) => ({ ...current, values: next, message: '', error: '' }))
    try {
      const saved = await updatePreferences(next)
      setState({ loading: false, values: saved, message: 'Saved.', error: '' })
    } catch {
      setState((current) => ({ ...current, values: { ...next, [name]: !next[name] }, error: 'The change was not saved. Try again.' }))
    }
  }

  return (
    <section className="card calendar-preferences" aria-labelledby="preferences-heading">
      <div className="engagement-card-header">
        <span className="material-symbols-outlined" aria-hidden="true">mail</span>
        <h2 id="preferences-heading">Email notifications</h2>
      </div>
      <p className="page-subtitle">Invites and reminders go to the email address on your staff account.</p>
      {state.loading && <p className="empty-note">Loading…</p>}
      {state.error && <p className="form-error" role="alert">{state.error}</p>}
      {state.values && (
        <>
          <div className="calendar-preferences__options">
            <label className="checkbox-label">
              <input type="checkbox" checked={state.values.invitesEnabled} onChange={() => toggle('invitesEnabled')} />
              Send calendar invites to my mailbox
            </label>
            <label className="checkbox-label">
              <input type="checkbox" checked={state.values.remindersEnabled} onChange={() => toggle('remindersEnabled')} />
              Email me reminders before meetings
            </label>
          </div>
          <p className="engagement-hint">Turning reminders off cancels the ones already scheduled.</p>
        </>
      )}
      {state.message && <p className="success-message" role="status">{state.message}</p>}
    </section>
  )
}

// The agent's meetings by month, with Singapore public holidays. Meetings
// also reach the agent's own calendar as emailed invites (ADR-0052).
// Layout: Stitch screen "Agent Calendar" (Scrooge Global Bank CRM).
function AgentCalendar({ now: fixedNow }) {
  const [now] = useState(() => fixedNow ?? new Date())
  const [searchParams] = useSearchParams()
  const [month, setMonth] = useState(() => currentMonth(now))
  const [data, setData] = useState({ loading: true, error: '', events: [], holidays: [] })
  const [clients, setClients] = useState([])
  const [editing, setEditing] = useState(null)
  const [expandedDay, setExpandedDay] = useState(null)
  const [version, setVersion] = useState(0)

  const load = useCallback(async () => {
    const range = monthWindow(month.year, month.month)
    const lastDay = new Date(Date.UTC(month.year, month.month, 0)).getUTCDate()
    try {
      const [events, holidays] = await Promise.all([
        listEvents(range),
        listHolidays({ from: `${month.year}-${pad(month.month)}-01`, to: `${month.year}-${pad(month.month)}-${pad(lastDay)}` }),
      ])
      setData({ loading: false, error: '', events, holidays })
    } catch {
      setData({ loading: false, error: 'The calendar is temporarily unavailable.', events: [], holidays: [] })
    }
  }, [month])

  useEffect(() => {
    // The calendar is server-owned; fetching it is this page's external synchronization.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load, version])

  useEffect(() => {
    let current = true
    listClients({ size: 100 })
      .then((page) => current && setClients(page.content ?? []))
      .catch(() => current && setClients([]))
    return () => { current = false }
  }, [])

  // A link from an invite or reminder opens that meeting.
  const linked = searchParams.get('event')
  useEffect(() => {
    if (!linked) return
    const found = data.events.find((event) => event.id === linked)
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (found) setEditing({ event: found })
  }, [linked, data.events])

  async function handleSave(body) {
    if (editing.event) await updateEvent(editing.event.id, body)
    else await createEvent(body)
    setEditing(null)
    setVersion((v) => v + 1)
  }

  async function handleCancelEvent(event) {
    await cancelEvent(event.id)
    setEditing(null)
    setVersion((v) => v + 1)
  }

  const byDay = {}
  for (const event of data.events) {
    const day = singaporeParts(event.startsAt).date
    ;(byDay[day] ||= []).push(event)
  }
  const holidaysByDay = {}
  for (const holiday of data.holidays) (holidaysByDay[holiday.date] ||= []).push(holiday)
  const today = singaporeParts(now).date

  function renderDay(cell, index) {
    const weekend = index % 7 >= 5
    if (cell.outside) {
      return (
        <div key={`outside-${index}`} className={`calendar-day calendar-day--outside${weekend ? ' calendar-day--weekend' : ''}`} aria-hidden="true">
          <span className="calendar-day__label">{cell.number}</span>
        </div>
      )
    }
    const day = cell.date
    const holidays = holidaysByDay[day] || []
    const events = byDay[day] || []
    const shown = expandedDay === day ? events : events.slice(0, CHIPS_PER_DAY)
    const classes = ['calendar-day']
    if (weekend) classes.push('calendar-day--weekend')
    if (holidays.length > 0) classes.push('calendar-day--holiday')
    if (day === today) classes.push('calendar-day--today')
    return (
      <div key={day} role="gridcell" aria-label={day} className={classes.join(' ')}>
        <div className="calendar-day__top">
          <button type="button" className="calendar-day__number" onClick={() => setEditing({ event: null, date: day })}
            aria-label={`New meeting on ${day}`}>
            {cell.number}
          </button>
          {day === today && <span className="calendar-day__today">Today</span>}
          {holidays.map((holiday) => (
            <span key={holiday.id} className="calendar-holiday">{holiday.name}{holiday.observed ? ' (observed)' : ''}</span>
          ))}
        </div>
        {holidays.length > 0 && <span className="calendar-day__note">Public holiday</span>}
        {shown.map((event) => (
          <button
            key={event.id}
            type="button"
            className={`calendar-event${event.status === 'CANCELLED' ? ' calendar-event--cancelled' : ''}${editing?.event?.id === event.id ? ' calendar-event--selected' : ''}`}
            onClick={() => setEditing({ event })}
            title={`${singaporeParts(event.startsAt).time} ${event.title}`}
          >
            {singaporeParts(event.startsAt).time} {event.title}
          </button>
        ))}
        {events.length > CHIPS_PER_DAY && (
          <button type="button" className="calendar-day__more" onClick={() => setExpandedDay(expandedDay === day ? null : day)}>
            {expandedDay === day ? 'Show fewer' : `+${events.length - CHIPS_PER_DAY} more`}
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="page engagement-page">
      <header className="page-header engagement-header">
        <div>
          <h1>Calendar</h1>
          <p className="page-subtitle">Your client meetings in Singapore time. Each meeting is emailed to you as a calendar invite.</p>
        </div>
        <div className="calendar-toolbar">
          <div className="calendar-month-nav">
            <button type="button" onClick={() => setMonth((m) => shiftMonth(m, -1))} aria-label="Previous month">
              <span className="material-symbols-outlined" aria-hidden="true">chevron_left</span>
            </button>
            <h2 className="calendar-month">{MONTH_NAME.format(new Date(Date.UTC(month.year, month.month - 1, 1)))}</h2>
            <button type="button" onClick={() => setMonth((m) => shiftMonth(m, 1))} aria-label="Next month">
              <span className="material-symbols-outlined" aria-hidden="true">chevron_right</span>
            </button>
          </div>
          <button type="button" onClick={() => setMonth(currentMonth(now))}>Today</button>
          <button type="button" className="button--accent" onClick={() => setEditing({ event: null, date: null })}>
            <span className="material-symbols-outlined" aria-hidden="true">add</span>
            New meeting
          </button>
        </div>
      </header>

      <div className={`calendar-layout${editing ? ' calendar-layout--editing' : ''}`}>
        <div className="calendar-layout__main">
          {data.error && (
            <div className="form-error" role="alert">
              {data.error}{' '}
              <button type="button" onClick={() => setVersion((v) => v + 1)}>Try again</button>
            </div>
          )}

          {!data.error && (
            <div className="calendar-grid" role="grid" aria-busy={data.loading}>
              {WEEKDAYS.map((day) => <div key={day} className="calendar-weekday" role="columnheader">{day}</div>)}
              {monthCells(month).map(renderDay)}
            </div>
          )}

          <Preferences />
        </div>

        {editing && (
          <aside className="calendar-layout__panel">
            <EventForm
              key={editing.event?.id ?? `new-${editing.date}`}
              event={editing.event}
              clients={clients}
              defaultStart={defaultStartFor(editing.date, now)}
              onSave={handleSave}
              onCancelEvent={handleCancelEvent}
              onClose={() => setEditing(null)}
            />
          </aside>
        )}
      </div>
    </div>
  )
}

export default AgentCalendar
