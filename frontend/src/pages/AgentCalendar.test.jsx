import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listClients } from '../api/clients.js'
import {
  cancelEvent,
  createEvent,
  getPreferences,
  listEvents,
  listHolidays,
  updateEvent,
  updatePreferences,
} from '../api/engagement.js'
import AgentCalendar from './AgentCalendar.jsx'

vi.mock('../api/clients.js', () => ({ listClients: vi.fn() }))
vi.mock('../api/engagement.js', async (importOriginal) => ({
  ...(await importOriginal()),
  listEvents: vi.fn(),
  listHolidays: vi.fn(),
  createEvent: vi.fn(),
  updateEvent: vi.fn(),
  cancelEvent: vi.fn(),
  getPreferences: vi.fn(),
  updatePreferences: vi.fn(),
}))

// 10:00 on 21 October 2026, Singapore time.
const NOW = new Date('2026-10-21T02:00:00Z')

const REVIEW = {
  id: 'event-1',
  title: 'Synthetic review',
  description: null,
  location: 'Level 3',
  startsAt: '2026-10-22T06:00:00Z',
  endsAt: '2026-10-22T07:00:00Z',
  clientId: 'client-1',
  status: 'SCHEDULED',
  invite: { status: 'SENT', sequence: 0 },
  reminders: [{ offsetMinutes: 60, dueAt: '2026-10-22T05:00:00Z', status: 'PENDING' }],
  version: 3,
}

beforeEach(() => {
  listClients.mockResolvedValue({ content: [{ client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' }] })
  listEvents.mockResolvedValue([REVIEW])
  listHolidays.mockResolvedValue([])
  getPreferences.mockResolvedValue({ invitesEnabled: true, remindersEnabled: true })
  createEvent.mockResolvedValue({})
  updateEvent.mockResolvedValue({})
  cancelEvent.mockResolvedValue(null)
})

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

function renderCalendar() {
  return render(<MemoryRouter><AgentCalendar now={NOW} /></MemoryRouter>)
}

describe('AgentCalendar', () => {
  it("shows the month's meetings on their Singapore day", async () => {
    renderCalendar()

    const day = await screen.findByRole('gridcell', { name: '2026-10-22' })
    expect(await within(day).findByRole('button', { name: /Synthetic review/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'October 2026' })).toBeInTheDocument()
    expect(listEvents).toHaveBeenCalledWith({ from: '2026-10-01T00:00:00+08:00', to: '2026-11-01T00:00:00+08:00' })
  })

  it('shows public holidays when moving to the next month', async () => {
    listHolidays.mockImplementation(async ({ from }) => (from === '2026-11-01'
      ? [{ id: 'h1', countryCode: 'SG', date: '2026-11-08', name: 'Deepavali', observed: false }]
      : []))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'Next month' }))

    expect(await screen.findByText('Deepavali')).toBeInTheDocument()
    expect(listHolidays).toHaveBeenCalledWith({ from: '2026-11-01', to: '2026-11-30' })
  })

  it('checks a new meeting before saving it', async () => {
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'New meeting' }))
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic lunch' } })
    fireEvent.change(screen.getByLabelText('Starts'), { target: { value: '2026-10-23T12:00' } })
    fireEvent.change(screen.getByLabelText('Ends'), { target: { value: '2026-10-23T11:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The end must be after the start.')
    expect(createEvent).not.toHaveBeenCalled()
  })

  it('creates a meeting in Singapore time with its reminders and client', async () => {
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'New meeting on 2026-10-23' }))
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic lunch' } })
    fireEvent.change(screen.getByLabelText('Client'), { target: { value: 'client-1' } })
    fireEvent.click(screen.getByLabelText('1 day before'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(createEvent).toHaveBeenCalledOnce())
    expect(createEvent).toHaveBeenCalledWith({
      title: 'Synthetic lunch',
      description: null,
      location: null,
      startsAt: '2026-10-23T10:00:00+08:00',
      endsAt: '2026-10-23T11:00:00+08:00',
      clientId: 'client-1',
      reminderOffsetsMinutes: [60, 1440],
    })
  })

  it('allows only two reminders', async () => {
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: 'New meeting' }))
    fireEvent.click(screen.getByLabelText('1 day before'))

    expect(screen.getByLabelText('30 minutes before')).toBeDisabled()
    expect(screen.getByLabelText('1 hour before')).not.toBeDisabled()
  })

  it('saves an edit with the version it replaces', async () => {
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /Synthetic review/ }))
    expect(screen.getByRole('status')).toHaveTextContent('Calendar invite: Sent')
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Synthetic review, moved' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await vi.waitFor(() => expect(updateEvent).toHaveBeenCalledOnce())
    expect(updateEvent.mock.calls[0][0]).toBe('event-1')
    expect(updateEvent.mock.calls[0][1]).toMatchObject({ title: 'Synthetic review, moved', version: 3, reminderOffsetsMinutes: [60] })
  })

  it('cancels a meeting after confirming', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /Synthetic review/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel meeting' }))

    await vi.waitFor(() => expect(cancelEvent).toHaveBeenCalledWith('event-1'))
  })

  it('explains a stale edit', async () => {
    updateEvent.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }))
    renderCalendar()

    fireEvent.click(await screen.findByRole('button', { name: /Synthetic review/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('changed elsewhere or cancelled')
  })

  it('says when the calendar is unavailable', async () => {
    listEvents.mockRejectedValue(Object.assign(new Error('Unavailable'), { status: 503 }))
    renderCalendar()

    expect(await screen.findByText('The calendar is temporarily unavailable.')).toBeInTheDocument()
  })

  it('saves notification preferences', async () => {
    updatePreferences.mockResolvedValue({ invitesEnabled: true, remindersEnabled: false })
    renderCalendar()

    fireEvent.click(await screen.findByLabelText('Email me reminders before meetings'))

    await vi.waitFor(() => expect(updatePreferences).toHaveBeenCalledWith({ invitesEnabled: true, remindersEnabled: false }))
    expect(await screen.findByText('Saved.')).toBeInTheDocument()
  })
})
