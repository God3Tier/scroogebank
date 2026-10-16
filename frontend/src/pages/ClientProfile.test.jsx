import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useAuth } from 'react-oidc-context'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchDocument, getClient, listDocuments, uploadDocument, verifyClient } from '../api/clients.js'
import ClientProfile from './ClientProfile.jsx'

vi.mock('react-oidc-context', () => ({ useAuth: vi.fn() }))
vi.mock('../api/clients.js', () => ({
  getClient: vi.fn(),
  listDocuments: vi.fn(),
  uploadDocument: vi.fn(),
  fetchDocument: vi.fn(),
  verifyClient: vi.fn(),
}))

const CLIENT_ID = '00000000-0000-0000-0000-000000000101'
const AGENT_SUB = 'agent-sub-0001'

// Synthetic client and documents only.
const unverified = {
  client_id: CLIENT_ID,
  first_name: 'Synthetic',
  last_name: 'Client',
  date_of_birth: '1990-05-10T00:00:00Z',
  gender: 'female',
  email: 'synthetic.client@example.test',
  phone_number: '+6560000101',
  address: '1 Example Street',
  city: 'Singapore',
  state: 'Singapore',
  country: 'Singapore',
  postal_code: '123456',
  verification_status: 'UNVERIFIED',
}

const document = {
  document_id: 'doc-0001',
  client_id: CLIENT_ID,
  content_type: 'image/png',
  size_bytes: 20480,
  uploaded_by_agent_id: AGENT_SUB,
  uploaded_at: '2026-10-13T04:00:00Z',
  verification_evidence: false,
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={[`/clients/${CLIENT_ID}`]}>
      <Routes>
        <Route path="/clients/:clientId" element={<ClientProfile />} />
      </Routes>
    </MemoryRouter>,
  )
}

async function openDocument() {
  fireEvent.click(await screen.findByRole('button', { name: /^View PNG image uploaded/ }))
  await screen.findByRole('img', { name: 'Identity document being checked' })
}

describe('client page', () => {
  beforeEach(() => {
    useAuth.mockReturnValue({ isAuthenticated: true, user: { profile: { sub: AGENT_SUB, 'cognito:groups': ['Agent'] } } })
    getClient.mockResolvedValue(unverified)
    listDocuments.mockResolvedValue([document])
    fetchDocument.mockResolvedValue(new Blob(['synthetic'], { type: 'image/png' }))
    URL.createObjectURL = vi.fn(() => 'blob:synthetic-preview')
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows the profile and its documents, and verifies nothing until the agent confirms', async () => {
    renderPage()

    expect(await screen.findByRole('heading', { name: 'Synthetic Client' })).toBeInTheDocument()
    expect(screen.getByText('Not verified')).toBeInTheDocument()
    expect(screen.getByText(/Uploading a document does not verify the client/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /^Checked: PNG image/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Verify without a document' })).toBeDisabled()
  })

  it('verifies the client with the documents the agent opened and checked', async () => {
    verifyClient.mockResolvedValue({})
    renderPage()

    await openDocument()
    expect(fetchDocument).toHaveBeenCalledWith(CLIENT_ID, 'doc-0001')
    const checkbox = screen.getByRole('checkbox', { name: /^Checked: PNG image/ })
    expect(checkbox).toBeEnabled()
    fireEvent.click(checkbox)
    expect(screen.getByRole('button', { name: 'Verify client' })).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: /I have checked the selected documents/ }))

    getClient.mockResolvedValue({
      ...unverified,
      verification_status: 'VERIFIED',
      verified_by_agent_id: AGENT_SUB,
      verified_at: '2026-10-13T05:00:00Z',
      verified_with_documents: true,
    })
    listDocuments.mockResolvedValue([{ ...document, verification_evidence: true }])
    fireEvent.click(screen.getByRole('button', { name: 'Verify client' }))

    expect(await screen.findByText(/by you, based on 1 document/)).toBeInTheDocument()
    expect(verifyClient).toHaveBeenCalledWith(CLIENT_ID, ['doc-0001'])
    expect(screen.getByText('Used for verification')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verify client' })).not.toBeInTheDocument()
    expect(screen.queryByText('Without a document')).not.toBeInTheDocument()
  })

  it('verifies without a document only when the agent confirms that, and shows it as such', async () => {
    verifyClient.mockResolvedValue({})
    renderPage()
    await screen.findByRole('heading', { name: 'Synthetic Client' })

    expect(screen.getByText(/recorded and shown as done\s+without an uploaded document/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /I have checked this client's identity without an uploaded document/ }))

    getClient.mockResolvedValue({
      ...unverified,
      verification_status: 'VERIFIED',
      verified_by_agent_id: AGENT_SUB,
      verified_at: '2026-10-13T05:00:00Z',
      verified_with_documents: false,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Verify without a document' }))

    expect(await screen.findByText(/by you, without an uploaded document/)).toBeInTheDocument()
    expect(verifyClient).toHaveBeenCalledWith(CLIENT_ID, [])
    expect(screen.getByText('Without a document')).toBeInTheDocument()
    expect(screen.getByText('Documents uploaded since are not part of this verification.')).toBeInTheDocument()
    expect(screen.queryByText('Used for verification')).not.toBeInTheDocument()
  })

  it('asks for the confirmation again when the agent switches to verifying with a document', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Synthetic Client' })
    fireEvent.click(screen.getByRole('checkbox', { name: /without an uploaded document/ }))
    expect(screen.getByRole('button', { name: 'Verify without a document' })).toBeEnabled()

    await openDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /^Checked: PNG image/ }))

    expect(screen.getByRole('checkbox', { name: /I have checked the selected documents/ })).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Verify client' })).toBeDisabled()
  })

  it('uploads a document and lists it, without verifying', async () => {
    uploadDocument.mockResolvedValue({ document_id: 'doc-0002' })
    renderPage()
    await screen.findByRole('heading', { name: 'Synthetic Client' })
    const file = new File(['synthetic'], 'synthetic.png', { type: 'image/png' })

    fireEvent.change(screen.getByLabelText(/Document file/), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(await screen.findByRole('status')).toHaveTextContent('Document uploaded')
    expect(uploadDocument).toHaveBeenCalledWith(CLIENT_ID, file)
    expect(listDocuments).toHaveBeenCalledTimes(2)
    expect(verifyClient).not.toHaveBeenCalled()
  })

  it('refuses a file over 5 MB before sending it', async () => {
    renderPage()
    await screen.findByRole('heading', { name: 'Synthetic Client' })
    const file = new File(['synthetic'], 'large.pdf', { type: 'application/pdf' })
    Object.defineProperty(file, 'size', { value: 5 * 1024 * 1024 + 1 })

    fireEvent.change(screen.getByLabelText(/Document file/), { target: { files: [file] } })
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('larger than 5 MB')
    expect(uploadDocument).not.toHaveBeenCalled()
  })

  it('explains a file type the service refuses', async () => {
    uploadDocument.mockRejectedValue(Object.assign(new Error('document must be a JPEG, PNG or PDF file'), { status: 415 }))
    renderPage()
    await screen.findByRole('heading', { name: 'Synthetic Client' })

    fireEvent.change(screen.getByLabelText(/Document file/), { target: { files: [new File(['x'], 'renamed.png')] } })
    fireEvent.click(screen.getByRole('button', { name: 'Upload' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Only JPEG, PNG or PDF files')
  })

  it('says so when the client is already verified', async () => {
    verifyClient.mockRejectedValue(Object.assign(new Error('client is already verified'), { status: 409 }))
    renderPage()
    await openDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: /^Checked: PNG image/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /I have checked the selected documents/ }))

    fireEvent.click(screen.getByRole('button', { name: 'Verify client' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already verified')
  })

  it('shows who verified the client and on which documents', async () => {
    getClient.mockResolvedValue({
      ...unverified,
      verification_status: 'VERIFIED',
      verified_by_agent_id: 'admin-sub-0009',
      verified_at: '2026-10-13T05:00:00Z',
      verified_with_documents: true,
    })
    listDocuments.mockResolvedValue([{ ...document, verification_evidence: true }])
    renderPage()

    expect(await screen.findByText('Verified')).toBeInTheDocument()
    expect(screen.getByText(/by another user \(admin-su…\), based on 1 document/)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('treats a client from before ADR-0041, stored as "verified", as verified', async () => {
    getClient.mockResolvedValue({ ...unverified, verification_status: 'verified' })
    listDocuments.mockResolvedValue([])
    renderPage()

    const panel = (await screen.findByRole('heading', { name: 'Verification' })).closest('section')
    expect(within(panel).getByRole('status')).toHaveTextContent('Verified before verification details were recorded.')
    expect(screen.queryByText('Without a document')).not.toBeInTheDocument()
  })

  it("says the client is not one of the user's when the service answers 404", async () => {
    getClient.mockRejectedValue(Object.assign(new Error('resource not found'), { status: 404 }))
    renderPage()

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('not one of your clients'))
  })
})
