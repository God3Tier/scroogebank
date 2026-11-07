import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getEvent as getAlertEvent, listApprovedReports, recordEventAction } from '../api/alertEvents.js'
import { listClients } from '../api/clients.js'
import {
  createDraft, createNewsDrafts, discardDraft, getDraft, listDrafts, listEvents, rebuildNewsSummary, removeNewsReport,
  retryNewsStatus, sendDraft, updateDraft,
} from '../api/engagement.js'
import AgentDrafts from './AgentDrafts.jsx'

vi.mock('../api/clients.js', () => ({ listClients: vi.fn() }))
vi.mock('../api/alertEvents.js', () => ({ listApprovedReports: vi.fn(), getEvent: vi.fn(), recordEventAction: vi.fn() }))
vi.mock('../api/engagement.js', async (importOriginal) => ({
  ...(await importOriginal()),
  listDrafts: vi.fn(),
  getDraft: vi.fn(),
  createDraft: vi.fn(),
  updateDraft: vi.fn(),
  sendDraft: vi.fn(),
  discardDraft: vi.fn(),
  listEvents: vi.fn(),
  createNewsDrafts: vi.fn(),
  removeNewsReport: vi.fn(),
  rebuildNewsSummary: vi.fn(),
  retryNewsStatus: vi.fn(),
  previewNewsPdf: vi.fn(),
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

function renderDrafts(path = '/agent/drafts') {
  return render(<MemoryRouter initialEntries={[path]}><AgentDrafts now={NOW} /></MemoryRouter>)
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
    expect(createDraft).toHaveBeenCalledWith({
      clientId: 'client-1', purpose: 'HOLIDAY_GREETING', tone: 'WARM', eventId: null, healthTaskId: null, alertId: null,
    })
    expect(getDraft).toHaveBeenCalledWith('draft-1')
  })

  it('offers the Client Health follow-up only from its link, and News Alert in place of the alert follow-up', async () => {
    renderDrafts()

    const purposes = [...(await screen.findByLabelText('Purpose')).querySelectorAll('option')].map((option) => option.value)
    expect(purposes).not.toContain('HEALTH_CHECK_IN')
    expect(purposes).not.toContain('ALERT_FOLLOW_UP')
    expect(purposes).toContain('NEWS_ALERT')
  })

  it('says Client Health emails start on the Client Health page', async () => {
    renderDrafts()

    const notice = await screen.findByRole('note')
    expect(notice).toHaveTextContent('Client Health emails cannot be written from here.')
    expect(within(notice).getByRole('link', { name: 'Client Health' })).toHaveAttribute('href', '/agent/client-health')
  })

  it('hides the Client Health notice when opened from a task', async () => {
    listDrafts.mockResolvedValueOnce([])
    renderDrafts('/agent/drafts?purpose=HEALTH_CHECK_IN&clientId=client-1&healthTaskId=task-1')

    expect(await screen.findByText('From Client Health task task-1')).toBeInTheDocument()
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('fills the form in from a Client Health task and sends the task with the request', async () => {
    listDrafts.mockResolvedValueOnce([])
    createDraft.mockResolvedValue({ ...AI_DRAFT, purpose: 'HEALTH_CHECK_IN', healthTaskId: 'task-1' })
    renderDrafts('/agent/drafts?purpose=HEALTH_CHECK_IN&clientId=client-1&healthTaskId=task-1')

    expect(await screen.findByText('From Client Health task task-1')).toBeInTheDocument()
    expect(screen.getByLabelText('Purpose')).toHaveValue('HEALTH_CHECK_IN')
    fireEvent.click(screen.getByRole('button', { name: 'Write draft' }))

    await vi.waitFor(() => expect(createDraft).toHaveBeenCalledWith({
      clientId: 'client-1', purpose: 'HEALTH_CHECK_IN', tone: 'FORMAL', eventId: null, healthTaskId: 'task-1', alertId: null,
    }))
  })

  it('asks the agent to replace the details slot and says what they add is theirs', async () => {
    getDraft.mockResolvedValue({
      ...AI_DRAFT,
      purpose: 'ALERT_FOLLOW_UP',
      body: 'Hi Synthetic,\n\n[Add your details here]\n\nSynthetic Agent',
    })
    await openDraft()

    expect(screen.getByText(/Replace \[Add your details here\] with your reason/)).toBeInTheDocument()
    expect(screen.getByText(/sent as written and is not checked/)).toBeInTheDocument()
  })

  it('shows a sent email without its text, which is not kept', async () => {
    const sent = { ...AI_DRAFT, status: 'SENT', sentAt: '2026-10-21T02:05:00Z', subject: null, body: null,
      subjectTemplate: null, bodyTemplate: null, edited: false }
    listDrafts.mockResolvedValue([sent])
    getDraft.mockResolvedValue(sent)
    await openDraft()

    expect(screen.getByText(/text isn't kept once an email is sent/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Body')).not.toBeInTheDocument()
    expect(screen.queryByText(/could not be looked up/)).not.toBeInTheDocument()
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

  describe('News Alert drafts', () => {
    const REPORTS = [
      { alertId: 'a1', approvalId: 'p1', eventId: 'e1', issuer: 'Apple Inc', headline: 'Apple reports record revenue',
        newsDate: '2026-10-19T01:00:00Z', validUntil: '2026-10-26T01:00:00Z' },
      { alertId: 'a2', approvalId: 'p2', eventId: 'e2', issuer: 'Shell plc', headline: 'Shell raises its dividend',
        newsDate: '2026-10-19T01:00:00Z', validUntil: '2026-10-26T01:00:00Z' },
    ]
    const NEWS_DRAFT = {
      ...AI_DRAFT, id: 'news-1', purpose: 'NEWS_ALERT', tone: 'NONE', source: 'NEWS', holiday: null,
      subject: 'Your portfolio update: Apple Inc and Shell plc',
      body: 'Dear Synthetic,\n\nHere is an update on 2 companies in your portfolio.',
      news: { reports: REPORTS, summaryEdited: false, rebuildSuggested: false, maxReports: 30, sentOutSynced: false,
        unmarkedReports: [], guide: 'Where to find each report in the attached PDF (the attached PDF):', fixedText: 'Your portfolio has exposure to Apple Inc and Shell plc' },
    }

    it('opens the draft made by Notify, with its reports, the guide and the fixed text', async () => {
      listDrafts.mockResolvedValue([NEWS_DRAFT])
      getDraft.mockResolvedValue(NEWS_DRAFT)
      renderDrafts('/agent/drafts?openDraft=news-1&news=1')

      expect(await screen.findByText('1 News Alert draft was made or updated.')).toBeInTheDocument()
      expect(await screen.findByText('News Alert: built from the reports you approved')).toBeInTheDocument()
      expect(screen.getByText('News Alert (fixed)')).toBeInTheDocument()
      expect(screen.getByText('None (fixed)')).toBeInTheDocument()
      expect(screen.getByText('Apple reports record revenue', { exact: false })).toBeInTheDocument()
      expect(screen.getByText('Added to every email. You cannot change this.')).toBeInTheDocument()
      expect(screen.getByText('Added from the PDF. Updates automatically.')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Preview PDF' })).toBeInTheDocument()
      expect(screen.getAllByText(/can be sent for 5 more days/)).toHaveLength(2)
    })

    it('takes a report off and offers to rebuild a summary the agent changed', async () => {
      vi.spyOn(window, 'confirm').mockReturnValue(true)
      getDraft.mockResolvedValue({ ...NEWS_DRAFT, news: { ...NEWS_DRAFT.news, rebuildSuggested: true, summaryEdited: true } })
      listDrafts.mockResolvedValue([NEWS_DRAFT])
      removeNewsReport.mockResolvedValue({ ...NEWS_DRAFT, news: { ...NEWS_DRAFT.news, reports: [REPORTS[0]] } })
      rebuildNewsSummary.mockResolvedValue(NEWS_DRAFT)
      renderDrafts('/agent/drafts?openDraft=news-1')

      fireEvent.click(await screen.findByRole('button', { name: 'Rebuild summary' }))
      await vi.waitFor(() => expect(rebuildNewsSummary).toHaveBeenCalledWith('news-1', { keep: false }))
      expect(await screen.findByText('The summary was built again from the reports.')).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Remove the Shell plc report' }))
      await vi.waitFor(() => expect(removeNewsReport).toHaveBeenCalledWith('news-1', 'a2'))
      expect(await screen.findByText('Shell plc was taken off.')).toBeInTheDocument()
    })

    it('offers to retry the status update after a send whose Sent Out call failed', async () => {
      const sent = { ...NEWS_DRAFT, status: 'SENT', subject: null, body: null, sentAt: '2026-10-21T03:00:00Z' }
      listDrafts.mockResolvedValue([sent])
      getDraft.mockResolvedValue(sent)
      retryNewsStatus.mockResolvedValue({ ...sent, news: { ...sent.news, sentOutSynced: true } })
      renderDrafts('/agent/drafts?openDraft=news-1')

      fireEvent.click(await screen.findByRole('button', { name: 'Retry status update' }))

      await vi.waitFor(() => expect(retryNewsStatus).toHaveBeenCalledWith('news-1'))
      expect(await screen.findByText('The clients are now Sent Out.')).toBeInTheDocument()
    })

    it('lists reports that could not be marked Sent Out and offers no retry for them', async () => {
      const sent = { ...NEWS_DRAFT, status: 'SENT', subject: null, body: null, sentAt: '2026-10-21T03:00:00Z',
        news: { ...NEWS_DRAFT.news, sentOutSynced: true,
          unmarkedReports: [{ alertId: 'a1', issuer: 'Apple Inc', reason: 'NOT_AUTHORISED' }] } }
      listDrafts.mockResolvedValue([sent])
      getDraft.mockResolvedValue(sent)
      renderDrafts('/agent/drafts?openDraft=news-1')

      expect(await screen.findByText(/these reports could not be marked Sent Out: Apple Inc \(the client is no longer yours\)/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Retry status update' })).not.toBeInTheDocument()
    })

    it('makes a News Alert draft from a client and the approved reports chosen on this page', async () => {
      listDrafts.mockResolvedValue([])
      listApprovedReports.mockResolvedValue([
        { alertId: 'a1', clientRef: 'client-1', eventId: 'e1', approvalId: 'p1', issuer: 'Apple Inc', headline: 'Apple reports',
          newsDate: '2026-10-19T01:00:00Z', edited: false },
        { alertId: 'a2', clientRef: 'client-1', eventId: 'e2', approvalId: 'p2', issuer: 'Shell plc', headline: 'Shell raises',
          newsDate: '2026-10-19T01:00:00Z', edited: true },
      ])
      createNewsDrafts.mockResolvedValue({ drafts: [{ draftId: 'news-1', clientId: 'client-1', created: true, reportCount: 1, added: ['a1'] }], skipped: [] })
      getDraft.mockResolvedValue(NEWS_DRAFT)
      renderDrafts()

      fireEvent.change(await screen.findByLabelText('Purpose'), { target: { value: 'NEWS_ALERT' } })
      expect(screen.getByText(/It must be set with a status of 'Plan client briefing' and be approved by you/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'See all reports' })).toHaveAttribute('href', '/agent/alerts')
      fireEvent.change(await screen.findByLabelText('Client'), { target: { value: 'client-1' } })
      fireEvent.click(await screen.findByRole('checkbox', { name: /Shell plc/ }))
      fireEvent.click(screen.getByRole('button', { name: 'Create draft' }))

      await vi.waitFor(() => expect(createNewsDrafts).toHaveBeenCalledWith({ eventId: 'e1', alertIds: ['a1'] }))
      expect(createNewsDrafts).toHaveBeenCalledTimes(1)
    })

    async function deleteBoth(dismiss) {
      vi.spyOn(window, 'confirm').mockImplementation((question) => (question.startsWith('Do you also want') ? dismiss : true))
      listDrafts.mockResolvedValue([NEWS_DRAFT, AI_DRAFT])
      discardDraft.mockResolvedValue(null)
      renderDrafts()
      fireEvent.click(await screen.findByRole('checkbox', { name: 'Select all drafts' }))
      fireEvent.click(screen.getByRole('button', { name: 'Delete selected (2)' }))
      await vi.waitFor(() => expect(discardDraft).toHaveBeenCalledTimes(2))
    }

    it('deletes several drafts and, on Yes, dismisses the reports of the news ones', async () => {
      getAlertEvent.mockImplementation(async (eventId) => ({
        clients: eventId === 'e1' ? [{ alertId: 'a1', status: 'INFORMED', version: 3 }] : [{ alertId: 'a2', status: 'SENT_OUT', version: 4 }],
      }))
      recordEventAction.mockResolvedValue({ members: [{ alertId: 'a1', outcome: 'APPLIED' }] })
      await deleteBoth(true)

      expect(window.confirm).toHaveBeenCalledWith('Do you also want to dismiss the related reports for the 1 deleted draft?')
      await vi.waitFor(() => expect(recordEventAction).toHaveBeenCalledWith('e1', {
        decision: 'DISMISS', note: null, members: [{ alertId: 'a1', expectedVersion: 3 }],
      }))
      expect(recordEventAction).toHaveBeenCalledTimes(1)
      expect(await screen.findByText(/2 deleted\. 1 report dismissed\. 1 skipped/)).toBeInTheDocument()
    })

    it('deletes several drafts and, on No, leaves the clients in Plan client briefing', async () => {
      await deleteBoth(false)

      expect(await screen.findByText('2 deleted.')).toBeInTheDocument()
      expect(recordEventAction).not.toHaveBeenCalled()
    })
  })
})
