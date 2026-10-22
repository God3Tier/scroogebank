import { useState } from 'react'

import { fromLocalInput, MAIL_STATUS_LABELS, REMINDER_OPTIONS, toLocalInput } from '../../api/engagement.js'
import { MAX_REMINDERS, validateEvent } from './eventValidation.js'

// Shown two to a row, shortest first, as in the Stitch "Agent Calendar" panel.
const REMINDER_CHOICES = [...REMINDER_OPTIONS].sort((a, b) => a.minutes - b.minutes)

function initialValues(event, defaultStart) {
  if (!event) {
    const end = new Date(new Date(fromLocalInput(defaultStart)).getTime() + 60 * 60 * 1000)
    return {
      title: '', description: '', location: '', clientId: '',
      startsAt: defaultStart, endsAt: toLocalInput(end), reminderOffsetsMinutes: [60],
    }
  }
  return {
    title: event.title,
    description: event.description || '',
    location: event.location || '',
    clientId: event.clientId || '',
    startsAt: toLocalInput(event.startsAt),
    endsAt: toLocalInput(event.endsAt),
    reminderOffsetsMinutes: event.reminders.map((reminder) => reminder.offsetMinutes),
  }
}

function problemFor(error) {
  if (error.status === 404) return 'That client is not one of yours, so the meeting was not saved.'
  if (error.status === 409) return 'This meeting was changed elsewhere or cancelled. Reload the calendar and try again.'
  if (error.status === 503) return 'Client details are unavailable right now, so nothing was saved. Try again.'
  if (error.status === 400) return `Check the details: ${error.message}`
  return 'The meeting could not be saved. Try again.'
}

// Creates or edits one meeting. The agent's mailbox receives the invite,
// update or cancellation; the CRM is the source of truth (ADR-0052).
function EventForm({ event, clients, defaultStart, onSave, onCancelEvent, onClose }) {
  const isNew = !event
  const [values, setValues] = useState(() => initialValues(event, defaultStart))
  const [state, setState] = useState({ saving: false, error: '' })

  function setField(name) {
    return (e) => setValues((current) => ({ ...current, [name]: e.target.value }))
  }

  function toggleReminder(minutes) {
    setValues((current) => {
      const chosen = current.reminderOffsetsMinutes
      return {
        ...current,
        reminderOffsetsMinutes: chosen.includes(minutes) ? chosen.filter((m) => m !== minutes) : [...chosen, minutes],
      }
    })
  }

  async function handleSubmit(e) {
    e.preventDefault()
    const problem = validateEvent(values, { isNew })
    if (problem) {
      setState({ saving: false, error: problem })
      return
    }
    setState({ saving: true, error: '' })
    const body = {
      title: values.title.trim(),
      description: values.description.trim() || null,
      location: values.location.trim() || null,
      startsAt: fromLocalInput(values.startsAt),
      endsAt: fromLocalInput(values.endsAt),
      clientId: values.clientId || null,
      reminderOffsetsMinutes: values.reminderOffsetsMinutes,
    }
    try {
      await onSave(isNew ? body : { ...body, version: event.version })
    } catch (error) {
      setState({ saving: false, error: problemFor(error) })
    }
  }

  async function handleCancelEvent() {
    if (!window.confirm('Cancel this meeting? A cancellation is sent to your calendar.')) return
    setState({ saving: true, error: '' })
    try {
      await onCancelEvent(event)
    } catch {
      setState({ saving: false, error: 'The meeting could not be cancelled. Try again.' })
    }
  }

  const chosen = values.reminderOffsetsMinutes.length
  const full = chosen >= MAX_REMINDERS

  return (
    <form className="event-form" onSubmit={handleSubmit} noValidate>
      <div className="event-form__header">
        <span className="material-symbols-outlined" aria-hidden="true">{isNew ? 'calendar_add_on' : 'edit_calendar'}</span>
        <h2>{isNew ? 'New meeting' : 'Edit meeting'}</h2>
        <button type="button" className="icon-button" onClick={onClose} disabled={state.saving} aria-label="Close panel">
          <span className="material-symbols-outlined" aria-hidden="true">close</span>
        </button>
      </div>
      <div className="event-form__body">
        <label><span className="label--required">Title</span>
          <input type="text" maxLength={120} value={values.title} onChange={setField('title')} placeholder="e.g. Portfolio review" />
        </label>
        <label><span className="label--required">Starts</span>
          <input type="datetime-local" value={values.startsAt} onChange={setField('startsAt')} />
        </label>
        <label><span className="label--required">Ends</span>
          <input type="datetime-local" value={values.endsAt} onChange={setField('endsAt')} />
        </label>
        <label>Location
          <span className="input-with-icon">
            <span className="material-symbols-outlined" aria-hidden="true">location_on</span>
            <input type="text" maxLength={200} value={values.location} onChange={setField('location')} placeholder="Meeting room or video link" />
          </span>
        </label>
        <label>Client
          <select value={values.clientId} onChange={setField('clientId')}>
            <option value="">No client</option>
            {clients.map((client) => (
              <option key={client.client_id} value={client.client_id}>{client.first_name} {client.last_name}</option>
            ))}
          </select>
        </label>
        <label>Notes
          <textarea rows={3} maxLength={1000} value={values.description} onChange={setField('description')} placeholder="Agenda or documents to bring" />
        </label>
        <fieldset className="event-form__reminders">
          <div className="event-form__reminders-head">
            <legend>Email reminders <span className="event-form__reminders-limit">(up to two)</span></legend>
            <span className="event-form__reminders-count">{chosen} selected</span>
          </div>
          <div className="event-form__reminders-options">
            {REMINDER_CHOICES.map((option) => {
              const checked = values.reminderOffsetsMinutes.includes(option.minutes)
              return (
                <label key={option.minutes} className="checkbox-label">
                  <input
                    type="checkbox"
                    checked={checked}
                    disabled={!checked && full}
                    onChange={() => toggleReminder(option.minutes)}
                  />
                  {option.label}
                </label>
              )
            })}
          </div>
        </fieldset>
        {!isNew && (
          <p className="event-form__status" role="status">
            Calendar invite: {MAIL_STATUS_LABELS[event.invite.status]}
            {event.reminders.length > 0 && ` · Reminders: ${event.reminders
              .map((reminder) => `${REMINDER_OPTIONS.find((o) => o.minutes === reminder.offsetMinutes)?.label ?? reminder.offsetMinutes} (${MAIL_STATUS_LABELS[reminder.status]})`)
              .join(', ')}`}
          </p>
        )}
        <p className="event-form__notice">
          <span className="material-symbols-outlined" aria-hidden="true">forward_to_inbox</span>
          <span>An invite is emailed to you when you save. Changes made in your own calendar do not come back to the CRM.</span>
        </p>
        {state.error && <p className="form-error" role="alert">{state.error}</p>}
        <div className="event-form__actions">
          {!isNew && (
            <button type="button" className="button--link-danger" onClick={handleCancelEvent} disabled={state.saving}>
              Cancel meeting
            </button>
          )}
          <span className="event-form__spacer" />
          <button type="button" onClick={onClose} disabled={state.saving}>Close</button>
          <button type="submit" disabled={state.saving}>{state.saving ? 'Saving…' : 'Save'}</button>
        </div>
      </div>
    </form>
  )
}

export default EventForm
