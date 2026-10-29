import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listClients } from '../api/clients.js'
import { createDraft, discardDraft, getDraft, listDrafts, listEvents, sendDraft, updateDraft } from '../api/engagement.js'
import AgentDrafts from './AgentDrafts.jsx'

vi.mock('../api/clients.js', () => ({ listClients: vi.fn() }))
vi.mock('../api/engagement.js', async (importOriginal) => ({
  ...(await importOriginal()),
  listDrafts: vi.fn(),
  getDraft: vi.fn(),
  createDraft: vi.fn(),
  updateDraft: vi.fn(),
  sendDraft: vi.fn(),
  discardDraft: vi.fn(),
  listEvents: vi.fn(),
}))

const NOW = new Date('2026-10-21T02:00:00Z')

const AI_DRAFT = {
  id: 'draft-1',
  clientId: 'client-1',
  eventId: null,
  purpose: 'HOLIDAY_GREETING',
  tone: 'WARM',
  subjectTemplate: 'Happy Deepavali, {{client_first_name}}',
  bodyTemplate: 'Hi {{client_first_name}},\n\nWishing you a bright Deepavali.\n\n{{agent_name}}',
  subject: 'Happy Deepavali, Synthetic',
  body: 'Hi Synthetic,\n\nWishing you a bright Deepavali.\n\nSynthetic Agent',
  source: 'AI',
  sourceReason: null,
  holiday: { id: 'h1', countryCode: 'SG', date: '2026-11-08', name: 'Deepavali', observed: false },
  status: 'DRAFT',
  sentAt: null,
  version: 0,
  createdAt: '2026-10-21T02:00:00Z',
  updatedAt: '2026-10-21T02:00:00Z',
}

beforeEach(() => {
  listClients.mockResolvedValue({ content: [{ client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' }] })
  listDrafts.mockResolvedValue([AI_DRAFT])
  getDraft.mockResolvedValue(AI_DRAFT)
  listEvents.mockResolvedValue([])
})

afterEach(() => {
  vi.clearAllMocks()
  vi.restoreAllMocks()
})

function renderDrafts() {
  return render(<MemoryRouter><AgentDrafts now={NOW} /></MemoryRouter>)
}

async function openDraft() {
  renderDrafts()
  fireEvent.click(await screen.findByRole('button', { name: 'Open' }))
  return screen.findByRole('heading', { name: 'Email to Synthetic Client' })
}

describe('AgentDrafts', () => {
  it('pages the emails ten at a time, asking for as many as engagement-service sends', async () => {
    listDrafts.mockResolvedValue(Array.from({ length: 12 }, (_, i) => ({ ...AI_DRAFT, id: `draft-${i + 1}` })))
    renderDrafts()

    expect(await screen.findByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Open' })).toHaveLength(10)
    expect(listDrafts).toHaveBeenCalledWith({ status: 'DRAFT', limit: 100 })

    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(await screen.findByText('Page 2 of 2')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Open' })).toHaveLength(2)
    expect(screen.getByText('12 emails')).toBeInTheDocument()
  })

  it('asks for a draft for the chosen client and opens it', async () => {
    listDrafts.mockResolvedValueOnce([])
    createDraft.mockResolvedValue(AI_DRAFT)
    renderDrafts()

    fireEvent.change(await screen.findByLabelText('Client'), { target: { value: 'client-1' } })
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'HOLIDAY_GREETING' } })
    fireEvent.change(screen.getByLabelText('Tone'), { target: { value: 'WARM' } })
    fireEvent.click(screen.getByRole('button', { name: 'Write draft' }))

    expect(await screen.findByRole('heading', { name: 'Email to Synthetic Client' })).toBeInTheDocument()
    expect(createDraft).toHaveBeenCalledWith({ clientId: 'client-1', purpose: 'HOLIDAY_GREETING', tone: 'WARM', eventId: null })
    expect(getDraft).toHaveBeenCalledWith('draft-1')
  })

  it('needs a past meeting for a meeting follow-up', async () => {
    listEvents.mockResolvedValue([
      { id: 'past', title: 'Synthetic review', clientId: 'client-1', startsAt: '2026-10-20T02:00:00Z', endsAt: '2026-10-20T03:00:00Z' },
      { id: 'other', title: 'Other client', clientId: 'client-2', startsAt: '2026-10-19T02:00:00Z', endsAt: '2026-10-19T03:00:00Z' },
    ])
    renderDrafts()

    fireEvent.change(await screen.findByLabelText('Client'), { target: { value: 'client-1' } })
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'MEETING_FOLLOW_UP' } })
    fireEvent.click(screen.getByRole('button', { name: 'Write draft' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Choose the meeting to follow up on.')
    expect(createDraft).not.toHaveBeenCalled()
    expect(await screen.findByRole('option', { name: /Synthetic review/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Other client/ })).not.toBeInTheDocument()
  })

  it('explains a holiday purpose with no holiday', async () => {
    createDraft.mockRejectedValue(Object.assign(
      new Error("The client's country has no public holiday in the next 30 days"), { status: 422 }))
    renderDrafts()

    fireEvent.change(await screen.findByLabelText('Client'), { target: { value: 'client-1' } })
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'HOLIDAY_GREETING' } })
    fireEvent.click(screen.getByRole('button', { name: 'Write draft' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('no public holiday in the next 30 days')
  })

  it("says when the client's country has no holiday data", async () => {
    createDraft.mockRejectedValue(Object.assign(new Error(
      "Public holidays are only available for clients in Singapore. Set the client's country to Singapore or SG, or choose another purpose"),
    { status: 422 }))
    renderDrafts()

    fireEvent.change(await screen.findByLabelText('Client'), { target: { value: 'client-1' } })
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'HOLIDAY_GREETING' } })
    fireEvent.click(screen.getByRole('button', { name: 'Write draft' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Set the client's country to Singapore or SG")
  })

  it('labels an AI draft, shows its holiday and the text with the names filled in', async () => {
    await openDraft()

    expect(screen.getByText('AI-generated: review before sending')).toBeInTheDocument()
    expect(screen.getByText('Holiday used: Deepavali (8 Nov)')).toBeInTheDocument()
    expect(screen.getByLabelText('Subject')).toHaveValue('Happy Deepavali, Synthetic')
    expect(screen.getByLabelText(/^Body/)).toHaveValue('Hi Synthetic,\n\nWishing you a bright Deepavali.\n\nSynthetic Agent')
    expect(document.body).not.toHaveTextContent('{{')
  })

  it('saves the plain text the agent edited', async () => {
    updateDraft.mockResolvedValue({ ...AI_DRAFT, version: 1 })
    await openDraft()

    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Deepavali wishes, Synthetic' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }))

    await vi.waitFor(() => expect(updateDraft).toHaveBeenCalledWith('draft-1', {
      subject: 'Deepavali wishes, Synthetic',
      body: AI_DRAFT.body,
      version: 0,
    }))
  })

  it('cannot edit or send a draft whose names cannot be looked up', async () => {
    getDraft.mockResolvedValue({ ...AI_DRAFT, subject: null, body: null })
    await openDraft()

    expect(screen.getByRole('status')).toHaveTextContent('could not be looked up')
    expect(screen.queryByLabelText('Subject')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Send' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Discard' })).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('{{')
  })

  it('says why a template was used', async () => {
    getDraft.mockResolvedValue({ ...AI_DRAFT, source: 'TEMPLATE', sourceReason: 'ai_disabled' })
    await openDraft()

    expect(screen.getByText('Template, because AI drafting is turned off')).toBeInTheDocument()
  })

  it('sends only after the agent confirms, with the version they saw', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    sendDraft.mockResolvedValue({ ...AI_DRAFT, status: 'SENT', sentAt: '2026-10-21T02:05:00Z', version: 1 })
    await openDraft()

    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText(/^Sent /)).toBeInTheDocument()
    expect(confirm).toHaveBeenCalledWith('Send this email to Synthetic Client?')
    expect(sendDraft).toHaveBeenCalledWith('draft-1', 0)
    expect(screen.queryByRole('button', { name: 'Send' })).not.toBeInTheDocument()
  })

  it('does not send when the agent declines', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await openDraft()

    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(sendDraft).not.toHaveBeenCalled()
  })

  it('saves unsaved edits, then sends the saved version', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    updateDraft.mockResolvedValue({ ...AI_DRAFT, subject: 'Changed subject', version: 1 })
    sendDraft.mockResolvedValue({ ...AI_DRAFT, subject: 'Changed subject', status: 'SENT', sentAt: '2026-10-21T02:05:00Z', version: 2 })
    await openDraft()

    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Changed subject' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText(/^Sent /)).toBeInTheDocument()
    expect(updateDraft).toHaveBeenCalledWith('draft-1', { subject: 'Changed subject', body: AI_DRAFT.body, version: 0 })
    expect(sendDraft).toHaveBeenCalledWith('draft-1', 1)
  })

  it('sends nothing when saving the edits fails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    updateDraft.mockRejectedValue(Object.assign(new Error('Conflict'), { status: 409 }))
    await openDraft()

    fireEvent.change(screen.getByLabelText('Subject'), { target: { value: 'Changed subject' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(sendDraft).not.toHaveBeenCalled()
  })

  it('explains a failed send', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    sendDraft.mockRejectedValue(Object.assign(new Error('Bad gateway'), { status: 502 }))
    await openDraft()

    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Nothing was sent')
  })

  it('discards a draft after confirming', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    discardDraft.mockResolvedValue(null)
    await openDraft()

    fireEvent.click(screen.getByRole('button', { name: 'Discard' }))

    await vi.waitFor(() => expect(discardDraft).toHaveBeenCalledWith('draft-1'))
  })
})
