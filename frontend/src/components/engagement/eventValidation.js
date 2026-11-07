import { fromLocalInput } from '../../api/engagement.js'

export const MAX_REMINDERS = 2
const DAY_MS = 24 * 60 * 60 * 1000

// Checks the rules the API enforces, so most mistakes are caught before a
// request (docs/api/engagement-api.yaml, EventInput). Returns a message, or
// '' when the meeting is valid.
export function validateEvent(values, { isNew, now = new Date() }) {
  if (!values.title.trim()) return 'Enter a title.'
  if (values.title.length > 120) return 'The title can be at most 120 characters.'
  if (!values.startsAt || !values.endsAt) return 'Enter a start and an end.'
  const start = new Date(fromLocalInput(values.startsAt))
  const end = new Date(fromLocalInput(values.endsAt))
  if (end <= start) return 'The end must be after the start.'
  if (end - start > DAY_MS) return 'A meeting can last at most 24 hours.'
  if (isNew && start <= now) return 'The start must be in the future.'
  if (values.reminderOffsetsMinutes.length > MAX_REMINDERS) return 'Choose at most two reminders.'
  return ''
}
