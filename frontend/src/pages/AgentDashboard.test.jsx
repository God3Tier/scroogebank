import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { createClient, deleteClient, listClients } from '../api/clients.js'
import { getDashboard } from '../api/dashboard.js'
import AgentDashboard from './AgentDashboard.jsx'

vi.mock('../api/clients.js', () => ({ createClient: vi.fn(), listClients: vi.fn(), deleteClient: vi.fn() }))
vi.mock('../api/dashboard.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getDashboard: vi.fn(),
}))
vi.mock('../api/activities.js', () => ({
  listActivities: vi.fn().mockResolvedValue({ content: [], nextCursor: null }),
}))

function OpenedClient() {
  const { clientId } = useParams()
  return <p>Client page {clientId}</p>
}

const SYNTHETIC_CLIENT = { client_id: 'client-1', first_name: 'Synthetic', last_name: 'Client' }

function clientPage(content, totalElements = content.length) {
  return { content, page: 0, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) }
}

beforeEach(() => {
  listClients.mockResolvedValue(clientPage([SYNTHETIC_CLIENT]))
  getDashboard.mockResolvedValue({
    panels: { transactions: { status: 'OK', data: { total: 12, byStatus: { Completed: 9, Pending: 3 } } } },
  })
})

function renderDashboard() {
  render(
    <MemoryRouter initialEntries={['/agent']}>
      <Routes>
        <Route path="/agent" element={<AgentDashboard />} />
        <Route path="/clients/:clientId" element={<OpenedClient />} />
      </Routes>
    </MemoryRouter>,
  )
  fireEvent.click(screen.getByRole('button', { name: 'Create Client Profile' }))
}

// Synthetic details only.
function fillInTheForm() {
  const fields = {
    'First Name:': 'Synthetic',
    'Last Name:': 'Client',
    'Date of Birth:': '1990-05-10',
    'Email:': 'synthetic.client@example.test',
    'Phone Number:': '+6560000101',
    'Address:': '1 Example Street',
    'City:': 'Singapore',
    'State:': 'Singapore',
    'Country:': 'Singapore',
    'Postal Code:': '123456',
  }
  for (const [label, value] of Object.entries(fields)) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
  fireEvent.change(screen.getByLabelText('Gender:'), { target: { value: 'female' } })
}

describe('creating a client', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it("creates the client and opens its page, where it is verified", async () => {
    createClient.mockResolvedValue({ client_id: 'client-new' })
    renderDashboard()

    fillInTheForm()
    fireEvent.click(screen.getByRole('button', { name: 'Create Profile' }))

    expect(await screen.findByText('Client page client-new')).toBeInTheDocument()
    expect(createClient).toHaveBeenCalledWith(expect.objectContaining({
      first_name: 'Synthetic',
      gender: 'female',
      date_of_birth: '1990-05-10T00:00:00Z',
      postal_code: '123456',
    }))
  })

  it('explains a duplicate email or phone number', async () => {
    createClient.mockRejectedValue(Object.assign(new Error('duplicate client'), { status: 409 }))
    renderDashboard()

    fillInTheForm()
    fireEvent.click(screen.getByRole('button', { name: 'Create Profile' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists')
  })
})

describe('the dashboard figures and client list', () => {
  afterEach(() => {
    vi.clearAllMocks()
    vi.restoreAllMocks()
  })

  function renderOnly() {
    render(
      <MemoryRouter initialEntries={['/agent']}>
        <Routes>
          <Route path="/agent" element={<AgentDashboard />} />
          <Route path="/clients/:clientId" element={<OpenedClient />} />
        </Routes>
      </MemoryRouter>,
    )
  }

  it('shows the totals from the client list and the business dashboard', async () => {
    listClients.mockResolvedValue(clientPage([SYNTHETIC_CLIENT], 7))
    renderOnly()

    expect(await screen.findByText('7')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(getDashboard).toHaveBeenCalledWith('transactions', expect.objectContaining({ from: expect.any(String), to: expect.any(String) }))
  })

  it("lists the agent's clients from client-service", async () => {
    renderOnly()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Client Profiles' }))

    expect(await screen.findByText('Synthetic Client')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
    expect(await screen.findByText('Client page client-1')).toBeInTheDocument()
  })

  it('explains that a client with accounts cannot be deleted', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    deleteClient.mockRejectedValue(Object.assign(new Error('client still has accounts'), { status: 409 }))
    renderOnly()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Client Profiles' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('still has accounts')
    expect(deleteClient).toHaveBeenCalledWith('client-1')
  })

  it('does not delete when the agent cancels', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    renderOnly()
    fireEvent.click(screen.getByRole('button', { name: 'Manage Client Profiles' }))

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    expect(deleteClient).not.toHaveBeenCalled()
  })
})
