import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { createClient } from '../api/clients.js'
import AgentDashboard from './AgentDashboard.jsx'

vi.mock('../api/clients.js', () => ({ createClient: vi.fn() }))
vi.mock('../api/activities.js', () => ({
  listActivities: vi.fn().mockResolvedValue({ content: [], nextCursor: null }),
}))

function OpenedClient() {
  const { clientId } = useParams()
  return <p>Client page {clientId}</p>
}

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
