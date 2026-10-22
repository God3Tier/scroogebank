import { describe, expect, it } from 'vitest'

import { validateEvent } from './eventValidation.js'

const NOW = new Date('2026-10-21T02:00:00Z')

function meeting(overrides = {}) {
  return {
    title: 'Synthetic review',
    startsAt: '2026-10-22T14:00',
    endsAt: '2026-10-22T15:00',
    reminderOffsetsMinutes: [60],
    ...overrides,
  }
}

describe('validateEvent', () => {
  it('accepts a valid meeting', () => {
    expect(validateEvent(meeting(), { isNew: true, now: NOW })).toBe('')
  })

  it.each([
    [{ title: '  ' }, 'Enter a title.'],
    [{ title: 'x'.repeat(121) }, 'The title can be at most 120 characters.'],
    [{ endsAt: '2026-10-22T13:00' }, 'The end must be after the start.'],
    [{ endsAt: '2026-10-23T14:01' }, 'A meeting can last at most 24 hours.'],
    [{ startsAt: '2026-10-21T09:00', endsAt: '2026-10-21T09:30' }, 'The start must be in the future.'],
    [{ reminderOffsetsMinutes: [15, 30, 60] }, 'Choose at most two reminders.'],
  ])('rejects %o', (overrides, message) => {
    expect(validateEvent(meeting(overrides), { isNew: true, now: NOW })).toBe(message)
  })

  it('lets an existing meeting keep a start in the past', () => {
    expect(validateEvent(meeting({ startsAt: '2026-10-21T09:00', endsAt: '2026-10-21T09:30' }), { isNew: false, now: NOW }))
      .toBe('')
  })
})
